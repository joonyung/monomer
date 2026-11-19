package cc.monomer.semantic.application

import cc.monomer.catalog.api.CatalogCurrentObject
import cc.monomer.catalog.api.CatalogCurrentObjectSet
import cc.monomer.catalog.api.CatalogObjectKind
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.SemanticPartitionCheck
import cc.monomer.semantic.api.SemanticPartitionFilter
import cc.monomer.semantic.api.SemanticPartitionFilterKind
import cc.monomer.semantic.api.MAXIMUM_SEMANTIC_ISSUE_MESSAGE_LENGTH
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.SemanticDefinitionIssue
import cc.monomer.semantic.api.SemanticDefinitionReference
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticExcludedModel
import cc.monomer.semantic.api.SemanticGroupBy
import cc.monomer.semantic.api.SemanticInvalidReason
import cc.monomer.semantic.api.SemanticInvalidReasonCode
import cc.monomer.semantic.api.SemanticMetricDefinition
import cc.monomer.semantic.api.SemanticModelDefinition
import cc.monomer.semantic.api.SemanticQueryOrderTarget
import cc.monomer.semantic.api.SemanticQuerySpec
import cc.monomer.semantic.api.SemanticRenderRequest
import cc.monomer.semantic.api.SemanticRenderResult
import cc.monomer.semantic.api.SemanticRenderedOutputColumn
import cc.monomer.semantic.api.SemanticRenderedOutputColumnSource
import cc.monomer.semantic.api.SemanticSourceRelation
import cc.monomer.semantic.api.SemanticSqlDialect
import cc.monomer.semantic.api.SemanticSqlRender
import cc.monomer.semantic.api.SemanticUnsupportedCapability
import cc.monomer.semantic.api.directMetricInputs
import cc.monomer.semantic.api.matchesCurrentAddress
import cc.monomer.semantic.api.referencedModelNames
import cc.monomer.semantic.domain.CurrentMetric
import cc.monomer.semantic.domain.CurrentSemanticModel
import cc.monomer.semantic.domain.CurrentTimeSpineConfiguration
import java.time.ZoneId
import org.slf4j.LoggerFactory

/** A request's current closure resolved on one connector, before MetricFlow validates it. */
internal sealed interface SemanticClosureProjection {
    data class NotFound(val metricName: SemanticElementName) : SemanticClosureProjection
    data class Invalid(
        val reasons: List<SemanticInvalidReason>,
        val definitionIssues: List<SemanticDefinitionIssue>,
    ) : SemanticClosureProjection

    /**
     * The closure's models keep their full definitions; candidates are measure-free and request-local.
     * A candidate whose time dimension declaration contradicts its Catalog type is left out up front.
     */
    data class Projected(
        val connectorId: ConnectorId,
        val dialect: SemanticSqlDialect,
        val closureModels: Map<SemanticElementName, SemanticMetricFlowModel>,
        val candidateModels: Map<SemanticElementName, SemanticMetricFlowModel>,
        val excludedCandidates: Map<SemanticElementName, String>,
        val metrics: Map<MetricId, SemanticMetricDefinition>,
        val timeSpines: List<SemanticMetricFlowTimeSpine>,
        val metricIds: List<MetricId>,
        val partitionFilters: List<SemanticPartitionFilter>,
        val reportingTimeZone: ZoneId,
    ) : SemanticClosureProjection {
        fun manifest(candidateNames: Collection<SemanticElementName>): SemanticMetricFlowManifest =
            SemanticMetricFlowManifest(
                connectorId = connectorId,
                dialect = dialect,
                models = closureModels + candidateModels.filterKeys { it in candidateNames },
                metrics = metrics,
                timeSpines = timeSpines,
                reportingTimeZone = reportingTimeZone,
            )
    }
}

/** The MetricFlow-valid manifest a query runs on, or why the request cannot run. */
internal sealed interface SemanticQueryResolution {
    data class NotFound(val metricName: SemanticElementName) : SemanticQueryResolution
    data class Invalid(
        val reasons: List<SemanticInvalidReason>,
        val definitionIssues: List<SemanticDefinitionIssue>,
    ) : SemanticQueryResolution
    data class Resolved(
        val manifest: SemanticMetricFlowManifest,
        val metricIds: List<MetricId>,
        val excludedModels: List<SemanticExcludedModel>,
        val partitionCheck: SemanticPartitionCheck,
    ) : SemanticQueryResolution
}

