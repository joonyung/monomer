@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.semantic.inbound.rest

import cc.monomer.control.agentapp.responses
import cc.monomer.connector.api.ConnectorId
import cc.monomer.semantic.api.MetricView
import cc.monomer.semantic.api.SemanticConstantProperty
import cc.monomer.semantic.api.SemanticDimensionDefinition
import cc.monomer.semantic.api.SemanticDimensionKind
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticElementReference
import cc.monomer.semantic.api.SemanticEntityDefinition
import cc.monomer.semantic.api.SemanticMeasureDefinition
import cc.monomer.semantic.api.SemanticMetricDefinition
import cc.monomer.semantic.api.SemanticMetricFilter
import cc.monomer.semantic.api.SemanticMetricInput
import cc.monomer.semantic.api.SemanticMetricType
import cc.monomer.semantic.api.SemanticMetricWindow
import cc.monomer.semantic.api.SemanticModelDefinition
import cc.monomer.semantic.api.SemanticModelView
import cc.monomer.semantic.api.SemanticDefinitionChange
import cc.monomer.semantic.api.SemanticNonAdditiveDimension
import cc.monomer.semantic.api.SemanticSourceRelation
import cc.monomer.semantic.api.SemanticTimeGranularity
import cc.monomer.semantic.api.SemanticTimeSpine
import cc.monomer.semantic.api.SemanticTimeSpineConfigurationView
import cc.monomer.semantic.api.SemanticTimeSpineDefinition
import cc.monomer.semantic.api.SemanticTimeSpinePrimaryColumn
import cc.monomer.semantic.api.SemanticTimeSpineRelation
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonProperty
import kotlin.uuid.Uuid

data class SemanticModelWriteRequest(
    val definition: SemanticModelDefinitionDto,
    val aggregateMetrics: List<SemanticAggregateMetricDto>,
)

data class SemanticAggregateMetricDto(
    val measure: String,
    val name: String,
    val label: String?,
    val description: String?,
    val timeGranularity: String?,
    val filters: List<SemanticMetricFilterDto>?,
) {
    internal fun toDomain(index: Int): cc.monomer.semantic.api.SemanticAggregateMetric {
        val field = "aggregateMetrics[$index]"
        fun <T> decode(part: String, block: () -> T): T = try {
            block()
        } catch (problem: SemanticDefinitionProblem) {
            throw SemanticDefinitionProblem(problem.status, problem.code, problem.detail,
                problem.violations.map { it.copy(field = if (it.field.startsWith("$field.filters[")) it.field else "$field.$part") })
        } catch (_: IllegalArgumentException) {
            invalidSemanticTransport("$field.$part", "This aggregate metric field is invalid.")
        }
        return cc.monomer.semantic.api.SemanticAggregateMetric(
            measure = decode("measure") { SemanticElementName(measure) },
            name = decode("name") { SemanticElementName(name) },
            label = decode("label") { label?.also { require(it.isNotBlank() && '\u0000' !in it) } },
            description = decode("description") { description?.also { require(it.isNotBlank() && '\u0000' !in it) } },
            timeGranularity = decode("timeGranularity") { timeGranularity?.toSemanticEnum<SemanticTimeGranularity>("time granularity") },
            filters = decode("filters") {
                require(filters == null || filters.size <= 32)
                filters.orEmpty().mapIndexed { filterIndex, filter -> decode("filters[$filterIndex]") { filter.toDomain() } }
            },
        )
    }
}

data class SemanticModelWriteResponseDto(
    val model: SemanticModelResponseDto,
    val aggregateMetrics: List<MetricResponseDto>,
) {
    internal companion object {
        fun fromDomain(result: cc.monomer.semantic.api.SemanticModelWriteResult) = SemanticModelWriteResponseDto(
            SemanticModelResponseDto.fromDomain(result.model), result.aggregateMetrics.map(MetricResponseDto::fromDomain),
        )
    }
}

data class MetricWriteRequest(
    val definition: MetricDefinitionDto,
)

