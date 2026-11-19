@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.semantic.adapter.outbound.postgres

import cc.monomer.semantic.api.*
import cc.monomer.semantic.api.SemanticDefinitionChange
import cc.monomer.semantic.api.SemanticSearchInventory
import cc.monomer.semantic.api.SemanticSearchReadPort
import cc.monomer.semantic.api.toSearchFields
import cc.monomer.semantic.application.toView
import org.springframework.transaction.support.TransactionSynchronizationManager
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.MetricListBoundary
import cc.monomer.semantic.api.MetricPageCursor
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticModelId
import cc.monomer.semantic.api.SemanticModelListBoundary
import cc.monomer.semantic.api.SemanticModelPageCursor
import cc.monomer.semantic.application.SemanticRepository
import cc.monomer.semantic.application.MetricSearchRow
import cc.monomer.semantic.application.SemanticModelSearchRow
import cc.monomer.semantic.application.SemanticTimeSpineRepository
import cc.monomer.semantic.domain.CurrentMetric
import cc.monomer.semantic.domain.CurrentSemanticModel
import cc.monomer.semantic.domain.CurrentTimeSpineConfiguration
import cc.monomer.semantic.domain.SemanticMutationKind
import cc.monomer.semantic.domain.SemanticMutationReceipt
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid
import org.springframework.jdbc.datasource.DataSourceUtils

