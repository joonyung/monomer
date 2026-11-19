@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.semantic.adapter.outbound.postgres

import cc.monomer.connector.api.ConnectorId
import cc.monomer.semantic.api.SemanticConstantProperty
import cc.monomer.semantic.api.SemanticDimensionDefinition
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticElementReference
import cc.monomer.semantic.api.SemanticEntityDefinition
import cc.monomer.semantic.api.SemanticMetricDefinition
import cc.monomer.semantic.api.SemanticMetricFilter
import cc.monomer.semantic.api.SemanticMetricInput
import cc.monomer.semantic.api.SemanticMetricType
import cc.monomer.semantic.api.SemanticMetricWindow
import cc.monomer.semantic.api.SemanticModelDefinition
import cc.monomer.semantic.api.SemanticMeasureDefinition
import cc.monomer.semantic.api.SemanticNonAdditiveDimension
import cc.monomer.semantic.api.SemanticSourceRelation
import cc.monomer.semantic.api.SemanticTimeGranularity
import cc.monomer.semantic.api.SemanticTimeKind
import cc.monomer.semantic.api.SemanticTimeSpine
import cc.monomer.semantic.api.SemanticTimeSpineDefinition
import cc.monomer.semantic.api.SemanticTimeSpinePrimaryColumn
import cc.monomer.semantic.api.SemanticTimeSpineRelation
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.uuid.Uuid