data class SemanticTimeSpineDefinitionRequest(
    val schemaVersion: Int,
    val timeSpines: List<SemanticTimeSpineDto>,
) {
    internal fun toDomain(): SemanticTimeSpineDefinition = decodeSemanticTransport {
        SemanticTimeSpineDefinition(
            schemaVersion = schemaVersion.toSemanticTimeSpineSchemaVersion(),
            timeSpines = timeSpines.mapIndexed { index, spine -> spine.toDomain(index) },
        )
    }

    internal companion object {
        fun fromDomain(definition: SemanticTimeSpineDefinition): SemanticTimeSpineDefinitionRequest =
            SemanticTimeSpineDefinitionRequest(
                schemaVersion = definition.schemaVersion.toInt(),
                timeSpines = definition.timeSpines.map(SemanticTimeSpineDto::fromDomain),
            )
    }
}

data class SemanticTimeSpineDto(
    val relation: SemanticTimeSpineRelationDto,
    val primaryColumn: SemanticTimeSpinePrimaryColumnDto,
) {
    internal fun toDomain(index: Int): SemanticTimeSpine = decodeSemanticTransport {
        SemanticTimeSpine(
            relation = relation.toDomain("timeSpines[$index].relation"),
            primaryColumn = primaryColumn.toDomain("timeSpines[$index].primaryColumn"),
        )
    }

    internal companion object {
        fun fromDomain(timeSpine: SemanticTimeSpine): SemanticTimeSpineDto =
            SemanticTimeSpineDto(
                relation = SemanticTimeSpineRelationDto.fromDomain(timeSpine.relation),
                primaryColumn = SemanticTimeSpinePrimaryColumnDto.fromDomain(timeSpine.primaryColumn),
            )
    }
}

data class SemanticTimeSpineRelationDto(
    val catalog: String?,
    val schema: String,
    val relation: String,
) {
    internal fun toDomain(field: String): SemanticTimeSpineRelation = decodeSemanticTransport {
        SemanticTimeSpineRelation(
            catalog = catalog?.requireCanonicalTimeSpineAddress("$field.catalog", "catalog"),
            schema = schema.requireCanonicalTimeSpineAddress("$field.schema", "schema"),
            relation = relation.requireCanonicalTimeSpineAddress("$field.relation", "relation"),
        )
    }

    internal companion object {
        fun fromDomain(relation: SemanticTimeSpineRelation): SemanticTimeSpineRelationDto =
            SemanticTimeSpineRelationDto(
                catalog = relation.catalog,
                schema = relation.schema,
                relation = relation.relation,
            )
    }
}

data class SemanticTimeSpinePrimaryColumnDto(
    val name: String,
    val granularity: String,
) {
    internal fun toDomain(field: String): SemanticTimeSpinePrimaryColumn = decodeSemanticTransport {
        SemanticTimeSpinePrimaryColumn(
            name = name.requireCanonicalTimeSpineAddress("$field.name", "primary column"),
            granularity = enumValues<SemanticTimeGranularity>().firstOrNull { it.name.equals(granularity, ignoreCase = true) }
                ?: invalidSemanticTransport("$field.granularity", "unsupported time spine granularity: $granularity"),
        )
    }

    internal companion object {
        fun fromDomain(column: SemanticTimeSpinePrimaryColumn): SemanticTimeSpinePrimaryColumnDto =
            SemanticTimeSpinePrimaryColumnDto(
                name = column.name,
                granularity = column.granularity.name.lowercase(),
            )
    }
}

data class SemanticTimeSpineConfigurationResponseDto(
    val connectorId: String,
    val definition: SemanticTimeSpineDefinitionRequest,
) {
    internal companion object {
        fun fromDomain(view: SemanticTimeSpineConfigurationView): SemanticTimeSpineConfigurationResponseDto =
            SemanticTimeSpineConfigurationResponseDto(
                connectorId = view.connectorId.value.toString(),
                definition = SemanticTimeSpineDefinitionRequest.fromDomain(view.definition),
            )
    }
}

