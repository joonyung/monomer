@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.semantic

import cc.monomer.catalog.api.CatalogCurrentObject
import cc.monomer.catalog.api.CatalogCurrentObjectSet
import cc.monomer.catalog.api.CatalogCurrentObjectsReadPort
import cc.monomer.catalog.api.CatalogDigest
import cc.monomer.catalog.api.CatalogObjectId
import cc.monomer.catalog.api.CatalogObjectKind
import cc.monomer.catalog.api.CatalogPhysicalAddress
import cc.monomer.catalog.api.RelationKind
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorProductType
import cc.monomer.connector.api.ConnectorWorkspaceReadUseCase
import cc.monomer.connector.api.CurrentConnectorRead
import cc.monomer.identity.api.OrganizationId
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.api.AuthorizationRevision
import cc.monomer.identity.api.CanonicalPrincipal
import cc.monomer.identity.api.CredentialKind
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.IdentityRoleEvidence
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.PrincipalKind
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.*
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.PutMetricCommand
import cc.monomer.semantic.api.PutSemanticModelCommand
import cc.monomer.semantic.api.SemanticDimensionDefinition
import cc.monomer.semantic.api.SemanticDimensionKind
import cc.monomer.semantic.api.metricResultColumnKey
import cc.monomer.semantic.api.resultColumns
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticEntityDefinition
import cc.monomer.semantic.api.SemanticEntityKind
import cc.monomer.semantic.api.SemanticInvalidReasonCode
import cc.monomer.semantic.api.SemanticMeasureAggregation
import cc.monomer.semantic.api.SemanticMeasureDefinition
import cc.monomer.semantic.api.SemanticMetricDefinition
import cc.monomer.semantic.api.SemanticMetricInput
import cc.monomer.semantic.api.SemanticModelDefinition
import cc.monomer.semantic.api.SemanticQuerySpec
import cc.monomer.semantic.api.SemanticRenderRequest
import cc.monomer.semantic.api.SemanticRenderResult
import cc.monomer.semantic.api.SemanticSourceRelation
import cc.monomer.semantic.api.SemanticTimeGranularity
import cc.monomer.semantic.api.SemanticTimeSpine
import cc.monomer.semantic.api.SemanticTimeSpineDefinition
import cc.monomer.semantic.api.SemanticTimeSpinePrimaryColumn
import cc.monomer.semantic.api.SemanticTimeSpineRelation
import cc.monomer.semantic.application.SemanticMetricFlowManifest
import cc.monomer.semantic.application.SemanticMetricFlowGroupByResult
import cc.monomer.semantic.application.SemanticMetricFlowOutputColumn
import cc.monomer.semantic.application.SemanticMetricFlowOutputColumnSource
import cc.monomer.semantic.application.SemanticMetricFlowPort
import cc.monomer.semantic.application.SemanticMetricFlowRenderResult
import cc.monomer.semantic.application.SemanticMetricFlowValidationResult
import cc.monomer.semantic.application.SemanticQueryService
import cc.monomer.semantic.application.SemanticRepository
import cc.monomer.semantic.application.SemanticTransactionPort
import cc.monomer.semantic.application.SemanticTimeSpineRepository
import cc.monomer.semantic.application.SemanticAuthorityService
import cc.monomer.semantic.application.SemanticCurrentReadService
import cc.monomer.semantic.domain.CurrentMetric
import cc.monomer.semantic.domain.CurrentSemanticModel
import cc.monomer.semantic.domain.CurrentTimeSpineConfiguration
import cc.monomer.semantic.domain.SemanticMutationReceipt
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class SemanticCurrentDefinitionBoundaryTest {
    @Test
    fun `model history survives delete with exact snapshots and original ID restore uses normal name conflict`() = runBlocking {
        val repository = InMemorySemanticRepository()
        val workspace = WorkspaceId.generate()
        val identity = evidence(workspace, WorkspaceRole.EDITOR)
        val service = historyAuthorityService(repository)
        val definition = model(ConnectorId.generate(), "amount")
        val create = PutSemanticModelCommand(identity.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE), "create-history", null, definition, emptyList())
        val original = assertIs<SemanticMutationResult.Committed<cc.monomer.semantic.api.SemanticModelWriteResult>>(service.putModel(create)).value.model
        val changed = definition.copy(description = "Edited")
        val update = PutSemanticModelCommand(identity.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_UPDATE), "update-history", original.semanticModelId, changed, emptyList())
        service.putModel(update)
        service.putModel(update)
        service.putModel(create)
        assertEquals(2, repository.changes.size)
        val read = SemanticCurrentReadService(repository, repository, ImmediateTransactionPort, EmptyConnectorRead,
            io.mockk.mockk(relaxed = true),
        )
        assertEquals(listOf(false, true), assertNotNull(read.readModelHistory(workspace, original.semanticModelId, null, 10)).changes.map { it.restorable })
        val delete = DeleteSemanticModelCommand(identity.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_DELETE), original.semanticModelId)
        assertIs<SemanticMutationResult.Committed<Unit>>(service.deleteModel(delete))
        assertEquals(SemanticMutationResult.NotFoundOrOutsideWorkspace, service.deleteModel(delete))
        assertEquals(SemanticMutationResult.NotFoundOrOutsideWorkspace, service.putModel(update.copy(idempotencyKey = "after-delete")))
        val first = assertNotNull(read.readModelHistory(workspace, original.semanticModelId, null, 1))
        assertTrue(first.deleted)
        assertEquals(SemanticDefinitionChangeAction.DELETED, first.changes.single().action)
        assertEquals(changed, (first.changes.single().definition as SemanticDefinitionSnapshot.Model).definition)
        assertTrue(first.changes.single().restorable)
        val second = assertNotNull(read.readModelHistory(workspace, original.semanticModelId, first.next, 2))
        assertEquals(listOf(SemanticDefinitionChangeAction.UPDATED, SemanticDefinitionChangeAction.CREATED), second.changes.map { it.action })
        assertEquals(listOf(false, true), second.changes.map { it.restorable })
        for (version in first.changes + second.changes) {
            assertEquals(version, read.readModelVersion(workspace, original.semanticModelId, version.changeId))
            assertEquals(null, read.readModelVersion(WorkspaceId.generate(), original.semanticModelId, version.changeId))
        }
        assertEquals(null, second.next)
        assertEquals(null, read.readModelHistory(WorkspaceId.generate(), original.semanticModelId, null, 10))
        val recreated = assertIs<SemanticMutationResult.Committed<cc.monomer.semantic.api.SemanticModelWriteResult>>(service.putModel(create.copy(idempotencyKey = "recreate"))).value.model
        assertTrue(recreated.semanticModelId != original.semanticModelId)
        val restore = RestoreSemanticModelCommand(identity.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_UPDATE), "restore", original.semanticModelId, second.changes.last().changeId)
        assertEquals(SemanticMutationResult.Conflict, service.restoreModel(restore))
        service.deleteModel(delete.copy(semanticModelId = recreated.semanticModelId))
        val restored = assertIs<SemanticMutationResult.Committed<SemanticModelView>>(service.restoreModel(restore)).value
        assertEquals(original.semanticModelId, restored.semanticModelId)
        assertEquals(definition, restored.definition)
        val recordCount = repository.changes.size
        service.restoreModel(restore)
        assertEquals(recordCount, repository.changes.size)
        assertTrue(!assertNotNull(read.readModelHistory(workspace, original.semanticModelId, null, 10)).deleted)
        assertEquals(1, assertNotNull(read.readModelHistory(workspace, recreated.semanticModelId, null, 10)).changes.count { it.action == SemanticDefinitionChangeAction.CREATED })
    }

    @Test
    fun `metric records retain Agent app and restoration of a live definition appends one normal update`() = runBlocking {
        val repository = InMemorySemanticRepository()
        val workspace = WorkspaceId.generate()
        val identity = evidence(workspace, WorkspaceRole.EDITOR).copy(credentialSurface = CredentialSurface.AGENT,
            agentClientId = cc.monomer.identity.api.AgentClientId("https://example.com/agent"))
        val service = historyAuthorityService(repository)
        val definition = simpleMetric("revenue")
        val create = PutMetricCommand(identity.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE), "metric-history", null, definition)
        val metric = assertIs<SemanticMutationResult.Committed<cc.monomer.semantic.api.MetricView>>(service.putMetric(create)).value
        val created = repository.changes.single().third
        service.putMetric(create.copy(authorization = identity.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_UPDATE), idempotencyKey = "metric-edit", metricId = metric.metricId, definition = definition.copy(description = "Changed")))
        val restore = RestoreMetricCommand(identity.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_UPDATE), "metric-restore", metric.metricId, created.changeId)
        assertEquals(definition, assertIs<SemanticMutationResult.Committed<cc.monomer.semantic.api.MetricView>>(service.restoreMetric(restore)).value.definition)
        service.restoreMetric(restore)
        val read = SemanticCurrentReadService(repository, repository, ImmediateTransactionPort, EmptyConnectorRead,
            io.mockk.mockk(relaxed = true),
        )
        val history = assertNotNull(read.readMetricHistory(workspace, metric.metricId, null, 10))
        assertEquals(listOf(SemanticDefinitionChangeAction.UPDATED, SemanticDefinitionChangeAction.UPDATED, SemanticDefinitionChangeAction.CREATED), history.changes.map { it.action })
        assertEquals(listOf(false, true, false), history.changes.map { it.restorable })
        assertTrue(history.changes.all { it.actorId == identity.actor.id && it.credentialSurface == CredentialSurface.AGENT && it.agentClientId == identity.agentClientId })
        service.deleteMetric(DeleteMetricCommand(identity.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_DELETE), metric.metricId))
        val first = assertNotNull(read.readMetricHistory(workspace, metric.metricId, null, 2))
        val second = assertNotNull(read.readMetricHistory(workspace, metric.metricId, first.next, 2))
        assertEquals(listOf(true, false), first.changes.map { it.restorable })
        assertEquals(listOf(true, false), second.changes.map { it.restorable })
        for (version in first.changes + second.changes) {
            assertEquals(version, read.readMetricVersion(workspace, metric.metricId, version.changeId))
            assertEquals(null, read.readMetricVersion(WorkspaceId.generate(), metric.metricId, version.changeId))
        }
        assertEquals(metric.metricId, assertIs<SemanticMutationResult.Committed<cc.monomer.semantic.api.MetricView>>(service.restoreMetric(restore.copy(idempotencyKey = "metric-restore-deleted"))).value.metricId)
    }

    @Test
    fun `model restore availability preserves source relation array order`() = runBlocking {
        val repository = InMemorySemanticRepository()
        val workspace = WorkspaceId.generate()
        val identity = evidence(workspace, WorkspaceRole.EDITOR)
        val service = historyAuthorityService(repository)
        val definition = model(ConnectorId.generate(), "amount").let { model ->
            model.copy(sourceRelations = model.sourceRelations + SemanticSourceRelation(ConnectorId.generate(), null, "analytics", "orders"))
        }
        val original = assertIs<SemanticMutationResult.Committed<cc.monomer.semantic.api.SemanticModelWriteResult>>(service.putModel(
            PutSemanticModelCommand(identity.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE), "model-order-create", null, definition, emptyList()))).value.model
        val update = PutSemanticModelCommand(identity.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_UPDATE), "model-order-edit", original.semanticModelId,
            definition.copy(sourceRelations = definition.sourceRelations.reversed()),
