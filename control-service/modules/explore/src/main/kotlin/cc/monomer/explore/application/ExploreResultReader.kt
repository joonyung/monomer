@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.explore.application

import cc.monomer.explore.api.ExploreResultCsv
import cc.monomer.explore.api.ExploreResultCsvReadOutcome
import cc.monomer.explore.api.ExploreResultDownloadLink
import cc.monomer.explore.api.ExploreResultDownloadLinkReadOutcome
import cc.monomer.explore.api.ExploreRunState
import cc.monomer.explore.api.ExploreResultArtifact
import cc.monomer.explore.api.ExploreResultArtifactReadOutcome
import cc.monomer.explore.api.ExploreResultColumn
import cc.monomer.explore.api.ExploreResultProjection
import cc.monomer.explore.api.ExploreResultProjectionReadOutcome
import cc.monomer.explore.api.ExploreResultReadTransaction
import cc.monomer.explore.api.ExploreResolvedResult
import cc.monomer.explore.api.ExploreResultReadOutcome
import cc.monomer.explore.api.ExploreResultView
import cc.monomer.explore.api.ReadExploreResultCommand
import cc.monomer.explore.api.ReadExploreResultArtifactCommand
import cc.monomer.explore.domain.ExploreRun
import cc.monomer.semantic.api.resultColumns
import cc.monomer.execution.api.ExecutionArtifactStore
import cc.monomer.execution.api.ResultColumn
import cc.monomer.execution.api.ResultPreviewCell
import cc.monomer.execution.api.ResultPreviewStopReason
import java.io.IOException
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.apache.arrow.memory.OutOfMemoryException
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.ipc.ArrowFileReader
import org.apache.arrow.vector.ipc.InvalidArrowFileException
import org.apache.arrow.vector.ipc.SeekableReadChannel

