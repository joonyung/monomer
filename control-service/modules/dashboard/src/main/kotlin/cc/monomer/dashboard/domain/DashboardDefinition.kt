@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.domain

import cc.monomer.connector.api.ConnectorId
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.identity.api.WorkspaceId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.Instant
import kotlin.uuid.Uuid

private val identifierPattern = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
private val semanticNamePattern = Regex("^(?![a-z0-9_]*__)[a-z](?:[a-z0-9_]*[a-z0-9])?$")

enum class DashboardValueType {
    TEXT,
    NUMBER,
    BOOLEAN,
    DATE,
    TIMESTAMP,
    TEXT_SET,
    NUMBER_SET,
    DATE_SET,
    TIMESTAMP_SET,
    DATE_RANGE,
    TIMESTAMP_RANGE,
}

data class DashboardMeta(
    val title: String,
    val description: String?,
)

data class DashboardParameterDefinition(
    val name: String,
    val type: DashboardValueType,
    val nullable: Boolean,
    val label: String?,
    val description: String?,
    val control: JsonObject?,
) {
    init {
        control?.let { DashboardValueValidator.requireControl(it, type, nullable, "$name.control") }
    }
}

data class DashboardVariableDefinition(
    val name: String,
    val type: DashboardValueType,
    val nullable: Boolean,
    val label: String?,
    val description: String?,
    val initial: JsonElement,
)

enum class DashboardWidgetType {
    CHART,
    TABLE,
    TEXT,
    INPUT,
}

data class DashboardTablePivot(
    val rowFields: List<String>,
    val columnFields: List<String>,
    val valueFields: List<String>,
    val totalsQueryNames: List<String>,
)

data class DashboardWidget(
    val id: String,
    val type: DashboardWidgetType,
    val orderedQueryNames: List<String>,
    val queryNames: Set<String>,
    val variableName: String?,
    val tablePivot: DashboardTablePivot?,
    val document: JsonObject,
)

/**
 * One complete, validated current Dashboard DSL document.
 *
 * The raw document is intentionally kept as JSON: the Dashboard owner validates the
 * shared Semantic query shape but does not create a second Semantic query model.
 */
@ConsistentCopyVisibility
data class DashboardDefinition internal constructor(
    val document: JsonObject,
    val meta: DashboardMeta,
    val parameters: Map<String, DashboardParameterDefinition>,
    val variables: Map<String, DashboardVariableDefinition>,
    val queries: Map<String, DashboardNamedQuery>,
    val widgets: Map<String, DashboardWidget>,
    val layout: JsonObject,
    val schedules: Map<String, DashboardScheduleDefinition>,
) {
    init {
        require(document.keys - "schedules" == ROOT_FIELDS) { "dashboard definition root fields are not canonical" }
        require(schedules.keys.all(::isDashboardName)) { "dashboard schedule names are invalid" }
        require(queries.keys.all(::isDashboardName)) { "dashboard query names are invalid" }
        require(variables.keys.all(::isDashboardName)) { "dashboard variable names are invalid" }
        require(parameters.keys.all(::isDashboardName)) { "dashboard parameter names are invalid" }
        require(widgets.keys.all(::isDashboardName)) { "dashboard widget ids are invalid" }
    }

    fun query(name: String): DashboardNamedQuery =
        queries[name] ?: error("dashboard query is not declared: $name")

    companion object {
        val ROOT_FIELDS = setOf(
            "schemaVersion",
            "meta",
            "parameters",
            "variables",
            "queries",
            "widgets",
            "layout",
        )

        fun isDashboardName(value: String): Boolean =
            value.isNotBlank() &&
                value != "__proto__" &&
                identifierPattern.matches(value) &&
                value.length <= 128

        fun isSemanticName(value: String): Boolean =
            value.isNotBlank() &&
                semanticNamePattern.matches(value) &&
                value.length <= 128
    }
}

data class Dashboard(
    val id: DashboardId,
    val workspaceId: WorkspaceId,
    val definition: DashboardDefinition,
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: String?,
    val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
    val agentClientId: cc.monomer.identity.api.AgentClientId?,
) {
    init {
        cc.monomer.identity.api.requireRecordedAgentClient(credentialSurface, agentClientId)
    }
}

data class DashboardListItem(
    val id: DashboardId,
    val workspaceId: WorkspaceId,
    val title: String,
    val description: String?,
    val updatedAt: Instant,
)

/**
 * Workspace Dashboard list row computed from stored facts only.
 *
 * latestResultsAt is the latest settlement time of a SUCCEEDED Viewer Session QueryRun; Draft runs are excluded.
 * draftHasChanges is true only when the shared Draft exists and its definition differs from the current definition.
 */
