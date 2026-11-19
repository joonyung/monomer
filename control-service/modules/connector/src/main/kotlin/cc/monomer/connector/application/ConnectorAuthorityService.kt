@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.connector.application

import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ContentDigest
import cc.monomer.connector.api.CredentialVersionId
import cc.monomer.connector.domain.Connector
import cc.monomer.connector.domain.ConnectorMutationKind
import cc.monomer.connector.domain.ConnectorMutationReceipt
import cc.monomer.connector.api.*
import kotlinx.serialization.json.*
import cc.monomer.connector.domain.ConnectorType
import cc.monomer.identity.api.OrganizationPlan
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.api.AuthorizedMutationContext
import cc.monomer.identity.api.MutationIntent
import cc.monomer.identity.api.WorkspaceId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi

interface ConnectorRepository : ConnectorExecutionAccessQueryPort {
    fun findConnector(connectorId: ConnectorId): Connector?

    fun countCurrentConnectors(workspaceId: WorkspaceId): Long

    fun findSampleConnector(workspaceId: WorkspaceId): Connector?

    fun insertConnector(connector: Connector)

    fun updateConnector(connector: Connector, expectedAuthorityRevision: Long): Boolean

    fun markDeleted(connector: Connector, deletedAt: Instant): Boolean

    fun findMutationReceipt(
        workspaceId: WorkspaceId,
        mutationIdempotencyKey: String,
    ): ConnectorMutationReceipt?

    fun insertMutationReceipt(receipt: ConnectorMutationReceipt)
}

/** Runs blocking Connector owner work in one transaction off the caller's thread. */
interface ConnectorTransactionPort {
    suspend fun <T> inTransaction(block: () -> T): T
}

