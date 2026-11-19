@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.domain

import cc.monomer.connector.api.ConnectorId
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardSessionId
import cc.monomer.dashboard.api.QueryRunId
import cc.monomer.execution.api.ArrowResultManifest
import cc.monomer.identity.api.WorkspaceId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId

enum class QueryRunState {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

data class QueryRun(
    val id: QueryRunId,
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val viewerSessionId: DashboardSessionId?,
    val queryName: String,
    val queryType: DashboardQueryType,
    val connectorId: ConnectorId,
    val resolvedQuery: DashboardResolvedQuery,
    val renderedSql: String?,
    val outputColumnNames: List<String>,
    val state: QueryRunState,
    val result: ArrowResultManifest?,
    val failureCode: String?,
    val failureMessage: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val actorId: String?,
    val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
    val agentClientId: cc.monomer.identity.api.AgentClientId?,
    val outputColumnDisplays: List<cc.monomer.semantic.api.SemanticOutputColumnDisplay>?,
    val reportingTimeZone: ZoneId?,
) {
    init {
        require(DashboardDefinition.isDashboardName(queryName))
        require(queryType == resolvedQuery.type)
        require(queryType == DashboardQueryType.SEMANTIC || reportingTimeZone == null) { "only Semantic query runs record a Reporting time zone" }
        cc.monomer.identity.api.requireRecordedAgentClient(credentialSurface, agentClientId)
        require(outputColumnNames.isNotEmpty())
        require(outputColumnNames.toSet().size == outputColumnNames.size)
        when (state) {
            QueryRunState.RUNNING,
            QueryRunState.CANCELLED,
            -> require(result == null && failureCode == null && failureMessage == null) {
                "$state query runs must not have terminal payload"
            }
            QueryRunState.SUCCEEDED -> require(result != null && failureCode == null && failureMessage == null) {
                "succeeded query runs must have exactly one result and no failure payload"
            }
            QueryRunState.FAILED -> require(result == null && !failureCode.isNullOrBlank()) {
                "failed query runs must have a code and no result"
            }
        }
        require(!updatedAt.isBefore(createdAt))
    }

    fun succeed(reference: ArrowResultManifest, at: Instant): QueryRun =
        terminal(QueryRunState.SUCCEEDED, reference, null, null, at)

    fun fail(code: String, message: String?, at: Instant): QueryRun =
        terminal(QueryRunState.FAILED, null, code, message, at)

    fun cancel(at: Instant): QueryRun =
        terminal(QueryRunState.CANCELLED, null, null, null, at)

    private fun terminal(
        nextState: QueryRunState,
        nextResult: ArrowResultManifest?,
        nextFailureCode: String?,
        nextFailureMessage: String?,
        at: Instant,
    ): QueryRun {
        require(state == QueryRunState.RUNNING) { "query run is already terminal" }
        require(!at.isBefore(updatedAt)) { "query run time must be monotonic" }
        return copy(
            state = nextState,
            result = nextResult,
            failureCode = nextFailureCode,
            failureMessage = nextFailureMessage,
            updatedAt = at,
        )
    }

    companion object {
        fun start(
            id: QueryRunId,
            workspaceId: WorkspaceId,
            dashboardId: DashboardId,
            viewerSessionId: DashboardSessionId?,
            query: DashboardNamedQuery,
            resolvedQuery: DashboardResolvedQuery,
            renderedSql: String,
            outputColumnNames: List<String>,
            startedAt: Instant,
            actorId: String?,
            credentialSurface: cc.monomer.identity.api.CredentialSurface?,
            agentClientId: cc.monomer.identity.api.AgentClientId?,
            outputColumnDisplays: List<cc.monomer.semantic.api.SemanticOutputColumnDisplay>?,
            reportingTimeZone: ZoneId?,
        ): QueryRun = QueryRun(
            id = id,
            workspaceId = workspaceId,
            dashboardId = dashboardId,
            viewerSessionId = viewerSessionId,
            queryName = query.name,
            queryType = query.type,
            connectorId = query.connectorId,
            resolvedQuery = resolvedQuery,
            renderedSql = renderedSql,
            outputColumnNames = outputColumnNames,
            state = QueryRunState.RUNNING,
            result = null,
            failureCode = null,
            failureMessage = null,
            createdAt = startedAt,
            updatedAt = startedAt,
            actorId = actorId,
            credentialSurface = credentialSurface,
            agentClientId = agentClientId,
            outputColumnDisplays = outputColumnDisplays,
            reportingTimeZone = reportingTimeZone,
        )
    }
}

data class DashboardSession(
    val id: DashboardSessionId,
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val definition: DashboardDefinition,
    val parameterBindings: Map<String, JsonElement>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(parameterBindings.keys == definition.parameters.keys) { "session parameter bindings must be complete" }
        require(!updatedAt.isBefore(createdAt))
        definition.parameters.forEach { (name, declaration) ->
            DashboardValueValidator.requireValid(parameterBindings.getValue(name), declaration.type, declaration.nullable, "parameter $name")
        }
        validateVariables(initialVariables)
    }

