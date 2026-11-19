package cc.monomer.semantic.application

import cc.monomer.catalog.api.CatalogCurrentObjectSet
import cc.monomer.catalog.api.CatalogCurrentObjectsReadPort
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorWorkspaceReadUseCase
import cc.monomer.connector.api.CurrentConnectorRead
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.api.WorkspaceReportingTimeZoneQuery
import cc.monomer.semantic.api.SemanticPartitionFilter
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.SemanticCheckInputs
import cc.monomer.semantic.api.SemanticTimeSpineConfigurationView
import cc.monomer.semantic.api.SemanticCheckDraft
import cc.monomer.semantic.api.SemanticCheckPreview
import cc.monomer.semantic.api.SemanticCheckProblem
import cc.monomer.semantic.api.SemanticCheckProblemCode
import cc.monomer.semantic.api.SemanticCheckRequest
import cc.monomer.semantic.api.SemanticCheckResult
import cc.monomer.semantic.api.SemanticDefinitionCheckUseCase
import cc.monomer.semantic.api.SemanticDefinitionReference
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticGroupBy
import cc.monomer.semantic.api.SemanticTimeGranularity
import cc.monomer.semantic.api.SemanticInvalidReasonCode
import cc.monomer.semantic.api.SemanticMetricDefinition
import cc.monomer.semantic.api.SemanticMetricFilter
import cc.monomer.semantic.api.SemanticMetricInput
import cc.monomer.semantic.api.SemanticModelDefinition
import cc.monomer.semantic.api.SemanticModelId
import cc.monomer.semantic.api.referencedModelNames
import cc.monomer.semantic.api.SemanticQuerySpec
import cc.monomer.semantic.api.SemanticRenderRequest
import cc.monomer.semantic.api.SemanticRenderResult
import cc.monomer.semantic.api.SemanticSqlDialect
import cc.monomer.semantic.api.SemanticUnsupportedCapability
import cc.monomer.semantic.domain.CurrentMetric
import cc.monomer.semantic.domain.CurrentSemanticModel
import cc.monomer.semantic.domain.CurrentTimeSpineConfiguration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ensureActive

/**
 * Checks one draft with the query path's closure resolution and MetricFlow validation. The draft
 * replaces the saved definition of the same name for this request only; nothing is stored.
 */
