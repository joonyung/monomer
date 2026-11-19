@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.agent.api

import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid

private val SEMANTIC_ELEMENT_NAME = Regex("^${AgentPathPatterns.SEMANTIC_NAME}$")

@JvmInline
value class AgentResourcePath private constructor(val value: String) {
    companion object {
        const val MAXIMUM_NAME_BYTES = 1_024
        // Three names in the longest route: catalogs/{name}/selection/options/schemas/{name}/relations/{name}.
        const val MAXIMUM_RESOURCE_PATH_BYTES = 113 + 3 * 2 * MAXIMUM_NAME_BYTES
        // Version/kind, two int32 lengths, the longest name collection path, and one raw name.
        const val MAXIMUM_CURSOR_LENGTH = ((2 + 4 + 112 + 2 * 2 * MAXIMUM_NAME_BYTES + 4 + MAXIMUM_NAME_BYTES) * 4 + 2) / 3

        fun parse(value: String): AgentResourcePath {
            require(value.toByteArray(StandardCharsets.UTF_8).size <= MAXIMUM_RESOURCE_PATH_BYTES)
            val segments = value.split('/')
            require(segments.first() in setOf("workspace", "organization")) { "resource path must be rooted at workspace or organization" }
            segments.drop(1).forEach(::decodeNameSegment)
            return AgentResourcePath(value)
        }

        fun encodeNameSegment(value: String): String {
            requireAddressPart(value)
            return value.replace("~", "~0").replace("/", "~1")
        }

        fun decodeNameSegment(value: String): String {
            require('/' !in value)
            val decoded = buildString {
                var index = 0
                while (index < value.length) {
                    val character = value[index++]
                    if (character == '~') {
                        require(index < value.length) { "incomplete name escape" }
                        append(when (value[index++]) {
                            '0' -> '~'
                            '1' -> '/'
                            else -> throw IllegalArgumentException("invalid name escape")
                        })
                    } else {
                        append(character)
                    }
                }
            }
            requireAddressPart(decoded)
            return decoded
        }
    }

    override fun toString(): String = value
}

@JvmInline
value class AgentRequestId(val value: String) {
    init {
        require(value.matches(Regex("^[A-Za-z0-9._:-]{1,200}$")))
    }
}

@JvmInline
value class AgentRevision(val value: Long) {
    init {
        require(value >= 0)
    }

    override fun toString(): String = value.toString()
}

sealed interface AgentWorkspaceCaller {
    val workspaceId: WorkspaceId
    val actorPrincipalId: String
    val role: cc.monomer.identity.api.WorkspaceRole
    val presentedCredential: PresentedCredential
    val expectedOAuthResource: ExpectedOAuthResource
    val requestId: AgentRequestId

    /** The agent app whose connection admitted this call. */
    val agentClientId: cc.monomer.identity.api.AgentClientId

    companion object {
        @JvmSynthetic
        internal suspend fun authenticateRole(
            admissionPort: AgentWorkspaceAdmissionPort,
            workspaceId: WorkspaceId,
            credential: PresentedCredential,
            audience: AgentWorkspaceAdmissionAudience,
            intent: AgentWorkspaceAdmissionIntent,
            requestId: AgentRequestId,
        ): AgentWorkspaceCallerAuthenticationOutcome {
            if (!intent.isSupported()) {
                return AgentWorkspaceCallerAuthenticationOutcome.InvalidRequest
            }
            val admissionRequest = AgentWorkspaceRoleAdmissionRequest.issue(
                workspaceId = workspaceId,
                credential = credential,
                audience = audience,
                intent = intent,
            )
            return authenticate(admissionPort, admissionRequest, requestId)
        }

        private suspend fun authenticate(
            admissionPort: AgentWorkspaceAdmissionPort,
            admissionRequest: AgentWorkspaceAdmissionRequest,
            requestId: AgentRequestId,
        ): AgentWorkspaceCallerAuthenticationOutcome {
            return when (val decision = admissionPort.decide(admissionRequest)) {
                is AgentWorkspaceAdmissionDecision.Authorized -> {
                    val evidence = decision.evidence
                    if (!evidence.proves(admissionRequest)) {
                        AgentWorkspaceCallerAuthenticationOutcome.EvidenceMismatch
                    } else {
                        AgentWorkspaceCallerAuthenticationOutcome.Authenticated(
                            VerifiedAgentWorkspaceCaller(
                                AgentWorkspaceCallerFacts(
                                    admission = evidence,
                                    presentedCredential = admissionRequest.credential,
                                    expectedOAuthResource = admissionRequest.expectedOAuthResource,
                                    requestId = requestId,
                                ),
                            ),
                        )
                    }
                }
                is AgentWorkspaceAdmissionDecision.Denied ->
                    AgentWorkspaceCallerAuthenticationOutcome.Denied(decision.reason, decision.problem)
            }
        }

        @JvmSynthetic
        internal fun facts(caller: AgentWorkspaceCaller): AgentWorkspaceCallerFacts? =
            (caller as? VerifiedAgentWorkspaceCaller)?.facts
    }

    private class VerifiedAgentWorkspaceCaller(
        val facts: AgentWorkspaceCallerFacts,
    ) : AgentWorkspaceCaller {
        override val role = (facts.admission as AgentWorkspaceRoleEvidence).role
        override val workspaceId: WorkspaceId = facts.admission.workspaceId
        override val actorPrincipalId: String = facts.admission.actor.id.value.toString()
        override val presentedCredential: PresentedCredential = facts.presentedCredential
        override val expectedOAuthResource: ExpectedOAuthResource = facts.expectedOAuthResource
        override val requestId: AgentRequestId = facts.requestId
        override val agentClientId: cc.monomer.identity.api.AgentClientId = checkNotNull(facts.admission.agentClientId)

        override fun toString(): String = "AgentWorkspaceCaller([REDACTED])"
    }
}

internal data class AgentWorkspaceCallerFacts(
    val admission: AgentWorkspaceAdmissionEvidence,
    val presentedCredential: PresentedCredential,
    val expectedOAuthResource: ExpectedOAuthResource,
    val requestId: AgentRequestId,
)

sealed interface AgentWorkspaceCallerAuthenticationOutcome {
    data class Authenticated(
        val caller: AgentWorkspaceCaller,
    ) : AgentWorkspaceCallerAuthenticationOutcome

    /** [problem] explains a FORBIDDEN role denial on an accessible target; it is null for every other denial. */
    data class Denied(
        val reason: AgentWorkspaceAdmissionDenial,
        val problem: AgentWorkspaceProblem?,
    ) : AgentWorkspaceCallerAuthenticationOutcome

    data object InvalidRequest : AgentWorkspaceCallerAuthenticationOutcome

    data object EvidenceMismatch : AgentWorkspaceCallerAuthenticationOutcome
}

data class AgentSearchRequest(
    val path: AgentResourcePath,
    val queries: List<String>,
    val kinds: Set<AgentResourceKind>?,
    val limitPerQuery: Int,
) {
    init {
        require(queries.size in 1..5)
        require(queries.all { it.isNotBlank() && it.codePointCount(0, it.length) <= 1_024 })
        require(kinds == null || kinds.isNotEmpty() && kinds.all { it in SUPPORTED_KINDS })
        require(limitPerQuery in 1..20)
    }

    companion object {
        val SUPPORTED_KINDS: Set<AgentResourceKind> = setOf(
            AgentResourceKind.CONNECTOR, AgentResourceKind.CATALOG, AgentResourceKind.SCHEMA,
            AgentResourceKind.RELATION, AgentResourceKind.SEMANTIC_MODEL, AgentResourceKind.METRIC,
            AgentResourceKind.DASHBOARD, AgentResourceKind.PAGE,
        )
    }
}

data class AgentSearchQueryResult(
    val query: String,
    val items: List<AgentResourceListingItem>,
) {
    init {
        require(items.size <= 20)
    }
}

data class AgentSearchResponse(
    val results: List<AgentSearchQueryResult>,
) {
    init {
        require(results.size in 1..5)
    }
}

sealed interface AgentSearchOutcome {
    data class Found(val response: AgentSearchResponse) : AgentSearchOutcome
    data class Rejected(val problem: AgentWorkspaceProblem) : AgentSearchOutcome
    data object NotFound : AgentSearchOutcome
}

interface AgentSearchPort {
    suspend fun search(caller: AgentWorkspaceCaller, request: AgentSearchRequest): AgentSearchOutcome
}

data class AgentListRequest(
    val path: AgentResourcePath,
    val cursor: String?,
    val limit: Int,
) {
    init {
        require(cursor == null || cursor.length <= AgentResourcePath.MAXIMUM_CURSOR_LENGTH)
        require(limit in 1..100)
    }
}

data class AgentReadRequest(
    val path: AgentResourcePath,
    val download: AgentResultDownloadFormat?,
) {
    init {
        require(download == null || AgentPathPatterns.exploreResult.matches(path.value))
    }
}

enum class AgentResultDownloadFormat { ARROW }

data class AgentCreateRequest(
    val path: AgentResourcePath,
    val input: AgentCreateInput,
)

data class AgentSemanticAggregateMetric(
    val measure: String,
    val name: String,
    val label: String?,
    val description: String?,
    val timeGranularity: AgentTimeGranularity?,
    val filters: List<AgentMetricFilter>,
)

data class AgentEditRequest(
    val path: AgentResourcePath,
    val revision: AgentRevision?,
    val patch: List<AgentJsonPatchOperation>,
    val commandId: String?,
    val aggregateMetrics: List<AgentSemanticAggregateMetric>?,
) {
    init {
        require((aggregateMetrics != null) == Regex("^workspace/semantic-models/${AgentPathPatterns.SEMANTIC_NAME}$").matches(path.value))
        require(patch.isNotEmpty() && patch.size <= 100)
        require((commandId != null) == AgentPagesPaths.editable(path.value))
        if (commandId != null) {
            require(commandId.isNotBlank() && commandId.length <= 200)
            require(revision != null)
        }
    }
}

data class AgentInvokeRequest(
    val path: AgentResourcePath,
    val action: String,
    val input: AgentInvokeInput,
) {
    init {
        require(action.isNotBlank() && action.length <= 128)
    }
}

sealed interface AgentCreateInput {
    data class OrganizationInvitation(val email: String, val workspaceAccess: List<InvitationWorkspaceAccess>) : AgentCreateInput {
        data class InvitationWorkspaceAccess(val workspaceId: Uuid, val role: cc.monomer.identity.api.WorkspaceRole)
    }

    data class FolderPage(
        val title: String,
        val description: String?,
        val parentPageId: Uuid?,
        val index: Int?,
        val baseRevision: Long,
        val commandId: String,
    ) : AgentCreateInput
    data class DocumentPage(
        val title: String,
        val description: String?,
        val parentPageId: Uuid?,
        val index: Int?,
        val baseRevision: Long,
        val commandId: String,
        val uploadRef: Uuid,
    ) : AgentCreateInput
    data class DashboardPage(
        val title: String,
        val description: String?,
        val parentPageId: Uuid?,
        val index: Int?,
        val baseRevision: Long,
        val commandId: String,
        val dashboardId: Uuid,
    ) : AgentCreateInput
    data class PageVersion(
        val commandId: String,
        val uploadRef: Uuid,
    ) : AgentCreateInput
    data class PageContentUpload(
        val format: AgentPageContentFormat,
        val byteSize: Long,
        val sha256: String,
    ) : AgentCreateInput