/** Not copied by coroutine stack-trace recovery, so [cause] stays the failure that rolled the transaction back. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectorTransactionRolledBackException(cause: Throwable) :
    RuntimeException("Connector transaction rollback was confirmed", cause),
    CopyableThrowable<ConnectorTransactionRolledBackException> {
    override fun createCopy(): ConnectorTransactionRolledBackException? = null
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectorTransactionOutcomeUnknownException(cause: Throwable) :
    RuntimeException("Connector transaction outcome is unknown", cause),
    CopyableThrowable<ConnectorTransactionOutcomeUnknownException> {
    override fun createCopy(): ConnectorTransactionOutcomeUnknownException? = null
}

interface ConnectorMutationKeyLockPort {
    fun lockWorkspaceInOwnerTransaction(workspaceId: WorkspaceId)

    fun lockInOwnerTransaction(workspaceId: WorkspaceId, mutationIdempotencyKey: String)

    fun lockConnectorInOwnerTransaction(workspaceId: WorkspaceId, connectorId: ConnectorId)
}

class ConnectorAuthorityService(
    private val repository: ConnectorRepository,
    private val transactionPort: ConnectorTransactionPort,
    private val mutationKeyLockPort: ConnectorMutationKeyLockPort,
    private val credentialWritePort: ManagedCredentialWritePort,
    private val clock: Clock,
    private val searchProjection: ConnectorSearchProjectionPort,
    private val dependentStateRemoval: ConnectorDependentStateRemovalPort,
    private val workspaceSampleQuery: cc.monomer.identity.api.WorkspaceSampleQuery,
) : ConnectorAuthorityUseCase {
    override suspend fun create(command: CreateConnectorCommand): ConnectorMutationResult {
        if (!command.authorization.allows(MutationIntent.CONNECTOR_CREATE)) return ConnectorMutationResult.InvalidAuthorization
        if (command.connectorType == ConnectorProductType.DUCKDB) return ConnectorMutationResult.InvalidInput("Sample connectors are managed by Monomer")
        if (workspaceSampleQuery.readMarker(command.authorization.workspaceId) != null) return ConnectorMutationResult.InvalidInput("Real warehouse connectors cannot be added to a sample workspace")
        return createAccepted(command)
    }

    suspend fun createSample(command: CreateSampleConnectorCommand): ConnectorMutationResult {
        if (!command.authorization.allows(MutationIntent.CONNECTOR_CREATE)) return ConnectorMutationResult.InvalidAuthorization
        if (command.dataset !in setOf("acme-cloud@1", "acme-cloud@2", "acme-cloud@3")) return ConnectorMutationResult.InvalidInput("Unsupported sample dataset")
        val marker = workspaceSampleQuery.readMarker(command.authorization.workspaceId)
        if (marker?.dataset != command.dataset || marker.initiator != command.authorization.actor.id) return ConnectorMutationResult.InvalidAuthorization
        return createAccepted(CreateConnectorCommand(command.authorization, ConnectorProductType.DUCKDB,
            "Acme Cloud (sample)", ConnectorSettingsProperties.Duckdb(command.dataset),
            ConnectorCredential.SampleDatasetAccess(command.dataset), command.mutationIdempotencyKey))
    }

    private suspend fun createAccepted(command: CreateConnectorCommand): ConnectorMutationResult {
        if (!command.authorization.allows(MutationIntent.CONNECTOR_CREATE)) {
            return ConnectorMutationResult.InvalidAuthorization
        }
        try {
            connectorInput {
                require(command.displayName.isNotBlank()) { "Connector display name must not be blank" }
                require(command.properties.connectorType == command.connectorType) { "Connector property type does not match" }
                command.credential.requireCompatible(command.connectorType)
            }
        } catch (failure: ConnectorInputInvalidException) {
            return ConnectorMutationResult.InvalidInput(failure.detail)
        }
        val connectorType = ConnectorType(command.connectorType.name.lowercase())
        val properties = command.properties
        val credentialMaterial = command.credential.toMaterial()
        val requestDigest = digestCreateRequest(command, connectorType)
        val authorizationScopeDigest = digestMutationAuthorizationScope(command.authorization)
        return try {
            transactionPort.inTransaction {
                mutationKeyLockPort.lockInOwnerTransaction(
                    command.authorization.workspaceId,
                    command.mutationIdempotencyKey,
                )
                replayOrConflict(
                    workspaceId = command.authorization.workspaceId,
                    mutationIdempotencyKey = command.mutationIdempotencyKey,
                    actorId = command.authorization.actor.id,
                    mutationKind = ConnectorMutationKind.CREATE,
                    authorizationScopeDigest = authorizationScopeDigest,
                    requestDigest = requestDigest,
                    credentialMaterial = credentialMaterial,
                    connectorType = connectorType,
                )?.let { return@inTransaction it }
                mutationKeyLockPort.lockWorkspaceInOwnerTransaction(command.authorization.workspaceId)
                if (connectorType.value == "duckdb") {
                    repository.findSampleConnector(command.authorization.workspaceId)?.let { return@inTransaction ConnectorMutationResult.Committed(it.toMutationView()) }
                }
                if (command.authorization.organizationPlan == OrganizationPlan.FREE &&
                    repository.countCurrentConnectors(command.authorization.workspaceId) >= 10
                ) {
                    return@inTransaction ConnectorMutationResult.LimitExceeded
                }
                val connectorId = ConnectorId.generate()
                val credentialVersionId = CredentialVersionId.generate()
                val now = Instant.now(clock)
                val reference = credentialWritePort.writeInOwnerTransaction(
                    ManagedCredentialWriteRequest(
                        workspaceId = command.authorization.workspaceId,
                        connectorId = connectorId,
                        connectorType = connectorType.value,
                        credentialVersionId = credentialVersionId,
                        createdAt = now,
                    ),
                    credentialMaterial,
                )
                val connector = Connector(
                    id = connectorId,
                    workspaceId = command.authorization.workspaceId,
                    connectorType = connectorType,
                    displayName = command.displayName,
                    properties = properties,
                    credentialReference = reference,
                    authorityRevision = 1,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                )
                commit(
                    connector = connector,
                    expectedAuthorityRevision = null,
                    authorization = command.authorization,
                    mutationIdempotencyKey = command.mutationIdempotencyKey,
                    mutationKind = ConnectorMutationKind.CREATE,
                    authorizationScopeDigest = authorizationScopeDigest,
                    requestDigest = requestDigest,
                )
            }
        } catch (failure: ConnectorTransactionRolledBackException) {
            failure.toMutationResult()
        } catch (failure: ConnectorInputInvalidException) {
            ConnectorMutationResult.InvalidInput(failure.detail)
        } finally {
            credentialMaterial.close()
        }
    }

    override suspend fun revise(command: ReviseConnectorCommand): ConnectorMutationResult {
        if (!command.authorization.allows(MutationIntent.CONNECTOR_UPDATE)) return ConnectorMutationResult.InvalidAuthorization
        val requestDigest = sha256(buildJsonObject {
            put("connectorId", command.connectorId.toString())
            put("patch", JsonArray(command.patch.map { patch -> buildJsonObject {
                put("op", patch.op); put("path", patch.path)
                patch.value?.let { put("value", canonicalJson(it)) }
            } }))
        }.toString())
        return mutateCurrent(command.authorization, command.connectorId, command.mutationIdempotencyKey,
            ConnectorMutationKind.REVISE, requestDigest, null) { existing, now ->
            val settings = connectorInput {
                val settings = applySettingsPatch(existing, command.patch)
                require(settings.first.isNotBlank()) { "Connector display name must not be blank" }
                if (existing.connectorType.value in setOf("postgresql", "redshift")) {
                    require(settings.second.storageValues().getValue("database") == existing.properties.storageValues().getValue("database")) {
                        "The database of a PostgreSQL or Redshift connector cannot change; create a new connector"
                    }
                }
                settings
            }
            existing.revise(settings.first, settings.second, existing.credentialReference, now)
        }
    }

    override suspend fun rotateCredential(command: RotateConnectorCredentialCommand): ConnectorMutationResult {
        if (!command.authorization.allows(MutationIntent.CONNECTOR_UPDATE)) return ConnectorMutationResult.InvalidAuthorization
        val material = command.credential.toMaterial()
        return try {
            val requestDigest = sha256(command.connectorId.toString() + ":" + command.credential.kind)
            mutateCurrent(command.authorization, command.connectorId, command.mutationIdempotencyKey,
                ConnectorMutationKind.ROTATE_CREDENTIAL, requestDigest, material) { existing, now ->
                val connectorType = ConnectorProductType.fromStorageValue(existing.connectorType.value)
                connectorInput { command.credential.requireCompatible(connectorType) }
                val reference = credentialWritePort.writeInOwnerTransaction(
                    ManagedCredentialWriteRequest(existing.workspaceId, existing.id, existing.connectorType.value,
                        CredentialVersionId.generate(), now), material)
                existing.revise(existing.displayName, existing.properties, reference, now)
            }
        } finally {
            material.close()
        }
    }

    override suspend fun delete(command: DeleteConnectorCommand): ConnectorDeletionResult {
        if (!command.authorization.allows(MutationIntent.CONNECTOR_DELETE)) return ConnectorDeletionResult.InvalidAuthorization
        val workspaceId = command.authorization.workspaceId
        return transactionPort.inTransaction {
            mutationKeyLockPort.lockConnectorInOwnerTransaction(workspaceId, command.connectorId)
            val existing = repository.findConnector(command.connectorId)
                ?.takeIf { it.workspaceId == workspaceId && it.deletedAt == null }
                ?: return@inTransaction ConnectorDeletionResult.NotFoundOrOutsideWorkspace
            if (existing.connectorType.value == "duckdb") return@inTransaction ConnectorDeletionResult.InvalidInput("Sample connectors are managed by Monomer")
            when (dependentStateRemoval.removeInOwnerTransaction(workspaceId, existing.id)) {
                ConnectorDependentStateRemoval.REFRESH_ACTIVE -> return@inTransaction ConnectorDeletionResult.RefreshActive
                ConnectorDependentStateRemoval.REMOVED -> Unit
            }
            check(repository.markDeleted(existing, Instant.now(clock))) { "Connector changed during deletion" }
            credentialWritePort.destroyInOwnerTransaction(workspaceId, existing.id)
            searchProjection.delete(workspaceId, existing.id)
            ConnectorDeletionResult.Deleted
        }
    }

    private suspend fun mutateCurrent(
        authorization: AuthorizedMutationContext,
        connectorId: ConnectorId,
        mutationIdempotencyKey: String,
        kind: ConnectorMutationKind,
        requestDigest: ContentDigest,
        material: CredentialMaterial?,
        revise: (Connector, Instant) -> Connector,
    ): ConnectorMutationResult {
        val authorizationDigest = digestMutationAuthorizationScope(authorization)
        return try {
            transactionPort.inTransaction {
                mutationKeyLockPort.lockInOwnerTransaction(authorization.workspaceId, mutationIdempotencyKey)
                mutationKeyLockPort.lockConnectorInOwnerTransaction(authorization.workspaceId, connectorId)
                val existing = repository.findConnector(connectorId)
                    ?.takeIf { it.workspaceId == authorization.workspaceId && it.deletedAt == null }
                    ?: return@inTransaction ConnectorMutationResult.NotFoundOrOutsideWorkspace
                if (existing.connectorType.value == "duckdb") return@inTransaction ConnectorMutationResult.InvalidInput("Sample connectors are managed by Monomer")
                replayOrConflict(authorization.workspaceId, mutationIdempotencyKey, authorization.actor.id,
                    kind, authorizationDigest, requestDigest, material, existing.connectorType, existing)
                    ?.let { return@inTransaction it }
                val updated = revise(existing, Instant.now(clock))
                commit(updated, existing.authorityRevision, authorization, mutationIdempotencyKey,
                    kind, authorizationDigest, requestDigest)
            }
        } catch (failure: ConnectorTransactionRolledBackException) {
            failure.toMutationResult()
        } catch (failure: ConnectorInputInvalidException) {
            ConnectorMutationResult.InvalidInput(failure.detail)
        }
    }

    private fun ConnectorTransactionRolledBackException.toMutationResult(): ConnectorMutationResult {
        if (cause is ConnectorConcurrentMutationException) return ConnectorMutationResult.Conflict
        val inputFailure = cause as? ConnectorInputInvalidException
        if (inputFailure != null) return ConnectorMutationResult.InvalidInput(inputFailure.detail)
        throw (cause as? IllegalArgumentException ?: this)
    }

    private fun replayOrConflict(
        workspaceId: WorkspaceId,
        mutationIdempotencyKey: String,
        actorId: cc.monomer.identity.api.PrincipalId,
        mutationKind: ConnectorMutationKind,
        authorizationScopeDigest: ContentDigest,
        requestDigest: ContentDigest,
        credentialMaterial: CredentialMaterial?,
        connectorType: ConnectorType,
        existing: Connector? = null,
    ): ConnectorMutationResult? {
        val receipt = repository.findMutationReceipt(workspaceId, mutationIdempotencyKey) ?: return null
        if (receipt.actorId != actorId ||
            receipt.mutationKind != mutationKind ||
            receipt.authorizationScopeDigest != authorizationScopeDigest ||
            receipt.requestDigest != requestDigest
        ) {
            return ConnectorMutationResult.Conflict
        }
        val connector = existing ?: repository.findConnector(receipt.connectorId)
            ?: return ConnectorMutationResult.Conflict
        if (connector.workspaceId != receipt.workspaceId) {
            return ConnectorMutationResult.Conflict
        }
        if (credentialMaterial != null) {
            val matches = credentialWritePort.matchesInOwnerTransaction(
                request = ManagedCredentialWriteRequest(
                    workspaceId = workspaceId,
                    connectorId = connector.id,
                    connectorType = connectorType.value,
                    credentialVersionId = receipt.credentialVersionId,
                    createdAt = receipt.committedAt,
                ),
                credentialMaterial = credentialMaterial,
            )
            if (!matches) return ConnectorMutationResult.Conflict
        }
        return ConnectorMutationResult.Committed(connector.toMutationView())
    }

    private fun commit(
        connector: Connector,
        expectedAuthorityRevision: Long?,
        authorization: AuthorizedMutationContext,
        mutationIdempotencyKey: String,
        mutationKind: ConnectorMutationKind,
        authorizationScopeDigest: ContentDigest,
        requestDigest: ContentDigest,
    ): ConnectorMutationResult {
        if (expectedAuthorityRevision == null) {
            repository.insertConnector(connector)
        } else if (!repository.updateConnector(connector, expectedAuthorityRevision)) {
            throw ConnectorConcurrentMutationException()
        }
        repository.insertMutationReceipt(
            ConnectorMutationReceipt(
                workspaceId = connector.workspaceId,
                mutationIdempotencyKey = mutationIdempotencyKey,
                actorId = authorization.actor.id,
                mutationKind = mutationKind,
                authorizationScopeDigest = authorizationScopeDigest,
                requestDigest = requestDigest,
                connectorId = connector.id,
                credentialVersionId = connector.credentialReference.credentialVersionId,
                committedAt = connector.updatedAt,
            ),
        )
        if (mutationKind != ConnectorMutationKind.ROTATE_CREDENTIAL) {
            searchProjection.upsert(connectorSearchFields(connector.workspaceId, connector.id, connector.displayName, connector.properties))
        }
        return ConnectorMutationResult.Committed(connector.toMutationView())
    }

    private fun digestCreateRequest(
        command: CreateConnectorCommand,
        connectorType: ConnectorType,
    ): ContentDigest = digestMutationRequest(
        kind = ConnectorMutationKind.CREATE,
        workspaceId = command.authorization.workspaceId,
        connectorId = null,
        connectorType = connectorType.value,
        displayName = command.displayName,
        properties = command.properties.storageValues(),
        credentialMaterialSupplied = true,
    )

    private fun digestMutationRequest(
        kind: ConnectorMutationKind,
        workspaceId: WorkspaceId,
        connectorId: ConnectorId?,
        connectorType: String,
        displayName: String,
        properties: Map<String, String>,
        credentialMaterialSupplied: Boolean,
    ): ContentDigest {
        val canonical = buildString {
            fun field(name: String, value: String) {
                append(name.toByteArray(StandardCharsets.UTF_8).size)
                    .append(':').append(name)
                    .append(value.toByteArray(StandardCharsets.UTF_8).size)
                    .append(':').append(value)
            }
            field("mutationKind", kind.name)
            field("workspaceId", workspaceId.value.toString())
            field("connectorId", connectorId?.toString().orEmpty())
            field("connectorType", connectorType)
            field("displayName", displayName)
            field("credentialMaterialSupplied", credentialMaterialSupplied.toString())
            field("propertyCount", properties.size.toString())
            properties.toSortedMap().forEach { (key, value) ->
                field("propertyKey", key)
                field("propertyValue", value)
            }
        }
        return sha256(canonical)
    }

    private fun digestMutationAuthorizationScope(
        authorization: AuthorizedMutationContext,
    ): ContentDigest {
        val canonical = buildString {
            fun field(name: String, value: String) {
                append(name.toByteArray(StandardCharsets.UTF_8).size)
                    .append(':').append(name)
                    .append(value.toByteArray(StandardCharsets.UTF_8).size)
                    .append(':').append(value)
            }
            field("intent", authorization.intent.name)
            field("role", authorization.role.name)
        }
        return sha256(canonical)
    }

    private fun sha256(value: String): ContentDigest {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return ContentDigest("sha256:$digest")
    }
}

internal class ConnectorInputInvalidException(val detail: String) : IllegalArgumentException(detail)

internal inline fun <T> connectorInput(validation: () -> T): T = try {
    validation()
} catch (failure: IllegalArgumentException) {
    throw ConnectorInputInvalidException(failure.message ?: "Connector input is invalid")
}

private fun AuthorizedMutationContext.allows(expectedIntent: MutationIntent): Boolean =
    intent == expectedIntent && role == WorkspaceRole.OWNER

private class ConnectorConcurrentMutationException : RuntimeException("Connector changed during owner mutation")

private fun canonicalJson(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonicalJson(it.value) })
    is JsonArray -> JsonArray(value.map(::canonicalJson))
    else -> value
}

private fun applySettingsPatch(connector: Connector, patch: List<ConnectorSettingsPatch>): Pair<String, ConnectorSettingsProperties> {
    require(patch.isNotEmpty()) { "Connector patch must not be empty" }
    var document = buildJsonObject {
        put("displayName", connector.displayName)
        put("properties", ConnectorSettingsCodec.encode(connector.properties))
    }
    patch.forEach { operation ->
        require(operation.op in setOf("add", "replace", "remove")) { "Connector patch operation is invalid" }
        require((operation.op == "remove") == (operation.value == null)) { "Connector patch value is invalid" }
        if (operation.path.isEmpty()) {
            require(operation.op == "replace") { "Connector root only accepts replace" }
            document = operation.value as? JsonObject ?: throw IllegalArgumentException("Connector settings require an object")
            require(document.keys == setOf("displayName", "properties")) { "Connector root replacement requires complete settings" }
        } else {
            val parts = operation.path.split('/').drop(1)
            require(operation.path.startsWith('/') && parts.size in 1..2 &&
                (parts[0] == "displayName" && parts.size == 1 || parts[0] == "properties")) { "Connector patch path is invalid" }
            val target = if (parts.size == 1) document else document["properties"] as? JsonObject
                ?: throw IllegalArgumentException("Connector properties require an object")
            val key = parts.last()
            require(key.isNotEmpty() && '~' !in key) { "Connector patch path is invalid" }
            if (operation.op != "add") require(key in target) { "Connector patch target does not exist" }
            val updated = JsonObject(target.toMutableMap().apply {
                if (operation.op == "remove") remove(key) else put(key, requireNotNull(operation.value))
            })
            document = if (parts.size == 1) updated else JsonObject(document + ("properties" to updated))
        }
        require(document.keys.all { it in setOf("displayName", "properties") }) { "Connector settings contain an unknown field" }
    }
    require(document.keys == setOf("displayName", "properties")) { "Connector settings require displayName and properties" }
    val properties = document["properties"] as? JsonObject ?: throw IllegalArgumentException("Connector properties require an object")
    return document.text("displayName") to ConnectorSettingsCodec.decode(
        ConnectorProductType.fromStorageValue(connector.connectorType.value), properties)
}

class SampleConnectorService(private val authority: ConnectorAuthorityService) : SampleConnectorUseCase {
    override suspend fun create(command: CreateSampleConnectorCommand): ConnectorMutationResult = authority.createSample(command)
}