/** Canonical JSON codec for the current Semantic definition rows. */
class SemanticPostgresJsonCodec(
    private val objectMapper: ObjectMapper,
) {
    fun modelDefinitionJson(definition: SemanticModelDefinition): String =
        objectMapper.writeValueAsString(modelMap(definition))

    fun parseModelDefinition(json: String): SemanticModelDefinition =
        parseModel(objectMapper.readTree(json))

    fun metricDefinitionJson(definition: SemanticMetricDefinition): String =
        objectMapper.writeValueAsString(metricMap(definition))

    fun parseMetricDefinition(json: String): SemanticMetricDefinition =
        parseMetric(objectMapper.readTree(json))

    fun timeSpineDefinitionJson(definition: SemanticTimeSpineDefinition): String =
        objectMapper.writeValueAsString(timeSpineDefinitionMap(definition))

    fun parseTimeSpineDefinition(json: String): SemanticTimeSpineDefinition =
        parseTimeSpineDefinition(objectMapper.readTree(json))

    private fun modelMap(definition: SemanticModelDefinition): Map<String, Any?> =
        buildMap {
            put("schemaVersion", definition.schemaVersion.toInt())
            put("name", definition.name.value)
            definition.label?.let { put("label", it) }
            definition.description?.let { put("description", it) }
            put("sourceRelations", definition.sourceRelations.map { relation ->
                buildMap {
                    put("connectorId", relation.connectorId.toString())
                    relation.catalog?.let { put("catalog", it) }
                    put("schema", relation.schema)
                    put("relation", relation.relation)
                }
            })
            definition.aggregationTimeDimension?.let { put("aggregationTimeDimension", it.value) }
            put("entities", definition.entities.map(::entityMap))
            put("dimensions", definition.dimensions.map(::dimensionMap))
            put("measures", definition.measures.map(::measureMap))
        }

    private fun timeSpineDefinitionMap(definition: SemanticTimeSpineDefinition): Map<String, Any?> =
        mapOf(
            "schemaVersion" to definition.schemaVersion.toInt(),
            "timeSpines" to definition.timeSpines.map { timeSpine ->
                mapOf(
                    "relation" to buildMap {
                        timeSpine.relation.catalog?.let { put("catalog", it) }
                        put("schema", timeSpine.relation.schema)
                        put("relation", timeSpine.relation.relation)
                    },
                    "primaryColumn" to mapOf(
                        "name" to timeSpine.primaryColumn.name,
                        "granularity" to timeSpine.primaryColumn.granularity.name.lowercase(),
                    ),
                )
            },
        )

    private fun entityMap(entity: SemanticEntityDefinition): Map<String, Any?> =
        buildMap {
            put("name", entity.name.value)
            put("type", entity.type.name.lowercase())
            put("expression", entity.expression)
            entity.label?.let { put("label", it) }
            entity.description?.let { put("description", it) }
        }

    private fun dimensionMap(dimension: SemanticDimensionDefinition): Map<String, Any?> =
        buildMap {
            put("name", dimension.name.value)
            put("type", dimension.type.name.lowercase())
            dimension.granularity?.let { put("granularity", it.name.lowercase()) }
            put("expression", dimension.expression)
            put("isPartition", dimension.isPartition)
            dimension.timeKind?.let { put("timeKind", it.name.lowercase()) }
            dimension.label?.let { put("label", it) }
            dimension.description?.let { put("description", it) }
        }

    private fun measureMap(measure: SemanticMeasureDefinition): Map<String, Any?> =
        buildMap {
            put("name", measure.name.value)
            put("aggregation", measure.aggregation.name.lowercase())
            put("expression", measure.expression)
            measure.aggregationTimeDimension?.let { put("aggregationTimeDimension", it.value) }
            measure.nonAdditiveDimension?.let { put("nonAdditiveDimension", nonAdditiveMap(it)) }
            measure.label?.let { put("label", it) }
            measure.description?.let { put("description", it) }
        }

    private fun nonAdditiveMap(value: SemanticNonAdditiveDimension): Map<String, Any?> =
        buildMap {
            put("name", value.name.value)
            put("windowChoice", value.windowChoice.name.lowercase())
            value.windowGroupings?.let { put("windowGroupings", it.map(SemanticElementName::value)) }
        }

    private fun metricMap(definition: SemanticMetricDefinition): Map<String, Any?> =
        buildMap {
            put("schemaVersion", definition.schemaVersion.toInt())
            put("name", definition.name.value)
            put("type", definition.type.name.lowercase())
            definition.label?.let { put("label", it) }
            definition.description?.let { put("description", it) }
            definition.display?.let { put("display", objectMapper.readTree(cc.monomer.semantic.api.MetricDisplayJsonCodec.encode(it).toString())) }
            definition.timeGranularity?.let { put("timeGranularity", it.name.lowercase()) }
            if (definition.filters.isNotEmpty()) put("filters", definition.filters.map(::filterMap))
            when (definition) {
                is SemanticMetricDefinition.Simple -> {
                    put("semanticModelName", definition.semanticModelName.value)
                    put("measure", definition.measure.value)
                }
                is SemanticMetricDefinition.Ratio -> {
                    put("numerator", inputMap(definition.numerator))
                    put("denominator", inputMap(definition.denominator))
                }
                is SemanticMetricDefinition.Derived -> {
                    put("expression", definition.expression)
                    put("inputMetrics", definition.inputMetrics.map(::inputMap))
                }
                is SemanticMetricDefinition.Cumulative -> {
                    put("inputMetric", inputMap(definition.inputMetric))
                    definition.window?.let { put("window", it.toString()) }
                    definition.grainToDate?.let { put("grainToDate", it.name.lowercase()) }
                    definition.periodAggregation?.let { put("periodAggregation", it.name.lowercase()) }
                }
                is SemanticMetricDefinition.Conversion -> {
                    put("entity", definition.entity.value)
                    put("calculation", definition.calculation.name.lowercase())
                    put("baseMetric", inputMap(definition.baseMetric))
                    put("conversionMetric", inputMap(definition.conversionMetric))
                    definition.window?.let { put("window", it.toString()) }
                    definition.constantProperties?.let { put("constantProperties", it.map(::constantPropertyMap)) }
                }
            }
        }

    private fun inputMap(input: SemanticMetricInput): Map<String, Any?> =
        buildMap {
            put("metricName", input.metricName.value)
            input.filter?.let { put("filter", filterMap(it)) }
            input.alias?.let { put("alias", it.value) }
            input.offsetWindow?.let { put("offsetWindow", it.toString()) }
            input.offsetToGrain?.let { put("offsetToGrain", it.name.lowercase()) }
        }

    private fun constantPropertyMap(property: SemanticConstantProperty): Map<String, Any?> =
        mapOf("baseProperty" to property.baseProperty.value, "conversionProperty" to property.conversionProperty.value)

    private fun filterMap(filter: SemanticMetricFilter): Map<String, Any?> =
        when (filter) {
            is SemanticMetricFilter.Predicate -> mapOf(
                "dimension" to dimensionReferenceMap(filter.dimension),
                "operator" to filter.operator.name.lowercase(),
                "values" to filter.values,
            )
            is SemanticMetricFilter.And -> mapOf("and" to filter.filters.map(::filterMap))
            is SemanticMetricFilter.Or -> mapOf("or" to filter.filters.map(::filterMap))
            is SemanticMetricFilter.Not -> mapOf("not" to filterMap(filter.filter))
        }

    private fun dimensionReferenceMap(reference: SemanticElementReference): Map<String, Any?> =
        buildMap {
            put("semanticModelName", reference.semanticModelName.value)
            put("name", reference.name.value)
            reference.granularity?.let { put("granularity", it.name.lowercase()) }
        }

    private fun parseModel(node: JsonNode): SemanticModelDefinition {
        node.requireExactFields(
            "schemaVersion", "name", "label", "description", "sourceRelations",
            "aggregationTimeDimension", "entities", "dimensions", "measures",
            optional = setOf("label", "description", "aggregationTimeDimension"),
        )
        return SemanticModelDefinition(
            schemaVersion = node.requiredInt("schemaVersion").toUInt(),
            name = SemanticElementName(node.requiredText("name")),
            label = node.optionalText("label"),
            description = node.optionalText("description"),
            sourceRelations = node.requiredArray("sourceRelations").map { relation ->
                relation.requireExactFields("connectorId", "catalog", "schema", "relation", optional = setOf("catalog"))
                SemanticSourceRelation(
                    connectorId = ConnectorId(Uuid.parse(relation.requiredText("connectorId"))),
                    catalog = relation.optionalText("catalog"),
                    schema = relation.requiredText("schema"),
                    relation = relation.requiredText("relation"),
                )
            },
            aggregationTimeDimension = node.optionalText("aggregationTimeDimension")?.let(::SemanticElementName),
            entities = node.requiredArray("entities").map { entity ->
                entity.requireExactFields("name", "type", "expression", "label", "description", optional = setOf("label", "description"))
                SemanticEntityDefinition(
                    name = SemanticElementName(entity.requiredText("name")),
                    type = enumValue(entity.requiredText("type")),
                    expression = entity.requiredText("expression"),
                    label = entity.optionalText("label"),
                    description = entity.optionalText("description"),
                )
            },
            dimensions = node.requiredArray("dimensions").map { dimension ->
                dimension.requireExactFields(
                    "name", "type", "granularity", "expression", "label", "description", "isPartition", "timeKind",
                    optional = setOf("granularity", "label", "description", "timeKind"),
                )
                SemanticDimensionDefinition(
                    name = SemanticElementName(dimension.requiredText("name")),
                    type = enumValue(dimension.requiredText("type")),
                    expression = dimension.requiredText("expression"),
                    granularity = dimension.optionalText("granularity")?.let(::parseGranularity),
                    label = dimension.optionalText("label"),
                    description = dimension.optionalText("description"),
                    isPartition = dimension.get("isPartition").let { require(it != null && it.isBoolean); it.booleanValue() },
                    timeKind = dimension.optionalText("timeKind")?.let { enumValue<SemanticTimeKind>(it) },
                )
            },
            measures = node.requiredArray("measures").map { measure ->
                measure.requireExactFields(
                    "name", "aggregation", "expression", "aggregationTimeDimension",
                    "nonAdditiveDimension", "label", "description",
                    optional = setOf("aggregationTimeDimension", "nonAdditiveDimension", "label", "description"),
                )
                SemanticMeasureDefinition(
                    name = SemanticElementName(measure.requiredText("name")),
                    aggregation = enumValue(measure.requiredText("aggregation")),
                    expression = measure.requiredText("expression"),
                    aggregationTimeDimension = measure.optionalText("aggregationTimeDimension")?.let(::SemanticElementName),
                    nonAdditiveDimension = measure.get("nonAdditiveDimension")?.let(::parseNonAdditive),
                    label = measure.optionalText("label"),
                    description = measure.optionalText("description"),
                )
            },
        )
    }

    private fun parseTimeSpineDefinition(node: JsonNode): SemanticTimeSpineDefinition {
        node.requireExactFields("schemaVersion", "timeSpines")
        return SemanticTimeSpineDefinition(
            schemaVersion = node.requiredInt("schemaVersion").toUInt(),
            timeSpines = node.requiredArray("timeSpines").map { timeSpine ->
                timeSpine.requireExactFields("relation", "primaryColumn")
                val relation = timeSpine.requiredNode("relation")
                relation.requireExactFields("catalog", "schema", "relation", optional = setOf("catalog"))
                val primaryColumn = timeSpine.requiredNode("primaryColumn")
                primaryColumn.requireExactFields("name", "granularity")
                SemanticTimeSpine(
                    relation = SemanticTimeSpineRelation(
                        catalog = relation.optionalText("catalog"),
                        schema = relation.requiredText("schema"),
                        relation = relation.requiredText("relation"),
                    ),
                    primaryColumn = SemanticTimeSpinePrimaryColumn(
                        name = primaryColumn.requiredText("name"),
                        granularity = parseGranularity(primaryColumn.requiredText("granularity")),
                    ),
                )
            },
        )
    }

    private fun parseNonAdditive(node: JsonNode): SemanticNonAdditiveDimension {
        node.requireExactFields("name", "windowChoice", "windowGroupings", optional = setOf("windowGroupings"))
        return SemanticNonAdditiveDimension(
            name = SemanticElementName(node.requiredText("name")),
            windowChoice = enumValue(node.requiredText("windowChoice")),
            windowGroupings = node.get("windowGroupings")?.let { array ->
                require(array.isArray) { "windowGroupings must be an array" }
                array.map { SemanticElementName(it.requiredTextValue()) }
            },
        )
    }

    private fun parseMetric(node: JsonNode): SemanticMetricDefinition {
        val common = CommonMetricFields(
            display = node.get("display")?.takeUnless { it.isNull }?.let { cc.monomer.semantic.api.MetricDisplayJsonCodec.decode(kotlinx.serialization.json.Json.parseToJsonElement(it.toString())) },
            schemaVersion = node.requiredInt("schemaVersion").toUInt(),
            name = SemanticElementName(node.requiredText("name")),
            label = node.optionalText("label"),
            description = node.optionalText("description"),
            timeGranularity = node.optionalText("timeGranularity")?.let(::parseGranularity),
            filters = node.get("filters")?.let { filters ->
                require(filters.isArray) { "metric filters must be an array" }
                filters.map(::parseFilter)
            }.orEmpty(),
        )
        return when (val type: SemanticMetricType = enumValue(node.requiredText("type"))) {
            SemanticMetricType.SIMPLE -> {
                node.requireExactFields(
                    "schemaVersion", "name", "type", "label", "description", "timeGranularity", "filters", "display",
                    "semanticModelName", "measure",
                    optional = setOf("label", "description", "timeGranularity", "filters", "display"),
                )
                SemanticMetricDefinition.Simple(
                    common.schemaVersion, common.name, common.label, common.description,
                    common.timeGranularity, common.filters,
                    SemanticElementName(node.requiredText("semanticModelName")),
                    SemanticElementName(node.requiredText("measure")),
                    display = common.display,
                )
            }
            SemanticMetricType.RATIO -> {
                node.requireExactFields(
                    "schemaVersion", "name", "type", "label", "description", "timeGranularity", "filters", "display",
                    "numerator", "denominator",
                    optional = setOf("label", "description", "timeGranularity", "filters", "display"),
                )
                SemanticMetricDefinition.Ratio(
                    common.schemaVersion, common.name, common.label, common.description,
                    common.timeGranularity, common.filters,
                    parseInput(node.requiredNode("numerator")),
                    parseInput(node.requiredNode("denominator")),
                    display = common.display,
                )
            }
            SemanticMetricType.DERIVED -> {
                node.requireExactFields(
                    "schemaVersion", "name", "type", "label", "description", "timeGranularity", "filters", "display",
                    "expression", "inputMetrics",
                    optional = setOf("label", "description", "timeGranularity", "filters", "display"),
                )
                SemanticMetricDefinition.Derived(
                    common.schemaVersion, common.name, common.label, common.description,
                    common.timeGranularity, common.filters,
                    node.requiredText("expression"),
                    node.requiredArray("inputMetrics").map(::parseInput),
                    display = common.display,
                )
            }
            SemanticMetricType.CUMULATIVE -> {
                node.requireExactFields(
                    "schemaVersion", "name", "type", "label", "description", "timeGranularity", "filters", "display",
                    "inputMetric", "window", "grainToDate", "periodAggregation",
                    optional = setOf("label", "description", "timeGranularity", "filters", "display", "window", "grainToDate", "periodAggregation"),
                )
                SemanticMetricDefinition.Cumulative(
                    common.schemaVersion, common.name, common.label, common.description,
                    common.timeGranularity, common.filters,
                    parseInput(node.requiredNode("inputMetric")),
                    node.optionalText("window")?.let(SemanticMetricWindow::parse),
                    node.optionalText("grainToDate")?.let(::parseGranularity),
                    node.optionalText("periodAggregation")?.let { enumValue(it) },
                    display = common.display,
                )
            }
            SemanticMetricType.CONVERSION -> {
                node.requireExactFields(
                    "schemaVersion", "name", "type", "label", "description", "timeGranularity", "filters", "display",
                    "entity", "calculation", "baseMetric", "conversionMetric", "window", "constantProperties",
                    optional = setOf("label", "description", "timeGranularity", "filters", "display", "window", "constantProperties"),
                )
                SemanticMetricDefinition.Conversion(
                    common.schemaVersion, common.name, common.label, common.description,
                    common.timeGranularity, common.filters,
                    SemanticElementName(node.requiredText("entity")),
                    enumValue(node.requiredText("calculation")),
                    parseInput(node.requiredNode("baseMetric")),
                    parseInput(node.requiredNode("conversionMetric")),
                    node.optionalText("window")?.let(SemanticMetricWindow::parse),
                    node.get("constantProperties")?.let { properties ->
                        require(properties.isArray) { "constantProperties must be an array" }
                        properties.map { property ->
                            property.requireExactFields("baseProperty", "conversionProperty")
                            SemanticConstantProperty(
                                SemanticElementName(property.requiredText("baseProperty")),
                                SemanticElementName(property.requiredText("conversionProperty")),
                            )
                        }
                    },
                    display = common.display,
                )
            }
        }
    }

    private fun parseInput(node: JsonNode): SemanticMetricInput {
        node.requireExactFields("metricName", "filter", "alias", "offsetWindow", "offsetToGrain", optional = setOf("filter", "alias", "offsetWindow", "offsetToGrain"))
        return SemanticMetricInput(
            metricName = SemanticElementName(node.requiredText("metricName")),
            filter = node.get("filter")?.let(::parseFilter),
            alias = node.optionalText("alias")?.let(::SemanticElementName),
            offsetWindow = node.optionalText("offsetWindow")?.let(SemanticMetricWindow::parse),
            offsetToGrain = node.optionalText("offsetToGrain")?.let(::parseGranularity),
        )
    }

    private fun parseFilter(node: JsonNode): SemanticMetricFilter {
        require(node.isObject) { "metric filter must be an object" }
        val fields = node.fieldNames().asSequence().toList()
        if ("dimension" in fields) {
            node.requireExactFields("dimension", "operator", "values")
            return SemanticMetricFilter.Predicate(
                dimension = parseDimensionReference(node.requiredNode("dimension")),
                operator = enumValue(node.requiredText("operator")),
                values = node.requiredArray("values").map { it.requiredTextValue(allowEmpty = true) },
            )
        }
        require(fields.size == 1) { "metric filter must have exactly one branch" }
        return when (fields.single()) {
            "and" -> SemanticMetricFilter.And(node.requiredArray("and").map(::parseFilter))
            "or" -> SemanticMetricFilter.Or(node.requiredArray("or").map(::parseFilter))
            "not" -> SemanticMetricFilter.Not(parseFilter(node.requiredNode("not")))
            else -> error("unknown metric filter branch")
        }
    }

    private fun parseDimensionReference(node: JsonNode): SemanticElementReference {
        node.requireExactFields("semanticModelName", "name", "granularity", optional = setOf("granularity"))
        return SemanticElementReference(
            semanticModelName = SemanticElementName(node.requiredText("semanticModelName")),
            name = SemanticElementName(node.requiredText("name")),
            granularity = node.optionalText("granularity")?.let(::parseGranularity),
        )
    }

    private fun parseGranularity(value: String): SemanticTimeGranularity = enumValue(value)

    private data class CommonMetricFields(
        val schemaVersion: UInt,
        val name: SemanticElementName,
        val label: String?,
        val description: String?,
        val timeGranularity: SemanticTimeGranularity?,
        val filters: List<SemanticMetricFilter>,
        val display: cc.monomer.semantic.api.MetricDisplay?,
    )
}