data class SemanticModelDefinitionDto(
    val schemaVersion: Int,
    val name: String,
    val label: String?,
    val description: String?,
    val sourceRelations: List<SemanticSourceRelationDto>,
    val aggregationTimeDimension: String?,
    val entities: List<SemanticEntityDefinitionDto>,
    val dimensions: List<SemanticDimensionDefinitionDto>,
    val measures: List<SemanticMeasureDefinitionDto>,
) {
    internal fun toDomain(): SemanticModelDefinition = decodeSemanticTransport {
        SemanticModelDefinition(
            schemaVersion = schemaVersion.toSemanticSchemaVersion(),
            name = name.toSemanticElementName("model name"),
            label = label,
            description = description,
            sourceRelations = sourceRelations.map(SemanticSourceRelationDto::toDomain),
            aggregationTimeDimension = aggregationTimeDimension?.toSemanticElementName("aggregationTimeDimension"),
            entities = entities.map(SemanticEntityDefinitionDto::toDomain),
            dimensions = dimensions.map(SemanticDimensionDefinitionDto::toDomain),
            measures = measures.map(SemanticMeasureDefinitionDto::toDomain),
        )
    }

    internal companion object {
        fun fromDomain(definition: SemanticModelDefinition): SemanticModelDefinitionDto =
            SemanticModelDefinitionDto(
                schemaVersion = definition.schemaVersion.toInt(),
                name = definition.name.value,
                label = definition.label,
                description = definition.description,
                sourceRelations = definition.sourceRelations.map(SemanticSourceRelationDto::fromDomain),
                aggregationTimeDimension = definition.aggregationTimeDimension?.value,
                entities = definition.entities.map(SemanticEntityDefinitionDto::fromDomain),
                dimensions = definition.dimensions.map(SemanticDimensionDefinitionDto::fromDomain),
                measures = definition.measures.map(SemanticMeasureDefinitionDto::fromDomain),
            )
    }
}

data class SemanticSourceRelationDto(
    val connectorId: String,
    val catalog: String?,
    val schema: String,
    val relation: String,
) {
    internal fun toDomain(): SemanticSourceRelation = decodeSemanticTransport {
        SemanticSourceRelation(
            connectorId = ConnectorId(Uuid.parse(connectorId)),
            catalog = catalog,
            schema = schema,
            relation = relation,
        )
    }

    internal companion object {
        fun fromDomain(relation: SemanticSourceRelation): SemanticSourceRelationDto =
            SemanticSourceRelationDto(
                connectorId = relation.connectorId.value.toString(),
                catalog = relation.catalog,
                schema = relation.schema,
                relation = relation.relation,
            )
    }
}

data class SemanticEntityDefinitionDto(
    val name: String,
    val type: String,
    val expression: String,
    val label: String?,
    val description: String?,
) {
    internal fun toDomain(): SemanticEntityDefinition = decodeSemanticTransport {
        SemanticEntityDefinition(
            name = name.toSemanticElementName("entity name"),
            type = type.toSemanticEnum("entity type"),
            expression = expression,
            label = label,
            description = description,
        )
    }

    internal companion object {
        fun fromDomain(definition: SemanticEntityDefinition): SemanticEntityDefinitionDto =
            SemanticEntityDefinitionDto(
                name = definition.name.value,
                type = definition.type.name.lowercase(),
                expression = definition.expression,
                label = definition.label,
                description = definition.description,
            )
    }
}

data class SemanticDimensionDefinitionDto(
    val name: String,
    val type: String,
    val expression: String,
    val granularity: String?,
    val label: String?,
    val description: String?,
    @get:JsonProperty("isPartition")
    @param:JsonProperty("isPartition")
    val isPartition: Boolean,
    val timeKind: String?,
) {
    internal fun toDomain(): SemanticDimensionDefinition = decodeSemanticTransport {
        val dimensionType = type.toSemanticEnum<SemanticDimensionKind>("dimension type")
        SemanticDimensionDefinition(
            name = name.toSemanticElementName("dimension name"),
            type = dimensionType,
            expression = expression,
            granularity = granularity?.toSemanticEnum("dimension granularity"),
            label = label,
            description = description,
            isPartition = isPartition,
            timeKind = timeKind?.toSemanticEnum("dimension time kind"),
        )
    }

    internal companion object {
        fun fromDomain(definition: SemanticDimensionDefinition): SemanticDimensionDefinitionDto =
            SemanticDimensionDefinitionDto(
                name = definition.name.value,
                type = definition.type.name.lowercase(),
                expression = definition.expression,
                granularity = definition.granularity?.name?.lowercase(),
                label = definition.label,
                description = definition.description,
                isPartition = definition.isPartition,
                timeKind = definition.timeKind?.name?.lowercase(),
            )
    }
}

