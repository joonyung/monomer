package cc.monomer.control.lineage

import cc.monomer.semantic.api.SemanticDimensionKind
import cc.monomer.semantic.api.SemanticEntityKind
import cc.monomer.semantic.api.SemanticMeasureAggregation
import cc.monomer.semantic.api.SemanticMetricType
import cc.monomer.semantic.api.SemanticTimeGranularity

data class LineageResourceDetail(
    val relation: LineageRelationDetail?,
    val semanticModel: LineageSemanticModelDetail?,
    val metric: LineageMetricDetail?,
) {
    init {
        require(listOf(relation, semanticModel, metric).count { it != null } == 1)
    }
}

data class LineageRelationDetail(val columns: List<LineageRelationColumn>) {
    init {
        require(columns.map(LineageRelationColumn::name).toSet().size == columns.size)
    }
}

data class LineageRelationColumn(
    val name: String,
    val nativeType: String,
    val nullable: Boolean,
    val hasDefault: Boolean,
    val primaryKey: Boolean,
    val foreignKey: Boolean,
) {
    init {
        require(name.isNotBlank())
        require(nativeType.isNotBlank())
    }
}

data class LineageSemanticModelDetail(
    val entities: List<LineageSemanticEntity>,
    val dimensions: List<LineageSemanticDimension>,
    val measures: List<LineageSemanticMeasure>,
)

data class LineageSemanticEntity(
    val name: String,
    val label: String?,
    val description: String?,
    val type: SemanticEntityKind,
)

data class LineageSemanticDimension(
    val name: String,
    val label: String?,
    val description: String?,
    val type: SemanticDimensionKind,
    val granularity: SemanticTimeGranularity?,
)

data class LineageSemanticMeasure(
    val name: String,
    val label: String?,
    val description: String?,
    val aggregation: SemanticMeasureAggregation,
)

data class LineageMetricDetail(
    val type: SemanticMetricType,
    val inputs: List<LineageMetricInput>,
    val timeGranularity: SemanticTimeGranularity?,
)

enum class LineageMetricInputKind { MEASURE, METRIC }

data class LineageMetricInput(
    val kind: LineageMetricInputKind,
    val name: String,
    val semanticModelName: String?,
) {
    init {
        require(name.isNotBlank())
        require((kind == LineageMetricInputKind.MEASURE) == (semanticModelName != null))
    }
}