class SemanticDefinitionCheckService(
    private val repository: SemanticRepository,
    private val timeSpineRepository: SemanticTimeSpineRepository,
    private val transactionPort: SemanticTransactionPort,
    private val metricFlowPort: SemanticMetricFlowPort,
    private val connectorReadUseCase: ConnectorWorkspaceReadUseCase,
    private val catalogReadPort: CatalogCurrentObjectsReadPort,
    private val reportingTimeZoneQuery: WorkspaceReportingTimeZoneQuery,
) : SemanticDefinitionCheckUseCase {
    override suspend fun check(request: SemanticCheckRequest): SemanticCheckResult =
        checkFromInputs(readInputs(request))

    override suspend fun readInputs(request: SemanticCheckRequest): SemanticCheckInputs {
        val workspaceId = request.workspaceId
        val connectors = connectorReadUseCase.listByWorkspace(workspaceId).associateBy(CurrentConnectorRead::connectorId)
        val dialects = connectors.mapValues { (_, connector) -> dialectFor(connector) }
        val draft = request.draft
        val reportingTimeZone = reportingTimeZoneQuery.reportingTimeZone(workspaceId)
        if (draft is SemanticCheckDraft.Metric && draft.connectorId != null && draft.connectorId !in connectors) {
            return SemanticCheckInputs(request, emptyList(), emptyList(), dialects, emptyMap(), emptyList(), reportingTimeZone)
        }
        return transactionPort.inSnapshotTransaction {
            val models = repository.findModels(workspaceId)
            val metrics = repository.findMetrics(workspaceId)
            val definitionFound = when (draft) {
                is SemanticCheckDraft.Model -> draft.semanticModelId == null || models.any { it.id == draft.semanticModelId }
                is SemanticCheckDraft.Metric -> draft.metricId == null || metrics.any { it.id == draft.metricId }
            }
            if (!definitionFound) return@inSnapshotTransaction SemanticCheckInputs(
                request, models.map { it.toView() }, metrics.map { it.toView() }, dialects, emptyMap(), emptyList(), reportingTimeZone,
            )
            val catalogConnectorIds = when (draft) {
                is SemanticCheckDraft.Model -> draft.definition.sourceRelations.map { it.connectorId }.filter { it in connectors }
                is SemanticCheckDraft.Metric ->
                    metricInputs(workspaceId, draft, models, metrics, dialects, emptyMap(), emptyList(), reportingTimeZone)
                        ?.candidates.orEmpty().map { it.connectorId }
            }.distinct()
            SemanticCheckInputs(
                request = request,
                models = models.map { it.toView() },
                metrics = metrics.map { it.toView() },
                connectorDialects = dialects,
                catalogs = catalogConnectorIds.associateWith { catalogReadPort.readCurrent(workspaceId, it) },
                timeSpineConfigurations = if (catalogConnectorIds.isEmpty() && draft is SemanticCheckDraft.Metric) emptyList() else
                    timeSpineRepository.findTimeSpineConfigurations(workspaceId).map {
                        SemanticTimeSpineConfigurationView(it.connectorId, it.definition)
                    },
                reportingTimeZone = reportingTimeZone,
            )
        }
    }

    override suspend fun checkFromInputs(inputs: SemanticCheckInputs): SemanticCheckResult {
        val workerContext = kotlin.coroutines.coroutineContext
        return kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.Default) {
            workerContext.ensureActive()
            val result = checkCurrentInputs(inputs)
            workerContext.ensureActive()
            result
        }
    }

    private fun checkCurrentInputs(current: SemanticCheckInputs): SemanticCheckResult {
        val request = current.request
        val workspaceId = request.workspaceId
        val draft = request.draft
        if (draft is SemanticCheckDraft.Metric && draft.connectorId != null && draft.connectorId !in current.connectorDialects) {
            return SemanticCheckResult.NotFoundOrOutsideWorkspace
        }
        val models = current.models.map { CurrentSemanticModel(it.semanticModelId, it.workspaceId, it.definition, it.createdAt, it.updatedAt) }
        val metrics = current.metrics.map { CurrentMetric(it.metricId, it.workspaceId, it.definition, it.createdAt, it.updatedAt) }
        val spines = current.timeSpineConfigurations.map { CurrentTimeSpineConfiguration(workspaceId, it.connectorId, it.definition) }
        val inputs = when (draft) {
            is SemanticCheckDraft.Model ->
                modelInputs(workspaceId, draft, models, metrics, current.connectorDialects, current.catalogs, spines, current.reportingTimeZone)
            is SemanticCheckDraft.Metric ->
                metricInputs(workspaceId, draft, models, metrics, current.connectorDialects, current.catalogs, spines, current.reportingTimeZone)
        } ?: return SemanticCheckResult.NotFoundOrOutsideWorkspace
        return when (inputs) {
            is ModelCheckInputs -> checkModel(workspaceId, inputs)
            is MetricCheckInputs -> checkMetric(workspaceId, inputs)
        }
    }

    private fun modelInputs(
        workspaceId: WorkspaceId,
        draft: SemanticCheckDraft.Model,
        models: List<CurrentSemanticModel>,
        metrics: List<CurrentMetric>,
        dialects: Map<ConnectorId, SemanticSqlDialect?>,
        catalogs: Map<ConnectorId, CatalogCurrentObjectSet?>,
        timeSpineConfigurations: List<CurrentTimeSpineConfiguration>,
        reportingTimeZone: ZoneId,
    ): ModelCheckInputs? {
        val saved = draft.semanticModelId?.let { id -> models.firstOrNull { it.id == id } ?: return null }
        val sameName = models.firstOrNull { it.definition.name == draft.definition.name }
        val identityProblems = identityProblems(saved?.definition?.name, sameName != null && sameName.id != saved?.id, draft.definition.name)
        val draftModel = CurrentSemanticModel(
            id = saved?.id ?: sameName?.id ?: SemanticModelId.generate(),
            workspaceId = workspaceId,
            definition = draft.definition,
            createdAt = CHECK_TIME,
            updatedAt = CHECK_TIME,
        )
        val modelsByName = models
            .filterNot { it.id == saved?.id || it.definition.name == draft.definition.name }
            .associateBy { it.definition.name } + (draft.definition.name to draftModel)
        val metricsByName = metrics.associateBy { it.definition.name }
        val dependentClosures = metrics
            .filter { draft.definition.name in it.definition.referencedModelNames() }
            .mapNotNull { dependent ->
                collectMetricClosure(listOf(dependent.definition.name), metricsByName, modelsByName) as? SemanticMetricClosure.Collected
            }
        val sources = draft.definition.sourceRelations.mapIndexed { index, source ->
            ModelSourceInput(
                index = index,
                connectorId = source.connectorId,
                dialect = dialects[source.connectorId],
                connectorFound = source.connectorId in dialects,
                catalog = catalogs[source.connectorId],
            )
        }
        return ModelCheckInputs(
            definition = draft.definition,
            identityProblems = identityProblems,
            sources = sources,
            dependentClosures = dependentClosures,
            modelsByName = modelsByName,
            timeSpineConfigurations = timeSpineConfigurations,
            reportingTimeZone = reportingTimeZone,
        )
    }

    private fun metricInputs(
        workspaceId: WorkspaceId,
        draft: SemanticCheckDraft.Metric,
        models: List<CurrentSemanticModel>,
        metrics: List<CurrentMetric>,
        dialects: Map<ConnectorId, SemanticSqlDialect?>,
        catalogs: Map<ConnectorId, CatalogCurrentObjectSet?>,
        timeSpineConfigurations: List<CurrentTimeSpineConfiguration>,
        reportingTimeZone: ZoneId,
    ): MetricCheckInputs? {
        val saved = draft.metricId?.let { id -> metrics.firstOrNull { it.id == id } ?: return null }
        val sameName = metrics.firstOrNull { it.definition.name == draft.definition.name }
        val identityProblems = identityProblems(saved?.definition?.name, sameName != null && sameName.id != saved?.id, draft.definition.name)
        val draftMetric = CurrentMetric(
            id = saved?.id ?: sameName?.id ?: MetricId.generate(),
            workspaceId = workspaceId,
            definition = draft.definition,
            createdAt = CHECK_TIME,
            updatedAt = CHECK_TIME,
        )
        val metricsByName = metrics
            .filterNot { it.id == saved?.id || it.definition.name == draft.definition.name }
            .associateBy { it.definition.name } + (draft.definition.name to draftMetric)
        val modelsByName = models.associateBy { it.definition.name }
        val closure = collectMetricClosure(listOf(draft.definition.name), metricsByName, modelsByName)
        val candidates = when {
            closure !is SemanticMetricClosure.Collected -> emptyList()
            draft.connectorId != null -> listOfNotNull(draft.connectorId.takeIf { it in dialects })
            else -> dialects.keys.filter { connectorId ->
                closure.models.values.all { it.definition.sourceRelationFor(connectorId) != null }
            }
        }
        return MetricCheckInputs(
            draft = draft.definition,
            identityProblems = identityProblems,
            closure = closure,
            candidates = candidates.map { connectorId ->
                MetricConnectorInput(connectorId, dialects[connectorId], catalogs[connectorId])
            },
            modelsByName = modelsByName,
            timeSpineConfigurations = timeSpineConfigurations,
            reportingTimeZone = reportingTimeZone,
        )
    }

    private fun checkModel(workspaceId: WorkspaceId, inputs: ModelCheckInputs): SemanticCheckResult.Checked {
        val problems = inputs.identityProblems.toMutableList()
        val runsOn = mutableListOf<ConnectorId>()
        val partitionFilters = mutableListOf<SemanticPartitionFilter>()
        inputs.sources.forEach { source ->
            val field = "sourceRelations[${source.index}]"
            if (!source.connectorFound) {
                problems += problem(SemanticCheckProblemCode.CONNECTOR_NOT_FOUND, field, source.connectorId)
                return@forEach
            }
            val dialect = source.dialect
                ?: return@forEach run { problems += problem(SemanticCheckProblemCode.UNSUPPORTED_DIALECT, field, source.connectorId) }
            val catalog = source.catalog
            val catalogObject = catalog?.let { resolveModelSource(inputs.definition, source.connectorId, it) }
            if (catalog == null || catalogObject == null) {
                problems += problem(SemanticCheckProblemCode.CATALOG_RELATION_NOT_FOUND, field, source.connectorId)
                return@forEach
            }
            sourcePartitionInfo(inputs.definition, source.connectorId, catalog)?.requiredFilter(inputs.definition.name)?.let { fact ->
                partitionFilters += fact
                if (fact.dimensions.isNullOrEmpty()) problems += partitionProblem(fact)
            }
            val timeKindProblems = timeKindProblems(inputs.definition, catalogObject, catalog, dialect, source.connectorId)
            problems += timeKindProblems
            if (timeKindProblems.any { it.code == SemanticCheckProblemCode.TIME_KIND_MISMATCH }) return@forEach
            val dependents = inputs.dependentClosures.filter { closure ->
                closure.models.values.all { resolveModelSource(it.definition, source.connectorId, catalog) != null }
            }
            val draftModel = inputs.modelsByName.getValue(inputs.definition.name)
            val merged = SemanticMetricClosure.Collected(
                requestedMetricIds = dependents.flatMap { it.requestedMetricIds }.distinct(),
                metrics = dependents.fold(linkedMapOf()) { all, closure -> all.apply { putAll(closure.metrics) } },
                models = dependents.fold(linkedMapOf(inputs.definition.name to draftModel)) { all, closure -> all.apply { putAll(closure.models) } },
            )
            val resolutionProblems = when (
                val projection = projectMetricClosure(
                    workspaceId, source.connectorId, merged, catalog, dialect, inputs.timeSpineConfigurations, inputs.modelsByName,
                    inputs.reportingTimeZone,
                )
            ) {
                is SemanticClosureProjection.Projected ->
                    validationProblems(metricFlowPort.validate(projection.manifest(projection.candidateModels.keys)), source.connectorId, SemanticDefinitionReference.SemanticModel(inputs.definition.name)) { subject ->
                        modelElementField(inputs.definition, subject)
                    }
                is SemanticClosureProjection.Invalid ->
                    timeTypeProblems(projection.definitionIssues, source.connectorId, SemanticDefinitionReference.SemanticModel(inputs.definition.name))
                        .ifEmpty { projection.reasons.map { problem(it.code.toCheckProblemCode(), null, source.connectorId) } }
                is SemanticClosureProjection.NotFound -> listOf(problem(SemanticCheckProblemCode.CATALOG_RELATION_NOT_FOUND, field, source.connectorId))
            }
            if (resolutionProblems.isEmpty()) runsOn += source.connectorId else problems += resolutionProblems
        }
        return SemanticCheckResult.Checked(runsOn, null, problems.distinct(), partitionFilters.distinct())
    }

    private fun checkMetric(workspaceId: WorkspaceId, inputs: MetricCheckInputs): SemanticCheckResult.Checked {
        val problems = inputs.identityProblems.toMutableList()
        val closure = when (val collected = inputs.closure) {
            is SemanticMetricClosure.Collected -> collected
            is SemanticMetricClosure.Unresolved -> {
                problems += problem(
                    if (collected.code == SemanticInvalidReasonCode.METRIC_MODEL_NOT_FOUND) {
                        SemanticCheckProblemCode.METRIC_MODEL_NOT_FOUND
                    } else {
                        SemanticCheckProblemCode.METRIC_DEPENDENCY_NOT_FOUND
                    },
                    directReferenceField(inputs.draft, collected.code, collected.missingName),
                    null,
                )
                return SemanticCheckResult.Checked(emptyList(), null, problems.distinct(), emptyList())
            }
            is SemanticMetricClosure.NotFound -> error("the draft metric is always part of its own closure")
        }
        if (inputs.candidates.isEmpty()) {
            problems += problem(SemanticCheckProblemCode.SOURCE_RELATION_NOT_FOUND, null, null)
            return SemanticCheckResult.Checked(emptyList(), null, problems.distinct(), emptyList())
        }
        val runsOn = mutableListOf<ConnectorId>()
        val partitionFilters = mutableListOf<SemanticPartitionFilter>()
        var preview: SemanticCheckPreview? = null
        inputs.candidates.forEach { candidate ->
            val dialect = candidate.dialect
                ?: return@forEach run { problems += problem(SemanticCheckProblemCode.UNSUPPORTED_DIALECT, null, candidate.connectorId) }
            val catalog = candidate.catalog
                ?: return@forEach run { problems += problem(SemanticCheckProblemCode.CATALOG_RELATION_NOT_FOUND, null, candidate.connectorId) }
            val resolution = metricFlowPort.resolveQuery(
                projectMetricClosure(
                    workspaceId, candidate.connectorId, closure, catalog, dialect, inputs.timeSpineConfigurations, inputs.modelsByName,
                    inputs.reportingTimeZone,
                ),
            )
            when (resolution) {
                is SemanticQueryResolution.NotFound -> problems += problem(SemanticCheckProblemCode.CATALOG_RELATION_NOT_FOUND, null, candidate.connectorId)
                is SemanticQueryResolution.Invalid -> problems += invalidResolutionProblems(resolution, candidate.connectorId, SemanticDefinitionReference.Metric(inputs.draft.name))
                is SemanticQueryResolution.Resolved -> {
                    partitionFilters += resolution.partitionCheck.partitionFilters
                    resolution.partitionCheck.partitionFilters.filter { it.dimensions?.isEmpty() == true }.forEach { problems += partitionProblem(it) }
                    val overTime = renderValidated(
                        metricFlowPort,
                        SemanticRenderRequest(workspaceId, candidate.connectorId, representativeQuery(inputs.draft.name, listOf(SemanticGroupBy.MetricTime(inputs.draft.timeGranularity ?: SemanticTimeGranularity.DAY)))),
                        resolution.manifest,
                        resolution.metricIds,
                    )
                    val representative = if (overTime is SemanticRenderResult.Invalid && overTime.reasons.any { it.code == SemanticInvalidReasonCode.QUERY_GROUP_BY_NOT_FOUND }) {
                        renderValidated(
                            metricFlowPort,
                            SemanticRenderRequest(workspaceId, candidate.connectorId, representativeQuery(inputs.draft.name, emptyList())),
                            resolution.manifest,
                            resolution.metricIds,
                        )
                    } else {
                        overTime
                    }
                    when (val rendered = representative) {
                        is SemanticRenderResult.Rendered -> {
                            runsOn += candidate.connectorId
                            if (preview == null) {
                                preview = SemanticCheckPreview(
                                    connectorId = candidate.connectorId,
                                    dialect = rendered.render.dialect,
                                    sql = rendered.render.sql,
                                    groupBys = when (val listed = metricFlowPort.listGroupBys(resolution.manifest, resolution.metricIds)) {
                                        is SemanticMetricFlowGroupByResult.Listed -> listed.groupBys
                                        else -> emptyList()
                                    },
                                )
                            }
                        }
                        is SemanticRenderResult.Invalid -> problems += rendered.reasons.map { reason -> problem(reason.code.toCheckProblemCode(), null, candidate.connectorId) }
                        is SemanticRenderResult.Unsupported -> problems += rendered.capabilities.map { problem(it.toCheckProblemCode(), null, candidate.connectorId) }
                        is SemanticRenderResult.MetricNotFound, SemanticRenderResult.NotFoundOrOutsideWorkspace -> problems += problem(SemanticCheckProblemCode.CATALOG_RELATION_NOT_FOUND, null, candidate.connectorId)
                    }
                }
            }
        }
        return SemanticCheckResult.Checked(runsOn, preview, problems.distinct(), partitionFilters.distinct())
    }

    private fun partitionProblem(fact: SemanticPartitionFilter) = SemanticCheckProblem(
        SemanticCheckProblemCode.PARTITION_COLUMN_NOT_MODELED, null, fact.connectorId,
        fact.semanticModelName?.let(SemanticDefinitionReference::SemanticModel),
        "The table needs a filter on ${fact.partitionKeys.joinToString(", ")}. Add a dimension that uses it.",
    )

    private fun validationProblems(
        validation: SemanticMetricFlowValidationResult,
        connectorId: ConnectorId,
        draft: SemanticDefinitionReference,
        fieldOf: (SemanticMetricFlowIssueSubject?) -> String?,
    ): List<SemanticCheckProblem> = when (validation) {
        SemanticMetricFlowValidationResult.Valid -> emptyList()
        is SemanticMetricFlowValidationResult.Invalid ->
            validation.issues.map { issue ->
                SemanticCheckProblem(
                    SemanticCheckProblemCode.METRICFLOW_MANIFEST_INVALID,
                    fieldOf(issue.subject),
                    null,
                    issue.subject?.definition()?.takeIf { it != draft },
                    issue.message.take(MAX_DETAIL_LENGTH),
                )
            }.ifEmpty {
                validation.reasons.map { reason -> problem(reason.code.toCheckProblemCode(), null, connectorId) }
            }
    }

    private fun invalidResolutionProblems(
        resolution: SemanticQueryResolution.Invalid,
        connectorId: ConnectorId,
        draft: SemanticDefinitionReference,
    ): List<SemanticCheckProblem> =
        (
            resolution.definitionIssues
                .filter { it.code == SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID }
                .map { issue -> SemanticCheckProblem(SemanticCheckProblemCode.METRICFLOW_MANIFEST_INVALID, null, null, issue.definition?.takeIf { it != draft }, issue.message) } +
                timeTypeProblems(resolution.definitionIssues, connectorId, draft)
            )
            .ifEmpty { resolution.reasons.map { reason -> problem(reason.code.toCheckProblemCode(), null, connectorId) } }

    /** Time kind and time spine column findings keep their readable message and the Connector whose Catalog proves them. */
    private fun timeTypeProblems(
        issues: List<cc.monomer.semantic.api.SemanticDefinitionIssue>,
        connectorId: ConnectorId,
        draft: SemanticDefinitionReference,
    ): List<SemanticCheckProblem> = issues.mapNotNull { issue ->
        val code = when (issue.code) {
            SemanticInvalidReasonCode.TIME_KIND_MISMATCH -> SemanticCheckProblemCode.TIME_KIND_MISMATCH
            SemanticInvalidReasonCode.TIME_SPINE_COLUMN_TYPE_INVALID -> SemanticCheckProblemCode.TIME_SPINE_COLUMN_TYPE_INVALID
            else -> return@mapNotNull null
        }
        SemanticCheckProblem(code, null, connectorId, issue.definition?.takeIf { it != draft }, issue.message)
    }

    /** A known Catalog type that contradicts a declaration is an error; an unknown kind without a declaration is a warning. */
    private fun timeKindProblems(
        definition: SemanticModelDefinition,
        relation: cc.monomer.catalog.api.CatalogCurrentObject,
        catalog: CatalogCurrentObjectSet,
        dialect: SemanticSqlDialect,
        connectorId: ConnectorId,
    ): List<SemanticCheckProblem> =
        timeDimensionResolutions(definition, relation, catalog, dialect).mapNotNull { (dimensionName, resolution) ->
            val field = "dimensions[${definition.dimensions.indexOfFirst { it.name == dimensionName }}]"
            when (resolution) {
                is SemanticTimeDimensionResolution.Mismatch -> SemanticCheckProblem(
                    SemanticCheckProblemCode.TIME_KIND_MISMATCH, field, connectorId, null,
                    "timeKind `${resolution.declared.name.lowercase()}` doesn't match this column's Catalog type `${resolution.nativeType}`."
                        .take(MAX_DETAIL_LENGTH),
                )
                is SemanticTimeDimensionResolution.Resolved ->
                    if (resolution.declarationNeeded) problem(SemanticCheckProblemCode.TIME_KIND_UNDECLARED, field, connectorId) else null
            }
        }

    /** An engine subject names a saved definition only when its name is a valid definition name. */
    private fun SemanticMetricFlowIssueSubject.definition(): SemanticDefinitionReference? = when (this) {
        is SemanticMetricFlowIssueSubject.Metric -> elementName(metricName)?.let(SemanticDefinitionReference::Metric)
        is SemanticMetricFlowIssueSubject.Model -> elementName(semanticModelName)?.let(SemanticDefinitionReference::SemanticModel)
        is SemanticMetricFlowIssueSubject.ModelElement -> elementName(semanticModelName)?.let(SemanticDefinitionReference::SemanticModel)
    }

    private fun elementName(value: String): SemanticElementName? = runCatching { SemanticElementName(value) }.getOrNull()

    private fun identityProblems(savedName: SemanticElementName?, nameTaken: Boolean, draftName: SemanticElementName): List<SemanticCheckProblem> =
        listOfNotNull(
            if (savedName != null && savedName != draftName) problem(SemanticCheckProblemCode.NAME_IMMUTABLE, "name", null) else null,
            if (nameTaken) problem(SemanticCheckProblemCode.NAME_CONFLICT, "name", null) else null,
        )

    private fun problem(code: SemanticCheckProblemCode, field: String?, connectorId: ConnectorId?) =
        SemanticCheckProblem(code, field, connectorId?.takeIf { code in CONNECTOR_SCOPED_CODES }, null, null)

    private sealed interface CheckInputs

    private data class ModelCheckInputs(
        val definition: SemanticModelDefinition,
        val identityProblems: List<SemanticCheckProblem>,
        val sources: List<ModelSourceInput>,
        val dependentClosures: List<SemanticMetricClosure.Collected>,
        val modelsByName: Map<SemanticElementName, CurrentSemanticModel>,
        val timeSpineConfigurations: List<CurrentTimeSpineConfiguration>,
        val reportingTimeZone: ZoneId,
    ) : CheckInputs

    private data class ModelSourceInput(
        val index: Int,
        val connectorId: ConnectorId,
        val dialect: SemanticSqlDialect?,
        val connectorFound: Boolean,
        val catalog: CatalogCurrentObjectSet?,
    )

    private data class MetricCheckInputs(
        val draft: SemanticMetricDefinition,
        val identityProblems: List<SemanticCheckProblem>,
        val closure: SemanticMetricClosure,
        val candidates: List<MetricConnectorInput>,
        val modelsByName: Map<SemanticElementName, CurrentSemanticModel>,
        val timeSpineConfigurations: List<CurrentTimeSpineConfiguration>,
        val reportingTimeZone: ZoneId,
    ) : CheckInputs

    private data class MetricConnectorInput(
        val connectorId: ConnectorId,
        val dialect: SemanticSqlDialect?,
        val catalog: CatalogCurrentObjectSet?,
    )

    private companion object {
        val CHECK_TIME: Instant = Instant.EPOCH
        const val MAX_DETAIL_LENGTH = 512
        const val REPRESENTATIVE_ROW_LIMIT = 100
        val CONNECTOR_SCOPED_CODES = setOf(
            SemanticCheckProblemCode.CONNECTOR_NOT_FOUND,
            SemanticCheckProblemCode.SOURCE_RELATION_NOT_FOUND,
            SemanticCheckProblemCode.CATALOG_RELATION_NOT_FOUND,
            SemanticCheckProblemCode.TIME_SPINE_UNAVAILABLE,
            SemanticCheckProblemCode.RELATIONSHIP_JOIN_UNSUPPORTED,
            SemanticCheckProblemCode.UNSUPPORTED_DIALECT,
            SemanticCheckProblemCode.TIME_KIND_UNDECLARED,
            SemanticCheckProblemCode.TIME_KIND_MISMATCH,
            SemanticCheckProblemCode.TIME_SPINE_COLUMN_TYPE_INVALID,
        )

        fun representativeQuery(metricName: SemanticElementName, groupBys: List<SemanticGroupBy>) = SemanticQuerySpec(
            metrics = listOf(metricName),
            groupBys = groupBys,
            metricTimeRange = null,
            filters = emptyList(),
            orderBy = emptyList(),
            rowLimit = REPRESENTATIVE_ROW_LIMIT,
        )
    }
}

