@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.semantic.inbound.rest

import cc.monomer.identity.api.MutationIntent
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.PutMetricCommand
import cc.monomer.semantic.api.SemanticAuthorityUseCase
import cc.monomer.semantic.api.SemanticCurrentReadUseCase
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

@RestController
@RequestMapping("/v2/workspaces/{workspaceId}/semantic/metrics")
class MetricController(
    private val authority: SemanticAuthorityUseCase,
    private val currentRead: SemanticCurrentReadUseCase,
    private val authorizer: SemanticWorkspaceRequestAuthorizer,
    private val principalDisplayNames: cc.monomer.identity.api.PrincipalDisplayNames,
    private val agentApps: cc.monomer.identity.api.AgentAppDisplays,
) {
    @GetMapping
    suspend fun findAll(
        @PathVariable workspaceId: String,
        exchange: ServerWebExchange,
    ): List<MetricResponseDto> {
        val workspace = workspaceId.toWorkspaceId()
        authorizer.authorizeExplorer(exchange, workspace)
        return currentRead.findMetrics(workspace).map(MetricResponseDto::fromDomain)
    }

    @GetMapping("/{metricId}")
    suspend fun findOne(
        @PathVariable workspaceId: String,
        @PathVariable metricId: String,
        exchange: ServerWebExchange,
    ): MetricDetailResponseDto {
        val workspace = workspaceId.toWorkspaceId()
        authorizer.authorizeExplorer(exchange, workspace)
        val id = parseMetricId(metricId)
        val current = currentRead.findMetric(workspace, id)
            ?: throw org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND)
        val change = currentRead.readMetricChange(workspace, id)
        return MetricDetailResponseDto.fromDomain(current, change, principalDisplayNames.ofChange(change), agentApps.ofChange(change))
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @PathVariable workspaceId: String,
        @RequestBody request: MetricWriteRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        exchange: ServerWebExchange,
    ): MetricResponseDto {
        val workspace = workspaceId.toWorkspaceId()
        return authority.putMetric(
            PutMetricCommand(
                authorization = authorizer.authorizeMutation(
                    exchange = exchange,
                    workspaceId = workspace,
                    intent = MutationIntent.METRIC_CREATE,
                ),
                idempotencyKey = idempotencyKey.requiredIdempotencyKey(),
                metricId = null,
                definition = request.definition.toDomain(),
            ),
        ).requiredHttpValue().let(MetricResponseDto::fromDomain)
    }

    @PutMapping("/{metricId}")
    @ResponseStatus(HttpStatus.OK)
    suspend fun update(
        @PathVariable workspaceId: String,
        @PathVariable metricId: String,
        @RequestBody request: MetricWriteRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        exchange: ServerWebExchange,
    ): MetricResponseDto {
        val workspace = workspaceId.toWorkspaceId()
        return authority.putMetric(
            PutMetricCommand(
                authorization = authorizer.authorizeMutation(
                    exchange = exchange,
                    workspaceId = workspace,
                    intent = MutationIntent.METRIC_UPDATE,
                ),
                idempotencyKey = idempotencyKey.requiredIdempotencyKey(),
                metricId = parseMetricId(metricId),
                definition = request.definition.toDomain(),
            ),
        ).requiredHttpValue().let(MetricResponseDto::fromDomain)
    }
    @DeleteMapping("/{metricId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable workspaceId: String,
        @PathVariable metricId: String,
        exchange: ServerWebExchange,
    ) {
        val workspace = workspaceId.toWorkspaceId()
        authority.deleteMetric(cc.monomer.semantic.api.DeleteMetricCommand(
            authorization = authorizer.authorizeMutation(exchange, workspace, MutationIntent.METRIC_DELETE),
            metricId = parseMetricId(metricId),
        )).requiredHttpValue()
    }

    @GetMapping("/{metricId}/history")
    suspend fun history(
        @PathVariable workspaceId: String,
        @PathVariable metricId: String,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
        exchange: ServerWebExchange,
    ): SemanticDefinitionHistoryResponseDto<MetricDefinitionDto> {
        val workspace = workspaceId.toWorkspaceId()
        authorizer.authorizeExplorer(exchange, workspace)
        val id = parseMetricId(metricId)
        requireHistoryLimit(limit)
        val scope = "$workspace/METRIC/${id.value}"
        val history = currentRead.readMetricHistory(workspace, id, decodeHistoryCursor(cursor, scope), limit)
            ?: throw org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND)
        return history.toHttp(principalDisplayNames, agentApps, scope) { snapshot ->
            MetricDefinitionDto.fromDomain((snapshot as cc.monomer.semantic.api.SemanticDefinitionSnapshot.Metric).definition)
        }
    }

    @PostMapping("/{metricId}/history/{changeId}/restore")
    suspend fun restore(
        @PathVariable workspaceId: String,
        @PathVariable metricId: String,
        @PathVariable changeId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        exchange: ServerWebExchange,
    ): MetricResponseDto {
        val workspace = workspaceId.toWorkspaceId()
        return authority.restoreMetric(cc.monomer.semantic.api.RestoreMetricCommand(
            authorization = authorizer.authorizeMutation(exchange, workspace, MutationIntent.METRIC_UPDATE),
            idempotencyKey = idempotencyKey.requiredIdempotencyKey(),
            metricId = parseMetricId(metricId),
            changeId = changeId.parseResourceId(),
        )).requiredHttpValue().let(MetricResponseDto::fromDomain)
    }

}

private fun String.toWorkspaceId(): WorkspaceId =
    parseResourceId(::WorkspaceId)