aggregateMetrics = emptyList(),)
        service.putModel(update)
        service.putModel(update.copy(idempotencyKey = "model-order-duplicate"))
        val read = SemanticCurrentReadService(repository, repository, ImmediateTransactionPort, EmptyConnectorRead,
            io.mockk.mockk(relaxed = true),
        )
        assertEquals(listOf(false, false, true), assertNotNull(read.readModelHistory(workspace, original.semanticModelId, null, 10)).changes.map { it.restorable })
        service.deleteModel(DeleteSemanticModelCommand(identity.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_DELETE), original.semanticModelId))
        assertEquals(listOf(true, false, false, true), assertNotNull(read.readModelHistory(workspace, original.semanticModelId, null, 10)).changes.map { it.restorable })
    }

    @Test
    fun `metric restore availability preserves nested filter array order and exact string values`() = runBlocking {
        val repository = InMemorySemanticRepository()
        val workspace = WorkspaceId.generate()
        val identity = evidence(workspace, WorkspaceRole.EDITOR)
        val service = historyAuthorityService(repository)
        val predicate = SemanticMetricFilter.Predicate(SemanticElementReference(SemanticElementName("orders"), SemanticElementName("status")),
            SemanticMetricFilterOperator.IN, listOf("1", "1.0"))
        val definition = simpleMetric("revenue").copy(filters = listOf(SemanticMetricFilter.Not(SemanticMetricFilter.And(listOf(predicate)))))
        val original = assertIs<SemanticMutationResult.Committed<MetricView>>(service.putMetric(
            PutMetricCommand(identity.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE), "metric-order-create", null, definition))).value
        val changed = definition.copy(filters = listOf(SemanticMetricFilter.Not(SemanticMetricFilter.And(listOf(predicate.copy(values = predicate.values.reversed()))))))
        val update = PutMetricCommand(identity.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_UPDATE), "metric-order-edit", original.metricId, changed)
        service.putMetric(update)
        service.putMetric(update.copy(idempotencyKey = "metric-order-duplicate"))
        val read = SemanticCurrentReadService(repository, repository, ImmediateTransactionPort, EmptyConnectorRead,
            io.mockk.mockk(relaxed = true),
        )
        assertEquals(listOf(false, false, true), assertNotNull(read.readMetricHistory(workspace, original.metricId, null, 10)).changes.map { it.restorable })
        service.deleteMetric(DeleteMetricCommand(identity.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_DELETE), original.metricId))
        assertEquals(listOf(true, false, false, true), assertNotNull(read.readMetricHistory(workspace, original.metricId, null, 10)).changes.map { it.restorable })
    }

    @Test
    fun `delete role intent and Workspace boundaries reject before any history change`() = runBlocking {
        val repository = InMemorySemanticRepository()
        val workspace = WorkspaceId.generate()
        val editor = evidence(workspace, WorkspaceRole.EDITOR)
        val service = historyAuthorityService(repository)
        val model = assertIs<SemanticMutationResult.Committed<cc.monomer.semantic.api.SemanticModelWriteResult>>(service.putModel(PutSemanticModelCommand(editor.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE), "model-permissions", null, model(ConnectorId.generate(), "amount"), emptyList()))).value.model
        val metric = assertIs<SemanticMutationResult.Committed<cc.monomer.semantic.api.MetricView>>(service.putMetric(PutMetricCommand(editor.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE), "metric-permissions", null, simpleMetric("revenue")))).value
        val explorer = evidence(workspace, WorkspaceRole.EXPLORER)
        assertEquals(SemanticMutationResult.InvalidAuthorization, service.deleteModel(DeleteSemanticModelCommand(explorer.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_DELETE), model.semanticModelId)))
        assertEquals(SemanticMutationResult.InvalidAuthorization, service.deleteMetric(DeleteMetricCommand(explorer.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_DELETE), metric.metricId)))
        assertEquals(SemanticMutationResult.InvalidAuthorization, service.deleteMetric(DeleteMetricCommand(editor.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_DELETE), metric.metricId)))
        val other = evidence(WorkspaceId.generate(), WorkspaceRole.EDITOR)
        assertEquals(SemanticMutationResult.NotFoundOrOutsideWorkspace, service.deleteModel(DeleteSemanticModelCommand(other.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_DELETE), model.semanticModelId)))
        assertEquals(SemanticMutationResult.NotFoundOrOutsideWorkspace, service.deleteMetric(DeleteMetricCommand(other.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_DELETE), metric.metricId)))
        assertEquals(2, repository.changes.size)
        assertEquals(1, repository.models.size)
        assertEquals(1, repository.metrics.size)
    }

    @Test
    fun `mutation intent must match both resource kind and create or update before repository work`() = runBlocking {
        val repository = InMemorySemanticRepository()
        val workspace = WorkspaceId.generate()
        val service = SemanticAuthorityService(repository, ImmediateTransactionPort, Clock.fixed(NOW, ZoneOffset.UTC),
            object : cc.monomer.semantic.api.SemanticSearchProjection {
                override fun deleteModel(workspaceId: WorkspaceId, name: String) = Unit
                override fun deleteMetric(workspaceId: WorkspaceId, name: String) = Unit
                override fun upsertModel(fields: cc.monomer.semantic.api.SemanticModelSearchFields) = error("unexpected projection")
                override fun upsertMetric(fields: cc.monomer.semantic.api.MetricSearchFields) = error("unexpected projection")
            })
        val identity = evidence(workspace, WorkspaceRole.OWNER)
        val modelDefinition = model(ConnectorId.generate(), "amount")
        val metricDefinition = simpleMetric("revenue")
        val intents = cc.monomer.identity.api.MutationIntent.entries
        for (intent in intents) {
            val context = identity.forMutation(intent)
            for (id in listOf(null, cc.monomer.semantic.api.SemanticModelId.generate())) {
                val expected = if (id == null) cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE else cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_UPDATE
                if (intent != expected) assertEquals(cc.monomer.semantic.api.SemanticMutationResult.InvalidAuthorization,
                    service.putModel(PutSemanticModelCommand(context, "model-$intent-$id", id, modelDefinition, emptyList())))
            }
            for (id in listOf(null, MetricId.generate())) {
                val expected = if (id == null) cc.monomer.identity.api.MutationIntent.METRIC_CREATE else cc.monomer.identity.api.MutationIntent.METRIC_UPDATE
                if (intent != expected) assertEquals(cc.monomer.semantic.api.SemanticMutationResult.InvalidAuthorization,
                    service.putMetric(PutMetricCommand(context, "metric-$intent-$id", id, metricDefinition)))
            }
        }
        assertTrue(repository.models.isEmpty())
        assertTrue(repository.metrics.isEmpty())
    }

    @Test
    fun `latest mutation attribution survives create and update replays on both current definitions`(): Unit = runBlocking {
        val repository = InMemorySemanticRepository()
        val workspace = WorkspaceId.generate()
        val web = evidence(workspace, WorkspaceRole.EDITOR)
        val claude = cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata")
        val agent = evidence(workspace, WorkspaceRole.EDITOR).copy(credentialSurface = CredentialSurface.AGENT, agentClientId = claude)
        fun service(at: Instant) = SemanticAuthorityService(repository, ImmediateTransactionPort, Clock.fixed(at, ZoneOffset.UTC),
            object : cc.monomer.semantic.api.SemanticSearchProjection {
                override fun deleteModel(workspaceId: WorkspaceId, name: String) = Unit
                override fun deleteMetric(workspaceId: WorkspaceId, name: String) = Unit
                override fun upsertModel(fields: cc.monomer.semantic.api.SemanticModelSearchFields) = Unit
                override fun upsertMetric(fields: cc.monomer.semantic.api.MetricSearchFields) = Unit
            })
        val createModel = PutSemanticModelCommand(web.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE), "create-model-attribution", null, model(ConnectorId.generate(), "amount"), emptyList())
        val createMetric = PutMetricCommand(web.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE), "create-metric-attribution", null, simpleMetric("revenue"))
        service(NOW).putModel(createModel)
        service(NOW).putMetric(createMetric)
        assertEquals(Triple(web.actor.id, CredentialSurface.WEB, null), repository.lastModelAttribution)
        assertEquals(Triple(web.actor.id, CredentialSurface.WEB, null), repository.lastMetricAttribution)
        val updateModel = createModel.copy(authorization = agent.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_UPDATE), idempotencyKey = "update-model-attribution", semanticModelId = repository.models.keys.single())
        val updateMetric = createMetric.copy(authorization = agent.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_UPDATE), idempotencyKey = "update-metric-attribution", metricId = repository.metrics.keys.single())
        service(NOW.plusSeconds(1)).putModel(updateModel)
        service(NOW.plusSeconds(1)).putMetric(updateMetric)
        service(NOW.plusSeconds(2)).putModel(createModel)
        service(NOW.plusSeconds(2)).putMetric(createMetric)
        service(NOW.plusSeconds(2)).putModel(updateModel)
        service(NOW.plusSeconds(2)).putMetric(updateMetric)
        assertEquals(Triple(agent.actor.id, CredentialSurface.AGENT, claude), repository.lastModelAttribution)
        assertEquals(Triple(agent.actor.id, CredentialSurface.AGENT, claude), repository.lastMetricAttribution)
        assertEquals(NOW.plusSeconds(1), repository.models.values.single().updatedAt)
        assertEquals(NOW.plusSeconds(1), repository.metrics.values.single().updatedAt)
        assertEquals(1, repository.modelUpdateCount)
        assertEquals(1, repository.metricUpdateCount)
    }

    @Test
    fun `seed definitions record no attribution while authorization and receipts stay with the initiator`(): Unit = runBlocking {
        val repository = InMemorySemanticRepository()
        val workspace = WorkspaceId.generate()
        val initiator = evidence(workspace, WorkspaceRole.OWNER)
        val service = authorityService(repository)
        val modelCommand = PutSemanticModelCommand(initiator.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE), "sample:model:orders", null,
            model(ConnectorId.generate(), "amount"), listOf(SemanticAggregateMetric(SemanticElementName("revenue"), SemanticElementName("total_revenue"), null, null, null, emptyList())))
        val metricCommand = PutMetricCommand(initiator.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE), "sample:metric:net_revenue", null, simpleMetric("net_revenue"))
        val seededModel = assertIs<SemanticMutationResult.Committed<SemanticModelWriteResult>>(service.seedModel(modelCommand)).value
        val seededMetric = assertIs<SemanticMutationResult.Committed<MetricView>>(service.seedMetric(metricCommand)).value
        val unrecorded = Triple<PrincipalId?, CredentialSurface?, cc.monomer.identity.api.AgentClientId?>(null, null, null)
        assertEquals(listOf("total_revenue"), seededModel.aggregateMetrics.map { it.definition.name.value })
        assertEquals(unrecorded, repository.lastModelAttribution)
        assertEquals(listOf(unrecorded, unrecorded), repository.metricAttributions)
        assertEquals(3, repository.changes.size)
        assertTrue(repository.changes.all { (_, _, version) ->
            version.action == SemanticDefinitionChangeAction.CREATED && version.actorId == null && version.credentialSurface == null && version.agentClientId == null
        })
        assertEquals(initiator.actor.id, repository.findMutationReceipt(workspace, modelCommand.idempotencyKey)?.actorId)
        assertEquals(initiator.actor.id, repository.findMutationReceipt(workspace, metricCommand.idempotencyKey)?.actorId)

        assertEquals(SemanticMutationResult.Committed(seededModel), service.seedModel(modelCommand))
        assertEquals(SemanticMutationResult.Committed(seededMetric), service.seedMetric(metricCommand))
        val other = evidence(workspace, WorkspaceRole.OWNER)
        assertEquals(SemanticMutationResult.IdempotencyConflict,
            service.seedMetric(metricCommand.copy(authorization = other.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE))))
        assertEquals(1, repository.modelInsertCount)
        assertEquals(2, repository.metricInsertCount)
        assertEquals(3, repository.changes.size)

        val edit = PutMetricCommand(initiator.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_UPDATE), "edit-net-revenue", seededMetric.metricId,
            simpleMetric("net_revenue").copy(label = "Net revenue"))
        assertIs<SemanticMutationResult.Committed<MetricView>>(service.putMetric(edit))
        assertEquals(Triple(initiator.actor.id, CredentialSurface.WEB, null), repository.lastMetricAttribution)
        val update = repository.changes.last().third
        assertEquals(SemanticDefinitionChangeAction.UPDATED, update.action)
        assertEquals(initiator.actor.id, update.actorId)
        assertEquals(CredentialSurface.WEB, update.credentialSurface)
    }

    @Test
    fun `seed definitions are create only and admit only their creation intent`(): Unit = runBlocking {
        val repository = InMemorySemanticRepository()
        val workspace = WorkspaceId.generate()
        val service = authorityService(repository)
        val identity = evidence(workspace, WorkspaceRole.OWNER)
        val modelDefinition = model(ConnectorId.generate(), "amount")
        val metricDefinition = simpleMetric("revenue")
        for (intent in cc.monomer.identity.api.MutationIntent.entries) {
            val context = identity.forMutation(intent)
            for (id in listOf(null, cc.monomer.semantic.api.SemanticModelId.generate())) {
                if (id != null || intent != cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE) {
                    assertEquals(SemanticMutationResult.InvalidAuthorization, service.seedModel(PutSemanticModelCommand(context, "model-$intent-$id", id, modelDefinition, emptyList())))
                }
            }
            for (id in listOf(null, MetricId.generate())) {
                if (id != null || intent != cc.monomer.identity.api.MutationIntent.METRIC_CREATE) {
                    assertEquals(SemanticMutationResult.InvalidAuthorization, service.seedMetric(PutMetricCommand(context, "metric-$intent-$id", id, metricDefinition)))
                }
            }
        }
        val viewer = evidence(workspace, WorkspaceRole.VIEWER)
        assertEquals(SemanticMutationResult.InvalidAuthorization, service.seedModel(PutSemanticModelCommand(
            viewer.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE), "viewer-model", null, modelDefinition, emptyList())))
        assertEquals(SemanticMutationResult.InvalidAuthorization, service.seedMetric(PutMetricCommand(
            viewer.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE), "viewer-metric", null, metricDefinition)))
        assertTrue(repository.models.isEmpty())
        assertTrue(repository.metrics.isEmpty())
        assertTrue(repository.changes.isEmpty())
    }

    @Test
    fun `search projection includes owner labels and references but excludes expressions and propagates failure`(): Unit = runBlocking {
        val repository = InMemorySemanticRepository()
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val projectedModels = mutableListOf<cc.monomer.semantic.api.SemanticModelSearchFields>()
        val projectedMetrics = mutableListOf<cc.monomer.semantic.api.MetricSearchFields>()
        var failProjection = false
        val service = SemanticAuthorityService(repository, ImmediateTransactionPort, Clock.fixed(NOW, ZoneOffset.UTC),
            object : cc.monomer.semantic.api.SemanticSearchProjection {
                override fun deleteModel(workspaceId: WorkspaceId, name: String) = Unit
                override fun deleteMetric(workspaceId: WorkspaceId, name: String) = Unit
                override fun upsertModel(fields: cc.monomer.semantic.api.SemanticModelSearchFields) {
                    check(!failProjection) { "search unavailable" }
                    projectedModels += fields
                }
                override fun upsertMetric(fields: cc.monomer.semantic.api.MetricSearchFields) {
                    check(!failProjection) { "search unavailable" }
                    projectedMetrics += fields
                }
            },
        )
        val definition = model(connector, "private_sql_expression").let { base -> base.copy(
            label = "Orders label", description = "Orders description",
            entities = base.entities.map { it.copy(label = "Entity label", description = "Entity description") },
            dimensions = base.dimensions.map { it.copy(label = "Dimension label", description = "Dimension description") },
            measures = base.measures.map { it.copy(label = "Measure label", description = "Measure description") },
        ) }
        val authorization = evidence(workspace, WorkspaceRole.EDITOR)
        val modelCommand = PutSemanticModelCommand(authorization.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE), "search-model", null, definition, emptyList())
        service.putModel(modelCommand)
        service.putModel(modelCommand)
        val fields = projectedModels.single()
        assertEquals(listOf("orders", "Orders label"), fields.primaryNames)
        assertTrue(fields.descriptions.containsAll(listOf("Orders description", "Entity description", "Dimension description", "Measure description")))
        assertTrue(fields.structuralNames.containsAll(listOf("Entity label", "Dimension label", "Measure label", "analytics", "orders")))
        assertTrue("private_sql_expression" !in fields.structuralNames)
        service.putMetric(PutMetricCommand(authorization.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE), "search-metric", null,
            derivedMetric("derived", "revenue").copy(expression = "private_formula")))
        assertEquals(listOf("DERIVED", "revenue"), projectedMetrics.single().structuralNames)
        failProjection = true
        kotlin.test.assertFailsWith<IllegalStateException> {
            service.putMetric(PutMetricCommand(authorization.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE), "failed-search", null, simpleMetric("failed_metric")))
        }
        assertEquals(null, repository.findMutationReceipt(workspace, "failed-search"))
    }

    @Test
    fun `direct child list uses name boundary limit plus one and admits later inserts after the boundary`(): Unit = runBlocking {
        val repository = InMemorySemanticRepository()
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        fun insertModel(name: String) {
            val id = cc.monomer.semantic.api.SemanticModelId.generate()
            repository.models[id] = CurrentSemanticModel(
                id = id,
                workspaceId = workspace,
                definition = model(connector, "amount").copy(name = SemanticElementName(name)),
                createdAt = NOW,
                updatedAt = NOW,
            )
        }
        fun insertMetric(name: String) {
            val id = MetricId.generate()
            repository.metrics[id] = CurrentMetric(
                id = id,
                workspaceId = workspace,
                definition = simpleMetric(name),
                createdAt = NOW,
                updatedAt = NOW,
            )
        }
        insertModel("alpha")
        insertModel("gamma")
        insertMetric("alpha")
        insertMetric("gamma")
        val service = SemanticCurrentReadService(repository, repository, ImmediateTransactionPort, EmptyConnectorRead,
            io.mockk.mockk(relaxed = true),
        )

        val first = service.listModels(workspace, null, 1)
        val firstMetrics = service.listMetrics(workspace, null, 1)
        assertEquals(listOf("alpha"), first.items.map { it.definition.name.value })
        assertEquals(listOf("alpha"), firstMetrics.items.map { it.definition.name.value })
        assertEquals(2, repository.lastModelListLimit)
        assertEquals(2, repository.lastMetricListLimit)

        insertModel("beta")
        insertMetric("beta")
        val second = service.listModels(workspace, assertNotNull(first.next), 1)
        val third = service.listModels(workspace, assertNotNull(second.next), 1)
        val secondMetrics = service.listMetrics(workspace, assertNotNull(firstMetrics.next), 1)
        val thirdMetrics = service.listMetrics(workspace, assertNotNull(secondMetrics.next), 1)

        assertEquals(listOf("beta"), second.items.map { it.definition.name.value })
        assertEquals(listOf("gamma"), third.items.map { it.definition.name.value })
        assertEquals(null, third.next)
        assertEquals(listOf("beta"), secondMetrics.items.map { it.definition.name.value })
        assertEquals(listOf("gamma"), thirdMetrics.items.map { it.definition.name.value })
        assertEquals(null, thirdMetrics.next)
    }

    @Test
    fun `current resource search pages in owner order and latest reads stay bounded`(): Unit = runBlocking {
        val repository = InMemorySemanticRepository()
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val older = NOW.minusSeconds(60)
        val modelDefinitions = listOf("z_orders", "accounts", "alpha_orders")
        modelDefinitions.forEachIndexed { index, name ->
            val id = cc.monomer.semantic.api.SemanticModelId.generate()
            repository.models[id] = CurrentSemanticModel(
                id = id,
                workspaceId = workspace,
                definition = model(connector, "amount").copy(name = SemanticElementName(name)),
                createdAt = older,
                updatedAt = older.plusSeconds(index.toLong()),
            )
        }
        listOf("z_revenue", "accounts", "alpha_revenue").forEachIndexed { index, name ->
            val id = MetricId.generate()
            repository.metrics[id] = CurrentMetric(
                id = id,
                workspaceId = workspace,
                definition = simpleMetric(name),
                createdAt = older,
                updatedAt = older.plusSeconds(index.toLong()),
            )
        }
        val service = SemanticCurrentReadService(repository, repository, ImmediateTransactionPort, EmptyConnectorRead,
            io.mockk.mockk(relaxed = true),
        )

        val firstModels = service.searchModels(workspace, "ORDERS", null, 1)
        assertEquals(listOf("alpha_orders"), firstModels.items.map { it.definition.name.value })
        val secondModels = service.searchModels(
            workspace,
            "orders",
            assertNotNull(firstModels.nextCursor),
            1,
        )
        assertEquals(listOf("z_orders"), secondModels.items.map { it.definition.name.value })
        assertEquals(null, secondModels.nextCursor)

        val firstMetrics = service.searchMetrics(workspace, "revenue", null, 1)
        assertEquals(listOf("alpha_revenue"), firstMetrics.items.map { it.definition.name.value })
        val secondMetrics = service.searchMetrics(
            workspace,
            "revenue",
            assertNotNull(firstMetrics.nextCursor),
            1,
        )
        assertEquals(listOf("z_revenue"), secondMetrics.items.map { it.definition.name.value })
        assertEquals(null, secondMetrics.nextCursor)
        assertEquals("alpha_orders", service.findLatestModel(workspace)?.definition?.name?.value)
        assertEquals("alpha_revenue", service.findLatestMetric(workspace)?.definition?.name?.value)
    }

    @Test
    fun `create idempotency key is bound to the definition name`(): Unit = runBlocking {
        val repository = InMemorySemanticRepository()
        var modelProjectionCount = 0
        val service = SemanticAuthorityService(
            repository,
            ImmediateTransactionPort,
            Clock.fixed(NOW, ZoneOffset.UTC),
            object : cc.monomer.semantic.api.SemanticSearchProjection {
                override fun deleteModel(workspaceId: WorkspaceId, name: String) = Unit
                override fun deleteMetric(workspaceId: WorkspaceId, name: String) = Unit
                override fun upsertModel(fields: cc.monomer.semantic.api.SemanticModelSearchFields) {
                    modelProjectionCount += 1
                }

                override fun upsertMetric(fields: cc.monomer.semantic.api.MetricSearchFields) = Unit
            },
        )
        val workspace = WorkspaceId.generate()
        val authorization = evidence(workspace, WorkspaceRole.EDITOR)
        val connector = ConnectorId.generate()
        val originalDefinition = model(connector, measureExpression = "amount").copy(description = "original")

        val created = assertIs<cc.monomer.semantic.api.SemanticMutationResult.Committed<*>>(
            service.putModel(PutSemanticModelCommand(authorization.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE), "model-create-key", null, originalDefinition, emptyList())),
        ).value.let { (it as cc.monomer.semantic.api.SemanticModelWriteResult).model }

        val replayWithChangedBody = assertIs<cc.monomer.semantic.api.SemanticMutationResult.Committed<*>>(
            service.putModel(
                PutSemanticModelCommand(
                    authorization.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE),
                    "model-create-key",
                    null,
                    originalDefinition.copy(description = "changed replay body"),
                    aggregateMetrics = emptyList(),
                ),
            ),
        ).value.let { (it as cc.monomer.semantic.api.SemanticModelWriteResult).model }
        assertEquals(created, replayWithChangedBody)
        assertEquals(1, repository.models.size)
        assertEquals(1, repository.modelInsertCount)
        assertEquals(0, repository.modelUpdateCount)
        assertEquals(1, modelProjectionCount)

        val reusedForAnotherName = service.putModel(
            PutSemanticModelCommand(
                authorization.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE),
                "model-create-key",
                null,
                originalDefinition.copy(name = SemanticElementName("other_orders")),
                aggregateMetrics = emptyList(),
            ),
        )
        assertEquals(
            cc.monomer.semantic.api.SemanticMutationResult.IdempotencyConflict,
            reusedForAnotherName,
        )
        assertEquals(1, repository.models.size)
        assertEquals(1, repository.modelInsertCount)
        assertEquals(0, repository.modelUpdateCount)
        assertEquals(1, modelProjectionCount)

        val updatedDefinition = model(connector, measureExpression = "amount_usd").copy(description = "latest")
        val updated = assertIs<cc.monomer.semantic.api.SemanticMutationResult.Committed<*>>(
            service.putModel(
                PutSemanticModelCommand(authorization.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_UPDATE), "model-update-key", created.semanticModelId, updatedDefinition, emptyList()),
            ),
        ).value.let { (it as cc.monomer.semantic.api.SemanticModelWriteResult).model }
        assertEquals("amount_usd", updated.definition.measures.single().expression)
        assertEquals(1, repository.models.size)
        assertEquals(1, repository.modelInsertCount)
        assertEquals(1, repository.modelUpdateCount)
        assertEquals(2, modelProjectionCount)

        val oldKeyReplay = assertIs<cc.monomer.semantic.api.SemanticMutationResult.Committed<*>>(
            service.putModel(
                PutSemanticModelCommand(
                    authorization.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE),
                    "model-create-key",
                    null,
                    originalDefinition.copy(description = "stale old request"),
                    aggregateMetrics = emptyList(),
                ),
            ),
        ).value.let { (it as cc.monomer.semantic.api.SemanticModelWriteResult).model }
        assertEquals(updated, oldKeyReplay)
        assertEquals("amount_usd", oldKeyReplay.definition.measures.single().expression)
        assertEquals(1, repository.models.size)
        assertEquals(1, repository.modelInsertCount)
        assertEquals(1, repository.modelUpdateCount)
        assertEquals(2, modelProjectionCount)
    }

    @Test
    fun `metric idempotency replays current resource without payload equality`(): Unit = runBlocking {
        val repository = InMemorySemanticRepository()
        var metricProjectionCount = 0
        val service = SemanticAuthorityService(
            repository,
            ImmediateTransactionPort,
            Clock.fixed(NOW, ZoneOffset.UTC),
            object : cc.monomer.semantic.api.SemanticSearchProjection {
                override fun deleteModel(workspaceId: WorkspaceId, name: String) = Unit
                override fun deleteMetric(workspaceId: WorkspaceId, name: String) = Unit
                override fun upsertModel(fields: cc.monomer.semantic.api.SemanticModelSearchFields) = Unit

                override fun upsertMetric(fields: cc.monomer.semantic.api.MetricSearchFields) {
                    metricProjectionCount += 1
                }
            },
        )
        val workspace = WorkspaceId.generate()
        val authorization = evidence(workspace, WorkspaceRole.EDITOR)
        val originalDefinition = simpleMetric("orders_metric").copy(description = "original")

        val created = assertIs<cc.monomer.semantic.api.SemanticMutationResult.Committed<*>>(
            service.putMetric(PutMetricCommand(authorization.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE), "metric-create-key", null, originalDefinition)),
        ).value as cc.monomer.semantic.api.MetricView

        val replayWithChangedBody = assertIs<cc.monomer.semantic.api.SemanticMutationResult.Committed<*>>(
            service.putMetric(
                PutMetricCommand(
                    authorization.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE),
                    "metric-create-key",
                    null,
                    originalDefinition.copy(description = "changed replay body"),
                ),
            ),
        ).value as cc.monomer.semantic.api.MetricView
        assertEquals(created, replayWithChangedBody)
        assertEquals(1, repository.metrics.size)
        assertEquals(1, repository.metricInsertCount)
        assertEquals(0, repository.metricUpdateCount)
        assertEquals(1, metricProjectionCount)

        val reusedForAnotherName = service.putMetric(
            PutMetricCommand(
                authorization.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE),
                "metric-create-key",
                null,
                originalDefinition.copy(name = SemanticElementName("other_metric")),
            ),
        )
        assertEquals(
            cc.monomer.semantic.api.SemanticMutationResult.IdempotencyConflict,
            reusedForAnotherName,
        )
        assertEquals(1, repository.metrics.size)
        assertEquals(1, repository.metricInsertCount)
        assertEquals(0, repository.metricUpdateCount)
        assertEquals(1, metricProjectionCount)

        val updatedDefinition = simpleMetric("orders_metric", measure = "amount_usd").copy(description = "latest")
        val updated = assertIs<cc.monomer.semantic.api.SemanticMutationResult.Committed<*>>(
            service.putMetric(
                PutMetricCommand(authorization.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_UPDATE), "metric-update-key", created.metricId, updatedDefinition),
            ),
        ).value as cc.monomer.semantic.api.MetricView
        assertEquals("amount_usd", (updated.definition as SemanticMetricDefinition.Simple).measure.value)
        assertEquals(1, repository.metrics.size)
        assertEquals(1, repository.metricInsertCount)
        assertEquals(1, repository.metricUpdateCount)
        assertEquals(2, metricProjectionCount)

        val oldKeyReplay = assertIs<cc.monomer.semantic.api.SemanticMutationResult.Committed<*>>(
            service.putMetric(
                PutMetricCommand(
                    authorization.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE),
                    "metric-create-key",
                    null,
                    originalDefinition.copy(description = "stale old request"),
                ),
            ),
        ).value as cc.monomer.semantic.api.MetricView
        assertEquals(updated, oldKeyReplay)
        assertEquals("amount_usd", (oldKeyReplay.definition as SemanticMetricDefinition.Simple).measure.value)
        assertEquals(1, repository.metrics.size)
        assertEquals(1, repository.metricInsertCount)
        assertEquals(1, repository.metricUpdateCount)
        assertEquals(2, metricProjectionCount)
    }

    @Test
    fun `current writes are workspace scoped and last write wins without resource authority refs`(): Unit = runBlocking {
        val repository = InMemorySemanticRepository()
        val service = authorityService(repository)
        val workspace = WorkspaceId.generate()
        val otherWorkspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()

        val created = assertIs<cc.monomer.semantic.api.SemanticMutationResult.Committed<*>>(
            service.putModel(
                PutSemanticModelCommand(
                    authorization = evidence(workspace, WorkspaceRole.EDITOR).forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE),
                    idempotencyKey = "model-create",
                    semanticModelId = null,
                    definition = model(connector, measureExpression = "amount"),
                    aggregateMetrics = emptyList(),
                ),
            ),
        )
        val modelId = (created.value as cc.monomer.semantic.api.SemanticModelWriteResult).model.semanticModelId

        val update = service.putModel(
            PutSemanticModelCommand(
                authorization = evidence(workspace, WorkspaceRole.EDITOR).forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_UPDATE),
                idempotencyKey = "model-update",
                semanticModelId = modelId,
                definition = model(connector, measureExpression = "amount_usd"),
                aggregateMetrics = emptyList(),
            ),
        )
        val updated = assertIs<cc.monomer.semantic.api.SemanticMutationResult.Committed<*>>(update)
        assertEquals(
            "amount_usd",
            (updated.value as cc.monomer.semantic.api.SemanticModelWriteResult).model
                .definition.measures.single().expression,
        )

        val outsideWorkspace = service.putModel(
            PutSemanticModelCommand(
                authorization = evidence(otherWorkspace, WorkspaceRole.EDITOR).forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_UPDATE),
                idempotencyKey = "model-outside-workspace",
                semanticModelId = modelId,
                definition = model(connector, measureExpression = "must_not_write"),
                aggregateMetrics = emptyList(),
            ),
        )
        assertEquals(
            cc.monomer.semantic.api.SemanticMutationResult.NotFoundOrOutsideWorkspace,
            outsideWorkspace,
        )

        val invalidCapability = service.putModel(
            PutSemanticModelCommand(
            authorization = evidence(workspace, WorkspaceRole.EXPLORER).forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_UPDATE),
                idempotencyKey = "model-invalid-capability",
                semanticModelId = modelId,
                definition = model(connector, measureExpression = "must_not_write"),
            aggregateMetrics = emptyList(),
            ),
        )
        assertEquals(
            cc.monomer.semantic.api.SemanticMutationResult.InvalidAuthorization,
            invalidCapability,
        )

        val metric = assertIs<cc.monomer.semantic.api.SemanticMutationResult.Committed<*>>(
            service.putMetric(
                PutMetricCommand(
                    authorization = evidence(workspace, WorkspaceRole.EDITOR).forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE),
                    idempotencyKey = "metric-create",
                    metricId = null,
                    definition = simpleMetric("orders_metric"),
                ),
            ),
        )
        val metricId = (metric.value as cc.monomer.semantic.api.MetricView).metricId
        val metricUpdate = service.putMetric(
            PutMetricCommand(
                authorization = evidence(workspace, WorkspaceRole.EDITOR).forMutation(cc.monomer.identity.api.MutationIntent.METRIC_UPDATE),
                idempotencyKey = "metric-update",
                metricId = metricId,
                definition = simpleMetric("orders_metric", measure = "amount_usd"),
            ),
        )
        assertEquals(
            "amount_usd",
            (assertIs<cc.monomer.semantic.api.SemanticMutationResult.Committed<*>>(metricUpdate).value
                as cc.monomer.semantic.api.MetricView)
                .definition.let { definition ->
                    assertIs<SemanticMetricDefinition.Simple>(definition)
                    definition.measure.value
                },
        )

        val metricOutsideWorkspace = service.putMetric(
            PutMetricCommand(
                authorization = evidence(otherWorkspace, WorkspaceRole.EDITOR).forMutation(cc.monomer.identity.api.MutationIntent.METRIC_UPDATE),
                idempotencyKey = "metric-outside-workspace",
                metricId = metricId,
                definition = simpleMetric("orders_metric", measure = "must_not_write"),
            ),
        )
        assertEquals(
            cc.monomer.semantic.api.SemanticMutationResult.NotFoundOrOutsideWorkspace,
            metricOutsideWorkspace,
        )
    }

    @Test
    fun `immutable name diagnostics require valid mutation authorization and workspace ownership`(): Unit = runBlocking {
        val repository = InMemorySemanticRepository()
        val service = authorityService(repository)
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val model = assertIs<cc.monomer.semantic.api.SemanticMutationResult.Committed<*>>(
            service.putModel(PutSemanticModelCommand(
                evidence(workspace, WorkspaceRole.EDITOR).forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE), "model", null, model(connector, "amount"),
                emptyList(),
            )),
        ).value.let { (it as cc.monomer.semantic.api.SemanticModelWriteResult).model }
        val metric = assertIs<cc.monomer.semantic.api.SemanticMutationResult.Committed<*>>(
            service.putMetric(PutMetricCommand(
                evidence(workspace, WorkspaceRole.EDITOR).forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE), "metric", null, simpleMetric("revenue"),
            )),
        ).value as cc.monomer.semantic.api.MetricView
        val modelCommand = PutSemanticModelCommand(
            evidence(workspace, WorkspaceRole.EDITOR).forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_UPDATE), "rename-model", model.semanticModelId,
            model.definition.copy(name = SemanticElementName("renamed_model")),
            aggregateMetrics = emptyList(),
        )
        val metricCommand = PutMetricCommand(
            evidence(workspace, WorkspaceRole.EDITOR).forMutation(cc.monomer.identity.api.MutationIntent.METRIC_UPDATE), "rename-metric", metric.metricId,
            simpleMetric("renamed_metric"),
        )
        val modelReason = assertIs<cc.monomer.semantic.api.SemanticMutationResult.Invalid>(
            service.putModel(modelCommand),
        ).reasons.single()
        val metricReason = assertIs<cc.monomer.semantic.api.SemanticMutationResult.Invalid>(
            service.putMetric(metricCommand),
        ).reasons.single()
        assertEquals(SemanticInvalidReasonCode.MODEL_NAME_IMMUTABLE, modelReason.code)
        assertEquals("name", modelReason.field)
        assertEquals(SemanticInvalidReasonCode.METRIC_NAME_IMMUTABLE, metricReason.code)
        assertEquals("name", metricReason.field)

        val invalidAuthorization = evidence(workspace, WorkspaceRole.EXPLORER)
        assertEquals(cc.monomer.semantic.api.SemanticMutationResult.InvalidAuthorization,
            service.putModel(modelCommand.copy(authorization = invalidAuthorization.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_UPDATE))))
        assertEquals(cc.monomer.semantic.api.SemanticMutationResult.InvalidAuthorization,
            service.putMetric(metricCommand.copy(authorization = invalidAuthorization.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_UPDATE))))
        val outsideWorkspace = evidence(WorkspaceId.generate(), WorkspaceRole.EDITOR)
        assertEquals(cc.monomer.semantic.api.SemanticMutationResult.NotFoundOrOutsideWorkspace,
            service.putModel(modelCommand.copy(authorization = outsideWorkspace.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_UPDATE))))
        assertEquals(cc.monomer.semantic.api.SemanticMutationResult.NotFoundOrOutsideWorkspace,
            service.putMetric(metricCommand.copy(authorization = outsideWorkspace.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_UPDATE))))
    }

    @Test
    fun `semantic owner stores opaque expressions without SQL shape validation`(): Unit = runBlocking {
        val repository = InMemorySemanticRepository()
        val service = authorityService(repository)
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val opaqueExpressions = listOf(
            "SELECT 1",
            "EXISTS (SELECT 1)",
            "amount; SELECT 1",
        )

        opaqueExpressions.forEachIndexed { index, expression ->
            val modelResult = service.putModel(
                PutSemanticModelCommand(
                    authorization = evidence(workspace, WorkspaceRole.EDITOR).forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE),
                    idempotencyKey = "opaque-model-$index",
                    semanticModelId = null,
                    definition = model(connector, measureExpression = expression)
                        .copy(name = SemanticElementName("orders_$index")),
                    aggregateMetrics = emptyList(),
                ),
            )
            val committedModel = assertIs<cc.monomer.semantic.api.SemanticMutationResult.Committed<*>>(modelResult)
            assertEquals(
                expression,
                (committedModel.value as cc.monomer.semantic.api.SemanticModelWriteResult).model
                    .definition.measures.single().expression,
            )

            val metricResult = service.putMetric(
                PutMetricCommand(
                    authorization = evidence(workspace, WorkspaceRole.EDITOR).forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE),
                    idempotencyKey = "opaque-metric-$index",
                    metricId = null,
                    definition = derivedMetric("opaque_metric_$index", "input_metric")
                        .copy(expression = expression),
                ),
            )
            val committedMetric = assertIs<cc.monomer.semantic.api.SemanticMutationResult.Committed<*>>(metricResult)
            assertEquals(
                expression,
                (committedMetric.value as cc.monomer.semantic.api.MetricView)
                    .definition.let { definition ->
                        assertIs<SemanticMetricDefinition.Derived>(definition)
                        definition.expression
                    },
            )
        }

        assertEquals(opaqueExpressions.size, repository.modelInsertCount)
        assertEquals(opaqueExpressions.size, repository.metricInsertCount)
    }

    @Test
    fun `writes accept unresolved model and metric references and model changes that break metric references`(): Unit = runBlocking {
        val repository = InMemorySemanticRepository()
        val service = authorityService(repository)
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val authorization = evidence(workspace, WorkspaceRole.EDITOR)

        assertIs<cc.monomer.semantic.api.SemanticMutationResult.Committed<*>>(
            service.putMetric(
                PutMetricCommand(
                    authorization = authorization.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE),
                    idempotencyKey = "missing-model",
                    metricId = null,
                    definition = simpleMetric("metric_with_missing_model"),
                ),
            ),
        )
        assertIs<cc.monomer.semantic.api.SemanticMutationResult.Committed<*>>(
            service.putMetric(
                PutMetricCommand(
                    authorization = authorization.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE),
                    idempotencyKey = "missing-metric",
                    metricId = null,
                    definition = derivedMetric("metric_with_missing_metric", "missing_metric"),
                ),
            ),
        )

        val firstCycle = assertIs<cc.monomer.semantic.api.SemanticMutationResult.Committed<*>>(
            service.putMetric(
                PutMetricCommand(
                    authorization = authorization.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE),
                    idempotencyKey = "cycle-a",
                    metricId = null,
                    definition = derivedMetric("cycle_a", "cycle_b"),
                ),
            ),
        )
        assertIs<cc.monomer.semantic.api.SemanticMutationResult.Committed<*>>(
            service.putMetric(
                PutMetricCommand(
                    authorization = authorization.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE),
                    idempotencyKey = "cycle-b",
                    metricId = null,
                    definition = derivedMetric("cycle_b", "cycle_a"),
                ),
            ),
        )
        assertNotNull(firstCycle)

        val model = assertIs<cc.monomer.semantic.api.SemanticMutationResult.Committed<*>>(
            service.putModel(
                PutSemanticModelCommand(
                    authorization = evidence(workspace, WorkspaceRole.EDITOR).forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE),
                    idempotencyKey = "model-create-for-breakage",
                    semanticModelId = null,
                    definition = model(connector, measureExpression = "amount"),
                    aggregateMetrics = emptyList(),
                ),
            ),
        ).value.let { (it as cc.monomer.semantic.api.SemanticModelWriteResult).model }
        assertIs<cc.monomer.semantic.api.SemanticMutationResult.Committed<*>>(
            service.putMetric(
                PutMetricCommand(
                    authorization = authorization.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE),
                    idempotencyKey = "metric-refers-to-model",
                    metricId = null,
                    definition = simpleMetric("metric_referring_to_model"),
                ),
            ),
        )

        val modelUpdate = service.putModel(
            PutSemanticModelCommand(
                authorization = evidence(workspace, WorkspaceRole.EDITOR).forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_UPDATE),
                idempotencyKey = "model-removes-measure",
                semanticModelId = model.semanticModelId,
                definition = model(connector, measureExpression = null),
                aggregateMetrics = emptyList(),
            ),
        )
        assertIs<cc.monomer.semantic.api.SemanticMutationResult.Committed<*>>(modelUpdate)
    }

    @Test
    fun `unresolved saved metric recovers on the next request without rewriting the dependent`(): Unit = runBlocking {
        val repository = InMemorySemanticRepository()
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val mutations = authorityService(repository)
        val authorization = evidence(workspace, WorkspaceRole.EDITOR)
        val dependent = assertIs<cc.monomer.semantic.api.SemanticMutationResult.Committed<*>>(
            mutations.putMetric(PutMetricCommand(authorization.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE), "dependent", null, derivedMetric("total", "base"))),
        ).value as cc.monomer.semantic.api.MetricView
        val dependentDefinition = dependent.definition
        val queryService = queryService(repository, workspace, connector, NOW)
        assertEquals(
            SemanticInvalidReasonCode.METRIC_DEPENDENCY_NOT_FOUND,
            assertIs<SemanticRenderResult.Invalid>(queryService.render(query(workspace, connector, "total")))
                .reasons.single().code,
        )
        mutations.putMetric(PutMetricCommand(authorization.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE), "base", null, simpleMetric("base")))
        assertEquals(
            SemanticInvalidReasonCode.METRIC_MODEL_NOT_FOUND,
            assertIs<SemanticRenderResult.Invalid>(queryService.render(query(workspace, connector, "total")))
                .reasons.single().code,
        )
        mutations.putModel(PutSemanticModelCommand(authorization.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE), "model", null, model(connector, "amount"), emptyList()))
        val rendered = assertIs<SemanticRenderResult.Rendered>(queryService.render(query(workspace, connector, "total")))
        assertEquals(dependentDefinition, repository.metrics.getValue(dependent.metricId).definition)
        assertEquals(dependent.updatedAt, repository.metrics.getValue(dependent.metricId).updatedAt)
        assertEquals(0, repository.metricUpdateCount)
    }

    @Test
    fun `missing source on connector B does not block connector A and recovers when B source resolves`(): Unit = runBlocking {
        val repository = InMemorySemanticRepository()
        val workspace = WorkspaceId.generate()
        val connectorA = ConnectorId.generate()
        val connectorB = ConnectorId.generate()
        val authorization = evidence(workspace, WorkspaceRole.EDITOR)
        val mutations = authorityService(repository)
        val definition = model(connectorA, "amount").copy(
            sourceRelations = model(connectorA, "amount").sourceRelations +
                SemanticSourceRelation(connectorB, null, "analytics", "missing"),
        )
        val modelView = assertIs<cc.monomer.semantic.api.SemanticMutationResult.Committed<*>>(
            mutations.putModel(PutSemanticModelCommand(authorization.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE), "model", null, definition, emptyList())),
        ).value.let { (it as cc.monomer.semantic.api.SemanticModelWriteResult).model }
        mutations.putMetric(PutMetricCommand(authorization.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE), "metric", null, simpleMetric("revenue")))
        assertIs<SemanticRenderResult.Rendered>(
            queryService(repository, workspace, connectorA, NOW).render(query(workspace, connectorA, "revenue")),
        )
        assertEquals(
            SemanticInvalidReasonCode.CATALOG_RELATION_NOT_FOUND,
            assertIs<SemanticRenderResult.Invalid>(
                queryService(repository, workspace, connectorB, NOW).render(query(workspace, connectorB, "revenue")),
            ).reasons.single().code,
        )
        val groupBys = assertIs<cc.monomer.semantic.api.SemanticGroupByOptionsResult.Listed>(
            queryService(repository, workspace, connectorA, NOW).listGroupBys(
                cc.monomer.semantic.api.SemanticGroupByOptionsRequest(
                    workspace, connectorA, listOf(SemanticElementName("revenue")),
                ),
            ),
        )
        assertIs<cc.monomer.semantic.api.SemanticGroupByOptionsResult.Invalid>(
            queryService(repository, workspace, connectorB, NOW).listGroupBys(
                cc.monomer.semantic.api.SemanticGroupByOptionsRequest(
                    workspace, connectorB, listOf(SemanticElementName("revenue")),
                ),
            ),
        )
        mutations.putModel(
            PutSemanticModelCommand(
                authorization.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_UPDATE), "resolve-b", modelView.semanticModelId,
                definition.copy(sourceRelations = definition.sourceRelations.map { source ->
                    if (source.connectorId == connectorB) source.copy(relation = "orders") else source
                }),
                aggregateMetrics = emptyList(),
            ),
        )
        assertIs<SemanticRenderResult.Rendered>(
            queryService(repository, workspace, connectorB, NOW).render(query(workspace, connectorB, "revenue")),
        )
    }

    @Test
    fun `closure rejects an unused declared input and a model referenced only by an input filter`(): Unit = runBlocking {
        val repository = InMemorySemanticRepository()
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val authorization = evidence(workspace, WorkspaceRole.EDITOR)
        val mutations = authorityService(repository)
        mutations.putModel(PutSemanticModelCommand(authorization.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE), "model", null, model(connector, "amount"), emptyList()))
        mutations.putMetric(PutMetricCommand(authorization.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE), "base", null, simpleMetric("base")))
        val baseDefinition = derivedMetric("total", "base")
        val unusedInput = baseDefinition.inputMetrics.single().copy(metricName = SemanticElementName("missing"))
        val metric = assertIs<cc.monomer.semantic.api.SemanticMutationResult.Committed<*>>(
            mutations.putMetric(PutMetricCommand(
                authorization.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE), "total", null,
                baseDefinition.copy(inputMetrics = baseDefinition.inputMetrics + unusedInput),
            )),
        ).value as cc.monomer.semantic.api.MetricView
        val service = queryService(repository, workspace, connector, NOW)
        assertEquals(
            SemanticInvalidReasonCode.METRIC_DEPENDENCY_NOT_FOUND,
            assertIs<SemanticRenderResult.Invalid>(service.render(query(workspace, connector, "total"))).reasons.single().code,
        )
        val missingModelFilter = derivedMetric("unused", "base", "missing_model").filters.single()
        mutations.putMetric(PutMetricCommand(
            authorization.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_UPDATE), "input-filter", metric.metricId,
            baseDefinition.copy(inputMetrics = baseDefinition.inputMetrics.map { it.copy(filter = missingModelFilter) }),
        ))
        assertEquals(
            SemanticInvalidReasonCode.METRIC_MODEL_NOT_FOUND,
            assertIs<SemanticRenderResult.Invalid>(service.render(query(workspace, connector, "total"))).reasons.single().code,
        )
    }

    @Test
    fun `omitted catalog source recovers from current Catalog loss and rejects ambiguous addresses`(): Unit = runBlocking {
        val repository = InMemorySemanticRepository()
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val authorization = evidence(workspace, WorkspaceRole.EDITOR)
        val mutations = authorityService(repository)
        val definition = model(connector, "amount").copy(
            sourceRelations = listOf(SemanticSourceRelation(connector, null, "analytics", "orders")),
        )
        mutations.putModel(PutSemanticModelCommand(authorization.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE), "model", null, definition, emptyList()))
        mutations.putMetric(PutMetricCommand(authorization.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE), "metric", null, simpleMetric("revenue")))
        val originalCatalog = catalogObjects(workspace, connector, NOW)
        var currentCatalog = originalCatalog.copy(objects = emptyList())
        val catalogRead = object : CatalogCurrentObjectsReadPort by StaticCatalogRead(workspace, connector, NOW) {
            override fun readCurrent(workspaceId: WorkspaceId, connectorId: ConnectorId): CatalogCurrentObjectSet = currentCatalog
        }
        val service = SemanticQueryService(
            repository, repository, ImmediateTransactionPort, AcceptingMetricFlowPort,
            StaticConnectorRead(workspace, connector, NOW), catalogRead,
            FixedReportingTimeZone(UTC_REPORTING_TIME_ZONE),
        )
        assertEquals(
            SemanticInvalidReasonCode.CATALOG_RELATION_NOT_FOUND,
            assertIs<SemanticRenderResult.Invalid>(service.render(query(workspace, connector, "revenue"))).reasons.single().code,
        )
        currentCatalog = originalCatalog
        assertIs<SemanticRenderResult.Rendered>(service.render(query(workspace, connector, "revenue")))
        val schema = originalCatalog.objects.single { it.kind == CatalogObjectKind.SCHEMA }
        val otherSchemaId = CatalogObjectId(Uuid.parse("0197d83f-2c9e-8a3b-8c1d-000000000098"))
        val otherSchema = schema.copy(
            id = otherSchemaId,
            parentId = null,
            identityDigest = CatalogDigest("sha256:" + "8".repeat(64)),
            address = schema.address.copy(catalogName = "other_warehouse"),
        )
        val relation = originalCatalog.objects.single {
            it.kind == CatalogObjectKind.RELATION && it.address.objectName == "orders"
        }
        val otherRelation = relation.copy(
            id = CatalogObjectId(Uuid.parse("0197d83f-2c9e-8a3b-8c1d-000000000099")),
            parentId = otherSchemaId,
            identityDigest = CatalogDigest("sha256:" + "9".repeat(64)),
            address = relation.address.copy(catalogName = "other_warehouse"),
        )
        currentCatalog = originalCatalog.copy(objects = originalCatalog.objects + otherSchema + otherRelation)
        assertEquals(
            SemanticInvalidReasonCode.CATALOG_RELATION_NOT_FOUND,
            assertIs<SemanticRenderResult.Invalid>(service.render(query(workspace, connector, "revenue"))).reasons.single().code,
        )
        assertEquals(0, repository.modelUpdateCount)
        assertEquals(0, repository.metricUpdateCount)
    }

    @Test
    fun `current transaction inventory reads current workspace definitions without opening a transaction`() {
        val repository = InMemorySemanticRepository()
        val workspace = WorkspaceId.generate()
        val otherWorkspace = WorkspaceId.generate()
        listOf(workspace, otherWorkspace).forEach { owner ->
            val modelId = cc.monomer.semantic.api.SemanticModelId.generate()
            repository.models[modelId] = CurrentSemanticModel(modelId, owner, model(ConnectorId.generate(), "amount"), NOW, NOW)
            val metricId = MetricId.generate()
            repository.metrics[metricId] = CurrentMetric(metricId, owner, simpleMetric("revenue"), NOW, NOW)
        }
        val transactions = object : SemanticTransactionPort {
    override suspend fun <T> inSnapshotTransaction(deadline: java.time.Instant, block: suspend () -> T): T = block()

            override suspend fun <T> inSnapshotTransaction(block: () -> T): T = error("must use caller transaction")
            override suspend fun <T> inMutationTransaction(block: () -> T): T = error("must use caller transaction")
        }
        val service = SemanticCurrentReadService(repository, repository, transactions, EmptyConnectorRead,
            io.mockk.mockk(relaxed = true),
        )
        assertEquals(workspace, service.findModelsInCurrentTransaction(workspace).single().workspaceId)
        assertEquals(workspace, service.findMetricsInCurrentTransaction(workspace).single().workspaceId)
    }

    @Test
    fun `batch shares fresh definitions preserves ordered single results and closes snapshot before render`(): Unit = runBlocking {
        val repository = InMemorySemanticRepository()
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val modelId = cc.monomer.semantic.api.SemanticModelId.generate()
        val metricId = MetricId.generate()
        repository.models[modelId] = CurrentSemanticModel(modelId, workspace, model(connector, "amount").copy(aggregationTimeDimension = SemanticElementName("ordered_at")), NOW, NOW)
        repository.metrics[metricId] = CurrentMetric(metricId, workspace, simpleMetric("revenue"), NOW, NOW)
        val requests = listOf(query(workspace, connector, "revenue"), query(workspace, connector, "missing"), query(workspace, connector, "revenue"))
        val renderer = cc.monomer.semantic.adapter.outbound.metricflow.MetricFlowSemanticRenderer.production()
        val expected = requests.map { queryService(repository, workspace, connector, NOW, renderer).render(it) }
        assertIs<SemanticRenderResult.Rendered>(expected.first(), expected.first().toString())
        repository.metricCollectionReads = 0
        repository.modelCollectionReads = 0
        repository.timeSpineCollectionReads = 0
        var snapshotActive = false
        var catalogReads = 0
        var connectorReads = 0
        val service = SemanticQueryService(
            repository, repository,
            object : SemanticTransactionPort {
                override suspend fun <T> inSnapshotTransaction(deadline: Instant, block: suspend () -> T): T {
                    snapshotActive = true
                    return try { block() } finally { snapshotActive = false }
                }
                override suspend fun <T> inSnapshotTransaction(block: () -> T): T = error("batch snapshot required")
                override suspend fun <T> inMutationTransaction(block: () -> T): T = error("read only")
            },
            object : SemanticMetricFlowPort by renderer {
                override fun validate(manifest: SemanticMetricFlowManifest): SemanticMetricFlowValidationResult {
                    assertEquals(false, snapshotActive)
                    assertTrue(Thread.currentThread().name.contains("DefaultDispatcher"))
                    return renderer.validate(manifest)
                }
            },
            object : ConnectorWorkspaceReadUseCase by StaticConnectorRead(workspace, connector, NOW) {
                override suspend fun findByWorkspaceAndIds(workspaceId: WorkspaceId, connectorIds: Set<ConnectorId>): List<CurrentConnectorRead> {
                    assertTrue(snapshotActive)
                    connectorReads++
                    return StaticConnectorRead(workspace, connector, NOW).findByWorkspaceAndIds(workspaceId, connectorIds)
                }
            },
            object : CatalogCurrentObjectsReadPort by StaticCatalogRead(workspace, connector, NOW) {
                override fun readCurrent(workspaceId: WorkspaceId, connectorId: ConnectorId): CatalogCurrentObjectSet? {
                    assertTrue(snapshotActive)
                    catalogReads++
                    return StaticCatalogRead(workspace, connector, NOW).readCurrent(workspaceId, connectorId)
                }
            },
            FixedReportingTimeZone(UTC_REPORTING_TIME_ZONE),
        )
        assertEquals(expected, service.renderBatch(requests, Instant.now().plusSeconds(10)))
        assertEquals(listOf(1, 1, 1, 1, 1), listOf(repository.metricCollectionReads, repository.modelCollectionReads, repository.timeSpineCollectionReads, catalogReads, connectorReads))
        repository.metrics[metricId] = repository.metrics.getValue(metricId).copy(definition = derivedMetric("revenue", "missing"))
        val next = service.renderBatch(requests, Instant.now().plusSeconds(10))
        assertIs<SemanticRenderResult.Invalid>(next.first())
        assertEquals(listOf(2, 2, 2, 2, 2), listOf(repository.metricCollectionReads, repository.modelCollectionReads, repository.timeSpineCollectionReads, catalogReads, connectorReads))
    }

    @Test
    fun `batch limits independent renders to four and propagates cancellation`(): Unit = runBlocking {
        val repository = InMemorySemanticRepository()
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val modelId = cc.monomer.semantic.api.SemanticModelId.generate()
        val metricId = MetricId.generate()
        repository.models[modelId] = CurrentSemanticModel(modelId, workspace, model(connector, "amount"), NOW, NOW)
        repository.metrics[metricId] = CurrentMetric(metricId, workspace, simpleMetric("revenue"), NOW, NOW)
        val entered = java.util.concurrent.CountDownLatch(4)
        val cancelled = java.util.concurrent.atomic.AtomicInteger()
        val active = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        val engine = object : SemanticMetricFlowPort by AcceptingMetricFlowPort {
            override fun validate(manifest: SemanticMetricFlowManifest): SemanticMetricFlowValidationResult {
                peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                entered.countDown()
                try { Thread.sleep(60_000) } finally { active.decrementAndGet(); cancelled.incrementAndGet() }
                return AcceptingMetricFlowPort.validate(manifest)
            }
        }
        val service = queryService(repository, workspace, connector, NOW, engine)
        val job = kotlinx.coroutines.CoroutineScope(kotlin.coroutines.coroutineContext).launch {
            service.renderBatch(List(7) { query(workspace, connector, "revenue") }, Instant.now().plusSeconds(10))
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        job.cancel()
        job.join()
        assertEquals(4, peak.get())
        assertEquals(4, cancelled.get())
        assertEquals(0, active.get())
    }

    @Test
    fun `expired batch performs no current definition reads`(): Unit = runBlocking {
        val repository = InMemorySemanticRepository()
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        kotlin.test.assertFailsWith<java.util.concurrent.TimeoutException> {
            queryService(repository, workspace, connector, NOW).renderBatch(listOf(query(workspace, connector, "revenue")), Instant.now().minusSeconds(1))
        }
        assertEquals(0, repository.metricCollectionReads)
    }

    @Test
    fun `query reads selected metrics and dependencies once and refreshes them on the next request`(): Unit = runBlocking {
        val repository = InMemorySemanticRepository()
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val modelId = cc.monomer.semantic.api.SemanticModelId.generate()
        repository.models[modelId] = CurrentSemanticModel(modelId, workspace, model(connector, "amount"), NOW, NOW)
        val baseId = MetricId.generate()
        val totalId = MetricId.generate()
        repository.metrics[baseId] = CurrentMetric(baseId, workspace, simpleMetric("base"), NOW, NOW)
        repository.metrics[totalId] = CurrentMetric(totalId, workspace, derivedMetric("total", "base"), NOW, NOW)
        val service = queryService(repository, workspace, connector, NOW)
        val initial = query(workspace, connector, "total")
        val request = initial.copy(query = initial.query.copy(metrics = listOf(SemanticElementName("total"), SemanticElementName("base"))))

        assertIs<SemanticRenderResult.Rendered>(service.render(request))
        assertEquals(1, repository.metricCollectionReads)
        assertEquals(0, repository.metricNameReads)

        repository.metrics[totalId] = repository.metrics.getValue(totalId).copy(definition = derivedMetric("total", "missing"))
        assertEquals(
            SemanticInvalidReasonCode.METRIC_DEPENDENCY_NOT_FOUND,
            assertIs<SemanticRenderResult.Invalid>(service.render(request)).reasons.single().code,
        )
        assertEquals(2, repository.metricCollectionReads)
        assertEquals(0, repository.metricNameReads)
    }

    @Test
    fun `query reports a missing current metric without connector ACL scope`(): Unit = runBlocking {
        val workspace = WorkspaceId.generate()
        val requestedConnector = ConnectorId.generate()
        val now = Instant.parse("2026-08-19T00:00:00Z")
        val result = queryService(
            InMemorySemanticRepository(),
            workspace,
            requestedConnector,
            now,
        ).render(query(workspace, requestedConnector, "missing_metric"))

        assertEquals(SemanticRenderResult.MetricNotFound(SemanticElementName("missing_metric")), result)
    }

    @Test
    fun `query hides another workspace metric existence before returning diagnostics`(): Unit = runBlocking {
        val repository = InMemorySemanticRepository()
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val foreignMetricId = MetricId.generate()
        repository.metrics[foreignMetricId] = CurrentMetric(
            foreignMetricId, WorkspaceId.generate(), derivedMetric("private_metric", "missing_dependency"), NOW, NOW,
        )
        val service = queryService(repository, workspace, connector, NOW)
        assertEquals(SemanticRenderResult.MetricNotFound(SemanticElementName("private_metric")),
            service.render(query(workspace, connector, "private_metric")))
        assertEquals(SemanticRenderResult.MetricNotFound(SemanticElementName("absent_metric")),
            service.render(query(workspace, connector, "absent_metric")))
    }

    @Test
    fun `query preserves the request field supplied by MetricFlow diagnostics`(): Unit = runBlocking {
        val repository = InMemorySemanticRepository()
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val mutations = authorityService(repository)
        val authorization = evidence(workspace, WorkspaceRole.EDITOR)
        mutations.putModel(PutSemanticModelCommand(authorization.forMutation(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE), "model", null, model(connector, "amount"), emptyList()))
        mutations.putMetric(PutMetricCommand(authorization.forMutation(cc.monomer.identity.api.MutationIntent.METRIC_CREATE), "metric", null, simpleMetric("revenue")))
        val metricFlow = object : SemanticMetricFlowPort by AcceptingMetricFlowPort {
            override fun render(
                manifest: SemanticMetricFlowManifest,
                querySpec: SemanticQuerySpec,
                metricIds: List<MetricId>,
            ): SemanticMetricFlowRenderResult = SemanticMetricFlowRenderResult.Invalid(
                listOf(cc.monomer.semantic.application.SemanticMetricFlowInvalidReason(
                    SemanticInvalidReasonCode.UNKNOWN_FILTER_TARGET, "filters[0]",
                )),
            )
        }
        val result = queryService(repository, workspace, connector, NOW, metricFlow)
            .render(query(workspace, connector, "revenue"))
        val reason = assertIs<SemanticRenderResult.Invalid>(result).reasons.single()
        assertEquals(SemanticInvalidReasonCode.UNKNOWN_FILTER_TARGET, reason.code)
        assertEquals("filters[0]", reason.field)
    }

    @Test
    fun `query projects the connector current time spine through the current catalog`(): Unit = runBlocking {
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val now = Instant.parse("2026-08-19T00:00:00Z")
        val repository = InMemorySemanticRepository()
        val modelId = cc.monomer.semantic.api.SemanticModelId.generate()
        val metricId = MetricId.generate()
        repository.models[modelId] = CurrentSemanticModel(
            modelId,
            workspace,
            model(connector, measureExpression = "amount"),
            now,
            now,
        )
        repository.metrics[metricId] = CurrentMetric(
            metricId,
            workspace,
            simpleMetric("revenue_metric"),
            now,
            now,
        )
        repository.upsertTimeSpineConfiguration(
            CurrentTimeSpineConfiguration(
                workspaceId = workspace,
                connectorId = connector,
                definition = SemanticTimeSpineDefinition(
                    schemaVersion = 1u,
                    timeSpines = listOf(
                        SemanticTimeSpine(
                            relation = SemanticTimeSpineRelation(null, "analytics", "orders"),
                            primaryColumn = SemanticTimeSpinePrimaryColumn(
                                "ordered_at",
                                SemanticTimeGranularity.DAY,
                            ),
                        ),
                    ),
                ),
            ),
        )
        val metricFlow = RecordingMetricFlowPort()

        val rendered = assertIs<SemanticRenderResult.Rendered>(
            queryService(repository, workspace, connector, now, metricFlow)
                .render(query(workspace, connector, "revenue_metric")),
        )

        val projected = assertNotNull(metricFlow.manifest).timeSpines.single()
        assertEquals(CatalogPhysicalAddress(null, "analytics", "orders", null), projected.physicalAddress)
        assertEquals("ordered_at", projected.primaryColumnName)
        assertEquals(SemanticTimeGranularity.DAY, projected.granularity)
    }

    @Test
    fun `query accepts a configured time spine relation in MetricFlow relation provenance`(): Unit = runBlocking {
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val now = Instant.parse("2026-08-19T00:00:00Z")
        val repository = InMemorySemanticRepository()
        val modelId = cc.monomer.semantic.api.SemanticModelId.generate()
        val metricId = MetricId.generate()
        repository.models[modelId] = CurrentSemanticModel(
            modelId,
            workspace,
            model(connector, measureExpression = "amount"),
            now,
            now,
        )
        repository.metrics[metricId] = CurrentMetric(
            metricId,
            workspace,
            simpleMetric("revenue_metric"),
            now,
            now,
        )
        repository.upsertTimeSpineConfiguration(
            CurrentTimeSpineConfiguration(
                workspaceId = workspace,
                connectorId = connector,
                definition = SemanticTimeSpineDefinition(
                    schemaVersion = 1u,
                    timeSpines = listOf(
                        SemanticTimeSpine(
                            relation = SemanticTimeSpineRelation(null, "analytics", "calendar"),
                            primaryColumn = SemanticTimeSpinePrimaryColumn(
                                "calendar_date",
                                SemanticTimeGranularity.DAY,
                            ),
                        ),
                    ),
                ),
            ),
        )
        val metricFlow = RecordingMetricFlowPort()

        val result = queryService(repository, workspace, connector, now, metricFlow)
            .render(query(workspace, connector, "revenue_metric"))

        assertIs<SemanticRenderResult.Rendered>(result)
    }

    @Test
    fun `two depth current sources reject database qualified definitions`(): Unit = runBlocking {
        listOf(ConnectorProductType.POSTGRESQL, ConnectorProductType.REDSHIFT).forEach { connectorType ->
            val workspace = WorkspaceId.generate()
            val connector = ConnectorId.generate()
            val repository = InMemorySemanticRepository()
            val modelId = cc.monomer.semantic.api.SemanticModelId.generate()
            val metricId = MetricId.generate()
            val currentModel = CurrentSemanticModel(modelId, workspace, model(connector, "amount"), NOW, NOW)
            repository.models[modelId] = currentModel
            repository.metrics[metricId] = CurrentMetric(metricId, workspace, simpleMetric("revenue_metric"), NOW, NOW)
            val metricFlow = RecordingMetricFlowPort()
            val service = queryService(repository, workspace, connector, NOW, metricFlow, connectorType)
            val request = query(workspace, connector, "revenue_metric")

            assertIs<SemanticRenderResult.Rendered>(service.render(request))
            assertEquals(null, assertNotNull(metricFlow.manifest).models.values.single().physicalAddress.catalogName)
            listOf("warehouse", "foreign_database").forEach { database ->
                repository.models[modelId] = currentModel.copy(
                    definition = currentModel.definition.copy(
                        sourceRelations = listOf(SemanticSourceRelation(connector, database, "analytics", "orders")),
                    ),
                )
                assertEquals(
                    SemanticInvalidReasonCode.CATALOG_RELATION_NOT_FOUND,
                    assertIs<SemanticRenderResult.Invalid>(service.render(request)).reasons.single().code,
                )
            }
            repository.models[modelId] = currentModel
            assertIs<SemanticRenderResult.Rendered>(service.render(request))
        }
    }

    @Test
    fun `query resolution rejects missing current dependencies and passes finite cycle closure to MetricFlow`(): Unit = runBlocking {
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val now = Instant.parse("2026-08-19T00:00:00Z")

        val missingMetricRepository = InMemorySemanticRepository()
        val missingMetricId = MetricId.generate()
        missingMetricRepository.metrics[missingMetricId] = CurrentMetric(
            missingMetricId,
            workspace,
            derivedMetric("root_metric", "private_nested_metric"),
            now,
            now,
        )
        val nestedMetricId = MetricId.generate()
        missingMetricRepository.metrics[nestedMetricId] = CurrentMetric(
            nestedMetricId,
            workspace,
            derivedMetric("private_nested_metric", "private_missing_metric"),
            now,
            now,
        )
        val missingMetricResult = queryService(
            missingMetricRepository,
            workspace,
            connector,
            now,
        ).render(query(workspace, connector, "root_metric"))
        val missingMetricInvalid = assertIs<SemanticRenderResult.Invalid>(missingMetricResult)
        assertEquals(
            SemanticInvalidReasonCode.METRIC_DEPENDENCY_NOT_FOUND,
            missingMetricInvalid.reasons.single().code,
        )

        assertEquals(null, missingMetricInvalid.reasons.single().field)

        val missingModelRepository = InMemorySemanticRepository()
        val missingModelId = MetricId.generate()
        missingModelRepository.metrics[missingModelId] = CurrentMetric(
            missingModelId,
            workspace,
            simpleMetric("metric_with_missing_model"),
            now,
            now,
        )
        val missingModelResult = queryService(
            missingModelRepository,
            workspace,
            connector,
            now,
        ).render(query(workspace, connector, "metric_with_missing_model"))
        val missingModelInvalid = assertIs<SemanticRenderResult.Invalid>(missingModelResult)
        assertEquals(
            SemanticInvalidReasonCode.METRIC_MODEL_NOT_FOUND,
            missingModelInvalid.reasons.single().code,
        )

        assertEquals(null, missingModelInvalid.reasons.single().field)

        val cycleRepository = InMemorySemanticRepository()
        val modelId = cc.monomer.semantic.api.SemanticModelId.generate()
        cycleRepository.models[modelId] = CurrentSemanticModel(
            modelId,
            workspace,
            model(connector, measureExpression = "amount"),
            now,
            now,
        )
        val cycleAId = MetricId.generate()
        val cycleBId = MetricId.generate()
        cycleRepository.metrics[cycleAId] = CurrentMetric(
            cycleAId,
            workspace,
            derivedMetric("cycle_a", "cycle_b"),
            now,
            now,
        )
        cycleRepository.metrics[cycleBId] = CurrentMetric(
            cycleBId,
            workspace,
            derivedMetric("cycle_b", "cycle_a"),
            now,
            now,
        )
        val cycleResult = queryService(
            cycleRepository,
            workspace,
            connector,
            now,
        ).render(query(workspace, connector, "cycle_a"))
        assertIs<SemanticRenderResult.Rendered>(cycleResult)
    }

    @Test
    fun `real engine one hop linked dimension renders with its canonical output key`(): Unit = runBlocking {
        val fixture = linkedModelsFixture()
        val listed = assertIs<cc.monomer.semantic.api.SemanticGroupByOptionsResult.Listed>(fixture.service.listGroupBys(
            cc.monomer.semantic.api.SemanticGroupByOptionsRequest(fixture.workspace, fixture.connector, listOf(SemanticElementName("total"))),
        ))
        for (modelName in listOf("customers")) {
            val selector = cc.monomer.semantic.api.SemanticGroupBy.Dimension(SemanticElementName(modelName), SemanticElementName("label"), null)
            assertTrue(selector in listed.groupBys, listed.groupBys.toString())
            val request = query(fixture.workspace, fixture.connector, "total")
            val rendered = assertIs<SemanticRenderResult.Rendered>(fixture.service.render(request.copy(
                query = request.query.copy(groupBys = listOf(selector)),
            )))
            assertEquals(listOf("dimension.$modelName.label", "metric.total"), rendered.render.outputColumns.map { it.name.value })
            assertTrue("JOIN" in rendered.render.sql, rendered.render.sql)
        }
    }

    @Test
    fun `real engine linked dimensions preserve intermediate joins and canonical output keys`(): Unit = runBlocking {
        val fixture = linkedModelsFixture()
        val listed = assertIs<cc.monomer.semantic.api.SemanticGroupByOptionsResult.Listed>(fixture.service.listGroupBys(
            cc.monomer.semantic.api.SemanticGroupByOptionsRequest(fixture.workspace, fixture.connector, listOf(SemanticElementName("total"))),
        ))
        for (modelName in listOf("customers", "regions")) {
            val selector = cc.monomer.semantic.api.SemanticGroupBy.Dimension(SemanticElementName(modelName), SemanticElementName("label"), null)
            assertTrue(selector in listed.groupBys, listed.groupBys.toString())
            val request = query(fixture.workspace, fixture.connector, "total")
            val rendered = assertIs<SemanticRenderResult.Rendered>(fixture.service.render(request.copy(
                query = request.query.copy(groupBys = listOf(selector)),
            )))
            assertEquals(listOf("dimension.$modelName.label", "metric.total"), rendered.render.outputColumns.map { it.name.value })
            assertTrue("JOIN" in rendered.render.sql, rendered.render.sql)
        }
    }

    @Test
    fun `real engine one hop request and stored filters render without group by output`(): Unit = runBlocking {
        for (stored in listOf(false, true)) {
            val fixture = linkedModelsFixture()
            val reference = cc.monomer.semantic.api.SemanticElementReference(SemanticElementName("customers"), SemanticElementName("label"))
            val request = query(fixture.workspace, fixture.connector, "total")
            val query = if (stored) {
                val metric = fixture.repository.metrics.values.single()
                fixture.repository.metrics[metric.id] = metric.copy(definition = (metric.definition as SemanticMetricDefinition.Simple).copy(
                    filters = listOf(cc.monomer.semantic.api.SemanticMetricFilter.Predicate(reference, cc.monomer.semantic.api.SemanticMetricFilterOperator.EQ, listOf("EU"))),
                ))
                request.query
            } else request.query.copy(filters = listOf(cc.monomer.semantic.api.SemanticQueryFilter.Text(
                reference, cc.monomer.semantic.api.SemanticTextFilterOperator.EQUALS, listOf("EU"),
            )))
            val result = fixture.service.render(request.copy(query = query))
            val rendered = assertIs<SemanticRenderResult.Rendered>(result, result.toString()).render
            assertEquals(listOf("metric.total"), rendered.outputColumns.map { it.name.value })
            assertTrue("EU" in rendered.sql && "JOIN" in rendered.sql, rendered.sql)
            if (stored) {
                val listed = assertIs<cc.monomer.semantic.api.SemanticGroupByOptionsResult.Listed>(fixture.service.listGroupBys(
                    cc.monomer.semantic.api.SemanticGroupByOptionsRequest(fixture.workspace, fixture.connector, listOf(SemanticElementName("total"))),
                ))
            }
        }
    }

    @Test
    fun `real engine request and stored filter only queries include the intermediate model without output`(): Unit = runBlocking {
        for (stored in listOf(false, true)) {
            val fixture = linkedModelsFixture()
            val reference = cc.monomer.semantic.api.SemanticElementReference(SemanticElementName("regions"), SemanticElementName("label"))
            val request = query(fixture.workspace, fixture.connector, "total")
            val query = if (stored) {
                val metric = fixture.repository.metrics.values.single()
                fixture.repository.metrics[metric.id] = metric.copy(definition = (metric.definition as SemanticMetricDefinition.Simple).copy(
                    filters = listOf(cc.monomer.semantic.api.SemanticMetricFilter.Predicate(reference, cc.monomer.semantic.api.SemanticMetricFilterOperator.EQ, listOf("EU"))),
                ))
                request.query
            } else request.query.copy(filters = listOf(cc.monomer.semantic.api.SemanticQueryFilter.Text(
                reference, cc.monomer.semantic.api.SemanticTextFilterOperator.EQUALS, listOf("EU"),
            )))
            val result = fixture.service.render(request.copy(query = query))
            val rendered = assertIs<SemanticRenderResult.Rendered>(result, result.toString()).render
            assertEquals(listOf("metric.total"), rendered.outputColumns.map { it.name.value })
            assertTrue("EU" in rendered.sql && "JOIN" in rendered.sql, rendered.sql)
            if (stored) {
                val listed = assertIs<cc.monomer.semantic.api.SemanticGroupByOptionsResult.Listed>(fixture.service.listGroupBys(
                    cc.monomer.semantic.api.SemanticGroupByOptionsRequest(fixture.workspace, fixture.connector, listOf(SemanticElementName("total"))),
                ))
            }
        }
    }

    @Test
    fun `disconnected outside workspace other connector and invalid source candidates are absent while duplicate unused measures are harmless`(): Unit = runBlocking {
        val fixture = linkedModelsFixture()
        val base = fixture.repository.models.values.first { it.definition.name.value == "orders" }
        for (name in listOf("disconnected", "outside", "other_connector", "invalid_source")) {
            val id = cc.monomer.semantic.api.SemanticModelId.generate()
            val definition = base.definition.copy(
                name = SemanticElementName(name),
                entities = if (name == "disconnected") listOf(base.definition.entities.first().copy(name = SemanticElementName("unlinked"))) else base.definition.entities,
                sourceRelations = listOf(SemanticSourceRelation(if (name == "other_connector") ConnectorId.generate() else fixture.connector, null, "analytics", if (name == "invalid_source") "missing" else "orders")),
            )
            fixture.repository.models[id] = base.copy(id = id, workspaceId = if (name == "outside") WorkspaceId.generate() else fixture.workspace, definition = definition)
        }
        val listed = assertIs<cc.monomer.semantic.api.SemanticGroupByOptionsResult.Listed>(fixture.service.listGroupBys(
            cc.monomer.semantic.api.SemanticGroupByOptionsRequest(fixture.workspace, fixture.connector, listOf(SemanticElementName("total"))),
        ))
        assertTrue(listed.groupBys.filterIsInstance<cc.monomer.semantic.api.SemanticGroupBy.Dimension>().all { it.semanticModelName.value in setOf("orders", "customers", "regions") })
        assertIs<SemanticRenderResult.Rendered>(fixture.service.render(query(fixture.workspace, fixture.connector, "total")))
    }

    @Test
    fun `ambiguous engine paths are hidden and direct selectors fail without choosing a path`(): Unit = runBlocking {
        val fixture = linkedModelsFixture()
        val orders = fixture.repository.models.values.first { it.definition.name.value == "orders" }
        fixture.repository.models[orders.id] = orders.copy(definition = orders.definition.copy(
            entities = orders.definition.entities + orders.definition.entities.first().copy(name = SemanticElementName("region"), type = SemanticEntityKind.FOREIGN, expression = "region"),
        ))
        val selector = cc.monomer.semantic.api.SemanticGroupBy.Dimension(SemanticElementName("regions"), SemanticElementName("label"), null)
        val listed = assertIs<cc.monomer.semantic.api.SemanticGroupByOptionsResult.Listed>(fixture.service.listGroupBys(
            cc.monomer.semantic.api.SemanticGroupByOptionsRequest(fixture.workspace, fixture.connector, listOf(SemanticElementName("total"))),
        ))
        assertTrue(selector !in listed.groupBys)
        val request = query(fixture.workspace, fixture.connector, "total")
        val invalid = assertIs<SemanticRenderResult.Invalid>(fixture.service.render(request.copy(query = request.query.copy(groupBys = listOf(selector)))))
        assertEquals(SemanticInvalidReasonCode.QUERY_GROUP_BY_NOT_FOUND, invalid.reasons.single().code)
        assertEquals("groupBys[0]", invalid.reasons.single().field)
    }

    private fun linkedModelsFixture(): LinkedModelsFixture {
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val repository = InMemorySemanticRepository()
        val base = model(connector, "amount").copy(aggregationTimeDimension = SemanticElementName("ordered_at"))
        fun entity(name: String, kind: SemanticEntityKind) = base.entities.first().copy(name = SemanticElementName(name), type = kind, expression = name)
        val label = SemanticDimensionDefinition(SemanticElementName("label"), SemanticDimensionKind.CATEGORICAL, "label", null, null, null,
            false,
            null,
        )
        val definitions = listOf(
            base.copy(entities = base.entities + entity("customer", SemanticEntityKind.FOREIGN)),
            base.copy(name = SemanticElementName("customers"), sourceRelations = listOf(SemanticSourceRelation(connector, null, "analytics", "customers")),
                entities = listOf(entity("customer", SemanticEntityKind.PRIMARY), entity("region", SemanticEntityKind.FOREIGN)), dimensions = base.dimensions + label,
                measures = base.measures.map { it.copy(name = SemanticElementName("customer_revenue")) }),
            base.copy(name = SemanticElementName("regions"), sourceRelations = listOf(SemanticSourceRelation(connector, null, "analytics", "regions")),
                entities = listOf(entity("region", SemanticEntityKind.PRIMARY)), dimensions = base.dimensions + label, measures = emptyList()),
        )
        definitions.forEach { definition ->
            val id = cc.monomer.semantic.api.SemanticModelId.generate()
            repository.models[id] = CurrentSemanticModel(id, workspace, definition, NOW, NOW)
        }
        val metricId = MetricId.generate()
        repository.metrics[metricId] = CurrentMetric(metricId, workspace, simpleMetric("total"), NOW, NOW)
        val catalog = catalogObjects(workspace, connector, NOW)
        val relation = catalog.objects.first { it.address.objectName == "orders" }
        val objects = catalog.copy(objects = catalog.objects + listOf("customers", "regions").mapIndexed { index, name ->
            relation.copy(id = CatalogObjectId(Uuid.parse("0197d83f-2c9e-8a3b-8c1d-00000000000${index + 5}")), identityDigest = CatalogDigest("sha256:" + (index + 5).toString().repeat(64)), address = relation.address.copy(objectName = name), qualifiedName = "analytics.$name")
        })
        val service = SemanticQueryService(repository, repository, ImmediateTransactionPort,
            cc.monomer.semantic.adapter.outbound.metricflow.MetricFlowSemanticRenderer.production(),
            StaticConnectorRead(workspace, connector, NOW, ConnectorProductType.POSTGRESQL),
            object : CatalogCurrentObjectsReadPort {
                override fun readCurrent(workspaceId: WorkspaceId, connectorId: ConnectorId): CatalogCurrentObjectSet = objects
                override fun searchCurrentRelations(workspaceId: WorkspaceId, query: String, cursor: cc.monomer.catalog.api.CatalogCurrentRelationPageCursor?, pageSize: Int): cc.monomer.catalog.api.CatalogCurrentRelationPage = error("unused")
            },
            FixedReportingTimeZone(UTC_REPORTING_TIME_ZONE),
        )
        return LinkedModelsFixture(workspace, connector, repository, service)
    }

    private data class LinkedModelsFixture(
        val workspace: WorkspaceId,
        val connector: ConnectorId,
        val repository: InMemorySemanticRepository,
        val service: SemanticQueryService,
    )

    private fun historyAuthorityService(repository: InMemorySemanticRepository): SemanticAuthorityService {
        var seconds = 0L
        return SemanticAuthorityService(repository, ImmediateTransactionPort, object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant(): Instant = NOW.plusSeconds(seconds++)
        }, object : SemanticSearchProjection {
            override fun upsertModel(fields: SemanticModelSearchFields) = Unit
            override fun upsertMetric(fields: MetricSearchFields) = Unit
            override fun deleteModel(workspaceId: WorkspaceId, name: String) = Unit
            override fun deleteMetric(workspaceId: WorkspaceId, name: String) = Unit
        })
    }

    private fun authorityService(
        repository: InMemorySemanticRepository,
    ): SemanticAuthorityService =
        SemanticAuthorityService(
            repository,
            ImmediateTransactionPort,
            Clock.fixed(NOW, ZoneOffset.UTC),
            object : cc.monomer.semantic.api.SemanticSearchProjection {
                override fun deleteModel(workspaceId: WorkspaceId, name: String) = Unit
                override fun deleteMetric(workspaceId: WorkspaceId, name: String) = Unit
                override fun upsertModel(fields: cc.monomer.semantic.api.SemanticModelSearchFields) {}
                override fun upsertMetric(fields: cc.monomer.semantic.api.MetricSearchFields) {}
            },
        )

    private fun queryService(
        repository: InMemorySemanticRepository,
        workspace: WorkspaceId,
        connector: ConnectorId,
        now: Instant,
        metricFlowPort: SemanticMetricFlowPort = AcceptingMetricFlowPort,
        connectorType: ConnectorProductType = ConnectorProductType.POSTGRESQL,
    ): SemanticQueryService = SemanticQueryService(
        repository = repository,
        timeSpineRepository = repository,
        transactionPort = ImmediateTransactionPort,
        metricFlowPort = metricFlowPort,
        connectorReadUseCase = StaticConnectorRead(workspace, connector, now, connectorType),
        catalogReadPort = StaticCatalogRead(workspace, connector, now),
        reportingTimeZoneQuery = FixedReportingTimeZone(UTC_REPORTING_TIME_ZONE),
    )

    private fun query(
        workspace: WorkspaceId,
        connector: ConnectorId,
        metricName: String,
    ): SemanticRenderRequest = SemanticRenderRequest(
        workspaceId = workspace,
        connectorId = connector,
        query = SemanticQuerySpec(
            metrics = listOf(SemanticElementName(metricName)),
            groupBys = emptyList(),
            metricTimeRange = null,
            filters = emptyList(),
            orderBy = emptyList(),
            rowLimit = 10,
        ),
    )

    private fun model(connector: ConnectorId, measureExpression: String?): SemanticModelDefinition =
        SemanticModelDefinition(
            schemaVersion = 3u,
            name = SemanticElementName("orders"),
            label = null,
            description = null,
            sourceRelations = listOf(SemanticSourceRelation(connector, null, "analytics", "orders")),
            aggregationTimeDimension = null,
            entities = listOf(
                SemanticEntityDefinition(
                    name = SemanticElementName("order_id"),
                    type = SemanticEntityKind.PRIMARY,
                    expression = "order_id",
                    label = null,
                    description = null,
                ),
            ),
            dimensions = listOf(
                SemanticDimensionDefinition(
                    name = SemanticElementName("ordered_at"),
                    type = SemanticDimensionKind.TIME,
                    expression = "ordered_at",
                    granularity = SemanticTimeGranularity.DAY,
                    label = null,
                    description = null,
                    isPartition = false,
                    timeKind = null,
                ),
            ),
            measures = measureExpression?.let { expression ->
                listOf(
                    SemanticMeasureDefinition(
                        name = SemanticElementName("revenue"),
                        aggregation = SemanticMeasureAggregation.SUM,
                        expression = expression,
                        aggregationTimeDimension = null,
                        nonAdditiveDimension = null,
                        label = null,
                        description = null,
                    ),
                )
            }.orEmpty(),
        )

    private fun simpleMetric(
        name: String,
        measure: String = "revenue",
    ): SemanticMetricDefinition.Simple = SemanticMetricDefinition.Simple(
        schemaVersion = 3u,
        name = SemanticElementName(name),
        label = null,
        description = null,
        timeGranularity = null,
        filters = emptyList(),
        semanticModelName = SemanticElementName("orders"),
        measure = SemanticElementName(measure),
        display = null,
    )

    private fun derivedMetric(
        name: String,
        inputMetric: String,
        filterModel: String? = null,
    ): SemanticMetricDefinition.Derived = SemanticMetricDefinition.Derived(
        schemaVersion = 3u,
        name = SemanticElementName(name),
        label = null,
        description = null,
        timeGranularity = null,
        filters = filterModel?.let { modelName ->
            listOf(
                cc.monomer.semantic.api.SemanticMetricFilter.Predicate(
                    dimension = cc.monomer.semantic.api.SemanticElementReference(
                        SemanticElementName(modelName),
                        SemanticElementName("ordered_at"),
                    ),
                    operator = cc.monomer.semantic.api.SemanticMetricFilterOperator.IS_NULL,
                    values = emptyList(),
                ),
            )
        }.orEmpty(),
        expression = inputMetric,
        inputMetrics = listOf(
            SemanticMetricInput(
                metricName = SemanticElementName(inputMetric),
                filter = null,
                alias = null,
                offsetWindow = null,
                offsetToGrain = null,
            ),
        ),
        display = null,
    )

    private fun evidence(
        workspaceId: WorkspaceId,
        capability: WorkspaceRole,
    ): IdentityRoleEvidence {
        val actor = CanonicalPrincipal(PrincipalId.generate(), PrincipalKind.USER)
        return IdentityRoleEvidence(
            actor = actor,
            organizationId = OrganizationId.generate(),
            organizationPlan = cc.monomer.identity.api.OrganizationPlan.FREE,
            workspaceId = workspaceId,
            authorizationRevision = AuthorizationRevision(1),
            credentialKind = CredentialKind.SESSION,
            credentialSurface = CredentialSurface.WEB,
            agentClientId = null,
            role = capability,
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-19T00:00:00Z")
    }
}