private inline fun <reified T : Enum<T>> enumValue(value: String): T =
    enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: throw IllegalArgumentException("unknown enum value: $value")

private fun JsonNode.requireExactFields(
    vararg requiredOrAllowed: String,
    optional: Set<String> = emptySet(),
) {
    val allowed = requiredOrAllowed.toSet()
    val actual = fieldNames().asSequence().toSet()
    require(actual.all { it in allowed }) {
        "unexpected JSON field(s): " + (actual - allowed)
    }
    val required = allowed - optional
    require(actual.containsAll(required)) {
        "missing JSON field(s): " + (required - actual)
    }
}

private fun JsonNode.requiredNode(field: String): JsonNode =
    get(field) ?: throw IllegalArgumentException("missing JSON field: " + field)

private fun JsonNode.requiredText(field: String): String =
    requiredNode(field).requiredTextValue()

private fun JsonNode.requiredTextValue(allowEmpty: Boolean = false): String {
    require(isTextual) { "JSON value must be a string" }
    if (!allowEmpty) require(textValue().isNotBlank()) { "JSON string must not be blank" }
    return textValue()
}

private fun JsonNode.requiredInt(field: String): Int {
    val value = requiredNode(field)
    require(value.isInt) { "JSON field " + field + " must be an integer" }
    return value.intValue()
}

private fun JsonNode.requiredArray(field: String): List<JsonNode> {
    val value = requiredNode(field)
    require(value.isArray) { "JSON field " + field + " must be an array" }
    return value.toList()
}

private fun JsonNode.optionalText(field: String): String? {
    val value = get(field) ?: return null
    require(value.isTextual) { "JSON field " + field + " must be a string" }
    require(value.textValue().isNotBlank()) { "JSON field " + field + " must not be blank" }
    return value.textValue()
}