/** The connector-independent part of a metric request: every metric and model it reaches by name. */
internal sealed interface SemanticMetricClosure {
    data class NotFound(val metricName: SemanticElementName) : SemanticMetricClosure
    data class Unresolved(
        val code: SemanticInvalidReasonCode,
        val missingName: SemanticElementName,
        val referencingMetricName: SemanticElementName,
    ) : SemanticMetricClosure
    data class Collected(
        val requestedMetricIds: List<MetricId>,
        val metrics: Map<MetricId, CurrentMetric>,
        val models: Map<SemanticElementName, CurrentSemanticModel>,
    ) : SemanticMetricClosure
}

internal fun collectMetricClosure(
    metricNames: List<SemanticElementName>,
    currentMetricsByName: Map<SemanticElementName, CurrentMetric>,
    currentModelsByName: Map<SemanticElementName, CurrentSemanticModel>,
): SemanticMetricClosure {
    val requested = metricNames.map { currentMetricsByName[it] ?: return SemanticMetricClosure.NotFound(it) }
    val metrics = linkedMapOf<MetricId, CurrentMetric>()
    val models = linkedMapOf<SemanticElementName, CurrentSemanticModel>()
    val pendingMetrics = ArrayDeque(requested)
    while (pendingMetrics.isNotEmpty()) {
        val metric = pendingMetrics.removeFirst()
        if (metrics.putIfAbsent(metric.id, metric) != null) continue
        metric.definition.directMetricInputs().forEach { input ->
            pendingMetrics += currentMetricsByName[input.metricName]
                ?: return SemanticMetricClosure.Unresolved(
                    SemanticInvalidReasonCode.METRIC_DEPENDENCY_NOT_FOUND,
                    input.metricName,
                    metric.definition.name,
                )
        }
        metric.definition.referencedModelNames().forEach { modelName ->
            models.putIfAbsent(
                modelName,
                currentModelsByName[modelName]
                    ?: return SemanticMetricClosure.Unresolved(
                        SemanticInvalidReasonCode.METRIC_MODEL_NOT_FOUND,
                        modelName,
                        metric.definition.name,
                    ),
            )
        }
    }
    return SemanticMetricClosure.Collected(requested.map(CurrentMetric::id), metrics, models)
}

/** Collects the requested metrics' closure and resolves it on the connector's current Catalog, if one is loaded. */
internal fun projectCurrentClosure(
    workspaceId: WorkspaceId,
    connectorId: ConnectorId,
    metricNames: List<SemanticElementName>,
    catalog: CatalogCurrentObjectSet?,
    dialect: SemanticSqlDialect,
    timeSpineConfigurations: List<CurrentTimeSpineConfiguration>,
    currentMetricsByName: Map<SemanticElementName, CurrentMetric>,
    currentModelsByName: Map<SemanticElementName, CurrentSemanticModel>,
    reportingTimeZone: ZoneId,
): SemanticClosureProjection = when (val closure = collectMetricClosure(metricNames, currentMetricsByName, currentModelsByName)) {
    is SemanticMetricClosure.NotFound -> SemanticClosureProjection.NotFound(closure.metricName)
    is SemanticMetricClosure.Unresolved -> closureInvalid(closure.code, closure.unresolvedIssue())
    is SemanticMetricClosure.Collected ->
        if (catalog == null) {
            closureInvalid(
                SemanticInvalidReasonCode.CATALOG_RELATION_NOT_FOUND,
                SemanticDefinitionIssue(
                    SemanticInvalidReasonCode.CATALOG_RELATION_NOT_FOUND,
                    null,
                    "This connector has no tables loaded in Catalog yet.",
                ),
            )
        } else {
            projectMetricClosure(
                workspaceId, connectorId, closure, catalog, dialect, timeSpineConfigurations, currentModelsByName, reportingTimeZone,
            )
        }
}

