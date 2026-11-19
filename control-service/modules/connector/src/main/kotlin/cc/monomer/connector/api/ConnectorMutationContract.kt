package cc.monomer.connector.api

import cc.monomer.identity.api.AuthorizedMutationContext
import cc.monomer.identity.api.WorkspaceId
import kotlinx.serialization.json.JsonElement

sealed interface ConnectorMutationResult {
    data class Committed(val connector: ConnectorMutationView) : ConnectorMutationResult
    data object NotFoundOrOutsideWorkspace : ConnectorMutationResult
    data object InvalidAuthorization : ConnectorMutationResult
    data class InvalidInput(val message: String) : ConnectorMutationResult
    data object LimitExceeded : ConnectorMutationResult
    data object Conflict : ConnectorMutationResult
}

data class CreateConnectorCommand(
    val authorization: AuthorizedMutationContext,
    val connectorType: ConnectorProductType,
    val displayName: String,
    val properties: ConnectorSettingsProperties,
    val credential: ConnectorCredential,
    val mutationIdempotencyKey: String,
) {
    init { require(mutationIdempotencyKey.isNotBlank()) { "Mutation idempotency key must not be blank" } }
}

data class ConnectorSettingsPatch(val op: String, val path: String, val value: JsonElement?) {
    override fun toString(): String = "ConnectorSettingsPatch([REDACTED])"
}

data class ReviseConnectorCommand(
    val authorization: AuthorizedMutationContext,
    val connectorId: ConnectorId,
    val patch: List<ConnectorSettingsPatch>,
    val mutationIdempotencyKey: String,
) {
    init { require(mutationIdempotencyKey.isNotBlank()) { "Mutation idempotency key must not be blank" } }
}

data class RotateConnectorCredentialCommand(
    val authorization: AuthorizedMutationContext,
    val connectorId: ConnectorId,
    val credential: ConnectorCredential,
    val mutationIdempotencyKey: String,
) {
    init { require(mutationIdempotencyKey.isNotBlank()) { "Mutation idempotency key must not be blank" } }
}

data class DeleteConnectorCommand(
    val authorization: AuthorizedMutationContext,
    val connectorId: ConnectorId,
)

sealed interface ConnectorDeletionResult {
    data class InvalidInput(val message: String) : ConnectorDeletionResult
    data object Deleted : ConnectorDeletionResult
    data object NotFoundOrOutsideWorkspace : ConnectorDeletionResult
    data object InvalidAuthorization : ConnectorDeletionResult
    data object RefreshActive : ConnectorDeletionResult
}

interface ConnectorAuthorityUseCase {
    suspend fun create(command: CreateConnectorCommand): ConnectorMutationResult
    suspend fun revise(command: ReviseConnectorCommand): ConnectorMutationResult
    suspend fun rotateCredential(command: RotateConnectorCredentialCommand): ConnectorMutationResult
    suspend fun delete(command: DeleteConnectorCommand): ConnectorDeletionResult
}

enum class ConnectorDependentStateRemoval {
    REMOVED,
    REFRESH_ACTIVE,
}

/** Runs inside the Connector owner transaction; a REFRESH_ACTIVE result must leave dependent state unchanged. */
fun interface ConnectorDependentStateRemovalPort {
    fun removeInOwnerTransaction(workspaceId: WorkspaceId, connectorId: ConnectorId): ConnectorDependentStateRemoval
}