internal object EmptyConnectorRead : ConnectorWorkspaceReadUseCase {
    override suspend fun findByWorkspaceAndId(workspaceId: WorkspaceId, connectorId: ConnectorId): CurrentConnectorRead? = null
    override suspend fun findByWorkspaceAndIds(workspaceId: WorkspaceId, connectorIds: Set<ConnectorId>): List<CurrentConnectorRead> = emptyList()
    override suspend fun listByWorkspace(workspaceId: WorkspaceId): List<CurrentConnectorRead> = emptyList()
}

internal object ImmediateTransactionPort : SemanticTransactionPort {
    override suspend fun <T> inSnapshotTransaction(deadline: java.time.Instant, block: suspend () -> T): T = block()

    override suspend fun <T> inSnapshotTransaction(block: () -> T): T = block()
    override suspend fun <T> inMutationTransaction(block: () -> T): T = block()
}

internal class InMemorySemanticRepository : SemanticRepository, SemanticTimeSpineRepository {
    val changes = mutableListOf<Triple<WorkspaceId, Uuid, SemanticDefinitionVersion>>()
    override fun appendDefinitionChange(workspaceId: WorkspaceId, definitionId: Uuid, version: SemanticDefinitionVersion) {
        changes += Triple(workspaceId, definitionId, version)
    }
    override fun findDefinitionVersion(workspaceId: WorkspaceId, kind: SemanticDefinitionKind, definitionId: Uuid, changeId: Uuid): SemanticDefinitionVersion? =
        readDefinitionVersions(workspaceId, kind, definitionId, null, Int.MAX_VALUE).firstOrNull { it.changeId == changeId }
    override fun readDefinitionVersions(workspaceId: WorkspaceId, kind: SemanticDefinitionKind, definitionId: Uuid, before: SemanticHistoryBoundary?, limit: Int): List<SemanticDefinitionVersion> =
        changes.filter { (workspace, id, version) -> workspace == workspaceId && id == definitionId &&
            (kind == SemanticDefinitionKind.SEMANTIC_MODEL) == (version.definition is SemanticDefinitionSnapshot.Model) }
            .map { it.third }.sortedWith(compareByDescending<SemanticDefinitionVersion> { it.occurredAt }.thenByDescending { it.changeId.toString() })
            .filter { before == null || it.occurredAt < before.occurredAt || (it.occurredAt == before.occurredAt && it.changeId.toString() < before.changeId.toString()) }.take(limit)
    override fun deleteModel(workspaceId: WorkspaceId, semanticModelId: SemanticModelId) {
        if (models[semanticModelId]?.workspaceId == workspaceId) models.remove(semanticModelId)
    }
    override fun deleteMetric(workspaceId: WorkspaceId, metricId: MetricId) {
        if (metrics[metricId]?.workspaceId == workspaceId) metrics.remove(metricId)
    }
    val models = linkedMapOf<cc.monomer.semantic.api.SemanticModelId, CurrentSemanticModel>()
    val metrics = linkedMapOf<MetricId, CurrentMetric>()
    var modelInsertCount: Int = 0
    var modelUpdateCount: Int = 0
    var metricInsertCount: Int = 0
    var metricUpdateCount: Int = 0
    var lastModelListLimit: Int? = null
    var lastMetricListLimit: Int? = null
    var modelCollectionReads = 0
    var timeSpineCollectionReads = 0
    var metricCollectionReads = 0
    var metricNameReads = 0
    private val timeSpineConfigurations = linkedMapOf<Pair<WorkspaceId, ConnectorId>, CurrentTimeSpineConfiguration>()
    private val receipts = linkedMapOf<Pair<WorkspaceId, String>, SemanticMutationReceipt>()
    override fun findModel(id: cc.monomer.semantic.api.SemanticModelId): CurrentSemanticModel? = models[id]