data class SemanticMeasureDefinitionDto(
    val name: String,
    val aggregation: String,
    val expression: String,
    val aggregationTimeDimension: String?,
    val nonAdditiveDimension: SemanticNonAdditiveDimensionDto?,
    val label: String?,
    val description: String?,
) {
    internal fun toDomain(): SemanticMeasureDefinition = decodeSemanticTransport {
        SemanticMeasureDefinition(
            name = name.toSemanticElementName("measure name"),
            aggregation = aggregation.toSemanticEnum("measure aggregation"),
            expression = expression,
            aggregationTimeDimension = aggregationTimeDimension?.toSemanticElementName("aggregationTimeDimension"),
            nonAdditiveDimension = nonAdditiveDimension?.toDomain(),
            label = label,
            description = description,
        )
    }

    internal companion object {
        fun fromDomain(definition: SemanticMeasureDefinition): SemanticMeasureDefinitionDto =
            SemanticMeasureDefinitionDto(
                name = definition.name.value,
                aggregation = definition.aggregation.name.lowercase(),
                expression = definition.expression,
                aggregationTimeDimension = definition.aggregationTimeDimension?.value,
                nonAdditiveDimension = definition.nonAdditiveDimension?.let(SemanticNonAdditiveDimensionDto::fromDomain),
                label = definition.label,
                description = definition.description,
            )
    }
}

data class SemanticNonAdditiveDimensionDto(
    val name: String,
    val windowChoice: String,
    val windowGroupings: List<String>?,
) {
    internal fun toDomain(): SemanticNonAdditiveDimension = decodeSemanticTransport {
        SemanticNonAdditiveDimension(
            name = name.toSemanticElementName("nonAdditiveDimension name"),
            windowChoice = windowChoice.toSemanticEnum("nonAdditiveDimension windowChoice"),
            windowGroupings = windowGroupings?.map { it.toSemanticElementName("window grouping") },
        )
    }

    internal companion object {
        fun fromDomain(definition: SemanticNonAdditiveDimension): SemanticNonAdditiveDimensionDto =
            SemanticNonAdditiveDimensionDto(
                name = definition.name.value,
                windowChoice = definition.windowChoice.name.lowercase(),
                windowGroupings = definition.windowGroupings?.map(SemanticElementName::value),
            )
    }
}

