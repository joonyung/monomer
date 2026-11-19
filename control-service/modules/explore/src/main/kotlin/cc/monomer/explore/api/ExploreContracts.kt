@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.explore.api

import cc.monomer.connector.api.ConnectorId
import cc.monomer.execution.api.BinaryValue
import cc.monomer.execution.api.ConnectorRuntimePropertyValue
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.ExecutableRequestKind
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ResultColumn
import cc.monomer.execution.api.ResultLogicalType
import cc.monomer.execution.api.ResultPreviewCell
import cc.monomer.execution.api.ResultPreviewStopReason
import cc.monomer.execution.api.RunRuntimeBindings
import cc.monomer.execution.api.isAllowedFor
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.SemanticQuerySpec
import java.time.Instant
import java.time.ZoneId
import java.nio.ByteBuffer
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow

@JvmInline
value class ExploreRunId(val value: Uuid) {
    init {
        value.requireUuidV7("Explore run id")
    }

    companion object {
        fun generate(): ExploreRunId = ExploreRunId(Uuid.generateV7())
    }

    override fun toString(): String = value.toString()
}

/** The request fingerprint used to make an idempotency key safe to replay. */
@JvmInline
value class ExploreDigest(val value: String) {
    init {
        require(value.matches(SHA256_PATTERN)) {
            "Explore request digest must be a lowercase sha256 digest"
        }
    }

    override fun toString(): String = value
}

/** Connector identity captured for this run. Runtime properties stay in ExecutionRun. */
data class ExploreConnectorBinding(
    val workspaceId: WorkspaceId,
    val connectorId: ConnectorId,
    val connectorType: ConnectorType,
)

data class ExploreExecutionReservation(
    val executionRequestDigest: ExploreDigest,
    val connectorProperties: Map<String, ConnectorRuntimePropertyValue>,
    val runtimeBindings: RunRuntimeBindings,
    val assignmentTemplate: BinaryValue,
    val dispatchIntentExpiresAt: Instant,
    val deadline: Instant,
) {
    init {
        require(connectorProperties.isNotEmpty())
        require(connectorProperties.keys.none(String::isBlank))
        require(!dispatchIntentExpiresAt.isAfter(deadline))
        require(deadline.isAfter(dispatchIntentExpiresAt))
        require(runtimeBindings.resultSink.isAllowedFor(ExecutableRequestKind.QUERY))
    }
}

data class StartSqlExploreRunCommand(
    val runId: ExploreRunId,
    val idempotencyKey: String,
    val ownerPrincipalId: PrincipalId,
    val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
    val agentClientId: cc.monomer.identity.api.AgentClientId?,
    val connectorBinding: ExploreConnectorBinding,
    val requestDigest: ExploreDigest,
    val executionReservation: ExploreExecutionReservation,
    val requestedAt: Instant,
) {
    init {
        require(idempotencyKey.isNotBlank())
        require(executionReservation.dispatchIntentExpiresAt.isAfter(requestedAt))
        require(executionReservation.deadline.isAfter(requestedAt))
    }

    val executionRunId: ExecutionRunId
        get() = ExecutionRunId(runId.value)
}

data class StartSemanticExploreRunCommand(
    val runId: ExploreRunId,
    val idempotencyKey: String,
    val ownerPrincipalId: PrincipalId,
    val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
    val agentClientId: cc.monomer.identity.api.AgentClientId?,
    val connectorBinding: ExploreConnectorBinding,
    val requestDigest: ExploreDigest,
    val query: SemanticQuerySpec,
    val executionReservation: ExploreExecutionReservation,
    val requestedAt: Instant,
    val outputColumnDisplays: List<cc.monomer.semantic.api.SemanticOutputColumnDisplay>?,
    val reportingTimeZone: ZoneId,
) {
    init {
        require(idempotencyKey.isNotBlank())
        require(executionReservation.dispatchIntentExpiresAt.isAfter(requestedAt))
        require(executionReservation.deadline.isAfter(requestedAt))
    }

    val executionRunId: ExecutionRunId
        get() = ExecutionRunId(runId.value)
}

enum class ExploreRunKind {
    SQL,
    SEMANTIC,
}

