package cc.monomer.semantic.adapter.outbound.metricflow

import cc.monomer.metricflow.domain.manifest.model.ConversionTypeParams
import cc.monomer.metricflow.domain.manifest.model.CumulativeTypeParams
import cc.monomer.metricflow.domain.manifest.model.Metric
import cc.monomer.metricflow.domain.manifest.model.MetricInput
import cc.monomer.metricflow.domain.manifest.model.MetricInputMeasure
import cc.monomer.metricflow.domain.manifest.model.MetricTimeWindow
import cc.monomer.metricflow.domain.manifest.model.MetricTypeParams
import cc.monomer.metricflow.domain.manifest.model.NodeRelation
import cc.monomer.metricflow.domain.manifest.model.ProjectConfiguration
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.SemanticModel
import cc.monomer.metricflow.domain.manifest.model.SemanticModelDefaults
import cc.monomer.metricflow.domain.manifest.model.TimeSpine
import cc.monomer.metricflow.domain.manifest.model.TimeSpinePrimaryColumn
import cc.monomer.metricflow.domain.manifest.model.element.Dimension
import cc.monomer.metricflow.domain.manifest.model.element.DimensionTypeParams
import cc.monomer.metricflow.domain.manifest.model.element.Entity
import cc.monomer.metricflow.domain.manifest.model.element.Measure
import cc.monomer.metricflow.domain.manifest.model.element.NonAdditiveDimensionParameters
import cc.monomer.metricflow.domain.manifest.model.enums.AggregationType
import cc.monomer.metricflow.domain.manifest.model.enums.ConversionCalculationType
import cc.monomer.metricflow.domain.manifest.model.enums.DimensionType
import cc.monomer.metricflow.domain.manifest.model.enums.EntityType
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.model.enums.PeriodAggregation
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.transformation.SemanticManifestTransformer
import cc.monomer.semantic.api.SemanticDimensionKind
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticEntityKind
import cc.monomer.semantic.api.SemanticGroupBy
import cc.monomer.semantic.api.SemanticMetricDefinition
import cc.monomer.semantic.api.SemanticMetricInput
import cc.monomer.semantic.api.SemanticMetricPeriodAggregation
import cc.monomer.semantic.api.SemanticMetricWindow
import cc.monomer.semantic.application.SemanticMetricFlowManifest

internal object MetricFlowManifestMapper {
    fun toEngineManifest(manifest: SemanticMetricFlowManifest): SemanticManifest = toEngineManifest(manifest, null)

    fun toEngineManifest(
        manifest: SemanticMetricFlowManifest,
        exactNamesByMetric: Map<SemanticElementName, Map<SemanticGroupBy, String>>?,
    ): SemanticManifest =
        SemanticManifestTransformer.transform(
            SemanticManifest(
                semanticModels = manifest.models.values.map {
                    it.toEngineModel(manifest)
                },
                metrics = manifest.metrics.values.map { it.toEngineMetric(manifest, exactNamesByMetric) },
                projectConfiguration = ProjectConfiguration(
                    timeSpines = manifest.timeSpines.map { timeSpine ->
                        TimeSpine(
                            nodeRelation = NodeRelation(
                                database = timeSpine.physicalAddress.catalogName,
                                schemaName = requireNotNull(timeSpine.physicalAddress.schemaName),
                                alias = requireNotNull(timeSpine.physicalAddress.objectName),
                            ),
                            primaryColumn = TimeSpinePrimaryColumn(
                                name = timeSpine.primaryColumnName,
                                timeGranularity = timeSpine.granularity.toMetricFlowGranularity(),
                            ),
                        )
                    },
                ),
            ),
        )

    private fun cc.monomer.semantic.application.SemanticMetricFlowModel.toEngineModel(
        manifest: SemanticMetricFlowManifest,
    ): SemanticModel {
        val definition = definition
        val source = definition.sourceRelationFor(manifest.connectorId)
            ?: throw IllegalArgumentException("selected connector source relation is missing")
        return SemanticModel(
            name = definition.name.value,
            defaults = SemanticModelDefaults(definition.aggregationTimeDimension?.value),
            description = definition.description,
            nodeRelation = NodeRelation(
                database = physicalAddress.catalogName,
                schemaName = requireNotNull(physicalAddress.schemaName),
                alias = requireNotNull(physicalAddress.objectName),
            ),
            primaryEntity = null,
            entities = definition.entities.map { entity ->
                Entity(
                    name = entity.name.value,
                    description = entity.description,
                    type = entity.type.toMetricFlowEntityType(),
                    expr = entity.expression,
                    label = entity.label,
                )
            },
            measures = definition.measures.map { measure ->
                Measure(
                    name = measure.name.value,
                    agg = measure.aggregation.toMetricFlowAggregationType(),
                    description = measure.description,
                    createMetric = null,
                    expr = measure.expression,
                    aggParams = null,
                    metadata = null,
                    nonAdditiveDimension = measure.nonAdditiveDimension?.toMetricFlowNonAdditive(),
                    aggTimeDimension = measure.aggregationTimeDimension?.value,
                    label = measure.label,
                    config = null,
                )
            },
            dimensions = definition.dimensions.map { dimension ->
                Dimension(
                    name = dimension.name.value,
                    description = dimension.description,
                    type = dimension.type.toMetricFlowDimensionType(),
                    isPartition = dimension.isPartition,
                    typeParams = dimension.granularity?.let { DimensionTypeParams(it.toMetricFlowGranularity()) },
                    expr = timeDimensions[dimension.name]?.let { timeDimension ->
                        reportingTimeExpression(
                            dimension.expression,
                            requireNotNull(dimension.granularity),
                            timeDimension,
                            manifest.dialect,
                            manifest.reportingTimeZone,
                        )
                    } ?: dimension.expression,
                    metadata = null,
                    label = dimension.label,
                    config = null,
                )
            },
            label = definition.label,
            metadata = null,
            config = null,
        )
    }