    override fun findModelByName(
        workspaceId: WorkspaceId,
        name: SemanticElementName,
    ): CurrentSemanticModel? = models.values.firstOrNull { it.workspaceId == workspaceId && it.definition.name == name }

    override fun findModels(workspaceId: WorkspaceId): List<CurrentSemanticModel> {
        modelCollectionReads++
        return models.values.filter { it.workspaceId == workspaceId }
    }

    override fun listModels(
        workspaceId: WorkspaceId,
        after: cc.monomer.semantic.api.SemanticModelListBoundary?,
        limit: Int,
    ): List<CurrentSemanticModel> {
        lastModelListLimit = limit
        return models.values
            .filter { it.workspaceId == workspaceId }
            .sortedBy { it.definition.name.value }
            .dropWhile { after != null && it.definition.name.value <= after.name.value }
            .take(limit)
    }

    override fun searchModels(
        workspaceId: WorkspaceId,
        query: String,
        cursor: cc.monomer.semantic.api.SemanticModelPageCursor?,
        limit: Int,
    ): List<cc.monomer.semantic.application.SemanticModelSearchRow> = models.values
        .asSequence()
        .filter { it.workspaceId == workspaceId }
        .map { model ->
            cc.monomer.semantic.application.SemanticModelSearchRow(
                model,
                (model.definition.label ?: model.definition.name.value).lowercase(),
                model.definition.name.value.lowercase(),
            )
        }
        .filter { row ->
            query in "${row.model.definition.label.orEmpty()} ${row.model.definition.name.value}".lowercase()
        }
        .sortedWith(compareBy({ it.normalizedLabel }, { it.normalizedName }, { it.model.id.toString() }))
        .dropWhile { row ->
            cursor != null && compareValuesBy(
                row,
                cc.monomer.semantic.application.SemanticModelSearchRow(
                    row.model.copy(id = cursor.semanticModelId),
                    cursor.normalizedLabel,
                    cursor.normalizedName,
                ),
                { it.normalizedLabel },
                { it.normalizedName },
                { it.model.id.toString() },
            ) <= 0
        }
        .take(limit)
        .toList()