enum class ExploreRunState {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

enum class ExploreRunFailureCode {
    PARTITION_FILTER_REQUIRED,
    PERMISSION_DENIED,
    CONNECTION_FAILED,
    AUTHENTICATION_FAILED,
    EXECUTION_FAILED,
    EXECUTION_ORPHANED,
    CANCELLED_BY_ACTOR,
}

const val MAX_EXPLORE_FAILURE_DETAIL_LENGTH = 4_096

data class ExploreResultRetention(
    val expiresAt: Instant,
)

data class ExploreResultView(
    val runId: ExploreRunId,
    val objectKey: String,
    val objectEtag: String,
    val byteSize: ULong,
    val mediaType: String,
    val rowCount: ULong,
    val columnCount: Int,
    val retention: ExploreResultRetention,
    val ownerPrincipalId: PrincipalId,
    val activatedAt: Instant,
) {
    init {
        require(objectKey.startsWith("artifacts/"))
        require(objectKey.length <= 1_024)
        require(objectEtag.isNotBlank())
        require(byteSize > 0uL)
        require(mediaType.isNotBlank())
        require(columnCount > 0)
        require(retention.expiresAt.isAfter(activatedAt))
    }
}

/** The only object key an Explore run's result artifact may use. */
fun exploreResultObjectKey(workspaceId: WorkspaceId, runId: ExploreRunId): String =
    "artifacts/workspaces/$workspaceId/explore/runs/$runId/result.arrow"

data class ReadExploreResultCommand(
    val runId: ExploreRunId,
    val ownerPrincipalId: PrincipalId,
    val previewRowLimit: Int,
)

data class ExploreRunPageBoundary(
    val createdAt: Instant,
    val runId: ExploreRunId,
)

data class ListExploreRunsCommand(
    val workspaceId: WorkspaceId,
    val ownerPrincipalId: PrincipalId,
    val after: ExploreRunPageBoundary?,
    val limit: Int,
) {
    init {
        require(limit in 1..MAX_EXPLORE_RUN_PAGE_SIZE)
    }
}

data class ExploreRunPage(
    val runs: List<ExploreRunView>,
    val next: ExploreRunPageBoundary?,
) {
    init {
        require(runs.size <= MAX_EXPLORE_RUN_PAGE_SIZE)
        require(next == null || runs.lastOrNull()?.let { it.createdAt == next.createdAt && it.id == next.runId } == true)
    }
}

data class ExploreResultMetadata(
    val byteSize: ULong,
    val mediaType: String,
    val rowCount: ULong,
    val columnCount: Int,
    val expiresAt: Instant,
) {
    init {
        require(byteSize > 0uL)
        require(mediaType.isNotBlank())
        require(columnCount > 0)
    }
}

data class ExploreResolvedResult(
    val columns: List<ResultColumn>,
    val rows: List<List<ResultPreviewCell>>,
    val returnedRowCount: Int,
    val stopReason: ResultPreviewStopReason,
    val columnDisplays: List<cc.monomer.semantic.api.MetricDisplay?>,
)

data class ExploreResultColumn(
    val name: String,
    val logicalType: ResultLogicalType,
    val display: cc.monomer.semantic.api.MetricDisplay?,
) {
    init {
        require(name.isNotBlank())
    }
}

data class ExploreResultProjection(
    val run: ExploreRunView,
    val columns: List<ExploreResultColumn>,
    val rows: List<List<ResultPreviewCell>>,
    val totalRowCount: ULong,
    val returnedRowCount: Int,
    val truncated: Boolean,
    val expiresAt: Instant,
) {
    init {
        require(returnedRowCount == rows.size)
        require(rows.all { it.size == columns.size })
        require(returnedRowCount.toULong() <= totalRowCount)
        require(truncated == (returnedRowCount.toULong() < totalRowCount))
    }
}

sealed interface ExploreResultReadOutcome {
    data class Visible(val result: ExploreResolvedResult) : ExploreResultReadOutcome
    data object NotFound : ExploreResultReadOutcome
    data object AccessDenied : ExploreResultReadOutcome
    data object RetentionExpired : ExploreResultReadOutcome
    data object TemporarilyUnavailable : ExploreResultReadOutcome
    data object PreviewUnavailable : ExploreResultReadOutcome
}

sealed interface ExploreResultProjectionReadOutcome {
    data class Visible(val projection: ExploreResultProjection) : ExploreResultProjectionReadOutcome
    data object NotFound : ExploreResultProjectionReadOutcome
    data object AccessDenied : ExploreResultProjectionReadOutcome
    data object RetentionExpired : ExploreResultProjectionReadOutcome
    data object TemporarilyUnavailable : ExploreResultProjectionReadOutcome
    data object PreviewUnavailable : ExploreResultProjectionReadOutcome
}

data class ExploreRunView(
    val id: ExploreRunId,
    val workspaceId: WorkspaceId,
    val kind: Kind,
    val connectorBinding: ExploreConnectorBinding,
    val requestDigest: ExploreDigest,
    val state: ExploreRunState,
    val revision: Long,
    val resultAvailable: Boolean,
    val failureCode: ExploreRunFailureCode?,
    val failureDetail: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val resultMetadata: ExploreResultMetadata?,
    val ownerPrincipalId: PrincipalId,
    val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
    val agentClientId: cc.monomer.identity.api.AgentClientId?,
) {
    sealed interface Kind {
        val type: ExploreRunKind

        data object Sql : Kind {
            override val type: ExploreRunKind = ExploreRunKind.SQL
        }

        /** [reportingTimeZone] is the zone the run's SQL was rendered in; null for runs recorded before zones were kept. */
        data class Semantic(val query: SemanticQuerySpec, val reportingTimeZone: ZoneId?) : Kind {
            override val type: ExploreRunKind = ExploreRunKind.SEMANTIC
        }
    }
}

data class ReadExploreResultArtifactCommand(
    val runId: ExploreRunId,
    val ownerPrincipalId: PrincipalId,
)

data class ExploreResultArtifact(
    val contentLength: Long,
    val mediaType: String,
    val content: Flow<ByteBuffer>,
) {
    init {
        require(contentLength > 0)
        require(mediaType.isNotBlank())
    }
}

sealed interface ExploreResultArtifactReadOutcome {
    data class Visible(val artifact: ExploreResultArtifact) : ExploreResultArtifactReadOutcome
    data object NotFound : ExploreResultArtifactReadOutcome
    data object AccessDenied : ExploreResultArtifactReadOutcome
    data object RetentionExpired : ExploreResultArtifactReadOutcome
    data object TemporarilyUnavailable : ExploreResultArtifactReadOutcome
    data object ArtifactUnavailable : ExploreResultArtifactReadOutcome
}

data class ExploreResultCsv(val content: Flow<ByteBuffer>) {
    val contentLength: Long? get() = null
    val mediaType: String get() = "text/csv; charset=utf-8"
}

sealed interface ExploreResultCsvReadOutcome {
    data class Visible(val csv: ExploreResultCsv) : ExploreResultCsvReadOutcome
    data object NotFound : ExploreResultCsvReadOutcome
    data object AccessDenied : ExploreResultCsvReadOutcome
    data object RetentionExpired : ExploreResultCsvReadOutcome
    data object TemporarilyUnavailable : ExploreResultCsvReadOutcome
    data object ArtifactUnavailable : ExploreResultCsvReadOutcome
    data object UnsupportedSchema : ExploreResultCsvReadOutcome
}

data class ExploreResultDownloadLink(
    val url: String,
    val fileName: String,
    val mediaType: String,
    val byteSize: ULong,
    val expiresAt: Instant,
) {
    override fun toString(): String = "ExploreResultDownloadLink(url=<redacted>, fileName=$fileName, mediaType=$mediaType, byteSize=$byteSize, expiresAt=$expiresAt)"
}

sealed interface ExploreResultDownloadLinkReadOutcome {
    data class Visible(val link: ExploreResultDownloadLink) : ExploreResultDownloadLinkReadOutcome
    data object NotFound : ExploreResultDownloadLinkReadOutcome
    data object AccessDenied : ExploreResultDownloadLinkReadOutcome
    data object RetentionExpired : ExploreResultDownloadLinkReadOutcome
    data object TemporarilyUnavailable : ExploreResultDownloadLinkReadOutcome
    data object ArtifactUnavailable : ExploreResultDownloadLinkReadOutcome
}

sealed interface StartExploreRunOutcome {
    data class Started(val run: ExploreRunView) : StartExploreRunOutcome
    data class Existing(val run: ExploreRunView) : StartExploreRunOutcome
    data object IdempotencyConflict : StartExploreRunOutcome
    data object Conflict : StartExploreRunOutcome
    data object Backpressured : StartExploreRunOutcome
}

data class CancelExploreRunCommand(
    val workspaceId: WorkspaceId,
    val runId: ExploreRunId,
    val ownerPrincipalId: PrincipalId,
    val reasonCode: String,
    val requestedAt: Instant,
) {
    init {
        require(reasonCode.isNotBlank())
    }
}

sealed interface CancelExploreRunOutcome {
    data object Accepted : CancelExploreRunOutcome
    data object AlreadyTerminal : CancelExploreRunOutcome
    data object AccessDenied : CancelExploreRunOutcome
    data object NotFound : CancelExploreRunOutcome
    data object ConcurrentConflict : CancelExploreRunOutcome
}

class ExploreInitialAdmissionTransaction internal constructor()

class ExploreResultReadTransaction internal constructor()

interface ExploreRunUseCase {
    suspend fun start(command: StartSqlExploreRunCommand): StartExploreRunOutcome

