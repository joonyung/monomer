package cc.monomer.control.sample

import cc.monomer.catalog.api.*
import cc.monomer.connector.api.*
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.dashboard.api.*
import cc.monomer.identity.api.*
import cc.monomer.semantic.api.*
import java.time.Clock
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.slf4j.MDCContext
import org.slf4j.LoggerFactory

class SampleWorkspaceProvisioning(
    private val workspaces: SampleWorkspaceProvisioningUseCase,
    private val connectors: SampleConnectorUseCase,
    private val catalog: CatalogProvisioningUseCase,
    private val semantic: SemanticSeedDefinitionUseCase,
    private val semanticRead: SemanticCurrentReadUseCase,
    private val timeSpines: SemanticTimeSpineUseCase,
    private val dashboards: DashboardSeedDefinitionUseCase,
    private val definitions: SampleDefinitionBundle,
    private val organizationLock: PostgresSampleWorkspaceLock,
    private val runnerScope: CoroutineScope,
    private val clock: Clock,
) {
    private val running = ConcurrentHashMap<OrganizationId, Job>()

    suspend fun start(actorUserId: PrincipalId, organizationId: OrganizationId): SampleWorkspace {
        val sample = workspaces.create(actorUserId, organizationId, "acme-cloud@3")
        workspaces.addAdminOwner(actorUserId, sample.workspaceId)
        if (sample.sample.state == WorkspaceSampleState.PROVISIONING) launch(sample)
        return sample
    }

    suspend fun read(actorUserId: PrincipalId, organizationId: OrganizationId): SampleWorkspace? =
        workspaces.findByOrganization(actorUserId, organizationId)

    private fun launch(sample: SampleWorkspace) {
        val job = runnerScope.launch(MDCContext(), start = CoroutineStart.LAZY) {
            try {
                organizationLock.withLock(sample.organizationId) {
                    if (workspaces.readMarker(sample.workspaceId)?.state == WorkspaceSampleState.PROVISIONING) complete(sample)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                logger.warn("sample_workspace_provisioning_failed organization_id={} workspace_id={} error_type={} error_stack_trace={}",
                    sample.organizationId, sample.workspaceId, failure.javaClass.name, cc.monomer.control.observability.messageFreeStackTrace(failure))
            }
        }
        if (running.putIfAbsent(sample.organizationId, job) != null) {
            job.cancel()
            return
        }
        job.invokeOnCompletion { running.remove(sample.organizationId, job) }
        job.start()
    }

    internal suspend fun complete(sample: SampleWorkspace) {
        val connector = when (val result = connectors.create(CreateSampleConnectorCommand(
            mutation(sample, MutationIntent.CONNECTOR_CREATE), sample.sample.dataset, "sample:${sample.sample.dataset}:connector"))) {
            is ConnectorMutationResult.Committed -> result.connector
            else -> error("Sample Connector creation was rejected")
        }
        val seed = definitions.read(sample.sample.dataset, connector.connectorId, sample.createdAt.atZone(ZoneOffset.UTC).toLocalDate())
        val entry = CatalogIngestEntry.Connector(connector.connectorId)
        check(catalog.saveSelection(SaveCatalogSelectionCommand(mutation(sample, MutationIntent.CATALOG_SELECTION_UPDATE),
            connector.connectorId, entry, CatalogSelection(setOf(CatalogSelectedSchema("acme", CatalogRelationSelection.AllRelations)))))
            is CatalogCommandResult.Accepted) { "Sample Catalog selection was rejected" }
        val ingest = when (val result = catalog.startIngest(StartCatalogIngestCommand(execution(sample), connector.connectorId, entry))) {
            is CatalogCommandResult.Accepted -> result.value
            else -> error("Sample Catalog ingestion was rejected")
        }
        while (true) {
            val job = checkNotNull(catalog.readJob(sample.workspaceId, ingest.jobId)) { "Sample Catalog ingestion is unavailable" }
            when (job.state) {
                CatalogRefreshState.RUNNING, CatalogRefreshState.CANCEL_REQUESTED -> delay(250)
                CatalogRefreshState.COMPLETED -> { check(job.errorCount == 0) { "Sample Catalog ingestion failed" }; break }
                CatalogRefreshState.COMPLETED_WITH_ERRORS, CatalogRefreshState.CANCELLED -> error("Sample Catalog ingestion failed")
            }
        }
        seed.models.forEach { model ->
            if (semanticRead.findModelByName(sample.workspaceId, model.name) == null) {
                check(semantic.seedModel(PutSemanticModelCommand(mutation(sample, MutationIntent.SEMANTIC_MODEL_CREATE),
                    "sample:${sample.sample.dataset}:model:${model.name.value}", null, model, emptyList())) is SemanticMutationResult.Committed) {
                    "Sample Semantic Model creation was rejected"
                }
            }
        }
        if (semanticRead.findTimeSpineConfigurations(sample.workspaceId).none { it.connectorId == connector.connectorId }) {
            check(timeSpines.putTimeSpineConfiguration(PutSemanticTimeSpineConfigurationCommand(
                mutation(sample, MutationIntent.SEMANTIC_TIME_SPINE_UPDATE), sample.workspaceId, connector.connectorId, seed.timeSpine))
                is SemanticTimeSpineMutationResult.Committed) { "Sample time spine creation was rejected" }
        }
        seed.metrics.forEach { metric ->
            if (semanticRead.findMetricByName(sample.workspaceId, metric.name) == null) {
                check(semantic.seedMetric(PutMetricCommand(mutation(sample, MutationIntent.METRIC_CREATE),
                    "sample:${sample.sample.dataset}:metric:${metric.name.value}", null, metric)) is SemanticMutationResult.Committed) {
                    "Sample Metric creation was rejected"
                }
            }
        }
        seed.dashboards.forEach { (name, definition) ->
            val evidence = initiator(sample)
            check(dashboards.seed(SeedDashboardDefinitionCommand(sample.workspaceId, definition, evidence.actor.id.toString(),
                "sample:${sample.sample.dataset}:$name", sample.createdAt)) is DashboardOwnerOutcome.Success) {
                "Sample Dashboard creation was rejected"
            }
        }
        initiator(sample)
        workspaces.markReady(sample.workspaceId, sample.sample.dataset)
    }

    private suspend fun initiator(sample: SampleWorkspace): SampleWorkspaceInitiatorEvidence =
        checkNotNull(workspaces.readInitiatorEvidence(sample.workspaceId, sample.sample.dataset)) { "Sample initiator authorization is unavailable" }.also {
            check(it.workspaceId == sample.workspaceId && it.organizationId == sample.organizationId &&
                it.actor.id == sample.sample.initiator && it.role == WorkspaceRole.OWNER) { "Sample initiator authorization does not match" }
        }

    private suspend fun mutation(sample: SampleWorkspace, intent: MutationIntent): AuthorizedMutationContext {
        val evidence = initiator(sample)
        return ControlAdmissionFacade.sampleWorkspaceProvisioningMutation(sample, evidence, intent, clock.instant())
    }

    private suspend fun execution(sample: SampleWorkspace): AuthorizedExecutionContext {
        val evidence = initiator(sample)
        return ControlAdmissionFacade.sampleWorkspaceCatalogIngest(sample, evidence, clock.instant())
    }

    private companion object { val logger = LoggerFactory.getLogger(SampleWorkspaceProvisioning::class.java) }
}