    data class Connector(
        val connectorType: cc.monomer.connector.api.ConnectorProductType,
        val displayName: String,
        val properties: cc.monomer.connector.api.ConnectorSettingsProperties,
        val credential: cc.monomer.connector.api.ConnectorCredential,
    ) : AgentCreateInput

    data class SemanticModel(
        val definition: AgentSemanticModelDefinition,
        val aggregateMetrics: List<AgentSemanticAggregateMetric>,
    ) : AgentCreateInput

    data class Metric(
        val definition: AgentMetricDefinition,
    ) : AgentCreateInput

    data class Dashboard(
        val definition: JsonObject,
    ) : AgentCreateInput
}

data class AgentJsonPatchOperation(
    val op: AgentJsonPatchOp,
    val path: String,
    val value: JsonElement?,
) {
    init {
        require(path.isEmpty() || path.startsWith('/'))
        when (op) {
            AgentJsonPatchOp.ADD,
            AgentJsonPatchOp.REPLACE,
            -> require(value != null)
            AgentJsonPatchOp.REMOVE -> require(value == null)
        }
    }
}

enum class AgentJsonPatchOp { ADD, REPLACE, REMOVE }

data class AgentSemanticModelDefinition(
    val schemaVersion: UInt,
    val name: String,
    val label: String?,
    val description: String?,
    val sourceRelations: List<AgentSemanticSourceRelation>,
    val aggregationTimeDimension: String?,
    val entities: List<AgentSemanticEntity>,
    val dimensions: List<AgentSemanticDimension>,
    val measures: List<AgentSemanticMeasure>,
) {
    init {
        require(schemaVersion == 3u)
        require(SEMANTIC_ELEMENT_NAME.matches(name))
        require(sourceRelations.isNotEmpty())
        require(sourceRelations.map(AgentSemanticSourceRelation::connectorId).toSet().size == sourceRelations.size)
        require(entities.count { entity -> entity.kind == AgentSemanticEntityKind.PRIMARY } == 1)
        requireOptionalText(label)
        requireOptionalText(description)
    }
}

data class AgentSemanticSourceRelation(
    val connectorId: Uuid,
    val catalog: String?,
    val schema: String,
    val relation: String,
) {
    init {
        requireAddressPart(schema)
        requireAddressPart(relation)
        require(catalog == null || isAddressPart(catalog))
    }
}

data class AgentSemanticEntity(
    val name: String,
    val expression: String,
    val kind: AgentSemanticEntityKind,
    val label: String?,
    val description: String?,
) {
    init {
        require(SEMANTIC_ELEMENT_NAME.matches(name))
        requireOptionalText(label)
        requireOptionalText(description)
    }
}

enum class AgentSemanticEntityKind {
    PRIMARY,
    UNIQUE,
    FOREIGN,
    NATURAL,
}

data class AgentSemanticDimension(
    val name: String,
    val expression: String,
    val kind: AgentSemanticDimensionKind,
    val granularity: AgentTimeGranularity?,
    val label: String?,
    val description: String?,
    val isPartition: Boolean,
    val timeKind: AgentSemanticTimeKind?,
) {
    init {
        require(SEMANTIC_ELEMENT_NAME.matches(name))
        require((kind == AgentSemanticDimensionKind.TIME) == (granularity != null))
        require(kind == AgentSemanticDimensionKind.TIME || timeKind == null)
        requireOptionalText(label)
        requireOptionalText(description)
    }
}

enum class AgentSemanticDimensionKind {
    CATEGORICAL,
    TIME,
}

enum class AgentSemanticTimeKind {
    DATE,
    LOCAL,
    INSTANT,
}

data class AgentSemanticMeasure(
    val name: String,
    val expression: String,
    val aggregation: AgentSemanticAggregation,
    val aggregationTimeDimension: String?,
    val nonAdditiveDimension: AgentSemanticNonAdditiveDimension?,
    val label: String?,
    val description: String?,
) {
    init {
        require(SEMANTIC_ELEMENT_NAME.matches(name))
        requireOptionalElementName(aggregationTimeDimension)
        requireOptionalText(label)
        requireOptionalText(description)
    }
}

data class AgentSemanticNonAdditiveDimension(
    val name: String,
    val windowChoice: AgentSemanticWindowChoice,
    val windowGroupings: List<String>?,
) {
    init {
        require(SEMANTIC_ELEMENT_NAME.matches(name))
        require(windowGroupings == null || windowGroupings.distinct().size == windowGroupings.size)
        require(windowGroupings.orEmpty().all { SEMANTIC_ELEMENT_NAME.matches(it) })
    }
}

enum class AgentSemanticWindowChoice {
    MIN,
    MAX,
}

private fun requireOptionalText(value: String?) {
    require(value == null || (value.isNotBlank() && value.none { it == '\u0000' }))
}

private fun requireOptionalElementName(value: String?) {
    require(value == null || SEMANTIC_ELEMENT_NAME.matches(value))
}

private fun isAddressPart(value: String): Boolean =
    value.isNotBlank() &&
        value.trim() == value &&
        Normalizer.normalize(value, Normalizer.Form.NFC) == value &&
        StandardCharsets.UTF_8.newEncoder().canEncode(value) &&
        value.toByteArray(StandardCharsets.UTF_8).size <= AgentResourcePath.MAXIMUM_NAME_BYTES &&
        value.none { it.isISOControl() }

private fun requireAddressPart(value: String) {
    require(isAddressPart(value))
}

enum class AgentSemanticAggregation {
    SUM,
    COUNT,
    COUNT_DISTINCT,
    AVERAGE,
    MIN,
    MAX,
    MEDIAN,
    SUM_BOOLEAN,
}

data class AgentMetricWindow(
    val amount: Int,
    val unit: AgentMetricWindowUnit,
) {
    init { require(amount > 0) }
}

enum class AgentMetricWindowUnit {
    DAY,
    WEEK,
    MONTH,
    QUARTER,
    YEAR,
}

data class AgentMetricInput(
    val metricName: String,
    val filter: AgentMetricFilter?,
    val alias: String?,
    val offsetWindow: AgentMetricWindow?,
    val offsetToGrain: AgentTimeGranularity?,
) {
    init {
        require(SEMANTIC_ELEMENT_NAME.matches(metricName))
        requireOptionalElementName(alias)
    }
}

enum class AgentMetricFilterOperator {
    EQ,
    NE,
    GT,
    GTE,
    LT,
    LTE,
    IN,
    NOT_IN,
    BETWEEN,
    IS_NULL,
    IS_NOT_NULL,
    LIKE,
    NOT_LIKE,
    CONTAINS,
    STARTS_WITH,
}

sealed interface AgentMetricFilter {
    data class Predicate(
        val dimension: AgentSemanticElement,
        val operator: AgentMetricFilterOperator,
        val values: List<String>,
    ) : AgentMetricFilter

    data class And(val filters: List<AgentMetricFilter>) : AgentMetricFilter {
        init { require(filters.isNotEmpty()) }
    }

    data class Or(val filters: List<AgentMetricFilter>) : AgentMetricFilter {
        init { require(filters.isNotEmpty()) }
    }

    data class Not(val filter: AgentMetricFilter) : AgentMetricFilter
}

enum class AgentMetricPeriodAggregation {
    FIRST,
    LAST,
    AVERAGE,
}

enum class AgentMetricConversionCalculation {
    CONVERSIONS,
    CONVERSION_RATE,
}

data class AgentMetricConstantProperty(
    val baseProperty: String,
    val conversionProperty: String,
) {
    init {
        require(SEMANTIC_ELEMENT_NAME.matches(baseProperty))
        require(SEMANTIC_ELEMENT_NAME.matches(conversionProperty))
    }
}

sealed interface AgentMetricDefinition {
    val schemaVersion: UInt
    val name: String
    val label: String?
    val description: String?
    val timeGranularity: AgentTimeGranularity?
    val filters: List<AgentMetricFilter>
    val display: AgentMetricDisplay?

    fun validateCommon() {
        require(schemaVersion == 3u)
        require(SEMANTIC_ELEMENT_NAME.matches(name))
        requireOptionalText(label)
        requireOptionalText(description)
    }

    data class Simple(
        override val schemaVersion: UInt,
        override val name: String,
        override val label: String?,
        override val description: String?,
        override val timeGranularity: AgentTimeGranularity?,
        override val filters: List<AgentMetricFilter>,
        val semanticModelName: String,
        val measure: String,
        override val display: AgentMetricDisplay?,
    ) : AgentMetricDefinition {
        init {
            validateCommon()
            require(SEMANTIC_ELEMENT_NAME.matches(semanticModelName))
            require(SEMANTIC_ELEMENT_NAME.matches(measure))
        }
    }

    data class Ratio(
        override val schemaVersion: UInt,
        override val name: String,
        override val label: String?,
        override val description: String?,
        override val timeGranularity: AgentTimeGranularity?,
        override val filters: List<AgentMetricFilter>,
        val numerator: AgentMetricInput,
        val denominator: AgentMetricInput,
        override val display: AgentMetricDisplay?,
    ) : AgentMetricDefinition {
        init { validateCommon() }
    }

    data class Derived(
        override val schemaVersion: UInt,
        override val name: String,
        override val label: String?,
        override val description: String?,
        override val timeGranularity: AgentTimeGranularity?,
        override val filters: List<AgentMetricFilter>,
        val expression: String,
        val inputMetrics: List<AgentMetricInput>,
        override val display: AgentMetricDisplay?,
    ) : AgentMetricDefinition {
        init {
            validateCommon()
            require(expression.isNotBlank() && expression.none { it == '\u0000' })
            require(inputMetrics.isNotEmpty())
        }
    }

    data class Cumulative(
        override val schemaVersion: UInt,
        override val name: String,
        override val label: String?,
        override val description: String?,
        override val timeGranularity: AgentTimeGranularity?,
        override val filters: List<AgentMetricFilter>,
        val inputMetric: AgentMetricInput,
        val window: AgentMetricWindow?,
        val grainToDate: AgentTimeGranularity?,
        val periodAggregation: AgentMetricPeriodAggregation?,
        override val display: AgentMetricDisplay?,
    ) : AgentMetricDefinition {
        init {
            validateCommon()
            require(window == null || grainToDate == null)
        }
    }

    data class Conversion(
        override val schemaVersion: UInt,
        override val name: String,
        override val label: String?,
        override val description: String?,
        override val timeGranularity: AgentTimeGranularity?,
        override val filters: List<AgentMetricFilter>,
        val entity: String,
        val calculation: AgentMetricConversionCalculation,
        val baseMetric: AgentMetricInput,
        val conversionMetric: AgentMetricInput,
        val window: AgentMetricWindow?,
        val constantProperties: List<AgentMetricConstantProperty>?,
        override val display: AgentMetricDisplay?,
    ) : AgentMetricDefinition {
        init {
            validateCommon()
            require(SEMANTIC_ELEMENT_NAME.matches(entity))
        }
    }
}

sealed interface AgentInvokeInput {
    data class WorkspaceMemberAdd(val userId: Uuid, val role: cc.monomer.identity.api.WorkspaceRole) : AgentInvokeInput
    data class WorkspaceMemberRole(val role: cc.monomer.identity.api.WorkspaceRole) : AgentInvokeInput