    suspend fun start(command: StartSemanticExploreRunCommand): StartExploreRunOutcome

    suspend fun list(command: ListExploreRunsCommand): ExploreRunPage

    suspend fun readVisibleResult(command: ReadExploreResultCommand): ExploreResultReadOutcome

    suspend fun readVisibleResultProjection(
        command: ReadExploreResultCommand,
    ): ExploreResultProjectionReadOutcome

    suspend fun readVisibleResultCsv(command: ReadExploreResultArtifactCommand): ExploreResultCsvReadOutcome

    suspend fun readVisibleResultDownloadLink(command: ReadExploreResultArtifactCommand): ExploreResultDownloadLinkReadOutcome

    suspend fun readVisibleResultArtifact(
        command: ReadExploreResultArtifactCommand,
    ): ExploreResultArtifactReadOutcome
}

const val MAX_EXPLORE_RUN_PAGE_SIZE = 100

private val SHA256_PATTERN = Regex("^sha256:[0-9a-f]{64}$")

private fun Uuid.requireUuidV7(label: String) {
    require(this != Uuid.NIL) { "$label must not be nil" }
    val bytes = toByteArray()
    require(bytes[8].toInt().and(0xc0) == 0x80) {
        "$label must use the IETF UUID variant"
    }
    require(bytes[6].toInt().and(0xf0) == 0x70) {
        "$label must use UUIDv7"
    }
}
