@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.semantic.adapter.outbound.metricflow

import cc.monomer.metricflow.domain.query.MetricFlowQueryParser
import cc.monomer.metricflow.application.engine.MetricFlowEngine
import cc.monomer.metricflow.application.engine.MetricFlowExplainRequest
import cc.monomer.metricflow.application.engine.MetricFlowExplainResult
import cc.monomer.metricflow.common.errors.FeatureNotSupportedError
import cc.monomer.metricflow.common.errors.InvalidManifestException
import cc.monomer.metricflow.common.errors.InvalidQueryException
import cc.monomer.metricflow.common.errors.MetricDefinitionDependencyError
import cc.monomer.metricflow.common.errors.MetricFlowInternalError
import cc.monomer.metricflow.common.errors.RenderSqlTemplateException
import cc.monomer.metricflow.common.errors.SemanticManifestConfigurationError
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.validation.DefaultValidationRules
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationResults
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationRule
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidator
import cc.monomer.metricflow.domain.sql.render.SqlEngine
import cc.monomer.metricflow.domain.spec.DunderColumnAssociationResolver
import cc.monomer.semantic.api.resultColumns
import cc.monomer.semantic.api.resultColumnKey
import cc.monomer.semantic.api.metricResultColumnKey
import cc.monomer.semantic.api.SemanticDimensionKind
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticGroupBy
import cc.monomer.semantic.api.SemanticInvalidReasonCode
import cc.monomer.semantic.api.SemanticQueryOrderDirection
import cc.monomer.semantic.api.SemanticQueryOrderTarget
import cc.monomer.semantic.api.SemanticQuerySpec
import cc.monomer.semantic.api.SemanticSqlDialect
import cc.monomer.semantic.api.SemanticTimeRangeEndpoint
import cc.monomer.semantic.application.SemanticMetricFlowInvalidReason
import cc.monomer.semantic.application.SemanticMetricFlowIssue
import cc.monomer.semantic.application.SemanticMetricFlowIssueSubject
import cc.monomer.semantic.application.SemanticMetricFlowManifest
import cc.monomer.semantic.application.SemanticMetricFlowEngineFaultException
import cc.monomer.semantic.application.SemanticMetricFlowInvalidQueryException
import cc.monomer.semantic.application.SemanticMetricFlowOutputColumn
import cc.monomer.semantic.application.SemanticMetricFlowOutputColumnSource
import cc.monomer.semantic.application.SemanticMetricFlowPort
import cc.monomer.semantic.application.SemanticMetricFlowRenderResult
import cc.monomer.semantic.application.SemanticMetricFlowGroupByResult
import cc.monomer.semantic.application.SemanticMetricFlowUnsupportedCapability
import cc.monomer.semantic.application.SemanticMetricFlowUnsupportedException
import cc.monomer.semantic.application.SemanticMetricFlowValidationResult
import cc.monomer.semantic.application.isDayOrCoarser
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import org.slf4j.LoggerFactory

internal fun interface MetricFlowExplainQuery {
    fun explain(engine: MetricFlowEngine, request: MetricFlowExplainRequest): MetricFlowExplainResult
}