data class MetricDefinitionDto(
    val schemaVersion: Int,
    val name: String,
    val type: String,
    val label: String?,
    val description: String?,
    val timeGranularity: String?,
    val filters: List<SemanticMetricFilterDto>?,
    val semanticModelName: String?,
    val measure: String?,
    val numerator: SemanticMetricInputDto?,
    val denominator: SemanticMetricInputDto?,
    val expression: String?,
    val inputMetrics: List<SemanticMetricInputDto>?,
    val inputMetric: SemanticMetricInputDto?,
    val window: String?,
    val grainToDate: String?,
    val periodAggregation: String?,
    val entity: String?,
    val calculation: String?,
    val baseMetric: SemanticMetricInputDto?,
    val conversionMetric: SemanticMetricInputDto?,
    val constantProperties: List<SemanticConstantPropertyDto>?,
    val display: MetricDisplayDto?,
) {
    internal fun toDomain(): SemanticMetricDefinition = decodeSemanticTransport {
        val metricType = type.toSemanticEnum<SemanticMetricType>("metric type")
        requireClosedMetricShape(metricType)
        val commonSchemaVersion = schemaVersion.toSemanticSchemaVersion()
        val commonName = name.toSemanticElementName("metric name")
        val commonLabel = label
        val commonDescription = description
        val commonGranularity = timeGranularity?.toSemanticEnum<SemanticTimeGranularity>("metric timeGranularity")
        val commonFilters = filters.orEmpty().map(SemanticMetricFilterDto::toDomain)
        when (metricType) {
            SemanticMetricType.SIMPLE -> SemanticMetricDefinition.Simple(
                schemaVersion = commonSchemaVersion,
                name = commonName,
                label = commonLabel,
                description = commonDescription,
                timeGranularity = commonGranularity,
                filters = commonFilters,
                semanticModelName = requireField(semanticModelName, "semanticModelName").toSemanticElementName("semanticModelName"),
                measure = requireField(measure, "measure").toSemanticElementName("measure"),
                display = display?.toDomain(),
            )
            SemanticMetricType.RATIO -> SemanticMetricDefinition.Ratio(
                schemaVersion = commonSchemaVersion,
                name = commonName,
                label = commonLabel,
                description = commonDescription,
                timeGranularity = commonGranularity,
                filters = commonFilters,
                numerator = requireField(numerator, "numerator").toDomain(),
                denominator = requireField(denominator, "denominator").toDomain(),
                display = display?.toDomain(),
            )
            SemanticMetricType.DERIVED -> SemanticMetricDefinition.Derived(
                schemaVersion = commonSchemaVersion,
                name = commonName,
                label = commonLabel,
                description = commonDescription,
                timeGranularity = commonGranularity,
                filters = commonFilters,
                expression = requireField(expression, "expression"),
                inputMetrics = requireField(inputMetrics, "inputMetrics").map(SemanticMetricInputDto::toDomain),
                display = display?.toDomain(),
            )
            SemanticMetricType.CUMULATIVE -> SemanticMetricDefinition.Cumulative(
                schemaVersion = commonSchemaVersion,
                name = commonName,
                label = commonLabel,
                description = commonDescription,
                timeGranularity = commonGranularity,
                filters = commonFilters,
                inputMetric = requireField(inputMetric, "inputMetric").toDomain(),
                window = window?.let(SemanticMetricWindow::parse),
                grainToDate = grainToDate?.toSemanticEnum("grainToDate"),
                periodAggregation = periodAggregation?.toSemanticEnum("periodAggregation"),
                display = display?.toDomain(),
            )
            SemanticMetricType.CONVERSION -> SemanticMetricDefinition.Conversion(
                schemaVersion = commonSchemaVersion,
                name = commonName,
                label = commonLabel,
                description = commonDescription,
                timeGranularity = commonGranularity,
                filters = commonFilters,
                entity = requireField(entity, "entity").toSemanticElementName("entity"),
                calculation = requireField(calculation, "calculation").toSemanticEnum("calculation"),
                baseMetric = requireField(baseMetric, "baseMetric").toDomain(),
                conversionMetric = requireField(conversionMetric, "conversionMetric").toDomain(),
                window = window?.let(SemanticMetricWindow::parse),
                constantProperties = constantProperties?.map(SemanticConstantPropertyDto::toDomain),
                display = display?.toDomain(),
            )
        }
    }

    private fun requireClosedMetricShape(metricType: SemanticMetricType) {
        val fields = mapOf(
            "semanticModelName" to semanticModelName,
            "measure" to measure,
            "numerator" to numerator,
            "denominator" to denominator,
            "expression" to expression,
            "inputMetrics" to inputMetrics,
            "inputMetric" to inputMetric,
            "window" to window,
            "grainToDate" to grainToDate,
            "periodAggregation" to periodAggregation,
            "entity" to entity,
            "calculation" to calculation,
            "baseMetric" to baseMetric,
            "conversionMetric" to conversionMetric,
            "constantProperties" to constantProperties,
        )
        val allowed = when (metricType) {
            SemanticMetricType.SIMPLE -> setOf("semanticModelName", "measure")
            SemanticMetricType.RATIO -> setOf("numerator", "denominator")
            SemanticMetricType.DERIVED -> setOf("expression", "inputMetrics")
            SemanticMetricType.CUMULATIVE -> setOf("inputMetric", "window", "grainToDate", "periodAggregation")
            SemanticMetricType.CONVERSION ->
                setOf("entity", "calculation", "baseMetric", "conversionMetric", "window", "constantProperties")
        }
        fields.forEach { (name, value) ->
            require(name in allowed || value == null) {
                "$name is not allowed for ${metricType.name.lowercase()} metric"
            }
        }
    }

    internal companion object {
        fun fromDomain(definition: SemanticMetricDefinition): MetricDefinitionDto =
            MetricDefinitionDto(
                schemaVersion = definition.schemaVersion.toInt(),
                name = definition.name.value,
                type = definition.type.name.lowercase(),
                label = definition.label,
                description = definition.description,
                timeGranularity = definition.timeGranularity?.name?.lowercase(),
                filters = definition.filters.map(SemanticMetricFilterDto::fromDomain).takeIf(List<*>::isNotEmpty),
                semanticModelName = (definition as? SemanticMetricDefinition.Simple)?.semanticModelName?.value,
                measure = (definition as? SemanticMetricDefinition.Simple)?.measure?.value,
                numerator = (definition as? SemanticMetricDefinition.Ratio)?.numerator?.let(SemanticMetricInputDto::fromDomain),
                denominator = (definition as? SemanticMetricDefinition.Ratio)?.denominator?.let(SemanticMetricInputDto::fromDomain),
                expression = (definition as? SemanticMetricDefinition.Derived)?.expression,
                inputMetrics = (definition as? SemanticMetricDefinition.Derived)?.inputMetrics?.map(SemanticMetricInputDto::fromDomain),
                inputMetric = (definition as? SemanticMetricDefinition.Cumulative)?.inputMetric?.let(SemanticMetricInputDto::fromDomain),
                window = when (definition) {
                    is SemanticMetricDefinition.Cumulative -> definition.window?.toString()
                    is SemanticMetricDefinition.Conversion -> definition.window?.toString()
                    else -> null
                },
                grainToDate = (definition as? SemanticMetricDefinition.Cumulative)?.grainToDate?.name?.lowercase(),
                periodAggregation = (definition as? SemanticMetricDefinition.Cumulative)?.periodAggregation?.name?.lowercase(),
                entity = (definition as? SemanticMetricDefinition.Conversion)?.entity?.value,
                calculation = (definition as? SemanticMetricDefinition.Conversion)?.calculation?.name?.lowercase(),
                baseMetric = (definition as? SemanticMetricDefinition.Conversion)?.baseMetric?.let(SemanticMetricInputDto::fromDomain),
                conversionMetric = (definition as? SemanticMetricDefinition.Conversion)?.conversionMetric?.let(SemanticMetricInputDto::fromDomain),
                constantProperties = (definition as? SemanticMetricDefinition.Conversion)?.constantProperties
                    ?.map(SemanticConstantPropertyDto::fromDomain),
                display = definition.display?.let(MetricDisplayDto::fromDomain),
            )
    }
}

