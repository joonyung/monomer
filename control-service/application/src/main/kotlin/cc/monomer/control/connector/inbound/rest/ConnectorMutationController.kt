@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.connector.inbound.rest

import cc.monomer.control.human.auth.toHumanHttpException
import cc.monomer.connector.api.*
import cc.monomer.control.AdmissionRequest
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.MutationAdmissionRequest
import cc.monomer.control.auth.inbound.BearerAuthorization
import cc.monomer.control.auth.inbound.BearerCredentials
import cc.monomer.identity.api.MutationIntent
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.serialization.json.*
import kotlin.uuid.Uuid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

@RestController
class ConnectorMutationController(
    private val connectorAuthorityUseCase: ConnectorAuthorityUseCase,
    private val controlAdmissionFacade: ControlAdmissionFacade,
) {
    @PostMapping("/v2/workspaces/{workspaceId}/connectors", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun create(
        @PathVariable workspaceId: String,
        @RequestHeader(name = "Idempotency-Key") mutationIdempotencyKey: String,
        @RequestBody body: String,
        exchange: ServerWebExchange,
    ): ResponseEntity<Map<String, Any?>> {
        val authorization = authorize(exchange, parseWorkspaceId(workspaceId), MutationIntent.CONNECTOR_CREATE)
        val command = parseInput {
            val input = strictObject(body, setOf("connectorType", "displayName", "properties", "credential"))
            val type = ConnectorProductType.valueOf(input.string("connectorType"))
            CreateConnectorCommand(
                authorization = authorization,
                connectorType = type,
                displayName = input.string("displayName"),
                properties = ConnectorSettingsCodec.decode(type, input.getValue("properties").jsonObject),
                credential = ConnectorCredentialCodec.decode(input.getValue("credential").jsonObject),
                mutationIdempotencyKey = requireIdempotencyKey(mutationIdempotencyKey),
            )
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(committed(connectorAuthorityUseCase.create(command)).toResponse())
    }

    @PutMapping("/v2/workspaces/{workspaceId}/connectors/{connectorId}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun revise(
        @PathVariable workspaceId: String,
        @PathVariable connectorId: String,
        @RequestHeader(name = "Idempotency-Key") mutationIdempotencyKey: String,
        @RequestBody body: String,
        exchange: ServerWebExchange,
    ): ResponseEntity<Map<String, Any?>> {
        val authorization = authorize(exchange, parseWorkspaceId(workspaceId), MutationIntent.CONNECTOR_UPDATE)
        val command = parseInput {
            val input = strictObject(body, setOf("displayName", "properties"))
            ReviseConnectorCommand(
                authorization = authorization,
                connectorId = parseConnectorId(connectorId),
                patch = listOf(ConnectorSettingsPatch("replace", "", input)),
                mutationIdempotencyKey = requireIdempotencyKey(mutationIdempotencyKey),
            )
        }
        return ResponseEntity.ok(committed(connectorAuthorityUseCase.revise(command)).toResponse())
    }

    @PutMapping("/v2/workspaces/{workspaceId}/connectors/{connectorId}/credentials", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun rotateCredentials(
        @PathVariable workspaceId: String,
        @PathVariable connectorId: String,
        @RequestHeader(name = "Idempotency-Key") mutationIdempotencyKey: String,
        @RequestBody body: String,
        exchange: ServerWebExchange,
    ): ResponseEntity<Void> {
        val authorization = authorize(exchange, parseWorkspaceId(workspaceId), MutationIntent.CONNECTOR_UPDATE)
        val command = parseInput {
            RotateConnectorCredentialCommand(
                authorization = authorization,
                connectorId = parseConnectorId(connectorId),
                credential = ConnectorCredentialCodec.decode(strictObject(body, null)),
                mutationIdempotencyKey = requireIdempotencyKey(mutationIdempotencyKey),
            )
        }
        committed(connectorAuthorityUseCase.rotateCredential(command))
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/v2/workspaces/{workspaceId}/connectors/{connectorId}")
    suspend fun delete(
        @PathVariable workspaceId: String,
        @PathVariable connectorId: String,
        exchange: ServerWebExchange,
    ): ResponseEntity<Void> {
        val authorization = authorize(exchange, parseWorkspaceId(workspaceId), MutationIntent.CONNECTOR_DELETE)
        return when (connectorAuthorityUseCase.delete(DeleteConnectorCommand(authorization, parseConnectorId(connectorId)))) {
            ConnectorDeletionResult.Deleted -> ResponseEntity.noContent().build()
            ConnectorDeletionResult.NotFoundOrOutsideWorkspace ->
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "Connector resource was not found.")
            ConnectorDeletionResult.InvalidAuthorization ->
                throw ResponseStatusException(HttpStatus.FORBIDDEN, "Connector mutation is not authorized.")
            ConnectorDeletionResult.RefreshActive -> throw ConnectorRefreshActiveException()
            is ConnectorDeletionResult.InvalidInput ->
                throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Sample connectors are managed by Monomer and cannot be changed.")
                    .also { it.body.setProperty("code", "INVALID_REQUEST") }
        }
    }

    private suspend fun authorize(
        exchange: ServerWebExchange,
        workspaceId: WorkspaceId,
        intent: MutationIntent,
    ) = when (
        val decision = controlAdmissionFacade.authorizeMutation(
            MutationAdmissionRequest(
                common = AdmissionRequest(
                    workspaceId = workspaceId,
                    credential = requireBearerCredential(exchange),
                    expectedOAuthResource = null,
                ),
                intent = intent,
            ),
        )
    ) {
        is ControlAdmissionDecision.Admitted -> decision.context
        is ControlAdmissionDecision.Denied -> throw admissionDenied(decision)
    }

    private fun <T> parseInput(block: () -> T): T = try {
        block()
    } catch (failure: ConnectorInputValueException) {
        throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, failure.detail)
            .also { it.body.setProperty("code", "INVALID_REQUEST") }
    } catch (_: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Connector input is invalid.")
    }
}

private fun committed(result: ConnectorMutationResult): ConnectorMutationView = when (result) {
    is ConnectorMutationResult.Committed -> result.connector
    is ConnectorMutationResult.InvalidInput -> throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, result.message)
        .also { it.body.setProperty("code", "INVALID_REQUEST") }
    ConnectorMutationResult.InvalidAuthorization -> throw ResponseStatusException(HttpStatus.FORBIDDEN, "Connector mutation is not authorized.")
    ConnectorMutationResult.NotFoundOrOutsideWorkspace -> throw ResponseStatusException(HttpStatus.NOT_FOUND, "Connector resource was not found.")
    ConnectorMutationResult.LimitExceeded -> throw ConnectorLimitExceededException()
    ConnectorMutationResult.Conflict -> throw ResponseStatusException(HttpStatus.CONFLICT, "Connector mutation conflicts with current authority.")
}

private val strictMapper = ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

private fun strictObject(body: String, fields: Set<String>?): JsonObject {
    val root = try {
        strictMapper.readTree(body).also { require(it.isObject) }
    } catch (_: Exception) {
        throw IllegalArgumentException("Connector input must be a JSON object")
    }
    val value = Json.parseToJsonElement(root.toString()).jsonObject
    require(fields == null || value.keys == fields) { "Connector input fields are invalid" }
    return value
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.let {
    require(it.isString) { "Connector field must be a string" }
    it.content
}

private fun ConnectorMutationView.toResponse(): Map<String, Any?> = mapOf(
    "connectorId" to connectorId.toString(),
    "workspaceId" to workspaceId.toString(),
    "type" to connectorType.uppercase(),
    "displayName" to displayName,
    "properties" to strictMapper.readValue(ConnectorSettingsCodec.encode(properties).toString(), Map::class.java),
    "createdAt" to createdAt.toString(),
    "updatedAt" to updatedAt.toString(),
)

private fun requireIdempotencyKey(value: String): String {
    require(value.matches(Regex("^[A-Za-z0-9._:-]{1,200}$"))) {
        "Idempotency-Key must be 1..200 characters matching [A-Za-z0-9._:-]+"
    }
    return value
}

private fun parseWorkspaceId(rawWorkspaceId: String): WorkspaceId =
    try {
        WorkspaceId.parse(rawWorkspaceId)
    } catch (_: RuntimeException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid workspace id: $rawWorkspaceId")
    }

private fun parseConnectorId(rawConnectorId: String): ConnectorId =
    try {
        ConnectorId(Uuid.parse(rawConnectorId))
    } catch (_: RuntimeException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid connector id: $rawConnectorId")
    }

private fun requireBearerCredential(exchange: ServerWebExchange): PresentedCredential =
    when (val bearer = BearerCredentials.fromFirstAuthorizationHeader(exchange.request.headers)) {
        is BearerAuthorization.Presented -> bearer.credential
        BearerAuthorization.Missing, BearerAuthorization.Malformed ->
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.")
    }

private fun admissionDenied(decision: ControlAdmissionDecision.Denied): ResponseStatusException =
    decision.toHumanHttpException()
