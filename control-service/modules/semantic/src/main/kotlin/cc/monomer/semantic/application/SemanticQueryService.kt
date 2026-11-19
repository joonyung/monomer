@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.semantic.application

import cc.monomer.catalog.api.CatalogCurrentObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import cc.monomer.catalog.api.CatalogCurrentObjectSet
import cc.monomer.catalog.api.CatalogCurrentObjectsReadPort
import cc.monomer.catalog.api.CatalogObjectKind
import cc.monomer.connector.api.ConnectorWorkspaceReadUseCase
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.CurrentConnectorRead
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.api.WorkspaceReportingTimeZoneQuery
import cc.monomer.semantic.api.SemanticGroupByOptionsRequest
import cc.monomer.semantic.api.SemanticGroupByOptionsResult
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticEntityKind
import cc.monomer.semantic.api.SemanticExcludedModel
import cc.monomer.semantic.api.SemanticGroupBy
import cc.monomer.semantic.api.SemanticInvalidReason
import cc.monomer.semantic.api.SemanticRenderRequest
import cc.monomer.semantic.api.SemanticRenderResult
import cc.monomer.semantic.domain.CurrentTimeSpineConfiguration
import java.time.ZoneId

/** Reads the current dependency closure and resolves only the selected connector binding. */
interface SemanticQueryUseCase {
    suspend fun renderBatch(requests: List<SemanticRenderRequest>, deadline: java.time.Instant): List<SemanticRenderResult>
    suspend fun readRenderInputs(request: SemanticRenderRequest): SemanticRenderInputs
    suspend fun renderFromInputs(inputs: SemanticRenderInputs): SemanticRenderResult
    suspend fun render(request: SemanticRenderRequest): SemanticRenderResult
    suspend fun readGroupByInputs(request: SemanticGroupByOptionsRequest): SemanticGroupByInputs
    suspend fun listGroupBysFromInputs(inputs: SemanticGroupByInputs): SemanticGroupByOptionsResult
    suspend fun listGroupBys(request: SemanticGroupByOptionsRequest): SemanticGroupByOptionsResult
}