data class SemanticDimensionReferenceDto(
    val semanticModelName: String,
    val name: String,
    val granularity: String?,
) {
    internal fun toDomain(): SemanticElementReference =
        decodeSemanticTransport {
            SemanticElementReference(
                semanticModelName = semanticModelName.toSemanticElementName("filter semanticModelName"),
                name = name.toSemanticElementName("filter dimension name"),
                granularity = granularity?.toSemanticEnum("filter granularity"),
            )
        }

    internal companion object {
        fun fromDomain(reference: SemanticElementReference): SemanticDimensionReferenceDto =
            SemanticDimensionReferenceDto(
                semanticModelName = reference.semanticModelName.value,
                name = reference.name.value,
                granularity = reference.granularity?.name?.lowercase(),
            )
    }
}

data class SemanticMetricFilterDto(
    val dimension: SemanticDimensionReferenceDto?,
    val operator: String?,
    val values: List<String>?,
    val and: List<SemanticMetricFilterDto>?,
    val or: List<SemanticMetricFilterDto>?,
    val not: SemanticMetricFilterDto?,
) {
    internal fun toDomain(): SemanticMetricFilter = decodeSemanticTransport {
        val hasPredicateField = dimension != null || operator != null || values != null
        val groups = listOfNotNull(
            and?.let { "and" to SemanticMetricFilter.And(it.map(SemanticMetricFilterDto::toDomain)) },
            or?.let { "or" to SemanticMetricFilter.Or(it.map(SemanticMetricFilterDto::toDomain)) },
            not?.let { "not" to SemanticMetricFilter.Not(it.toDomain()) },
        )
        if (hasPredicateField) {
            require(groups.isEmpty()) { "filter predicate cannot include a group" }
            SemanticMetricFilter.Predicate(
                dimension = requireField(dimension, "dimension").toDomain(),
                operator = requireField(operator, "operator").toSemanticEnum("filter operator"),
                values = requireField(values, "values"),
            )
        } else {
            require(groups.size == 1) { "filter must contain exactly one predicate or group" }
            groups.single().second
        }
    }

    internal companion object {
        fun fromDomain(filter: SemanticMetricFilter): SemanticMetricFilterDto =
            when (filter) {
                is SemanticMetricFilter.Predicate -> SemanticMetricFilterDto(
                    dimension = SemanticDimensionReferenceDto.fromDomain(filter.dimension),
                    operator = filter.operator.name.lowercase(),
                    values = filter.values,
                    and = null,
                    or = null,
                    not = null,
                )
                is SemanticMetricFilter.And -> SemanticMetricFilterDto(null, null, null, filter.filters.map(SemanticMetricFilterDto::fromDomain), null, null)
                is SemanticMetricFilter.Or -> SemanticMetricFilterDto(null, null, null, null, filter.filters.map(SemanticMetricFilterDto::fromDomain), null)
                is SemanticMetricFilter.Not -> SemanticMetricFilterDto(null, null, null, null, null, fromDomain(filter.filter))
            }
    }
}

