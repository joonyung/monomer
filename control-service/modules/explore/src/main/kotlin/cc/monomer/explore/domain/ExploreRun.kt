@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.explore.domain

import cc.monomer.explore.api.ExploreConnectorBinding
import cc.monomer.explore.api.ExploreDigest
import cc.monomer.explore.api.ExploreResultView
import cc.monomer.explore.api.ExploreResultMetadata
import cc.monomer.explore.api.ExploreRunFailureCode
import cc.monomer.explore.api.ExploreRunId
import cc.monomer.explore.api.ExploreRunKind
import cc.monomer.explore.api.ExploreRunState
import cc.monomer.explore.api.ExploreRunView
import cc.monomer.explore.api.MAX_EXPLORE_FAILURE_DETAIL_LENGTH
import cc.monomer.explore.api.StartSemanticExploreRunCommand
import cc.monomer.explore.api.StartSqlExploreRunCommand
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.SemanticQuerySpec
import java.time.Instant
import java.time.ZoneId

data class ExploreTerminalReceipt(
    val workspaceId: WorkspaceId,
    val settlementIdempotencyKey: String,
    val runId: ExploreRunId,
    val terminalState: ExploreRunState,
    val failureCode: ExploreRunFailureCode?,
    val failureMessage: String?,
    val result: ExploreResultView?,
    val settledAt: Instant,
) {
    init {
        require(settlementIdempotencyKey.isNotBlank())
        require(terminalState in TERMINAL_STATES)
        require(failureMessage == null || failureMessage.length <= MAX_EXPLORE_FAILURE_DETAIL_LENGTH)
        require(
            (terminalState == ExploreRunState.SUCCEEDED) ==
                (result != null && failureCode == null && failureMessage == null),
        )
        require(
            (terminalState != ExploreRunState.SUCCEEDED) ==
                (result == null && failureCode != null && !failureMessage.isNullOrBlank()),
        )
        require(result?.let { it.runId == runId && it.activatedAt == settledAt } != false)
    }
}

data class ExploreRun(
    val id: ExploreRunId,
    val kind: Kind,
    val idempotencyKey: String,
    val ownerPrincipalId: PrincipalId,
    val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
    val agentClientId: cc.monomer.identity.api.AgentClientId?,
    val connectorBinding: ExploreConnectorBinding,
    val requestDigest: ExploreDigest,
    val state: ExploreRunState,
    val visibleResult: ExploreResultView?,
    val failureCode: ExploreRunFailureCode?,
    val failureMessage: String?,
    val revision: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val workspaceId: WorkspaceId
        get() = connectorBinding.workspaceId

    sealed interface Kind {
        val type: ExploreRunKind

        data object Sql : Kind {
            override val type: ExploreRunKind = ExploreRunKind.SQL
        }

        data class Semantic(
            val query: SemanticQuerySpec,
            val outputColumnDisplays: List<cc.monomer.semantic.api.SemanticOutputColumnDisplay>?,
            val reportingTimeZone: ZoneId?,
        ) : Kind {
            override val type: ExploreRunKind = ExploreRunKind.SEMANTIC
        }
    }

    init {
        require(idempotencyKey.isNotBlank())
        require(revision >= 0)
        require(!updatedAt.isBefore(createdAt))
        require((state == ExploreRunState.SUCCEEDED) == (visibleResult != null))
        require(
            when (state) {
                ExploreRunState.RUNNING, ExploreRunState.SUCCEEDED ->
                    failureCode == null && failureMessage == null
                ExploreRunState.FAILED, ExploreRunState.CANCELLED ->
                    failureCode != null && !failureMessage.isNullOrBlank()
            },
        )
        require(state == ExploreRunState.RUNNING || state in TERMINAL_STATES)
        require(visibleResult?.runId == null || visibleResult.runId == id)
        cc.monomer.identity.api.requireRecordedAgentClient(credentialSurface, agentClientId)
    }

    companion object {
        fun admit(command: StartSqlExploreRunCommand, admittedAt: Instant): ExploreRun =
            ExploreRun(
                id = command.runId,
                kind = Kind.Sql,
                idempotencyKey = command.idempotencyKey,
                ownerPrincipalId = command.ownerPrincipalId,
                credentialSurface = command.credentialSurface,
                agentClientId = command.agentClientId,
                connectorBinding = command.connectorBinding,
                requestDigest = command.requestDigest,
                state = ExploreRunState.RUNNING,
                visibleResult = null,
                failureCode = null,
                failureMessage = null,
                revision = 0,
                createdAt = admittedAt,
                updatedAt = admittedAt,
            )

        fun admit(command: StartSemanticExploreRunCommand, admittedAt: Instant): ExploreRun =
            ExploreRun(
                id = command.runId,
                kind = Kind.Semantic(command.query, command.outputColumnDisplays, command.reportingTimeZone),
                idempotencyKey = command.idempotencyKey,
                ownerPrincipalId = command.ownerPrincipalId,
                credentialSurface = command.credentialSurface,
                agentClientId = command.agentClientId,
                connectorBinding = command.connectorBinding,
                requestDigest = command.requestDigest,
                state = ExploreRunState.RUNNING,
                visibleResult = null,
                failureCode = null,
                failureMessage = null,
                revision = 0,
                createdAt = admittedAt,
                updatedAt = admittedAt,
            )
    }

    fun complete(receipt: ExploreTerminalReceipt): ExploreRun {
        require(state == ExploreRunState.RUNNING)
        require(receipt.runId == id)
        require(receipt.workspaceId == workspaceId)
        return copy(
            state = receipt.terminalState,
            visibleResult = receipt.result,
            failureCode = receipt.failureCode,
            failureMessage = receipt.failureMessage,
            revision = revision + 1,
            updatedAt = receipt.settledAt,
        )
    }

    fun asView(): ExploreRunView =
        ExploreRunView(
            id = id,
            kind = when (val runKind = kind) {
                Kind.Sql -> ExploreRunView.Kind.Sql
                is Kind.Semantic -> ExploreRunView.Kind.Semantic(runKind.query, runKind.reportingTimeZone)
            },
            workspaceId = workspaceId,
            connectorBinding = connectorBinding,
            requestDigest = requestDigest,
            state = state,
            revision = revision,
            resultAvailable = visibleResult != null,
            failureCode = failureCode,
            failureDetail = failureMessage,
            createdAt = createdAt,
            updatedAt = updatedAt,
            resultMetadata = visibleResult?.asMetadata(),
            ownerPrincipalId = ownerPrincipalId,
            credentialSurface = credentialSurface,
            agentClientId = agentClientId,
        )
}

private fun ExploreResultView.asMetadata(): ExploreResultMetadata =
    ExploreResultMetadata(
        byteSize = byteSize,
        mediaType = mediaType,
        rowCount = rowCount,
        columnCount = columnCount,
        expiresAt = retention.expiresAt,
    )

val TERMINAL_STATES =
    setOf(
        ExploreRunState.SUCCEEDED,
        ExploreRunState.FAILED,
        ExploreRunState.CANCELLED,
    )
