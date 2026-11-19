package cc.monomer.semantic.api

fun SemanticMetricDefinition.directMetricInputs(): List<SemanticMetricInput> =
    when (this) {
        is SemanticMetricDefinition.Simple -> emptyList()
        is SemanticMetricDefinition.Ratio -> listOf(numerator, denominator)
        is SemanticMetricDefinition.Derived -> inputMetrics
        is SemanticMetricDefinition.Cumulative -> listOf(inputMetric)
        is SemanticMetricDefinition.Conversion -> listOf(baseMetric, conversionMetric)
    }

fun SemanticMetricDefinition.referencedModelNames(): Set<SemanticElementName> =
    buildSet {
        if (this@referencedModelNames is SemanticMetricDefinition.Simple) {
            add(this@referencedModelNames.semanticModelName)
        }
        filters.forEach { addAll(it.referencedModelNames()) }
        directMetricInputs().forEach { input ->
            input.filter?.let { addAll(it.referencedModelNames()) }
        }
    }

private fun SemanticMetricFilter.referencedModelNames(): Set<SemanticElementName> =
    when (this) {
        is SemanticMetricFilter.Predicate -> setOf(dimension.semanticModelName)
        is SemanticMetricFilter.And -> filters.flatMapTo(mutableSetOf()) { it.referencedModelNames() }
        is SemanticMetricFilter.Or -> filters.flatMapTo(mutableSetOf()) { it.referencedModelNames() }
        is SemanticMetricFilter.Not -> filter.referencedModelNames()
    }