    data class PageDelete(val baseRevision: Long, val commandId: String) : AgentInvokeInput
    data class PageContentUploadComplete(val parts: List<AgentPageUploadCompletedPart>) : AgentInvokeInput

    data class ConnectorCredentialRotate(val credential: cc.monomer.connector.api.ConnectorCredential) : AgentInvokeInput

    data class SemanticModelCheck(val definition: AgentSemanticModelDefinition) : AgentInvokeInput

    data class MetricCheck(val definition: AgentMetricDefinition, val connectorId: Uuid?) : AgentInvokeInput

    data class ExploreRun(
        val request: AgentExploreRunRequest,
    ) : AgentInvokeInput

    data class DashboardDraftStart(
        val parameterBindings: Map<String, JsonElement>,
    ) : AgentInvokeInput

    data class DashboardDraftApply(
        val expectedRevision: Long,
    ) : AgentInvokeInput {
        init {
            require(expectedRevision >= 0)
        }
    }

    data class DashboardDraftDiscard(
        val expectedRevision: Long,
    ) : AgentInvokeInput {
        init {
            require(expectedRevision >= 0)
        }
    }

    data class DashboardQueryRefresh(
        val queryNames: Set<String>,
    ) : AgentInvokeInput {
        init {
            require(queryNames.isNotEmpty())
        }
    }

    data class DashboardSessionOpen(
        val parameterBindings: Map<String, JsonElement>,
    ) : AgentInvokeInput

    data class DashboardSessionResolve(val variables: Map<String, JsonElement>) : AgentInvokeInput

    data class DashboardSessionRefresh(
        val variables: Map<String, JsonElement>,
        val queryNames: Set<String>,
    ) : AgentInvokeInput {
        init { require(queryNames.isNotEmpty()) }
    }

    data object None : AgentInvokeInput
}

sealed interface AgentExploreRunRequest {
    data class Sql(
        val connectorId: Uuid,
        val statement: String,
    ) : AgentExploreRunRequest {
        init {
            require(statement.isNotBlank() && statement.length <= 1_048_576)
        }
    }

    data class Semantic(
        val connectorId: Uuid,
        val query: AgentSemanticQuery,
    ) : AgentExploreRunRequest
}

data class AgentSemanticQuery(
    val schemaVersion: UInt,
    val metrics: List<String>,
    val groupBys: List<AgentSemanticGroupBy>,
    val metricTimeRange: AgentSemanticMetricTimeRange?,
    val filters: List<AgentSemanticFilter>,
    val orderBy: List<AgentSemanticOrder>,
    val rowLimit: Int,
) {
    init {
        require(schemaVersion == 4u)
        require(metrics.isNotEmpty())
        require(metrics.all(SEMANTIC_ELEMENT_NAME::matches))
        require(metrics.distinct().size == metrics.size)
        require(groupBys.distinct().size == groupBys.size)
        require(groupBys.count { it is AgentSemanticGroupBy.MetricTime } <= 1)
        require(filters.size <= 32)
        require(filters.distinct().size == filters.size)
        require(orderBy.distinct().size == orderBy.size)
        val groupBySet = groupBys.toSet()
        orderBy.forEach { order ->
            when (val target = order.target) {
                is AgentSemanticOrderTarget.Metric -> require(target.metricName in metrics)
                is AgentSemanticOrderTarget.GroupBy -> require(target.groupBy in groupBySet)
            }
        }
        require(orderBy.map(AgentSemanticOrder::target).distinct().size == orderBy.size)
        require(rowLimit in 1..100_000)
    }
}

data class AgentSemanticElement(
    val semanticModelName: String,
    val name: String,
    val granularity: AgentTimeGranularity?,
) {
    init {
        require(SEMANTIC_ELEMENT_NAME.matches(semanticModelName))
        require(SEMANTIC_ELEMENT_NAME.matches(name))
    }
}

sealed interface AgentSemanticGroupBy {
    data class Dimension(
        val semanticModelName: String,
        val name: String,
        val granularity: AgentTimeGranularity?,
    ) : AgentSemanticGroupBy {
        init {
            require(SEMANTIC_ELEMENT_NAME.matches(semanticModelName))
            require(SEMANTIC_ELEMENT_NAME.matches(name))
            require(granularity == null || granularity in QUERY_TIME_GRANULARITIES)
        }
    }

    data class Entity(
        val semanticModelName: String,
        val name: String,
    ) : AgentSemanticGroupBy {
        init {
            require(SEMANTIC_ELEMENT_NAME.matches(semanticModelName))
            require(SEMANTIC_ELEMENT_NAME.matches(name))
        }
    }

    data class MetricTime(
        val granularity: AgentTimeGranularity,
    ) : AgentSemanticGroupBy {
        init { require(granularity in QUERY_TIME_GRANULARITIES) }
    }
}

enum class AgentTimeGranularity {
    YEAR,
    QUARTER,
    MONTH,
    WEEK,
    DAY,
    HOUR,
    MINUTE,
    SECOND,
    MILLISECOND,
    MICROSECOND,
    NANOSECOND,
}

private val QUERY_TIME_GRANULARITIES = AgentTimeGranularity.entries.toSet()

private fun requireQueryElement(element: AgentSemanticElement) {
    require(element.granularity == null)
}

sealed interface AgentSemanticTimeRangeEndpoint {
    data class Date(
        val value: LocalDate,
    ) : AgentSemanticTimeRangeEndpoint

    data class Timestamp(
        val value: Instant,
    ) : AgentSemanticTimeRangeEndpoint
}

data class AgentSemanticMetricTimeRange(
    val start: AgentSemanticTimeRangeEndpoint,
    val end: AgentSemanticTimeRangeEndpoint,
) {
    init {
        when {
            start is AgentSemanticTimeRangeEndpoint.Date && end is AgentSemanticTimeRangeEndpoint.Date ->
                require(start.value <= end.value)
            start is AgentSemanticTimeRangeEndpoint.Timestamp && end is AgentSemanticTimeRangeEndpoint.Timestamp ->
                require(start.value <= end.value)
            else -> error("semantic metric time range endpoints must have the same type")
        }
    }
}

enum class AgentSemanticFilterCardinality {
    NONE,
    ONE,
    MANY,
}

sealed interface AgentSemanticFilter {
    val dimension: AgentSemanticElement

    data class Text(
        override val dimension: AgentSemanticElement,
        val operator: AgentSemanticTextFilterOperator,
        val values: List<String>,
    ) : AgentSemanticFilter {
        init {
            requireQueryElement(dimension)
            requireFilterCardinality(values, operator.cardinality)
            require(values.all { it.codePointCount(0, it.length) in 1..256 }) {
                "Text filter values must contain 1 to 256 characters."
            }
            require(values.all { '\u0000' !in it }) { "Text filter values must not contain NUL." }
        }
    }

    data class Number(
        override val dimension: AgentSemanticElement,
        val operator: AgentSemanticNumberFilterOperator,
        val values: List<BigDecimal>,
    ) : AgentSemanticFilter {
        init {
            requireQueryElement(dimension)
            requireFilterCardinality(values, operator.cardinality)
            require(values.all { SEMANTIC_NUMBER_PATTERN.matches(it.toPlainString()) })
        }
    }

    data class Boolean(
        override val dimension: AgentSemanticElement,
        val operator: AgentSemanticBooleanFilterOperator,
        val values: List<kotlin.Boolean>,
    ) : AgentSemanticFilter {
        init {
            requireQueryElement(dimension)
            requireFilterCardinality(values, operator.cardinality)
        }
    }

    data class Date(
        override val dimension: AgentSemanticElement,
        val operator: AgentSemanticDateFilterOperator,
        val values: List<LocalDate>,
    ) : AgentSemanticFilter {
        init {
            requireQueryElement(dimension)
            requireFilterCardinality(values, operator.cardinality)
        }
    }

    data class Timestamp(
        override val dimension: AgentSemanticElement,
        val operator: AgentSemanticTimestampFilterOperator,
        val values: List<Instant>,
    ) : AgentSemanticFilter {
        init {
            requireQueryElement(dimension)
            requireFilterCardinality(values, operator.cardinality)
        }
    }
}

enum class AgentSemanticTextFilterOperator(
    val cardinality: AgentSemanticFilterCardinality,
) {
    EQUALS(AgentSemanticFilterCardinality.ONE),
    NOT_EQUALS(AgentSemanticFilterCardinality.ONE),
    IN(AgentSemanticFilterCardinality.MANY),
    NOT_IN(AgentSemanticFilterCardinality.MANY),
    IS_NULL(AgentSemanticFilterCardinality.NONE),
    IS_NOT_NULL(AgentSemanticFilterCardinality.NONE),
}

enum class AgentSemanticNumberFilterOperator(
    val cardinality: AgentSemanticFilterCardinality,
) {
    EQUALS(AgentSemanticFilterCardinality.ONE),
    NOT_EQUALS(AgentSemanticFilterCardinality.ONE),
    IN(AgentSemanticFilterCardinality.MANY),
    NOT_IN(AgentSemanticFilterCardinality.MANY),
    GREATER_THAN(AgentSemanticFilterCardinality.ONE),
    GREATER_THAN_OR_EQUAL(AgentSemanticFilterCardinality.ONE),
    LESS_THAN(AgentSemanticFilterCardinality.ONE),
    LESS_THAN_OR_EQUAL(AgentSemanticFilterCardinality.ONE),
    IS_NULL(AgentSemanticFilterCardinality.NONE),
    IS_NOT_NULL(AgentSemanticFilterCardinality.NONE),
}

enum class AgentSemanticBooleanFilterOperator(
    val cardinality: AgentSemanticFilterCardinality,
) {
    EQUALS(AgentSemanticFilterCardinality.ONE),
    NOT_EQUALS(AgentSemanticFilterCardinality.ONE),
    IS_NULL(AgentSemanticFilterCardinality.NONE),
    IS_NOT_NULL(AgentSemanticFilterCardinality.NONE),
}

enum class AgentSemanticDateFilterOperator(
    val cardinality: AgentSemanticFilterCardinality,
) {
    EQUALS(AgentSemanticFilterCardinality.ONE),
    NOT_EQUALS(AgentSemanticFilterCardinality.ONE),
    IN(AgentSemanticFilterCardinality.MANY),
    NOT_IN(AgentSemanticFilterCardinality.MANY),
    GREATER_THAN(AgentSemanticFilterCardinality.ONE),
    GREATER_THAN_OR_EQUAL(AgentSemanticFilterCardinality.ONE),
    LESS_THAN(AgentSemanticFilterCardinality.ONE),
    LESS_THAN_OR_EQUAL(AgentSemanticFilterCardinality.ONE),
    IS_NULL(AgentSemanticFilterCardinality.NONE),
    IS_NOT_NULL(AgentSemanticFilterCardinality.NONE),
}

enum class AgentSemanticTimestampFilterOperator(
    val cardinality: AgentSemanticFilterCardinality,
) {
    EQUALS(AgentSemanticFilterCardinality.ONE),
    NOT_EQUALS(AgentSemanticFilterCardinality.ONE),
    IN(AgentSemanticFilterCardinality.MANY),
    NOT_IN(AgentSemanticFilterCardinality.MANY),
    GREATER_THAN(AgentSemanticFilterCardinality.ONE),
    GREATER_THAN_OR_EQUAL(AgentSemanticFilterCardinality.ONE),
    LESS_THAN(AgentSemanticFilterCardinality.ONE),
    LESS_THAN_OR_EQUAL(AgentSemanticFilterCardinality.ONE),
    IS_NULL(AgentSemanticFilterCardinality.NONE),
    IS_NOT_NULL(AgentSemanticFilterCardinality.NONE),
}