    private fun SemanticMetricDefinition.toEngineMetric(
        manifest: SemanticMetricFlowManifest,
        exactNamesByMetric: Map<SemanticElementName, Map<SemanticGroupBy, String>>?,
    ): Metric =
        Metric(
            name = name.value,
            description = description,
            type = type.toMetricFlowType(),
            typeParams = when (this) {
                is SemanticMetricDefinition.Simple -> MetricTypeParams(
                    measure = MetricInputMeasure(measure.value, null, null, false, null),
                    metricAggregationParams = null,
                )
                is SemanticMetricDefinition.Ratio -> MetricTypeParams(
                    numerator = numerator.toMetricFlowInput(manifest, exactNamesByMetric),
                    denominator = denominator.toMetricFlowInput(manifest, exactNamesByMetric),
                )
                is SemanticMetricDefinition.Derived -> MetricTypeParams(
                    expr = expression,
                    metrics = inputMetrics.map { it.toMetricFlowInput(manifest, exactNamesByMetric) },
                )
                is SemanticMetricDefinition.Cumulative -> MetricTypeParams(
                    cumulativeTypeParams = CumulativeTypeParams(
                        window = window?.toMetricFlowWindow(),
                        grainToDate = grainToDate?.name?.lowercase(),
                        periodAgg = periodAggregation?.toMetricFlowPeriodAggregation()
                            ?: PeriodAggregation.FIRST,
                        metric = inputMetric.toMetricFlowInput(manifest, exactNamesByMetric),
                    ),
                )
                is SemanticMetricDefinition.Conversion -> MetricTypeParams(
                    conversionTypeParams = ConversionTypeParams(
                        baseMetric = baseMetric.toMetricFlowInput(manifest, exactNamesByMetric),
                        conversionMetric = conversionMetric.toMetricFlowInput(manifest, exactNamesByMetric),
                        entity = entity.value,
                        calculation = calculation.toMetricFlowCalculation(),
                        window = window?.toMetricFlowWindow(),
                        constantProperties = constantProperties?.map {
                            cc.monomer.metricflow.domain.manifest.model.ConstantPropertyInput(
                                it.baseProperty.value,
                                it.conversionProperty.value,
                            )
                        },
                    ),
                )
            },
            filter = filters.toMetricFlowFilterIntersection(manifest, exactNamesByMetric?.getValue(name)),
            metadata = null,
            label = label,
            config = null,
            timeGranularity = timeGranularity?.name?.lowercase(),
        )
}

private fun SemanticMetricInput.toMetricFlowInput(
    manifest: SemanticMetricFlowManifest,
    exactNamesByMetric: Map<SemanticElementName, Map<SemanticGroupBy, String>>?,
): MetricInput =
    MetricInput(
        name = metricName.value,
        filter = filter?.let { listOf(it).toMetricFlowFilterIntersection(manifest, exactNamesByMetric?.getValue(metricName)) },
        alias = alias?.value,
        offsetWindow = offsetWindow?.toMetricFlowWindow(),
        offsetToGrain = offsetToGrain?.name?.lowercase(),
    )

private fun SemanticMetricWindow.toMetricFlowWindow(): MetricTimeWindow =
    MetricTimeWindow(amount, unit.name.lowercase())

private fun SemanticMetricPeriodAggregation.toMetricFlowPeriodAggregation(): PeriodAggregation =
    when (this) {
        SemanticMetricPeriodAggregation.FIRST -> PeriodAggregation.FIRST
        SemanticMetricPeriodAggregation.LAST -> PeriodAggregation.LAST
        SemanticMetricPeriodAggregation.AVERAGE -> PeriodAggregation.AVERAGE
    }

