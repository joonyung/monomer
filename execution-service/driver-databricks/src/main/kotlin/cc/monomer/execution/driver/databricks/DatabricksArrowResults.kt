package cc.monomer.execution.driver.databricks

import cc.monomer.execution.driver.spi.*
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.*
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.*
import org.apache.arrow.vector.ipc.ArrowFileWriter
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.apache.arrow.vector.types.DateUnit
import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.TimeUnit
import org.apache.arrow.vector.types.pojo.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.time.Instant

internal class DatabricksArrowResults(
    private val client: DatabricksStatementClient,
    private val dispatcher: CoroutineDispatcher,
    private val clock: java.time.Clock,
    private val arrowBatchSize: Int,
    private val timing: DriverOperationTiming,
) {
    suspend fun emit(id: String, snapshot: JsonNode, deadline: Long, request: QueryRequest, sink: RunResultSink): DriverPhysicalResult.Succeeded = timing.measure(DriverPhase.READ) {
        val manifest = snapshot.path("manifest")
        if (manifest.path("format").asText() != "ARROW_STREAM") throw protocolFailure("Databricks result format is invalid")
        val totalChunks = manifest.path("total_chunk_count").strictNonnegativeInt()
        val totalRows = manifest.path("total_row_count").strictNonnegativeLong().toULong()
        val declaredChunks = manifest.path("chunks")
        if (declaredChunks.isMissingNode && totalChunks != 0 || !declaredChunks.isMissingNode && (!declaredChunks.isArray || declaredChunks.size() != totalChunks)) {
            throw protocolFailure("Databricks chunk manifest is incomplete")
        }
        val columns = manifest.path("schema").path("columns")
        if (!columns.isArray || columns.isEmpty) throw protocolFailure("Databricks result schema is missing")
        if (manifest.path("schema").path("column_count").asInt(-1) != columns.size()) throw protocolFailure("Databricks result column count is inconsistent")
        val names = ResultColumn.acceptedNames(columns.map { it.path("name").asText("") })
        if (request.expectedColumnNames.isNotEmpty() && request.expectedColumnNames != names) {
            throw DatabricksFailure("RESULT_COLUMN_MISMATCH", "query result columns do not match declared columns")
        }
        val target = request.resultTarget
        if (target is QueryResultTarget.ArtifactUpload && totalRows > target.maximumArtifactRows) {
            throw DatabricksFailure("RESULT_ROW_LIMIT_EXCEEDED", "Databricks result exceeded the authorized row limit")
        }
        if (manifest.path("truncated").asBoolean(false)) throw protocolFailure("Databricks result was truncated")
        var expectedIndex = 0
        var rowOffset = 0L
        var resultResponse = snapshot.path("result")
        if (totalChunks == 0) {
            val links = resultResponse.path("external_links")
            if ((!links.isMissingNode && !links.isNull && (!links.isArray || !links.isEmpty)) || resultResponse.hasNonNull("next_chunk_index")) {
                throw protocolFailure("Databricks result contains extra chunks")
            }
        }
        var acceptedSchema: Schema? = null
        var encoder: DatabricksArrowEncoder? = null
        var upload: ArtifactUploadSink? = null
        var sequence = 1uL
        var encodedBytes = 0uL
        var emittedRows = 0uL
        suspend fun send(payload: BinaryValue) {
            if (target is QueryResultTarget.ArtifactUpload) {
                if (payload.size.toULong() > target.maximumArtifactBytes - encodedBytes) throw DatabricksFailure("RESULT_LIMIT_EXCEEDED", "Databricks result exceeded the artifact byte limit")
                if (payload.size > target.maximumMultipartPartBytes) throw DatabricksFailure("RESULT_LIMIT_EXCEEDED", "Databricks result exceeded the multipart part limit")
                timing.measure(DriverPhase.UPLOAD) {
                    val active = upload ?: sink.openObjectUpload(target.objectKey, ArrowResultManifest.ARROW_FILE_MEDIA_TYPE).also { upload = it }
                    active.append(payload)
                }
            } else timing.measure(DriverPhase.DELIVERY) { sink.emitInlineResult(InlineResultBatch.fromPayload(sequence++, payload)) }
            encodedBytes += payload.size.toULong()
        }
        try {
            while (expectedIndex < totalChunks) {
                val linkResponse = resultResponse.path("external_links")
                if (!linkResponse.isArray || linkResponse.isEmpty) throw protocolFailure("Databricks result links are missing")
                val links = linkResponse.toMutableList()
                var linkPosition = 0
                while (linkPosition < links.size) {
                    val initialLink = links[linkPosition]
                    if (expectedIndex >= totalChunks) throw protocolFailure("Databricks result contains duplicate chunks")
                    val declaration = declaredChunks[expectedIndex]
                    if (declaration.path("chunk_index").strictNonnegativeInt() != expectedIndex || declaration.path("row_offset").strictNonnegativeLong() != rowOffset) {
                        throw protocolFailure("Databricks chunk manifest is inconsistent")
                    }
                    val expectedCount = declaration.path("row_count").strictNonnegativeLong()
                    var link = initialLink
                    var refreshed = false
                    var chunkEmitted = false
                    var chunkRows = 0L
                    suspend fun refreshLink(): JsonNode {
                        val refreshedResponse = client.chunk(id, expectedIndex, deadline)
                        val refreshed = refreshedResponse.path("external_links")
                        if (!refreshed.isArray || refreshed.isEmpty) throw protocolFailure("Databricks refreshed chunk is missing")
                        val freshLinks = refreshed.toList()
                        freshLinks.forEachIndexed { position, fresh ->
                            if (fresh.path("chunk_index").strictNonnegativeInt() != expectedIndex + position) {
                                throw protocolFailure("Databricks refreshed chunk indices are inconsistent")
                            }
                        }
                        val lastRefreshedIndex = expectedIndex + freshLinks.size - 1
                        val remainingLinks = links.drop(linkPosition + 1).filter { it.path("chunk_index").strictNonnegativeInt() > lastRefreshedIndex }
                        links.subList(linkPosition, links.size).clear()
                        links.addAll(freshLinks)
                        links.addAll(remainingLinks)
                        if (remainingLinks.isEmpty()) resultResponse = refreshedResponse
                        return freshLinks.first()
                    }
                    while (true) {
                        validateLink(link, expectedIndex, rowOffset, expectedCount)
                        val headers = client.externalHeaders(link.path("http_headers"))
                        val expiration = link.path("expiration").takeIf(JsonNode::isTextual)?.asText()
                            ?: throw protocolFailure("Databricks result link expiry is missing")
                        val expired = try { Instant.parse(expiration).toEpochMilli() <= clock.millis() } catch (_: Throwable) { throw protocolFailure("Databricks result link expiry is invalid") }
                        if (expired) {
                            if (refreshed || chunkEmitted) throw protocolFailure("Databricks result link expired")
                            link = refreshLink()
                            refreshed = true
                            continue
                        }
                        val response = client.download(link.path("external_link").asText(""), headers, deadline)
                        if (response.statusCode() == 403) {
                            response.body().close()
                            if (refreshed || chunkEmitted) throw protocolFailure("Databricks result link expired")
                            link = refreshLink()
                            refreshed = true
                            continue
                        }
                        if (response.statusCode() != 200) {
                            response.body().close()
                            throw protocolFailure("Databricks result download failed")
                        }
                        val maximumInputBytes = when (target) {
                            is QueryResultTarget.ArtifactUpload -> target.maximumArtifactBytes.coerceAtMost(MAXIMUM_DOWNLOAD_BYTES.toULong()).toLong()
                            is QueryResultTarget.InlineStream -> MAXIMUM_DOWNLOAD_BYTES
                        }
                        response.body().use { body ->
                            val closeOnCancel = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
                                try { awaitCancellation() } finally { body.close() }
                            }
                            try {
                                RootAllocator(ARROW_MEMORY_BYTES).use { allocator ->
                                    ArrowStreamReader(client.boundedInput(body, deadline, maximumInputBytes), allocator).use { reader ->
                                        val inputRoot = runInterruptible(dispatcher) { reader.vectorSchemaRoot }
                                        val inputSchema = inputRoot.schema
                                        validateSchema(inputSchema, columns)
                                        if (acceptedSchema != null && inputSchema != acceptedSchema) throw protocolFailure("Databricks Arrow schema changed between chunks")
                                        acceptedSchema = inputSchema
                                        val outputSchema = Schema(inputSchema.fields.mapIndexed { i, field -> Field(names[i], field.fieldType, field.children) })
                                        if (target is QueryResultTarget.ArtifactUpload && encoder == null) {
                                            encoder = DatabricksArrowEncoder(outputSchema, target.maximumRecordBatchBytes)
                                        }
                                        var hadBatch = false
                                        while (runInterruptible(dispatcher) { reader.loadNextBatch() }) {
                                            hadBatch = true
                                            var inputOffset = 0
                                            if (inputRoot.rowCount == 0 && target is QueryResultTarget.InlineStream && !chunkEmitted) {
                                                val payload = DatabricksArrowEncoder(outputSchema, target.maximumRecordBatchBytes).use { frame ->
                                                    frame.append(inputRoot, 0, 0, target.maximumCellBytes)
                                                    frame.finishWholeFile()
                                                }
                                                send(payload)
                                                chunkEmitted = true
                                            }
                                            while (inputOffset < inputRoot.rowCount) {
                                                currentCoroutineContext().ensureActive()
                                                client.remainingMillis(deadline)
                                                val rows = boundedBatchRows(inputRoot, inputOffset,
                                                    minOf(inputRoot.rowCount - inputOffset, target.maximumRecordBatchRows, arrowBatchSize),
                                                    target.maximumRecordBatchBytes, target.maximumCellBytes)
                                                if (target is QueryResultTarget.ArtifactUpload && rows.toULong() > target.maximumArtifactRows - emittedRows) {
                                                    throw DatabricksFailure("RESULT_ROW_LIMIT_EXCEEDED", "Databricks result exceeded the authorized row limit")
                                                }
                                                val payload = if (target is QueryResultTarget.ArtifactUpload) {
                                                    requireNotNull(encoder).append(inputRoot, inputOffset, rows, target.maximumCellBytes)
                                                } else DatabricksArrowEncoder(outputSchema, target.maximumRecordBatchBytes).use { frame ->
                                                    frame.append(inputRoot, inputOffset, rows, target.maximumCellBytes)
                                                    frame.finishWholeFile()
                                                }
                                                send(payload)
                                                chunkEmitted = true
                                                inputOffset += rows
                                                chunkRows += rows
                                                emittedRows += rows.toULong()
                                            }
                                        }
                                        if (!hadBatch && target is QueryResultTarget.InlineStream && !chunkEmitted) {
                                            val payload = DatabricksArrowEncoder(outputSchema, target.maximumRecordBatchBytes).use { it.finishWholeFile() }
                                            send(payload)
                                            chunkEmitted = true
                                        }
                                    }
                                }
                            } finally { closeOnCancel.cancel() }
                        }
                        break
                    }
                    if (chunkRows != expectedCount) throw protocolFailure("Databricks chunk row count is inconsistent")
                    rowOffset = Math.addExact(rowOffset, chunkRows)
                    expectedIndex++
                    linkPosition++
                }
                if (expectedIndex < totalChunks) {
                    val next = links.last().path("next_chunk_index").asInt(resultResponse.path("next_chunk_index").asInt(-1))
                    if (next != expectedIndex) throw protocolFailure("Databricks next chunk index is inconsistent")
                    resultResponse = client.chunk(id, next, deadline)
                } else if (links.last().hasNonNull("next_chunk_index") || resultResponse.hasNonNull("next_chunk_index")) {
                    throw protocolFailure("Databricks result contains extra chunks")
                }
            }
            if (emittedRows != totalRows || rowOffset.toULong() != totalRows) throw protocolFailure("Databricks result row total is inconsistent")
            if (totalChunks == 0) {
                if (totalRows != 0uL) throw protocolFailure("Databricks result chunk manifest is incomplete")
                val emptySchema = schemaFromManifest(columns)
                validateSchema(emptySchema, columns)
                val normalizedSchema = Schema(emptySchema.fields.mapIndexed { index, field -> Field(names[index], field.fieldType, field.children) })
                if (target is QueryResultTarget.ArtifactUpload) encoder = DatabricksArrowEncoder(normalizedSchema, target.maximumRecordBatchBytes)
                else send(DatabricksArrowEncoder(normalizedSchema, target.maximumRecordBatchBytes).use { it.finishWholeFile() })
            }
            val resultManifest = if (target is QueryResultTarget.ArtifactUpload) {
                send(requireNotNull(encoder).finish())
                timing.measure(DriverPhase.UPLOAD) { requireNotNull(upload).complete(emittedRows, columns.size()) }
            } else null
            DriverPhysicalResult.Succeeded(emittedRows, encodedBytes, resultManifest)
        } catch (failure: Throwable) {
            timing.measure(DriverPhase.CLEANUP) {
                withContext(NonCancellable) { withTimeoutOrNull(5_000) { runCatching { upload?.abort() }.onFailure { timing.cleanupFailed() } } }
            }
            throw failure
        } finally { timing.measure(DriverPhase.CLEANUP) { encoder?.close() } }
    }

    private fun validateLink(link: JsonNode, index: Int, offset: Long, count: Long) {
        if (link.path("chunk_index").strictNonnegativeInt() != index || link.path("row_offset").strictNonnegativeLong() != offset || link.path("row_count").strictNonnegativeLong() != count) {
            throw protocolFailure("Databricks result link metadata is inconsistent")
        }
    }
}

