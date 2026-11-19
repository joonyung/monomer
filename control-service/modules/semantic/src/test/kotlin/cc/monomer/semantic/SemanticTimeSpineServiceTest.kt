@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.semantic

import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorProductType
import cc.monomer.connector.api.ConnectorWorkspaceReadUseCase
import cc.monomer.connector.api.CurrentConnectorRead
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.api.OrganizationId
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.api.AuthorizationRevision
import cc.monomer.identity.api.CanonicalPrincipal
import cc.monomer.identity.api.CredentialKind
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.AuthorizedMutationContext
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.PrincipalKind
import cc.monomer.semantic.api.PutSemanticTimeSpineConfigurationCommand
import cc.monomer.semantic.api.SemanticTimeGranularity
import cc.monomer.semantic.api.SemanticTimeSpine
import cc.monomer.semantic.api.SemanticTimeSpineConfigurationView
import cc.monomer.semantic.api.SemanticTimeSpineDefinition
import cc.monomer.semantic.api.SemanticTimeSpineMutationResult
import cc.monomer.semantic.api.SemanticTimeSpinePrimaryColumn
import cc.monomer.semantic.api.SemanticTimeSpineRelation
import cc.monomer.semantic.application.SemanticTimeSpineRepository
import cc.monomer.semantic.application.SemanticTimeSpineService
import cc.monomer.semantic.application.SemanticTransactionPort
import cc.monomer.semantic.api.SemanticInvalidReason
import cc.monomer.semantic.api.SemanticInvalidReasonCode
import cc.monomer.semantic.domain.CurrentTimeSpineConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class SemanticTimeSpineServiceTest {
    @Test
    fun `put writes in a mutation transaction so concurrent saves are last-write-wins`() = runBlocking<Unit> {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val service = SemanticTimeSpineService(
            repository = InMemoryTimeSpineRepository(),
            transactionPort = MutationOnlyTimeSpineTransaction,
            connectorReadUseCase = ExistingConnectorRead(workspaceId, connectorId),
            catalogReadPort = FixedCatalogRead(null),
        )

        assertIs<SemanticTimeSpineMutationResult.Committed<SemanticTimeSpineConfigurationView>>(
            service.putTimeSpineConfiguration(
                PutSemanticTimeSpineConfigurationCommand(
                    authorization = authorization(workspaceId),
                    workspaceId = workspaceId,
                    connectorId = connectorId,
                    definition = definition(SemanticTimeGranularity.DAY),
                ),
            ),
        )
    }

    @Test
    fun `put replaces the current configuration without idempotency or lifecycle state`() = runBlocking {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val repository = InMemoryTimeSpineRepository()
        val service = SemanticTimeSpineService(
            repository = repository,
            transactionPort = ImmediateTimeSpineTransaction,
            connectorReadUseCase = ExistingConnectorRead(workspaceId, connectorId),
            catalogReadPort = FixedCatalogRead(null),
        )

        val first = assertIs<SemanticTimeSpineMutationResult.Committed<SemanticTimeSpineConfigurationView>>(
            service.putTimeSpineConfiguration(
                PutSemanticTimeSpineConfigurationCommand(
                    authorization = authorization(workspaceId),
                    workspaceId = workspaceId,
                    connectorId = connectorId,
                    definition = definition(SemanticTimeGranularity.DAY),
                ),
            ),
        )
        val second = assertIs<SemanticTimeSpineMutationResult.Committed<SemanticTimeSpineConfigurationView>>(
            service.putTimeSpineConfiguration(
                PutSemanticTimeSpineConfigurationCommand(
                    authorization = authorization(workspaceId),
                    workspaceId = workspaceId,
                    connectorId = connectorId,
                    definition = definition(SemanticTimeGranularity.MONTH),
                ),
            ),
        )

        assertEquals(SemanticTimeGranularity.DAY, first.value.definition.timeSpines.single().primaryColumn.granularity)
        assertEquals(SemanticTimeGranularity.MONTH, second.value.definition.timeSpines.single().primaryColumn.granularity)
        assertEquals(1, repository.values.size)
        assertEquals(SemanticTimeGranularity.MONTH, repository.values.values.single().definition.timeSpines.single().primaryColumn.granularity)
    }

    @Test
    fun `put rejects a connector outside the workspace before persistence`() = runBlocking {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val repository = InMemoryTimeSpineRepository()
        val service = SemanticTimeSpineService(
            repository = repository,
            transactionPort = ImmediateTimeSpineTransaction,
            connectorReadUseCase = ExistingConnectorRead(WorkspaceId.generate(), connectorId),
            catalogReadPort = FixedCatalogRead(null),
        )

        assertEquals(
            SemanticTimeSpineMutationResult.NotFoundOrOutsideWorkspace,
            service.putTimeSpineConfiguration(
                PutSemanticTimeSpineConfigurationCommand(
                    authorization = authorization(workspaceId),
                    workspaceId = workspaceId,
                    connectorId = connectorId,
                    definition = definition(SemanticTimeGranularity.DAY),
                ),
            ),
        )
        assertEquals(0, repository.values.size)
    }

    @Test
    fun `put rejects evidence without semantic edit capability`() = runBlocking {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val repository = InMemoryTimeSpineRepository()
        val service = SemanticTimeSpineService(
            repository = repository,
            transactionPort = ImmediateTimeSpineTransaction,
            connectorReadUseCase = ExistingConnectorRead(workspaceId, connectorId),
            catalogReadPort = FixedCatalogRead(null),
        )

        assertEquals(
            SemanticTimeSpineMutationResult.InvalidAuthorization,
            service.putTimeSpineConfiguration(
                PutSemanticTimeSpineConfigurationCommand(
                    authorization = authorization(workspaceId, WorkspaceRole.EXPLORER),
                    workspaceId = workspaceId,
                    connectorId = connectorId,
                    definition = definition(SemanticTimeGranularity.DAY),
                ),
            ),
        )
        assertEquals(0, repository.values.size)
    }

    @Test
    fun `time spine rejects wrong intent and foreign workspace before writing`() = runBlocking {
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val repository = InMemoryTimeSpineRepository()
        val service = SemanticTimeSpineService(repository, ImmediateTimeSpineTransaction, ExistingConnectorRead(workspace, connector), FixedCatalogRead(null))
        val wrongContexts = cc.monomer.identity.api.MutationIntent.entries
            .filter { it != cc.monomer.identity.api.MutationIntent.SEMANTIC_TIME_SPINE_UPDATE }
            .map { authorization(workspace, WorkspaceRole.OWNER, it) } + authorization(WorkspaceId.generate())
        for (context in wrongContexts) {
            assertEquals(SemanticTimeSpineMutationResult.InvalidAuthorization, service.putTimeSpineConfiguration(
                PutSemanticTimeSpineConfigurationCommand(context, workspace, connector, definition(SemanticTimeGranularity.DAY)),
            ))
        }
        assertEquals(0, repository.values.size)
    }

    @Test
    fun `put rejects a spine column whose known Catalog type has a time zone or is not temporal`() = runBlocking {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        listOf("timestamp with time zone", "text").forEach { nativeType ->
            val repository = InMemoryTimeSpineRepository()
            val service = SemanticTimeSpineService(
                repository, ImmediateTimeSpineTransaction, ExistingConnectorRead(workspaceId, connectorId),
                FixedCatalogRead(catalogWithTypedColumns(workspaceId, connectorId, mapOf("time_spine" to listOf("date_utc" to nativeType)))),
            )
            assertEquals(
                SemanticTimeSpineMutationResult.Invalid(listOf(SemanticInvalidReason(
                    SemanticInvalidReasonCode.TIME_SPINE_COLUMN_TYPE_INVALID, "timeSpines[0].primaryColumn.name",
                ))),
                service.putTimeSpineConfiguration(
                    PutSemanticTimeSpineConfigurationCommand(authorization(workspaceId), workspaceId, connectorId, definition(SemanticTimeGranularity.DAY)),
                ),
                nativeType,
            )
            assertEquals(0, repository.values.size)
        }
    }

    @Test
    fun `put accepts date wall clock and unknown spine column types and spines Catalog has not loaded`() = runBlocking {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val catalogs = listOf("date", "timestamp without time zone", "USER-DEFINED").map { nativeType ->
            catalogWithTypedColumns(workspaceId, connectorId, mapOf("time_spine" to listOf("date_utc" to nativeType)))
        } + catalogWithTypedColumns(workspaceId, connectorId, emptyMap()) + null
        catalogs.forEach { catalog ->
            val repository = InMemoryTimeSpineRepository()
            val service = SemanticTimeSpineService(
                repository, ImmediateTimeSpineTransaction, ExistingConnectorRead(workspaceId, connectorId), FixedCatalogRead(catalog),
            )
            assertIs<SemanticTimeSpineMutationResult.Committed<SemanticTimeSpineConfigurationView>>(
                service.putTimeSpineConfiguration(
                    PutSemanticTimeSpineConfigurationCommand(authorization(workspaceId), workspaceId, connectorId, definition(SemanticTimeGranularity.DAY)),
                ),
            )
            assertEquals(1, repository.values.size)
        }
    }

    private fun definition(granularity: SemanticTimeGranularity): SemanticTimeSpineDefinition =
        SemanticTimeSpineDefinition(
            schemaVersion = 1u,
            timeSpines = listOf(
                SemanticTimeSpine(
                    relation = SemanticTimeSpineRelation(
                        catalog = null,
                        schema = "analytics",
                        relation = "time_spine",
                    ),
                    primaryColumn = SemanticTimeSpinePrimaryColumn(
                        name = "date_utc",
                        granularity = granularity,
                    ),
                ),
            ),
        )

    private fun authorization(
        workspaceId: WorkspaceId,
        capability: WorkspaceRole = WorkspaceRole.EDITOR,
        intent: cc.monomer.identity.api.MutationIntent = cc.monomer.identity.api.MutationIntent.SEMANTIC_TIME_SPINE_UPDATE,
    ): AuthorizedMutationContext {
        val actor = CanonicalPrincipal(PrincipalId.generate(), PrincipalKind.USER)
        return AuthorizedMutationContext(
            actor = actor,
            organizationId = OrganizationId.generate(),
            organizationPlan = cc.monomer.identity.api.OrganizationPlan.FREE,
            workspaceId = workspaceId,
            authorizationRevision = AuthorizationRevision(1),
            credentialKind = CredentialKind.SESSION,
            credentialSurface = CredentialSurface.WEB,
            agentClientId = null,
            role = capability,
            intent = intent,
            admittedAt = java.time.Instant.parse("2026-09-29T00:00:00Z"),
        )
    }
}