    override fun findLatestModel(workspaceId: WorkspaceId): CurrentSemanticModel? =
        models.values.filter { it.workspaceId == workspaceId }
            .maxWithOrNull(compareBy({ it.updatedAt }, { it.id.toString() }))

    override fun findMetric(id: MetricId): CurrentMetric? = metrics[id]

    override fun findMetricByName(workspaceId: WorkspaceId, name: SemanticElementName): CurrentMetric? {
        metricNameReads++
        return metrics.values.firstOrNull { it.workspaceId == workspaceId && it.definition.name == name }
    }

    override fun findMetrics(workspaceId: WorkspaceId): List<CurrentMetric> {
        metricCollectionReads++
        return metrics.values.filter { it.workspaceId == workspaceId }
    }

    override fun listMetrics(
        workspaceId: WorkspaceId,
        after: cc.monomer.semantic.api.MetricListBoundary?,
        limit: Int,
    ): List<CurrentMetric> {
        lastMetricListLimit = limit
        return metrics.values
            .filter { it.workspaceId == workspaceId }
            .sortedBy { it.definition.name.value }
            .dropWhile { after != null && it.definition.name.value <= after.name.value }
            .take(limit)
    }

    override fun searchMetrics(
        workspaceId: WorkspaceId,
        query: String,
        cursor: cc.monomer.semantic.api.MetricPageCursor?,
        limit: Int,
    ): List<cc.monomer.semantic.application.MetricSearchRow> = metrics.values
        .asSequence()
        .filter { it.workspaceId == workspaceId }
        .map { metric ->
            cc.monomer.semantic.application.MetricSearchRow(
                metric,
                (metric.definition.label ?: metric.definition.name.value).lowercase(),
                metric.definition.name.value.lowercase(),
            )
        }
        .filter { row ->
            query in "${row.metric.definition.label.orEmpty()} ${row.metric.definition.name.value}".lowercase()
        }
        .sortedWith(compareBy({ it.normalizedLabel }, { it.normalizedName }, { it.metric.id.toString() }))
        .dropWhile { row ->
            cursor != null && compareValuesBy(
                row,
                cc.monomer.semantic.application.MetricSearchRow(
                    row.metric.copy(id = cursor.metricId),
                    cursor.normalizedLabel,
                    cursor.normalizedName,
                ),
                { it.normalizedLabel },
                { it.normalizedName },
                { it.metric.id.toString() },
            ) <= 0
        }
        .take(limit)
        .toList()

