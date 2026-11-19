package cc.monomer.semantic.application

import cc.monomer.connector.api.ConnectorWorkspaceReadUseCase
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.semantic.api.*
import cc.monomer.semantic.api.SemanticDefinitionChange
import java.time.Instant
import kotlin.uuid.Uuid
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.MetricListBoundary
import cc.monomer.semantic.api.MetricListPage
import cc.monomer.semantic.api.MetricPage
import cc.monomer.semantic.api.MetricPageCursor
import cc.monomer.semantic.api.MetricView
import cc.monomer.semantic.api.SemanticCurrentReadUseCase
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticModelId
import cc.monomer.semantic.api.SemanticModelListBoundary
import cc.monomer.semantic.api.SemanticModelListPage
import cc.monomer.semantic.api.SemanticModelPage
import cc.monomer.semantic.api.SemanticModelPageCursor
import cc.monomer.semantic.api.SemanticModelView
import cc.monomer.semantic.api.SemanticTimeSpineConfigurationView

class SemanticCurrentReadService(
    private val repository: SemanticRepository,
    private val timeSpineRepository: SemanticTimeSpineRepository,
    private val transactionPort: SemanticTransactionPort,
    private val connectorReadUseCase: ConnectorWorkspaceReadUseCase,
    private val catalogReadPort: cc.monomer.catalog.api.CatalogCurrentObjectsReadPort,
) : SemanticCurrentReadUseCase {
    private inner class PartitionViewReads(private val workspaceId: WorkspaceId) {
        private val catalogsByConnector = mutableMapOf<cc.monomer.connector.api.ConnectorId, cc.monomer.catalog.api.CatalogCurrentObjectSet?>()
        private val metricsByName by lazy { repository.findMetrics(workspaceId).associateBy { it.definition.name } }
        private val modelsByName by lazy { repository.findModels(workspaceId).associateBy { it.definition.name } }

        private fun catalog(connectorId: cc.monomer.connector.api.ConnectorId): cc.monomer.catalog.api.CatalogCurrentObjectSet? =
            if (connectorId in catalogsByConnector) {
                catalogsByConnector.getValue(connectorId)
            } else {
                catalogReadPort.readCurrent(workspaceId, connectorId).also { catalogsByConnector[connectorId] = it }
            }

        private fun sourcePartitionInfos(definition: SemanticModelDefinition): List<SemanticSourcePartitionInfo> =
            definition.sourceRelations.mapNotNull { source ->
                sourcePartitionInfo(definition, source.connectorId, catalog(source.connectorId))
            }

        fun modelView(model: cc.monomer.semantic.domain.CurrentSemanticModel): SemanticModelView {
            require(model.workspaceId == workspaceId)
            return model.toView().copy(sourcePartitionInfo = sourcePartitionInfos(model.definition))
        }

        fun metricView(metric: cc.monomer.semantic.domain.CurrentMetric): MetricView {
            require(metric.workspaceId == workspaceId)
            val info = when (val closure = collectMetricClosure(listOf(metric.definition.name), metricsByName, modelsByName)) {
                is SemanticMetricClosure.Collected -> closure.models.values.flatMap { model -> sourcePartitionInfos(model.definition) }
                is SemanticMetricClosure.NotFound, is SemanticMetricClosure.Unresolved -> emptyList()
            }
            return metric.toView().copy(sourcePartitionInfo = info)
        }
    }

    private suspend fun currentPartitionConnectorIds(
        workspaceId: WorkspaceId,
        partitionInfo: List<SemanticSourcePartitionInfo>,
    ): Set<cc.monomer.connector.api.ConnectorId> {
        val requestedIds = partitionInfo.map { it.connectorId }.toSet()
        if (requestedIds.isEmpty()) return emptySet()
        return connectorReadUseCase.findByWorkspaceAndIds(workspaceId, requestedIds)
            .filter { it.workspaceId == workspaceId && it.connectorId in requestedIds }
            .map { it.connectorId }.toSet()
    }

    private suspend fun currentModelPartitionViews(
        workspaceId: WorkspaceId,
        models: List<SemanticModelView>,
    ): List<SemanticModelView> {
        val currentIds = currentPartitionConnectorIds(workspaceId, models.flatMap { it.sourcePartitionInfo })
        return models.map { it.copy(sourcePartitionInfo = it.sourcePartitionInfo.filter { fact -> fact.connectorId in currentIds }) }
    }

    private suspend fun currentMetricPartitionViews(
        workspaceId: WorkspaceId,
        metrics: List<MetricView>,
    ): List<MetricView> {
        val currentIds = currentPartitionConnectorIds(workspaceId, metrics.flatMap { it.sourcePartitionInfo })
        return metrics.map { it.copy(sourcePartitionInfo = it.sourcePartitionInfo.filter { fact -> fact.connectorId in currentIds }) }
    }

    override suspend fun readModelVersion(workspaceId: WorkspaceId, semanticModelId: SemanticModelId, changeId: Uuid): SemanticDefinitionVersion? =
        transactionPort.inSnapshotTransaction {
            readVersion(workspaceId, SemanticDefinitionKind.SEMANTIC_MODEL, semanticModelId.value, changeId,
                repository.findModel(semanticModelId)?.takeIf { it.workspaceId == workspaceId } != null)
        }

    override suspend fun readMetricVersion(workspaceId: WorkspaceId, metricId: MetricId, changeId: Uuid): SemanticDefinitionVersion? =
        transactionPort.inSnapshotTransaction {
            readVersion(workspaceId, SemanticDefinitionKind.METRIC, metricId.value, changeId,
                repository.findMetric(metricId)?.takeIf { it.workspaceId == workspaceId } != null)
        }

    private fun readVersion(workspaceId: WorkspaceId, kind: SemanticDefinitionKind, definitionId: Uuid, changeId: Uuid, exists: Boolean): SemanticDefinitionVersion? {
        val version = repository.findDefinitionVersion(workspaceId, kind, definitionId, changeId) ?: return null
        val latest = repository.readDefinitionVersions(workspaceId, kind, definitionId, null, 1).firstOrNull() ?: return null
        return version.withRestoreAvailability(latest, exists)
    }

    override suspend fun readModelHistory(workspaceId: WorkspaceId, semanticModelId: SemanticModelId, before: SemanticHistoryBoundary?, limit: Int): SemanticDefinitionHistory? =
        transactionPort.inSnapshotTransaction {
            readHistory(workspaceId, SemanticDefinitionKind.SEMANTIC_MODEL, semanticModelId.value, before, limit,
                repository.findModel(semanticModelId)?.takeIf { it.workspaceId == workspaceId } != null)
        }

    override suspend fun readMetricHistory(workspaceId: WorkspaceId, metricId: MetricId, before: SemanticHistoryBoundary?, limit: Int): SemanticDefinitionHistory? =
        transactionPort.inSnapshotTransaction {
            readHistory(workspaceId, SemanticDefinitionKind.METRIC, metricId.value, before, limit,
                repository.findMetric(metricId)?.takeIf { it.workspaceId == workspaceId } != null)
        }

    private fun readHistory(workspaceId: WorkspaceId, kind: SemanticDefinitionKind, definitionId: Uuid, before: SemanticHistoryBoundary?, limit: Int, exists: Boolean): SemanticDefinitionHistory? {
        requirePageSize(limit, "Semantic history")
        val latest = repository.readDefinitionVersions(workspaceId, kind, definitionId, null, 1).firstOrNull() ?: return null
        val observed = repository.readDefinitionVersions(workspaceId, kind, definitionId, before, limit + 1)
        val changes = observed.take(limit).map { it.withRestoreAvailability(latest, exists) }
        val name = when (val snapshot = latest.definition) {
            is SemanticDefinitionSnapshot.Model -> snapshot.definition.name.value
            is SemanticDefinitionSnapshot.Metric -> snapshot.definition.name.value
        }
        return SemanticDefinitionHistory(definitionId, name, !exists, changes,
            if (observed.size > limit) changes.last().let { SemanticHistoryBoundary(it.occurredAt, it.changeId) } else null)
    }

    private fun SemanticDefinitionVersion.withRestoreAvailability(latest: SemanticDefinitionVersion, exists: Boolean): SemanticDefinitionVersion =
        copy(restorable = if (changeId == latest.changeId) {
            !exists && action == SemanticDefinitionChangeAction.DELETED
        } else {
            definition != latest.definition
        })

    override suspend fun readRecentModels(
        workspaceId: WorkspaceId, before: Instant?, beforeId: Uuid?, actorId: PrincipalId?, credentialSurface: CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?, limit: Int,
    ): List<SemanticDefinitionChange> =
        transactionPort.inSnapshotTransaction {
            require(limit in 1..101)
            require((before == null) == (beforeId == null))
            repository.readRecentModels(workspaceId, before, beforeId, actorId, credentialSurface, agentClientId, limit)
        }

    override suspend fun readRecentMetrics(
        workspaceId: WorkspaceId, before: Instant?, beforeId: Uuid?, actorId: PrincipalId?, credentialSurface: CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?, limit: Int,
    ): List<SemanticDefinitionChange> =
        transactionPort.inSnapshotTransaction {
            require(limit in 1..101)
            require((before == null) == (beforeId == null))
            repository.readRecentMetrics(workspaceId, before, beforeId, actorId, credentialSurface, agentClientId, limit)
        }

    override suspend fun readRecentAgentClients(workspaceId: WorkspaceId, scanLimit: Int): List<cc.monomer.identity.api.RecentAgentClient> =
        transactionPort.inSnapshotTransaction { repository.readRecentAgentClients(workspaceId, scanLimit) }

    override suspend fun readModelChange(workspaceId: WorkspaceId, semanticModelId: SemanticModelId): SemanticDefinitionChange? =
        transactionPort.inSnapshotTransaction { repository.readModelChange(workspaceId, semanticModelId) }

    override suspend fun readMetricChange(workspaceId: WorkspaceId, metricId: MetricId): SemanticDefinitionChange? =
        transactionPort.inSnapshotTransaction { repository.readMetricChange(workspaceId, metricId) }


    override fun findModelsInCurrentTransaction(workspaceId: WorkspaceId): List<SemanticModelView> =
        repository.findModels(workspaceId).map { model -> model.toView() }

    override fun findMetricsInCurrentTransaction(workspaceId: WorkspaceId): List<MetricView> =
        repository.findMetrics(workspaceId).map { metric -> metric.toView() }

    override suspend fun findModel(
        workspaceId: WorkspaceId,
        semanticModelId: SemanticModelId,
    ): SemanticModelView? = transactionPort.inSnapshotTransaction {
        repository.findModel(semanticModelId)
            ?.takeIf { model -> model.workspaceId == workspaceId }
            ?.let(PartitionViewReads(workspaceId)::modelView)
    }?.let { currentModelPartitionViews(workspaceId, listOf(it)).single() }

    override suspend fun findModelByName(
        workspaceId: WorkspaceId,
        name: SemanticElementName,
    ): SemanticModelView? = transactionPort.inSnapshotTransaction {
        repository.findModelByName(workspaceId, name)?.let(PartitionViewReads(workspaceId)::modelView)
    }?.let { currentModelPartitionViews(workspaceId, listOf(it)).single() }

    override suspend fun findModels(workspaceId: WorkspaceId): List<SemanticModelView> =
        transactionPort.inSnapshotTransaction {
            repository.findModels(workspaceId).map(PartitionViewReads(workspaceId)::modelView)
        }.let { currentModelPartitionViews(workspaceId, it) }

    override suspend fun listModels(
        workspaceId: WorkspaceId,
        after: SemanticModelListBoundary?,
        limit: Int,
    ): SemanticModelListPage = transactionPort.inSnapshotTransaction {
        requirePageSize(limit, "Semantic Model")
        val observed = repository.listModels(workspaceId, after, limit + 1)
        val items = observed.take(limit).map(PartitionViewReads(workspaceId)::modelView)
        SemanticModelListPage(
            items = items,
            next = if (observed.size > limit) {
                SemanticModelListBoundary(items.last().definition.name)
            } else {
                null
            },
        )
    }.let { it.copy(items = currentModelPartitionViews(workspaceId, it.items)) }

    override suspend fun searchModels(
        workspaceId: WorkspaceId,
        query: String,
        cursor: SemanticModelPageCursor?,
        pageSize: Int,
    ): SemanticModelPage = transactionPort.inSnapshotTransaction {
        requirePageSize(pageSize, "Semantic Model")
        val rows = repository.searchModels(workspaceId, query.trim().lowercase(), cursor, pageSize + 1)
        val items = rows.take(pageSize)
        SemanticModelPage(
            items = PartitionViewReads(workspaceId).let { reads -> items.map { row -> reads.modelView(row.model) } },
            nextCursor = if (rows.size > pageSize) {
                items.lastOrNull()?.let { row ->
                    SemanticModelPageCursor(row.normalizedLabel, row.normalizedName, row.model.id)
                }
            } else {
                null
            },
        )
    }.let { it.copy(items = currentModelPartitionViews(workspaceId, it.items)) }

    override suspend fun findLatestModel(workspaceId: WorkspaceId): SemanticModelView? =
        transactionPort.inSnapshotTransaction {
            repository.findLatestModel(workspaceId)?.let(PartitionViewReads(workspaceId)::modelView)
        }?.let { currentModelPartitionViews(workspaceId, listOf(it)).single() }

    override suspend fun findMetric(
        workspaceId: WorkspaceId,
        metricId: MetricId,
    ): MetricView? = transactionPort.inSnapshotTransaction {
        repository.findMetric(metricId)
            ?.takeIf { metric -> metric.workspaceId == workspaceId }
            ?.let(PartitionViewReads(workspaceId)::metricView)
    }?.let { currentMetricPartitionViews(workspaceId, listOf(it)).single() }

    override suspend fun findMetricByName(
        workspaceId: WorkspaceId,
        name: SemanticElementName,
    ): MetricView? = transactionPort.inSnapshotTransaction {
        repository.findMetricByName(workspaceId, name)?.let(PartitionViewReads(workspaceId)::metricView)
    }?.let { currentMetricPartitionViews(workspaceId, listOf(it)).single() }

    override suspend fun findMetrics(workspaceId: WorkspaceId): List<MetricView> =
        transactionPort.inSnapshotTransaction {
            repository.findMetrics(workspaceId).map(PartitionViewReads(workspaceId)::metricView)
        }.let { currentMetricPartitionViews(workspaceId, it) }

    override suspend fun findMetricSourceConnectors(
        workspaceId: WorkspaceId,
        metricNames: Set<SemanticElementName>,
    ): Map<SemanticElementName, List<MetricSourceConnector>> {
        if (metricNames.isEmpty()) return emptyMap()
        val connectors = connectorReadUseCase.listByWorkspace(workspaceId)
            .filter { it.workspaceId == workspaceId }
            .sortedBy { it.connectorId.toString() }
        return transactionPort.inSnapshotTransaction {
            val currentMetricsByName = repository.findMetrics(workspaceId).associateBy { it.definition.name }
            val currentModelsByName = repository.findModels(workspaceId).associateBy { it.definition.name }
            metricNames.associateWith { metricName ->
                when (val closure = collectMetricClosure(listOf(metricName), currentMetricsByName, currentModelsByName)) {
                    is SemanticMetricClosure.NotFound, is SemanticMetricClosure.Unresolved -> emptyList()
                    is SemanticMetricClosure.Collected -> connectors
                        .filter { connector ->
                            closure.models.values.all { it.definition.sourceRelationFor(connector.connectorId) != null }
                        }
                        .map { MetricSourceConnector(it.connectorId, it.displayName) }
                }
            }
        }
    }

    override suspend fun listMetrics(
        workspaceId: WorkspaceId,
        after: MetricListBoundary?,
        limit: Int,
    ): MetricListPage = transactionPort.inSnapshotTransaction {
        requirePageSize(limit, "Metric")
        val observed = repository.listMetrics(workspaceId, after, limit + 1)
        val items = observed.take(limit).map(PartitionViewReads(workspaceId)::metricView)
        MetricListPage(
            items = items,
            next = if (observed.size > limit) {
                MetricListBoundary(items.last().definition.name)
            } else {
                null
            },
        )
    }.let { it.copy(items = currentMetricPartitionViews(workspaceId, it.items)) }

    override suspend fun searchMetrics(
        workspaceId: WorkspaceId,
        query: String,
        cursor: MetricPageCursor?,
        pageSize: Int,
    ): MetricPage = transactionPort.inSnapshotTransaction {
        requirePageSize(pageSize, "Metric")
        val rows = repository.searchMetrics(workspaceId, query.trim().lowercase(), cursor, pageSize + 1)
        val items = rows.take(pageSize)
        MetricPage(
            items = PartitionViewReads(workspaceId).let { reads -> items.map { row -> reads.metricView(row.metric) } },
            nextCursor = if (rows.size > pageSize) {
                items.lastOrNull()?.let { row ->
                    MetricPageCursor(row.normalizedLabel, row.normalizedName, row.metric.id)
                }
            } else {
                null
            },
        )
    }.let { it.copy(items = currentMetricPartitionViews(workspaceId, it.items)) }

    override suspend fun findLatestMetric(workspaceId: WorkspaceId): MetricView? =
        transactionPort.inSnapshotTransaction {
            repository.findLatestMetric(workspaceId)?.let(PartitionViewReads(workspaceId)::metricView)
        }?.let { currentMetricPartitionViews(workspaceId, listOf(it)).single() }

    override suspend fun findTimeSpineConfigurations(
        workspaceId: WorkspaceId,
    ): List<SemanticTimeSpineConfigurationView> = transactionPort.inSnapshotTransaction {
        timeSpineRepository.findTimeSpineConfigurations(workspaceId).map { configuration ->
            SemanticTimeSpineConfigurationView(
                connectorId = configuration.connectorId,
                definition = configuration.definition,
            )
        }
    }
}

private fun requirePageSize(pageSize: Int, resource: String) {
    require(pageSize in 1..MAXIMUM_SEMANTIC_RESOURCE_PAGE_SIZE) {
        "$resource page size must be between 1 and $MAXIMUM_SEMANTIC_RESOURCE_PAGE_SIZE"
    }
}

private const val MAXIMUM_SEMANTIC_RESOURCE_PAGE_SIZE = 100
