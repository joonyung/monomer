package cc.monomer.control.sample

import cc.monomer.catalog.api.*
import cc.monomer.connector.api.*
import cc.monomer.dashboard.api.*
import cc.monomer.identity.api.*
import cc.monomer.semantic.api.*
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.*

class SampleProvisioningFixture(val workspaces: SampleWorkspaceProvisioningUseCase, val workspaceId: WorkspaceId) {
    val now = Instant.parse("2026-10-12T12:00:00Z")
    val clock = Clock.fixed(now, ZoneOffset.UTC)
    val connectors = mockk<SampleConnectorUseCase>()
    val catalog = mockk<CatalogProvisioningUseCase>()
    val semantic = mockk<SemanticSeedDefinitionUseCase>()
    val semanticRead = mockk<SemanticCurrentReadUseCase>()
    val spines = mockk<SemanticTimeSpineUseCase>()
    val dashboards = mockk<DashboardSeedDefinitionUseCase>()
    val connectorId = ConnectorId.generate()
    val models = ConcurrentHashMap<SemanticElementName, SemanticModelView>()
    val metrics = ConcurrentHashMap<SemanticElementName, MetricView>()
    val createdDashboards = ConcurrentHashMap<String, DashboardOwnerDashboard>()
    val originalDashboardInputs = ConcurrentHashMap<String, JsonObject>()
    val createdSpines = ConcurrentHashMap<ConnectorId, SemanticTimeSpineConfigurationView>()
    val connectorDatasets = java.util.Collections.synchronizedList(mutableListOf<String>())
    val actors = java.util.Collections.synchronizedList(mutableListOf<PrincipalId>())
    var failedIngests = 0
    var failAfterModel = false
    var failAfterDashboard = false
    var failBeforeConnector = false
    @Volatile var ingestionRunning = false
    val startedIngests = java.util.concurrent.atomic.AtomicInteger()
    private val ingestOutcomes = ConcurrentHashMap<CatalogIngestJobId, Boolean>()

    init {
        coEvery { connectors.create(any()) } coAnswers {
            if (failBeforeConnector) { failBeforeConnector = false; error("Injected Connector failure") }
            val c = firstArg<CreateSampleConnectorCommand>()
            actors.add(c.authorization.actor.id)
            connectorDatasets.add(c.dataset)
            ConnectorMutationResult.Committed(ConnectorMutationView(connectorId, c.authorization.workspaceId, "duckdb", "Acme Cloud (sample)",
                ConnectorSettingsProperties.Duckdb(c.dataset), CredentialVersionId.generate(), now, now))
        }
        coEvery { catalog.saveSelection(any()) } coAnswers {
            val c = firstArg<SaveCatalogSelectionCommand>()
            actors.add(c.authorization.actor.id)
            check(c.selection.selectedSchemas == setOf(CatalogSelectedSchema("acme", CatalogRelationSelection.AllRelations)))
            CatalogCommandResult.Accepted(CatalogSelectionView(c.authorization.workspaceId, c.connectorId, c.entry, c.selection, now))
        }
        coEvery { catalog.startIngest(any()) } coAnswers {
            val c = firstArg<StartCatalogIngestCommand>()
            actors.add(c.authorization.actor.id)
            startedIngests.incrementAndGet()
            val id = CatalogIngestJobId.generate()
            ingestOutcomes[id] = failedIngests > 0
            if (failedIngests > 0) failedIngests--
            coEvery { catalog.readJob(workspaceId, id) } coAnswers {
                CatalogProvisioningJob(id, if (ingestionRunning) CatalogRefreshState.RUNNING else if (ingestOutcomes[id] == true) CatalogRefreshState.COMPLETED_WITH_ERRORS else CatalogRefreshState.COMPLETED,
                    if (ingestOutcomes[id] == true) 1 else 0)
            }
            CatalogCommandResult.Accepted(CatalogIngestStarted(id, c.authorization.workspaceId, c.connectorId, c.entry))
        }
        val seed = SampleDefinitionBundle().read("acme-cloud@3", connectorId, java.time.LocalDate.parse("2026-10-12"))
        seed.models.forEach { model -> coEvery { semanticRead.findModelByName(workspaceId, model.name) } coAnswers { models[model.name] } }
        seed.metrics.forEach { metric -> coEvery { semanticRead.findMetricByName(workspaceId, metric.name) } coAnswers { metrics[metric.name] } }
        coEvery { semanticRead.findTimeSpineConfigurations(workspaceId) } coAnswers { createdSpines.values.toList() }
        coEvery { semantic.seedModel(any()) } coAnswers {
            val c = firstArg<PutSemanticModelCommand>()
            actors.add(c.authorization.actor.id)
            check(c.semanticModelId == null && c.aggregateMetrics.isEmpty())
            val view = SemanticModelView(SemanticModelId.generate(), c.authorization.workspaceId, c.definition, now, now, emptyList())
            check(models.putIfAbsent(c.definition.name, view) == null)
            if (failAfterModel) { failAfterModel = false; error("Injected lost Model response") }
            SemanticMutationResult.Committed(SemanticModelWriteResult(view, emptyList()))
        }
        coEvery { spines.putTimeSpineConfiguration(any()) } coAnswers {
            val c = firstArg<PutSemanticTimeSpineConfigurationCommand>()
            actors.add(c.authorization.actor.id)
            val view = SemanticTimeSpineConfigurationView(c.connectorId, c.definition)
            check(createdSpines.putIfAbsent(c.connectorId, view) == null)
            SemanticTimeSpineMutationResult.Committed(view)
        }
        coEvery { semantic.seedMetric(any()) } coAnswers {
            val c = firstArg<PutMetricCommand>()
            actors.add(c.authorization.actor.id)
            check(c.metricId == null)
            val view = MetricView(MetricId.generate(), c.authorization.workspaceId, c.definition, now, now, emptyList())
            check(metrics.putIfAbsent(c.definition.name, view) == null)
            SemanticMutationResult.Committed(view)
        }
        coEvery { dashboards.seed(any()) } coAnswers {
            val c = firstArg<SeedDashboardDefinitionCommand>()
            actors.add(PrincipalId.parse(c.receiptActorId))
            val original = originalDashboardInputs.putIfAbsent(c.idempotencyKey, c.definition)
            check(original == null || original == c.definition) { "Seed Dashboard replay changed its literal date input" }
            val view = createdDashboards.computeIfAbsent(c.idempotencyKey) { DashboardOwnerDashboard(DashboardId.generate(), c.workspaceId, c.definition,
                c.createdAt, c.createdAt, null, null, null) }
            if (failAfterDashboard) { failAfterDashboard = false; error("Injected lost Dashboard response") }
            DashboardOwnerOutcome.Success(view)
        }
    }

