package cc.monomer.semantic.api

import cc.monomer.identity.api.WorkspaceId

interface SemanticSearchProjection {
    fun deleteModel(workspaceId: WorkspaceId, name: String)
    fun deleteMetric(workspaceId: WorkspaceId, name: String)
    fun upsertModel(fields: SemanticModelSearchFields)
    fun upsertMetric(fields: MetricSearchFields)
}

data class SemanticModelSearchFields(
    val workspaceId: WorkspaceId,
    val name: String,
    val primaryNames: List<String>,
    val descriptions: List<String>,
    val structuralNames: List<String>,
)

data class MetricSearchFields(
    val workspaceId: WorkspaceId,
    val name: String,
    val primaryNames: List<String>,
    val descriptions: List<String>,
    val structuralNames: List<String>,
)

fun SemanticModelView.toSearchFields(): SemanticModelSearchFields = SemanticModelSearchFields(
    workspaceId = workspaceId,
    name = definition.name.value,
    primaryNames = listOfNotNull(definition.name.value, definition.label),
    descriptions = buildList {
        definition.description?.let(::add)
        addAll(definition.entities.mapNotNull { it.description })
        addAll(definition.dimensions.mapNotNull { it.description })
        addAll(definition.measures.mapNotNull { it.description })
    },
    structuralNames = buildList {
        definition.entities.forEach { add(it.name.value); it.label?.let(::add) }
        definition.dimensions.forEach { add(it.name.value); it.label?.let(::add) }
        definition.measures.forEach { add(it.name.value); it.label?.let(::add) }
        definition.sourceRelations.forEach { addAll(listOfNotNull(it.catalog, it.schema, it.relation)) }
    },
)

fun MetricView.toSearchFields(): MetricSearchFields = MetricSearchFields(
    workspaceId = workspaceId,
    name = definition.name.value,
    primaryNames = listOfNotNull(definition.name.value, definition.label),
    descriptions = listOfNotNull(definition.description),
    structuralNames = listOf(definition.type.name) + when (val metric = definition) {
        is SemanticMetricDefinition.Simple -> listOf(metric.semanticModelName.value, metric.measure.value)
        is SemanticMetricDefinition.Ratio -> listOf(metric.numerator.metricName.value, metric.denominator.metricName.value)
        is SemanticMetricDefinition.Derived -> metric.inputMetrics.map { it.metricName.value }
        is SemanticMetricDefinition.Cumulative -> listOf(metric.inputMetric.metricName.value)
        is SemanticMetricDefinition.Conversion -> listOf(metric.baseMetric.metricName.value, metric.conversionMetric.metricName.value)
    },
)

interface SemanticSearchReadPort {
    fun inventory(): SemanticSearchInventory
}

data class SemanticSearchInventory(
    val models: List<SemanticModelSearchFields>,
    val metrics: List<MetricSearchFields>,
)
