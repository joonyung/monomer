@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.semantic.adapter.outbound.postgres

import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.*
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.*
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.MetricListBoundary
import cc.monomer.semantic.api.SemanticModelListBoundary
import cc.monomer.semantic.api.SemanticConstantProperty
import cc.monomer.semantic.api.SemanticDimensionKind
import cc.monomer.semantic.api.SemanticDimensionDefinition
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticEntityDefinition
import cc.monomer.semantic.api.SemanticEntityKind
import cc.monomer.semantic.api.SemanticConversionCalculation
import cc.monomer.semantic.api.SemanticMeasureAggregation
import cc.monomer.semantic.api.SemanticMeasureDefinition
import cc.monomer.semantic.api.SemanticMetricDefinition
import cc.monomer.semantic.api.SemanticMetricInput
import cc.monomer.semantic.api.SemanticMetricPeriodAggregation
import cc.monomer.semantic.api.SemanticMetricWindow
import cc.monomer.semantic.api.SemanticMetricWindowUnit
import cc.monomer.semantic.api.SemanticModelDefinition
import cc.monomer.semantic.api.SemanticSourceRelation
import cc.monomer.semantic.api.SemanticTimeGranularity
import cc.monomer.semantic.api.SemanticTimeSpine
import cc.monomer.semantic.api.SemanticTimeSpineDefinition
import cc.monomer.semantic.api.SemanticTimeSpinePrimaryColumn
import cc.monomer.semantic.api.SemanticTimeSpineRelation
import cc.monomer.semantic.domain.CurrentMetric
import cc.monomer.semantic.domain.CurrentSemanticModel
import cc.monomer.semantic.domain.CurrentTimeSpineConfiguration
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.SQLException
import java.time.Instant
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import cc.monomer.semantic.application.SemanticAuthorityService
import cc.monomer.semantic.application.SemanticCurrentReadService
import cc.monomer.semantic.application.SemanticTransactionPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(TargetSemanticPostgres::class)
class SemanticCurrentRepositoryPostgresIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var repository: PostgresSemanticRepository
    private val objectMapper = ObjectMapper()

    @BeforeEach
    fun setUp() {
        dataSource = TargetSemanticPostgres.dataSource
        repository = PostgresSemanticRepository(dataSource, objectMapper)
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TRIGGER IF EXISTS test_reject_change ON semantic.definition_changes")
                statement.execute("CREATE TABLE IF NOT EXISTS semantic.test_search_documents (workspace_id UUID NOT NULL, kind TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY (workspace_id, kind, name))")
                statement.execute("TRUNCATE semantic.test_search_documents")
                statement.execute(
                    "TRUNCATE semantic.semantic_models, semantic.metrics, semantic.time_spine_configurations, semantic.mutation_receipts, semantic.definition_changes CASCADE",
                )
            }
        }
    }

    @Test
    fun `model create and update reject second aggregate metric without any partial state`() = runBlocking {
        for (updating in listOf(false, true)) {
            setUp()
            val workspace = WorkspaceId.generate()
            val service = SemanticAuthorityService(repository, transactionPort(), Clock.fixed(instant(0), ZoneOffset.UTC), searchProjection())
            val original = if (updating) assertIs<SemanticMutationResult.Committed<SemanticModelWriteResult>>(
                service.putModel(PutSemanticModelCommand(authorization(workspace, MutationIntent.SEMANTIC_MODEL_CREATE), "original", null, modelDefinition(), emptyList()))).value.model else null
            service.putMetric(PutMetricCommand(authorization(workspace, MutationIntent.METRIC_CREATE), "existing", null, simpleMetric("batch_second")))
            val before = batchCounts(workspace)
            val command = PutSemanticModelCommand(authorization(workspace, if (updating) MutationIntent.SEMANTIC_MODEL_UPDATE else MutationIntent.SEMANTIC_MODEL_CREATE),
                "batch", original?.semanticModelId, modelDefinition(label = "Changed"), listOf(aggregateMetric("batch_first"), aggregateMetric("batch_second")))
            assertEquals(SemanticMutationResult.AggregateMetricNameConflict("aggregateMetrics[1].name"), service.putModel(command))
            assertEquals(before, batchCounts(workspace))
            assertEquals(null, repository.findMutationReceipt(workspace, "batch"))
            assertEquals(null, repository.findMetricByName(workspace, SemanticElementName("batch_first")))
            if (original != null) assertEquals(original.definition, repository.findModel(original.semanticModelId)?.definition)
            val missingMeasure = command.copy(aggregateMetrics = listOf(aggregateMetric("batch_first"), aggregateMetric("batch_new").copy(measure = SemanticElementName("missing"))))
            assertEquals("aggregateMetrics[1].measure", assertIs<SemanticMutationResult.Invalid>(service.putModel(missingMeasure)).reasons.single().field)
            assertEquals(before, batchCounts(workspace))
        }
    }

    @Test
    fun `second aggregate history failure rolls back create and update model metrics search history and receipt`() = runBlocking {
        for (updating in listOf(false, true)) {
            setUp()
            val workspace = WorkspaceId.generate()
            val service = SemanticAuthorityService(repository, transactionPort(), Clock.fixed(instant(0), ZoneOffset.UTC), searchProjection())
            val original = if (updating) assertIs<SemanticMutationResult.Committed<SemanticModelWriteResult>>(
                service.putModel(PutSemanticModelCommand(authorization(workspace, MutationIntent.SEMANTIC_MODEL_CREATE), "original", null, modelDefinition(), emptyList()))).value.model else null
            val before = batchCounts(workspace)
            dataSource.connection.use { connection -> connection.createStatement().use { statement ->
                statement.execute("CREATE OR REPLACE FUNCTION semantic.test_reject_change() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN IF NEW.definition_kind = ''METRIC'' AND NEW.definition_name = ''batch_second'' THEN RAISE EXCEPTION ''forced second aggregate failure''; END IF; RETURN NEW; END'")
                statement.execute("CREATE TRIGGER test_reject_change BEFORE INSERT ON semantic.definition_changes FOR EACH ROW EXECUTE FUNCTION semantic.test_reject_change()")
            } }
            val command = PutSemanticModelCommand(authorization(workspace, if (updating) MutationIntent.SEMANTIC_MODEL_UPDATE else MutationIntent.SEMANTIC_MODEL_CREATE),
                "batch", original?.semanticModelId, modelDefinition(label = "Changed"), listOf(aggregateMetric("batch_first"), aggregateMetric("batch_second")))
            val failure = assertFailsWith<java.lang.reflect.UndeclaredThrowableException> { service.putModel(command) }
            assertTrue(assertIs<SQLException>(failure.undeclaredThrowable).message.orEmpty().contains("forced second aggregate failure"))
            assertEquals(before, batchCounts(workspace))
            assertEquals(null, repository.findMutationReceipt(workspace, "batch"))
            assertTrue(repository.findMetrics(workspace).isEmpty())
            if (original != null) assertEquals(original.definition, repository.findModel(original.semanticModelId)?.definition)
            dataSource.connection.use { connection -> connection.createStatement().use { it.execute("DROP TRIGGER test_reject_change ON semantic.definition_changes") } }
            val saved = assertIs<SemanticMutationResult.Committed<SemanticModelWriteResult>>(service.putModel(command)).value
            assertEquals(listOf("batch_first", "batch_second"), saved.aggregateMetrics.map { it.definition.name.value })
            assertEquals(before.zip(listOf(if (updating) 0 else 1, 2, 3, 1, if (updating) 2 else 3)).map { (count, delta) -> count + delta }, batchCounts(workspace))
        }
    }

    @Test
    fun `atomic replay binds ordered descriptors and original child IDs without new definitions or history`() = runBlocking {
        val workspace = WorkspaceId.generate()
        val service = SemanticAuthorityService(repository, transactionPort(), Clock.fixed(instant(0), ZoneOffset.UTC), searchProjection())
        val command = PutSemanticModelCommand(authorization(workspace, MutationIntent.SEMANTIC_MODEL_CREATE), "batch", null, modelDefinition(),
            listOf(aggregateMetric("batch_first"), aggregateMetric("batch_second")))
        val saved = assertIs<SemanticMutationResult.Committed<SemanticModelWriteResult>>(service.putModel(command)).value
        val counts = batchCounts(workspace)
        assertEquals(SemanticMutationResult.Committed(saved), service.putModel(command))
        assertEquals(counts, batchCounts(workspace))
        assertEquals(SemanticMutationResult.IdempotencyConflict, service.putModel(command.copy(aggregateMetrics = command.aggregateMetrics.reversed())))
        assertEquals(SemanticMutationResult.IdempotencyConflict, service.putModel(command.copy(aggregateMetrics = listOf(command.aggregateMetrics[0].copy(label = "Changed"), command.aggregateMetrics[1]))))
        assertEquals(SemanticMutationResult.IdempotencyConflict, service.putModel(command.copy(aggregateMetrics = emptyList())))
        service.deleteMetric(DeleteMetricCommand(authorization(workspace, MutationIntent.METRIC_DELETE), saved.aggregateMetrics[0].metricId))
        val replacement = assertIs<SemanticMutationResult.Committed<MetricView>>(service.putMetric(PutMetricCommand(authorization(workspace, MutationIntent.METRIC_CREATE), "replacement", null, saved.aggregateMetrics[0].definition))).value
        assertTrue(replacement.metricId != saved.aggregateMetrics[0].metricId)
        val afterReplacement = batchCounts(workspace)
        assertEquals(SemanticMutationResult.NotFoundOrOutsideWorkspace, service.putModel(command))
        assertEquals(afterReplacement, batchCounts(workspace))
    }

    private fun aggregateMetric(name: String) = SemanticAggregateMetric(SemanticElementName("revenue"), SemanticElementName(name), "Aggregate metric", null,
        SemanticTimeGranularity.MONTH, listOf(SemanticMetricFilter.Predicate(SemanticElementReference(SemanticElementName("orders_model"), SemanticElementName("status"), null), SemanticMetricFilterOperator.EQ, listOf("paid"))))

    private fun batchCounts(workspaceId: WorkspaceId): List<Int> = listOf("semantic_models", "metrics", "definition_changes", "mutation_receipts", "test_search_documents").map { table ->
        dataSource.connection.use { connection -> connection.prepareStatement("SELECT count(*) FROM semantic.$table WHERE workspace_id = ?").use { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            statement.executeQuery().use { result -> check(result.next()); result.getInt(1) }
        } }
    }

    @Test
    fun `recent records preserve every snapshot with actor app keyset and deleted Workspace isolation`() {
        val workspace = WorkspaceId.generate()
        val actor = PrincipalId.generate()
        val agentActor = PrincipalId.generate()
        val app = AgentClientId("https://example.com/agent")
        val model = CurrentSemanticModel(SemanticModelId.generate(), workspace, modelDefinition(name = "orders"), instant(0), instant(0))
        val metric = CurrentMetric(MetricId.generate(), workspace, simpleMetric("revenue"), instant(0), instant(0))
        repository.insertModel(model, actor, CredentialSurface.WEB, null)
        repository.insertMetric(metric, actor, CredentialSurface.WEB, null)
        repository.appendDefinitionChange(workspace, model.id.value, SemanticDefinitionVersion(Uuid.generateV7(), SemanticDefinitionChangeAction.CREATED, SemanticDefinitionSnapshot.Model(model.definition), instant(0), actor, CredentialSurface.WEB, null, false))
        repository.appendDefinitionChange(workspace, metric.id.value, SemanticDefinitionVersion(Uuid.generateV7(), SemanticDefinitionChangeAction.CREATED, SemanticDefinitionSnapshot.Metric(metric.definition), instant(0), actor, CredentialSurface.WEB, null, false))
        val updatedModel = model.copy(definition = model.definition.copy(label = "Updated"), updatedAt = instant(1))
        repository.updateModel(updatedModel, agentActor, CredentialSurface.AGENT, app)
        repository.updateMetric(metric.copy(updatedAt = instant(1)), agentActor, CredentialSurface.AGENT, app)
        repository.appendDefinitionChange(workspace, model.id.value, SemanticDefinitionVersion(Uuid.generateV7(), SemanticDefinitionChangeAction.UPDATED, SemanticDefinitionSnapshot.Model(updatedModel.definition), instant(1), agentActor, CredentialSurface.AGENT, app, false))
        repository.appendDefinitionChange(workspace, metric.id.value, SemanticDefinitionVersion(Uuid.generateV7(), SemanticDefinitionChangeAction.UPDATED, SemanticDefinitionSnapshot.Metric(metric.definition), instant(1), agentActor, CredentialSurface.AGENT, app, false))
        assertEquals(app, repository.readModelChange(workspace, model.id)?.agentClientId)
        assertEquals(app, repository.readMetricChange(workspace, metric.id)?.agentClientId)
        repository.deleteModel(workspace, model.id)
        repository.deleteMetric(workspace, metric.id)
        repository.appendDefinitionChange(workspace, model.id.value, SemanticDefinitionVersion(Uuid.generateV7(), SemanticDefinitionChangeAction.DELETED, SemanticDefinitionSnapshot.Model(updatedModel.definition), instant(2), agentActor, CredentialSurface.AGENT, app, false))
        repository.appendDefinitionChange(workspace, metric.id.value, SemanticDefinitionVersion(Uuid.generateV7(), SemanticDefinitionChangeAction.DELETED, SemanticDefinitionSnapshot.Metric(metric.definition), instant(2), agentActor, CredentialSurface.AGENT, app, false))
        for (read in listOf(repository::readRecentModels, repository::readRecentMetrics)) {
            val records = read(workspace, null, null, null, null, null, 10)
            assertEquals(listOf(SemanticDefinitionChangeAction.DELETED, SemanticDefinitionChangeAction.UPDATED, SemanticDefinitionChangeAction.CREATED), records.map { it.action })
            assertTrue(records.all { it.currentlyDeleted })
            assertEquals(records.drop(1), read(workspace, records.first().updatedAt, records.first().changeId, null, null, null, 10))
            assertEquals(records.take(1), read(workspace, null, null, null, null, null, 1))
            assertEquals(records.take(2), read(workspace, null, null, agentActor, CredentialSurface.AGENT, app, 10))
            assertEquals(records.takeLast(1), read(workspace, null, null, actor, CredentialSurface.WEB, null, 10))
            assertTrue(read(WorkspaceId.generate(), null, null, null, null, null, 10).isEmpty())
        }
        assertEquals(listOf(app), repository.readRecentAgentClients(workspace, 500).map { it.clientId })
        assertEquals(instant(2), repository.readRecentAgentClients(workspace, 500).single().lastSeenAt)
        assertEquals(null, repository.readModelChange(workspace, model.id))
        assertEquals(null, repository.readMetricChange(workspace, metric.id))
        assertEquals(listOf("Updated", "Updated", "Orders"), repository.readRecentModels(workspace, null, null, null, null, null, 10).map { it.label })
    }

    @Test
    fun `unknown historical attribution stays null and equal timestamps use change ID keyset`() {
        val workspace = WorkspaceId.generate()
        val definitionId = SemanticModelId.generate()
        val first = SemanticDefinitionVersion(Uuid.parse("01942b04-6e00-7000-8000-000000000001"), SemanticDefinitionChangeAction.CREATED,
            SemanticDefinitionSnapshot.Model(modelDefinition()), instant(0), null, null, null, false)
        val second = first.copy(changeId = Uuid.parse("01942b04-6e00-7000-8000-000000000002"), action = SemanticDefinitionChangeAction.UPDATED)
        repository.appendDefinitionChange(workspace, definitionId.value, first)
        repository.appendDefinitionChange(workspace, definitionId.value, second)
        assertEquals(listOf(second), repository.readDefinitionVersions(workspace, SemanticDefinitionKind.SEMANTIC_MODEL, definitionId.value, null, 1))
        assertEquals(listOf(first), repository.readDefinitionVersions(workspace, SemanticDefinitionKind.SEMANTIC_MODEL, definitionId.value,
            SemanticHistoryBoundary(second.occurredAt, second.changeId), 1))
        assertEquals(null, repository.findDefinitionVersion(workspace, SemanticDefinitionKind.METRIC, definitionId.value, first.changeId))
        assertEquals(null, repository.findDefinitionVersion(WorkspaceId.generate(), SemanticDefinitionKind.SEMANTIC_MODEL, definitionId.value, first.changeId))
        val summaries = repository.readRecentModels(workspace, null, null, null, null, null, 10)
        assertEquals(listOf(second.changeId, first.changeId), summaries.map { it.changeId })
        assertTrue(summaries.all { it.actorId == null && it.credentialSurface == null && it.agentClientId == null })
        assertEquals(summaries.drop(1), repository.readRecentModels(workspace, second.occurredAt, second.changeId, null, null, null, 10))
    }

    @Test
    fun `recent record reads use bounded Workspace kind and actor indexes`() {
        val workspace = WorkspaceId.generate()
        val actor = PrincipalId.generate()
        dataSource.connection.use { connection -> connection.createStatement().use { statement ->
            for (kind in SemanticDefinitionKind.entries) {
                statement.execute("""
                    INSERT INTO semantic.definition_changes (change_id, workspace_id, definition_kind, definition_id, definition_name, action, definition, actor_id, credential_surface, occurred_at)
                    SELECT ('01942b04-6e00-7000-8000-' || lpad(to_hex(n + ${kind.ordinal * 5000}), 12, '0'))::uuid, '$workspace', '$kind',
                        ('01942b04-6e00-7000-8000-' || lpad(to_hex(n), 12, '0'))::uuid, 'resource_' || n, 'UPDATED',
                        jsonb_build_object('name', 'resource_' || n), CASE WHEN n % 2 = 0 THEN '$actor'::uuid ELSE NULL END, 'WEB',
                        '2026-09-09T00:00:00Z'::timestamptz + n * interval '1 microsecond' FROM generate_series(1, 5000) n
                """.trimIndent())
            }
            statement.execute("ANALYZE semantic.definition_changes")
            for (kind in SemanticDefinitionKind.entries) for (actorFilter in listOf(false, true)) {
                val filter = if (actorFilter) "AND actor_id = '$actor'" else ""
                statement.executeQuery("""
                    EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
                    SELECT change_id, definition_id, definition_name, action, definition ->> 'label', occurred_at, actor_id, credential_surface
                    FROM semantic.definition_changes WHERE workspace_id = '$workspace' AND definition_kind = '$kind' $filter
                        AND (occurred_at, change_id) < ('2026-09-09T00:00:00.004Z', 'ffffffff-ffff-ffff-ffff-ffffffffffff')
                    ORDER BY occurred_at DESC, change_id DESC LIMIT 21
                """.trimIndent()).use { result ->
                    assertTrue(result.next())
                    val evidence = result.getString(1)
                    val plan = objectMapper.readTree(evidence)[0]["Plan"]
                    assertEquals("Limit", plan["Node Type"].asText())
                    assertEquals(21, plan["Actual Rows"].asInt())
                    assertTrue(evidence.contains(if (actorFilter) "definition_changes_recent_actor" else "definition_changes_recent"), evidence)
                    assertTrue(!evidence.contains("Seq Scan"), evidence)
                }
            }
        } }
        assertEquals(21, repository.readRecentModels(workspace, null, null, actor, null, null, 21).size)
        assertEquals(21, repository.readRecentMetrics(workspace, null, null, actor, null, null, 21).size)
    }

    @Test
    fun `definition change write failure rolls back Model and Metric delete with search documents and history`() = runBlocking {
        val workspace = WorkspaceId.generate()
        val transactions = transactionPort()
        val service = SemanticAuthorityService(repository, transactions, Clock.fixed(instant(0), ZoneOffset.UTC), searchProjection())
        val model = assertIs<SemanticMutationResult.Committed<cc.monomer.semantic.api.SemanticModelWriteResult>>(service.putModel(PutSemanticModelCommand(authorization(workspace, MutationIntent.SEMANTIC_MODEL_CREATE), "rollback-model", null, modelDefinition(), emptyList()))).value.model
        val metric = assertIs<SemanticMutationResult.Committed<MetricView>>(service.putMetric(PutMetricCommand(authorization(workspace, MutationIntent.METRIC_CREATE), "rollback-metric", null, simpleMetric("revenue")))).value
        dataSource.connection.use { connection -> connection.createStatement().use { statement ->
            statement.execute("CREATE OR REPLACE FUNCTION semantic.test_reject_change() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN IF NEW.action = ''DELETED'' THEN RAISE EXCEPTION ''forced record failure''; END IF; RETURN NEW; END'")
            statement.execute("CREATE TRIGGER test_reject_change BEFORE INSERT ON semantic.definition_changes FOR EACH ROW EXECUTE FUNCTION semantic.test_reject_change()")
        } }
        val modelFailure = assertFailsWith<java.lang.reflect.UndeclaredThrowableException> {
            service.deleteModel(DeleteSemanticModelCommand(authorization(workspace, MutationIntent.SEMANTIC_MODEL_DELETE), model.semanticModelId))
        }
        val metricFailure = assertFailsWith<java.lang.reflect.UndeclaredThrowableException> {
            service.deleteMetric(DeleteMetricCommand(authorization(workspace, MutationIntent.METRIC_DELETE), metric.metricId))
        }
        assertTrue(assertIs<SQLException>(modelFailure.undeclaredThrowable).message.orEmpty().contains("forced record failure"))
        assertTrue(assertIs<SQLException>(metricFailure.undeclaredThrowable).message.orEmpty().contains("forced record failure"))
        assertEquals(model.definition, repository.findModel(model.semanticModelId)?.definition)
        assertEquals(metric.definition, repository.findMetric(metric.metricId)?.definition)
        assertEquals(1, repository.readDefinitionVersions(workspace, SemanticDefinitionKind.SEMANTIC_MODEL, model.semanticModelId.value, null, 10).size)
        assertEquals(1, repository.readDefinitionVersions(workspace, SemanticDefinitionKind.METRIC, metric.metricId.value, null, 10).size)
        assertEquals(2, searchDocumentCount(workspace))
    }

    @Test
    fun `Postgres lifecycle keeps deleted history isolates Workspace and restores original IDs with conflict and receipts`() = runBlocking {
        val workspace = WorkspaceId.generate()
        val transactions = transactionPort()
        var seconds = 0L
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant(): Instant = instant(seconds++)
        }
        val service = SemanticAuthorityService(repository, transactions, clock, searchProjection())
        val read = SemanticCurrentReadService(repository, repository, transactions, io.mockk.mockk {
            io.mockk.coEvery { listByWorkspace(workspace) } returns emptyList()
        },
            io.mockk.mockk(relaxed = true),
        )
        val createModel = PutSemanticModelCommand(authorization(workspace, MutationIntent.SEMANTIC_MODEL_CREATE), "lifecycle-model", null, modelDefinition(), emptyList())
        val model = assertIs<SemanticMutationResult.Committed<cc.monomer.semantic.api.SemanticModelWriteResult>>(service.putModel(createModel)).value.model
        service.putModel(createModel)
        val initial = assertNotNull(read.readModelHistory(workspace, model.semanticModelId, null, 10)).changes.single()
        assertTrue(!initial.restorable)
        val deleteModel = DeleteSemanticModelCommand(authorization(workspace, MutationIntent.SEMANTIC_MODEL_DELETE), model.semanticModelId)
        assertEquals(SemanticMutationResult.NotFoundOrOutsideWorkspace, service.deleteModel(deleteModel.copy(authorization = authorization(WorkspaceId.generate(), MutationIntent.SEMANTIC_MODEL_DELETE))))
        service.putModel(PutSemanticModelCommand(authorization(workspace, MutationIntent.SEMANTIC_MODEL_UPDATE), "lifecycle-update", model.semanticModelId, model.definition.copy(label = "Edited"), aggregateMetrics = emptyList()))
        service.deleteModel(deleteModel)
        assertEquals(SemanticMutationResult.NotFoundOrOutsideWorkspace, service.deleteModel(deleteModel))
        assertEquals(SemanticMutationResult.NotFoundOrOutsideWorkspace, service.putModel(PutSemanticModelCommand(authorization(workspace, MutationIntent.SEMANTIC_MODEL_UPDATE), "after-delete", model.semanticModelId, model.definition, emptyList())))
        val deleted = assertNotNull(read.readModelHistory(workspace, model.semanticModelId, null, 2))
        assertTrue(deleted.deleted)
        assertEquals(listOf(SemanticDefinitionChangeAction.DELETED, SemanticDefinitionChangeAction.UPDATED), deleted.changes.map { it.action })
        assertEquals(listOf(true, false), deleted.changes.map { it.restorable })
        val older = assertNotNull(read.readModelHistory(workspace, model.semanticModelId, deleted.next, 2))
        assertEquals(listOf(initial.copy(restorable = true)), older.changes)
        for (version in deleted.changes + older.changes) {
            assertEquals(version, read.readModelVersion(workspace, model.semanticModelId, version.changeId))
        }
        assertEquals(null, read.readModelHistory(WorkspaceId.generate(), model.semanticModelId, null, 10))
        val recreated = assertIs<SemanticMutationResult.Committed<cc.monomer.semantic.api.SemanticModelWriteResult>>(service.putModel(createModel.copy(idempotencyKey = "recreate"))).value.model
        assertTrue(recreated.semanticModelId != model.semanticModelId)
        val restore = RestoreSemanticModelCommand(authorization(workspace, MutationIntent.SEMANTIC_MODEL_UPDATE), "restore-model", model.semanticModelId, initial.changeId)
        assertEquals(SemanticMutationResult.Conflict, service.restoreModel(restore))
        service.deleteModel(deleteModel.copy(semanticModelId = recreated.semanticModelId))
        val restored = assertIs<SemanticMutationResult.Committed<SemanticModelView>>(service.restoreModel(restore)).value
        assertEquals(model.semanticModelId, restored.semanticModelId)
        assertEquals(model.definition, restored.definition)
        service.restoreModel(restore)
        val restoredHistory = assertNotNull(read.readModelHistory(workspace, model.semanticModelId, null, 10))
        assertEquals(4, restoredHistory.changes.size)
        assertEquals(listOf(false, true, true, false), restoredHistory.changes.map { it.restorable })
        val createMetric = PutMetricCommand(authorization(workspace, MutationIntent.METRIC_CREATE), "lifecycle-metric", null, simpleMetric("revenue"))
        val metric = assertIs<SemanticMutationResult.Committed<MetricView>>(service.putMetric(createMetric)).value
        val initialMetric = assertNotNull(read.readMetricHistory(workspace, metric.metricId, null, 10)).changes.single()
        assertTrue(!initialMetric.restorable)
        val deleteMetric = DeleteMetricCommand(authorization(workspace, MutationIntent.METRIC_DELETE), metric.metricId)
        service.deleteMetric(deleteMetric)
        assertEquals(SemanticMutationResult.NotFoundOrOutsideWorkspace, service.deleteMetric(deleteMetric))
        val deletedMetricHistory = assertNotNull(read.readMetricHistory(workspace, metric.metricId, null, 10))
        assertEquals(listOf(true, false), deletedMetricHistory.changes.map { it.restorable })
        for (version in deletedMetricHistory.changes) {
            assertEquals(version, read.readMetricVersion(workspace, metric.metricId, version.changeId))
        }
        val restoredMetric = assertIs<SemanticMutationResult.Committed<MetricView>>(service.restoreMetric(RestoreMetricCommand(authorization(workspace, MutationIntent.METRIC_UPDATE), "restore-metric", metric.metricId, initialMetric.changeId))).value
        assertEquals(metric.metricId, restoredMetric.metricId)
        assertEquals(metric.definition, restoredMetric.definition)
        assertEquals(listOf(false, false, false), assertNotNull(read.readMetricHistory(workspace, metric.metricId, null, 10)).changes.map { it.restorable })
        assertEquals(2, searchDocumentCount(workspace))
    }

    @Test
    fun `name keyset lists direct children with database limit and workspace isolation`() {
        val workspaceId = WorkspaceId(Uuid.generateV7())
        val otherWorkspaceId = WorkspaceId(Uuid.generateV7())
        listOf("alpha", "beta", "gamma").forEachIndexed { index, name ->
            repository.insertModel(
                CurrentSemanticModel(
                    id = cc.monomer.semantic.api.SemanticModelId.generate(),
                    workspaceId = workspaceId,
                    definition = modelDefinition(name = name),
                    createdAt = instant(index.toLong()),
                    updatedAt = instant(index.toLong()),
                ), null, null, null)
            repository.insertMetric(
                CurrentMetric(
                    id = MetricId.generate(),
                    workspaceId = workspaceId,
                    definition = simpleMetric(name),
                    createdAt = instant(index.toLong()),
                    updatedAt = instant(index.toLong()),
                ), null, null, null)
        }
        repository.insertModel(
            CurrentSemanticModel(
                id = cc.monomer.semantic.api.SemanticModelId.generate(),
                workspaceId = otherWorkspaceId,
                definition = modelDefinition(name = "between"),
                createdAt = instant(4),
                updatedAt = instant(4),
            ), null, null, null)

        val models = repository.listModels(
            workspaceId,
            SemanticModelListBoundary(SemanticElementName("alpha")),
            1,
        )
        val metrics = repository.listMetrics(
            workspaceId,
            MetricListBoundary(SemanticElementName("beta")),
            2,
        )

        assertEquals(listOf("beta"), models.map { it.definition.name.value })
        assertEquals(listOf("gamma"), metrics.map { it.definition.name.value })
    }

    @Test
    fun `current model row is read back and later write silently replaces definition`() {
        val workspaceId = WorkspaceId(Uuid.generateV7())
        val modelId = cc.monomer.semantic.api.SemanticModelId.generate()
        val createdAt = Instant.parse("2026-08-19T00:00:00Z")
        val initial = CurrentSemanticModel(
            id = modelId,
            workspaceId = workspaceId,
            definition = modelDefinition(label = "Initial", measureExpression = "amount"),
            createdAt = createdAt,
            updatedAt = createdAt,
        )
        repository.insertModel(initial, null, null, null)

        val readInitial = repository.findModel(modelId)
        assertNotNull(readInitial)
        assertEquals(initial, readInitial)

        val updatedAt = createdAt.plusSeconds(1)
        repository.updateModel(
            initial.copy(
                definition = modelDefinition(label = "Replaced", measureExpression = "net_amount"),
                updatedAt = updatedAt,
            ), null, null, null)

        val current = repository.findModel(modelId)
        assertNotNull(current)
        assertEquals("Replaced", current.definition.label)
        assertEquals("net_amount", current.definition.measures.single().expression)
        assertEquals(createdAt, current.createdAt)
        assertEquals(updatedAt, current.updatedAt)
    }

    @Test
    fun `all five metric shapes round trip as current JSON and do not require a model row`() {
        val workspaceId = WorkspaceId(Uuid.generateV7())
        val definitions = listOf(
            simpleMetric("simple_metric"),
            ratioMetric("ratio_metric"),
            derivedMetric("derived_metric"),
            cumulativeMetric("cumulative_metric"),
            conversionMetric("conversion_metric"),
        )
        val inserted = definitions.mapIndexed { index, definition ->
            val metric = CurrentMetric(
                id = MetricId.generate(),
                workspaceId = workspaceId,
                definition = definition,
                createdAt = Instant.parse("2026-08-19T00:00:0${index}Z"),
                updatedAt = Instant.parse("2026-08-19T00:00:0${index}Z"),
            )
            repository.insertMetric(metric, null, null, null)
            metric
        }

        inserted.forEach { expected ->
            val actual = repository.findMetric(expected.id)
            assertNotNull(actual)
            assertEquals(expected, actual)
        }

        val orphan = repository.findMetricByName(workspaceId, SemanticElementName("simple_metric"))
        assertNotNull(orphan)
        assertEquals("orders_model_not_persisted", orphan.definition.let { definition ->
            when (definition) {
                is SemanticMetricDefinition.Simple -> definition.semanticModelName.value
                else -> error("expected a simple metric")
            }
        })
    }

    @Test
    fun `same workspace names are unique while the same name is isolated by workspace`() {
        val firstWorkspace = WorkspaceId(Uuid.generateV7())
        val secondWorkspace = WorkspaceId(Uuid.generateV7())
        val firstDefinition = modelDefinition(name = "shared_model", label = "First")
        repository.insertModel(
            CurrentSemanticModel(
                id = cc.monomer.semantic.api.SemanticModelId.generate(),
                workspaceId = firstWorkspace,
                definition = firstDefinition,
                createdAt = instant(0),
                updatedAt = instant(0),
            ), null, null, null)
        repository.insertModel(
            CurrentSemanticModel(
                id = cc.monomer.semantic.api.SemanticModelId.generate(),
                workspaceId = secondWorkspace,
                definition = firstDefinition.copy(label = "Second"),
                createdAt = instant(1),
                updatedAt = instant(1),
            ), null, null, null)

        assertEquals("First", repository.findModelByName(firstWorkspace, SemanticElementName("shared_model"))?.definition?.label)
        assertEquals("Second", repository.findModelByName(secondWorkspace, SemanticElementName("shared_model"))?.definition?.label)

        assertFailsWith<SQLException> {
            repository.insertModel(
                CurrentSemanticModel(
                    id = cc.monomer.semantic.api.SemanticModelId.generate(),
                    workspaceId = firstWorkspace,
                    definition = firstDefinition.copy(label = "Duplicate"),
                    createdAt = instant(2),
                    updatedAt = instant(2),
                ), null, null, null)
        }
    }

    @Test
    fun `persisted definitions contain schemaVersion but no lifecycle identity`() {
        val workspaceId = WorkspaceId(Uuid.generateV7())
        val model = CurrentSemanticModel(
            id = cc.monomer.semantic.api.SemanticModelId.generate(),
            workspaceId = workspaceId,
            definition = modelDefinition(),
            createdAt = instant(0),
            updatedAt = instant(0),
        )
        val metric = CurrentMetric(
            id = MetricId.generate(),
            workspaceId = workspaceId,
            definition = simpleMetric("forbidden_field_probe"),
            createdAt = instant(0),
            updatedAt = instant(0),
        )
        repository.insertModel(model, null, null, null)
        repository.insertMetric(metric, null, null, null)

        assertForbiddenDefinitionFields(rawDefinition("semantic.semantic_models", "semantic_model_id", model.id.value))
        assertForbiddenDefinitionFields(rawDefinition("semantic.metrics", "metric_id", metric.id.value))
    }

    @Test
    fun `time spine configuration round trips with one current row per workspace connector`() {
        val workspaceId = WorkspaceId(Uuid.generateV7())
        val otherWorkspaceId = WorkspaceId(Uuid.generateV7())
        val connectorId = ConnectorId.generate()
        val initial = CurrentTimeSpineConfiguration(
            workspaceId = workspaceId,
            connectorId = connectorId,
            definition = timeSpineDefinition(
                SemanticTimeGranularity.DAY,
                SemanticTimeGranularity.HOUR,
            ),
        )
        repository.upsertTimeSpineConfiguration(initial)

        assertEquals(listOf(initial), repository.findTimeSpineConfigurations(workspaceId))
        assertTrue(repository.findTimeSpineConfigurations(otherWorkspaceId).isEmpty())

        val replacement = initial.copy(
            definition = timeSpineDefinition(SemanticTimeGranularity.MONTH),
        )
        repository.upsertTimeSpineConfiguration(replacement)

        assertEquals(listOf(replacement), repository.findTimeSpineConfigurations(workspaceId))
        val raw = rawDefinition(
            table = "semantic.time_spine_configurations",
            idColumn = "connector_id",
            id = connectorId.value,
        )
        assertEquals(1, raw["schemaVersion"].intValue())
        assertEquals(1, raw["timeSpines"].size())
        assertTrue(raw.fieldNames().asSequence().toSet() == setOf("schemaVersion", "timeSpines"))
    }

    private val actor = PrincipalId.generate()
    private val organization = OrganizationId.generate()

    private fun authorization(workspaceId: WorkspaceId, intent: MutationIntent): AuthorizedMutationContext = AuthorizedMutationContext(
        OrganizationPlan.FREE, CanonicalPrincipal(actor, PrincipalKind.USER), organization, workspaceId, AuthorizationRevision(1),
        CredentialKind.SESSION, CredentialSurface.WEB, null, WorkspaceRole.EDITOR, intent, instant(0))

    private fun transactionPort(): SemanticTransactionPort {
        val manager = DataSourceTransactionManager(dataSource)
        val read = TransactionTemplate(manager).apply { isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ; timeout = 10 }
        val write = TransactionTemplate(manager).apply { isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED; timeout = 10 }
        return SpringSemanticTransactionPort(dataSource, read, write, Dispatchers.Unconfined,
            SemanticTransactionTimeouts(Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(2)))
    }

    private fun searchProjection(): SemanticSearchProjection = object : SemanticSearchProjection {
        override fun upsertModel(fields: SemanticModelSearchFields) = upsert(fields.workspaceId, "SEMANTIC_MODEL", fields.name)
        override fun upsertMetric(fields: MetricSearchFields) = upsert(fields.workspaceId, "METRIC", fields.name)
        override fun deleteModel(workspaceId: WorkspaceId, name: String) = delete(workspaceId, "SEMANTIC_MODEL", name)
        override fun deleteMetric(workspaceId: WorkspaceId, name: String) = delete(workspaceId, "METRIC", name)
        private fun upsert(workspaceId: WorkspaceId, kind: String, name: String) {
            DataSourceUtils.getConnection(dataSource).prepareStatement("INSERT INTO semantic.test_search_documents (workspace_id, kind, name) VALUES (?, ?, ?) ON CONFLICT DO NOTHING").use { statement ->
                statement.setObject(1, workspaceId.value.toJavaUuid()); statement.setString(2, kind); statement.setString(3, name); statement.executeUpdate()
            }
        }
        private fun delete(workspaceId: WorkspaceId, kind: String, name: String) {
            DataSourceUtils.getConnection(dataSource).prepareStatement("DELETE FROM semantic.test_search_documents WHERE workspace_id = ? AND kind = ? AND name = ?").use { statement ->
                statement.setObject(1, workspaceId.value.toJavaUuid()); statement.setString(2, kind); statement.setString(3, name); statement.executeUpdate()
            }
        }
    }

    private fun searchDocumentCount(workspaceId: WorkspaceId): Int = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT count(*) FROM semantic.test_search_documents WHERE workspace_id = ?").use { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            statement.executeQuery().use { result -> check(result.next()); result.getInt(1) }
        }
    }

    private fun assertForbiddenDefinitionFields(node: JsonNode) {
        assertEquals(3, node["schemaVersion"].intValue())
        listOf(
            "version",
            "publication",
            "definitionDigest",
            "graphDigest",
            "catalogSnapshot",
            "artifactVersion",
            "semanticRevision",
            "pin",
            "history",
        ).forEach { field ->
            assertTrue(!node.has(field), "definition must not persist forbidden field $field")
        }
    }

    private fun rawDefinition(table: String, idColumn: String, id: Uuid): JsonNode =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT definition FROM $table WHERE $idColumn = ?",
            ).use { statement ->
                statement.setObject(1, id.toJavaUuid())
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    objectMapper.readTree(resultSet.getString("definition"))
                }
            }
        }

    private fun modelDefinition(
        name: String = "orders_model",
        label: String? = "Orders",
        measureExpression: String = "amount",
    ): cc.monomer.semantic.api.SemanticModelDefinition =
        cc.monomer.semantic.api.SemanticModelDefinition(
            schemaVersion = 3u,
            name = SemanticElementName(name),
            label = label,
            description = "Current model",
            sourceRelations = listOf(
                SemanticSourceRelation(
                    connectorId = ConnectorId(Uuid.generateV7()),
                    catalog = "warehouse",
                    schema = "analytics",
                    relation = "orders",
                ),
            ),
            aggregationTimeDimension = SemanticElementName("occurred_at"),
            entities = listOf(
                SemanticEntityDefinition(
                    name = SemanticElementName("order_id"),
                    type = SemanticEntityKind.PRIMARY,
                    expression = "order_id",
                    label = "Order ID",
                    description = null,
                ),
            ),
            dimensions = listOf(
                SemanticDimensionDefinition(
                    name = SemanticElementName("status"),
                    type = SemanticDimensionKind.CATEGORICAL,
                    expression = "status",
                    granularity = null,
                    label = null,
                    description = null,
                    isPartition = false,
                    timeKind = null,
                ),
                SemanticDimensionDefinition(
                    name = SemanticElementName("occurred_at"),
                    type = SemanticDimensionKind.TIME,
                    expression = "occurred_at",
                    granularity = SemanticTimeGranularity.DAY,
                    label = "Occurred At",
                    description = null,
                    isPartition = false,
                    timeKind = null,
                ),
            ),
            measures = listOf(
                SemanticMeasureDefinition(
                    name = SemanticElementName("revenue"),
                    aggregation = SemanticMeasureAggregation.SUM,
                    expression = measureExpression,
                    aggregationTimeDimension = null,
                    nonAdditiveDimension = null,
                    label = "Revenue",
                    description = null,
                ),
            ),
        )

    private fun simpleMetric(name: String): SemanticMetricDefinition.Simple =
        SemanticMetricDefinition.Simple(
            schemaVersion = 3u,
            name = SemanticElementName(name),
            label = "Simple",
            description = "Simple metric",
            timeGranularity = SemanticTimeGranularity.DAY,
            filters = emptyList(),
            semanticModelName = SemanticElementName("orders_model_not_persisted"),
            measure = SemanticElementName("revenue"),
            display = null,
        )

    private fun ratioMetric(name: String): SemanticMetricDefinition.Ratio =
        commonMetricFields(name).let { fields ->
        SemanticMetricDefinition.Ratio(
            schemaVersion = fields.schemaVersion,
            name = fields.name,
            label = fields.label,
            description = fields.description,
            timeGranularity = fields.timeGranularity,
            filters = fields.filters,
            numerator = input("revenue_metric"),
            denominator = input("orders_metric"),
            display = null,
        )
        }

    private fun derivedMetric(name: String): SemanticMetricDefinition.Derived =
        commonMetricFields(name).let { fields ->
        SemanticMetricDefinition.Derived(
            schemaVersion = fields.schemaVersion,
            name = fields.name,
            label = fields.label,
            description = fields.description,
            timeGranularity = fields.timeGranularity,
            filters = fields.filters,
            expression = "revenue_metric / orders_metric",
            inputMetrics = listOf(input("revenue_metric"), input("orders_metric")),
            display = null,
        )
        }

    private fun cumulativeMetric(name: String): SemanticMetricDefinition.Cumulative =
        commonMetricFields(name).let { fields ->
        SemanticMetricDefinition.Cumulative(
            schemaVersion = fields.schemaVersion,
            name = fields.name,
            label = fields.label,
            description = fields.description,
            timeGranularity = fields.timeGranularity,
            filters = fields.filters,
            inputMetric = input("revenue_metric"),
            window = SemanticMetricWindow(7, SemanticMetricWindowUnit.DAY),
            grainToDate = null,
            periodAggregation = SemanticMetricPeriodAggregation.LAST,
            display = null,
        )
        }

    private fun conversionMetric(name: String): SemanticMetricDefinition.Conversion =
        commonMetricFields(name).let { fields ->
        SemanticMetricDefinition.Conversion(
            schemaVersion = fields.schemaVersion,
            name = fields.name,
            label = fields.label,
            description = fields.description,
            timeGranularity = fields.timeGranularity,
            filters = fields.filters,
            entity = SemanticElementName("user_id"),
            calculation = SemanticConversionCalculation.CONVERSION_RATE,
            baseMetric = input("signup_metric"),
            conversionMetric = input("purchase_metric"),
            window = SemanticMetricWindow(30, SemanticMetricWindowUnit.DAY),
            constantProperties = listOf(
                SemanticConstantProperty(
                    baseProperty = SemanticElementName("plan"),
                    conversionProperty = SemanticElementName("plan"),
                ),
            ),
            display = null,
        )
        }

    private fun commonMetricFields(name: String): MetricFields =
        MetricFields(
            schemaVersion = 3u,
            name = SemanticElementName(name),
            label = "Metric",
            description = "Metric definition",
            timeGranularity = SemanticTimeGranularity.DAY,
            filters = emptyList(),
        )

    private data class MetricFields(
        val schemaVersion: UInt,
        val name: SemanticElementName,
        val label: String?,
        val description: String?,
        val timeGranularity: SemanticTimeGranularity?,
        val filters: List<cc.monomer.semantic.api.SemanticMetricFilter>,
    )

    private fun input(name: String) =
        SemanticMetricInput(
            metricName = SemanticElementName(name),
            filter = null,
            alias = null,
            offsetWindow = null,
            offsetToGrain = null,
        )

    private fun instant(seconds: Long): Instant = Instant.parse("2026-08-19T00:00:00Z").plusSeconds(seconds)

    private fun timeSpineDefinition(vararg granularities: SemanticTimeGranularity): SemanticTimeSpineDefinition =
        SemanticTimeSpineDefinition(
            schemaVersion = 1u,
            timeSpines = granularities.map { granularity ->
                SemanticTimeSpine(
                    relation = SemanticTimeSpineRelation(
                        catalog = "warehouse",
                        schema = "analytics",
                        relation = "time_spine_${granularity.name.lowercase()}",
                    ),
                    primaryColumn = SemanticTimeSpinePrimaryColumn(
                        name = "date_utc",
                        granularity = granularity,
                    ),
                )
            },
        )
}