    fun coordinator(lock: PostgresSampleWorkspaceLock, scope: CoroutineScope, currentClock: Clock = clock) = SampleWorkspaceProvisioning(
        workspaces, connectors, catalog, semantic, semanticRead, spines, dashboards, SampleDefinitionBundle(), lock, scope, currentClock)
}

class MemorySampleWorkspaces(val actor: PrincipalId = PrincipalId.generate(), val organization: OrganizationId = OrganizationId.generate()) : SampleWorkspaceProvisioningUseCase {
    val workspaceId = WorkspaceId.generate()
    var value: SampleWorkspace? = null
    var evidenceAvailable = true
    var admins = mutableSetOf(actor)
    val owners = mutableSetOf<PrincipalId>()
    var creations = 0
    override suspend fun create(actorUserId: PrincipalId, organizationId: OrganizationId, dataset: String): SampleWorkspace {
        checkAdmin(actorUserId, organizationId)
        return value ?: SampleWorkspace(workspaceId, organizationId, "Acme Cloud (sample)", Instant.parse("2026-10-12T12:00:00Z"),
            WorkspaceSampleMarker(dataset, WorkspaceSampleState.PROVISIONING, actorUserId)).also { value = it; creations++; owners.add(actorUserId) }
    }
    override suspend fun findByOrganization(actorUserId: PrincipalId, organizationId: OrganizationId): SampleWorkspace? {
        checkAdmin(actorUserId, organizationId)
        return value
    }
    override suspend fun readMarker(workspaceId: WorkspaceId) = value?.takeIf { it.workspaceId == workspaceId }?.sample
    override suspend fun readInitiatorEvidence(workspaceId: WorkspaceId, dataset: String): SampleWorkspaceInitiatorEvidence? = value
        ?.takeIf { it.workspaceId == workspaceId && it.sample.dataset == dataset && evidenceAvailable && it.sample.state == WorkspaceSampleState.PROVISIONING }
        ?.let { SampleWorkspaceInitiatorEvidence(OrganizationPlan.FREE, CanonicalPrincipal(it.sample.initiator, PrincipalKind.USER), it.organizationId,
            it.workspaceId, AuthorizationRevision(1), WorkspaceRole.OWNER) }
    override suspend fun markReady(workspaceId: WorkspaceId, dataset: String) { value = value!!.copy(sample = value!!.sample.copy(state = WorkspaceSampleState.READY)) }
    override suspend fun addAdminOwner(actorUserId: PrincipalId, workspaceId: WorkspaceId) { checkAdmin(actorUserId, organization); owners.add(actorUserId) }
    private fun checkAdmin(actorUserId: PrincipalId, organizationId: OrganizationId) {
        if (organizationId != organization || actorUserId !in admins) throw SampleWorkspaceAdminRequiredException()
    }
}
