@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.application

import cc.monomer.dashboard.domain.Dashboard
import cc.monomer.dashboard.domain.DashboardScheduleDefinition
import cc.monomer.dashboard.domain.DashboardDefinition
import cc.monomer.dashboard.domain.DashboardJson
import cc.monomer.dashboard.domain.DashboardMeta
import cc.monomer.dashboard.domain.DashboardSqlTemplate
import cc.monomer.dashboard.domain.DashboardValueRef
import cc.monomer.dashboard.domain.DashboardValueRefKind
import cc.monomer.dashboard.domain.DashboardRangeEndpoint
import cc.monomer.dashboard.domain.DashboardBindValue
import cc.monomer.dashboard.domain.DashboardNamedQuery
import cc.monomer.dashboard.domain.DashboardParameterDefinition
import cc.monomer.dashboard.domain.DashboardValueType
import cc.monomer.dashboard.domain.DashboardValueValidator
import cc.monomer.dashboard.domain.DashboardVariableDefinition
import cc.monomer.dashboard.domain.DashboardTablePivot
import cc.monomer.dashboard.domain.DashboardWidget
import cc.monomer.dashboard.domain.DashboardWidgetType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal

class InvalidDashboardDefinitionException internal constructor(cause: RuntimeException) :
    IllegalArgumentException(cause.message, cause)

