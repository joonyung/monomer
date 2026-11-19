@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.semantic.inbound.rest

import cc.monomer.identity.api.MutationIntent
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.PutSemanticModelCommand
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
@RequestMapping("/v2/workspaces/{workspaceId}/semantic/semantic-models")
class SemanticModelController(
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
    ): List<SemanticModelResponseDto> {
        val workspace = workspaceId.toWorkspaceId()
        authorizer.authorizeExplorer(exchange, workspace)
        return currentRead.findModels(workspace).map(SemanticModelResponseDto::fromDomain)
    }

    @GetMapping("/{semanticModelId}")
    suspend fun findOne(
        @PathVariable workspaceId: String,
        @PathVariable semanticModelId: String,
        exchange: ServerWebExchange,
    ): SemanticModelDetailResponseDto {
        val workspace = workspaceId.toWorkspaceId()
        authorizer.authorizeExplorer(exchange, workspace)
        val id = parseSemanticModelId(semanticModelId)
        val current = currentRead.findModel(workspace, id)
            ?: throw org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND)
        val change = currentRead.readModelChange(workspace, id)
        return SemanticModelDetailResponseDto.fromDomain(current, change, principalDisplayNames.ofChange(change), agentApps.ofChange(change))
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @PathVariable workspaceId: String,
        @RequestBody request: SemanticModelWriteRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        exchange: ServerWebExchange,
    ): SemanticModelWriteResponseDto {
        val workspace = workspaceId.toWorkspaceId()
        return authority.putModel(
            PutSemanticModelCommand(
                authorization = authorizer.authorizeMutation(
                    exchange = exchange,
                    workspaceId = workspace,
                    intent = MutationIntent.SEMANTIC_MODEL_CREATE,
                ),
                idempotencyKey = idempotencyKey.requiredIdempotencyKey(),
                semanticModelId = null,
                definition = request.definition.toDomain(),
                aggregateMetrics = request.aggregateMetrics.mapIndexed { index, metric -> metric.toDomain(index) },
            ),
        ).requiredHttpValue().let(SemanticModelWriteResponseDto::fromDomain)
    }

    @PutMapping("/{semanticModelId}")
    @ResponseStatus(HttpStatus.OK)
    suspend fun update(
        @PathVariable workspaceId: String,
        @PathVariable semanticModelId: String,
        @RequestBody request: SemanticModelWriteRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        exchange: ServerWebExchange,
    ): SemanticModelWriteResponseDto {
        val workspace = workspaceId.toWorkspaceId()
        return authority.putModel(
            PutSemanticModelCommand(
                authorization = authorizer.authorizeMutation(
                    exchange = exchange,
                    workspaceId = workspace,
                    intent = MutationIntent.SEMANTIC_MODEL_UPDATE,
                ),
                idempotencyKey = idempotencyKey.requiredIdempotencyKey(),
                semanticModelId = parseSemanticModelId(semanticModelId),
                definition = request.definition.toDomain(),
                aggregateMetrics = request.aggregateMetrics.mapIndexed { index, metric -> metric.toDomain(index) },
            ),
        ).requiredHttpValue().let(SemanticModelWriteResponseDto::fromDomain)
    }
    @DeleteMapping("/{semanticModelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable workspaceId: String,
        @PathVariable semanticModelId: String,
        exchange: ServerWebExchange,
    ) {
        val workspace = workspaceId.toWorkspaceId()
        authority.deleteModel(cc.monomer.semantic.api.DeleteSemanticModelCommand(
            authorization = authorizer.authorizeMutation(exchange, workspace, MutationIntent.SEMANTIC_MODEL_DELETE),
            semanticModelId = parseSemanticModelId(semanticModelId),
        )).requiredHttpValue()
    }

    @GetMapping("/{semanticModelId}/history")
    suspend fun history(
        @PathVariable workspaceId: String,
        @PathVariable semanticModelId: String,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
        exchange: ServerWebExchange,
    ): SemanticDefinitionHistoryResponseDto<SemanticModelDefinitionDto> {
        val workspace = workspaceId.toWorkspaceId()
        authorizer.authorizeExplorer(exchange, workspace)
        val id = parseSemanticModelId(semanticModelId)
        requireHistoryLimit(limit)
        val scope = "$workspace/SEMANTIC_MODEL/${id.value}"
        val history = currentRead.readModelHistory(workspace, id, decodeHistoryCursor(cursor, scope), limit)
            ?: throw org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND)
        return history.toHttp(principalDisplayNames, agentApps, scope) { snapshot ->
            SemanticModelDefinitionDto.fromDomain((snapshot as cc.monomer.semantic.api.SemanticDefinitionSnapshot.Model).definition)
        }
    }

    @PostMapping("/{semanticModelId}/history/{changeId}/restore")
    suspend fun restore(
        @PathVariable workspaceId: String,
        @PathVariable semanticModelId: String,
        @PathVariable changeId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        exchange: ServerWebExchange,
    ): SemanticModelResponseDto {
        val workspace = workspaceId.toWorkspaceId()
        return authority.restoreModel(cc.monomer.semantic.api.RestoreSemanticModelCommand(
            authorization = authorizer.authorizeMutation(exchange, workspace, MutationIntent.SEMANTIC_MODEL_UPDATE),
            idempotencyKey = idempotencyKey.requiredIdempotencyKey(),
            semanticModelId = parseSemanticModelId(semanticModelId),
            changeId = changeId.parseResourceId(),
        )).requiredHttpValue().let(SemanticModelResponseDto::fromDomain)
    }

}

private fun String.toWorkspaceId(): WorkspaceId =
    parseResourceId(::WorkspaceId)