data class SemanticMetricInputDto(
    val metricName: String,
    val filter: SemanticMetricFilterDto?,
    val alias: String?,
    val offsetWindow: String?,
    val offsetToGrain: String?,
) {
    internal fun toDomain(): SemanticMetricInput = decodeSemanticTransport {
        SemanticMetricInput(
            metricName = metricName.toSemanticElementName("metricName"),
            filter = filter?.toDomain(),
            alias = alias?.toSemanticElementName("metric input alias"),
            offsetWindow = offsetWindow?.let(SemanticMetricWindow::parse),
            offsetToGrain = offsetToGrain?.toSemanticEnum("offsetToGrain"),
        )
    }

    internal companion object {
        fun fromDomain(input: SemanticMetricInput): SemanticMetricInputDto =
            SemanticMetricInputDto(
                metricName = input.metricName.value,
                filter = input.filter?.let(SemanticMetricFilterDto::fromDomain),
                alias = input.alias?.value,
                offsetWindow = input.offsetWindow?.toString(),
                offsetToGrain = input.offsetToGrain?.name?.lowercase(),
            )
    }
}

data class SemanticConstantPropertyDto(
    val baseProperty: String,
    val conversionProperty: String,
) {
    @JsonAnySetter
    fun rejectUnknownProperty(name: String, ignored: Any?) {
        throw IllegalArgumentException("unknown constant property field: $name")
    }

    internal fun toDomain(): SemanticConstantProperty =
        decodeSemanticTransport {
            SemanticConstantProperty(
                baseProperty = baseProperty.toSemanticElementName("baseProperty"),
                conversionProperty = conversionProperty.toSemanticElementName("conversionProperty"),
            )
        }

    internal companion object {
        fun fromDomain(property: SemanticConstantProperty): SemanticConstantPropertyDto =
            SemanticConstantPropertyDto(property.baseProperty.value, property.conversionProperty.value)
    }
}

data class SemanticModelResponseDto(
    val semanticModelId: String,
    val workspaceId: String,
    val definition: SemanticModelDefinitionDto,
    val sourcePartitionInfo: List<Map<String, Any?>>,
    val createdAt: String,
    val updatedAt: String,
) {
    internal companion object {
        fun fromDomain(view: SemanticModelView): SemanticModelResponseDto =
            SemanticModelResponseDto(
                semanticModelId = view.semanticModelId.value.toString(),
                workspaceId = view.workspaceId.value.toString(),
                definition = SemanticModelDefinitionDto.fromDomain(view.definition),
                sourcePartitionInfo = view.sourcePartitionInfo.map { it.toHttpValue() },
                createdAt = view.createdAt.toString(),
                updatedAt = view.updatedAt.toString(),
            )
    }
}

