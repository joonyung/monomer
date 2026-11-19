package cc.monomer.control.connector.inbound.rest

import cc.monomer.control.connector.application.ConnectorInvalidArgumentException
import cc.monomer.control.connector.application.ConnectorNotFoundException
import cc.monomer.control.connector.application.ConnectorReadUseCase
import cc.monomer.control.connector.application.ConnectorView
import cc.monomer.control.human.auth.ConnectorCatalogRequestAccess
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

@RestController
class ConnectorController(
    private val useCase: ConnectorReadUseCase,
    private val requestAccess: ConnectorCatalogRequestAccess,
) {
    @GetMapping("/v2/workspaces/{workspaceId}/connectors")
    suspend fun list(
        @PathVariable workspaceId: String,
        exchange: ServerWebExchange,
    ): ConnectorListResponse = mapErrors {
        requestAccess.requireWorkspace(exchange, workspaceId)
        ConnectorListResponse(useCase.listByWorkspace(workspaceId).map { it.toResponse() })
    }

    @GetMapping("/v2/workspaces/{workspaceId}/connectors/{connectorId}")
    suspend fun get(
        @PathVariable workspaceId: String,
        @PathVariable connectorId: String,
        exchange: ServerWebExchange,
    ): ConnectorResponse = mapErrors {
        requestAccess.requireWorkspace(exchange, workspaceId)
        useCase.get(workspaceId, connectorId).toResponse()
    }

    private inline fun <T> mapErrors(block: () -> T): T =
        try {
            block()
        } catch (error: ConnectorNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, error.message)
        } catch (error: ConnectorInvalidArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, error.message)
        }

    private fun ConnectorView.toResponse(): ConnectorResponse = ConnectorResponse(
        id = id,
        workspaceId = workspaceId,
        name = name,
        type = type,
        properties = properties,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        sample = sample,
    )

    data class ConnectorListResponse(val connectors: List<ConnectorResponse>)

    data class ConnectorResponse(
        val id: String,
        val workspaceId: String,
        val name: String,
        val type: String,
        val properties: Map<String, Any?>,
        val createdAtEpochMs: Long,
        val updatedAtEpochMs: Long,
        val sample: cc.monomer.connector.api.ConnectorSample?,
    )
}