class ExploreResultReader(
    private val resultReadTransactionPort: ExploreResultReadTransactionPort,
    private val repository: ExploreRunRepository,
    private val executionArtifactStore: ExecutionArtifactStore,
    private val clock: Clock,
) {
    suspend fun read(command: ReadExploreResultCommand): ExploreResultReadOutcome {
        require(command.previewRowLimit in 1..MAX_PREVIEW_ROWS)
        val observedAt = Instant.now(clock)
        val preparation =
            try {
                resultReadTransactionPort.inResultReadTransaction { transaction ->
                    prepareAuthorizedRead(
                        command = command,
                        observedAt = observedAt,
                        transaction = transaction,
                    )
                }
            } catch (_: ExploreResultReadTransactionConcurrentConflictException) {
                return ExploreResultReadOutcome.TemporarilyUnavailable
            }
        return when (preparation) {
            is ExploreResultReadPreparation.Outcome -> preparation.outcome
            is ExploreResultReadPreparation.Resolve ->
                resolvePreview(preparation.run, preparation.result, command.previewRowLimit)
        }
    }

    suspend fun readProjection(command: ReadExploreResultCommand): ExploreResultProjectionReadOutcome {
        require(command.previewRowLimit in 1..MAX_PREVIEW_ROWS)
        val observedAt = Instant.now(clock)
        val preparation =
            try {
                resultReadTransactionPort.inResultReadTransaction { transaction ->
                    prepareAuthorizedRead(command, observedAt, transaction)
                }
            } catch (_: ExploreResultReadTransactionConcurrentConflictException) {
                return ExploreResultProjectionReadOutcome.TemporarilyUnavailable
            }
        return when (preparation) {
            is ExploreResultReadPreparation.Outcome -> preparation.outcome.toProjectionOutcome()
            is ExploreResultReadPreparation.Resolve ->
                when (val preview = resolvePreview(preparation.run, preparation.result, command.previewRowLimit)) {
                    is ExploreResultReadOutcome.Visible -> {
                        val resolved = preview.result
                        if (resolved.rows.size.toULong() > preparation.result.rowCount) {
                            return ExploreResultProjectionReadOutcome.PreviewUnavailable
                        }
                        ExploreResultProjectionReadOutcome.Visible(
                            ExploreResultProjection(
                                run = preparation.run.asView(),
                                columns = resolved.columns.mapIndexed { index, column -> ExploreResultColumn(column.name, column.logicalType, resolved.columnDisplays[index]) },
                                rows = resolved.rows,
                                totalRowCount = preparation.result.rowCount,
                                returnedRowCount = resolved.rows.size,
                                truncated = resolved.rows.size.toULong() < preparation.result.rowCount,
                                expiresAt = preparation.result.retention.expiresAt,
                            ),
                        )
                    }
                    else -> preview.toProjectionOutcome()
                }
        }
    }

    suspend fun readArtifact(
        command: ReadExploreResultArtifactCommand,
    ): ExploreResultArtifactReadOutcome =
        when (val preparation = prepareDownload(command)) {
            is ExploreResultReadPreparation.Outcome -> preparation.outcome.toArtifactOutcome()
            is ExploreResultReadPreparation.Resolve -> resolveArtifact(preparation.result)
        }

    suspend fun readCsv(command: ReadExploreResultArtifactCommand): ExploreResultCsvReadOutcome {
        val preparation = prepareDownload(command)
        if (preparation is ExploreResultReadPreparation.Outcome) return when (preparation.outcome) {
            ExploreResultReadOutcome.NotFound -> ExploreResultCsvReadOutcome.NotFound
            ExploreResultReadOutcome.AccessDenied -> ExploreResultCsvReadOutcome.AccessDenied
            ExploreResultReadOutcome.RetentionExpired -> ExploreResultCsvReadOutcome.RetentionExpired
            ExploreResultReadOutcome.TemporarilyUnavailable -> ExploreResultCsvReadOutcome.TemporarilyUnavailable
            else -> ExploreResultCsvReadOutcome.ArtifactUnavailable
        }
        preparation as ExploreResultReadPreparation.Resolve
        if (!verifyArtifactHead(preparation.result)) return ExploreResultCsvReadOutcome.ArtifactUnavailable
        return try {
            ExploreResultCsvReadOutcome.Visible(ExploreResultCsv(ExploreCsvEncoder(executionArtifactStore).prepare(preparation.run, preparation.result)))
        } catch (_: UnsupportedCsvSchemaException) {
            ExploreResultCsvReadOutcome.UnsupportedSchema
        } catch (_: IOException) {
            ExploreResultCsvReadOutcome.ArtifactUnavailable
        } catch (_: IllegalArgumentException) {
            ExploreResultCsvReadOutcome.ArtifactUnavailable
        } catch (_: IndexOutOfBoundsException) {
            ExploreResultCsvReadOutcome.ArtifactUnavailable
        }
    }

    suspend fun readDownloadLink(command: ReadExploreResultArtifactCommand): ExploreResultDownloadLinkReadOutcome {
        val preparation = prepareDownload(command)
        if (preparation is ExploreResultReadPreparation.Outcome) return when (preparation.outcome) {
            ExploreResultReadOutcome.NotFound -> ExploreResultDownloadLinkReadOutcome.NotFound
            ExploreResultReadOutcome.AccessDenied -> ExploreResultDownloadLinkReadOutcome.AccessDenied
            ExploreResultReadOutcome.RetentionExpired -> ExploreResultDownloadLinkReadOutcome.RetentionExpired
            ExploreResultReadOutcome.TemporarilyUnavailable -> ExploreResultDownloadLinkReadOutcome.TemporarilyUnavailable
            else -> ExploreResultDownloadLinkReadOutcome.ArtifactUnavailable
        }
        preparation as ExploreResultReadPreparation.Resolve
        val result = preparation.result
        if (!verifyArtifactHead(result)) return ExploreResultDownloadLinkReadOutcome.ArtifactUnavailable
        val now = clock.instant()
        if (!result.retention.expiresAt.isAfter(now)) return ExploreResultDownloadLinkReadOutcome.RetentionExpired
        val expiresAt = minOf(now.plusSeconds(300), result.retention.expiresAt)
        val fileName = "explore-${command.runId.value}.arrow"
        val signed = executionArtifactStore.presignGet(result.objectKey, expiresAt, fileName)
        check(signed.expiresAt <= expiresAt) { "Signed artifact link exceeded owner retention" }
        return ExploreResultDownloadLinkReadOutcome.Visible(
            ExploreResultDownloadLink(signed.url, fileName, result.mediaType, result.byteSize, signed.expiresAt),
        )
    }

    private suspend fun prepareDownload(command: ReadExploreResultArtifactCommand): ExploreResultReadPreparation = try {
        resultReadTransactionPort.inResultReadTransaction { transaction ->
            prepareAuthorizedRead(ReadExploreResultCommand(command.runId, command.ownerPrincipalId, 1), clock.instant(), transaction)
        }
    } catch (_: ExploreResultReadTransactionConcurrentConflictException) {
        outcome(ExploreResultReadOutcome.TemporarilyUnavailable)
    }

    private suspend fun verifyArtifactHead(result: ExploreResultView): Boolean {
        val head = executionArtifactStore.head(result.objectKey) ?: return false
        return head.objectKey == result.objectKey && head.byteSize == result.byteSize &&
            normalizeEtag(head.etag) == normalizeEtag(result.objectEtag) && result.byteSize <= Long.MAX_VALUE.toULong()
    }

    private fun prepareAuthorizedRead(
        command: ReadExploreResultCommand,
        observedAt: Instant,
        @Suppress("UNUSED_PARAMETER") transaction: ExploreResultReadTransaction,
    ): ExploreResultReadPreparation {
        val run =
            repository.lockRunForResultRead(command.runId)
                ?: return outcome(ExploreResultReadOutcome.NotFound)
        val binding =
            repository.lockVisibleResultForRead(command.runId)
                ?: return outcome(ExploreResultReadOutcome.NotFound)
        val result = binding
        if (
            run.state != ExploreRunState.SUCCEEDED ||
            run.visibleResult != result ||
            run.ownerPrincipalId != command.ownerPrincipalId ||
            result.ownerPrincipalId != command.ownerPrincipalId
        ) {
            return outcome(ExploreResultReadOutcome.AccessDenied)
        }
        if (
            !result.retention.expiresAt.isAfter(observedAt)
        ) {
            return outcome(ExploreResultReadOutcome.RetentionExpired)
        }
        return ExploreResultReadPreparation.Resolve(run, result)
    }

    private suspend fun resolvePreview(
        run: ExploreRun,
        result: ExploreResultView,
        rowLimit: Int,
    ): ExploreResultReadOutcome {
        val head = executionArtifactStore.head(result.objectKey)
            ?: return ExploreResultReadOutcome.PreviewUnavailable
        if (
            head.objectKey != result.objectKey ||
            head.byteSize != result.byteSize ||
            normalizeEtag(head.etag) != normalizeEtag(result.objectEtag)
        ) {
            return ExploreResultReadOutcome.PreviewUnavailable
        }
        return try {
            val sample = readFirstBatch(result.objectKey, result.objectEtag, head.byteSize.toLong(), rowLimit)
            val semanticKind = run.kind as? ExploreRun.Kind.Semantic
            val columns = if (semanticKind != null) {
                val projection = semanticKind.query.resultColumns()
                require(sample.columns.map { it.name } == projection.map { it.sqlName }) {
                    "semantic artifact columns do not match the Semantic projection"
                }
                sample.columns.zip(projection) { column, output -> column.copy(name = output.key.value) }
            } else {
                sample.columns
            }
            val stopReason = when {
                sample.byteLimited -> ResultPreviewStopReason.BYTE_LIMIT
                sample.rows.size.toULong() >= result.rowCount -> ResultPreviewStopReason.END_OF_RESULT
                sample.rows.size >= rowLimit -> ResultPreviewStopReason.ROW_LIMIT
                else -> ResultPreviewStopReason.FIRST_BATCH_BOUNDARY
            }
            ExploreResultReadOutcome.Visible(
                ExploreResolvedResult(
                    columns = columns,
                    rows = sample.rows,
                    returnedRowCount = sample.rows.size,
                    stopReason = stopReason,
                    columnDisplays = cc.monomer.semantic.api.storedMetricDisplays(columns.map { it.name }, semanticKind?.outputColumnDisplays),
                ),
            )
        } catch (_: IOException) {
            ExploreResultReadOutcome.PreviewUnavailable
        } catch (_: InvalidArrowFileException) {
            ExploreResultReadOutcome.PreviewUnavailable
        } catch (_: OutOfMemoryException) {
            ExploreResultReadOutcome.PreviewUnavailable
        } catch (_: IllegalArgumentException) {
            ExploreResultReadOutcome.PreviewUnavailable
        }
    }

    private suspend fun readFirstBatch(
        objectKey: String,
        expectedEtag: String,
        objectSizeBytes: Long,
        rowLimit: Int,
    ): ArrowPreviewSample = withContext(Dispatchers.IO) {
        require(objectSizeBytes > 0)
        RootAllocator(EXPLORE_ARROW_ALLOCATOR_BYTES).use { allocator ->
            ExecutionArtifactRangeChannel(
                store = executionArtifactStore,
                objectKey = objectKey,
                expectedEtag = expectedEtag,
                objectSizeBytes = objectSizeBytes,
                coroutineContext = currentCoroutineContext(),
            ).use { rangeChannel ->
                ArrowFileReader(SeekableReadChannel(rangeChannel), allocator).use { reader ->
                    val columns = reader.vectorSchemaRoot.schema.fields.mapIndexed { index, field ->
                        require(field.name.length <= 512) { "physical result column name exceeds the preview limit" }
                        ResultColumn(
                            ordinal = index + 1,
                            name = field.name,
                            logicalType = field.toResultLogicalType(),
                            nullable = field.fieldType.isNullable,
                        )
                    }
                    val firstBlock = reader.recordBlocks.firstOrNull()
                        ?: return@withContext ArrowPreviewSample(columns, emptyList(), false)
                    reader.loadRecordBatch(firstBlock)
                    val root = reader.vectorSchemaRoot
                    val rows = mutableListOf<List<ResultPreviewCell>>()
                    var encodedBytes = 0L
                    var byteLimited = false
                    for (rowIndex in 0 until root.rowCount) {
                        if (rows.size >= rowLimit) break
                        val row =
                            root.fieldVectors.mapIndexed { index, vector ->
                                vector.toPreviewCell(rowIndex, columns[index])
                            }
                        val cellBytes = row.map { cell ->
                            canonicalPreviewText(cell)
                                ?.toByteArray(Charsets.UTF_8)
                                ?.size
                                ?.toLong() ?: 4L
                        }
                        if (cellBytes.any { it > MAX_PREVIEW_BYTES }) {
                            throw IllegalArgumentException("A preview cell exceeds the preview byte budget")
                        }
                        val rowBytes = cellBytes.sum()
                        if (encodedBytes + rowBytes > MAX_PREVIEW_BYTES) {
                            if (rows.isEmpty()) {
                                throw IllegalArgumentException("A preview row exceeds the preview byte budget")
                            }
                            byteLimited = true
                            break
                        }
                        rows += row
                        encodedBytes += rowBytes
                    }
                    return@withContext ArrowPreviewSample(columns, rows, byteLimited)
                }
            }
        }
    }

    private suspend fun resolveArtifact(
        result: ExploreResultView,
    ): ExploreResultArtifactReadOutcome {
        if (!verifyArtifactHead(result)) return ExploreResultArtifactReadOutcome.ArtifactUnavailable
        val contentLength = result.byteSize.toLong()
        return ExploreResultArtifactReadOutcome.Visible(
            ExploreResultArtifact(
                contentLength = contentLength,
                mediaType = result.mediaType,
                content = executionArtifactStore.readChunks(
                    objectKey = result.objectKey,
                    expectedEtag = result.objectEtag,
                    chunkSizeBytes = DOWNLOAD_CHUNK_BYTES,
                ).requireExactLength(contentLength),
            ),
        )
    }
}