class SemanticQueryService(
    private val repository: SemanticRepository,
    private val timeSpineRepository: SemanticTimeSpineRepository,
    private val transactionPort: SemanticTransactionPort,
    private val metricFlowPort: SemanticMetricFlowPort,
    private val connectorReadUseCase: ConnectorWorkspaceReadUseCase,
    private val catalogReadPort: CatalogCurrentObjectsReadPort,
    private val reportingTimeZoneQuery: WorkspaceReportingTimeZoneQuery,
) : SemanticQueryUseCase {
    override suspend fun renderBatch(
        requests: List<SemanticRenderRequest>,
        deadline: java.time.Instant,
    ): List<SemanticRenderResult> {
        if (requests.isEmpty()) return emptyList()
        require(requests.size <= 64)
        if (!java.time.Instant.now().isBefore(deadline)) throw java.util.concurrent.TimeoutException("Planning deadline exceeded")
        val workspaceId = requests.first().workspaceId
        require(requests.all { it.workspaceId == workspaceId })
        val snapshotStarted = System.nanoTime()
        val inputs = transactionPort.inSnapshotTransaction(deadline) {
            val connectors = connectorReadUseCase.findByWorkspaceAndIds(workspaceId, requests.map { it.connectorId }.toSet())
                .associateBy { it.connectorId }
            val catalogs = connectors.keys.associateWith { catalogReadPort.readCurrent(workspaceId, it) }
            CurrentBatchInputs(
                connectors.mapValues { (_, connector) ->
                    dialectFor(connector)
                },
                catalogs,
                repository.findMetrics(workspaceId).associateBy { it.definition.name },
                repository.findModels(workspaceId).associateBy { it.definition.name },
                timeSpineRepository.findTimeSpineConfigurations(workspaceId),
                reportingTimeZoneQuery.reportingTimeZone(workspaceId),
            )
        }
        val snapshotNanos = System.nanoTime() - snapshotStarted
        val renderStarted = System.nanoTime()
        val remaining = java.time.Duration.between(java.time.Instant.now(), deadline).toMillis()
        if (remaining <= 0) throw java.util.concurrent.TimeoutException("Planning deadline exceeded")
        try {
            return kotlinx.coroutines.withTimeout(remaining) {
                kotlinx.coroutines.coroutineScope {
                    val permits = kotlinx.coroutines.sync.Semaphore(4)
                    requests.map { request ->
                        async(kotlinx.coroutines.Dispatchers.Default) {
                            permits.acquire()
                            try {
                                val workerContext = kotlin.coroutines.coroutineContext
                                kotlinx.coroutines.runInterruptible {
                                    workerContext.ensureActive()
                                    if (!java.time.Instant.now().isBefore(deadline)) throw java.util.concurrent.TimeoutException("Planning deadline exceeded")
                                    if (request.connectorId !in inputs.dialects) return@runInterruptible SemanticRenderResult.NotFoundOrOutsideWorkspace
                                    val dialect = inputs.dialects[request.connectorId] ?: return@runInterruptible SemanticRenderResult.Unsupported(
                                        setOf(cc.monomer.semantic.api.SemanticUnsupportedCapability.UNSUPPORTED_DIALECT),
                                    )
                                    val projection = projectCurrentClosure(
                                        workspaceId, request.connectorId, request.query.metrics, inputs.catalogs[request.connectorId], dialect,
                                        inputs.timeSpineConfigurations, inputs.metrics, inputs.models, inputs.reportingTimeZone,
                                    )
                                    val result = renderResolution(request, metricFlowPort.resolveQuery(projection), inputs.catalogs[request.connectorId])
                                    workerContext.ensureActive()
                                    if (!java.time.Instant.now().isBefore(deadline)) throw java.util.concurrent.TimeoutException("Planning deadline exceeded")
                                    result
                                }
                            } finally { permits.release() }
                        }
                    }.awaitAll()
                }
            }
        } finally {
            org.slf4j.LoggerFactory.getLogger(SemanticQueryService::class.java).atInfo()
                .addKeyValue("event", "semantic_planning_batch_timing")
                .addKeyValue("queries", requests.size)
                .addKeyValue("snapshot_wall_ms", snapshotNanos / 1_000_000)
                .addKeyValue("render_wall_ms", (System.nanoTime() - renderStarted) / 1_000_000)
                .log("semantic_planning_batch_timing")
        }
    }

    override suspend fun render(request: SemanticRenderRequest): SemanticRenderResult =
        renderFromInputs(readRenderInputs(request))

    override suspend fun readRenderInputs(request: SemanticRenderRequest): SemanticRenderInputs =
        SemanticRenderInputs(request, readCurrentInputs(request.workspaceId, request.connectorId))

    override suspend fun renderFromInputs(inputs: SemanticRenderInputs): SemanticRenderResult {
        val workerContext = kotlin.coroutines.coroutineContext
        return kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.Default) {
            workerContext.ensureActive()
            val request = inputs.request
            val current = inputs.current
            val result = when {
                !current.connectorFound -> SemanticRenderResult.NotFoundOrOutsideWorkspace
                current.dialect == null -> SemanticRenderResult.Unsupported(
                    setOf(cc.monomer.semantic.api.SemanticUnsupportedCapability.UNSUPPORTED_DIALECT),
                )
                else -> renderResolution(
                    request,
                    metricFlowPort.resolveQuery(current.projection(request.workspaceId, request.connectorId, request.query.metrics)),
                    current.catalog,
                )
            }
            workerContext.ensureActive()
            result
        }
    }

    private suspend fun readCurrentInputs(workspaceId: WorkspaceId, connectorId: ConnectorId): SemanticCurrentQueryInputs {
        val connector = connectorReadUseCase.findByWorkspaceAndId(workspaceId, connectorId)
        val dialect = connector?.let(::dialectFor)
        if (dialect == null) return SemanticCurrentQueryInputs(connector != null, null, null, emptyMap(), emptyMap(), emptyList(), null)
        val reportingTimeZone = reportingTimeZoneQuery.reportingTimeZone(workspaceId)
        return transactionPort.inSnapshotTransaction {
            SemanticCurrentQueryInputs(
                connectorFound = true,
                dialect = dialect,
                catalog = catalogReadPort.readCurrent(workspaceId, connectorId),
                metrics = repository.findMetrics(workspaceId).associateBy { it.definition.name },
                models = repository.findModels(workspaceId).associateBy { it.definition.name },
                timeSpineConfigurations = timeSpineRepository.findTimeSpineConfigurations(workspaceId),
                reportingTimeZone = reportingTimeZone,
            )
        }
    }

    private fun renderResolution(
        request: SemanticRenderRequest,
        resolution: SemanticQueryResolution,
        catalog: CatalogCurrentObjectSet?,
    ): SemanticRenderResult =
        when (resolution) {
            is SemanticQueryResolution.NotFound -> SemanticRenderResult.MetricNotFound(resolution.metricName)
            is SemanticQueryResolution.Invalid -> SemanticRenderResult.Invalid(resolution.reasons, resolution.definitionIssues)
            is SemanticQueryResolution.Resolved -> {
                val mismatches = request.query.filterTypeMismatches(
                    dimensionFilterTypes(request.workspaceId, catalog, resolution.manifest),
                    resolution.manifest,
                )
                if (mismatches.isNotEmpty()) SemanticRenderResult.Invalid(mismatches, emptyList())
                else metricFlowPort.renderResolved(request, resolution)
            }
        }

    override suspend fun listGroupBys(request: SemanticGroupByOptionsRequest): SemanticGroupByOptionsResult =
        listGroupBysFromInputs(readGroupByInputs(request))

    override suspend fun readGroupByInputs(request: SemanticGroupByOptionsRequest): SemanticGroupByInputs =
        SemanticGroupByInputs(request, readCurrentInputs(request.workspaceId, request.connectorId))

    override suspend fun listGroupBysFromInputs(inputs: SemanticGroupByInputs): SemanticGroupByOptionsResult {
        val workerContext = kotlin.coroutines.coroutineContext
        return kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.Default) {
            workerContext.ensureActive()
            val result = listCurrentGroupBys(inputs)
            workerContext.ensureActive()
            result
        }
    }

    private fun listCurrentGroupBys(inputs: SemanticGroupByInputs): SemanticGroupByOptionsResult {
        val current = inputs.current
        if (!current.connectorFound) return SemanticGroupByOptionsResult.NotFoundOrOutsideWorkspace
        if (current.dialect == null) return SemanticGroupByOptionsResult.Unsupported(
            setOf(cc.monomer.semantic.api.SemanticUnsupportedCapability.UNSUPPORTED_DIALECT),
        )
        val request = inputs.request
        val projection = current.projection(request.workspaceId, request.connectorId, request.metrics)
        return when (val resolution = metricFlowPort.resolveQuery(projection)) {
            is SemanticQueryResolution.NotFound -> SemanticGroupByOptionsResult.NotFoundOrOutsideWorkspace
            is SemanticQueryResolution.Invalid ->
                SemanticGroupByOptionsResult.Invalid(resolution.reasons, resolution.definitionIssues)
            is SemanticQueryResolution.Resolved -> {
                check(projection is SemanticClosureProjection.Projected)
                when (val listed = metricFlowPort.listGroupBys(resolution.manifest, resolution.metricIds)) {
                    is SemanticMetricFlowGroupByResult.Listed ->
                        SemanticGroupByOptionsResult.Listed(
                            listed.groupBys,
                            projection.relevantExcludedModels(resolution.excludedModels, listed.groupBys),
                            dimensionFilterTypes(request.workspaceId, current.catalog, resolution.manifest).filter { filterType ->
                                listed.groupBys.any { groupBy ->
                                    groupBy is SemanticGroupBy.Dimension && groupBy.semanticModelName == filterType.dimension.semanticModelName &&
                                        groupBy.name == filterType.dimension.name
                                }
                            },
                        )
                    is SemanticMetricFlowGroupByResult.Invalid ->
                        SemanticGroupByOptionsResult.Invalid(
                            listed.reasons.map { reason -> SemanticInvalidReason(reason.code, reason.field) },
                            emptyList(),
                        )
                    is SemanticMetricFlowGroupByResult.Unsupported ->
                        SemanticGroupByOptionsResult.Unsupported(listed.capabilities.toPublicCapabilities())
                }
            }
        }
    }
}