/** Resolves a collected closure and the Workspace's candidate models on one connector's current Catalog. */
internal fun projectMetricClosure(
    workspaceId: WorkspaceId,
    connectorId: ConnectorId,
    closure: SemanticMetricClosure.Collected,
    catalog: CatalogCurrentObjectSet,
    dialect: SemanticSqlDialect,
    timeSpineConfigurations: List<CurrentTimeSpineConfiguration>,
    currentModelsByName: Map<SemanticElementName, CurrentSemanticModel>,
    reportingTimeZone: ZoneId,
): SemanticClosureProjection {
    val closureModels = linkedMapOf<SemanticElementName, SemanticMetricFlowModel>()
    closure.models.forEach { (modelName, model) ->
        val source = model.definition.sourceRelationFor(connectorId)
            ?: return closureInvalid(
                SemanticInvalidReasonCode.SOURCE_RELATION_NOT_FOUND,
                SemanticDefinitionIssue(
                    SemanticInvalidReasonCode.SOURCE_RELATION_NOT_FOUND,
                    SemanticDefinitionReference.SemanticModel(modelName),
                    issueMessage("Semantic model `$modelName` has no source table on this connector."),
                ),
            )
        val catalogObject = resolveModelSource(model.definition, connectorId, catalog)
            ?: return closureInvalid(
                SemanticInvalidReasonCode.CATALOG_RELATION_NOT_FOUND,
                SemanticDefinitionIssue(
                    SemanticInvalidReasonCode.CATALOG_RELATION_NOT_FOUND,
                    SemanticDefinitionReference.SemanticModel(modelName),
                    unresolvedSourceMessage(modelName, source, catalog),
                ),
            )
        val timeDimensions = when (val resolved = resolvedTimeDimensions(modelName, model.definition, catalogObject, catalog, dialect)) {
            is ModelTimeDimensions.Resolved -> resolved.timeDimensions
            is ModelTimeDimensions.Mismatched -> return closureInvalid(
                SemanticInvalidReasonCode.TIME_KIND_MISMATCH,
                SemanticDefinitionIssue(
                    SemanticInvalidReasonCode.TIME_KIND_MISMATCH,
                    SemanticDefinitionReference.SemanticModel(modelName),
                    resolved.message,
                ),
            )
        }
        closureModels[modelName] = SemanticMetricFlowModel(model.definition, catalogObject.address, timeDimensions)
    }
    invalidTimeSpineColumns(timeSpineConfigurations, workspaceId, connectorId, catalog, dialect).firstOrNull()?.let { message ->
        return closureInvalid(
            SemanticInvalidReasonCode.TIME_SPINE_COLUMN_TYPE_INVALID,
            SemanticDefinitionIssue(SemanticInvalidReasonCode.TIME_SPINE_COLUMN_TYPE_INVALID, null, message),
        )
    }
    val candidateModels = linkedMapOf<SemanticElementName, SemanticMetricFlowModel>()
    val excludedCandidates = linkedMapOf<SemanticElementName, String>()
    currentModelsByName.forEach { (modelName, model) ->
        if (model.workspaceId != workspaceId || modelName in closureModels) return@forEach
        val catalogObject = resolveModelSource(model.definition, connectorId, catalog) ?: return@forEach
        when (val resolved = resolvedTimeDimensions(modelName, model.definition, catalogObject, catalog, dialect)) {
            is ModelTimeDimensions.Resolved -> candidateModels[modelName] = SemanticMetricFlowModel(
                model.definition.copy(measures = emptyList()), catalogObject.address, resolved.timeDimensions,
            )
            is ModelTimeDimensions.Mismatched -> excludedCandidates[modelName] = resolved.message
        }
    }
    return SemanticClosureProjection.Projected(
        connectorId = connectorId,
        dialect = dialect,
        closureModels = closureModels,
        candidateModels = candidateModels,
        excludedCandidates = excludedCandidates,
        metrics = closure.metrics.mapValues { it.value.definition },
        timeSpines = projectCurrentTimeSpines(timeSpineConfigurations, workspaceId, connectorId, catalog),
        metricIds = closure.requestedMetricIds,
        partitionFilters = if (dialect == SemanticSqlDialect.BIGQUERY) buildList {
            (closure.models + currentModelsByName.filterKeys { it in candidateModels }).forEach { (name, model) ->
                sourcePartitionInfo(model.definition, connectorId, catalog)?.requiredFilter(name)?.let(::add)
            }
            timeSpineConfigurations.filter { it.workspaceId == workspaceId && it.connectorId == connectorId }
                .flatMap { it.definition.timeSpines }.forEach { spine ->
                    resolveCatalogRelation(catalog.objects, spine.relation.catalog, spine.relation.schema, spine.relation.relation)
                        ?.timeSpinePartitionFilter(connectorId)?.let(::add)
                }
        } else emptyList(),
        reportingTimeZone = reportingTimeZone,
    )
}

private sealed interface ModelTimeDimensions {
    data class Resolved(val timeDimensions: Map<SemanticElementName, SemanticMetricFlowTimeDimension>) : ModelTimeDimensions
    data class Mismatched(val message: String) : ModelTimeDimensions
}