private fun cc.monomer.semantic.api.SemanticMetricType.toMetricFlowType(): MetricType = when (this) {
    cc.monomer.semantic.api.SemanticMetricType.SIMPLE -> MetricType.SIMPLE
    cc.monomer.semantic.api.SemanticMetricType.RATIO -> MetricType.RATIO
    cc.monomer.semantic.api.SemanticMetricType.DERIVED -> MetricType.DERIVED
    cc.monomer.semantic.api.SemanticMetricType.CUMULATIVE -> MetricType.CUMULATIVE
    cc.monomer.semantic.api.SemanticMetricType.CONVERSION -> MetricType.CONVERSION
}

private fun cc.monomer.semantic.api.SemanticConversionCalculation.toMetricFlowCalculation(): ConversionCalculationType =
    when (this) {
        cc.monomer.semantic.api.SemanticConversionCalculation.CONVERSIONS -> ConversionCalculationType.CONVERSIONS
        cc.monomer.semantic.api.SemanticConversionCalculation.CONVERSION_RATE -> ConversionCalculationType.CONVERSION_RATE
    }

private fun cc.monomer.semantic.api.SemanticMeasureAggregation.toMetricFlowAggregationType(): AggregationType =
    when (this) {
        cc.monomer.semantic.api.SemanticMeasureAggregation.SUM -> AggregationType.SUM
        cc.monomer.semantic.api.SemanticMeasureAggregation.COUNT -> AggregationType.COUNT
        cc.monomer.semantic.api.SemanticMeasureAggregation.COUNT_DISTINCT -> AggregationType.COUNT_DISTINCT
        cc.monomer.semantic.api.SemanticMeasureAggregation.AVERAGE -> AggregationType.AVERAGE
        cc.monomer.semantic.api.SemanticMeasureAggregation.MIN -> AggregationType.MIN
        cc.monomer.semantic.api.SemanticMeasureAggregation.MAX -> AggregationType.MAX
        cc.monomer.semantic.api.SemanticMeasureAggregation.MEDIAN -> AggregationType.MEDIAN
        cc.monomer.semantic.api.SemanticMeasureAggregation.SUM_BOOLEAN -> AggregationType.SUM_BOOLEAN
    }

private fun SemanticEntityKind.toMetricFlowEntityType(): EntityType = when (this) {
    SemanticEntityKind.PRIMARY -> EntityType.PRIMARY
    SemanticEntityKind.UNIQUE -> EntityType.UNIQUE
    SemanticEntityKind.FOREIGN -> EntityType.FOREIGN
    SemanticEntityKind.NATURAL -> EntityType.NATURAL
}

private fun SemanticDimensionKind.toMetricFlowDimensionType(): DimensionType = when (this) {
    SemanticDimensionKind.CATEGORICAL -> DimensionType.CATEGORICAL
    SemanticDimensionKind.TIME -> DimensionType.TIME
}

private fun cc.monomer.semantic.api.SemanticTimeGranularity.toMetricFlowGranularity(): TimeGranularity = when (this) {
    cc.monomer.semantic.api.SemanticTimeGranularity.NANOSECOND -> TimeGranularity.NANOSECOND
    cc.monomer.semantic.api.SemanticTimeGranularity.MICROSECOND -> TimeGranularity.MICROSECOND
    cc.monomer.semantic.api.SemanticTimeGranularity.MILLISECOND -> TimeGranularity.MILLISECOND
    cc.monomer.semantic.api.SemanticTimeGranularity.SECOND -> TimeGranularity.SECOND
    cc.monomer.semantic.api.SemanticTimeGranularity.MINUTE -> TimeGranularity.MINUTE
    cc.monomer.semantic.api.SemanticTimeGranularity.HOUR -> TimeGranularity.HOUR
    cc.monomer.semantic.api.SemanticTimeGranularity.DAY -> TimeGranularity.DAY
    cc.monomer.semantic.api.SemanticTimeGranularity.WEEK -> TimeGranularity.WEEK
    cc.monomer.semantic.api.SemanticTimeGranularity.MONTH -> TimeGranularity.MONTH
    cc.monomer.semantic.api.SemanticTimeGranularity.QUARTER -> TimeGranularity.QUARTER
    cc.monomer.semantic.api.SemanticTimeGranularity.YEAR -> TimeGranularity.YEAR
}

private fun cc.monomer.semantic.api.SemanticNonAdditiveDimension.toMetricFlowNonAdditive(): NonAdditiveDimensionParameters =
    NonAdditiveDimensionParameters(
        name = name.value,
        windowChoice = when (windowChoice) {
            cc.monomer.semantic.api.SemanticNonAdditiveWindowChoice.MIN -> AggregationType.MIN
            cc.monomer.semantic.api.SemanticNonAdditiveWindowChoice.MAX -> AggregationType.MAX
        },
        windowGroupings = windowGroupings.orEmpty().map { it.value },
    )
