package cc.monomer.explore.application

import cc.monomer.execution.api.ExecutionArtifactStore
import cc.monomer.explore.api.ExploreResultView
import cc.monomer.explore.domain.ExploreRun
import cc.monomer.semantic.api.resultColumns
import java.io.IOException
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.apache.arrow.flatbuf.Footer
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.FieldVector
import org.apache.arrow.vector.ipc.ArrowFileReader
import org.apache.arrow.vector.ipc.message.ArrowFooter
import org.apache.arrow.vector.types.DateUnit
import org.apache.arrow.vector.types.TimeUnit
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field

internal class UnsupportedCsvSchemaException : IOException("Arrow schema cannot be represented as CSV")
internal class CsvBudgetExceededException : IOException("CSV download exceeds its byte or memory budget")

internal class ExploreCsvEncoder(private val store: ExecutionArtifactStore) {
    suspend fun prepare(run: ExploreRun, result: ExploreResultView): Flow<ByteBuffer> =
        downloads.withPermit {
            withContext(Dispatchers.IO) {
                val footer = readFooter(result)
                val fields = footer.schema.fields
                if (fields.size != result.columnCount) throw IOException("Artifact column count mismatch")
                fields.forEach(::validateCsvField)
                val semanticKind = run.kind as? ExploreRun.Kind.Semantic
                val names = if (semanticKind != null) {
                    val projection = semanticKind.query.resultColumns()
                    require(fields.map { it.name } == projection.map { it.sqlName }) {
                        "Semantic artifact columns do not match the saved projection"
                    }
                    projection.map { it.key.value }
                } else fields.map { it.name }
                names.forEach { if (it.length > MAX_CELL_BYTES) throw CsvBudgetExceededException() }
                stream(result, fields, names)
            }
        }

    private suspend fun readFooter(result: ExploreResultView): ArrowFooter {
        if (result.byteSize > Long.MAX_VALUE.toULong()) throw IOException("Invalid artifact size")
        val size = result.byteSize.toLong()
        if (size < 18) throw IOException("Invalid Arrow file")
        val trailer = readBytes(result, size - 10, 10)
        if (!trailer.copyOfRange(4, 10).contentEquals("ARROW1".toByteArray())) throw IOException("Invalid Arrow magic")
        val footerLength = ByteBuffer.wrap(trailer).order(ByteOrder.LITTLE_ENDIAN).int
        if (footerLength !in 1..MAX_FOOTER_BYTES || footerLength.toLong() > size - 18) throw CsvBudgetExceededException()
        val footerStart = size - 10 - footerLength
        val flatFooter = Footer.getRootAsFooter(ByteBuffer.wrap(readBytes(result, footerStart, footerLength)))
        val schema = requireNotNull(flatFooter.schema())
        if (schema.fieldsLength() !in 1..MAX_COLUMNS || flatFooter.dictionariesLength() !in 0..MAX_COLUMNS ||
            flatFooter.recordBatchesLength() !in 0..MAX_BLOCKS) throw CsvBudgetExceededException()
        repeat(schema.fieldsLength()) { index ->
            if (requireNotNull(schema.fields(index)).childrenLength() != 0) throw UnsupportedCsvSchemaException()
        }
        val footer = ArrowFooter(flatFooter)
        var dictionaryBytes = 0L
        for (block in footer.dictionaries + footer.recordBatches) {
            if (block.metadataLength !in 1..MAX_METADATA_BYTES || block.bodyLength !in 0..MAX_BATCH_BYTES ||
                block.offset < 8 || block.offset > footerStart - block.metadataLength - block.bodyLength) {
                throw CsvBudgetExceededException()
            }
        }
        for (block in footer.dictionaries) {
            dictionaryBytes += block.bodyLength + block.metadataLength
            if (dictionaryBytes > MAX_DICTIONARY_BYTES) throw CsvBudgetExceededException()
        }
        return footer
    }

    private suspend fun readBytes(result: ExploreResultView, start: Long, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var copied = 0
        while (copied < count) {
            currentCoroutineContext().ensureActive()
            val length = minOf(RANGE_CHUNK_BYTES, count - copied)
            val chunk = store.readRange(result.objectKey, result.objectEtag, start + copied..start + copied + length - 1)
            if (chunk.size != length) throw IOException("Incomplete Arrow range")
            chunk.copyInto(bytes, copied)
            copied += length
        }
        return bytes
    }