private fun <T> requireFilterCardinality(
    values: List<T>,
    cardinality: AgentSemanticFilterCardinality,
) {
    when (cardinality) {
        AgentSemanticFilterCardinality.NONE -> require(values.isEmpty()) { "This filter operator does not accept values." }
        AgentSemanticFilterCardinality.ONE -> require(values.size == 1) { "This filter operator requires exactly one value." }
        AgentSemanticFilterCardinality.MANY -> require(values.isNotEmpty() && values.size <= 100) {
            "This filter operator requires 1 to 100 values."
        }
    }
    require(values.distinct().size == values.size) { "Filter values must be unique." }
}

private val SEMANTIC_NUMBER_PATTERN = Regex(
    "^(?:0|-?(?:(?:[1-9][0-9]{0,19})(?:\\.[0-9]{0,17}[1-9])?|0\\.[0-9]{0,17}[1-9]))$",
)

sealed interface AgentSemanticOrderTarget {
    data class Metric(
        val metricName: String,
    ) : AgentSemanticOrderTarget {
        init { require(SEMANTIC_ELEMENT_NAME.matches(metricName)) }
    }

    data class GroupBy(
        val groupBy: AgentSemanticGroupBy,
    ) : AgentSemanticOrderTarget
}

enum class AgentSemanticOrderDirection {
    ASC,
    DESC,
}

data class AgentSemanticOrder(
    val target: AgentSemanticOrderTarget,
    val direction: AgentSemanticOrderDirection,
)

enum class AgentWorkspaceOperation {
    LIST,
    SEARCH,
    READ,
    CREATE,
    EDIT,
    INVOKE,
}

sealed interface AgentAvailableOperation {
    val operation: AgentWorkspaceOperation
    val path: AgentResourcePath

    data class List(override val path: AgentResourcePath) : AgentAvailableOperation {
        override val operation = AgentWorkspaceOperation.LIST
    }

    data class Read(override val path: AgentResourcePath) : AgentAvailableOperation {
        override val operation = AgentWorkspaceOperation.READ
    }

    data class Edit(override val path: AgentResourcePath) : AgentAvailableOperation {
        override val operation = AgentWorkspaceOperation.EDIT
    }

    data class Create(
        override val path: AgentResourcePath,
        val inputKind: String,
    ) : AgentAvailableOperation {
        override val operation = AgentWorkspaceOperation.CREATE
        init {
            require(inputKind in when {
                path.value == "workspace/pages" -> setOf("FOLDER_PAGE", "DOCUMENT_PAGE", "DASHBOARD_PAGE")
                path.value == "workspace/page-content-uploads" -> setOf("PAGE_CONTENT_UPLOAD")
                AgentPagesPaths.versions.matches(path.value) -> setOf("PAGE_VERSION")
                else -> setOf(when (path.value) {
                "organization/invitations" -> "ORGANIZATION_INVITATION_CREATE"
                "workspace/connectors" -> "CONNECTOR_CREATE"
                "workspace/semantic-models" -> "SEMANTIC_MODEL_DEFINITION"
                "workspace/metrics" -> "METRIC_DEFINITION"
                "workspace/dashboards" -> "DASHBOARD_DEFINITION"
                else -> error("unsupported create collection")
                })
            })
        }
    }

    data class Invoke(
        override val path: AgentResourcePath,
        val action: String,
        val inputKind: String?,
    ) : AgentAvailableOperation {
        override val operation = AgentWorkspaceOperation.INVOKE
        init { require(AgentWorkspaceResponseRelations.matchesInvocation(path, action, inputKind)) }
    }
}

enum class AgentResourceKind {
    ORGANIZATION, ORGANIZATION_MEMBER, ORGANIZATION_INVITATION, WORKSPACE_MEMBER, WORKSPACE_ACCESS, WORKSPACE_AUTHORIZATION, LINEAGE_RESOURCE,
    PAGE,
    PAGES_TREE,
    PAGE_VERSION,
    PAGE_CONTENT,
    PAGE_SESSION,
    PAGE_QUERY_RUN,
    PAGE_CONTENT_UPLOAD,
    COLLECTION,
    CONNECTOR,
    CATALOG_ROOT,
    CATALOG,
    CONNECTOR_CATALOG_SELECTION,
    CATALOG_SELECTION,
    CATALOG_SELECTION_OPTION,
    CATALOG_SELECTION_OPTION_REFRESH,
    CATALOG_INGESTION,
    CATALOG_INGESTION_FAILURE,
    SCHEMA,
    RELATION,
    SEMANTIC_MODEL,
    METRIC,
    DASHBOARD_VERSION,
    SEMANTIC_MODEL_VERSION,
    METRIC_VERSION,
    EXPLORE_RUN,
    EXPLORE_RESULT,
    DASHBOARD,
    DASHBOARD_DRAFT,
    DASHBOARD_DRAFT_STATUS,
    DASHBOARD_SESSION,
    DASHBOARD_SCHEDULES,
    DASHBOARD_SCHEDULE_OCCURRENCE,
    DASHBOARD_QUERY_RUN,
    DASHBOARD_QUERY_RESULT,
}

sealed interface AgentResourceBody {
    val kind: AgentResourceKind
}

data class AgentWorkspaceRootBody(
    val role: cc.monomer.identity.api.WorkspaceRole,
    val resources: List<AgentAvailableOperation>,
) : AgentResourceBody {
    init {
        require(resources.size <= 100)
    }

    override val kind: AgentResourceKind = AgentResourceKind.COLLECTION
}

data class AgentConnectorResourceBody(
    val connectorType: String,
    val displayName: String,
    val properties: JsonObject,
    val sample: cc.monomer.connector.api.ConnectorSample?,
) : AgentResourceBody {
    override val kind = AgentResourceKind.CONNECTOR
}

data class AgentCatalogResourceBody(
    val connectorId: Uuid,
    val catalogName: String?,
    val schemaName: String,
    val relationName: String,
    val relationKind: String,
    val viewDefinition: String?,
    val columns: List<AgentCatalogColumn>,
    val primaryKey: AgentCatalogPrimaryKey?,
    val foreignKeys: List<AgentCatalogForeignKey>,
    val partitionKeys: List<String>,
    val clusteringKeys: List<String>,
    val requiresPartitionFilter: Boolean?,
) : AgentResourceBody {
    init {
        require(schemaName.isNotBlank())
        require(relationName.isNotBlank())
        require(relationKind.isNotBlank())
        require(viewDefinition == null || viewDefinition.isNotBlank())
        require(columns.size <= 10_000)
        require(foreignKeys.size <= 1_000)
    }

    override val kind: AgentResourceKind = AgentResourceKind.RELATION
}

data class AgentCatalogColumn(
    val name: String,
    val nativeType: String,
    val ordinalPosition: Int,
    val nullable: Boolean,
    val defaultValue: String?,
) {
    init {
        require(name.isNotBlank())
        require(nativeType.isNotBlank())
        require(ordinalPosition >= 0)
        require(defaultValue == null || defaultValue.isNotBlank())
    }
}

data class AgentCatalogPrimaryKey(
    val name: String?,
    val columns: List<String>,
) {
    init { require(columns.isNotEmpty()) }
}

data class AgentCatalogForeignKey(
    val name: String?,
    val columns: List<String>,
    val referencedConnectorId: Uuid,
    val referencedCatalogName: String?,
    val referencedSchemaName: String,
    val referencedRelationName: String,
    val referencedColumns: List<String>,
) {
    init {
        require(columns.isNotEmpty())
        require(columns.size == referencedColumns.size)
    }
}

data class AgentConnectorCatalogSelectionResourceBody(
    val selectedCatalogNames: List<String>,
) : AgentResourceBody {
    init { require(selectedCatalogNames.distinct().size == selectedCatalogNames.size) }
    override val kind: AgentResourceKind = AgentResourceKind.CONNECTOR_CATALOG_SELECTION
}

data class AgentCatalogSelectedSchema(
    val schemaName: String,
    val relations: AgentCatalogRelationSelection,
) {
    init { require(schemaName.isNotBlank()) }
}

sealed interface AgentCatalogRelationSelection {
    data object AllRelations : AgentCatalogRelationSelection
    data class SelectedRelations(val relationNames: List<String>) : AgentCatalogRelationSelection {
        init { require(relationNames.distinct().size == relationNames.size) }
    }
}

data class AgentCatalogSelectionResourceBody(
    val selectedSchemas: List<AgentCatalogSelectedSchema>,
) : AgentResourceBody {
    init { require(selectedSchemas.map { it.schemaName }.distinct().size == selectedSchemas.size) }
    override val kind: AgentResourceKind = AgentResourceKind.CATALOG_SELECTION
}

data class AgentCatalogOptionResourceBody(
    val optionKind: String,
    val available: Boolean?,
    val relationKind: String?,
) : AgentResourceBody {
    init {
        when (optionKind) {
            "CATALOG" -> require(available == null && relationKind == null)
            "SCHEMA" -> require(available != null && relationKind == null)
            "RELATION" -> require(available != null && !relationKind.isNullOrBlank())
            else -> error("unsupported Catalog option kind")
        }
    }
    override val kind: AgentResourceKind = AgentResourceKind.CATALOG_SELECTION_OPTION
}

/**
 * A recorded person, as the Web shows them. [id] is the person's principal id; [displayName] is null when the
 * account no longer has a readable name. Agent reads name people only on resources the caller may already read.
 */
data class AgentActor(val id: String, val displayName: String?) {
    init { require(id.isNotBlank()) }
}

/**
 * The agent app behind AGENT work, as the Web shows it ("via {name}"). [name] is self-asserted and only for display;
 * [verifiedHost] is the host Monomer verified for an https client id, else null.
 */
data class AgentApp(val clientId: String, val name: String, val verifiedHost: String?)

/** Who started a Catalog ingestion, as the Web shows them; [agentApp] is null for WEB work and unknown apps. */
data class AgentCatalogRequester(
    val principalId: String,
    val displayName: String?,
    val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
    val agentApp: AgentApp?,
)

data class AgentCatalogJobResourceBody(
    override val kind: AgentResourceKind,
    val state: String,
    val requestedAt: Instant,
    val terminalAt: Instant?,
    val failureCode: String?,
    val message: String?,
    val refreshedRelationCount: Int?,
    val runningTaskCount: Int?,
    val errorCount: Int?,
    val runningTargets: List<AgentActionResultReference>?,
    val requestedBy: AgentCatalogRequester?,
) : AgentResourceBody {
    init {
        require(kind in setOf(AgentResourceKind.CATALOG_SELECTION_OPTION_REFRESH, AgentResourceKind.CATALOG_INGESTION))
        require(state in setOf("RUNNING", "CANCEL_REQUESTED", "COMPLETED", "COMPLETED_WITH_ERRORS", "CANCELLED"))
        require((state in setOf("RUNNING", "CANCEL_REQUESTED")) == (terminalAt == null))
        require((failureCode == null) == (message == null))
        require((state == "COMPLETED_WITH_ERRORS") || failureCode == null)
        when (kind) {
            AgentResourceKind.CATALOG_SELECTION_OPTION_REFRESH -> {
                require(refreshedRelationCount == null && runningTaskCount == null && errorCount == null && runningTargets == null && requestedBy == null)
                require((state == "COMPLETED_WITH_ERRORS") == (failureCode != null))
            }
            AgentResourceKind.CATALOG_INGESTION -> {
                require(failureCode == null)
                require(refreshedRelationCount != null && refreshedRelationCount >= 0)
                require(runningTaskCount != null && runningTaskCount >= 0)
                require(errorCount != null && errorCount >= 0)
                require(runningTargets != null && runningTargets.size <= 250)
                require(requestedBy != null)
            }
            else -> error("unsupported Catalog job kind")
        }
    }
}