private fun SemanticInvalidReasonCode.toCheckProblemCode(): SemanticCheckProblemCode = when (this) {
    SemanticInvalidReasonCode.AGGREGATE_METRIC_INVALID,
    SemanticInvalidReasonCode.AGGREGATE_MEASURE_NOT_FOUND,
    -> error("Aggregate metric write failures cannot be definition check failures")
    SemanticInvalidReasonCode.MODEL_NAME_IMMUTABLE,
    SemanticInvalidReasonCode.METRIC_NAME_IMMUTABLE,
    -> SemanticCheckProblemCode.NAME_IMMUTABLE
    SemanticInvalidReasonCode.METRIC_MODEL_NOT_FOUND -> SemanticCheckProblemCode.METRIC_MODEL_NOT_FOUND
    SemanticInvalidReasonCode.METRIC_DEPENDENCY_NOT_FOUND -> SemanticCheckProblemCode.METRIC_DEPENDENCY_NOT_FOUND
    SemanticInvalidReasonCode.CATALOG_RELATION_NOT_FOUND -> SemanticCheckProblemCode.CATALOG_RELATION_NOT_FOUND
    SemanticInvalidReasonCode.SOURCE_RELATION_NOT_FOUND -> SemanticCheckProblemCode.SOURCE_RELATION_NOT_FOUND
    SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID -> SemanticCheckProblemCode.METRICFLOW_MANIFEST_INVALID
    SemanticInvalidReasonCode.TIME_KIND_MISMATCH -> SemanticCheckProblemCode.TIME_KIND_MISMATCH
    SemanticInvalidReasonCode.TIME_SPINE_COLUMN_TYPE_INVALID -> SemanticCheckProblemCode.TIME_SPINE_COLUMN_TYPE_INVALID
    SemanticInvalidReasonCode.QUERY_METRIC_NOT_FOUND,
    SemanticInvalidReasonCode.QUERY_GROUP_BY_NOT_FOUND,
    SemanticInvalidReasonCode.QUERY_FILTER_UNRENDERABLE,
    SemanticInvalidReasonCode.GROUP_BY_KIND_MISMATCH,
    SemanticInvalidReasonCode.UNKNOWN_FILTER_TARGET,
    -> SemanticCheckProblemCode.QUERY_UNRENDERABLE
}

