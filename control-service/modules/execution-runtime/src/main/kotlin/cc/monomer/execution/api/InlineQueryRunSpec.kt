package cc.monomer.execution.api

import cc.monomer.identity.api.WorkspaceId
import java.time.Instant

data class InlineQueryRunSpecCommand(
    val runId: ExecutionRunId,
    val workspaceId: WorkspaceId,
    val connector: ConnectorRuntimeBindingSpec,
    val sql: String,
    val parameters: List<QueryParameterValue>,
    val maximumRecordBatchRows: Int,
    val maximumRecordBatchBytes: Int,
    val maximumCellBytes: Int,
    val deadline: Instant,
) {
    init {
        require(sql.isNotBlank())
        requireCanonicalSqlText(sql)
        require(maximumRecordBatchRows > 0)
        require(maximumRecordBatchBytes in 1..ResultSinkBinding.MAXIMUM_FRAME_BYTES)
        require(maximumCellBytes in 1..ResultSinkBinding.MAXIMUM_CELL_BYTES)
        require(deadline.toEpochMilli() > 0)
    }
}

data class InlineQueryRunSpec(
    val canonicalRequestDigest: Sha256Digest,
    val runtimeBindings: RunRuntimeBindings,
    val assignmentTemplate: BinaryValue,
)

fun interface InlineQueryRunSpecFactory {
    fun create(command: InlineQueryRunSpecCommand): InlineQueryRunSpec
}