data class AgentCatalogIngestionFailureResourceBody(
    val target: AgentActionResultReference,
    val failureCode: String,
    val message: String,
) : AgentResourceBody {
    init {
        require(failureCode.isNotBlank())
        require(message.isNotBlank())
    }
    override val kind: AgentResourceKind = AgentResourceKind.CATALOG_INGESTION_FAILURE
}

data class AgentSemanticModelResourceBody(
    val semanticModelId: Uuid,
    val workspaceId: WorkspaceId,
    val name: String,
    val definition: AgentSemanticModelDefinition,
    val sourcePartitionInfo: List<AgentSourcePartitionInfo>,
) : AgentResourceBody {
    override val kind: AgentResourceKind = AgentResourceKind.SEMANTIC_MODEL
}

data class AgentMetricResourceBody(
    val metricId: Uuid,
    val workspaceId: WorkspaceId,
    val name: String,
    val definition: AgentMetricDefinition,
    val sourcePartitionInfo: List<AgentSourcePartitionInfo>,
) : AgentResourceBody {
    override val kind: AgentResourceKind = AgentResourceKind.METRIC
}

data class AgentDashboardDefinitionVersionBody(
    val workspaceId: WorkspaceId,
    val definitionId: Uuid,
    val name: String,
    val deleted: Boolean,
    val changeId: Uuid,
    val action: String,
    val definition: JsonObject,
    val occurredAt: Instant,
    val actor: AgentActor?,
    val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
    val agentApp: AgentApp?,
    val restorable: Boolean,
) : AgentResourceBody {
    override val kind = AgentResourceKind.DASHBOARD_VERSION
    init {

        require(action in setOf("CREATED", "APPLIED", "DELETED"))
    }
}

data class AgentSemanticDefinitionVersionBody(
    override val kind: AgentResourceKind,
    val workspaceId: WorkspaceId,
    val definitionId: Uuid,
    val name: String,
    val deleted: Boolean,
    val changeId: Uuid,
    val action: String,
    val definition: JsonObject,
    val occurredAt: Instant,
    val actor: AgentActor?,
    val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
    val agentApp: AgentApp?,
    val restorable: Boolean,
) : AgentResourceBody {
    init {
        require(kind in setOf(AgentResourceKind.SEMANTIC_MODEL_VERSION, AgentResourceKind.METRIC_VERSION))
        require(action in setOf("CREATED", "UPDATED", "DELETED"))
    }
}

sealed interface AgentExploreRunResourceBody : AgentResourceBody {
    val runKind: String
    val connectorId: Uuid
    val state: String
    val failureCode: String?
    val failureDetail: String?
    val createdAt: Instant
    val updatedAt: Instant
    val sql: String
    val credentialSurface: cc.monomer.identity.api.CredentialSurface?
    val agentApp: AgentApp?
    override val kind: AgentResourceKind get() = AgentResourceKind.EXPLORE_RUN

    data class Sql(
        override val connectorId: Uuid,
        override val state: String,
        override val failureCode: String?,
        override val failureDetail: String?,
        override val createdAt: Instant,
        override val updatedAt: Instant,
        override val sql: String,
        override val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
        override val agentApp: AgentApp?,
    ) : AgentExploreRunResourceBody {
        override val runKind: String = "SQL"
        init { requireExploreRunHistory(this) }
    }

    data class Semantic(
        override val connectorId: Uuid,
        override val state: String,
        override val failureCode: String?,
        override val failureDetail: String?,
        override val createdAt: Instant,
        override val updatedAt: Instant,
        override val sql: String,
        val query: AgentSemanticQuery,
        override val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
        override val agentApp: AgentApp?,
    ) : AgentExploreRunResourceBody {
        override val runKind: String = "SEMANTIC"
        init { requireExploreRunHistory(this) }
    }
}

private fun requireExploreRunHistory(body: AgentExploreRunResourceBody) {
    require(body.sql.isNotBlank() && body.sql.length <= 2_097_152)
    require(body.state in setOf("RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"))
    if (body.state in setOf("FAILED", "CANCELLED")) {
        require(body.failureCode != null && !body.failureDetail.isNullOrBlank())
    } else {
        require(body.failureCode == null && body.failureDetail == null)
    }
    require(body.failureDetail == null || body.failureDetail!!.length <= 4_096)
    require(body.agentApp == null || body.credentialSurface == cc.monomer.identity.api.CredentialSurface.AGENT)
}

data class AgentExploreResultColumn(
    val name: String,
    val logicalType: String,
    val display: AgentMetricDisplay?,
) {
    init {
        require(name.isNotBlank())
        require(logicalType.isNotBlank())
    }
}

data class AgentExploreResultDownloadLink(
    val url: String,
    val fileName: String,
    val mediaType: String,
    val byteSize: String,
    val expiresAt: Instant,
) {
    init {
        require(byteSize.matches(Regex("^(?:0|[1-9][0-9]*)$")))
        require(byteSize.toULongOrNull() != null)
    }

    override fun toString(): String = "AgentExploreResultDownloadLink([REDACTED])"
}

data class AgentExploreResultResourceBody(
    val columns: List<AgentExploreResultColumn>,
    val rows: List<List<JsonElement>>,
    val totalRowCount: String,
    val returnedRowCount: Int,
    val truncated: Boolean,
    val expiresAt: Instant,
    val download: AgentExploreResultDownloadLink?,
    val reportingTimeZone: java.time.ZoneId?,
) : AgentResourceBody {
    init {
        require(totalRowCount.matches(Regex("^(?:0|[1-9][0-9]*)$")))
        require(returnedRowCount == rows.size)
        require(rows.all { row -> row.size == columns.size })
        require(returnedRowCount.toULong() <= totalRowCount.toULong())
        require(truncated == (returnedRowCount.toULong() < totalRowCount.toULong()))
    }

    override val kind: AgentResourceKind = AgentResourceKind.EXPLORE_RESULT
}

data class AgentDashboardSchedulesBody(val observation: JsonObject) : AgentResourceBody {
    override val kind = AgentResourceKind.DASHBOARD_SCHEDULES
}

data class AgentDashboardOccurrenceSummary(
    val scheduleKey: String,
    val scheduledAt: Instant,
    val decidedAt: Instant,
    val outcome: String,
    val reason: String?,
    val sessionId: Uuid?,
    val resultState: String?,
)

data class AgentDashboardScheduleOccurrenceBody(val observation: JsonObject) : AgentResourceBody {
    override val kind = AgentResourceKind.DASHBOARD_SCHEDULE_OCCURRENCE
}

data class AgentDashboardResourceBody(
    val definition: JsonObject,
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: AgentActor?,
    val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
    val agentApp: AgentApp?,
) : AgentResourceBody {
    init { require(agentApp == null || credentialSurface == cc.monomer.identity.api.CredentialSurface.AGENT) }

    override val kind: AgentResourceKind = AgentResourceKind.DASHBOARD
}

data class AgentDashboardReadyResult(
    val authoringRevision: AgentRevision,
    val parameterBindings: Map<String, JsonElement>,
    val variables: Map<String, JsonElement>,
    val queryRuns: Map<String, AgentResourcePath>,
) {
    init {
        require(queryRuns.isNotEmpty())
    }
}

data class AgentDashboardQueryRunBody(
    val queryName: String,
    val queryType: String,
    val connectorId: Uuid,
    val state: String,
    val failureCode: String?,
    val failureDetail: String?,
    val outputColumnNames: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
    val agentApp: AgentApp?,
) : AgentResourceBody {
    init {
        require(agentApp == null || credentialSurface == cc.monomer.identity.api.CredentialSurface.AGENT)
        require(queryName.isNotBlank())
        require(queryType in setOf("SEMANTIC", "SQL"))
        require(state in setOf("RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"))
        if (state == "FAILED") {
            require(!failureCode.isNullOrBlank())
        } else {
            require(failureCode == null && failureDetail == null)
        }
        require(failureCode == null || failureCode.matches(Regex("^[A-Z][A-Z0-9_]{0,127}$")))
        require(
            failureDetail == null ||
                (failureDetail.isNotBlank() && failureDetail.length <= 4_096 && failureDetail.none { it == '\u0000' }),
        )
        require(outputColumnNames.toSet().size == outputColumnNames.size)
    }

    override val kind: AgentResourceKind = AgentResourceKind.DASHBOARD_QUERY_RUN
}

data class AgentDashboardDraftResourceBody(
    val definition: JsonObject,
    val parameterBindings: Map<String, JsonElement>,
    val variables: Map<String, JsonElement>,
    val targetQueryRuns: Map<String, AgentResourcePath>,
    val readyResults: Map<String, AgentDashboardReadyResult>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastEditedBy: AgentActor?,
    val lastEditedAt: Instant?,
    val lastEditSurface: cc.monomer.identity.api.CredentialSurface?,
    val lastEditAgentApp: AgentApp?,
) : AgentResourceBody {
    init { require(lastEditAgentApp == null || lastEditSurface == cc.monomer.identity.api.CredentialSurface.AGENT) }

    override val kind: AgentResourceKind = AgentResourceKind.DASHBOARD_DRAFT
}

data class AgentDashboardDraftStatusBody(
    val draftRevision: AgentRevision,
    val targetQueryRuns: Map<String, AgentDashboardDraftTargetQueryRun>,
    val readyResults: Map<String, AgentDashboardReadyResult>,
) : AgentResourceBody {
    override val kind: AgentResourceKind = AgentResourceKind.DASHBOARD_DRAFT_STATUS
}

data class AgentDashboardDraftTargetQueryRun(
    val path: AgentResourcePath,
    val state: AgentRunState,
)

data class AgentDashboardSessionResourceBody(
    val definition: JsonObject,
    val parameterBindings: Map<String, JsonElement>,
    val initialVariables: Map<String, JsonElement>,
    val runningQueryRuns: List<AgentDashboardSessionCurrentQueryRun>,
) : AgentResourceBody {
    override val kind: AgentResourceKind = AgentResourceKind.DASHBOARD_SESSION
}

data class AgentDashboardResultColumn(
    val name: String,
    val logicalType: String,
    val nullable: Boolean,
    val display: AgentMetricDisplay?,
) {
    init {
        require(name.isNotBlank())
        require(logicalType.isNotBlank())
    }
}

data class AgentDashboardResultResourceBody(
    val queryType: String,
    val columns: List<AgentDashboardResultColumn>,
    val rows: List<List<JsonElement>>,
    val totalRowCount: String,
    val returnedRowCount: Int,
    val truncated: Boolean,
    val reportingTimeZone: java.time.ZoneId?,
) : AgentResourceBody {
    init {
        require(queryType in setOf("SEMANTIC", "SQL"))
        require(queryType == "SEMANTIC" || reportingTimeZone == null)
        require(totalRowCount.matches(Regex("^(?:0|[1-9][0-9]*)$")))
        require(returnedRowCount >= 0)
        require(returnedRowCount == rows.size)
        require(rows.all { row -> row.size == columns.size })
        require(returnedRowCount.toULong() <= totalRowCount.toULong())
        require(truncated == (returnedRowCount.toULong() < totalRowCount.toULong()))
        require(rows.flatten().all { cell ->
            cell is JsonNull || (cell is JsonPrimitive && (cell.isString || cell.booleanOrNull != null))
        })
    }

    override val kind: AgentResourceKind = AgentResourceKind.DASHBOARD_QUERY_RESULT
}