private fun resolvedTimeDimensions(
    modelName: SemanticElementName,
    definition: SemanticModelDefinition,
    relation: CatalogCurrentObject,
    catalog: CatalogCurrentObjectSet,
    dialect: SemanticSqlDialect,
): ModelTimeDimensions {
    val resolutions = timeDimensionResolutions(definition, relation, catalog, dialect)
    resolutions.forEach { (dimensionName, resolution) ->
        if (resolution is SemanticTimeDimensionResolution.Mismatch) {
            return ModelTimeDimensions.Mismatched(timeKindMismatchMessage(modelName, dimensionName, resolution))
        }
    }
    return ModelTimeDimensions.Resolved(
        resolutions.mapValues { (_, resolution) -> (resolution as SemanticTimeDimensionResolution.Resolved).timeDimension },
    )
}

internal fun timeKindMismatchMessage(
    modelName: SemanticElementName,
    dimensionName: SemanticElementName,
    mismatch: SemanticTimeDimensionResolution.Mismatch,
): String = issueMessage(
    "Semantic model `$modelName` declares timeKind `${mismatch.declared.name.lowercase()}` for time dimension " +
        "`$dimensionName`, but its Catalog type is `${mismatch.nativeType}`.",
)

/**
 * Validates the closure with every candidate model. When that fails but the closure alone is valid,
 * the first candidate the engine names is left out and the rest are validated again, one candidate per
 * round: a conflict finding names only its later model, which may be valid once an earlier partner is
 * gone. Some findings name no model at all; then the remaining candidates are added back to the closure
 * one at a time and each one that makes the manifest invalid is left out, so only the candidate that
 * breaks it is blamed. A closure the engine rejects fails the request.
 */
internal fun SemanticMetricFlowPort.resolveQuery(projection: SemanticClosureProjection): SemanticQueryResolution {
    val projected = when (projection) {
        is SemanticClosureProjection.NotFound -> return SemanticQueryResolution.NotFound(projection.metricName)
        is SemanticClosureProjection.Invalid ->
            return SemanticQueryResolution.Invalid(projection.reasons, projection.definitionIssues)
        is SemanticClosureProjection.Projected -> projection
    }
    val complete = projected.manifest(projected.candidateModels.keys)
    var findings = when (val validation = validate(complete)) {
        SemanticMetricFlowValidationResult.Valid -> return projected.resolved(complete, projected.excludedCandidates)
        is SemanticMetricFlowValidationResult.Invalid ->
            if (projected.candidateModels.isEmpty()) return validation.toQueryInvalid(complete) else validation.issues
    }
    val closureOnly = projected.manifest(emptySet())
    val closureValidation = validate(closureOnly)
    if (closureValidation is SemanticMetricFlowValidationResult.Invalid) return closureValidation.toQueryInvalid(closureOnly)
    val excluded = LinkedHashMap(projected.excludedCandidates)
    var remaining = projected.candidateModels.keys.toList()
    while (true) {
        val named = findings.firstNotNullOfOrNull { issue -> issue.namedModel(remaining)?.let { name -> name to issue.message } }
        if (named == null) {
            val kept = mutableListOf<SemanticElementName>()
            remaining.forEach { candidate ->
                when (val validation = validate(projected.manifest(kept + candidate))) {
                    SemanticMetricFlowValidationResult.Valid -> kept += candidate
                    is SemanticMetricFlowValidationResult.Invalid -> excluded[candidate] = (
                        validation.issues.firstOrNull { it.namedModel(listOf(candidate)) != null } ?: validation.issues.firstOrNull()
                    )?.message ?: ENGINE_REJECTED_CANDIDATE_MESSAGE
                }
            }
            return projected.resolved(projected.manifest(kept), excluded)
        }
        excluded[named.first] = named.second
        remaining = remaining - named.first
        if (remaining.isEmpty()) return projected.resolved(closureOnly, excluded)
        val manifest = projected.manifest(remaining)
        findings = when (val validation = validate(manifest)) {
            SemanticMetricFlowValidationResult.Valid -> return projected.resolved(manifest, excluded)
            is SemanticMetricFlowValidationResult.Invalid -> validation.issues
        }
    }
}