    val initialVariables: Map<String, JsonElement>
        get() = definition.variables.mapValues { (_, declaration) ->
            val value = declaration.initial
            if (value is JsonObject && value["kind"]?.jsonPrimitive?.content == "PARAMETER") {
                parameterBindings.getValue(value.getValue("name").jsonPrimitive.content)
            } else value
        }

    fun validateVariables(variables: Map<String, JsonElement>) {
        require(variables.keys == definition.variables.keys) { "session variables must contain every declared variable exactly once" }
        definition.variables.forEach { (name, declaration) ->
            DashboardValueValidator.requireValid(variables.getValue(name), declaration.type, declaration.nullable, "variable $name")
        }
    }

    companion object {
        fun open(
            id: DashboardSessionId,
            workspaceId: WorkspaceId,
            dashboardId: DashboardId,
            definition: DashboardDefinition,
            parameterBindings: Map<String, JsonElement>,
            openedAt: Instant,
        ): DashboardSession = DashboardSession(id, workspaceId, dashboardId, definition, parameterBindings.toMap(), openedAt, openedAt)
    }
}

internal object DashboardValueValidator {
    fun requireValid(
        value: JsonElement,
        type: DashboardValueType,
        nullable: Boolean,
        context: String,
    ) {
        if (value is JsonNull) {
            require(nullable) { "$context cannot be null" }
            return
        }
        when (type) {
            DashboardValueType.TEXT ->
                require(value is JsonPrimitive && value.isString) { "$context must be a string" }
            DashboardValueType.DATE -> DashboardJson.date(value, context)
            DashboardValueType.TIMESTAMP -> DashboardJson.timestamp(value, context)
            DashboardValueType.NUMBER -> {
                require(value is JsonPrimitive && value.isString) { "$context must be a canonical exact decimal string" }
                DashboardJson.decimal(value, context)
            }
            DashboardValueType.BOOLEAN ->
                require(value is JsonPrimitive && !value.isString && value.booleanOrNull != null) { "$context must be boolean" }
            DashboardValueType.TEXT_SET -> requireStringSet(value, context)
            DashboardValueType.DATE_SET -> requireSet(value, context, DashboardValueType.DATE)
            DashboardValueType.TIMESTAMP_SET -> requireSet(value, context, DashboardValueType.TIMESTAMP)
            DashboardValueType.NUMBER_SET ->
                requireSet(value, context, DashboardValueType.NUMBER)
            DashboardValueType.DATE_RANGE, DashboardValueType.TIMESTAMP_RANGE -> {
                val range = requireNotNull(value as? JsonObject) { "$context must be an object" }
                require(range.keys == setOf("start", "end")) { "$context must contain start and end" }
                val start = range.getValue("start")
                val end = range.getValue("end")
                require(start is JsonPrimitive && start.isString && end is JsonPrimitive && end.isString) {
                    "$context endpoints must be strings"
                }
                when (type) {
                    DashboardValueType.DATE_RANGE -> {
                        require(!DashboardJson.date(start, "$context.start").isAfter(DashboardJson.date(end, "$context.end"))) {
                            "$context.start must be less than or equal to $context.end"
                        }
                    }
                    DashboardValueType.TIMESTAMP_RANGE -> {
                        require(!DashboardJson.timestamp(start, "$context.start").isAfter(DashboardJson.timestamp(end, "$context.end"))) {
                            "$context.start must be less than or equal to $context.end"
                        }
                    }
                }
            }
        }
    }