/** PostgreSQL authority for current Semantic Model/Metric rows. */
class PostgresSemanticRepository(
    private val dataSource: DataSource,
    objectMapper: ObjectMapper,
) : SemanticRepository, SemanticTimeSpineRepository, SemanticSearchReadPort {
    private val jsonCodec = SemanticPostgresJsonCodec(objectMapper)

    override fun readRecentModels(
        workspaceId: WorkspaceId, before: Instant?, beforeId: Uuid?, actorId: PrincipalId?, credentialSurface: CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?, limit: Int,
    ): List<SemanticDefinitionChange> = readRecentChanges(SemanticDefinitionKind.SEMANTIC_MODEL, workspaceId, before, beforeId, actorId, credentialSurface, agentClientId, limit)

    override fun readRecentMetrics(
        workspaceId: WorkspaceId, before: Instant?, beforeId: Uuid?, actorId: PrincipalId?, credentialSurface: CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?, limit: Int,
    ): List<SemanticDefinitionChange> = readRecentChanges(SemanticDefinitionKind.METRIC, workspaceId, before, beforeId, actorId, credentialSurface, agentClientId, limit)

    private fun readRecentChanges(kind: SemanticDefinitionKind, workspaceId: WorkspaceId, before: Instant?, beforeId: Uuid?, actorId: PrincipalId?, credentialSurface: CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?, limit: Int): List<SemanticDefinitionChange> = queryMany(
        buildString {
            append(summarySelect(kind))
            append(" WHERE changes.workspace_id = ? AND changes.definition_kind = ?")
            if (actorId != null) append(" AND changes.actor_id = ?")
            if (credentialSurface != null) append(" AND changes.credential_surface = ?")
            if (agentClientId != null) append(" AND changes.agent_client_id = ?")
            if (before != null) append(" AND (changes.occurred_at, changes.change_id) < (?, ?)")
            append(" ORDER BY changes.occurred_at DESC, changes.change_id DESC LIMIT ?")
        },
        { statement ->
            var index = 1
            statement.setObject(index++, workspaceId.value.toJavaUuid())
            statement.setString(index++, kind.name)
            if (actorId != null) statement.setObject(index++, actorId.value.toJavaUuid())
            if (credentialSurface != null) statement.setString(index++, credentialSurface.name)
            if (agentClientId != null) statement.setString(index++, agentClientId.value)
            if (before != null) {
                statement.setObject(index++, before.atOffset(ZoneOffset.UTC))
                statement.setObject(index++, requireNotNull(beforeId).toJavaUuid())
            }
            statement.setInt(index, limit)
        },
        ::definitionChange,
    )

    override fun readRecentAgentClients(workspaceId: WorkspaceId, scanLimit: Int): List<cc.monomer.identity.api.RecentAgentClient> {
        require(scanLimit in 1..cc.monomer.identity.api.RecentAgentClient.MAX_SCAN)
        return queryMany(
            "SELECT agent_client_id, max(occurred_at) AS last_seen_at FROM (" +
                "(SELECT agent_client_id, occurred_at FROM semantic.definition_changes WHERE workspace_id = ? AND definition_kind = 'SEMANTIC_MODEL' ORDER BY occurred_at DESC, change_id DESC LIMIT ?) " +
                "UNION ALL (SELECT agent_client_id, occurred_at FROM semantic.definition_changes WHERE workspace_id = ? AND definition_kind = 'METRIC' ORDER BY occurred_at DESC, change_id DESC LIMIT ?)" +
                ") recent WHERE agent_client_id IS NOT NULL GROUP BY agent_client_id",
            { statement ->
                statement.setObject(1, workspaceId.value.toJavaUuid())
                statement.setInt(2, scanLimit)
                statement.setObject(3, workspaceId.value.toJavaUuid())
                statement.setInt(4, scanLimit)
            },
        ) { row -> cc.monomer.identity.api.RecentAgentClient(cc.monomer.identity.api.AgentClientId(row.getString("agent_client_id")), row.instant("last_seen_at")) }
    }

    override fun readModelChange(workspaceId: WorkspaceId, semanticModelId: SemanticModelId): SemanticDefinitionChange? =
        readCurrentChange(SemanticDefinitionKind.SEMANTIC_MODEL, workspaceId, semanticModelId.value)

    override fun readMetricChange(workspaceId: WorkspaceId, metricId: MetricId): SemanticDefinitionChange? =
        readCurrentChange(SemanticDefinitionKind.METRIC, workspaceId, metricId.value)

    private fun readCurrentChange(kind: SemanticDefinitionKind, workspaceId: WorkspaceId, definitionId: Uuid): SemanticDefinitionChange? = queryOne(
        summarySelect(kind) + " WHERE changes.workspace_id = ? AND changes.definition_kind = ? AND changes.definition_id = ? AND current.workspace_id IS NOT NULL ORDER BY changes.occurred_at DESC, changes.change_id DESC LIMIT 1",
        { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            statement.setString(2, kind.name)
            statement.setObject(3, definitionId.toJavaUuid())
        }, ::definitionChange,
    )

    private fun summarySelect(kind: SemanticDefinitionKind): String {
        val (table, idColumn) = if (kind == SemanticDefinitionKind.SEMANTIC_MODEL) "semantic_models" to "semantic_model_id" else "metrics" to "metric_id"
        return "SELECT changes.change_id, changes.action, changes.definition_id AS id, changes.definition_name AS name, changes.definition ->> 'label' AS label, " +
            "coalesce(current.created_at, changes.occurred_at) AS created_at, changes.occurred_at AS updated_at, changes.actor_id, changes.credential_surface, changes.agent_client_id, " +
            "(current.workspace_id IS NULL) AS currently_deleted FROM semantic.definition_changes changes " +
            "LEFT JOIN semantic.$table current ON current.workspace_id = changes.workspace_id AND current.$idColumn = changes.definition_id"
    }

    private fun definitionChange(row: ResultSet): SemanticDefinitionChange = SemanticDefinitionChange(
        changeId = row.uuid("change_id"), action = SemanticDefinitionChangeAction.valueOf(row.getString("action")),
        currentlyDeleted = row.getBoolean("currently_deleted"), id = row.uuid("id"), name = row.getString("name"), label = row.getString("label"),
        createdAt = row.instant("created_at"), updatedAt = row.instant("updated_at"),
        actorId = row.getObject("actor_id", java.util.UUID::class.java)?.let { PrincipalId(it.toKotlinUuid()) },
        credentialSurface = row.getString("credential_surface")?.let(CredentialSurface::valueOf),
        agentClientId = row.getString("agent_client_id")?.let { cc.monomer.identity.api.AgentClientId(it) },
    )

    override fun appendDefinitionChange(workspaceId: WorkspaceId, definitionId: Uuid, version: SemanticDefinitionVersion) {
        val (kind, name, definitionJson) = when (val snapshot = version.definition) {
            is SemanticDefinitionSnapshot.Model -> Triple(SemanticDefinitionKind.SEMANTIC_MODEL, snapshot.definition.name.value, jsonCodec.modelDefinitionJson(snapshot.definition))
            is SemanticDefinitionSnapshot.Metric -> Triple(SemanticDefinitionKind.METRIC, snapshot.definition.name.value, jsonCodec.metricDefinitionJson(snapshot.definition))
        }
        update("INSERT INTO semantic.definition_changes (change_id, workspace_id, definition_kind, definition_id, definition_name, action, definition, actor_id, credential_surface, agent_client_id, occurred_at) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?, ?, ?)") { statement ->
            statement.setObject(1, version.changeId.toJavaUuid())
            statement.setObject(2, workspaceId.value.toJavaUuid())
            statement.setString(3, kind.name)
            statement.setObject(4, definitionId.toJavaUuid())
            statement.setString(5, name)
            statement.setString(6, version.action.name)
            statement.setString(7, definitionJson)
            statement.setObject(8, version.actorId?.value?.toJavaUuid())
            statement.setString(9, version.credentialSurface?.name)
            statement.setString(10, version.agentClientId?.value)
            statement.setObject(11, version.occurredAt.atOffset(ZoneOffset.UTC))
        }
    }

    override fun findDefinitionVersion(workspaceId: WorkspaceId, kind: SemanticDefinitionKind, definitionId: Uuid, changeId: Uuid): SemanticDefinitionVersion? = queryOne(
        "SELECT * FROM semantic.definition_changes WHERE workspace_id = ? AND definition_kind = ? AND definition_id = ? AND change_id = ?",
        { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            statement.setString(2, kind.name)
            statement.setObject(3, definitionId.toJavaUuid())
            statement.setObject(4, changeId.toJavaUuid())
        }, ::definitionVersion,
    )

    override fun readDefinitionVersions(workspaceId: WorkspaceId, kind: SemanticDefinitionKind, definitionId: Uuid, before: SemanticHistoryBoundary?, limit: Int): List<SemanticDefinitionVersion> = queryMany(
        "SELECT * FROM semantic.definition_changes WHERE workspace_id = ? AND definition_kind = ? AND definition_id = ?" +
            (if (before == null) "" else " AND (occurred_at, change_id) < (?, ?)") + " ORDER BY occurred_at DESC, change_id DESC LIMIT ?",
        { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            statement.setString(2, kind.name)
            statement.setObject(3, definitionId.toJavaUuid())
            var index = 4
            if (before != null) {
                statement.setObject(index++, before.occurredAt.atOffset(ZoneOffset.UTC))
                statement.setObject(index++, before.changeId.toJavaUuid())
            }
            statement.setInt(index, limit)
        }, ::definitionVersion,
    )

    private fun definitionVersion(row: ResultSet): SemanticDefinitionVersion = SemanticDefinitionVersion(
        changeId = row.uuid("change_id"), action = SemanticDefinitionChangeAction.valueOf(row.getString("action")),
        definition = when (SemanticDefinitionKind.valueOf(row.getString("definition_kind"))) {
            SemanticDefinitionKind.SEMANTIC_MODEL -> SemanticDefinitionSnapshot.Model(jsonCodec.parseModelDefinition(row.getString("definition")))
            SemanticDefinitionKind.METRIC -> SemanticDefinitionSnapshot.Metric(jsonCodec.parseMetricDefinition(row.getString("definition")))
        }, occurredAt = row.instant("occurred_at"),
        actorId = row.getObject("actor_id", java.util.UUID::class.java)?.let { PrincipalId(it.toKotlinUuid()) },
        credentialSurface = row.getString("credential_surface")?.let(CredentialSurface::valueOf),
        agentClientId = row.getString("agent_client_id")?.let { cc.monomer.identity.api.AgentClientId(it) },
        restorable = false,
    )

    override fun deleteModel(workspaceId: WorkspaceId, semanticModelId: SemanticModelId) {
        update("DELETE FROM semantic.semantic_models WHERE workspace_id = ? AND semantic_model_id = ?") { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            statement.setObject(2, semanticModelId.value.toJavaUuid())
        }
    }

    override fun deleteMetric(workspaceId: WorkspaceId, metricId: MetricId) {
        update("DELETE FROM semantic.metrics WHERE workspace_id = ? AND metric_id = ?") { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            statement.setObject(2, metricId.value.toJavaUuid())
        }
    }

    override fun inventory(): SemanticSearchInventory {
        check(TransactionSynchronizationManager.isActualTransactionActive())
        return SemanticSearchInventory(
            models = queryMany(
                "SELECT semantic_model_id, workspace_id, definition, created_at, updated_at FROM semantic.semantic_models",
                {}, ::mapModel,
            ).map { it.toView().toSearchFields() },
            metrics = queryMany(
                "SELECT metric_id, workspace_id, definition, created_at, updated_at FROM semantic.metrics",
                {}, ::mapMetric,
            ).map { it.toView().toSearchFields() },
        )
    }

    override fun findModel(id: SemanticModelId): CurrentSemanticModel? =
        queryOne(
            """
            SELECT semantic_model_id, workspace_id, definition, created_at, updated_at
            FROM semantic.semantic_models
            WHERE semantic_model_id = ?
            """.trimIndent(),
            { it.setObject(1, id.value.toJavaUuid()) },
            ::mapModel,
        )

    override fun findModelByName(workspaceId: WorkspaceId, name: SemanticElementName): CurrentSemanticModel? =
        queryOne(
            """
            SELECT semantic_model_id, workspace_id, definition, created_at, updated_at
            FROM semantic.semantic_models
            WHERE workspace_id = ? AND definition ->> 'name' = ?
            """.trimIndent(),
            {
                it.setObject(1, workspaceId.value.toJavaUuid())
                it.setString(2, name.value)
            },
            ::mapModel,
        )

    override fun findModels(workspaceId: WorkspaceId): List<CurrentSemanticModel> =
        queryMany(
            """
            SELECT semantic_model_id, workspace_id, definition, created_at, updated_at
            FROM semantic.semantic_models
            WHERE workspace_id = ?
            ORDER BY definition ->> 'name'
            """.trimIndent(),
            { it.setObject(1, workspaceId.value.toJavaUuid()) },
            ::mapModel,
        )

    override fun listModels(
        workspaceId: WorkspaceId,
        after: SemanticModelListBoundary?,
        limit: Int,
    ): List<CurrentSemanticModel> = queryMany(
        if (after == null) LIST_MODELS_SQL else LIST_MODELS_AFTER_SQL,
        { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            var parameterIndex = 2
            if (after != null) {
                statement.setString(parameterIndex++, after.name.value)
            }
            statement.setInt(parameterIndex, limit)
        },
        ::mapModel,
    )

    override fun searchModels(
        workspaceId: WorkspaceId,
        query: String,
        cursor: SemanticModelPageCursor?,
        limit: Int,
    ): List<SemanticModelSearchRow> = queryMany(
        if (cursor == null) SEARCH_MODELS_SQL else SEARCH_MODELS_AFTER_SQL,
        { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            statement.setString(2, query)
            var parameterIndex = 3
            if (cursor != null) {
                statement.setString(parameterIndex++, cursor.normalizedLabel)
                statement.setString(parameterIndex++, cursor.normalizedName)
                statement.setObject(parameterIndex++, cursor.semanticModelId.value.toJavaUuid())
            }
            statement.setInt(parameterIndex, limit)
        },
    ) { resultSet ->
        SemanticModelSearchRow(
            model = mapModel(resultSet),
            normalizedLabel = resultSet.getString("normalized_label"),
            normalizedName = resultSet.getString("normalized_name"),
        )
    }

    override fun findLatestModel(workspaceId: WorkspaceId): CurrentSemanticModel? =
        queryOne(
            """
            SELECT semantic_model_id, workspace_id, definition, created_at, updated_at
            FROM semantic.semantic_models
            WHERE workspace_id = ?
            ORDER BY updated_at DESC, semantic_model_id DESC
            LIMIT 1
            """.trimIndent(),
            { it.setObject(1, workspaceId.value.toJavaUuid()) },
            ::mapModel,
        )

    override fun findMetric(id: MetricId): CurrentMetric? =
        queryOne(
            """
            SELECT metric_id, workspace_id, definition, created_at, updated_at
            FROM semantic.metrics
            WHERE metric_id = ?
            """.trimIndent(),
            { it.setObject(1, id.value.toJavaUuid()) },
            ::mapMetric,
        )

    override fun findMetricByName(workspaceId: WorkspaceId, name: SemanticElementName): CurrentMetric? =
        queryOne(
            """
            SELECT metric_id, workspace_id, definition, created_at, updated_at
            FROM semantic.metrics
            WHERE workspace_id = ? AND definition ->> 'name' = ?
            """.trimIndent(),
            {
                it.setObject(1, workspaceId.value.toJavaUuid())
                it.setString(2, name.value)
            },
            ::mapMetric,
        )

    override fun findMetrics(workspaceId: WorkspaceId): List<CurrentMetric> =
        queryMany(
            """
            SELECT metric_id, workspace_id, definition, created_at, updated_at
            FROM semantic.metrics
            WHERE workspace_id = ?
            ORDER BY definition ->> 'name'
            """.trimIndent(),
            { it.setObject(1, workspaceId.value.toJavaUuid()) },
            ::mapMetric,
        )

    override fun listMetrics(
        workspaceId: WorkspaceId,
        after: MetricListBoundary?,
        limit: Int,
    ): List<CurrentMetric> = queryMany(
        if (after == null) LIST_METRICS_SQL else LIST_METRICS_AFTER_SQL,
        { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            var parameterIndex = 2
            if (after != null) {
                statement.setString(parameterIndex++, after.name.value)
            }
            statement.setInt(parameterIndex, limit)
        },
        ::mapMetric,
    )

    override fun searchMetrics(
        workspaceId: WorkspaceId,
        query: String,
        cursor: MetricPageCursor?,
        limit: Int,
    ): List<MetricSearchRow> = queryMany(
        if (cursor == null) SEARCH_METRICS_SQL else SEARCH_METRICS_AFTER_SQL,
        { statement ->
            statement.setObject(1, workspaceId.value.toJavaUuid())
            statement.setString(2, query)
            var parameterIndex = 3
            if (cursor != null) {
                statement.setString(parameterIndex++, cursor.normalizedLabel)
                statement.setString(parameterIndex++, cursor.normalizedName)
                statement.setObject(parameterIndex++, cursor.metricId.value.toJavaUuid())
            }
            statement.setInt(parameterIndex, limit)
        },
    ) { resultSet ->
        MetricSearchRow(
            metric = mapMetric(resultSet),
            normalizedLabel = resultSet.getString("normalized_label"),
            normalizedName = resultSet.getString("normalized_name"),
        )
    }

    override fun findLatestMetric(workspaceId: WorkspaceId): CurrentMetric? =
        queryOne(
            """
            SELECT metric_id, workspace_id, definition, created_at, updated_at
            FROM semantic.metrics
            WHERE workspace_id = ?
            ORDER BY updated_at DESC, metric_id DESC
            LIMIT 1
            """.trimIndent(),
            { it.setObject(1, workspaceId.value.toJavaUuid()) },
            ::mapMetric,
        )

    override fun findTimeSpineConfigurations(workspaceId: WorkspaceId): List<CurrentTimeSpineConfiguration> =
        queryMany(
            """
            SELECT workspace_id, connector_id, definition
            FROM semantic.time_spine_configurations
            WHERE workspace_id = ?
            ORDER BY connector_id
            """.trimIndent(),
            { it.setObject(1, workspaceId.value.toJavaUuid()) },
            ::mapTimeSpineConfiguration,
        )

    override fun lockWorkspaceMutation(workspaceId: WorkspaceId) {
        connection().prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use { statement ->
            statement.setString(1, "semantic:workspace:$workspaceId")
            statement.execute()
        }
    }

    override fun findMutationReceipt(
        workspaceId: WorkspaceId,
        idempotencyKey: String,
    ): SemanticMutationReceipt? =
        queryOne(
            """
            SELECT workspace_id, idempotency_key, actor_id, mutation_kind, mutation_subject,
                   response_identity, committed_at
            FROM semantic.mutation_receipts
            WHERE workspace_id = ? AND idempotency_key = ?
            """.trimIndent(),
            {
                it.setObject(1, workspaceId.value.toJavaUuid())
                it.setString(2, idempotencyKey)
            },
        ) { resultSet ->
            SemanticMutationReceipt(
                workspaceId = WorkspaceId(resultSet.uuid("workspace_id")),
                idempotencyKey = resultSet.getString("idempotency_key"),
                actorId = PrincipalId(resultSet.uuid("actor_id")),
                mutationKind = SemanticMutationKind.valueOf(resultSet.getString("mutation_kind")),
                subject = resultSet.getString("mutation_subject"),
                responseIdentity = resultSet.uuid("response_identity"),
                committedAt = resultSet.instant("committed_at"),
            )
        }

    override fun insertMutationReceipt(receipt: SemanticMutationReceipt) {
        update(
            """
            INSERT INTO semantic.mutation_receipts (
                workspace_id, idempotency_key, actor_id, mutation_kind, mutation_subject,
                response_identity, committed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, receipt.workspaceId.value.toJavaUuid())
            statement.setString(2, receipt.idempotencyKey)
            statement.setObject(3, receipt.actorId.value.toJavaUuid())
            statement.setString(4, receipt.mutationKind.name)
            statement.setString(5, receipt.subject)
            statement.setObject(6, receipt.responseIdentity.toJavaUuid())
            statement.setObject(7, receipt.committedAt.atOffset(ZoneOffset.UTC))
        }
    }

    override fun insertModel(model: CurrentSemanticModel, actorId: PrincipalId?, credentialSurface: CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?) {
        update(
            """
            INSERT INTO semantic.semantic_models (
                semantic_model_id, workspace_id, definition, created_at, updated_at, actor_id, credential_surface, agent_client_id
            ) VALUES (?, ?, CAST(? AS JSONB), ?, ?, ?, ?, ?)
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, model.id.value.toJavaUuid())
            statement.setObject(2, model.workspaceId.value.toJavaUuid())
            statement.setString(3, jsonCodec.modelDefinitionJson(model.definition))
            statement.setObject(4, model.createdAt.atOffset(ZoneOffset.UTC))
            statement.setObject(5, model.updatedAt.atOffset(ZoneOffset.UTC))
            statement.setObject(6, actorId?.value?.toJavaUuid())
            statement.setString(7, credentialSurface?.name)
            statement.setString(8, agentClientId?.value)
        }
    }

    override fun updateModel(model: CurrentSemanticModel, actorId: PrincipalId?, credentialSurface: CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?) {
        update(
            """
            UPDATE semantic.semantic_models
            SET definition = CAST(? AS JSONB), updated_at = ?, actor_id = ?, credential_surface = ?, agent_client_id = ?
            WHERE semantic_model_id = ? AND workspace_id = ?
            """.trimIndent(),
        ) { statement ->
            statement.setString(1, jsonCodec.modelDefinitionJson(model.definition))
            statement.setObject(2, model.updatedAt.atOffset(ZoneOffset.UTC))
            statement.setObject(3, actorId?.value?.toJavaUuid())
            statement.setString(4, credentialSurface?.name)
            statement.setString(5, agentClientId?.value)
            statement.setObject(6, model.id.value.toJavaUuid())
            statement.setObject(7, model.workspaceId.value.toJavaUuid())
        }
    }

    override fun insertMetric(metric: CurrentMetric, actorId: PrincipalId?, credentialSurface: CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?) {
        update(
            """
            INSERT INTO semantic.metrics (
                metric_id, workspace_id, definition, created_at, updated_at, actor_id, credential_surface, agent_client_id
            ) VALUES (?, ?, CAST(? AS JSONB), ?, ?, ?, ?, ?)
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, metric.id.value.toJavaUuid())
            statement.setObject(2, metric.workspaceId.value.toJavaUuid())
            statement.setString(3, jsonCodec.metricDefinitionJson(metric.definition))
            statement.setObject(4, metric.createdAt.atOffset(ZoneOffset.UTC))
            statement.setObject(5, metric.updatedAt.atOffset(ZoneOffset.UTC))
            statement.setObject(6, actorId?.value?.toJavaUuid())
            statement.setString(7, credentialSurface?.name)
            statement.setString(8, agentClientId?.value)
        }
    }

    override fun updateMetric(metric: CurrentMetric, actorId: PrincipalId?, credentialSurface: CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?) {
        update(
            """
            UPDATE semantic.metrics
            SET definition = CAST(? AS JSONB), updated_at = ?, actor_id = ?, credential_surface = ?, agent_client_id = ?
            WHERE metric_id = ? AND workspace_id = ?
            """.trimIndent(),
        ) { statement ->
            statement.setString(1, jsonCodec.metricDefinitionJson(metric.definition))
            statement.setObject(2, metric.updatedAt.atOffset(ZoneOffset.UTC))
            statement.setObject(3, actorId?.value?.toJavaUuid())
            statement.setString(4, credentialSurface?.name)
            statement.setString(5, agentClientId?.value)
            statement.setObject(6, metric.id.value.toJavaUuid())
            statement.setObject(7, metric.workspaceId.value.toJavaUuid())
        }
    }

    override fun upsertTimeSpineConfiguration(configuration: CurrentTimeSpineConfiguration) {
        update(
            """
            INSERT INTO semantic.time_spine_configurations (workspace_id, connector_id, definition)
            VALUES (?, ?, CAST(? AS JSONB))
            ON CONFLICT (workspace_id, connector_id)
            DO UPDATE SET definition = EXCLUDED.definition
            """.trimIndent(),
        ) { statement ->
            statement.setObject(1, configuration.workspaceId.value.toJavaUuid())
            statement.setObject(2, configuration.connectorId.value.toJavaUuid())
            statement.setString(3, jsonCodec.timeSpineDefinitionJson(configuration.definition))
        }
    }

    private fun mapModel(resultSet: ResultSet): CurrentSemanticModel =
        CurrentSemanticModel(
            id = SemanticModelId(resultSet.uuid("semantic_model_id")),
            workspaceId = WorkspaceId(resultSet.uuid("workspace_id")),
            definition = jsonCodec.parseModelDefinition(resultSet.getString("definition")),
            createdAt = resultSet.instant("created_at"),
            updatedAt = resultSet.instant("updated_at"),
        )

    private fun mapMetric(resultSet: ResultSet): CurrentMetric =
        CurrentMetric(
            id = MetricId(resultSet.uuid("metric_id")),
            workspaceId = WorkspaceId(resultSet.uuid("workspace_id")),
            definition = jsonCodec.parseMetricDefinition(resultSet.getString("definition")),
            createdAt = resultSet.instant("created_at"),
            updatedAt = resultSet.instant("updated_at"),
        )

    private fun mapTimeSpineConfiguration(resultSet: ResultSet): CurrentTimeSpineConfiguration =
        CurrentTimeSpineConfiguration(
            workspaceId = WorkspaceId(resultSet.uuid("workspace_id")),
            connectorId = cc.monomer.connector.api.ConnectorId(resultSet.uuid("connector_id")),
            definition = jsonCodec.parseTimeSpineDefinition(resultSet.getString("definition")),
        )

    private fun connection(): Connection = DataSourceUtils.getConnection(dataSource)

    private fun update(sql: String, bind: (java.sql.PreparedStatement) -> Unit) {
        connection().prepareStatement(sql).use { statement ->
            bind(statement)
            statement.executeUpdate()
        }
    }

    private fun <T> queryOne(
        sql: String,
        bind: (java.sql.PreparedStatement) -> Unit,
        map: (ResultSet) -> T,
    ): T? = queryMany(sql, bind, map).firstOrNull()

    private fun <T> queryMany(
        sql: String,
        bind: (java.sql.PreparedStatement) -> Unit,
        map: (ResultSet) -> T,
    ): List<T> {
        connection().prepareStatement(sql).use { statement ->
            bind(statement)
            statement.executeQuery().use { resultSet ->
                return buildList {
                    while (resultSet.next()) add(map(resultSet))
                }
            }
        }
    }
}

private const val LIST_MODELS_SQL = """
    SELECT semantic_model_id, workspace_id, definition, created_at, updated_at
      FROM semantic.semantic_models
     WHERE workspace_id = ?
     ORDER BY definition ->> 'name'
     LIMIT ?
"""

private const val LIST_MODELS_AFTER_SQL = """
    SELECT semantic_model_id, workspace_id, definition, created_at, updated_at
      FROM semantic.semantic_models
     WHERE workspace_id = ?
       AND definition ->> 'name' > ?
     ORDER BY definition ->> 'name'
     LIMIT ?
"""

private const val LIST_METRICS_SQL = """
    SELECT metric_id, workspace_id, definition, created_at, updated_at
      FROM semantic.metrics
     WHERE workspace_id = ?
     ORDER BY definition ->> 'name'
     LIMIT ?
"""

private const val LIST_METRICS_AFTER_SQL = """
    SELECT metric_id, workspace_id, definition, created_at, updated_at
      FROM semantic.metrics
     WHERE workspace_id = ?
       AND definition ->> 'name' > ?
     ORDER BY definition ->> 'name'
     LIMIT ?
"""

private const val SEARCH_MODELS_SQL = """
    SELECT semantic_model_id, workspace_id, definition, created_at, updated_at,
           lower(coalesce(definition ->> 'label', definition ->> 'name')) AS normalized_label,
           lower(definition ->> 'name') AS normalized_name
      FROM semantic.semantic_models
     WHERE workspace_id = ?
       AND strpos(lower(coalesce(definition ->> 'label', '') || ' ' || (definition ->> 'name')), ?) > 0
     ORDER BY lower(coalesce(definition ->> 'label', definition ->> 'name')),
              lower(definition ->> 'name'), semantic_model_id
     LIMIT ?
"""

private const val SEARCH_MODELS_AFTER_SQL = """
    SELECT semantic_model_id, workspace_id, definition, created_at, updated_at,
           lower(coalesce(definition ->> 'label', definition ->> 'name')) AS normalized_label,
           lower(definition ->> 'name') AS normalized_name
      FROM semantic.semantic_models
     WHERE workspace_id = ?
       AND strpos(lower(coalesce(definition ->> 'label', '') || ' ' || (definition ->> 'name')), ?) > 0
       AND (lower(coalesce(definition ->> 'label', definition ->> 'name')),
            lower(definition ->> 'name'), semantic_model_id) > (?, ?, ?)
     ORDER BY lower(coalesce(definition ->> 'label', definition ->> 'name')),
              lower(definition ->> 'name'), semantic_model_id
     LIMIT ?
"""

private const val SEARCH_METRICS_SQL = """
    SELECT metric_id, workspace_id, definition, created_at, updated_at,
           lower(coalesce(definition ->> 'label', definition ->> 'name')) AS normalized_label,
           lower(definition ->> 'name') AS normalized_name
      FROM semantic.metrics
     WHERE workspace_id = ?
       AND strpos(lower(coalesce(definition ->> 'label', '') || ' ' || (definition ->> 'name')), ?) > 0
     ORDER BY lower(coalesce(definition ->> 'label', definition ->> 'name')),
              lower(definition ->> 'name'), metric_id
     LIMIT ?
"""

private const val SEARCH_METRICS_AFTER_SQL = """
    SELECT metric_id, workspace_id, definition, created_at, updated_at,
           lower(coalesce(definition ->> 'label', definition ->> 'name')) AS normalized_label,
           lower(definition ->> 'name') AS normalized_name
      FROM semantic.metrics
     WHERE workspace_id = ?
       AND strpos(lower(coalesce(definition ->> 'label', '') || ' ' || (definition ->> 'name')), ?) > 0
       AND (lower(coalesce(definition ->> 'label', definition ->> 'name')),
            lower(definition ->> 'name'), metric_id) > (?, ?, ?)
     ORDER BY lower(coalesce(definition ->> 'label', definition ->> 'name')),
              lower(definition ->> 'name'), metric_id
     LIMIT ?
"""

private fun ResultSet.uuid(column: String): Uuid =
    getObject(column, java.util.UUID::class.java).toKotlinUuid()

private fun ResultSet.instant(column: String): Instant =
    when (val value = getObject(column)) {
        is OffsetDateTime -> value.toInstant()
        is java.sql.Timestamp -> value.toInstant()
        else -> error("semantic timestamp column " + column + " must be an instant")
    }