sealed interface AgentResourceEnvelope {
    data class DashboardDefinitionVersion(
        override val path: AgentResourcePath,
        override val body: AgentDashboardDefinitionVersionBody,
        override val availableOperations: List<AgentAvailableOperation>,
    ) : AgentResourceEnvelope { override val kind = AgentResourceKind.DASHBOARD_VERSION }

    data class SemanticDefinitionVersion(
        override val path: AgentResourcePath,
        override val body: AgentSemanticDefinitionVersionBody,
        override val availableOperations: List<AgentAvailableOperation>,
    ) : AgentResourceEnvelope { override val kind = body.kind }

    data class Organization(override val path: AgentResourcePath, override val body: AgentOrganizationBody, override val availableOperations: List<AgentAvailableOperation>) : AgentResourceEnvelope { override val kind = AgentResourceKind.ORGANIZATION; init { require(availableOperations.size <= 100) } }
    data class OrganizationMember(override val path: AgentResourcePath, override val body: AgentOrganizationMemberBody, override val availableOperations: List<AgentAvailableOperation>) : AgentResourceEnvelope { override val kind = AgentResourceKind.ORGANIZATION_MEMBER; init { require(availableOperations.size <= 100) } }
    data class OrganizationInvitation(override val path: AgentResourcePath, override val body: AgentOrganizationInvitationBody, override val availableOperations: List<AgentAvailableOperation>) : AgentResourceEnvelope { override val kind = AgentResourceKind.ORGANIZATION_INVITATION; init { require(availableOperations.size <= 100) } }
    data class WorkspaceMember(override val path: AgentResourcePath, override val body: AgentWorkspaceMemberBody, override val availableOperations: List<AgentAvailableOperation>) : AgentResourceEnvelope { override val kind = AgentResourceKind.WORKSPACE_MEMBER; init { require(availableOperations.size <= 100) } }
    data class WorkspaceAccess(override val path: AgentResourcePath, override val body: AgentWorkspaceAccessBody, override val availableOperations: List<AgentAvailableOperation>) : AgentResourceEnvelope { override val kind = AgentResourceKind.WORKSPACE_ACCESS; init { require(availableOperations.size <= 100) } }
    data class WorkspaceAuthorization(override val path: AgentResourcePath, override val body: AgentWorkspaceAuthorizationBody, override val availableOperations: List<AgentAvailableOperation>) : AgentResourceEnvelope { override val kind = AgentResourceKind.WORKSPACE_AUTHORIZATION; init { require(availableOperations.size <= 100) } }
    data class LineageResource(override val path: AgentResourcePath, override val body: AgentLineageResourceBody, override val availableOperations: List<AgentAvailableOperation>) : AgentResourceEnvelope { override val kind = AgentResourceKind.LINEAGE_RESOURCE; init { require(availableOperations.size <= 100) } }

    val kind: AgentResourceKind
    val path: AgentResourcePath
    val revision: AgentRevision? get() = null
    val body: AgentResourceBody? get() = null
    val availableOperations: List<AgentAvailableOperation>

    data class Page(
        override val path: AgentResourcePath,
        override val revision: AgentRevision,
        override val body: AgentPageResourceBody,
        override val availableOperations: List<AgentAvailableOperation>,
    ) : AgentResourceEnvelope { override val kind = AgentResourceKind.PAGE }
    data class PagesTree(
        override val path: AgentResourcePath,
        override val revision: AgentRevision,
        override val body: AgentPagesTreeResourceBody,
        override val availableOperations: List<AgentAvailableOperation>,
    ) : AgentResourceEnvelope { override val kind = AgentResourceKind.PAGES_TREE }
    data class PageVersion(
        override val path: AgentResourcePath,
        override val body: AgentPageVersionResourceBody,
        override val availableOperations: List<AgentAvailableOperation>,
    ) : AgentResourceEnvelope { override val kind = AgentResourceKind.PAGE_VERSION }

    data class Workspace(
        override val path: AgentResourcePath,
        override val body: AgentWorkspaceRootBody,
        override val availableOperations: List<AgentAvailableOperation>,
    ) : AgentResourceEnvelope {
        override val kind = AgentResourceKind.COLLECTION
        init {
            require(availableOperations.size <= 100)
            require(body.kind == kind)
        }
    }

    data class Connector(
        override val path: AgentResourcePath,
        override val body: AgentConnectorResourceBody,
        override val availableOperations: List<AgentAvailableOperation>,
    ) : AgentResourceEnvelope {
        override val kind = AgentResourceKind.CONNECTOR
        init {
            require(availableOperations.size <= 100)
            require(body.kind == kind)
        }
    }

    data class Relation(
        override val path: AgentResourcePath,
        override val body: AgentCatalogResourceBody,
        override val availableOperations: List<AgentAvailableOperation>,
    ) : AgentResourceEnvelope {
        override val kind = AgentResourceKind.RELATION
        init {
            require(availableOperations.size <= 100)
            require(body.kind == kind)
        }
    }

    data class ConnectorCatalogSelection(
        override val path: AgentResourcePath,
        override val body: AgentConnectorCatalogSelectionResourceBody,
        override val availableOperations: List<AgentAvailableOperation>,
    ) : AgentResourceEnvelope {
        override val kind = AgentResourceKind.CONNECTOR_CATALOG_SELECTION
        init {
            require(availableOperations.size <= 100)
            require(body.kind == kind)
        }
    }

    data class CatalogSelection(
        override val path: AgentResourcePath,
        override val body: AgentCatalogSelectionResourceBody,
        override val availableOperations: List<AgentAvailableOperation>,
    ) : AgentResourceEnvelope {
        override val kind = AgentResourceKind.CATALOG_SELECTION
        init {
            require(availableOperations.size <= 100)
            require(body.kind == kind)
        }
    }

    data class CatalogOption(
        override val path: AgentResourcePath,
        override val body: AgentCatalogOptionResourceBody,
        override val availableOperations: List<AgentAvailableOperation>,
    ) : AgentResourceEnvelope {
        override val kind = AgentResourceKind.CATALOG_SELECTION_OPTION
        init {
            require(availableOperations.size <= 100)
            require(body.kind == kind)
        }
    }

    data class CatalogRefresh(
        override val path: AgentResourcePath,
        override val body: AgentCatalogJobResourceBody,
        override val availableOperations: List<AgentAvailableOperation>,
    ) : AgentResourceEnvelope {
        override val kind = AgentResourceKind.CATALOG_SELECTION_OPTION_REFRESH
        init {
            require(availableOperations.size <= 100)
            require(body.kind == kind)
        }
    }

    data class CatalogIngestion(
        override val path: AgentResourcePath,
        override val body: AgentCatalogJobResourceBody,
        override val availableOperations: List<AgentAvailableOperation>,
    ) : AgentResourceEnvelope {
        override val kind = AgentResourceKind.CATALOG_INGESTION
        init {
            require(availableOperations.size <= 100)
            require(body.kind == kind)
        }
    }

    data class CatalogFailure(
        override val path: AgentResourcePath,
        override val body: AgentCatalogIngestionFailureResourceBody,
        override val availableOperations: List<AgentAvailableOperation>,
    ) : AgentResourceEnvelope {
        override val kind = AgentResourceKind.CATALOG_INGESTION_FAILURE
        init {
            require(availableOperations.size <= 100)
            require(body.kind == kind)
        }
    }

    /** The body is the editable definition, so the last save's time, person, surface, and app sit beside it. */
    data class SemanticModel(
        override val path: AgentResourcePath,
        override val body: AgentSemanticModelResourceBody,
        override val availableOperations: List<AgentAvailableOperation>,
        val updatedAt: Instant,
        val updatedBy: AgentActor?,
        val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
        val agentApp: AgentApp?,
    ) : AgentResourceEnvelope {
        override val kind = AgentResourceKind.SEMANTIC_MODEL
        init {
            require(availableOperations.size <= 100)
            require(body.kind == kind)
            require(agentApp == null || credentialSurface == cc.monomer.identity.api.CredentialSurface.AGENT)
        }
    }

    /** The body is the editable definition, so source candidates and the last save sit beside it. */
    data class Metric(
        override val path: AgentResourcePath,
        override val body: AgentMetricResourceBody,
        override val availableOperations: List<AgentAvailableOperation>,
        val updatedAt: Instant,
        val updatedBy: AgentActor?,
        val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
        val agentApp: AgentApp?,
        val sourceConnectors: List<AgentMetricSourceConnector>,
    ) : AgentResourceEnvelope {
        override val kind = AgentResourceKind.METRIC
        init {
            require(availableOperations.size <= 100)
            require(body.kind == kind)
            require(agentApp == null || credentialSurface == cc.monomer.identity.api.CredentialSurface.AGENT)
        }
    }

    data class ExploreRun(
        override val path: AgentResourcePath,
        override val body: AgentExploreRunResourceBody,
        override val availableOperations: List<AgentAvailableOperation>,
    ) : AgentResourceEnvelope {
        override val kind = AgentResourceKind.EXPLORE_RUN
        init {
            require(availableOperations.size <= 100)
            require(body.kind == kind)
        }
    }

    data class ExploreResult(
        override val path: AgentResourcePath,
        override val body: AgentExploreResultResourceBody,
        override val availableOperations: List<AgentAvailableOperation>,
    ) : AgentResourceEnvelope {
        override val kind = AgentResourceKind.EXPLORE_RESULT
        init {
            require(availableOperations.size <= 100)
            require(body.kind == kind)
        }
    }

    data class DashboardSchedules(
        override val path: AgentResourcePath,
        override val body: AgentDashboardSchedulesBody,
        override val availableOperations: List<AgentAvailableOperation>,
    ) : AgentResourceEnvelope { override val kind = AgentResourceKind.DASHBOARD_SCHEDULES }

    data class DashboardScheduleOccurrence(
        override val path: AgentResourcePath,
        override val body: AgentDashboardScheduleOccurrenceBody,
        override val availableOperations: List<AgentAvailableOperation>,
    ) : AgentResourceEnvelope { override val kind = AgentResourceKind.DASHBOARD_SCHEDULE_OCCURRENCE }

    data class Dashboard(
        override val path: AgentResourcePath,
        override val body: AgentDashboardResourceBody,
        override val availableOperations: List<AgentAvailableOperation>,
    ) : AgentResourceEnvelope {
        override val kind = AgentResourceKind.DASHBOARD
        init {
            require(availableOperations.size <= 100)
            require(body.kind == kind)
        }
    }

    data class DashboardDraft(
        override val path: AgentResourcePath,
        override val revision: AgentRevision,
        override val body: AgentDashboardDraftResourceBody,
        override val availableOperations: List<AgentAvailableOperation>,
    ) : AgentResourceEnvelope {
        override val kind = AgentResourceKind.DASHBOARD_DRAFT
        init {
            require(availableOperations.size <= 100)
            require(body.kind == kind)
        }
    }