private object MutationOnlyTimeSpineTransaction : SemanticTransactionPort {
    override suspend fun <T> inSnapshotTransaction(deadline: java.time.Instant, block: suspend () -> T): T =
        error("Time Spine writes must not use a snapshot transaction")

    override suspend fun <T> inSnapshotTransaction(block: () -> T): T =
        error("Time Spine writes must not use a snapshot transaction")

    override suspend fun <T> inMutationTransaction(block: () -> T): T = block()
}

private object ImmediateTimeSpineTransaction : SemanticTransactionPort {
    override suspend fun <T> inSnapshotTransaction(deadline: java.time.Instant, block: suspend () -> T): T = block()

    override suspend fun <T> inSnapshotTransaction(block: () -> T): T = block()
    override suspend fun <T> inMutationTransaction(block: () -> T): T = block()
}

private class InMemoryTimeSpineRepository : SemanticTimeSpineRepository {
    val values = linkedMapOf<Pair<WorkspaceId, ConnectorId>, CurrentTimeSpineConfiguration>()

    override fun findTimeSpineConfigurations(workspaceId: WorkspaceId): List<CurrentTimeSpineConfiguration> =
        values.values.filter { it.workspaceId == workspaceId }

    override fun upsertTimeSpineConfiguration(configuration: CurrentTimeSpineConfiguration) {
        values[configuration.workspaceId to configuration.connectorId] = configuration
    }
}