internal class DatabricksArrowEncoder(schema: Schema, private val maximumBytes: Int) : AutoCloseable {
    private val allocator = RootAllocator(ARROW_MEMORY_BYTES)
    private val root = VectorSchemaRoot.create(schema, allocator)
    private val output = BoundedArrowOutput(maximumBytes)
    private val writer = ArrowFileWriter(root, null, Channels.newChannel(output))
    private var first: BinaryValue? = null
    init { writer.start() }
    fun append(input: VectorSchemaRoot, offset: Int, count: Int, maximumCellBytes: Int): BinaryValue {
        root.clear()
        root.fieldVectors.forEach { it.setInitialCapacity(count); it.allocateNew() }
        for (column in input.fieldVectors.indices) {
            val source = input.fieldVectors[column]
            val destination = root.fieldVectors[column]
            for (row in 0 until count) {
                val value = source.getObject(offset + row)
                val bytes = when (value) {
                    null -> 0
                    is org.apache.arrow.vector.util.Text -> value.length.toInt()
                    is ByteArray -> value.size
                    else -> source.field.type.let { type -> when(type) {
                        is ArrowType.Decimal -> type.bitWidth / 8
                        is ArrowType.Int -> type.bitWidth / 8
                        is ArrowType.FloatingPoint -> if(type.precision == FloatingPointPrecision.SINGLE) 4 else 8
                        is ArrowType.Bool -> 1
                        is ArrowType.Date -> 4
                        is ArrowType.Timestamp -> 8
                        else -> throw protocolFailure("Databricks result scalar is unsupported")
                    } }
                }
                if (bytes > maximumCellBytes) throw DatabricksFailure("RESULT_LIMIT_EXCEEDED", "Databricks result exceeded the cell byte limit")
                destination.copyFromSafe(offset + row, row, source)
            }
            destination.valueCount = count
        }
        root.rowCount = count
        writer.writeBatch()
        return output.drain().also { if (first == null) first = it }
    }
    fun finish(): BinaryValue { writer.end(); return output.drain() }
    fun finishWholeFile(): BinaryValue {
        val head = first
        val tail = finish()
        if (head == null) return tail
        if (head.size > maximumBytes - tail.size) throw DatabricksFailure("RESULT_LIMIT_EXCEEDED", "Databricks result exceeded the record batch byte limit")
        val combined = head.copyBytes() + tail.copyBytes()
        return try { BinaryValue.copyOf(combined) } finally { combined.fill(0) }
    }
    override fun close() { runCatching(writer::close); runCatching(root::close); runCatching(allocator::close) }
}