    data class DashboardDraftStatus(
        override val path: AgentResourcePath,
        override val body: AgentDashboardDraftStatusBody,
    ) : AgentResourceEnvelope {
        override val kind = AgentResourceKind.DASHBOARD_DRAFT_STATUS
        override val availableOperations: List<AgentAvailableOperation> = emptyList()
    }

    data class PageContent(override val path: AgentResourcePath, override val body: AgentPageContentBody, override val availableOperations: List<AgentAvailableOperation>) : AgentResourceEnvelope { override val kind = body.kind }
    data class PageSession(override val path: AgentResourcePath, override val body: AgentPageSessionBody, override val availableOperations: List<AgentAvailableOperation>) : AgentResourceEnvelope { override val kind = body.kind }
    data class PageRun(override val path: AgentResourcePath, override val body: AgentPageRunBody, override val availableOperations: List<AgentAvailableOperation>) : AgentResourceEnvelope { override val kind = body.kind }

    data class DashboardSession(
        override val path: AgentResourcePath,
        override val body: AgentDashboardSessionResourceBody,
        override val availableOperations: List<AgentAvailableOperation>,
    ) : AgentResourceEnvelope {
        override val kind = AgentResourceKind.DASHBOARD_SESSION
        init {
            require(availableOperations.size <= 100)
            require(body.kind == kind)
        }
    }

    data class DashboardQueryRun(
        override val path: AgentResourcePath,
        override val body: AgentDashboardQueryRunBody,
        override val availableOperations: List<AgentAvailableOperation>,
    ) : AgentResourceEnvelope {
        override val kind = AgentResourceKind.DASHBOARD_QUERY_RUN
        init {
            require(availableOperations.size <= 100)
            require(body.kind == kind)
        }
    }

    data class DashboardQueryResult(
        override val path: AgentResourcePath,
        override val body: AgentDashboardResultResourceBody,
        override val availableOperations: List<AgentAvailableOperation>,
    ) : AgentResourceEnvelope {
        override val kind = AgentResourceKind.DASHBOARD_QUERY_RESULT
        init {
            require(availableOperations.size <= 100)
            require(body.kind == kind)
        }
    }

    data class CatalogRoot(
        override val path: AgentResourcePath,
        override val availableOperations: List<AgentAvailableOperation>,
    ) : AgentResourceEnvelope {
        override val kind = AgentResourceKind.CATALOG_ROOT
        init { require(availableOperations.size <= 100) }
    }

    data class Catalog(
        override val path: AgentResourcePath,
        override val availableOperations: List<AgentAvailableOperation>,
    ) : AgentResourceEnvelope {
        override val kind = AgentResourceKind.CATALOG
        init { require(availableOperations.size <= 100) }
    }

    data class Schema(
        override val path: AgentResourcePath,
        override val availableOperations: List<AgentAvailableOperation>,
    ) : AgentResourceEnvelope {
        override val kind = AgentResourceKind.SCHEMA
        init { require(availableOperations.size <= 100) }
    }
}

enum class AgentRunState { RUNNING, SUCCEEDED, FAILED, CANCELLED }
enum class AgentExploreRunKind { SQL, SEMANTIC }
enum class AgentCatalogJobState { RUNNING, CANCEL_REQUESTED, COMPLETED, COMPLETED_WITH_ERRORS, CANCELLED }
enum class AgentRelationKind { TABLE, VIEW, MATERIALIZED_VIEW, EXTERNAL, FOREIGN, DYNAMIC, SNAPSHOT, UNKNOWN }

data class AgentMetricSourceConnector(val connectorId: Uuid, val displayName: String) {
    init { require(displayName.isNotBlank()) }
}

sealed interface AgentResourceListingItem {
    data class DashboardDefinitionVersion(
        override val path: AgentResourcePath,
        val definitionId: Uuid,
        val name: String,
        val deleted: Boolean,
        val changeId: Uuid,
        val action: String,
        val occurredAt: Instant,
        val actor: AgentActor?,
        val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
        val agentApp: AgentApp?,
        val restorable: Boolean,
    ) : AgentResourceListingItem {
        override val kind = AgentResourceKind.DASHBOARD_VERSION
    }

    data class SemanticDefinitionVersion(
        override val path: AgentResourcePath,
        override val kind: AgentResourceKind,
        val definitionId: Uuid,
        val name: String,
        val deleted: Boolean,
        val changeId: Uuid,
        val action: String,
        val occurredAt: Instant,
        val actor: AgentActor?,
        val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
        val agentApp: AgentApp?,
        val restorable: Boolean,
    ) : AgentResourceListingItem {
        init { require(kind in setOf(AgentResourceKind.SEMANTIC_MODEL_VERSION, AgentResourceKind.METRIC_VERSION)) }
    }

    data class OrganizationMember(override val path: AgentResourcePath, val userId: Uuid, val displayName: String, val role: AgentOrganizationRole) : AgentResourceListingItem { override val kind = AgentResourceKind.ORGANIZATION_MEMBER }
    data class OrganizationInvitation(override val path: AgentResourcePath, val id: Uuid, val email: String, val status: AgentInvitationStatus) : AgentResourceListingItem { override val kind = AgentResourceKind.ORGANIZATION_INVITATION }
    data class OrganizationWorkspaceMember(override val path: AgentResourcePath, val body: AgentWorkspaceMemberBody.Member) : AgentResourceListingItem { override val kind = AgentResourceKind.WORKSPACE_MEMBER }
    data class WorkspaceMember(override val path: AgentResourcePath, val userId: Uuid, val displayName: String, val role: cc.monomer.identity.api.WorkspaceRole) : AgentResourceListingItem { override val kind = AgentResourceKind.WORKSPACE_MEMBER }
    data class WorkspaceMemberCandidate(override val path: AgentResourcePath, val userId: Uuid, val displayName: String) : AgentResourceListingItem { override val kind = AgentResourceKind.WORKSPACE_MEMBER }
    data class LineageResource(override val path: AgentResourcePath, val ref: AgentManagementResource, val label: String, val address: String) : AgentResourceListingItem { override val kind = AgentResourceKind.LINEAGE_RESOURCE }

    data class Page(override val path: AgentResourcePath, val body: AgentPageResourceBody) : AgentResourceListingItem { override val kind = AgentResourceKind.PAGE }
    data class PageVersion(override val path: AgentResourcePath, val body: AgentPageVersionResourceBody) : AgentResourceListingItem { override val kind = AgentResourceKind.PAGE_VERSION }

    data class Connector(override val path: AgentResourcePath, val connectorType: String, val displayName: String) : AgentResourceListingItem {
        override val kind = AgentResourceKind.CONNECTOR
    }

    val kind: AgentResourceKind
    val path: AgentResourcePath

    data class Collection(
        override val path: AgentResourcePath,
        val name: String,
    ) : AgentResourceListingItem {
        override val kind = AgentResourceKind.COLLECTION
    }

    data class SemanticModel(
        override val path: AgentResourcePath,
        val name: String,
    ) : AgentResourceListingItem {
        override val kind = AgentResourceKind.SEMANTIC_MODEL
    }

    data class Metric(
        override val path: AgentResourcePath,
        val name: String,
        val sourceConnectors: List<AgentMetricSourceConnector>,
    ) : AgentResourceListingItem {
        override val kind = AgentResourceKind.METRIC
    }

    data class Catalog(
        override val path: AgentResourcePath,
        val name: String,
    ) : AgentResourceListingItem {
        override val kind = AgentResourceKind.CATALOG
    }

    data class Schema(
        override val path: AgentResourcePath,
        val name: String,
        val updatedAt: Instant?,
    ) : AgentResourceListingItem {
        override val kind = AgentResourceKind.SCHEMA
    }

    data class Relation(
        override val path: AgentResourcePath,
        val name: String,
        val relationKind: AgentRelationKind,
        val updatedAt: Instant?,
    ) : AgentResourceListingItem {
        override val kind = AgentResourceKind.RELATION
    }

    data class CatalogOption(
        override val path: AgentResourcePath,
        val name: String,
    ) : AgentResourceListingItem {
        override val kind = AgentResourceKind.CATALOG_SELECTION_OPTION
    }

    data class SchemaOption(
        override val path: AgentResourcePath,
        val name: String,
        val available: Boolean,
    ) : AgentResourceListingItem {
        override val kind = AgentResourceKind.CATALOG_SELECTION_OPTION
    }

    data class RelationOption(
        override val path: AgentResourcePath,
        val name: String,
        val available: Boolean,
        val relationKind: AgentRelationKind,
    ) : AgentResourceListingItem {
        override val kind = AgentResourceKind.CATALOG_SELECTION_OPTION
    }

    data class CatalogRefresh(
        override val path: AgentResourcePath,
        val state: AgentCatalogJobState,
        val requestedAt: Instant,
        val terminalAt: Instant?,
    ) : AgentResourceListingItem {
        override val kind = AgentResourceKind.CATALOG_SELECTION_OPTION_REFRESH
        init { require((state in setOf(AgentCatalogJobState.RUNNING, AgentCatalogJobState.CANCEL_REQUESTED)) == (terminalAt == null)) }
    }

    data class CatalogIngestion(
        override val path: AgentResourcePath,
        val state: AgentCatalogJobState,
        val requestedAt: Instant,
        val terminalAt: Instant?,
    ) : AgentResourceListingItem {
        override val kind = AgentResourceKind.CATALOG_INGESTION
        init { require((state in setOf(AgentCatalogJobState.RUNNING, AgentCatalogJobState.CANCEL_REQUESTED)) == (terminalAt == null)) }
    }

    data class CatalogFailure(
        override val path: AgentResourcePath,
    ) : AgentResourceListingItem {
        override val kind = AgentResourceKind.CATALOG_INGESTION_FAILURE
    }

    data class ExploreRun(
        override val path: AgentResourcePath,
        val runKind: AgentExploreRunKind,
        val connectorId: Uuid,
        val state: AgentRunState,
        val createdAt: Instant,
        val updatedAt: Instant,
    ) : AgentResourceListingItem {
        override val kind = AgentResourceKind.EXPLORE_RUN
    }

    data class DashboardScheduleOccurrence(
        override val path: AgentResourcePath,
        val body: AgentDashboardOccurrenceSummary,
    ) : AgentResourceListingItem { override val kind = AgentResourceKind.DASHBOARD_SCHEDULE_OCCURRENCE }

    data class Dashboard(
        override val path: AgentResourcePath,
        val title: String?,
        val description: String?,
        val updatedAt: Instant,
    ) : AgentResourceListingItem {
        override val kind = AgentResourceKind.DASHBOARD
    }

    data class PageSession(override val path: AgentResourcePath, val createdAt: Instant, val updatedAt: Instant) : AgentResourceListingItem { override val kind = AgentResourceKind.PAGE_SESSION }
    data class PageRun(override val path: AgentResourcePath, val body: AgentPageRunBody) : AgentResourceListingItem { override val kind = AgentResourceKind.PAGE_QUERY_RUN }

    data class DashboardSession(
        override val path: AgentResourcePath,
        val parameterBindings: Map<String, JsonElement>,
        val runningQueryRuns: List<AgentDashboardSessionCurrentQueryRun>,
        val lastQueryRunAt: Instant?,
        val createdAt: Instant,
        val updatedAt: Instant,
    ) : AgentResourceListingItem {
        override val kind = AgentResourceKind.DASHBOARD_SESSION
    }