    private fun stream(result: ExploreResultView, fields: List<Field>, names: List<String>): Flow<ByteBuffer> = flow {
        downloads.withPermit {
            val context = currentCoroutineContext()
            RootAllocator(EXPLORE_ARROW_ALLOCATOR_BYTES).use { allocator ->
                ExecutionArtifactRangeChannel(store, result.objectKey, result.objectEtag, result.byteSize.toLong(), context).use { channel ->
                    ArrowFileReader(channel, allocator).use { reader ->
                        val root = reader.vectorSchemaRoot
                        val output = CsvOutput(MAX_OUTPUT_BYTES) { emit(it) }
                        output.write(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()))
                        names.forEachIndexed { index, name ->
                            if (index > 0) output.write(COMMA)
                            output.field(name, true)
                        }
                        output.write(CRLF)
                        var rows = 0uL
                        while (reader.loadNextBatch()) {
                            context.ensureActive()
                            if (root.rowCount.toULong() > result.rowCount - rows) throw IOException("Artifact row count mismatch")
                            for (row in 0 until root.rowCount) {
                                context.ensureActive()
                                fields.forEachIndexed { index, field ->
                                    if (index > 0) output.write(COMMA)
                                    var vector = root.fieldVectors[index]
                                    var valueIndex = row
                                    if (field.dictionary != null && !vector.isNull(row)) {
                                        val dictionaryIndex = (vector.getObject(row) as Number).toLong()
                                        vector = requireNotNull(reader.lookup(field.dictionary.id)).vector
                                        if (dictionaryIndex < 0 || dictionaryIndex >= vector.valueCount) throw IOException("Invalid Arrow dictionary index")
                                        valueIndex = dictionaryIndex.toInt()
                                    }
                                    val value = if (root.fieldVectors[index].isNull(row)) null else csvScalar(vector, valueIndex, field)
                                    output.field(value, field.isCsvText())
                                }
                                output.write(CRLF)
                            }
                            rows += root.rowCount.toULong()
                            root.clear()
                        }
                        if (rows != result.rowCount) throw IOException("Artifact row count mismatch")
                        output.finish()
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO).buffer(0)

    companion object {
        private val downloads = Semaphore(4)
        internal const val MAX_OUTPUT_BYTES = 1_000_000_000L
        internal const val MAX_FOOTER_BYTES = 4 * 1024 * 1024
        private const val MAX_METADATA_BYTES = 1024 * 1024
        private const val MAX_BATCH_BYTES = 64L * 1024 * 1024
        private const val MAX_DICTIONARY_BYTES = 16L * 1024 * 1024
        private const val MAX_COLUMNS = 4096
        private const val MAX_BLOCKS = 100_000
        private const val RANGE_CHUNK_BYTES = 256 * 1024
        private val COMMA = byteArrayOf(44)
        private val CRLF = byteArrayOf(13, 10)
    }
}

private fun validateCsvField(field: Field) {
    if (field.children.isNotEmpty()) throw UnsupportedCsvSchemaException()
    if (field.name.length > MAX_CELL_BYTES) throw CsvBudgetExceededException()
    if (field.metadata["cc.monomer.logical_type"] == "DECIMAL" ||
        field.metadata["cc.monomer.physical_encoding"] == "UTF8_CANONICAL_DECIMAL") {
        require(field.type is ArrowType.Utf8 && field.metadata["cc.monomer.logical_type"] == "DECIMAL" &&
            field.metadata["cc.monomer.physical_encoding"] == "UTF8_CANONICAL_DECIMAL")
    }
    when (field.type) {
        is ArrowType.Utf8, is ArrowType.LargeUtf8, is ArrowType.Utf8View,
        is ArrowType.Bool, is ArrowType.Int, is ArrowType.Decimal, is ArrowType.FloatingPoint,
        is ArrowType.Date, is ArrowType.Time, is ArrowType.Timestamp,
        is ArrowType.Binary, is ArrowType.LargeBinary, is ArrowType.FixedSizeBinary, is ArrowType.BinaryView,
        is ArrowType.Null -> Unit
        else -> throw UnsupportedCsvSchemaException()
    }
}

private fun Field.isCsvText(): Boolean =
    (type is ArrowType.Utf8 || type is ArrowType.LargeUtf8 || type is ArrowType.Utf8View) &&
        metadata["cc.monomer.logical_type"] != "DECIMAL"

private fun csvScalar(vector: FieldVector, row: Int, field: Field): String? {
    if (vector.isNull(row)) return null
    return when (val type = field.type) {
        is ArrowType.Date -> LocalDate.ofEpochDay(
            if (type.unit == DateUnit.DAY) vector.dataBuffer.getInt(row.toLong() * 4).toLong()
            else Math.floorDiv(vector.dataBuffer.getLong(row.toLong() * 8), 86_400_000),
        ).toString()
        is ArrowType.Time -> {
            val value = if (type.bitWidth == 32) vector.dataBuffer.getInt(row.toLong() * 4).toLong()
                else vector.dataBuffer.getLong(row.toLong() * 8)
            val time = LocalTime.ofNanoOfDay(Math.multiplyExact(value, type.unit.nanosPerUnit()))
            time.format(DateTimeFormatterBuilder().appendPattern("HH:mm:ss")
                .csvFraction(type.unit.precision()).toFormatter())
        }
        is ArrowType.Timestamp -> {
            val value = vector.dataBuffer.getLong(row.toLong() * 8)
            val unitsPerSecond = 1_000_000_000 / type.unit.nanosPerUnit()
            val seconds = Math.floorDiv(value, unitsPerSecond)
            val nanos = Math.floorMod(value, unitsPerSecond) * type.unit.nanosPerUnit()
            if (type.timezone != null) DateTimeFormatterBuilder().appendInstant(type.unit.precision()).toFormatter()
                .format(Instant.ofEpochSecond(seconds, nanos))
            else LocalDateTime.ofEpochSecond(seconds, nanos.toInt(), ZoneOffset.UTC)
                .format(DateTimeFormatterBuilder().appendPattern("uuuu-MM-dd'T'HH:mm:ss")
                    .csvFraction(type.unit.precision()).toFormatter())
        }
        is ArrowType.Binary, is ArrowType.LargeBinary, is ArrowType.FixedSizeBinary, is ArrowType.BinaryView ->
            Base64.getEncoder().encodeToString(vector.getObject(row) as ByteArray)
        is ArrowType.Int -> if (!type.isSigned && type.bitWidth == 64)
            java.lang.Long.toUnsignedString(vector.dataBuffer.getLong(row.toLong() * 8)) else vector.getObject(row).toString()
        is ArrowType.FloatingPoint -> if (type.precision == org.apache.arrow.vector.types.FloatingPointPrecision.HALF)
            java.lang.Float.float16ToFloat(vector.dataBuffer.getShort(row.toLong() * 2)).toString() else vector.getObject(row).toString()
        is ArrowType.Decimal -> (vector.getObject(row) as BigDecimal).toPlainString()
        else -> vector.getObject(row).toString().also {
            if (field.metadata["cc.monomer.logical_type"] == "DECIMAL") {
                require(it.matches(CANONICAL_DECIMAL)) { "Invalid canonical decimal" }
            }
        }
    }
}

private fun DateTimeFormatterBuilder.csvFraction(precision: Int): DateTimeFormatterBuilder =
    if (precision == 0) this else appendFraction(ChronoField.NANO_OF_SECOND, precision, precision, true)

private fun TimeUnit.nanosPerUnit(): Long = when (this) {
    TimeUnit.SECOND -> 1_000_000_000L
    TimeUnit.MILLISECOND -> 1_000_000L
    TimeUnit.MICROSECOND -> 1_000L
    TimeUnit.NANOSECOND -> 1L
}

private fun TimeUnit.precision(): Int = when (this) {
    TimeUnit.SECOND -> 0
    TimeUnit.MILLISECOND -> 3
    TimeUnit.MICROSECOND -> 6
    TimeUnit.NANOSECOND -> 9
}

internal class CsvOutput(private val byteLimit: Long, private val emit: suspend (ByteBuffer) -> Unit) {
    private val chunk = ByteArray(64 * 1024)
    private var used = 0
    private var byteCount = 0L

    suspend fun field(value: String?, text: Boolean) {
        if (value == null) return
        if (value.length > MAX_CELL_BYTES) throw CsvBudgetExceededException()
        val risky = text && value.isRiskyCsvText()
        val quoted = risky || value.isEmpty() || value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }
        val encoded = buildString {
            if (quoted) append('"')
            if (risky) append('\t')
            value.forEach { if (it == '"') append("\"\"") else append(it) }
            if (quoted) append('"')
        }.toByteArray(Charsets.UTF_8)
        if (encoded.size > MAX_CELL_BYTES) throw CsvBudgetExceededException()
        write(encoded)
    }

    suspend fun write(bytes: ByteArray) {
        if (bytes.size > byteLimit - byteCount) throw CsvBudgetExceededException()
        byteCount += bytes.size
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(chunk.size - used, bytes.size - offset)
            bytes.copyInto(chunk, used, offset, offset + count)
            used += count
            offset += count
            if (used == chunk.size) finish()
        }
    }

    suspend fun finish() {
        if (used == 0) return
        emit(ByteBuffer.wrap(chunk.copyOf(used)).asReadOnlyBuffer())
        used = 0
    }
}

private fun String.isRiskyCsvText(): Boolean {
    for (character in this) {
        if (character.isISOControl()) return true
        if (character.isWhitespace() || character == '\uFEFF' || Character.getType(character) == Character.FORMAT.toInt()) continue
        return character in "=+-@＝＋－＠"
    }
    return false
}

private const val MAX_CELL_BYTES = 8 * 1024 * 1024
private val CANONICAL_DECIMAL = Regex("NaN|-?Infinity|-?(0|[1-9][0-9]*)(\\.[0-9]+)?")

/**
 * Arrow memory one result read may hold. CSV admits batch bodies up to 64 MiB plus 16 MiB of
 * dictionaries; a preview loads only the first batch, which Explore asks Execution to keep within the
 * 8 MiB run frame.
 */
internal const val EXPLORE_ARROW_ALLOCATOR_BYTES = 128L * 1024 * 1024