private class BoundedArrowOutput(private val maximumBytes: Int) : OutputStream() {
    private val output = ByteArrayOutputStream(minOf(maximumBytes, 64 * 1_024))
    override fun write(value: Int) { ensure(1); output.write(value) }
    override fun write(bytes: ByteArray, offset: Int, count: Int) { ensure(count); output.write(bytes, offset, count) }
    private fun ensure(count: Int) { if (output.size() > maximumBytes - count) throw DatabricksFailure("RESULT_LIMIT_EXCEEDED", "Databricks result exceeded the record batch byte limit") }
    fun drain(): BinaryValue {
        val bytes = output.toByteArray()
        return try { BinaryValue.copyOf(bytes) } finally { bytes.fill(0); output.reset() }
    }
}

internal fun validateSchema(schema: Schema, columns: JsonNode) {
    if (schema.fields.size != columns.size()) throw protocolFailure("Databricks Arrow column count is inconsistent")
    schema.fields.forEachIndexed { index, field ->
        val declared = columns[index]
        if (declared.path("position").asInt(-1) != index || declared.path("name").asText("") != field.name) throw protocolFailure("Databricks Arrow column metadata is inconsistent")
        val typeName = declared.path("type_name").asText("").uppercase()
        val typeText = declared.path("type_text").asText("").uppercase()
        val accepted = when(val type = field.type) {
            is ArrowType.Utf8 -> typeName == "STRING"
            is ArrowType.Binary -> typeName == "BINARY"
            is ArrowType.Bool -> typeName == "BOOLEAN"
            is ArrowType.Int -> type.isSigned && when(type.bitWidth) { 8 -> typeName == "BYTE" || typeName == "TINYINT"; 16 -> typeName == "SHORT" || typeName == "SMALLINT"; 32 -> typeName == "INT" || typeName == "INTEGER"; 64 -> typeName == "LONG" || typeName == "BIGINT"; else -> false }
            is ArrowType.FloatingPoint -> (type.precision == FloatingPointPrecision.SINGLE && typeName == "FLOAT") || (type.precision == FloatingPointPrecision.DOUBLE && typeName == "DOUBLE")
            is ArrowType.Date -> type.unit == DateUnit.DAY && typeName == "DATE"
            is ArrowType.Timestamp -> type.unit == TimeUnit.MICROSECOND && ((typeName == "TIMESTAMP" && !type.timezone.isNullOrEmpty()) || (typeName == "TIMESTAMP_NTZ" && type.timezone.isNullOrEmpty()))
            is ArrowType.Decimal -> type.bitWidth == 128 && type.precision in 1..38 && type.scale in 0..type.precision && typeName == "DECIMAL" && typeText == "DECIMAL(${type.precision},${type.scale})"
            else -> false
        }
        if (!accepted || field.children.isNotEmpty() || field.dictionary != null) {
            throw DatabricksFailure("UNSUPPORTED_RESULT_TYPE", "Column '${field.name}' has unsupported result type '$typeText (${field.type})'")
        }
    }
}
private fun JsonNode.strictNonnegativeLong(): Long = if (isIntegralNumber && canConvertToLong() && asLong() >= 0) asLong() else throw protocolFailure("Databricks result count is invalid")
private fun JsonNode.strictNonnegativeInt(): Int = strictNonnegativeLong().also { if(it > Int.MAX_VALUE) throw protocolFailure("Databricks chunk index is invalid") }.toInt()
internal const val ARROW_MEMORY_BYTES = 32L * 1_024 * 1_024
private const val MAXIMUM_DOWNLOAD_BYTES = 64L * 1_024 * 1_024