data class DashboardListEntry(
    val id: DashboardId,
    val workspaceId: WorkspaceId,
    val title: String,
    val description: String?,
    val updatedAt: Instant,
    val updatedBy: String?,
    val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
    val agentClientId: cc.monomer.identity.api.AgentClientId?,
    val latestResultsAt: Instant?,
    val schedules: List<DashboardListSchedule>,
    val draftHasChanges: Boolean,
    val draftLastEditedAt: Instant?,
    val draftLastEditedBy: String?,
    val draftLastEditSurface: cc.monomer.identity.api.CredentialSurface?,
    val draftLastEditAgentClientId: cc.monomer.identity.api.AgentClientId?,
)

data class DashboardListSchedule(
    val key: String,
    val cron: String,
    val timeZone: String,
)

internal object DashboardJson {
    fun text(node: JsonElement, field: String): String {
        val value = node.jsonPrimitive.content
        require(value.isNotBlank()) { "$field must not be blank" }
        require(value.none { it == '\u0000' }) { "$field must not contain NUL" }
        return value
    }

    fun optionalText(node: JsonElement, field: String): String? {
        if (node is JsonNull) return null
        return text(node, field)
    }

    fun displayLabel(node: JsonElement, field: String): String {
        require(node is JsonPrimitive && node.isString) { "$field must be a string" }
        val value = node.content
        require(value.isNotEmpty() && value.length <= MAXIMUM_DISPLAY_LABEL_LENGTH) {
            "$field must contain 1 to $MAXIMUM_DISPLAY_LABEL_LENGTH characters"
        }
        require(value == value.trim()) { "$field must not start or end with whitespace" }
        require(value.none { it == '\n' || it == '\r' || it == '\u0000' }) { "$field must be a single line" }
        return value
    }

    fun boolean(node: JsonElement, field: String): Boolean {
        val primitive = node.jsonPrimitive
        require(primitive.isString.not()) { "$field must be boolean" }
        return primitive.booleanOrNull ?: error("$field must be boolean")
    }

    fun integer(node: JsonElement, field: String): Int {
        val value = node.jsonPrimitive.intOrNull ?: error("$field must be integer")
        return value
    }

    fun number(node: JsonElement, field: String): BigDecimal =
        node.jsonPrimitive.content.toBigDecimalOrNull() ?: error("$field must be number")

    fun decimal(node: JsonElement, field: String): BigDecimal {
        val value = text(node, field)
        require(canonicalDecimalPattern.matches(value)) { "$field must be a canonical exact decimal string" }
        val decimal = value.toBigDecimal()
        requireFiniteScale(decimal, field)
        return decimal
    }

    fun date(node: JsonElement, field: String): LocalDate {
        val value = text(node, field)
        require(value.length == 10) { "$field must be an ISO local date" }
        return try {
            LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE).also {
                require(it.toString() == value) { "$field must be an ISO local date" }
            }
        } catch (error: DateTimeParseException) {
            throw IllegalArgumentException("$field must be an ISO local date", error)
        }
    }

    fun timestamp(node: JsonElement, field: String): Instant {
        val value = text(node, field)
        return try {
            OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (error: DateTimeParseException) {
            throw IllegalArgumentException("$field must be an RFC3339 timestamp", error)
        }
    }

    fun objectNode(node: JsonElement, field: String): JsonObject =
        node as? JsonObject ?: error("$field must be object")

    fun arrayNode(node: JsonElement, field: String): JsonArray =
        node as? JsonArray ?: error("$field must be array")

    fun exact(node: JsonObject, fields: Set<String>, context: String) {
        require(node.keys == fields) {
            val unknown = node.keys - fields
            val missing = fields - node.keys
            "$context fields are invalid; unknown=$unknown missing=$missing"
        }
    }

    fun exactOptional(node: JsonObject, required: Set<String>, optional: Set<String>, context: String) {
        require(node.keys.containsAll(required)) { "$context is missing required fields" }
        require(node.keys.subtract(required + optional).isEmpty()) {
            "$context contains unknown fields: ${node.keys.subtract(required + optional)}"
        }
    }

    fun connectorId(node: JsonElement, field: String): ConnectorId =
        ConnectorId(Uuid.parse(text(node, field)))

    fun requireFiniteScale(value: BigDecimal, field: String) {
        require(value.scale() <= 18 && value.precision() <= 38) {
            "$field must fit the Semantic decimal bounds"
        }
    }

    private const val MAXIMUM_DISPLAY_LABEL_LENGTH = 120

    private val canonicalDecimalPattern = Regex(
        "^(?:0|-?(?:(?:[1-9][0-9]{0,19})(?:\\.[0-9]{0,17}[1-9])?|0\\.[0-9]{0,17}[1-9]))$",
    )
}