private class ExistingConnectorRead(
    private val workspaceId: WorkspaceId,
    private val connectorId: ConnectorId,
) : ConnectorWorkspaceReadUseCase {
    private val connector = CurrentConnectorRead(
        connectorId = connectorId,
        workspaceId = workspaceId,
        connectorType = ConnectorProductType.POSTGRESQL,
        displayName = "warehouse",
        properties = cc.monomer.connector.api.ConnectorSettingsProperties.Postgresql("warehouse", 5432, "warehouse", null, null),
        createdAt = java.time.Instant.EPOCH,
        updatedAt = java.time.Instant.EPOCH,
    )

    override suspend fun findByWorkspaceAndId(
        workspaceId: WorkspaceId,
        connectorId: ConnectorId,
    ): CurrentConnectorRead? = connector.takeIf { it.workspaceId == workspaceId && it.connectorId == connectorId }

    override suspend fun findByWorkspaceAndIds(
        workspaceId: WorkspaceId,
        connectorIds: Set<ConnectorId>,
    ): List<CurrentConnectorRead> = listOfNotNull(
        connector.takeIf { it.workspaceId == workspaceId && it.connectorId in connectorIds },
    )

    override suspend fun listByWorkspace(workspaceId: WorkspaceId): List<CurrentConnectorRead> = emptyList()
}