    override fun findLatestMetric(workspaceId: WorkspaceId): CurrentMetric? =
        metrics.values.filter { it.workspaceId == workspaceId }
            .maxWithOrNull(compareBy({ it.updatedAt }, { it.id.toString() }))

    override fun findTimeSpineConfigurations(workspaceId: WorkspaceId): List<CurrentTimeSpineConfiguration> {
        timeSpineCollectionReads++
        return timeSpineConfigurations.values.filter { it.workspaceId == workspaceId }
    }

    override fun upsertTimeSpineConfiguration(configuration: CurrentTimeSpineConfiguration) {
        timeSpineConfigurations[configuration.workspaceId to configuration.connectorId] = configuration
    }

    override fun readRecentModels(workspaceId: WorkspaceId, before: Instant?, beforeId: Uuid?, actorId: PrincipalId?, credentialSurface: cc.monomer.identity.api.CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?, limit: Int): List<cc.monomer.semantic.api.SemanticDefinitionChange> = error("Not used by this fixture")
    override fun readRecentMetrics(workspaceId: WorkspaceId, before: Instant?, beforeId: Uuid?, actorId: PrincipalId?, credentialSurface: cc.monomer.identity.api.CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?, limit: Int): List<cc.monomer.semantic.api.SemanticDefinitionChange> = error("Not used by this fixture")
    override fun readRecentAgentClients(workspaceId: WorkspaceId, scanLimit: Int): List<cc.monomer.identity.api.RecentAgentClient> = error("Not used by this fixture")
    override fun readModelChange(workspaceId: WorkspaceId, semanticModelId: cc.monomer.semantic.api.SemanticModelId): cc.monomer.semantic.api.SemanticDefinitionChange? = error("Not used by this fixture")
    override fun readMetricChange(workspaceId: WorkspaceId, metricId: MetricId): cc.monomer.semantic.api.SemanticDefinitionChange? = error("Not used by this fixture")