private fun boundedBatchRows(root: VectorSchemaRoot, offset: Int, maximumRows: Int, maximumBytes: Int, maximumCellBytes: Int): Int {
    var bytes = 0L
    var rows = 0
    while (rows < maximumRows) {
        var rowBytes = 0L
        root.fieldVectors.forEach { vector ->
            val value = vector.getObject(offset + rows)
            val cellBytes = when (value) {
                null -> 0L
                is org.apache.arrow.vector.util.Text -> value.length
                is ByteArray -> value.size.toLong()
                else -> 16L
            }
            if (cellBytes > maximumCellBytes) throw DatabricksFailure("RESULT_LIMIT_EXCEEDED", "Databricks result exceeded the cell byte limit")
            rowBytes += cellBytes + 1
        }
        if (rows > 0 && bytes + rowBytes > maximumBytes / 2) break
        bytes += rowBytes
        rows++
    }
    return rows
}

private fun schemaFromManifest(columns: JsonNode): Schema = Schema(columns.map { column ->
    val type = when (column.path("type_name").asText("").uppercase()) {
        "STRING" -> ArrowType.Utf8()
        "BINARY" -> ArrowType.Binary()
        "BOOLEAN" -> ArrowType.Bool()
        "BYTE", "TINYINT" -> ArrowType.Int(8, true)
        "SHORT", "SMALLINT" -> ArrowType.Int(16, true)
        "INT", "INTEGER" -> ArrowType.Int(32, true)
        "LONG", "BIGINT" -> ArrowType.Int(64, true)
        "FLOAT" -> ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)
        "DOUBLE" -> ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)
        "DATE" -> ArrowType.Date(DateUnit.DAY)
        "TIMESTAMP" -> ArrowType.Timestamp(TimeUnit.MICROSECOND, "UTC")
        "TIMESTAMP_NTZ" -> ArrowType.Timestamp(TimeUnit.MICROSECOND, null)
        "DECIMAL" -> {
            val specification = Regex("DECIMAL\\(([0-9]+),([0-9]+)\\)").matchEntire(column.path("type_text").asText("").uppercase())
                ?: throw protocolFailure("Databricks decimal metadata is invalid")
            val precision = specification.groupValues[1].toIntOrNull() ?: throw protocolFailure("Databricks decimal metadata is invalid")
            val scale = specification.groupValues[2].toIntOrNull() ?: throw protocolFailure("Databricks decimal metadata is invalid")
            ArrowType.Decimal(precision, scale, 128)
        }
        else -> throw DatabricksFailure("UNSUPPORTED_RESULT_TYPE", "Column '${column.path("name").asText("")}' has unsupported result type '${column.path("type_text").asText("")}'")
    }
    Field(column.path("name").asText(""), FieldType.nullable(type), emptyList())
})
