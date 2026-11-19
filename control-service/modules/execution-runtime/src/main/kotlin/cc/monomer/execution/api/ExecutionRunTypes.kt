package cc.monomer.execution.api

import cc.monomer.identity.api.WorkspaceId
import java.time.Instant
import kotlin.uuid.Uuid

@JvmInline
value class ExecutionRunId(val value: Uuid) {
    init {
        value.requireUuidV7("execution run id")
    }

    companion object {
        fun generate(): ExecutionRunId = ExecutionRunId(Uuid.generateV7())
    }

    override fun toString(): String = value.toString()

    /** The only idempotency key a run's physical terminal outcome may carry. */
    fun terminalIdempotencyKey(): String = "$value:terminal"
}

enum class ExecutionRunState {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

fun ExecutionRunState.isPhysicalTerminal(): Boolean =
    this in
        setOf(
            ExecutionRunState.SUCCEEDED,
            ExecutionRunState.FAILED,
            ExecutionRunState.CANCELLED,
        )

enum class PhysicalOutcomeKind {
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

data class PhysicalOutcome(
    val terminalIdempotencyKey: String,
    val kind: PhysicalOutcomeKind,
    val failureCode: String?,
    val safeMessage: String?,
    val arrowResultManifest: ArrowResultManifest? = null,
) {
    init {
        require(terminalIdempotencyKey.isNotBlank())
        require(
            (kind in setOf(PhysicalOutcomeKind.SUCCEEDED, PhysicalOutcomeKind.CANCELLED)) ==
                (failureCode == null)
        )
        require(failureCode == null || failureCode.isNotBlank())
        require(safeMessage == null || safeMessage.isNotBlank())
    }
}

/**
 * The terminal result metadata sent by Execution after it completes the Arrow
 * multipart upload.  The object itself remains owned by Control's artifact
 * boundary; this value only describes the exact key that Control must verify.
 */
data class ArrowResultManifest(
    val objectKey: String,
    val byteSize: ULong,
    val objectEtag: String,
    val rowCount: ULong,
    val columnCount: Int,
    val mediaType: String,
) {
    init {
        require(objectKey.isNotBlank())
        require(byteSize > 0uL)
        require(objectEtag.isNotBlank())
        require(columnCount >= 0)
        require(mediaType.isNotBlank())
    }
}

data class ExecutionRunProgress(
    val rowsProcessed: ULong,
    val bytesProcessed: ULong,
    val phase: String,
    val observedAt: Instant,
) {
    init {
        require(phase.isNotBlank())
        require(phase == phase.trim())
    }
}

data class ResultSinkBinding(
    val schemaVersion: UInt,
    val kind: ResultSinkKind,
    val objectKey: String?,
    val maximumArtifactRows: ULong?,
    val maximumArtifactBytes: ULong?,
    val expiresAt: Instant?,
    val maximumRecordBatchRows: Int,
    val maximumFrameBytes: Int,
    val maximumCellBytes: Int,
    val maximumMultipartPartBytes: Int?,
    val bindingDigest: Sha256Digest
) {
    init {
        require(schemaVersion == 2u)
        requireResultCapacity()
    }

    private fun requireResultCapacity() {
        require(maximumRecordBatchRows > 0)
        require(maximumFrameBytes in 1..MAXIMUM_FRAME_BYTES)
        require(maximumCellBytes in 1..MAXIMUM_CELL_BYTES)
        when (kind) {
            ResultSinkKind.ARTIFACT_UPLOAD -> {
                require(!objectKey.isNullOrBlank())
                require(maximumArtifactRows != null && maximumArtifactRows > 0uL)
                require(maximumArtifactBytes != null && maximumArtifactBytes > 0uL)
                require(expiresAt != null && expiresAt.toEpochMilli() > 0)
                require(
                    maximumMultipartPartBytes != null &&
                        maximumMultipartPartBytes in maximumFrameBytes..MAXIMUM_MULTIPART_PART_BYTES,
                )
            }
            ResultSinkKind.INLINE_STREAM -> {
                require(objectKey == null)
                require(maximumArtifactRows == null)
                require(maximumArtifactBytes == null)
                require(expiresAt == null)
                require(maximumMultipartPartBytes == null)
            }
        }
    }

    companion object {
        const val MAXIMUM_FRAME_BYTES = 8 * 1024 * 1024
        const val MAXIMUM_CELL_BYTES = 4 * 1024 * 1024
        const val MAXIMUM_MULTIPART_PART_BYTES = 8 * 1024 * 1024
    }
}

fun ResultSinkBinding.isAllowedFor(requestKind: ExecutableRequestKind): Boolean =
    requestKind == ExecutableRequestKind.QUERY

data class RunRuntimeBindings(
    val connectorBindingDigest: Sha256Digest,
    val credentialVersion: CredentialVersionReference?,
    val credentialKeyVersion: String,
    val credentialBindingDigest: Sha256Digest,
    val resultSink: ResultSinkBinding
) {
    init {
        require(credentialKeyVersion.isNotBlank())
    }
}

data class RunCancelIntent(
    val reasonCode: String,
    val requestedAt: Instant
) {
    init {
        require(reasonCode.isNotBlank())
    }
}

data class ExecutionRunSnapshot(
    val id: ExecutionRunId,
    val owner: ExecutionOwnerReference,
    val workspaceId: WorkspaceId,
    val connectorId: ConnectorReference,
    val connectorType: ConnectorType,
    val connectorProperties: Map<String, ConnectorRuntimePropertyValue>,
    val requestKind: ExecutableRequestKind,
    val canonicalRequestDigest: Sha256Digest,
    val runtimeBindings: RunRuntimeBindings,
    val dispatchDeadlineAt: Instant,
    val deadline: Instant,
    val state: ExecutionRunState,
    val physicalOutcome: PhysicalOutcome?,
    val cancelIntent: RunCancelIntent?,
    val ownerSettledAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
    val executionOccupancyAcquiredAt: Instant?,
    val executionOccupancyReleasedAt: Instant?,
    val dispatchClaimedAt: Instant? = null,
    val lastHeartbeatAt: Instant? = null,
    val progress: ExecutionRunProgress? = null,
    val resultManifest: ArrowResultManifest? = null,
    val ownerDiscardedAt: Instant?,
) {
    init {
        require(connectorProperties.isNotEmpty())
        require(connectorProperties.keys.none(String::isBlank))
        require(version >= 0)
        require(!updatedAt.isBefore(createdAt))
        require(dispatchDeadlineAt.isAfter(createdAt))
        require(!dispatchDeadlineAt.isAfter(deadline))
        require(executionOccupancyAcquiredAt == null || dispatchClaimedAt != null)
        require(executionOccupancyReleasedAt == null || executionOccupancyAcquiredAt != null)
        require(executionOccupancyReleasedAt == null || !executionOccupancyReleasedAt.isBefore(executionOccupancyAcquiredAt))
        require(executionOccupancyReleasedAt == null || physicalOutcome != null)
        require(lastHeartbeatAt == null || dispatchClaimedAt != null)
        require(lastHeartbeatAt == null || !lastHeartbeatAt.isBefore(createdAt))
        require(dispatchClaimedAt == null || !dispatchClaimedAt.isBefore(createdAt))
        require(runtimeBindings.resultSink.isAllowedFor(requestKind))
        require((physicalOutcome != null) == state.isPhysicalTerminal())
        require(
            state != ExecutionRunState.SUCCEEDED ||
                physicalOutcome?.kind == PhysicalOutcomeKind.SUCCEEDED
        )
        require(
            runtimeBindings.resultSink.kind != ResultSinkKind.ARTIFACT_UPLOAD ||
                physicalOutcome?.kind != PhysicalOutcomeKind.SUCCEEDED ||
                resultManifest != null
        )
        require(ownerDiscardedAt == null || (physicalOutcome != null && ownerSettledAt != null))
        require(ownerDiscardedAt == null || !ownerDiscardedAt.isBefore(createdAt))
        require(ownerSettledAt == null || physicalOutcome != null)
        require(ownerSettledAt == null || !ownerSettledAt.isBefore(updatedAt))
    }

}

private fun Uuid.requireUuidV7(label: String) {
    require(this != Uuid.NIL) { "$label must not be nil" }
    val bytes = toByteArray()
    require(bytes[8].toInt().and(0xc0) == 0x80) {
        "$label must use the IETF UUID variant"
    }
    require(bytes[6].toInt().and(0xf0) == 0x70) {
        "$label must be UUIDv7"
    }
}
