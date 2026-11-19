@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.semantic.inbound.rest

import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.semantic.api.SemanticGroupByOptionsRequest
import cc.monomer.semantic.api.SemanticGroupByOptionsResult
import cc.monomer.semantic.api.SemanticRenderRequest
import cc.monomer.semantic.api.SemanticRenderResult
import cc.monomer.semantic.application.SemanticQueryUseCase
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

@RestController
class SemanticQueryController(
    private val queryUseCase: SemanticQueryUseCase,
    private val authorizer: SemanticWorkspaceRequestAuthorizer,
    private val consistentSnapshotTransactionRunner: ConsistentSnapshotTransactionRunner,
) {
    @PostMapping("/v2/workspaces/{workspaceId}/semantic/query/render-sql")
    suspend fun renderSql(
        @PathVariable workspaceId: String,
        @RequestBody request: SemanticQueryHttpRequest,
        exchange: ServerWebExchange,
    ): SemanticSqlRenderHttpView {
        val workspace = workspaceId.parseResourceId(::WorkspaceId)
        val payload = request.toPayload()
        val inputs = consistentSnapshotTransactionRunner.run {
            authorizer.authorizeExplorer(exchange, workspace)
            queryUseCase.readRenderInputs(
                SemanticRenderRequest(
                    workspaceId = workspace,
                    connectorId = payload.connectorId,
                    query = payload.query,
                ),
            )
        }
        val outcome = queryUseCase.renderFromInputs(inputs)
        return when (outcome) {
            is SemanticRenderResult.Rendered -> SemanticSqlRenderHttpView.fromDomain(outcome.render)
            is SemanticRenderResult.Invalid ->
                throw semanticQueryRejected(outcome.reasons, outcome.definitionIssues, "query")
            is SemanticRenderResult.Unsupported -> throw semanticQueryUnsupported(outcome.capabilities)
            is SemanticRenderResult.MetricNotFound -> throw semanticMetricNotFound(outcome.name)
            SemanticRenderResult.NotFoundOrOutsideWorkspace ->
                throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
    }

    @PostMapping("/v2/workspaces/{workspaceId}/semantic/query/group-bys")
    suspend fun listGroupBys(
        @PathVariable workspaceId: String,
        @RequestBody request: SemanticGroupByOptionsHttpRequest,
        exchange: ServerWebExchange,
    ): SemanticGroupByOptionsHttpView {
        val workspace = workspaceId.parseResourceId(::WorkspaceId)
        val payload = request.toPayload()
        val inputs = consistentSnapshotTransactionRunner.run {
            authorizer.authorizeExplorer(exchange, workspace)
            queryUseCase.readGroupByInputs(
                SemanticGroupByOptionsRequest(
                    workspaceId = workspace,
                    connectorId = payload.connectorId,
                    metrics = payload.metrics,
                ),
            )
        }
        val outcome = queryUseCase.listGroupBysFromInputs(inputs)
        return when (outcome) {
            is SemanticGroupByOptionsResult.Listed -> SemanticGroupByOptionsHttpView.fromDomain(outcome)
            is SemanticGroupByOptionsResult.Invalid ->
                throw semanticQueryRejected(outcome.reasons, outcome.definitionIssues, "metrics")
            is SemanticGroupByOptionsResult.Unsupported -> throw semanticQueryUnsupported(outcome.capabilities)
            SemanticGroupByOptionsResult.NotFoundOrOutsideWorkspace ->
                throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
    }
}