    fun requireControl(control: JsonObject, type: DashboardValueType, nullable: Boolean, context: String) {
        require(control.isNotEmpty()) { "$context must not be empty" }
        val keys = control.keys
        if ("options" in keys) {
            require(!type.isRange) { "$context options are not allowed for $type" }
            require(keys - "emptyLabel" == setOf("options")) {
                "$context options cannot be combined with free-input fields"
            }
            control["emptyLabel"]?.let { label ->
                require(nullable) { "$context.emptyLabel requires a nullable value; remove it or declare nullable true" }
                DashboardJson.displayLabel(label, "$context.emptyLabel")
            }
            val options = requireNotNull(control.getValue("options") as? kotlinx.serialization.json.JsonArray) {
                "$context.options must be an array"
            }
            require(options.isNotEmpty() && options.size <= MAXIMUM_OPTIONS) {
                "$context.options must contain between 1 and $MAXIMUM_OPTIONS items"
            }
            val optionType = type.scalarTypeForControl
            val values = options.mapIndexed { index, rawOption ->
                val option = requireNotNull(rawOption as? JsonObject) { "$context.options[$index] must be an object" }
                DashboardJson.exactOptional(option, setOf("value"), setOf("label"), "$context.options[$index]")
                val value = option.getValue("value")
                requireValid(value, optionType, nullable = false, context = "$context.options[$index].value")
                option["label"]?.let { DashboardJson.optionalText(it, "$context.options[$index].label") }
                value
            }
            require(values.indices.all { index -> values.drop(index + 1).none { equal(values[index], it, optionType) } }) {
                "$context.options values must be unique"
            }
            return
        }

        val allowed = when (type) {
            DashboardValueType.TEXT, DashboardValueType.TEXT_SET -> setOf("placeholder")
            DashboardValueType.NUMBER, DashboardValueType.NUMBER_SET -> setOf("placeholder", "min", "max")
            DashboardValueType.BOOLEAN -> emptySet()
            DashboardValueType.DATE, DashboardValueType.DATE_SET -> setOf("min", "max")
            DashboardValueType.TIMESTAMP, DashboardValueType.TIMESTAMP_SET -> setOf("min", "max")
            DashboardValueType.DATE_RANGE, DashboardValueType.TIMESTAMP_RANGE -> setOf(
                "min", "max", "startPlaceholder", "endPlaceholder",
            )
        }
        require("emptyLabel" !in keys) { "$context.emptyLabel is allowed only with options" }
        require(keys.all { it in allowed }) { "$context contains fields not allowed for $type" }
        require(keys.isNotEmpty()) { "$context must declare a control field" }
        keys.filter { it == "placeholder" || it == "startPlaceholder" || it == "endPlaceholder" }
            .forEach { field -> DashboardJson.text(control.getValue(field), "$context.$field") }
        val boundType = type.scalarTypeForControl
        val min = control["min"]?.also { requireLiteral(it, boundType, "$context.min") }
        val max = control["max"]?.also { requireLiteral(it, boundType, "$context.max") }
        if (min != null && max != null) {
            require(compare(min, max, boundType) <= 0) { "$context.min must be less than or equal to $context.max" }
        }
    }

    private fun requireLiteral(value: JsonElement, type: DashboardValueType, context: String) {
        requireValid(value, type, nullable = false, context = context)
    }

    private fun compare(left: JsonElement, right: JsonElement, type: DashboardValueType): Int = when (type) {
        DashboardValueType.NUMBER -> DashboardJson.decimal(left, "control bound").compareTo(DashboardJson.decimal(right, "control bound"))
        DashboardValueType.DATE -> DashboardJson.date(left, "control bound").compareTo(DashboardJson.date(right, "control bound"))
        DashboardValueType.TIMESTAMP -> DashboardJson.timestamp(left, "control bound").compareTo(DashboardJson.timestamp(right, "control bound"))
        else -> error("$type does not support bounds")
    }

    private fun equal(left: JsonElement, right: JsonElement, type: DashboardValueType): Boolean = when (type) {
        DashboardValueType.TEXT -> left == right
        DashboardValueType.NUMBER -> DashboardJson.decimal(left, "control option").compareTo(DashboardJson.decimal(right, "control option")) == 0
        DashboardValueType.BOOLEAN -> left == right
        DashboardValueType.DATE -> DashboardJson.date(left, "control option") == DashboardJson.date(right, "control option")
        DashboardValueType.TIMESTAMP -> DashboardJson.timestamp(left, "control option") == DashboardJson.timestamp(right, "control option")
        else -> error("$type is not scalar")
    }

    private val DashboardValueType.isRange: Boolean
        get() = this == DashboardValueType.DATE_RANGE || this == DashboardValueType.TIMESTAMP_RANGE

    private val DashboardValueType.scalarTypeForControl: DashboardValueType
        get() = when (this) {
            DashboardValueType.TEXT_SET -> DashboardValueType.TEXT
            DashboardValueType.NUMBER_SET -> DashboardValueType.NUMBER
            DashboardValueType.DATE_SET, DashboardValueType.DATE_RANGE -> DashboardValueType.DATE
            DashboardValueType.TIMESTAMP_SET, DashboardValueType.TIMESTAMP_RANGE -> DashboardValueType.TIMESTAMP
            else -> this
        }

    private fun requireStringSet(value: JsonElement, context: String) {
        require(value is kotlinx.serialization.json.JsonArray && value.isNotEmpty() &&
            value.all { it is JsonPrimitive && it.isString } && value.toSet().size == value.size) {
            "$context must be a non-empty unique string array"
        }
    }

    private fun requireSet(
        value: JsonElement,
        context: String,
        scalarType: DashboardValueType,
    ) {
        val values = requireNotNull(value as? kotlinx.serialization.json.JsonArray) { "$context must be an array" }
        require(values.isNotEmpty()) { "$context must be a non-empty array" }
        values.forEachIndexed { index, element -> requireValid(element, scalarType, nullable = false, context = "$context[$index]") }
        require(values.indices.all { index -> values.drop(index + 1).none { equal(values[index], it, scalarType) } }) {
            "$context must be a unique array"
        }
    }

    private const val MAXIMUM_OPTIONS = 500
}