    override fun lockWorkspaceMutation(workspaceId: WorkspaceId) = Unit

    override fun findMutationReceipt(workspaceId: WorkspaceId, idempotencyKey: String): SemanticMutationReceipt? =
        receipts[workspaceId to idempotencyKey]

    override fun insertMutationReceipt(receipt: SemanticMutationReceipt) {
        receipts[receipt.workspaceId to receipt.idempotencyKey] = receipt
    }

    var lastModelAttribution: Triple<PrincipalId?, CredentialSurface?, cc.monomer.identity.api.AgentClientId?>? = null
    var lastMetricAttribution: Triple<PrincipalId?, CredentialSurface?, cc.monomer.identity.api.AgentClientId?>? = null

    override fun insertModel(model: CurrentSemanticModel, actorId: PrincipalId?, credentialSurface: CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?) {
        modelInsertCount += 1
        models[model.id] = model
        lastModelAttribution = Triple(actorId, credentialSurface, agentClientId)
    }

    override fun updateModel(model: CurrentSemanticModel, actorId: PrincipalId?, credentialSurface: CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?) {
        modelUpdateCount += 1
        models[model.id] = model
        lastModelAttribution = Triple(actorId, credentialSurface, agentClientId)
    }

    val metricAttributions = mutableListOf<Triple<PrincipalId?, CredentialSurface?, cc.monomer.identity.api.AgentClientId?>>()