private fun ExploreResultReadOutcome.toArtifactOutcome(): ExploreResultArtifactReadOutcome =
    when (this) {
        is ExploreResultReadOutcome.Visible -> error("Visible preview is not a preparation outcome")
        ExploreResultReadOutcome.NotFound -> ExploreResultArtifactReadOutcome.NotFound
        ExploreResultReadOutcome.AccessDenied -> ExploreResultArtifactReadOutcome.AccessDenied
        ExploreResultReadOutcome.RetentionExpired -> ExploreResultArtifactReadOutcome.RetentionExpired
        ExploreResultReadOutcome.TemporarilyUnavailable -> ExploreResultArtifactReadOutcome.TemporarilyUnavailable
        ExploreResultReadOutcome.PreviewUnavailable -> ExploreResultArtifactReadOutcome.ArtifactUnavailable
    }

private fun ExploreResultReadOutcome.toProjectionOutcome(): ExploreResultProjectionReadOutcome =
    when (this) {
        is ExploreResultReadOutcome.Visible -> error("Visible preview must be projected with its authorized run")
        ExploreResultReadOutcome.NotFound -> ExploreResultProjectionReadOutcome.NotFound
        ExploreResultReadOutcome.AccessDenied -> ExploreResultProjectionReadOutcome.AccessDenied
        ExploreResultReadOutcome.RetentionExpired -> ExploreResultProjectionReadOutcome.RetentionExpired
        ExploreResultReadOutcome.TemporarilyUnavailable -> ExploreResultProjectionReadOutcome.TemporarilyUnavailable
        ExploreResultReadOutcome.PreviewUnavailable -> ExploreResultProjectionReadOutcome.PreviewUnavailable
    }