    data class DashboardQueryRun(
        override val path: AgentResourcePath,
        val queryName: String,
        val queryType: String,
        val connectorId: Uuid,
        val state: AgentRunState,
        val createdAt: Instant,
        val updatedAt: Instant,
    ) : AgentResourceListingItem {
        init { require(queryType in setOf("SEMANTIC", "SQL")) }
        override val kind = AgentResourceKind.DASHBOARD_QUERY_RUN
    }
}

data class AgentDashboardSessionCurrentQueryRun(
    val queryName: String,
    val path: AgentResourcePath,
    val state: String,
) {
    init {
        require(queryName.isNotBlank())
        require(state in setOf("RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"))
    }
}

data class AgentResourceListingResponse(
    val items: List<AgentResourceListingItem>,
    val nextCursor: String?,
    val availableOperations: List<AgentAvailableOperation>,
) {
    init {
        require(items.size <= 100)
        require(nextCursor == null || nextCursor.length <= AgentResourcePath.MAXIMUM_CURSOR_LENGTH)
        require(availableOperations.size <= 100)
    }
}

enum class AgentActionStatus {
    ACCEPTED,
    COMPLETED,
}

sealed interface AgentActionResult {
    val operation: AgentWorkspaceOperation
    val path: AgentResourcePath
    val status: AgentActionStatus
    val result: AgentActionResultReference

    data class PageUploadPrepared(override val path: AgentResourcePath, override val result: AgentActionResultReference, val upload: AgentPageUploadMetadata, val partSizeBytes: Long, val parts: List<AgentPageUploadPart>) : AgentActionResult {
        override val operation = AgentWorkspaceOperation.CREATE
        override val status = AgentActionStatus.COMPLETED
        override fun toString(): String = "PageUploadPrepared([REDACTED])"
    }
    data class PageUploadCompleted(override val path: AgentResourcePath, override val result: AgentActionResultReference, val upload: AgentPageUploadMetadata) : AgentActionResult {
        override val operation = AgentWorkspaceOperation.INVOKE
        override val status = AgentActionStatus.COMPLETED
        val action = "complete"
    }

    data class Create(
        override val path: AgentResourcePath,
        override val result: AgentActionResultReference,
    ) : AgentActionResult {
        override val operation = AgentWorkspaceOperation.CREATE
        override val status = AgentActionStatus.COMPLETED
    }

    data class Edit(
        override val path: AgentResourcePath,
        override val result: AgentActionResultReference,
    ) : AgentActionResult {
        override val operation = AgentWorkspaceOperation.EDIT
        override val status = AgentActionStatus.COMPLETED
    }

    data class DashboardSessionResolution(
        override val path: AgentResourcePath,
        val action: String,
        override val status: AgentActionStatus,
        override val result: AgentActionResultReference,
        val variables: Map<String, JsonElement>,
        val queryRuns: Map<String, AgentResourcePath>,
    ) : AgentActionResult {
        override val operation = AgentWorkspaceOperation.INVOKE
    }

    /** A Semantic check returns its resolution as data; problems are not a failed action. */
    data class SemanticCheck(
        override val path: AgentResourcePath,
        override val result: AgentActionResultReference,
        val check: JsonElement,
    ) : AgentActionResult {
        override val operation = AgentWorkspaceOperation.INVOKE
        override val status = AgentActionStatus.COMPLETED
        val action = "check"
    }

    data class Invoke(
        override val path: AgentResourcePath,
        val action: String,
        override val status: AgentActionStatus,
        override val result: AgentActionResultReference,
    ) : AgentActionResult {
        override val operation = AgentWorkspaceOperation.INVOKE
        init { require(action.isNotBlank() && action.length <= 128) }
    }
}

data class AgentActionResultReference(
    val kind: AgentResourceKind,
    val path: AgentResourcePath,
)

interface AgentWorkspaceHandler {
    suspend fun list(
        caller: AgentWorkspaceCaller,
        request: AgentListRequest,
    ): AgentListingOutcome

    suspend fun search(
        caller: AgentWorkspaceCaller,
        request: AgentSearchRequest,
    ): AgentSearchOutcome

    suspend fun read(
        caller: AgentWorkspaceCaller,
        request: AgentReadRequest,
    ): AgentResourceOutcome

    suspend fun create(
        caller: AgentWorkspaceCaller,
        request: AgentCreateRequest,
    ): AgentActionOutcome

    suspend fun edit(
        caller: AgentWorkspaceCaller,
        request: AgentEditRequest,
    ): AgentActionOutcome

    suspend fun invoke(
        caller: AgentWorkspaceCaller,
        request: AgentInvokeRequest,
    ): AgentActionOutcome
}

sealed interface AgentListingOutcome {
    data class Found(
        val listing: AgentResourceListingResponse,
    ) : AgentListingOutcome

    data class Rejected(
        val problem: AgentWorkspaceProblem,
    ) : AgentListingOutcome

    data object NotFound : AgentListingOutcome

    data object Forbidden : AgentListingOutcome

    data object Unavailable : AgentListingOutcome
}

sealed interface AgentResourceOutcome {
    data class Found(
        val resource: AgentResourceEnvelope,
    ) : AgentResourceOutcome

    data class Rejected(
        val problem: AgentWorkspaceProblem,
    ) : AgentResourceOutcome

    data object NotFound : AgentResourceOutcome

    data object Forbidden : AgentResourceOutcome

    data object Unavailable : AgentResourceOutcome
}

sealed interface AgentActionOutcome {
    data class Completed(
        val action: AgentActionResult,
    ) : AgentActionOutcome

    data class Rejected(
        val problem: AgentWorkspaceProblem,
    ) : AgentActionOutcome

    data object NotFound : AgentActionOutcome

    data object Forbidden : AgentActionOutcome

    data object Unavailable : AgentActionOutcome
}

enum class AgentWorkspaceProblemStatus(
    val value: Int,
) {
    BAD_REQUEST(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    CONFLICT(409),
    TOO_MANY_REQUESTS(429),
    SERVICE_UNAVAILABLE(503),
}

data class AgentWorkspaceProblem(
    val code: String,
    val title: String,
    val detail: String,
    val status: AgentWorkspaceProblemStatus,
    val retryable: Boolean,
    val currentRevision: AgentRevision?,
    val violations: List<AgentProblemViolation>,
    val hints: List<String>,
    val nextActions: List<AgentAvailableOperation>,
    val retryAfterSeconds: Long?,
    val roleRequirement: AgentRoleRequirement?,
    val partitionFilters: List<AgentPartitionFilter>,
) {
    init {
        require(code.matches(Regex("^[A-Z][A-Z0-9_]{0,127}$")))
        require(title.isNotBlank())
        require(detail.isNotBlank())
        require(partitionFilters.size <= 16)
        require(violations.size <= 100)
        require(hints.size <= 20 && hints.all(String::isNotBlank))
        require(nextActions.size <= 50)
        require(retryAfterSeconds == null || retryAfterSeconds >= 0)
        require(roleRequirement == null || status == AgentWorkspaceProblemStatus.FORBIDDEN)
    }
}

/**
 * The role an accessible target needs and the role the caller holds. [askOwners] names the people who can grant it,
 * and only names already visible to the caller: Workspace Owners for a Workspace member, nobody for an Organization
 * action because the Admin roster is visible only to Admins.
 */
data class AgentRoleRequirement(
    val requiredRole: String,
    val currentRole: String,
    val askOwners: List<String>,
) {
    init {
        require(requiredRole in ROLES && currentRole in ROLES && requiredRole != currentRole)
        require(askOwners.size <= 20 && askOwners.all { it.isNotBlank() && it.length <= 200 })
    }

    private companion object {
        val ROLES = setOf("VIEWER", "EXPLORER", "EDITOR", "OWNER", "MEMBER", "ADMIN")
    }
}

data class AgentProblemViolation(
    val field: String,
    val code: String,
    val message: String,
    val operationIndex: Int?,
    val path: String?,
) {
    init {
        require(field.isNotBlank() && field.length <= 512)
        require(code.matches(Regex("^[A-Z][A-Z0-9_]{0,127}$")))
        require(message.isNotBlank() && message.length <= 512)
        require(operationIndex == null || operationIndex >= 0)
        require(path == null || path.length <= 2_048)
    }
}

interface AgentExploreOwnerPort {
    suspend fun list(
        caller: AgentWorkspaceCaller,
        request: AgentListRequest,
    ): AgentListingOutcome

    suspend fun read(
        caller: AgentWorkspaceCaller,
        request: AgentReadRequest,
    ): AgentResourceOutcome

    suspend fun invoke(
        caller: AgentWorkspaceCaller,
        request: AgentInvokeRequest,
    ): AgentActionOutcome
}

interface AgentConnectorOwnerPort {
    suspend fun create(caller: AgentWorkspaceCaller, request: AgentCreateRequest): AgentActionOutcome
    suspend fun edit(caller: AgentWorkspaceCaller, request: AgentEditRequest): AgentActionOutcome
    suspend fun invoke(caller: AgentWorkspaceCaller, request: AgentInvokeRequest): AgentActionOutcome

    suspend fun list(
        caller: AgentWorkspaceCaller,
        request: AgentListRequest,
    ): AgentListingOutcome

    suspend fun read(
        caller: AgentWorkspaceCaller,
        request: AgentReadRequest,
    ): AgentResourceOutcome
}

interface AgentCatalogDiscoveryPort {
    suspend fun list(
        caller: AgentWorkspaceCaller,
        request: AgentListRequest,
    ): AgentListingOutcome

    suspend fun read(
        caller: AgentWorkspaceCaller,
        request: AgentReadRequest,
    ): AgentResourceOutcome

    suspend fun edit(
        caller: AgentWorkspaceCaller,
        request: AgentEditRequest,
    ): AgentActionOutcome

    suspend fun invoke(
        caller: AgentWorkspaceCaller,
        request: AgentInvokeRequest,
    ): AgentActionOutcome
}

interface AgentSemanticOwnerPort {
    suspend fun list(
        caller: AgentWorkspaceCaller,
        request: AgentListRequest,
    ): AgentListingOutcome

    suspend fun read(
        caller: AgentWorkspaceCaller,
        request: AgentReadRequest,
    ): AgentResourceOutcome

    suspend fun create(
        caller: AgentWorkspaceCaller,
        request: AgentCreateRequest,
    ): AgentActionOutcome

    suspend fun edit(
        caller: AgentWorkspaceCaller,
        request: AgentEditRequest,
    ): AgentActionOutcome

    suspend fun invoke(
        caller: AgentWorkspaceCaller,
        request: AgentInvokeRequest,
    ): AgentActionOutcome
}

interface AgentDashboardOwnerPort {
    suspend fun list(
        caller: AgentWorkspaceCaller,
        request: AgentListRequest,
    ): AgentListingOutcome

    suspend fun read(
        caller: AgentWorkspaceCaller,
        request: AgentReadRequest,
    ): AgentResourceOutcome

    suspend fun create(
        caller: AgentWorkspaceCaller,
        request: AgentCreateRequest,
    ): AgentActionOutcome

    suspend fun edit(
        caller: AgentWorkspaceCaller,
        request: AgentEditRequest,
    ): AgentActionOutcome

    suspend fun invoke(
        caller: AgentWorkspaceCaller,
        request: AgentInvokeRequest,
    ): AgentActionOutcome
}