private fun SemanticUnsupportedCapability.toCheckProblemCode(): SemanticCheckProblemCode = when (this) {
    SemanticUnsupportedCapability.RELATIONSHIP_JOIN -> SemanticCheckProblemCode.RELATIONSHIP_JOIN_UNSUPPORTED
    SemanticUnsupportedCapability.UNSUPPORTED_DIALECT -> SemanticCheckProblemCode.UNSUPPORTED_DIALECT
    SemanticUnsupportedCapability.TIME_SPINE_UNAVAILABLE -> SemanticCheckProblemCode.TIME_SPINE_UNAVAILABLE
}

private fun modelElementField(definition: SemanticModelDefinition, subject: SemanticMetricFlowIssueSubject?): String? {
    if (subject !is SemanticMetricFlowIssueSubject.ModelElement || subject.semanticModelName != definition.name.value) return null
    definition.entities.indexOfFirst { it.name.value == subject.elementName }.takeIf { it >= 0 }?.let { return "entities[$it]" }
    definition.dimensions.indexOfFirst { it.name.value == subject.elementName }.takeIf { it >= 0 }?.let { return "dimensions[$it]" }
    definition.measures.indexOfFirst { it.name.value == subject.elementName }.takeIf { it >= 0 }?.let { return "measures[$it]" }
    return null
}