data class MetricResponseDto(
    val metricId: String,
    val workspaceId: String,
    val definition: MetricDefinitionDto,
    val sourcePartitionInfo: List<Map<String, Any?>>,
    val createdAt: String,
    val updatedAt: String,
) {
    internal companion object {
        fun fromDomain(view: MetricView): MetricResponseDto =
            MetricResponseDto(
                metricId = view.metricId.value.toString(),
                workspaceId = view.workspaceId.value.toString(),
                definition = MetricDefinitionDto.fromDomain(view.definition),
                sourcePartitionInfo = view.sourcePartitionInfo.map { it.toHttpValue() },
                createdAt = view.createdAt.toString(),
                updatedAt = view.updatedAt.toString(),
            )
    }
}

/** A recorded person; [displayName] is null when the account no longer has a readable name. */
data class SemanticActorDto(val id: String, val displayName: String?)

/** One current Semantic Model with its last recorded save: the person and the surface (WEB or AGENT) they used. */
data class SemanticModelDetailResponseDto(
    val semanticModelId: String,
    val workspaceId: String,
    val definition: SemanticModelDefinitionDto,
    val sourcePartitionInfo: List<Map<String, Any?>>,
    val createdAt: String,
    val updatedAt: String,
    val updatedBy: SemanticActorDto?,
    val credentialSurface: String?,
    val agentApp: cc.monomer.control.agentapp.AgentAppResponse?,
) {
    internal companion object {
        fun fromDomain(view: SemanticModelView, change: SemanticDefinitionChange?, names: Map<String, String>, agentApp: cc.monomer.control.agentapp.AgentAppResponse?): SemanticModelDetailResponseDto =
            SemanticModelDetailResponseDto(
                semanticModelId = view.semanticModelId.value.toString(),
                workspaceId = view.workspaceId.value.toString(),
                definition = SemanticModelDefinitionDto.fromDomain(view.definition),
                sourcePartitionInfo = view.sourcePartitionInfo.map { it.toHttpValue() },
                createdAt = view.createdAt.toString(),
                updatedAt = view.updatedAt.toString(),
                updatedBy = change?.actorId?.let { SemanticActorDto(it.toString(), names[it.toString()]) },
                credentialSurface = change?.credentialSurface?.name,
                agentApp = agentApp,
            )
    }
}

/** One current Metric with its last recorded save. */
data class MetricDetailResponseDto(
    val metricId: String,
    val workspaceId: String,
    val definition: MetricDefinitionDto,
    val sourcePartitionInfo: List<Map<String, Any?>>,
    val createdAt: String,
    val updatedAt: String,
    val updatedBy: SemanticActorDto?,
    val credentialSurface: String?,
    val agentApp: cc.monomer.control.agentapp.AgentAppResponse?,
) {
    internal companion object {
        fun fromDomain(view: MetricView, change: SemanticDefinitionChange?, names: Map<String, String>, agentApp: cc.monomer.control.agentapp.AgentAppResponse?): MetricDetailResponseDto =
            MetricDetailResponseDto(
                metricId = view.metricId.value.toString(),
                workspaceId = view.workspaceId.value.toString(),
                definition = MetricDefinitionDto.fromDomain(view.definition),
                sourcePartitionInfo = view.sourcePartitionInfo.map { it.toHttpValue() },
                createdAt = view.createdAt.toString(),
                updatedAt = view.updatedAt.toString(),
                updatedBy = change?.actorId?.let { SemanticActorDto(it.toString(), names[it.toString()]) },
                credentialSurface = change?.credentialSurface?.name,
                agentApp = agentApp,
            )
    }
}

internal suspend fun cc.monomer.identity.api.PrincipalDisplayNames.ofChange(change: SemanticDefinitionChange?): Map<String, String> =
    change?.actorId?.let { actor -> read(setOf(actor.value)).mapKeys { (id, _) -> id.toString() } } ?: emptyMap()

internal suspend fun cc.monomer.identity.api.AgentAppDisplays.ofChange(change: SemanticDefinitionChange?): cc.monomer.control.agentapp.AgentAppResponse? =
    change?.agentClientId?.let { clientId -> responses(listOf(clientId))[clientId] }