private fun SemanticClosureProjection.Projected.resolved(
    manifest: SemanticMetricFlowManifest,
    excluded: Map<SemanticElementName, String>,
): SemanticQueryResolution.Resolved {
    if (excluded.isNotEmpty()) {
        logger.atInfo()
            .addKeyValue("event", "semantic_candidate_models_excluded")
            .addKeyValue("connector_id", connectorId.toString())
            .addKeyValue("excluded", excluded.size)
            .addKeyValue("kept", manifest.models.size - closureModels.size)
            .log("semantic_candidate_models_excluded")
    }
    return SemanticQueryResolution.Resolved(
        manifest = manifest,
        metricIds = metricIds,
        excludedModels = excluded.map { (name, message) -> SemanticExcludedModel(name, issueMessage(message)) },
        partitionCheck = partitionFilters.filter { it.kind == SemanticPartitionFilterKind.TIME_SPINE || it.semanticModelName in manifest.models }
            .let { SemanticPartitionCheck(it.isNotEmpty(), it) },
    )
}

private fun SemanticMetricFlowIssue.namedModel(candidates: List<SemanticElementName>): SemanticElementName? {
    val modelName = when (val issueSubject = subject) {
        is SemanticMetricFlowIssueSubject.Model -> issueSubject.semanticModelName
        is SemanticMetricFlowIssueSubject.ModelElement -> issueSubject.semanticModelName
        is SemanticMetricFlowIssueSubject.Metric, null -> return null
    }
    return candidates.firstOrNull { it.value == modelName }
}

private fun SemanticMetricFlowValidationResult.Invalid.toQueryInvalid(
    manifest: SemanticMetricFlowManifest,
): SemanticQueryResolution.Invalid = SemanticQueryResolution.Invalid(
    reasons = reasons.map { reason -> SemanticInvalidReason(reason.code, reason.field) },
    definitionIssues = issues.map { issue ->
        SemanticDefinitionIssue(
            SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID,
            issue.definitionIn(manifest),
            issueMessage(issue.message),
        )
    }.distinct(),
)

private fun SemanticMetricFlowIssue.definitionIn(manifest: SemanticMetricFlowManifest): SemanticDefinitionReference? =
    when (val issueSubject = subject) {
        is SemanticMetricFlowIssueSubject.Metric ->
            manifest.metrics.values.firstOrNull { it.name.value == issueSubject.metricName }
                ?.let { metric -> SemanticDefinitionReference.Metric(metric.name) }
        is SemanticMetricFlowIssueSubject.Model -> manifest.modelReference(issueSubject.semanticModelName)
        is SemanticMetricFlowIssueSubject.ModelElement -> manifest.modelReference(issueSubject.semanticModelName)
        null -> null
    }

private fun SemanticMetricFlowManifest.modelReference(modelName: String): SemanticDefinitionReference? =
    models.keys.firstOrNull { it.value == modelName }?.let(SemanticDefinitionReference::SemanticModel)

private fun SemanticMetricClosure.Unresolved.unresolvedIssue(): SemanticDefinitionIssue = SemanticDefinitionIssue(
    code,
    SemanticDefinitionReference.Metric(referencingMetricName),
    issueMessage(
        when (code) {
            SemanticInvalidReasonCode.METRIC_MODEL_NOT_FOUND ->
                "Metric `$referencingMetricName` uses semantic model `$missingName`, which doesn't exist."
            else -> "Metric `$referencingMetricName` uses metric `$missingName`, which doesn't exist."
        },
    ),
)

private fun closureInvalid(code: SemanticInvalidReasonCode, issue: SemanticDefinitionIssue) =
    SemanticClosureProjection.Invalid(listOf(SemanticInvalidReason(code, null)), listOf(issue))

private fun unresolvedSourceMessage(
    modelName: SemanticElementName,
    source: SemanticSourceRelation,
    catalog: CatalogCurrentObjectSet,
): String {
    val relation = listOfNotNull(source.catalog, source.schema, source.relation).joinToString(".")
    val matches = catalog.objects.count { current ->
        current.kind == CatalogObjectKind.RELATION && source.matchesCurrentAddress(current.address)
    }
    return issueMessage(
        if (matches > 1) {
            "Semantic model `$modelName` reads $relation, which matches more than one table in this connector's Catalog."
        } else {
            "Semantic model `$modelName` reads $relation, which isn't loaded in this connector's Catalog."
        },
    )
}

private fun issueMessage(message: String): String = message.take(MAXIMUM_SEMANTIC_ISSUE_MESSAGE_LENGTH)