/** The draft's own reference to a missing name, when the draft names it directly. */
internal fun directReferenceField(
    draft: SemanticMetricDefinition,
    code: SemanticInvalidReasonCode,
    missingName: SemanticElementName,
): String? {
    val inputs = draft.namedInputs()
    return when (code) {
        SemanticInvalidReasonCode.METRIC_DEPENDENCY_NOT_FOUND ->
            inputs.firstOrNull { (_, input) -> input.metricName == missingName }?.let { (path, _) -> "$path.metricName" }
        SemanticInvalidReasonCode.METRIC_MODEL_NOT_FOUND ->
            if (draft is SemanticMetricDefinition.Simple && draft.semanticModelName == missingName) {
                "semanticModelName"
            } else {
                draft.filters.indexOfFirst { it.references(missingName) }.takeIf { it >= 0 }?.let { "filters[$it]" }
                    ?: inputs.firstOrNull { (_, input) -> input.filter?.references(missingName) == true }?.let { (path, _) -> "$path.filter" }
            }
        else -> null
    }
}

private fun SemanticMetricDefinition.namedInputs(): List<Pair<String, SemanticMetricInput>> = when (this) {
    is SemanticMetricDefinition.Simple -> emptyList()
    is SemanticMetricDefinition.Ratio -> listOf("numerator" to numerator, "denominator" to denominator)
    is SemanticMetricDefinition.Derived -> inputMetrics.mapIndexed { index, input -> "inputMetrics[$index]" to input }
    is SemanticMetricDefinition.Cumulative -> listOf("inputMetric" to inputMetric)
    is SemanticMetricDefinition.Conversion -> listOf("baseMetric" to baseMetric, "conversionMetric" to conversionMetric)
}

private fun SemanticMetricFilter.references(modelName: SemanticElementName): Boolean = when (this) {
    is SemanticMetricFilter.Predicate -> dimension.semanticModelName == modelName
    is SemanticMetricFilter.And -> filters.any { it.references(modelName) }
    is SemanticMetricFilter.Or -> filters.any { it.references(modelName) }
    is SemanticMetricFilter.Not -> filter.references(modelName)
}
