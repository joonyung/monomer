package cc.monomer.execution.api

import cc.monomer.identity.api.WorkspaceId
import java.time.Instant

data class RawSqlRunSpecCommand(
    val runId: ExecutionRunId,
    val workspaceId: WorkspaceId,
    val objectKey: String,
    val connector: ConnectorRuntimeBindingSpec,
    val request: RawSqlExecutionRequest,
    val deadline: Instant,
) {
    init {
        require(
            RawSqlExecutionRequestCanonicalizer.isCanonical(
                request,
                objectKey,
            ),
        )
        require(objectKey.isNotBlank())
        require(deadline.toEpochMilli() > 0)
    }
}

data class RawSqlRunSpec(
    val canonicalRequestDigest: Sha256Digest,
    val runtimeBindings: RunRuntimeBindings,
    val assignmentTemplate: BinaryValue,
)

fun interface RawSqlRunSpecFactory {
    fun create(command: RawSqlRunSpecCommand): RawSqlRunSpec
}