internal fun resolveModelSource(
    definition: SemanticModelDefinition,
    connectorId: ConnectorId,
    catalog: CatalogCurrentObjectSet,
): CatalogCurrentObject? {
    val address = definition.sourceRelationFor(connectorId) ?: return null
    return catalog.objects.singleOrNull { current ->
        current.kind == CatalogObjectKind.RELATION && address.matchesCurrentAddress(current.address)
    }
}

/** Renders on a validated manifest; an Invalid result names the left-out models the query still selects. */
internal fun SemanticMetricFlowPort.renderResolved(
    request: SemanticRenderRequest,
    resolution: SemanticQueryResolution.Resolved,
): SemanticRenderResult = when (val rendered = renderValidated(this, request, resolution.manifest, resolution.metricIds)) {
    is SemanticRenderResult.Invalid -> {
        val selectedModels = request.query.selectedModelNames()
        rendered.copy(
            definitionIssues = rendered.definitionIssues + resolution.excludedModels
                .filter { it.name in selectedModels }
                .map { excluded ->
                    SemanticDefinitionIssue(
                        SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID,
                        SemanticDefinitionReference.SemanticModel(excluded.name),
                        excluded.message,
                    )
                },
        )
    }
    is SemanticRenderResult.Rendered -> rendered.copy(render = rendered.render.copy(partitionCheck = resolution.partitionCheck))
    else -> rendered
}

private fun SemanticQuerySpec.selectedModelNames(): Set<SemanticElementName> = buildSet {
    groupBys.forEach { groupBy -> groupBy.modelName()?.let(::add) }
    filters.forEach { filter -> add(filter.dimension.semanticModelName) }
    orderBy.forEach { order -> (order.target as? SemanticQueryOrderTarget.GroupBy)?.groupBy?.modelName()?.let(::add) }
}

private fun SemanticGroupBy.modelName(): SemanticElementName? = when (this) {
    is SemanticGroupBy.Dimension -> semanticModelName
    is SemanticGroupBy.Entity -> semanticModelName
    is SemanticGroupBy.MetricTime -> null
}

internal fun renderValidated(
    metricFlowPort: SemanticMetricFlowPort,
    request: SemanticRenderRequest,
    manifest: SemanticMetricFlowManifest,
    metricIds: List<MetricId>,
): SemanticRenderResult = when (val rendered = metricFlowPort.render(manifest, request.query, metricIds)) {
    is SemanticMetricFlowRenderResult.Invalid ->
        SemanticRenderResult.Invalid(
            rendered.reasons.map { reason -> SemanticInvalidReason(reason.code, reason.field) },
            emptyList(),
        )
    is SemanticMetricFlowRenderResult.Unsupported ->
        SemanticRenderResult.Unsupported(rendered.capabilities.toPublicCapabilities())
    is SemanticMetricFlowRenderResult.Rendered -> SemanticRenderResult.Rendered(
        SemanticSqlRender(
            workspaceId = request.workspaceId,
            dialect = rendered.dialect,
            sql = rendered.sql,
            reportingTimeZone = manifest.reportingTimeZone,
            partitionCheck = SemanticPartitionCheck(false, emptyList()),
            outputColumns = rendered.outputColumns.map { column ->
                SemanticRenderedOutputColumn(
                    source = when (val source = column.source) {
                        is SemanticMetricFlowOutputColumnSource.Metric ->
                            SemanticRenderedOutputColumnSource.Metric(source.metricId)
                        is SemanticMetricFlowOutputColumnSource.GroupBy ->
                            SemanticRenderedOutputColumnSource.GroupBy(source.groupBy)
                    },
                    name = column.name,
                    sqlName = column.sqlName,
                    display = (column.source as? SemanticMetricFlowOutputColumnSource.Metric)?.let { manifest.metrics.getValue(it.metricId).display },
                )
            },
        ),
    )
}

internal fun Set<SemanticMetricFlowUnsupportedCapability>.toPublicCapabilities(): Set<SemanticUnsupportedCapability> =
    mapTo(mutableSetOf()) { capability ->
        when (capability) {
            SemanticMetricFlowUnsupportedCapability.RELATIONSHIP_JOIN -> SemanticUnsupportedCapability.RELATIONSHIP_JOIN
            SemanticMetricFlowUnsupportedCapability.TIME_SPINE_UNAVAILABLE -> SemanticUnsupportedCapability.TIME_SPINE_UNAVAILABLE
        }
    }

private const val ENGINE_REJECTED_CANDIDATE_MESSAGE = "MetricFlow can't use this semantic model with the selected metrics."

private val logger = LoggerFactory.getLogger("cc.monomer.semantic.application.SemanticClosureResolution")