private fun Flow<ByteBuffer>.requireExactLength(expectedBytes: Long): Flow<ByteBuffer> = flow {
    var emittedBytes = 0L
    collect { buffer ->
        val copy = buffer.asReadOnlyBuffer()
        emittedBytes += copy.remaining()
        check(emittedBytes <= expectedBytes) { "Explore result artifact exceeded its declared size" }
        emit(copy)
    }
    check(emittedBytes == expectedBytes) { "Explore result artifact ended before its declared size" }
}

private data class ArrowPreviewSample(
    val columns: List<ResultColumn>,
    val rows: List<List<ResultPreviewCell>>,
    val byteLimited: Boolean,
)

private fun normalizeEtag(value: String): String = value.trim().trim('"')

private sealed interface ExploreResultReadPreparation {
    data class Outcome(val outcome: ExploreResultReadOutcome) : ExploreResultReadPreparation
    data class Resolve(val run: ExploreRun, val result: ExploreResultView) : ExploreResultReadPreparation
}

private fun outcome(outcome: ExploreResultReadOutcome): ExploreResultReadPreparation.Outcome =
    ExploreResultReadPreparation.Outcome(outcome)

private const val MAX_PREVIEW_ROWS = 1_000
private const val MAX_PREVIEW_BYTES = 2L * 1024 * 1024
private const val DOWNLOAD_CHUNK_BYTES = 256 * 1024