data class SemanticRenderInputs(
    val request: SemanticRenderRequest,
    val current: SemanticCurrentQueryInputs,
)

data class SemanticGroupByInputs(
    val request: SemanticGroupByOptionsRequest,
    val current: SemanticCurrentQueryInputs,
)

data class SemanticCurrentQueryInputs(
    val connectorFound: Boolean,
    val dialect: cc.monomer.semantic.api.SemanticSqlDialect?,
    val catalog: CatalogCurrentObjectSet?,
    val metrics: Map<SemanticElementName, cc.monomer.semantic.domain.CurrentMetric>,
    val models: Map<SemanticElementName, cc.monomer.semantic.domain.CurrentSemanticModel>,
    val timeSpineConfigurations: List<CurrentTimeSpineConfiguration>,
    val reportingTimeZone: ZoneId?,
) {
    init {
        require((dialect != null) == (reportingTimeZone != null)) {
            "semantic query inputs read the reporting time zone exactly when the connector can render"
        }
    }

    internal fun projection(workspaceId: WorkspaceId, connectorId: ConnectorId, metricNames: List<SemanticElementName>): SemanticClosureProjection =
        projectCurrentClosure(
            workspaceId, connectorId, metricNames, catalog, requireNotNull(dialect), timeSpineConfigurations, metrics, models,
            requireNotNull(reportingTimeZone),
        )
}