/** Stateless renderer from the product DSL to the pinned MetricFlow engine. */
class MetricFlowSemanticRenderer internal constructor(
    private val explainQuery: MetricFlowExplainQuery,
    private val validationRules: List<SemanticManifestValidationRule>,
) : SemanticMetricFlowPort {
    internal constructor(explainQuery: MetricFlowExplainQuery) : this(explainQuery, DefaultValidationRules)

    companion object {
        private val logger = LoggerFactory.getLogger(MetricFlowSemanticRenderer::class.java)

        fun production(): MetricFlowSemanticRenderer =
            MetricFlowSemanticRenderer(
                MetricFlowExplainQuery { engine, request -> engine.explain(request) },
            )
    }

    override fun validate(manifest: SemanticMetricFlowManifest): SemanticMetricFlowValidationResult {
        val started = System.nanoTime()
        try {
            return validateManifest(manifest)
        } finally {
            logger.atInfo()
                .addKeyValue("event", "semantic_metricflow_timing")
                .addKeyValue("stage", "validate")
                .addKeyValue("wall_ms", (System.nanoTime() - started) / 1_000_000)
                .log("semantic_metricflow_timing")
        }
    }

    private fun validateManifest(manifest: SemanticMetricFlowManifest): SemanticMetricFlowValidationResult {
        return try {
            val engineManifest = MetricFlowManifestMapper.toEngineManifest(manifest)
            val validation = validateEngineManifest(engineManifest, manifest.metrics.isNotEmpty())
            if (validation.hasBlockingIssues) {
                manifestInvalid(validation.errors.map { error -> error.toIssue() })
            } else {
                SemanticMetricFlowValidationResult.Valid
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RenderSqlTemplateException) {
            throw SemanticMetricFlowEngineFaultException(failure)
        } catch (failure: MetricFlowInternalError) {
            throw SemanticMetricFlowEngineFaultException(failure)
        } catch (failure: FeatureNotSupportedError) {
            throw SemanticMetricFlowUnsupportedException(failure)
        } catch (failure: InvalidQueryException) {
            throw SemanticMetricFlowInvalidQueryException(SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID, failure)
        } catch (_: InvalidManifestException) {
            manifestInvalid(emptyList())
        } catch (_: MetricDefinitionDependencyError) {
            manifestInvalid(
                listOf(SemanticMetricFlowIssue("Metric inputs form a cycle or are more than 100 levels deep.", null)),
            )
        } catch (_: IllegalArgumentException) {
            manifestInvalid(emptyList())
        } catch (_: IllegalStateException) {
            manifestInvalid(emptyList())
        }
    }

    private fun validateEngineManifest(
        manifest: SemanticManifest,
        hasMetrics: Boolean,
    ): SemanticManifestValidationResults {
        // A model check without dependent metrics omits the engine's "no metrics present" rule.
        val rules = if (hasMetrics) validationRules else validationRules
            .filterNot { it === cc.monomer.metricflow.domain.manifest.validation.rules.NonEmptyRule }
        var originalFailure: Throwable? = null
        // The engine validator erases thrown types into findings; retain these failures before it does.
        val validator = SemanticManifestValidator(rules.map { rule ->
            SemanticManifestValidationRule { engineManifest ->
                try {
                    rule.validateManifest(engineManifest)
                } catch (failure: Throwable) {
                    if (failure is CancellationException ||
                        originalFailure !is CancellationException && failure.isMetricFlowEngineFault() ||
                        originalFailure == null && failure.requiresMetricFlowFailureMapping()
                    ) {
                        originalFailure = failure
                    }
                    throw failure
                }
            }
        })
        val validation = validator.validate(manifest)
        originalFailure?.let { throw it }
        return validation
    }

    private fun manifestInvalid(issues: List<SemanticMetricFlowIssue>): SemanticMetricFlowValidationResult =
        SemanticMetricFlowValidationResult.Invalid(
            listOf(SemanticMetricFlowInvalidReason(SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID, null)),
            issues,
        )

    override fun listGroupBys(
        manifest: SemanticMetricFlowManifest,
        metricIds: List<cc.monomer.semantic.api.MetricId>,
    ): SemanticMetricFlowGroupByResult {
        val metricNamesById = manifest.metrics.mapValues { (_, metric) -> metric.name.value }
        if (metricIds.any { it !in metricNamesById }) {
            return SemanticMetricFlowGroupByResult.Invalid(
                listOf(SemanticMetricFlowInvalidReason(SemanticInvalidReasonCode.QUERY_METRIC_NOT_FOUND, null)),
            )
        }
        val engine = metricFlowCall(SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID, { return it.toGroupByResult() }) {
            MetricFlowEngine(MetricFlowManifestMapper.toEngineManifest(manifest), ControlMetricFlowSqlPlanRendererRegistry)
        }
        val validation = metricFlowCall(SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID, { return it.toGroupByResult() }) {
            validateEngineManifest(engine.semanticManifest, true)
        }
        if (validation.hasBlockingIssues) {
            return MetricFlowCallFailure.Invalid(SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID).toGroupByResult()
        }
        val uniqueNames = metricFlowCall(SemanticInvalidReasonCode.QUERY_METRIC_NOT_FOUND, { return it.toGroupByResult() }) {
            engine.exactGroupByNames(metricIds.map(metricNamesById::getValue))
        }
        val resolvedEngine = metricFlowCall(SemanticInvalidReasonCode.QUERY_FILTER_UNRENDERABLE, { return it.toGroupByResult() }) {
            MetricFlowEngine(MetricFlowManifestMapper.toEngineManifest(manifest, engine.exactGroupByNamesByMetric(manifest)), ControlMetricFlowSqlPlanRendererRegistry)
        }
        val metricNames = metricIds.map(metricNamesById::getValue)
        val parser = metricFlowCall(SemanticInvalidReasonCode.QUERY_FILTER_UNRENDERABLE, { return it.toGroupByResult() }) {
            MetricFlowQueryParser(resolvedEngine.semanticManifestLookup, resolvedEngine.semanticManifestGraphLookup)
        }
        val metricTimeName = uniqueNames.entries.firstOrNull { it.key is SemanticGroupBy.MetricTime }?.value
        fun resolveModels(groupByNames: List<String>): Set<SemanticElementName>? {
            val resolution = parser.parseAndValidateQuery(
                metricNames, emptyList(), groupByNames, emptyList(), null, null, null,
                emptyList(), emptyList(), emptyList(), emptyList(), false, true,
            )
            return if (resolution.hasErrors) null else resolution.queriedSemanticModels
                .mapTo(linkedSetOf()) { SemanticElementName(it.semanticModelName) }
        }
        fun metadataModels(names: List<String>): Set<SemanticElementName>? = resolveModels(names)
            ?: metricTimeName?.let { resolveModels((names + it).distinct()) }
        val candidates = metricFlowCall(SemanticInvalidReasonCode.QUERY_FILTER_UNRENDERABLE, { return it.toGroupByResult() }) {
            metadataModels(emptyList()) ?: return MetricFlowCallFailure.Invalid(SemanticInvalidReasonCode.QUERY_FILTER_UNRENDERABLE).toGroupByResult()
            uniqueNames.mapNotNull { (selector, name) ->
                val models = metadataModels(listOf(name)) ?: return@mapNotNull null
                selector.takeIf { it is SemanticGroupBy.MetricTime || it.modelName() in models }
            }
        }
        return SemanticMetricFlowGroupByResult.Listed(candidates)
    }

    override fun render(
        manifest: SemanticMetricFlowManifest,
        querySpec: SemanticQuerySpec,
        metricIds: List<cc.monomer.semantic.api.MetricId>,
    ): SemanticMetricFlowRenderResult {
        val started = System.nanoTime()
        try {
            return renderQuery(manifest, querySpec, metricIds)
        } finally {
            logger.atInfo()
                .addKeyValue("event", "semantic_metricflow_timing")
                .addKeyValue("stage", "render")
                .addKeyValue("wall_ms", (System.nanoTime() - started) / 1_000_000)
                .log("semantic_metricflow_timing")
        }
    }

    private fun renderQuery(
        manifest: SemanticMetricFlowManifest,
        querySpec: SemanticQuerySpec,
        metricIds: List<cc.monomer.semantic.api.MetricId>,
    ): SemanticMetricFlowRenderResult {
        invalidQueryReason(manifest, querySpec, metricIds)?.let { reason ->
            return SemanticMetricFlowRenderResult.Invalid(
                listOf(reason),
            )
        }
        val resultColumns = querySpec.resultColumns()
        val resultProjection = MetricFlowResultProjection(
            columns = resultColumns,
            orderByColumns = querySpec.orderBy.map { order ->
                val key = when (val target = order.target) {
                    is SemanticQueryOrderTarget.Metric -> target.metricName.metricResultColumnKey()
                    is SemanticQueryOrderTarget.GroupBy -> target.groupBy.resultColumnKey()
                }
                resultColumns.single { it.key == key }
            },
            calendarDateColumns = querySpec.groupBys.filter { groupBy ->
                when (groupBy) {
                    is SemanticGroupBy.Dimension -> groupBy.granularity?.isDayOrCoarser() == true
                    is SemanticGroupBy.MetricTime -> groupBy.granularity.isDayOrCoarser()
                    is SemanticGroupBy.Entity -> false
                }
            }.mapTo(mutableSetOf()) { it.resultColumnKey() },
        )
        val engine = metricFlowCall(SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID, { return it.toRenderResult() }) {
            MetricFlowEngine(MetricFlowManifestMapper.toEngineManifest(manifest), resultProjection)
        }
        val validation = metricFlowCall(SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID, { return it.toRenderResult() }) {
            validateEngineManifest(engine.semanticManifest, true)
        }
        if (validation.hasBlockingIssues) {
            return MetricFlowCallFailure.Invalid(SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID).toRenderResult()
        }
        val exactNames = metricFlowCall(SemanticInvalidReasonCode.QUERY_METRIC_NOT_FOUND, { return it.toRenderResult() }) {
            engine.exactGroupByNames(querySpec.metrics.map(SemanticElementName::value))
        }
        querySpec.groupBys.forEachIndexed { index, selector ->
            if (selector !in exactNames) return SemanticMetricFlowRenderResult.Invalid(
                listOf(SemanticMetricFlowInvalidReason(SemanticInvalidReasonCode.QUERY_GROUP_BY_NOT_FOUND, "groupBys[$index]")),
            )
        }
        querySpec.filters.forEachIndexed { index, filter ->
            if (filter.dimension.toGroupBy(manifest) !in exactNames) return SemanticMetricFlowRenderResult.Invalid(
                listOf(SemanticMetricFlowInvalidReason(SemanticInvalidReasonCode.UNKNOWN_FILTER_TARGET, "filters[$index]")),
            )
        }
        val resolvedEngine = metricFlowCall(SemanticInvalidReasonCode.QUERY_FILTER_UNRENDERABLE, { return it.toRenderResult() }) {
            MetricFlowEngine(MetricFlowManifestMapper.toEngineManifest(manifest, engine.exactGroupByNamesByMetric(manifest)), resultProjection)
        }
        val explainResult = metricFlowCall(SemanticInvalidReasonCode.QUERY_FILTER_UNRENDERABLE, { return it.toRenderResult() }) {
            val whereConstraints = querySpec.filters.map { it.toMetricFlowFilter(manifest, exactNames) }
            val explainRequest = MetricFlowExplainRequest(
                metricNames = querySpec.metrics.map(SemanticElementName::value),
                groupByNames = querySpec.groupBys.map(exactNames::getValue),
                whereConstraints = whereConstraints,
                orderByNames = querySpec.orderBy.map { order ->
                    val name = when (val target = order.target) {
                        is SemanticQueryOrderTarget.Metric -> target.metricName.value
                        is SemanticQueryOrderTarget.GroupBy -> exactNames.getValue(target.groupBy)
                    }
                    if (order.direction == SemanticQueryOrderDirection.DESC) "-$name" else name
                },
                limit = querySpec.rowLimit,
                timeConstraintStart = querySpec.metricTimeRange?.start?.metricFlowValue(manifest.reportingTimeZone),
                timeConstraintEnd = querySpec.metricTimeRange?.end?.metricFlowValue(manifest.reportingTimeZone),
                savedQueryName = null,
                minMaxOnly = false,
                applyGroupBy = true,
                orderOutputColumnsByInputOrder = true,
                dialect = manifest.dialect.toMetricFlowSqlEngine(),
            )
            explainQuery.explain(resolvedEngine, explainRequest)
        }

        val queriedModels = explainResult.queriedSemanticModels.mapTo(linkedSetOf()) {
            SemanticElementName(it.semanticModelName)
        }
        querySpec.groupBys.forEachIndexed { index, selector ->
            if (selector.modelName()?.let { it !in queriedModels } == true) return SemanticMetricFlowRenderResult.Invalid(
                listOf(SemanticMetricFlowInvalidReason(SemanticInvalidReasonCode.QUERY_GROUP_BY_NOT_FOUND, "groupBys[$index]")),
            )
        }
        querySpec.filters.forEachIndexed { index, filter ->
            if (filter.dimension.semanticModelName !in queriedModels) return SemanticMetricFlowRenderResult.Invalid(
                listOf(SemanticMetricFlowInvalidReason(SemanticInvalidReasonCode.UNKNOWN_FILTER_TARGET, "filters[$index]")),
            )
        }
        val resolvedOrder = explainResult.querySpec.inputSpecOrder
        val resolvedAliases = metricFlowCall(SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID, { return it.toRenderResult() }) {
            (resolvedOrder.groupByItemSpecs + resolvedOrder.metricSpecs).map {
                DunderColumnAssociationResolver(dunderPrefixSimpleMetricInputs = true).resolveSpec(it).columnName
            }
        }
        if (resultProjection.sourceAliases != resolvedAliases || resolvedAliases.size != resultColumns.size) {
            return SemanticMetricFlowRenderResult.Invalid(
                listOf(SemanticMetricFlowInvalidReason(SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID, null)),
            )
        }
        val outputColumns = buildList {
            querySpec.groupBys.forEachIndexed { index, groupBy ->
                add(
                    SemanticMetricFlowOutputColumn(
                        source = SemanticMetricFlowOutputColumnSource.GroupBy(groupBy),
                        name = resultColumns[index].key,
                        sqlName = resultColumns[index].sqlName,
                    ),
                )
            }
            metricIds.forEachIndexed { index, metricId ->
                add(
                    SemanticMetricFlowOutputColumn(
                        source = SemanticMetricFlowOutputColumnSource.Metric(metricId),
                        name = resultColumns[querySpec.groupBys.size + index].key,
                        sqlName = resultColumns[querySpec.groupBys.size + index].sqlName,
                    ),
                )
            }
        }
        return SemanticMetricFlowRenderResult.Rendered(
            dialect = manifest.dialect,
            sql = explainResult.sql,
            requestedMetricIds = metricIds,
            outputColumns = outputColumns,
        )
    }
}

private fun invalidQueryReason(
    manifest: SemanticMetricFlowManifest,
    querySpec: SemanticQuerySpec,
    metricIds: List<cc.monomer.semantic.api.MetricId>,
): SemanticMetricFlowInvalidReason? {
    if (metricIds.size != querySpec.metrics.size || metricIds.any { it !in manifest.metrics }) {
        return SemanticMetricFlowInvalidReason(SemanticInvalidReasonCode.QUERY_METRIC_NOT_FOUND, null)
    }
    if (metricIds.map { manifest.metrics.getValue(it).name } != querySpec.metrics) {
        return SemanticMetricFlowInvalidReason(SemanticInvalidReasonCode.QUERY_METRIC_NOT_FOUND, null)
    }
    querySpec.groupBys.forEachIndexed { index, groupBy ->
        when (groupBy) {
            is SemanticGroupBy.Dimension -> {
                val model = manifest.models[groupBy.semanticModelName]
                    ?: return SemanticMetricFlowInvalidReason(
                        SemanticInvalidReasonCode.QUERY_GROUP_BY_NOT_FOUND,
                        "groupBys[$index]",
                    )
                val dimension = model.definition.dimensions.firstOrNull {
                    it.name == groupBy.name
                } ?: return SemanticMetricFlowInvalidReason(
                    SemanticInvalidReasonCode.QUERY_GROUP_BY_NOT_FOUND,
                    "groupBys[$index]",
                )
                if (dimension.type == SemanticDimensionKind.TIME) {
                    if (groupBy.granularity == null) {
                        return SemanticMetricFlowInvalidReason(
                            SemanticInvalidReasonCode.GROUP_BY_KIND_MISMATCH,
                            "groupBys[$index]",
                        )
                    }
                } else if (groupBy.granularity != null) {
                    return SemanticMetricFlowInvalidReason(
                        SemanticInvalidReasonCode.GROUP_BY_KIND_MISMATCH,
                        "groupBys[$index]",
                    )
                }
            }
            is SemanticGroupBy.Entity -> {
                val model = manifest.models[groupBy.semanticModelName]
                    ?: return SemanticMetricFlowInvalidReason(
                        SemanticInvalidReasonCode.QUERY_GROUP_BY_NOT_FOUND,
                        "groupBys[$index]",
                    )
                if (model.definition.entities.none { it.name == groupBy.name }) {
                    return SemanticMetricFlowInvalidReason(
                        SemanticInvalidReasonCode.QUERY_GROUP_BY_NOT_FOUND,
                        "groupBys[$index]",
                    )
                }
            }
            is SemanticGroupBy.MetricTime -> Unit
        }
    }
    querySpec.filters.forEachIndexed { index, filter ->
        val model = manifest.models[filter.dimension.semanticModelName]
            ?: return SemanticMetricFlowInvalidReason(
                SemanticInvalidReasonCode.UNKNOWN_FILTER_TARGET,
                "filters[$index]",
            )
        if (model.definition.dimensions.none { it.name == filter.dimension.name }) {
            return SemanticMetricFlowInvalidReason(
                SemanticInvalidReasonCode.UNKNOWN_FILTER_TARGET,
                "filters[$index]",
            )
        }
    }
    return null
}

private fun TimeGranularity.toSemanticTimeGranularity(): cc.monomer.semantic.api.SemanticTimeGranularity =
    when (this) {
        TimeGranularity.NANOSECOND -> cc.monomer.semantic.api.SemanticTimeGranularity.NANOSECOND
        TimeGranularity.MICROSECOND -> cc.monomer.semantic.api.SemanticTimeGranularity.MICROSECOND
        TimeGranularity.MILLISECOND -> cc.monomer.semantic.api.SemanticTimeGranularity.MILLISECOND
        TimeGranularity.SECOND -> cc.monomer.semantic.api.SemanticTimeGranularity.SECOND
        TimeGranularity.MINUTE -> cc.monomer.semantic.api.SemanticTimeGranularity.MINUTE
        TimeGranularity.HOUR -> cc.monomer.semantic.api.SemanticTimeGranularity.HOUR
        TimeGranularity.DAY -> cc.monomer.semantic.api.SemanticTimeGranularity.DAY
        TimeGranularity.WEEK -> cc.monomer.semantic.api.SemanticTimeGranularity.WEEK
        TimeGranularity.MONTH -> cc.monomer.semantic.api.SemanticTimeGranularity.MONTH
        TimeGranularity.QUARTER -> cc.monomer.semantic.api.SemanticTimeGranularity.QUARTER
        TimeGranularity.YEAR -> cc.monomer.semantic.api.SemanticTimeGranularity.YEAR
    }

private fun SemanticTimeRangeEndpoint.metricFlowValue(reportingTimeZone: ZoneId): String = when (this) {
    is SemanticTimeRangeEndpoint.Date -> value.toString()
    is SemanticTimeRangeEndpoint.Timestamp -> value.atZone(reportingTimeZone).toLocalDateTime().toString()
}

private fun SemanticSqlDialect.toMetricFlowSqlEngine(): SqlEngine = when (this) {
    SemanticSqlDialect.POSTGRESQL -> SqlEngine.POSTGRES
    SemanticSqlDialect.BIGQUERY -> SqlEngine.BIGQUERY
    SemanticSqlDialect.SNOWFLAKE -> SqlEngine.SNOWFLAKE
    SemanticSqlDialect.DATABRICKS -> SqlEngine.DATABRICKS
    SemanticSqlDialect.REDSHIFT -> SqlEngine.REDSHIFT
    SemanticSqlDialect.TRINO -> SqlEngine.TRINO
    SemanticSqlDialect.DUCKDB -> SqlEngine.DUCKDB
}

private fun MetricFlowEngine.exactGroupByNamesByMetric(
    manifest: SemanticMetricFlowManifest,
): Map<SemanticElementName, Map<SemanticGroupBy, String>> = manifest.metrics.values.associate { metric ->
    metric.name to exactGroupByNames(listOf(metric.name.value))
}

private fun MetricFlowEngine.exactGroupByNames(metricNames: List<String>): Map<SemanticGroupBy, String> {
    val resolver = semanticManifestGraphLookup.groupByItemSetResolver
    val sets = metricNames.map { resolver.resolveAvailableItemsForMetric(
        cc.monomer.metricflow.domain.manifest.model.references.MetricReference(it),
    ) }
    val common = sets.first().intersection(*sets.drop(1).toTypedArray())
    val candidates = common.annotatedSpecs.flatMap { annotated ->
        val spec = annotated.spec
        if (spec is cc.monomer.metricflow.domain.spec.TimeDimensionSpec && spec.isMetricTime && spec.datePart == null) {
            listOf(SemanticGroupBy.MetricTime(requireNotNull(spec.baseGranularity).toSemanticTimeGranularity()) to spec.dunderName)
        } else annotated.originSemanticModelNames.mapNotNull { modelName ->
            val model = SemanticElementName(modelName)
            val name = SemanticElementName(spec.elementName)
            val selector = when (spec) {
                is cc.monomer.metricflow.domain.spec.DimensionSpec -> SemanticGroupBy.Dimension(model, name, null)
                is cc.monomer.metricflow.domain.spec.TimeDimensionSpec ->
                    if (spec.datePart == null) SemanticGroupBy.Dimension(
                        model, name, requireNotNull(spec.baseGranularity).toSemanticTimeGranularity(),
                    ) else null
                is cc.monomer.metricflow.domain.spec.EntitySpec -> SemanticGroupBy.Entity(model, name)
                else -> null
            }
            selector?.let { it to spec.dunderName }
        }
    }
    return candidates.groupBy({ it.first }, { it.second }).mapNotNull { (selector, names) ->
        names.distinct().singleOrNull()?.let { selector to it }
    }.toMap()
}

private fun SemanticGroupBy.modelName(): SemanticElementName? = when (this) {
    is SemanticGroupBy.Dimension -> semanticModelName
    is SemanticGroupBy.Entity -> semanticModelName
    is SemanticGroupBy.MetricTime -> null
}

private fun cc.monomer.metricflow.domain.manifest.validation.ValidationError.toIssue(): SemanticMetricFlowIssue =
    SemanticMetricFlowIssue(
        message = message.ifBlank { "MetricFlow rejected the definitions." },
        subject = when (val issueContext = context) {
            is cc.monomer.metricflow.domain.manifest.validation.MetricContext ->
                SemanticMetricFlowIssueSubject.Metric(issueContext.metric.metricName)
            is cc.monomer.metricflow.domain.manifest.validation.SemanticModelContext ->
                SemanticMetricFlowIssueSubject.Model(issueContext.semanticModel.semanticModelName)
            is cc.monomer.metricflow.domain.manifest.validation.SemanticModelElementContext ->
                SemanticMetricFlowIssueSubject.ModelElement(
                    issueContext.semanticModelElement.semanticModelName,
                    issueContext.semanticModelElement.elementName,
                )
            else -> null
        },
    )

private fun Throwable.requiresMetricFlowFailureMapping(): Boolean =
    this is InvalidQueryException || this is FeatureNotSupportedError ||
        isMetricFlowEngineFault() || this is CancellationException

private fun Throwable.isMetricFlowEngineFault(): Boolean =
    this is RenderSqlTemplateException || this is MetricFlowInternalError

private sealed interface MetricFlowCallFailure {
    data class Invalid(val code: SemanticInvalidReasonCode) : MetricFlowCallFailure
    data class Unsupported(val capability: SemanticMetricFlowUnsupportedCapability) : MetricFlowCallFailure
}

private fun MetricFlowCallFailure.toRenderResult(): SemanticMetricFlowRenderResult = when (this) {
    is MetricFlowCallFailure.Invalid ->
        SemanticMetricFlowRenderResult.Invalid(listOf(SemanticMetricFlowInvalidReason(code, null)))
    is MetricFlowCallFailure.Unsupported -> SemanticMetricFlowRenderResult.Unsupported(setOf(capability))
}

private fun MetricFlowCallFailure.toGroupByResult(): SemanticMetricFlowGroupByResult = when (this) {
    is MetricFlowCallFailure.Invalid ->
        SemanticMetricFlowGroupByResult.Invalid(listOf(SemanticMetricFlowInvalidReason(code, null)))
    is MetricFlowCallFailure.Unsupported -> SemanticMetricFlowGroupByResult.Unsupported(setOf(capability))
}

/**
 * Maps the engine's typed failures the same way at every engine call. Only an illegal argument means
 * something different per stage, so the caller names its reason. Cancellation is a JDK
 * IllegalStateException and must keep propagating.
 */
private inline fun <T> metricFlowCall(
    illegalArgumentReason: SemanticInvalidReasonCode,
    onFailure: (MetricFlowCallFailure) -> Nothing,
    call: () -> T,
): T = try {
    call()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    val mapped = failure.toMetricFlowCallFailure(illegalArgumentReason) ?: throw failure
    metricFlowCallLogger.atWarn()
        .addKeyValue("event", "semantic_metricflow_engine_failure_mapped")
        .addKeyValue("failure_type", failure.javaClass.name)
        .addKeyValue("mapped_to", mapped.name())
        .log("semantic_metricflow_engine_failure_mapped")
    onFailure(mapped)
}

private fun Throwable.toMetricFlowCallFailure(illegalArgumentReason: SemanticInvalidReasonCode): MetricFlowCallFailure? =
    when (this) {
        is RenderSqlTemplateException, is MetricFlowInternalError -> throw SemanticMetricFlowEngineFaultException(this)
        is FeatureNotSupportedError -> throw SemanticMetricFlowUnsupportedException(this)
        is InvalidManifestException, is MetricDefinitionDependencyError ->
            MetricFlowCallFailure.Invalid(SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID)
        is SemanticManifestConfigurationError ->
            MetricFlowCallFailure.Unsupported(SemanticMetricFlowUnsupportedCapability.TIME_SPINE_UNAVAILABLE)
        is NotImplementedError -> MetricFlowCallFailure.Unsupported(SemanticMetricFlowUnsupportedCapability.RELATIONSHIP_JOIN)
        is InvalidQueryException -> throw SemanticMetricFlowInvalidQueryException(illegalArgumentReason, this)
        is IllegalArgumentException -> MetricFlowCallFailure.Invalid(illegalArgumentReason)
        is IllegalStateException -> MetricFlowCallFailure.Invalid(SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID)
        else -> null
    }

private fun MetricFlowCallFailure.name(): String = when (this) {
    is MetricFlowCallFailure.Invalid -> code.name
    is MetricFlowCallFailure.Unsupported -> capability.name
}

private val metricFlowCallLogger = LoggerFactory.getLogger(MetricFlowSemanticRenderer::class.java)