/** Strict owner decoder for the one current Dashboard DSL document. */
object DashboardDefinitionDecoder {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = true }
    private val valueDefinitionRequired = setOf("type", "nullable")
    private val valueDefinitionOptional = setOf("label", "description", "control")
    private val semanticNamedQueryFields = setOf("type", "connectorId", "query")
    private val sqlQueryFields = setOf("type", "connectorId", "sql", "bindings", "columns", "rowLimit")
    private val semanticQueryFields = setOf(
        "schemaVersion",
        "metrics",
        "groupBys",
        "metricTimeRange",
        "filters",
        "orderBy",
        "rowLimit",
    )

    fun decode(document: String): DashboardDefinition {
        val tokens = Regex("\"(?:\\\\.|[^\"\\\\])*+\"|[{}\\[\\]:,]|[^\\s{}\\[\\]:,]+").findAll(document).map { it.value }.toList()
        val objects = ArrayDeque<MutableSet<String>>()
        tokens.forEachIndexed { index, token ->
            when (token) {
                "{" -> objects.addLast(mutableSetOf())
                "}" -> if (objects.isNotEmpty()) objects.removeLast()
                else -> if (token.startsWith('"') && tokens.getOrNull(index + 1) == ":") {
                    val key = json.decodeFromString<String>(token)
                    require(objects.lastOrNull()?.add(key) != false) { "duplicate JSON key: $key" }
                }
            }
        }
        return decode(json.parseToJsonElement(document) as? JsonObject ?: error("dashboard definition must be a JSON object"))
    }

    fun decode(document: JsonObject): DashboardDefinition = try {
        decodeDocument(document)
    } catch (error: IllegalArgumentException) {
        throw InvalidDashboardDefinitionException(error)
    } catch (error: IllegalStateException) {
        throw InvalidDashboardDefinitionException(error)
    }

    private fun decodeDocument(document: JsonObject): DashboardDefinition {
        DashboardJson.exactOptional(document, DashboardDefinition.ROOT_FIELDS, setOf("schedules"), "dashboard definition")
        require(DashboardJson.integer(document.getValue("schemaVersion"), "schemaVersion") == 1) {
            "dashboard schemaVersion must be 1"
        }
        val meta = decodeMeta(DashboardJson.objectNode(document.getValue("meta"), "meta"))
        val parameters = decodeValueDefinitions(
            DashboardJson.objectNode(document.getValue("parameters"), "parameters"),
            isVariable = false,
        )
        val variables = decodeValueDefinitions(
            DashboardJson.objectNode(document.getValue("variables"), "variables"),
            isVariable = true,
        )
        val parameterDefinitions = parameters.mapValues { (_, value) ->
            value as DashboardParameterDefinition
        }
        val variableDefinitions = variables.mapValues { (_, value) ->
            value as DashboardVariableDefinition
        }
        val queries = decodeQueries(DashboardJson.objectNode(document.getValue("queries"), "queries"))
        val widgets = decodeWidgets(DashboardJson.objectNode(document.getValue("widgets"), "widgets"))
        val layout = DashboardJson.objectNode(document.getValue("layout"), "layout")

        validateVariableReferences(variableDefinitions, parameterDefinitions)
        validateQueryReferences(queries, parameterDefinitions, variableDefinitions)
        validateTablePivots(widgets, queries)
        validateWidgetReferences(widgets, queries, parameterDefinitions, variableDefinitions)
        validateLayout(layout, widgets)

        return DashboardDefinition(
            document = document,
            meta = meta,
            parameters = parameterDefinitions,
            variables = variableDefinitions,
            queries = queries,
            widgets = widgets,
            layout = layout,
            schedules = document["schedules"]?.let { raw ->
                DashboardJson.objectNode(raw, "schedules").mapValues { (name, value) ->
                    require(DashboardDefinition.isDashboardName(name)) { "invalid Dashboard schedule name: $name" }
                    DashboardScheduleDefinition.decode(DashboardJson.objectNode(value, "schedule $name"), parameterDefinitions)
                }
            } ?: emptyMap(),
        )
    }

    private fun decodeMeta(node: JsonObject): DashboardMeta {
        DashboardJson.exactOptional(node, setOf("title"), setOf("description"), "dashboard meta")
        return DashboardMeta(
            title = DashboardJson.text(node.getValue("title"), "meta.title"),
            description = node["description"]?.let { DashboardJson.optionalText(it, "meta.description") },
        )
    }

    private fun decodeValueDefinitions(
        node: JsonObject,
        isVariable: Boolean,
    ): Map<String, Any> =
        node.entries.associate { (name, value) ->
            require(DashboardDefinition.isDashboardName(name)) { "invalid Dashboard value name: $name" }
            val definition = DashboardJson.objectNode(value, "value definition $name")
            val allowedOptional = valueDefinitionOptional - if (isVariable) setOf("control") else emptySet()
            DashboardJson.exactOptional(definition, valueDefinitionRequired, allowedOptional + if (isVariable) setOf("initial") else emptySet(), "value definition $name")
            if (isVariable) {
                require("initial" in definition) { "variable $name must declare initial" }
            }
            val type = decodeValueType(DashboardJson.text(definition.getValue("type"), "$name.type"))
            val nullable = DashboardJson.boolean(definition.getValue("nullable"), "$name.nullable")
            val initial = if (isVariable) definition.getValue("initial") else null
            val control = if (!isVariable) definition["control"]?.let {
                DashboardJson.objectNode(it, "$name.control").also { control ->
                    DashboardValueValidator.requireControl(control, type, nullable, "$name.control")
                }
            } else null
            if (initial != null && !isParameterReference(initial)) {
                DashboardValueValidator.requireValid(initial, type, nullable, "$name.initial")
            }
            val label = definition["label"]?.let { DashboardJson.optionalText(it, "$name.label") }
            val description = definition["description"]?.let { DashboardJson.optionalText(it, "$name.description") }
            if (isVariable) {
                name to DashboardVariableDefinition(name, type, nullable, label, description, initial!!)
            } else {
                name to DashboardParameterDefinition(name, type, nullable, label, description, control)
            }
        }

    private fun isParameterReference(value: JsonElement): Boolean =
        value is JsonObject && value["kind"]?.jsonPrimitive?.content == "PARAMETER"

    private fun decodeValueType(raw: String): DashboardValueType =
        runCatching { DashboardValueType.valueOf(raw) }
            .getOrElse { error("unknown Dashboard value type: $raw") }

    private fun decodeQueries(node: JsonObject): Map<String, DashboardNamedQuery> =
        node.entries.associate { (name, value) ->
            require(DashboardDefinition.isDashboardName(name)) { "invalid Dashboard query name: $name" }
            val query = DashboardJson.objectNode(value, "query $name")
            val connectorId = DashboardJson.connectorId(query.getValue("connectorId"), "queries.$name.connectorId")
            val namedQuery = when (DashboardJson.text(query["type"] ?: error("queries.$name.type is required"), "queries.$name.type")) {
                "SEMANTIC" -> {
                    DashboardJson.exact(query, semanticNamedQueryFields, "queries.$name")
                    val semanticQuery = DashboardJson.objectNode(query.getValue("query"), "queries.$name.query")
                    validateSemanticQuery(semanticQuery, name)
                    DashboardNamedQuery.Semantic(name, connectorId, semanticQuery)
                }
                "SQL" -> {
                    DashboardJson.exact(query, sqlQueryFields, "queries.$name")
                    val bindings = DashboardJson.objectNode(query.getValue("bindings"), "queries.$name.bindings").mapValues { (alias, raw) ->
                        val context = "queries.$name.bindings.$alias"
                        require(DashboardDefinition.isDashboardName(alias)) { "$context is not a valid alias" }
                        val ref = DashboardJson.objectNode(raw, context)
                        DashboardJson.exactOptional(ref, setOf("kind", "name"), setOf("endpoint"), context)
                        val kind = runCatching { DashboardValueRefKind.valueOf(DashboardJson.text(ref.getValue("kind"), "$context.kind")) }
                            .getOrElse { error("$context.kind must be PARAMETER or VARIABLE") }
                        val referenceName = DashboardJson.text(ref.getValue("name"), "$context.name")
                        require(DashboardDefinition.isDashboardName(referenceName)) { "$context.name is invalid" }
                        val endpoint = ref["endpoint"]?.let { value -> when (DashboardJson.text(value, "$context.endpoint")) {
                            "start" -> DashboardRangeEndpoint.START
                            "end" -> DashboardRangeEndpoint.END
                            else -> error("$context.endpoint must be start or end")
                        } }
                        DashboardValueRef(kind, referenceName, endpoint)
                    }
                    val source = query.getValue("sql")
                    require(source is JsonPrimitive && source.isString) { "queries.$name.sql must be a string" }
                    val template = DashboardSqlTemplate.compile(source.content, bindings.keys, "queries.$name.sql")
                    val columns = DashboardJson.arrayNode(query.getValue("columns"), "queries.$name.columns").mapIndexed { index, value ->
                        require(value is JsonPrimitive && value.isString) { "queries.$name.columns[$index] must be a string" }
                        value.content.also { column ->
                            require(column.isNotEmpty() && column.length <= 504 && column == column.trim() &&
                                java.text.Normalizer.isNormalized(column, java.text.Normalizer.Form.NFC) && column.none { Character.isISOControl(it) }) {
                                "queries.$name.columns[$index] must be a nonempty NFC column name without controls or surrounding whitespace, at most 504 characters"
                            }
                        }
                    }
                    require(columns.size in 1..1000 && columns.toSet().size == columns.size) { "queries.$name.columns must contain 1 to 1000 unique names" }
                    val rowLimit = DashboardJson.integer(query.getValue("rowLimit"), "queries.$name.rowLimit")
                    require(rowLimit in 1..100_000) { "queries.$name.rowLimit must be between 1 and 100000" }
                    DashboardNamedQuery.Sql(name, connectorId, template, bindings, columns, rowLimit)
                }
                else -> error("queries.$name.type must be SEMANTIC or SQL")
            }
            namedQuery.resultColumnNames()
            name to namedQuery
        }

    private fun validateSemanticQuery(node: JsonObject, queryName: String) {
        DashboardJson.exact(node, semanticQueryFields, "semantic query $queryName")
        require(DashboardJson.integer(node.getValue("schemaVersion"), "$queryName.schemaVersion") == 4) {
            "semantic query $queryName schemaVersion must be 4"
        }
        val metrics = DashboardJson.arrayNode(node.getValue("metrics"), "$queryName.metrics")
        require(metrics.isNotEmpty()) { "semantic query $queryName must select a metric" }
        metrics.forEachIndexed { index, value ->
            val metric = DashboardJson.text(value, "$queryName.metrics[$index]")
            require(DashboardDefinition.isSemanticName(metric)) { "invalid metric name: $metric" }
        }
        require(metrics.map { it.jsonPrimitive.content }.toSet().size == metrics.size) {
            "semantic query $queryName metrics must be unique"
        }
        val groupBys = DashboardJson.arrayNode(node.getValue("groupBys"), "$queryName.groupBys")
        require(groupBys.toSet().size == groupBys.size) { "semantic query $queryName groupBys must be unique" }
        require(groupBys.count { (it as? JsonObject)?.get("kind")?.jsonPrimitive?.content == "METRIC_TIME" } <= 1) {
            "semantic query $queryName may select at most one metric time group-by"
        }
        groupBys.forEachIndexed { index, value ->
            validateGroupBy(DashboardJson.objectNode(value, "$queryName.groupBys[$index]"), "$queryName.groupBys[$index]")
        }
        val timeRange = node.getValue("metricTimeRange")
        if (timeRange !is JsonNull) validateMetricTimeRange(DashboardJson.objectNode(timeRange, "$queryName.metricTimeRange"), queryName)
        val filters = DashboardJson.arrayNode(node.getValue("filters"), "$queryName.filters")
        require(filters.size <= 32) { "semantic query $queryName may contain at most 32 filters" }
        require(filters.toSet().size == filters.size) { "semantic query $queryName filters must be unique" }
        filters.forEachIndexed { index, value ->
            validateFilter(DashboardJson.objectNode(value, "$queryName.filters[$index]"), "$queryName.filters[$index]")
        }
        val orderBy = DashboardJson.arrayNode(node.getValue("orderBy"), "$queryName.orderBy")
        require(orderBy.toSet().size == orderBy.size) { "semantic query $queryName orderBy must be unique" }
        orderBy.forEachIndexed { index, value ->
            validateOrder(DashboardJson.objectNode(value, "$queryName.orderBy[$index]"), "$queryName.orderBy[$index]")
        }
        val rowLimit = DashboardJson.integer(node.getValue("rowLimit"), "$queryName.rowLimit")
        require(rowLimit in 1..100_000) { "semantic query rowLimit must be between 1 and 100000" }
    }

    private fun validateGroupBy(node: JsonObject, context: String) {
        val kind = DashboardJson.text(node["kind"] ?: error("$context.kind is missing"), "$context.kind")
        when (kind) {
            "DIMENSION" -> {
                DashboardJson.exactOptional(node, setOf("kind", "semanticModelName", "name"), setOf("granularity"), context)
                validateSemanticAddress(node, context)
                node["granularity"]?.let { validateGranularity(DashboardJson.text(it, "$context.granularity"), context) }
            }
            "ENTITY" -> {
                DashboardJson.exact(node, setOf("kind", "semanticModelName", "name"), context)
                validateSemanticAddress(node, context)
            }
            "METRIC_TIME" -> {
                DashboardJson.exact(node, setOf("kind", "granularity"), context)
                validateGranularity(DashboardJson.text(node.getValue("granularity"), "$context.granularity"), context)
            }
            else -> error("unknown group-by kind in $context: $kind")
        }
    }

    private fun validateMetricTimeRange(node: JsonObject, queryName: String) {
        if (node["kind"] != null) {
            DashboardJson.exact(node, setOf("kind", "name"), "$queryName.metricTimeRange")
            require(DashboardJson.text(node.getValue("kind"), "$queryName.metricTimeRange.kind") in setOf("PARAMETER", "VARIABLE")) {
                "$queryName.metricTimeRange.kind must be PARAMETER or VARIABLE"
            }
            DashboardJson.text(node.getValue("name"), "$queryName.metricTimeRange.name")
            return
        }
        DashboardJson.exact(node, setOf("start", "end"), "$queryName.metricTimeRange")
        requireMetricTimeEndpoint(node.getValue("start"), "$queryName.metricTimeRange.start")
        requireMetricTimeEndpoint(node.getValue("end"), "$queryName.metricTimeRange.end")
    }

    private fun validateFilter(node: JsonObject, context: String) {
        DashboardJson.exactOptional(node, setOf("kind", "dimension", "operator", "value"), setOf("emptyBehavior"), context)
        val kind = DashboardJson.text(node.getValue("kind"), "$context.kind")
        require(kind in setOf("TEXT", "NUMBER", "BOOLEAN", "DATE", "TIMESTAMP")) {
            "$context.kind must be a scalar filter kind"
        }
        val dimension = DashboardJson.objectNode(node.getValue("dimension"), "$context.dimension")
        DashboardJson.exact(dimension, setOf("semanticModelName", "name"), "$context.dimension")
        validateSemanticAddress(dimension, "$context.dimension")
        val operator = DashboardJson.text(node.getValue("operator"), "$context.operator")
        val allowedOperators = when (kind) {
            "TEXT" -> setOf("EQUALS", "NOT_EQUALS", "IN", "NOT_IN", "IS_NULL", "IS_NOT_NULL")
            "NUMBER", "DATE", "TIMESTAMP" -> setOf("EQUALS", "NOT_EQUALS", "IN", "NOT_IN", "GREATER_THAN", "GREATER_THAN_OR_EQUAL", "LESS_THAN", "LESS_THAN_OR_EQUAL", "IS_NULL", "IS_NOT_NULL")
            "BOOLEAN" -> setOf("EQUALS", "NOT_EQUALS", "IS_NULL", "IS_NOT_NULL")
            else -> emptySet()
        }
        require(operator in allowedOperators) { "$context.operator is invalid for $kind" }
        val value = node.getValue("value")
        if (operator == "IS_NULL" || operator == "IS_NOT_NULL") {
            require(value is JsonNull) { "$context.value must be null for a null operator" }
        } else if (operator == "IN" || operator == "NOT_IN") {
            requireValueSource(value, "$context.value", requireArray = true)
            if (value !is JsonObject) {
                val values = DashboardJson.arrayNode(value, "$context.value")
                require(values.isNotEmpty() && values.toSet().size == values.size) {
                    "$context.value must be a non-empty unique array"
                }
                values.forEachIndexed { index, literal ->
                    requireFilterLiteral(literal, kind, "$context.value[$index]")
                }
            }
        } else {
            requireValueSource(value, "$context.value")
            requireFilterLiteral(value, kind, context)
        }
        node["emptyBehavior"]?.let {
            require(DashboardJson.text(it, "$context.emptyBehavior") in setOf("OMIT", "ERROR")) {
                "$context.emptyBehavior must be OMIT or ERROR"
            }
        }
    }

    private fun validateOrder(node: JsonObject, context: String) {
        DashboardJson.exact(node, setOf("target", "direction"), context)
        val target = DashboardJson.objectNode(node.getValue("target"), "$context.target")
        val kind = DashboardJson.text(target["kind"] ?: error("$context.target.kind is missing"), "$context.target.kind")
        when (kind) {
            "METRIC" -> {
                DashboardJson.exact(target, setOf("kind", "metricName"), "$context.target")
                require(DashboardDefinition.isSemanticName(DashboardJson.text(target.getValue("metricName"), "$context.target.metricName")))
            }
            "GROUP_BY" -> {
                DashboardJson.exact(target, setOf("kind", "groupBy"), "$context.target")
                validateGroupBy(DashboardJson.objectNode(target.getValue("groupBy"), "$context.target.groupBy"), "$context.target.groupBy")
            }
            else -> error("unknown order target kind in $context: $kind")
        }
        require(DashboardJson.text(node.getValue("direction"), "$context.direction") in setOf("ASC", "DESC"))
    }

    private fun validateSemanticAddress(node: JsonObject, context: String) {
        require(DashboardDefinition.isSemanticName(DashboardJson.text(node.getValue("semanticModelName"), "$context.semanticModelName")))
        require(DashboardDefinition.isSemanticName(DashboardJson.text(node.getValue("name"), "$context.name")))
    }

    private fun validateGranularity(raw: String, context: String) {
        require(raw in setOf("NANOSECOND", "MICROSECOND", "MILLISECOND", "SECOND", "MINUTE", "HOUR", "DAY", "WEEK", "MONTH", "QUARTER", "YEAR")) {
            "$context.granularity is invalid"
        }
    }

    private fun decodeWidgets(node: JsonObject): Map<String, DashboardWidget> =
        node.entries.associate { (id, value) ->
            require(DashboardDefinition.isDashboardName(id)) { "invalid Dashboard widget id: $id" }
            val document = DashboardJson.objectNode(value, "widget $id")
            val type = runCatching { DashboardWidgetType.valueOf(DashboardJson.text(document["type"] ?: error("widget $id.type is missing"), "widget $id.type")) }
                .getOrElse { error("unknown widget type in $id") }
            val references = when (type) {
                DashboardWidgetType.CHART -> {
                    DashboardJson.exactOptional(document, setOf("type", "queryNames", "module"), setOf("title"), "widget $id")
                    document["title"]?.let { DashboardJson.displayLabel(it, "widget $id.title") }
                    val module = DashboardJson.objectNode(document.getValue("module"), "widget $id.module")
                    DashboardJson.exact(module, setOf("inputs", "source"), "widget $id.module")
                    val inputs = DashboardJson.objectNode(module.getValue("inputs"), "widget $id.module.inputs")
                    DashboardJson.exact(inputs, setOf("parameters", "variables"), "widget $id.module.inputs")
                    DashboardJson.arrayNode(inputs.getValue("parameters"), "widget $id.module.inputs.parameters")
                        .asStringList("widget $id.module.inputs.parameters", allowEmpty = true)
                    DashboardJson.arrayNode(inputs.getValue("variables"), "widget $id.module.inputs.variables")
                        .asStringList("widget $id.module.inputs.variables", allowEmpty = true)
                    val source = DashboardJson.text(module.getValue("source"), "widget $id.module.source")
                    require(source.toByteArray(Charsets.UTF_8).size <= MAXIMUM_WIDGET_SOURCE_BYTES) {
                        "widget $id chart source is too large"
                    }
                    val dataUris = dataUriLiteralPattern.findAll(source).map { it.groupValues[2] }.toList()
                    dataUris.forEach { uri ->
                        val match = requireNotNull(dataUriPattern.matchEntire(uri)) {
                            "widget $id chart data URI must be an inline base64 raster image"
                        }
                        require(match.groupValues[1].lowercase() in INLINE_IMAGE_MEDIA_TYPES) {
                            "widget $id chart data URI media type is not allowed"
                        }
                        val encodedBytes = match.groupValues[2].length
                        val padding = match.groupValues[2].count { it == '=' }
                        val decodedBytes = (encodedBytes * 3 / 4 - padding)
                        require(decodedBytes <= MAXIMUM_DECODED_DATA_URI_BYTES) {
                            "widget $id chart decoded data URI is too large"
                        }
                    }
                    require(dataUris.all { it.length <= MAXIMUM_DATA_URI_LENGTH }) {
                        "widget $id chart data URI is too large"
                    }
                    require(dataUris.sumOf(String::length) <= MAXIMUM_DATA_URI_BYTES) {
                        "widget $id chart data URIs are too large"
                    }
                    require(dataUris.sumOf { uri ->
                        val payload = dataUriPattern.matchEntire(uri)!!.groupValues[2]
                        payload.length * 3 / 4 - payload.count { it == '=' }
                    } <= MAXIMUM_TOTAL_DECODED_DATA_URI_BYTES) {
                        "widget $id decoded chart data URIs are too large"
                    }
                    WidgetQueryReferences(
                        DashboardJson.arrayNode(document.getValue("queryNames"), "widget $id.queryNames").asStringList("widget $id.queryNames"),
                        tablePivot = null,
                    )
                }
                DashboardWidgetType.TABLE -> {
                    require(("columns" in document) != ("pivot" in document)) {
                        "widget $id: a TABLE needs exactly one of columns or pivot"
                    }
                    val layoutField = if ("columns" in document) "columns" else "pivot"
                    DashboardJson.exactOptional(document, setOf("type", "queryName", layoutField), setOf("title", "rowHeight", "module"), "widget $id")
                    document["title"]?.let { DashboardJson.displayLabel(it, "widget $id.title") }
                    val tablePivot = document["pivot"]?.let { decodeTablePivot(it, "widget $id.pivot") }
                    if (tablePivot == null) {
                        val columns = DashboardJson.arrayNode(document.getValue("columns"), "widget $id.columns")
                        require(columns.isNotEmpty()) { "widget $id.columns must contain at least one column" }
                        val fields = columns.map { DashboardJson.text(DashboardJson.objectNode(it, "widget $id.columns" ).getValue("field"), "widget $id.columns.field") }
                        require(fields.toSet().size == fields.size) { "widget $id.columns fields must be unique" }
                        columns.forEachIndexed { index, rawColumn ->
                            val column = DashboardJson.objectNode(rawColumn, "widget $id.columns[$index]")
                            DashboardJson.exactOptional(column, setOf("field"), setOf("header", "width"), "widget $id.columns[$index]")
                            DashboardJson.text(column.getValue("field"), "widget $id.columns[$index].field")
                            column["header"]?.let { DashboardJson.optionalText(it, "widget $id.columns[$index].header") }
                            column["width"]?.let {
                                require(it is JsonPrimitive && !it.isString && DashboardJson.integer(it, "widget $id.columns[$index].width") in 80..640) {
                                    "widget $id.columns[$index].width must be between 80 and 640"
                                }
                            }
                        }
                    }
                    document["rowHeight"]?.let {
                        require(it is JsonPrimitive && !it.isString && DashboardJson.integer(it, "widget $id.rowHeight") in 32..160) {
                            "widget $id.rowHeight must be between 32 and 160"
                        }
                    }
                    val primaryQueryName = DashboardJson.text(document.getValue("queryName"), "widget $id.queryName")
                    val auxiliaryQueryNames = document["module"]?.let { rawModule ->
                        val module = DashboardJson.objectNode(rawModule, "widget $id.module")
                        DashboardJson.exact(module, setOf("inputs", "source"), "widget $id.module")
                        val inputs = DashboardJson.objectNode(module.getValue("inputs"), "widget $id.module.inputs")
                        DashboardJson.exact(inputs, setOf("queries", "parameters", "variables"), "widget $id.module.inputs")
                        val source = DashboardJson.text(module.getValue("source"), "widget $id.module.source")
                        require(source.toByteArray(Charsets.UTF_8).size <= MAXIMUM_WIDGET_SOURCE_BYTES) {
                            "widget $id table source is too large"
                        }
                        DashboardJson.arrayNode(inputs.getValue("queries"), "widget $id.module.inputs.queries")
                            .asStringList("widget $id.module.inputs.queries", allowEmpty = true)
                    }.orEmpty()
                    require(primaryQueryName !in auxiliaryQueryNames) {
                        "widget $id auxiliary queries must not repeat the primary query"
                    }
                    val totalsQueryNames = tablePivot?.totalsQueryNames.orEmpty()
                    totalsQueryNames.forEachIndexed { index, totalsQueryName ->
                        require(totalsQueryName != primaryQueryName) {
                            "widget $id.pivot.totals[$index] must not repeat the primary query $primaryQueryName"
                        }
                        require(totalsQueryName !in auxiliaryQueryNames) {
                            "widget $id.pivot.totals[$index] query $totalsQueryName must not also be a module.inputs.queries entry"
                        }
                    }
                    WidgetQueryReferences(listOf(primaryQueryName) + auxiliaryQueryNames + totalsQueryNames, tablePivot)
                }
                DashboardWidgetType.TEXT -> {
                    DashboardJson.exact(document, setOf("type", "content"), "widget $id")
                    require(DashboardJson.text(document.getValue("content"), "widget $id.content").length <= 128_000)
                    WidgetQueryReferences(emptyList(), tablePivot = null)
                }
                DashboardWidgetType.INPUT -> {
                    DashboardJson.exactOptional(document, setOf("type", "variableName"), setOf("control"), "widget $id")
                    document["control"]?.let { DashboardJson.objectNode(it, "widget $id.control") }
                    WidgetQueryReferences(emptyList(), tablePivot = null)
                }
            }
            id to DashboardWidget(
                id = id,
                type = type,
                orderedQueryNames = references.orderedQueryNames,
                queryNames = references.orderedQueryNames.toSet(),
                variableName = document["variableName"]?.let { DashboardJson.text(it, "widget $id.variableName") },
                tablePivot = references.tablePivot,
                document = document,
            )
        }

    private data class WidgetQueryReferences(
        val orderedQueryNames: List<String>,
        val tablePivot: DashboardTablePivot?,
    )

    private data class PivotFieldPlacement(val path: String, val field: String)

    private fun DashboardTablePivot.fieldPlacements(): List<PivotFieldPlacement> =
        rowFields.mapIndexed { index, field -> PivotFieldPlacement("rows[$index].field", field) } +
            columnFields.mapIndexed { index, field -> PivotFieldPlacement("columns[$index].field", field) } +
            valueFields.mapIndexed { index, field -> PivotFieldPlacement("values[$index].field", field) }

    private fun decodeTablePivot(node: JsonElement, context: String): DashboardTablePivot {
        val pivot = DashboardJson.objectNode(node, context)
        DashboardJson.exactOptional(pivot, setOf("rows", "columns", "values", "valuesAxis"), setOf("totals"), context)
        val rows = DashboardJson.arrayNode(pivot.getValue("rows"), "$context.rows")
        require(rows.size <= 8) { "$context.rows must contain at most 8 fields" }
        val columns = DashboardJson.arrayNode(pivot.getValue("columns"), "$context.columns")
        require(columns.size in 1..3) { "$context.columns must contain 1 to 3 fields" }
        val values = DashboardJson.arrayNode(pivot.getValue("values"), "$context.values")
        require(values.size in 1..20) { "$context.values must contain 1 to 20 fields" }
        val tablePivot = DashboardTablePivot(
            rowFields = rows.mapIndexed { index, row -> decodePivotTableColumn(row, "$context.rows[$index]") },
            columnFields = columns.mapIndexed { index, column -> decodePivotAxisField(column, "$context.columns[$index]") },
            valueFields = values.mapIndexed { index, value -> decodePivotTableColumn(value, "$context.values[$index]") },
            totalsQueryNames = pivot["totals"]?.let { decodePivotTotals(it, "$context.totals") }.orEmpty(),
        )
        require(pivotString(pivot.getValue("valuesAxis"), "$context.valuesAxis") in setOf("COLUMNS", "ROWS")) {
            "$context.valuesAxis must be COLUMNS or ROWS"
        }
        val placements = tablePivot.fieldPlacements()
        placements.forEachIndexed { index, placement ->
            val earlier = placements.take(index).firstOrNull { it.field == placement.field }
            require(earlier == null) {
                "$context.${placement.path} repeats ${placement.field} from $context.${earlier?.path}; rows, columns, and values fields must be distinct"
            }
        }
        return tablePivot
    }

    private fun decodePivotTableColumn(node: JsonElement, context: String): String {
        val column = DashboardJson.objectNode(node, context)
        DashboardJson.exactOptional(column, setOf("field"), setOf("header", "width"), context)
        column["header"]?.let { decodePivotHeader(it, "$context.header") }
        column["width"]?.let {
            require(it is JsonPrimitive && !it.isString && DashboardJson.integer(it, "$context.width") in 80..640) {
                "$context.width must be between 80 and 640"
            }
        }
        return pivotString(column.getValue("field"), "$context.field")
    }

    private fun decodePivotAxisField(node: JsonElement, context: String): String {
        val axisField = DashboardJson.objectNode(node, context)
        DashboardJson.exactOptional(axisField, setOf("field"), setOf("header"), context)
        axisField["header"]?.let { decodePivotHeader(it, "$context.header") }
        return pivotString(axisField.getValue("field"), "$context.field")
    }

    private fun decodePivotHeader(node: JsonElement, context: String) {
        if (node !is JsonNull) pivotString(node, context)
    }

    private fun decodePivotTotals(node: JsonElement, context: String): List<String> {
        val totals = DashboardJson.arrayNode(node, context)
        require(totals.size in 1..3) { "$context must contain 1 to 3 query names; omit totals for a pivot without totals" }
        val queryNames = totals.mapIndexed { index, value ->
            pivotString(value, "$context[$index]").also { queryName ->
                require(DashboardDefinition.isDashboardName(queryName)) { "$context[$index] is not a valid query name" }
            }
        }
        require(queryNames.toSet().size == queryNames.size) { "$context must contain unique query names" }
        return queryNames
    }

    private fun pivotString(node: JsonElement, context: String): String {
        require(node is JsonPrimitive && node.isString) { "$context must be a string" }
        return DashboardJson.text(node, context)
    }

    private fun validateVariableReferences(
        variables: Map<String, DashboardVariableDefinition>,
        parameters: Map<String, DashboardParameterDefinition>,
    ) {
        variables.values.forEach { variable ->
            val initial = variable.initial
            if (initial is JsonObject && initial["kind"] != null) {
                DashboardJson.exact(initial, setOf("kind", "name"), "${variable.name}.initial reference")
                require(DashboardJson.text(initial.getValue("kind"), "${variable.name}.initial.kind") == "PARAMETER")
                val parameterName = DashboardJson.text(initial.getValue("name"), "${variable.name}.initial.name")
                val parameter = parameters[parameterName] ?: error("unknown parameter reference: $parameterName")
                require(parameter.type == variable.type) { "variable ${variable.name} initial parameter type differs" }
                require(!(!variable.nullable && parameter.nullable)) { "non-nullable variable cannot reference nullable parameter" }
            }
        }
    }

    private fun validateQueryReferences(
        queries: Map<String, DashboardNamedQuery>,
        parameters: Map<String, DashboardParameterDefinition>,
        variables: Map<String, DashboardVariableDefinition>,
    ) {
        queries.values.forEach { query ->
            if (query is DashboardNamedQuery.Sql) {
                query.bindings.forEach { (alias, ref) ->
                    val context = "queries.${query.name}.bindings.$alias"
                    val declared = when (ref.kind) {
                        DashboardValueRefKind.PARAMETER -> parameters[ref.name]?.type
                        DashboardValueRefKind.VARIABLE -> variables[ref.name]?.type
                    } ?: error("$context references unknown ${ref.kind} ${ref.name}")
                    val scalar = if (ref.endpoint == null) declared else when (declared) {
                        DashboardValueType.DATE_RANGE -> DashboardValueType.DATE
                        DashboardValueType.TIMESTAMP_RANGE -> DashboardValueType.TIMESTAMP
                        else -> error("$context.endpoint requires DATE_RANGE or TIMESTAMP_RANGE")
                    }
                    require(scalar in DashboardBindValue.scalarTypes) { "$context must reference a scalar value; SET bindings and ranges without endpoint are excluded" }
                }
                return@forEach
            }
            query as DashboardNamedQuery.Semantic
            val filters = DashboardJson.arrayNode(query.query.getValue("filters"), "${query.name}.filters")
            filters.forEachIndexed { index, rawFilter ->
                val filter = DashboardJson.objectNode(rawFilter, "${query.name}.filters[$index]")
                val kind = DashboardJson.text(filter.getValue("kind"), "${query.name}.filters[$index].kind")
                val operator = DashboardJson.text(filter.getValue("operator"), "${query.name}.filters[$index].operator")
                val expectedType = filterValueType(kind)
                val expected = if (operator == "IN" || operator == "NOT_IN") expectedType.toSetType() else expectedType
                val value = filter.getValue("value")
                if (value is JsonObject && value["kind"]?.jsonPrimitive?.content in setOf("PARAMETER", "VARIABLE")) {
                    val referenceKind = value.getValue("kind").jsonPrimitive.content
                    val referenceName = value.getValue("name").jsonPrimitive.content
                    val nullable = when (referenceKind) {
                        "PARAMETER" -> parameters[referenceName]?.nullable
                        "VARIABLE" -> variables[referenceName]?.nullable
                        else -> null
                    } ?: error("${query.name}.filters[$index].value references unknown $referenceKind $referenceName")
                    require(!nullable || filter["emptyBehavior"] != null) {
                        "${query.name}.filters[$index].value requires emptyBehavior for a nullable reference"
                    }
                }
                validateValueReference(
                    value,
                    parameters,
                    variables,
                    expected,
                    "${query.name}.filters[$index].value",
                )
            }
            val metricTimeRange = query.query.getValue("metricTimeRange")
            if (metricTimeRange !is kotlinx.serialization.json.JsonNull) {
                val range = DashboardJson.objectNode(metricTimeRange, "${query.name}.metricTimeRange")
                if (range["kind"] != null) {
                    validateWholeMetricTimeRangeReference(range, parameters, variables, "${query.name}.metricTimeRange")
                } else {
                    val startType = metricTimeEndpointType(
                        range.getValue("start"),
                        parameters,
                        variables,
                        "${query.name}.metricTimeRange.start",
                    )
                    val endType = metricTimeEndpointType(
                        range.getValue("end"),
                        parameters,
                        variables,
                        "${query.name}.metricTimeRange.end",
                    )
                    require(startType == endType) {
                        "${query.name}.metricTimeRange endpoints must have the same type"
                    }
                    validateValueReference(
                        range.getValue("start"),
                        parameters,
                        variables,
                        expected = null,
                        context = "${query.name}.metricTimeRange.start",
                    )
                    validateValueReference(
                        range.getValue("end"),
                        parameters,
                        variables,
                        expected = null,
                        context = "${query.name}.metricTimeRange.end",
                    )
                }
            }
        }
    }

    private fun validateWholeMetricTimeRangeReference(
        node: JsonObject,
        parameters: Map<String, DashboardParameterDefinition>,
        variables: Map<String, DashboardVariableDefinition>,
        context: String,
    ) {
        DashboardJson.exact(node, setOf("kind", "name"), context)
        val kind = DashboardJson.text(node.getValue("kind"), "$context.kind")
        val name = DashboardJson.text(node.getValue("name"), "$context.name")
        val type = when (kind) {
            "PARAMETER" -> parameters[name]?.type
            "VARIABLE" -> variables[name]?.type
            else -> null
        } ?: error("$context references unknown $kind $name")
        require(type == DashboardValueType.DATE_RANGE || type == DashboardValueType.TIMESTAMP_RANGE) {
            "$context must reference a DATE_RANGE or TIMESTAMP_RANGE value"
        }
    }

    private fun validateTablePivots(
        widgets: Map<String, DashboardWidget>,
        queries: Map<String, DashboardNamedQuery>,
    ) {
        widgets.values.forEach { widget ->
            val tablePivot = widget.tablePivot ?: return@forEach
            val context = "widget ${widget.id}.pivot"
            val primaryQueryName = widget.orderedQueryNames.first()
            val primaryQuery = queries[primaryQueryName]
                ?: error("widget ${widget.id} references unknown query $primaryQueryName")
            val resultColumnNames = primaryQuery.resultColumnNames()
            tablePivot.fieldPlacements().forEach { placement ->
                require(placement.field in resultColumnNames) {
                    "$context.${placement.path} ${placement.field} is not a result column of query $primaryQueryName; result columns: $resultColumnNames"
                }
            }
            if (primaryQuery is DashboardNamedQuery.Semantic) {
                validateSemanticPivotFields(tablePivot, primaryQuery, context)
            }
            if (tablePivot.totalsQueryNames.isNotEmpty()) {
                validatePivotTotals(tablePivot, primaryQuery, queries, "$context.totals")
            }
        }
    }

    private fun validateSemanticPivotFields(
        tablePivot: DashboardTablePivot,
        primaryQuery: DashboardNamedQuery.Semantic,
        context: String,
    ) {
        val groupByKeys = primaryQuery.groupByResultKeys()
        val metricKeys = primaryQuery.metricResultKeys()
        tablePivot.rowFields.forEachIndexed { index, field ->
            require(field in groupByKeys) {
                "$context.rows[$index].field $field must be a group-by result key of SEMANTIC query ${primaryQuery.name}; group-by keys: $groupByKeys"
            }
        }
        tablePivot.columnFields.forEachIndexed { index, field ->
            require(field in groupByKeys) {
                "$context.columns[$index].field $field must be a group-by result key of SEMANTIC query ${primaryQuery.name}; group-by keys: $groupByKeys"
            }
        }
        tablePivot.valueFields.forEachIndexed { index, field ->
            require(field in metricKeys) {
                "$context.values[$index].field $field must be a metric result key of SEMANTIC query ${primaryQuery.name}; metric keys: $metricKeys"
            }
        }
        val unplacedGroupByKeys = groupByKeys - (tablePivot.rowFields + tablePivot.columnFields).toSet()
        require(unplacedGroupByKeys.isEmpty()) {
            "$context rows and columns must place every group-by result key of SEMANTIC query ${primaryQuery.name}; unplaced: $unplacedGroupByKeys"
        }
    }

    private fun validatePivotTotals(
        tablePivot: DashboardTablePivot,
        primaryQuery: DashboardNamedQuery,
        queries: Map<String, DashboardNamedQuery>,
        context: String,
    ) {
        require(primaryQuery is DashboardNamedQuery.Semantic) {
            "$context requires a SEMANTIC primary query; query ${primaryQuery.name} is ${primaryQuery.type}"
        }
        val primaryGroupByKeys = primaryQuery.groupByResultKeys().toSet()
        val rowKeys = tablePivot.rowFields.toSet()
        val columnKeys = tablePivot.columnFields.toSet()
        val primaryMetricTimeRange = primaryQuery.query.getValue("metricTimeRange")
        val primaryFilterCounts = semanticFilterCounts(primaryQuery)
        val totalsGroupByKeys = tablePivot.totalsQueryNames.mapIndexed { index, totalsQueryName ->
            val location = "$context[$index]"
            val totalsQuery = queries[totalsQueryName] ?: error("$location references unknown query $totalsQueryName")
            require(totalsQuery is DashboardNamedQuery.Semantic) {
                "$location query $totalsQueryName must be SEMANTIC; it is ${totalsQuery.type}"
            }
            require(totalsQuery.connectorId == primaryQuery.connectorId) {
                "$location query $totalsQueryName must use the connector of primary query ${primaryQuery.name}"
            }
            val missingValueMetrics = tablePivot.valueFields - totalsQuery.metricResultKeys().toSet()
            require(missingValueMetrics.isEmpty()) {
                "$location query $totalsQueryName must select every values metric; missing: $missingValueMetrics"
            }
            require(totalsQuery.query.getValue("metricTimeRange") == primaryMetricTimeRange) {
                "$location query $totalsQueryName metricTimeRange must equal the metricTimeRange of primary query ${primaryQuery.name}"
            }
            require(semanticFilterCounts(totalsQuery) == primaryFilterCounts) {
                "$location query $totalsQueryName filters must equal the filters of primary query ${primaryQuery.name}, in any order"
            }
            val groupByKeys = totalsQuery.groupByResultKeys().toSet()
            require(groupByKeys != primaryGroupByKeys) {
                "$location query $totalsQueryName groups by the same keys as primary query ${primaryQuery.name}, so it is not a total"
            }
            require(groupByKeys == rowKeys || groupByKeys == columnKeys || groupByKeys.isEmpty()) {
                "$location query $totalsQueryName group-by result keys must equal the rows fields $rowKeys (a total column), " +
                    "the columns fields $columnKeys (a total row), or be empty (the grand total); found $groupByKeys"
            }
            groupByKeys
        }
        totalsGroupByKeys.forEachIndexed { index, groupByKeys ->
            val earlierIndex = totalsGroupByKeys.take(index).indexOf(groupByKeys)
            require(earlierIndex < 0) {
                "$context[$index] query ${tablePivot.totalsQueryNames[index]} fills the same total placement as " +
                    "$context[$earlierIndex] query ${tablePivot.totalsQueryNames[earlierIndex]}; each placement takes one query " +
                    "(with empty rows, the total column and the grand total are the same placement)"
            }
        }
    }

    private fun semanticFilterCounts(query: DashboardNamedQuery.Semantic): Map<JsonElement, Int> =
        DashboardJson.arrayNode(query.query.getValue("filters"), "${query.name}.filters").groupingBy { it }.eachCount()

    private fun validateWidgetReferences(
        widgets: Map<String, DashboardWidget>,
        queries: Map<String, DashboardNamedQuery>,
        parameters: Map<String, DashboardParameterDefinition>,
        variables: Map<String, DashboardVariableDefinition>,
    ) {
        val referenced = widgets.values.flatMap { it.queryNames }.toSet()
        require(referenced == queries.keys) {
            "every Dashboard query must be referenced exactly by the widget registry; referenced=$referenced queries=${queries.keys}"
        }
        widgets.values.forEach { widget ->
            if (widget.type == DashboardWidgetType.INPUT) {
                val variableName = widget.variableName ?: error("input widget ${widget.id} has no variable")
                val variable = variables[variableName]
                    ?: error("input widget ${widget.id} references unknown variable $variableName")
                widget.document["control"]?.let {
                    DashboardValueValidator.requireControl(
                        DashboardJson.objectNode(it, "widget ${widget.id}.control"),
                        variable.type,
                        variable.nullable,
                        "widget ${widget.id}.control",
                    )
                }
            }
            if (widget.type == DashboardWidgetType.CHART || (widget.type == DashboardWidgetType.TABLE && "module" in widget.document)) {
                val inputs = DashboardJson.objectNode(
                    DashboardJson.objectNode(widget.document.getValue("module"), "widget ${widget.id}.module").getValue("inputs"),
                    "widget ${widget.id}.module.inputs",
                )
                val parameterNames = DashboardJson.arrayNode(inputs.getValue("parameters"), "widget ${widget.id}.parameters").asStringList("widget ${widget.id}.parameters", allowEmpty = true)
                val variableNames = DashboardJson.arrayNode(inputs.getValue("variables"), "widget ${widget.id}.variables").asStringList("widget ${widget.id}.variables", allowEmpty = true)
                require(parameterNames.toSet().size == parameterNames.size) { "widget ${widget.id} parameter inputs must be unique" }
                require(variableNames.toSet().size == variableNames.size) { "widget ${widget.id} variable inputs must be unique" }
                require(parameterNames.all { it in parameters.keys }) { "widget ${widget.id} references unknown parameter" }
                require(variableNames.all { it in variables.keys }) { "widget ${widget.id} references unknown variable" }
            }
            widget.queryNames.forEach { queryName ->
                require(queryName in queries) { "widget ${widget.id} references unknown query $queryName" }
            }
        }
    }

    private fun validateLayout(layout: JsonObject, widgets: Map<String, DashboardWidget>) {
        DashboardJson.exact(layout, setOf("sections"), "dashboard layout")
        val sections = DashboardJson.arrayNode(layout.getValue("sections"), "dashboard layout.sections")
        val ids = mutableSetOf<String>()
        val placedWidgets = mutableListOf<String>()
        sections.forEachIndexed { index, value ->
            val section = DashboardJson.objectNode(value, "layout.sections[$index]")
            DashboardJson.exact(section, setOf("id", "height", "root"), "layout.sections[$index]")
            val sectionId = DashboardJson.text(section.getValue("id"), "layout.sections[$index].id")
            require(ids.add(sectionId)) { "duplicate layout id: $sectionId" }
            val height = DashboardJson.objectNode(section.getValue("height"), "layout.sections[$index].height")
            val root = DashboardJson.objectNode(section.getValue("root"), "layout.sections[$index].root")
            val rootValidation = validateLayoutNode(root, null, widgets, ids, placedWidgets)
            when (DashboardJson.text(height["mode"] ?: error("layout.sections[$index].height.mode is missing"), "layout.sections[$index].height.mode")) {
                "CONTENT" -> {
                    DashboardJson.exact(height, setOf("mode"), "layout.sections[$index].height")
                    require(!rootValidation.needsAllottedHeight) {
                        "layout.sections[$index].height: CONTENT cannot contain CHART, TABLE, or a SPACER directly inside a COLUMN; use SCALED"
                    }
                }
                "SCALED" -> {
                    DashboardJson.exact(height, setOf("mode", "scale"), "layout.sections[$index].height")
                    require(rootValidation.needsAllottedHeight) {
                        "layout.sections[$index].height: SCALED needs a CHART, TABLE, or a SPACER directly inside a COLUMN; use CONTENT for TEXT and INPUT"
                    }
                    val scaleNode = height.getValue("scale")
                    require(scaleNode is JsonPrimitive && !scaleNode.isString) { "layout.sections[$index].height.scale must be a number" }
                    val scale = DashboardJson.number(scaleNode, "layout.sections[$index].height.scale")
                    require(scale >= BigDecimal("0.3") && scale <= BigDecimal("10.0") && scale.stripTrailingZeros().scale() <= 1) {
                        "layout.sections[$index].height.scale must be between 0.3 and 10.0 in steps of 0.1"
                    }
                }
                else -> error("layout.sections[$index].height.mode must be CONTENT or SCALED")
            }
            require(rootValidation.widgetCount > 0) { "layout.sections[$index] must contain a widget node" }
        }
        require(placedWidgets.size == placedWidgets.toSet().size && placedWidgets.toSet() == widgets.keys) {
            "every widget must be placed exactly once; placed=${placedWidgets.toSet()} widgets=${widgets.keys}"
        }
    }

    private data class LayoutNodeValidation(val widgetCount: Int, val needsAllottedHeight: Boolean)

    private fun validateLayoutNode(
        node: JsonObject,
        parentDirection: String?,
        widgets: Map<String, DashboardWidget>,
        ids: MutableSet<String>,
        placedWidgets: MutableList<String>,
    ): LayoutNodeValidation {
        val type = DashboardJson.text(node["type"] ?: error("layout node type is missing"), "layout node.type")
        val id = DashboardJson.text(node["id"] ?: error("layout node id is missing"), "layout node.id")
        require(ids.add(id)) { "duplicate layout id: $id" }
        return when (type) {
            "WIDGET" -> {
                DashboardJson.exact(node, setOf("type", "id", "widgetId"), "layout widget $id")
                val widgetId = DashboardJson.text(node.getValue("widgetId"), "layout widget $id.widgetId")
                val widget = widgets[widgetId] ?: error("layout widget $id.widgetId references unknown widget $widgetId")
                placedWidgets += widgetId
                LayoutNodeValidation(1, widget.type in setOf(DashboardWidgetType.CHART, DashboardWidgetType.TABLE))
            }
            "SPACER" -> {
                DashboardJson.exact(node, setOf("type", "id"), "layout spacer $id")
                LayoutNodeValidation(0, parentDirection == "COLUMN")
            }
            "STACK" -> {
                DashboardJson.exactOptional(
                    node,
                    setOf("type", "id", "direction", "children"),
                    setOf("separation"),
                    "layout stack $id",
                )
                val direction = DashboardJson.text(node.getValue("direction"), "layout stack $id.direction")
                require(direction in setOf("ROW", "COLUMN")) { "layout stack $id direction is invalid" }
                node["separation"]?.let {
                    require(DashboardJson.text(it, "layout stack $id.separation") in setOf("GAP", "DIVIDER")) {
                        "layout stack $id separation is invalid"
                    }
                }
                val children = DashboardJson.arrayNode(node.getValue("children"), "layout stack $id.children")
                require(children.size >= 2) { "layout stack $id needs at least two children" }
                val weights = mutableListOf<Int>()
                var needsAllottedHeight = false
                var itemCount = 0
                var previousWasSpacer = false
                children.forEachIndexed { index, child ->
                    val wrapper = DashboardJson.objectNode(child, "layout stack $id.children[$index]")
                    val location = "layout stack $id.children[$index]"
                    DashboardJson.exactOptional(wrapper, setOf("node"), setOf("weight"), location)
                    val childNode = DashboardJson.objectNode(wrapper.getValue("node"), "$location.node")
                    val childType = DashboardJson.text(childNode["type"] ?: error("layout child node.type is missing"), "layout child node.type")
                    require(!(previousWasSpacer && childType == "SPACER")) {
                        "layout stack $id must not contain adjacent SPACER children"
                    }
                    previousWasSpacer = childType == "SPACER"
                    val childValidation = validateLayoutNode(childNode, direction, widgets, ids, placedWidgets)
                    itemCount += childValidation.widgetCount
                    needsAllottedHeight = needsAllottedHeight || childValidation.needsAllottedHeight
                    val weight = wrapper["weight"]?.let {
                        DashboardJson.integer(it, "$location.weight").also { value ->
                            require(value > 0) { "$location.weight must be positive; use an integer of at least 1" }
                        }
                    }
                    if (direction == "ROW") {
                        require(weight != null) { "$location: ROW children need weight" }
                    } else if (childValidation.needsAllottedHeight) {
                        require(weight != null) { "$location: a COLUMN child with CHART, TABLE, or a SPACER directly inside a COLUMN needs weight" }
                    } else {
                        require(weight == null) { "$location: a COLUMN child with only TEXT or INPUT uses its content height; remove weight" }
                    }
                    weight?.let(weights::add)
                }
                require(weights.isEmpty() || weights.reduce(::greatestCommonDivisor) == 1) {
                    "layout stack $id weights must have greatest common divisor 1; divide all weights by their greatest common divisor (a sole weight must be 1)"
                }
                LayoutNodeValidation(itemCount, needsAllottedHeight)
            }
            else -> error("unknown layout node type: $type")
        }
    }

    private fun greatestCommonDivisor(left: Int, right: Int): Int {
        var a = left
        var b = right
        while (b != 0) {
            val remainder = a % b
            a = b
            b = remainder
        }
        return a
    }

    private fun validateValueReference(
        value: JsonElement,
        parameters: Map<String, DashboardParameterDefinition>,
        variables: Map<String, DashboardVariableDefinition>,
        expected: DashboardValueType?,
        context: String,
    ) {
        if (value !is JsonObject || value["kind"]?.jsonPrimitive?.content !in setOf("PARAMETER", "VARIABLE")) return
        requireQueryValueReference(value, context)
        val kind = DashboardJson.text(value.getValue("kind"), "$context.kind")
        val name = DashboardJson.text(value.getValue("name"), "$context.name")
        val declarationType = when (kind) {
            "PARAMETER" -> parameters[name]?.type
            "VARIABLE" -> variables[name]?.type
            else -> null
        } ?: error("$context references unknown $kind $name")
        val declarationNullable = when (kind) {
            "PARAMETER" -> parameters[name]?.nullable
            "VARIABLE" -> variables[name]?.nullable
            else -> null
        } ?: error("$context references unknown $kind $name")
        val referenceType = projectedReferenceType(value, declarationType, context)
        if (expected != null) {
            require(referenceType == expected) {
                "$context type $referenceType does not match expected $expected"
            }
        } else {
            require(!declarationNullable) { "$context cannot reference a nullable value" }
            require(referenceType in setOf(DashboardValueType.DATE, DashboardValueType.TIMESTAMP)) {
                "$context must reference a DATE or TIMESTAMP value"
            }
        }
    }

    private fun metricTimeEndpointType(
        value: JsonElement,
        parameters: Map<String, DashboardParameterDefinition>,
        variables: Map<String, DashboardVariableDefinition>,
        context: String,
    ): DashboardValueType {
        if (value is JsonObject && value["kind"]?.jsonPrimitive?.content in setOf("PARAMETER", "VARIABLE")) {
            val kind = value.getValue("kind").jsonPrimitive.content
            val name = DashboardJson.text(value.getValue("name"), "$context.name")
            return when (kind) {
                "PARAMETER" -> parameters[name]?.type
                "VARIABLE" -> variables[name]?.type
                else -> null
            }?.let { projectedReferenceType(value, it, context) }?.also {
                require(it == DashboardValueType.DATE || it == DashboardValueType.TIMESTAMP) {
                    "$context must reference a DATE or TIMESTAMP value"
                }
            } ?: error("$context references unknown $kind $name")
        }
        val endpoint = DashboardJson.objectNode(value, context)
        val kind = DashboardJson.text(endpoint.getValue("kind"), "$context.kind")
        require(kind in setOf("DATE", "TIMESTAMP")) { "$context.kind must be DATE or TIMESTAMP" }
        return if (kind == "DATE") DashboardValueType.DATE else DashboardValueType.TIMESTAMP
    }

    private fun requireQueryValueReference(value: JsonObject, context: String) {
        DashboardJson.exactOptional(value, setOf("kind", "name"), setOf("endpoint"), "$context reference")
        require(DashboardJson.text(value.getValue("kind"), "$context.kind") in setOf("PARAMETER", "VARIABLE"))
        value["endpoint"]?.let {
            require(DashboardJson.text(it, "$context.endpoint") in setOf("start", "end")) {
                "$context.endpoint must be start or end"
            }
        }
    }

    private fun projectedReferenceType(
        value: JsonObject,
        declarationType: DashboardValueType,
        context: String,
    ): DashboardValueType {
        if ("endpoint" !in value) return declarationType
        return when (declarationType) {
            DashboardValueType.DATE_RANGE -> DashboardValueType.DATE
            DashboardValueType.TIMESTAMP_RANGE -> DashboardValueType.TIMESTAMP
            else -> error("$context.endpoint requires a DATE_RANGE or TIMESTAMP_RANGE value")
        }
    }

    private fun requireValueSource(value: JsonElement, context: String, requireArray: Boolean = false) {
        require(value !is JsonNull) { "$context must be explicit; use a nullable ref for null" }
        if (value is JsonObject && value["kind"] != null) {
            requireQueryValueReference(value, context)
        } else if (requireArray) {
            val array = value as? kotlinx.serialization.json.JsonArray ?: error("$context must be an array")
            require(array.isNotEmpty()) { "$context must be a non-empty array" }
        }
    }

    private fun requireFilterLiteral(value: JsonElement, kind: String, context: String) {
        if (value is JsonObject && value["kind"] != null) return
        when (kind) {
            "TEXT" -> require(value is JsonPrimitive && value.isString) { "$context must be a string" }
            "DATE" -> require(value is JsonPrimitive && value.isString) { "$context must be a date string" }
                .also { DashboardJson.date(value, context) }
            "TIMESTAMP" -> require(value is JsonPrimitive && value.isString) { "$context must be a timestamp string" }
                .also { DashboardJson.timestamp(value, context) }
            "NUMBER" -> DashboardJson.decimal(value, context)
            "BOOLEAN" -> require(value is JsonPrimitive && value.booleanOrNull != null) {
                "$context must be boolean"
            }
            else -> error("unknown filter kind: $kind")
        }
    }

    private fun requireMetricTimeEndpoint(value: JsonElement, context: String) {
        if (value is JsonObject && value["kind"]?.jsonPrimitive?.content in setOf("PARAMETER", "VARIABLE")) {
            requireQueryValueReference(value, context)
            return
        }
        val endpoint = DashboardJson.objectNode(value, context)
        DashboardJson.exact(endpoint, setOf("kind", "value"), context)
        when (DashboardJson.text(endpoint.getValue("kind"), "$context.kind")) {
            "DATE" -> DashboardJson.date(endpoint.getValue("value"), "$context.value")
            "TIMESTAMP" -> DashboardJson.timestamp(endpoint.getValue("value"), "$context.value")
            else -> error("$context.kind must be DATE or TIMESTAMP")
        }
    }

    private fun filterValueType(kind: String): DashboardValueType = when (kind) {
        "TEXT" -> DashboardValueType.TEXT
        "NUMBER" -> DashboardValueType.NUMBER
        "BOOLEAN" -> DashboardValueType.BOOLEAN
        "DATE" -> DashboardValueType.DATE
        "TIMESTAMP" -> DashboardValueType.TIMESTAMP
        else -> error("unknown filter kind: $kind")
    }

    private fun DashboardValueType.toSetType(): DashboardValueType = when (this) {
        DashboardValueType.TEXT -> DashboardValueType.TEXT_SET
        DashboardValueType.NUMBER -> DashboardValueType.NUMBER_SET
        DashboardValueType.BOOLEAN -> error("BOOLEAN filters do not support set operators")
        DashboardValueType.DATE -> DashboardValueType.DATE_SET
        DashboardValueType.TIMESTAMP -> DashboardValueType.TIMESTAMP_SET
        else -> error("filter type cannot be converted to a set: $this")
    }

    private fun JsonArray.asStringList(context: String, allowEmpty: Boolean = false): List<String> {
        val values = mapIndexed { index, value -> DashboardJson.text(value, "$context[$index]") }
        require((allowEmpty || values.isNotEmpty()) && values.toSet().size == values.size) { "$context must contain unique names" }
        return values
    }

    private const val MAXIMUM_WIDGET_SOURCE_BYTES = 256 * 1_024
    private const val MAXIMUM_DATA_URI_LENGTH = 128_000
    private const val MAXIMUM_DATA_URI_BYTES = 256_000
    private const val MAXIMUM_DECODED_DATA_URI_BYTES = 96_000
    private const val MAXIMUM_TOTAL_DECODED_DATA_URI_BYTES = 192_000
    private val INLINE_IMAGE_MEDIA_TYPES = setOf("image/png", "image/jpeg", "image/webp")
    private val dataUriLiteralPattern = Regex("""(["'`])(?:image://)?(data:[^"'`]*)\1""", RegexOption.IGNORE_CASE)
    private val dataUriPattern = Regex("data:([^,;\\\"'`\\s]+);base64,([A-Za-z0-9+/=]+)", RegexOption.IGNORE_CASE)
}
