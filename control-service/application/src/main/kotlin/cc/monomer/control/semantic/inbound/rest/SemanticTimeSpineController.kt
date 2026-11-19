@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.semantic.inbound.rest

import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.MutationIntent
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.PutSemanticTimeSpineConfigurationCommand
import cc.monomer.semantic.api.SemanticCurrentReadUseCase
import cc.monomer.semantic.api.SemanticTimeSpineUseCase
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

@RestController
@RequestMapping("/v2/workspaces/{workspaceId}/semantic/time-spines")
class SemanticTimeSpineController(
    private val timeSpines: SemanticTimeSpineUseCase,
    private val currentRead: SemanticCurrentReadUseCase,
    private val authorizer: SemanticWorkspaceRequestAuthorizer,
) {
    @GetMapping
    suspend fun findAll(
        @PathVariable workspaceId: String,
        exchange: ServerWebExchange,
    ): List<SemanticTimeSpineConfigurationResponseDto> {
        val workspace = workspaceId.toWorkspaceId()
        authorizer.authorizeExplorer(exchange, workspace)
        return currentRead.findTimeSpineConfigurations(workspace)
            .map(SemanticTimeSpineConfigurationResponseDto::fromDomain)
    }

    @PutMapping("/{connectorId}")
    @ResponseStatus(HttpStatus.OK)
    suspend fun put(
        @PathVariable workspaceId: String,
        @PathVariable connectorId: String,
        @RequestBody request: SemanticTimeSpineDefinitionRequest,
        exchange: ServerWebExchange,
    ): SemanticTimeSpineConfigurationResponseDto {
        val workspace = workspaceId.toWorkspaceId()
        val connector = connectorId.parseResourceId(::ConnectorId)
        val authorization = authorizer.authorizeMutation(
            exchange = exchange,
            workspaceId = workspace,
            intent = MutationIntent.SEMANTIC_TIME_SPINE_UPDATE,
        )
        return timeSpines.putTimeSpineConfiguration(
            PutSemanticTimeSpineConfigurationCommand(
                authorization = authorization,
                workspaceId = workspace,
                connectorId = connector,
                definition = request.toDomain(),
            ),
        ).requiredTimeSpineHttpValue().let(SemanticTimeSpineConfigurationResponseDto::fromDomain)
    }
}

private fun String.toWorkspaceId(): WorkspaceId =
    parseResourceId(::WorkspaceId)