private fun SemanticClosureProjection.Projected.relevantExcludedModels(
    excludedModels: List<SemanticExcludedModel>,
    groupBys: List<SemanticGroupBy>,
): List<SemanticExcludedModel> {
    if (excludedModels.isEmpty()) return emptyList()
    val projectedModels = closureModels + candidateModels
    val reachedModelNames = closureModels.keys + groupBys.mapNotNull { groupBy ->
        when (groupBy) {
            is SemanticGroupBy.Dimension -> groupBy.semanticModelName
            is SemanticGroupBy.Entity -> groupBy.semanticModelName
            is SemanticGroupBy.MetricTime -> null
        }
    }
    val reachedEntityNames = reachedModelNames.flatMap { modelName ->
        projectedModels.getValue(modelName).definition.entities.map { it.name }
    }.toSet()
    return excludedModels.filter { excluded ->
        candidateModels.getValue(excluded.name).definition.entities.any { entity ->
            // MetricFlow joins toward PRIMARY/UNIQUE; NATURAL targets require validity dimensions we do not project.
            entity.name in reachedEntityNames &&
                (entity.type == SemanticEntityKind.PRIMARY || entity.type == SemanticEntityKind.UNIQUE)
        }
    }
}

internal fun projectCurrentTimeSpines(
    timeSpineConfigurations: List<CurrentTimeSpineConfiguration>,
    workspaceId: WorkspaceId,
    connectorId: ConnectorId,
    catalog: CatalogCurrentObjectSet,
): List<SemanticMetricFlowTimeSpine> {
    if (catalog.workspaceId != workspaceId || catalog.connectorId != connectorId) return emptyList()
    val configuration = timeSpineConfigurations.singleOrNull {
        it.workspaceId == workspaceId && it.connectorId == connectorId
    } ?: return emptyList()
    return configuration.definition.timeSpines.mapNotNull { timeSpine ->
        resolveCatalogRelation(catalog.objects, timeSpine.relation.catalog, timeSpine.relation.schema, timeSpine.relation.relation)
            ?.let { relation ->
                SemanticMetricFlowTimeSpine(relation.address, timeSpine.primaryColumn.name, timeSpine.primaryColumn.granularity)
            }
    }
}

/** Readable findings for configured spine columns whose known Catalog type is not a date or wall-clock time. */
internal fun invalidTimeSpineColumns(
    timeSpineConfigurations: List<CurrentTimeSpineConfiguration>,
    workspaceId: WorkspaceId,
    connectorId: ConnectorId,
    catalog: CatalogCurrentObjectSet,
    dialect: cc.monomer.semantic.api.SemanticSqlDialect,
): List<String> {
    if (catalog.workspaceId != workspaceId || catalog.connectorId != connectorId) return emptyList()
    val configuration = timeSpineConfigurations.singleOrNull {
        it.workspaceId == workspaceId && it.connectorId == connectorId
    } ?: return emptyList()
    return configuration.definition.timeSpines.mapNotNull { timeSpine ->
        timeSpineColumnTypeProblem(timeSpine, catalog, dialect)
    }
}

internal fun timeSpineColumnTypeProblem(
    timeSpine: cc.monomer.semantic.api.SemanticTimeSpine,
    catalog: CatalogCurrentObjectSet,
    dialect: cc.monomer.semantic.api.SemanticSqlDialect,
): String? {
    val relation = resolveCatalogRelation(
        catalog.objects, timeSpine.relation.catalog, timeSpine.relation.schema, timeSpine.relation.relation,
    ) ?: return null
    val nativeType = catalogColumn(catalog, relation, ColumnIdentifier(timeSpine.primaryColumn.name, false), dialect)
        ?.nativeType ?: return null
    if (timeSpineColumnTypeAllowed(nativeType, dialect)) return null
    return "Time spine column `${timeSpine.primaryColumn.name}` of ${relation.qualifiedName} is `$nativeType`. " +
        "Use a date column or a timestamp column without a time zone."
}

internal fun resolveCatalogRelation(
    objects: List<CatalogCurrentObject>,
    catalog: String?,
    schema: String,
    relation: String,
): CatalogCurrentObject? = objects.filter { current ->
    current.kind == CatalogObjectKind.RELATION &&
        (catalog == null || current.address.catalogName == catalog) &&
        current.address.schemaName == schema &&
        current.address.objectName == relation &&
        current.address.memberName == null
}.singleOrNull()

private data class CurrentBatchInputs(
    val dialects: Map<ConnectorId, cc.monomer.semantic.api.SemanticSqlDialect?>,
    val catalogs: Map<ConnectorId, CatalogCurrentObjectSet?>,
    val metrics: Map<SemanticElementName, cc.monomer.semantic.domain.CurrentMetric>,
    val models: Map<SemanticElementName, cc.monomer.semantic.domain.CurrentSemanticModel>,
    val timeSpineConfigurations: List<CurrentTimeSpineConfiguration>,
    val reportingTimeZone: ZoneId,
)

internal fun dialectFor(connector: CurrentConnectorRead): cc.monomer.semantic.api.SemanticSqlDialect? =
    try {
        cc.monomer.semantic.api.SemanticSqlDialect.requireForConnectorType(connector.connectorType.name.lowercase())
    } catch (_: IllegalArgumentException) {
        null
    }