    override fun insertMetric(metric: CurrentMetric, actorId: PrincipalId?, credentialSurface: CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?) {
        metricInsertCount += 1
        metrics[metric.id] = metric
        lastMetricAttribution = Triple(actorId, credentialSurface, agentClientId)
        metricAttributions += Triple(actorId, credentialSurface, agentClientId)
    }

    override fun updateMetric(metric: CurrentMetric, actorId: PrincipalId?, credentialSurface: CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?) {
        metricUpdateCount += 1
        metrics[metric.id] = metric
        lastMetricAttribution = Triple(actorId, credentialSurface, agentClientId)
    }

}

internal class StaticConnectorRead(
    private val workspaceId: WorkspaceId,
    private val connectorId: ConnectorId,
    private val now: Instant,
    connectorType: ConnectorProductType = ConnectorProductType.POSTGRESQL,
) : ConnectorWorkspaceReadUseCase {
    private val connector = CurrentConnectorRead(
        connectorId = connectorId,
        workspaceId = workspaceId,
        connectorType = connectorType,
        displayName = "warehouse",
        properties = when (connectorType) {
            ConnectorProductType.POSTGRESQL -> cc.monomer.connector.api.ConnectorSettingsProperties.Postgresql("warehouse", 5432, "warehouse", null, null)
            ConnectorProductType.REDSHIFT -> cc.monomer.connector.api.ConnectorSettingsProperties.Redshift("warehouse", 5439, "warehouse", null, null)
            else -> error("Unsupported test connector type")
        },
        createdAt = now,
        updatedAt = now,
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

    override suspend fun listByWorkspace(workspaceId: WorkspaceId): List<CurrentConnectorRead> =
        listOfNotNull(connector.takeIf { it.workspaceId == workspaceId })
}

internal class StaticCatalogRead(
    private val workspaceId: WorkspaceId,
    private val connectorId: ConnectorId,
    private val now: Instant,
) : CatalogCurrentObjectsReadPort {
    override fun readCurrent(workspaceId: WorkspaceId, connectorId: ConnectorId): CatalogCurrentObjectSet? =
        catalogObjects(workspaceId, connectorId, now).takeIf {
            it.workspaceId == workspaceId && it.connectorId == connectorId
        }

    override fun searchCurrentRelations(
        workspaceId: WorkspaceId,
        query: String,
        cursor: cc.monomer.catalog.api.CatalogCurrentRelationPageCursor?,
        pageSize: Int,
    ): cc.monomer.catalog.api.CatalogCurrentRelationPage = error("unused")
}

internal object AcceptingMetricFlowPort : SemanticMetricFlowPort {
    override fun validate(manifest: SemanticMetricFlowManifest): SemanticMetricFlowValidationResult =
        SemanticMetricFlowValidationResult.Valid

    override fun listGroupBys(
        manifest: SemanticMetricFlowManifest,
        metricIds: List<MetricId>,
    ): SemanticMetricFlowGroupByResult = SemanticMetricFlowGroupByResult.Listed(emptyList())

    override fun render(
        manifest: SemanticMetricFlowManifest,
        querySpec: SemanticQuerySpec,
        metricIds: List<MetricId>,
    ): SemanticMetricFlowRenderResult = SemanticMetricFlowRenderResult.Rendered(
        dialect = manifest.dialect,
        sql = "select 1",
        requestedMetricIds = metricIds,
        outputColumns = metricIds.map { metricId ->
            SemanticMetricFlowOutputColumn(
                source = SemanticMetricFlowOutputColumnSource.Metric(metricId),
                name = manifest.metrics.getValue(metricId).name.metricResultColumnKey(),
                sqlName = querySpec.resultColumns().single {
                    it.key == manifest.metrics.getValue(metricId).name.metricResultColumnKey()
                }.sqlName,
            )
        },
    )
}

private class RecordingMetricFlowPort : SemanticMetricFlowPort {
    var manifest: SemanticMetricFlowManifest? = null

    override fun validate(manifest: SemanticMetricFlowManifest): SemanticMetricFlowValidationResult {
        this.manifest = manifest
        return SemanticMetricFlowValidationResult.Valid
    }

    override fun listGroupBys(
        manifest: SemanticMetricFlowManifest,
        metricIds: List<MetricId>,
    ): SemanticMetricFlowGroupByResult = SemanticMetricFlowGroupByResult.Listed(emptyList())

    override fun render(
        manifest: SemanticMetricFlowManifest,
        querySpec: SemanticQuerySpec,
        metricIds: List<MetricId>,
    ): SemanticMetricFlowRenderResult {
        this.manifest = manifest
        return SemanticMetricFlowRenderResult.Rendered(
            dialect = manifest.dialect,
            sql = "select 1",
            requestedMetricIds = metricIds,
            outputColumns = metricIds.map { metricId ->
                SemanticMetricFlowOutputColumn(
                    source = SemanticMetricFlowOutputColumnSource.Metric(metricId),
                    name = manifest.metrics.getValue(metricId).name.metricResultColumnKey(),
                sqlName = querySpec.resultColumns().single {
                    it.key == manifest.metrics.getValue(metricId).name.metricResultColumnKey()
                }.sqlName,
                )
            },
        )
    }
}

internal fun catalogObjects(
    workspaceId: WorkspaceId,
    connectorId: ConnectorId,
    observedAt: Instant,
): CatalogCurrentObjectSet {
    val schemaId = CatalogObjectId(Uuid.parse("0197d83f-2c9e-8a3b-8c1d-000000000002"))
    val relationId = CatalogObjectId(Uuid.parse("0197d83f-2c9e-8a3b-8c1d-000000000003"))
    fun digest(suffix: Int) = CatalogDigest("sha256:" + suffix.toString().padStart(64, '0'))
    return CatalogCurrentObjectSet(
        workspaceId = workspaceId,
        connectorId = connectorId,
        objects = listOf(
            CatalogCurrentObject(
                id = schemaId,
                identityDigest = digest(2),
                parentId = null,
                kind = CatalogObjectKind.SCHEMA,
                relationKind = null,
                address = CatalogPhysicalAddress(null, "analytics", null, null),
                qualifiedName = "analytics",
                nativeType = null,
                contentDigest = digest(2),
                definition = null,
                ordinal = null,
                nullable = null,
                defaultExpression = null,
                relationshipKind = null,
                memberOrdinal = null,
                constraintName = null,
                sourceColumn = null,
                referencedCatalog = null,
                referencedSchema = null,
                referencedRelation = null,
                referencedColumn = null,
                observedAt = observedAt,
                partitionKeys = null, clusteringKeys = null, requiresPartitionFilter = null,
            ),
            CatalogCurrentObject(
                id = relationId,
                identityDigest = digest(3),
                parentId = schemaId,
                kind = CatalogObjectKind.RELATION,
                relationKind = RelationKind.TABLE,
                address = CatalogPhysicalAddress(null, "analytics", "orders", null),
                qualifiedName = "analytics.orders",
                nativeType = null,
                contentDigest = digest(3),
                definition = "create table orders",
                ordinal = null,
                nullable = null,
                defaultExpression = null,
                relationshipKind = null,
                memberOrdinal = null,
                constraintName = null,
                sourceColumn = null,
                referencedCatalog = null,
                referencedSchema = null,
                referencedRelation = null,
                referencedColumn = null,
                observedAt = observedAt,
                partitionKeys = null, clusteringKeys = null, requiresPartitionFilter = null,
            ),
            catalogCalendarRelation(schemaId, observedAt),
        ),
    )
}

private fun catalogCalendarRelation(parentId: CatalogObjectId, observedAt: Instant): CatalogCurrentObject {
    val digest = CatalogDigest("sha256:" + "4".repeat(64))
    return CatalogCurrentObject(
        id = CatalogObjectId(Uuid.parse("0197d83f-2c9e-8a3b-8c1d-000000000004")),
        identityDigest = digest,
        parentId = parentId,
        kind = CatalogObjectKind.RELATION,
        relationKind = RelationKind.TABLE,
        address = CatalogPhysicalAddress(null, "analytics", "calendar", null),
        qualifiedName = "analytics.calendar",
        nativeType = null,
        contentDigest = digest,
        definition = "create table calendar",
        ordinal = null,
        nullable = null,
        defaultExpression = null,
        relationshipKind = null,
        memberOrdinal = null,
        constraintName = null,
        sourceColumn = null,
        referencedCatalog = null,
        referencedSchema = null,
        referencedRelation = null,
        referencedColumn = null,
        observedAt = observedAt,
        partitionKeys = null, clusteringKeys = null, requiresPartitionFilter = null,
    )
}

private fun IdentityRoleEvidence.forMutation(intent: cc.monomer.identity.api.MutationIntent) =
    cc.monomer.identity.api.AuthorizedMutationContext(
        organizationPlan, actor, organizationId, workspaceId, authorizationRevision,
        credentialKind, credentialSurface, agentClientId, role, intent, java.time.Instant.parse("2026-09-29T00:00:00Z"),
    )
