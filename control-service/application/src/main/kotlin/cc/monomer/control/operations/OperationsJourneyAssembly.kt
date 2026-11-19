package cc.monomer.control.operations

import cc.monomer.dashboard.api.OperationsDashboardJourneyActivity
import cc.monomer.explore.api.OperationsExploreJourneyActivity
import cc.monomer.identity.api.AgentClientId
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.OperationsJourneyContact
import cc.monomer.identity.api.OperationsJourneyOrganization
import cc.monomer.identity.api.OperationsJourneyPopulation
import cc.monomer.semantic.api.OperationsSemanticJourneyActivity
import java.time.Instant
import java.time.LocalDate

internal class OperationsJourneySources(
    val population: OperationsJourneyPopulation,
    val firstConnectorCreatedAt: Map<String, Instant>?,
    val firstSampleConnectorCreatedAt: Map<String, Instant>?,
    val firstAgentAuthorizations: Map<String, Instant>?,
    val explore: OperationsExploreJourneyActivity?,
    val dashboard: OperationsDashboardJourneyActivity?,
    val semantic: OperationsSemanticJourneyActivity?,
) {
    val unavailableSources: List<String> = buildList {
        if (firstConnectorCreatedAt == null || firstSampleConnectorCreatedAt == null) add("connector")
        if (explore == null) add("explore")
        if (dashboard == null) add("dashboard")
        if (semantic == null) add("semantic")
        if (firstAgentAuthorizations == null) add("agentGrants")
    }
    val activitySourcesAvailable: Boolean = explore != null && dashboard != null && semantic != null
}

/**
 * Activity counts only when its actor is a current member of the Organization that owns the activity's Workspace.
 * A value that depends on an unavailable source is null rather than a partial count.
 */
internal class OperationsJourneyAssembly(private val sources: OperationsJourneySources, private val weeks: List<LocalDate>) {
    private enum class ActivityKind { EXPLORE_RUN, DASHBOARD_QUERY_RUN, DEFINITION_CHANGE }

    private data class MemberActivity(
        val organizationId: String,
        val weekStart: LocalDate,
        val actorId: String,
        val kind: ActivityKind,
        val sampleDataset: Boolean,
        val credentialSurface: CredentialSurface?,
        val agentClientId: AgentClientId?,
        val rows: Long,
        val succeededRows: Long,
    )

    private val organizationByWorkspace: Map<String, OperationsJourneyOrganization> =
        sources.population.organizations.flatMap { organization -> organization.workspaceIds.map { it to organization } }.toMap()

    private fun memberOrganizationId(workspaceId: String, actorId: String): String? =
        organizationByWorkspace[workspaceId]?.takeIf { actorId in it.memberUserIds }?.id

    private val memberActivities: List<MemberActivity> = buildList {
        fun addMemberActivity(workspaceId: String, weekStart: LocalDate, actorId: String, kind: ActivityKind, sampleDataset: Boolean, surface: CredentialSurface?, client: AgentClientId?, rows: Long, succeededRows: Long) {
            val organizationId = memberOrganizationId(workspaceId, actorId) ?: return
            add(MemberActivity(organizationId, weekStart, actorId, kind, sampleDataset, surface, client, rows, succeededRows))
        }
        sources.explore?.weeklyRuns?.forEach {
            addMemberActivity(it.workspaceId, it.weekStart, it.actorId, ActivityKind.EXPLORE_RUN, it.sampleDataset, it.credentialSurface, it.agentClientId, it.runs, it.succeededRuns)
        }
        sources.dashboard?.weeklyQueryRuns?.forEach {
            addMemberActivity(it.workspaceId, it.weekStart, it.actorId, ActivityKind.DASHBOARD_QUERY_RUN, false, it.credentialSurface, it.agentClientId, it.runs, it.succeededRuns)
        }
        sources.dashboard?.weeklyDefinitionChanges?.forEach {
            addMemberActivity(it.workspaceId, it.weekStart, it.actorId, ActivityKind.DEFINITION_CHANGE, false, it.credentialSurface, it.agentClientId, it.changes, 0)
        }
        sources.semantic?.weeklyDefinitionChanges?.forEach {
            addMemberActivity(it.workspaceId, it.weekStart, it.actorId, ActivityKind.DEFINITION_CHANGE, false, it.credentialSurface, it.agentClientId, it.changes, 0)
        }
    }
    private val activitiesByOrganization = memberActivities.groupBy { it.organizationId }
    private val activitiesByOrganizationWeek = memberActivities.groupBy { it.organizationId to it.weekStart }
    private val exploreMilestonesByOrganization = sources.explore?.actorMilestones.orEmpty().groupBy { memberOrganizationId(it.workspaceId, it.actorId) }
    private val dashboardMilestonesByOrganization = sources.dashboard?.actorMilestones.orEmpty().groupBy { memberOrganizationId(it.workspaceId, it.actorId) }
    private val semanticMilestonesByOrganization = sources.semantic?.actorMilestones.orEmpty().groupBy { memberOrganizationId(it.workspaceId, it.actorId) }

    val agentClientIds: Set<AgentClientId> = memberActivities.mapNotNull { it.agentClientId }.toSet()

    fun response(queriedAt: Instant, from: Instant, to: Instant, agentAppNames: Map<AgentClientId, String>): OperationsJourneysResponse {
        val items = sources.population.organizations.map { item(it, agentAppNames) }
        val weeklyTotals = weeks.mapIndexed { index, week ->
            if (!sources.activitySourcesAvailable) OperationsJourneyWeeklyTotal(week.toString(), null, null)
            else OperationsJourneyWeeklyTotal(
                week.toString(),
                items.count { it.weekly[index].active == true }.toLong(),
                memberActivities.filter { it.weekStart == week }.map { it.actorId }.toSet().size.toLong(),
            )
        }
        return OperationsJourneysResponse(
            queriedAt, from, to, weeks.map(LocalDate::toString), sources.population.excludedOrganizations,
            sources.unavailableSources, weeklyTotals, items,
        )
    }

    private fun item(organization: OperationsJourneyOrganization, agentAppNames: Map<AgentClientId, String>): OperationsJourneyItem {
        val agentApps = if (!sources.activitySourcesAvailable) null
        else activitiesByOrganization[organization.id].orEmpty().mapNotNull { it.agentClientId }.toSet()
            .mapNotNull(agentAppNames::get).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER.thenBy { it })
        return OperationsJourneyItem(
            organization.id, organization.name, organization.createdAt, organization.memberUserIds.size.toLong(),
            organization.workspaceIds.size.toLong(), organization.contacts, agentApps, milestones(organization),
            weeks.map { week(organization.id, it) },
        )
    }

    private fun week(organizationId: String, week: LocalDate): OperationsJourneyWeek {
        val activities = activitiesByOrganizationWeek[organizationId to week].orEmpty()
        val exploreRuns = activities.filter { it.kind == ActivityKind.EXPLORE_RUN }
        val queryRuns = activities.filter { it.kind == ActivityKind.DASHBOARD_QUERY_RUN }
        val definitionChanges = activities.filter { it.kind == ActivityKind.DEFINITION_CHANGE }
        val qualifyingActivity = exploreRuns.any { it.succeededRows > 0 } || queryRuns.any { it.succeededRows > 0 } || definitionChanges.isNotEmpty()
        return OperationsJourneyWeek(
            week = week.toString(),
            active = when {
                qualifyingActivity -> true
                sources.activitySourcesAvailable -> false
                else -> null
            },
            activeUsers = activities.map { it.actorId }.toSet().size.toLong().takeIf { sources.activitySourcesAvailable },
            agentClients = activities.mapNotNull { it.agentClientId }.toSet().size.toLong().takeIf { sources.activitySourcesAvailable },
            exploreRuns = if (sources.explore == null) null else OperationsJourneyExploreRuns(
                exploreRuns.sumOf { it.rows }, exploreRuns.sumOf { it.succeededRows },
                exploreRuns.filter { it.credentialSurface == CredentialSurface.AGENT }.sumOf { it.succeededRows },
                exploreRuns.filter { it.credentialSurface == null }.sumOf { it.succeededRows },
                sampleTotal = exploreRuns.filter { it.sampleDataset }.sumOf { it.rows },
                sampleSucceeded = exploreRuns.filter { it.sampleDataset }.sumOf { it.succeededRows },
                externalTotal = exploreRuns.filterNot { it.sampleDataset }.sumOf { it.rows },
                externalSucceeded = exploreRuns.filterNot { it.sampleDataset }.sumOf { it.succeededRows },
            ),
            dashboardRuns = if (sources.dashboard == null) null else OperationsJourneyDashboardRuns(
                queryRuns.sumOf { it.rows }, queryRuns.sumOf { it.succeededRows },
                queryRuns.filter { it.credentialSurface == CredentialSurface.AGENT }.sumOf { it.succeededRows },
            ),
            definitionChanges = if (sources.dashboard == null || sources.semantic == null) null else OperationsJourneyDefinitionChanges(
                definitionChanges.sumOf { it.rows },
                definitionChanges.filter { it.credentialSurface == CredentialSurface.AGENT }.sumOf { it.rows },
                definitionChanges.filter { it.credentialSurface == null }.sumOf { it.rows },
            ),
        )
    }

    private fun milestones(organization: OperationsJourneyOrganization): OperationsJourneyMilestones {
        val explore = exploreMilestonesByOrganization[organization.id].orEmpty()
        val dashboard = dashboardMilestonesByOrganization[organization.id].orEmpty()
        val semantic = semanticMilestonesByOrganization[organization.id].orEmpty()
        val sampleWorkspaceIds = sources.firstSampleConnectorCreatedAt?.keys.orEmpty()
        fun Map<String, Instant>.inOrganizationWorkspaces(): List<Instant> = organization.workspaceIds.mapNotNull { this[it] }
        return OperationsJourneyMilestones(
            firstConnectorAt = if ("connector" in sources.unavailableSources) null else sources.firstConnectorCreatedAt?.inOrganizationWorkspaces()?.minOrNull(),
            firstSampleConnectorAt = if ("connector" in sources.unavailableSources) null else sources.firstSampleConnectorCreatedAt?.inOrganizationWorkspaces()?.minOrNull(),
            firstSampleAgentRunAt = if ("connector" in sources.unavailableSources || sources.explore == null || sources.dashboard == null) null
            else (explore.filter { it.workspaceId in sampleWorkspaceIds }.mapNotNull { it.firstAgentAt } +
                dashboard.filter { it.workspaceId in sampleWorkspaceIds }.mapNotNull { it.firstAgentQueryRunAt }).minOrNull(),
            firstAgentAuthorizedAt = sources.firstAgentAuthorizations?.get(organization.id),
            firstSucceededResultAt = if (sources.explore == null || sources.dashboard == null) null
            else (explore.mapNotNull { it.firstSucceededAt } + dashboard.mapNotNull { it.firstSucceededQueryRunAt }).minOrNull(),
            firstMetricAt = if (sources.semantic == null) null
            else (semantic.mapNotNull { it.firstMetricCreatedChangeAt } + sources.semantic.firstMetricCreatedAtByWorkspace.inOrganizationWorkspaces()).minOrNull(),
            firstDashboardAt = if (sources.dashboard == null) null
            else (dashboard.mapNotNull { it.firstCreatedChangeAt } + sources.dashboard.firstDashboardCreatedAtByWorkspace.inOrganizationWorkspaces()).minOrNull(),
            firstAgentActivityAt = if (!sources.activitySourcesAvailable) null
            else (explore.mapNotNull { it.firstAgentAt } + dashboard.mapNotNull { it.firstAgentAt } + semantic.mapNotNull { it.firstAgentAt }).minOrNull(),
        )
    }
}

data class OperationsJourneysResponse(
    val queriedAt: Instant,
    val from: Instant,
    val to: Instant,
    val weeks: List<String>,
    val excludedOrganizations: Long,
    val unavailableSources: List<String>,
    val weeklyTotals: List<OperationsJourneyWeeklyTotal>,
    val items: List<OperationsJourneyItem>,
)
data class OperationsJourneyWeeklyTotal(val week: String, val activeOrganizations: Long?, val activeUsers: Long?)
data class OperationsJourneyItem(
    val organizationId: String,
    val name: String,
    val createdAt: Instant,
    val memberCount: Long,
    val workspaceCount: Long,
    val contacts: List<OperationsJourneyContact>,
    val agentApps: List<String>?,
    val milestones: OperationsJourneyMilestones,
    val weekly: List<OperationsJourneyWeek>,
)
data class OperationsJourneyMilestones(
    val firstConnectorAt: Instant?,
    val firstSampleConnectorAt: Instant?,
    val firstSampleAgentRunAt: Instant?,
    val firstAgentAuthorizedAt: Instant?,
    val firstSucceededResultAt: Instant?,
    val firstMetricAt: Instant?,
    val firstDashboardAt: Instant?,
    val firstAgentActivityAt: Instant?,
)
data class OperationsJourneyWeek(
    val week: String,
    val active: Boolean?,
    val activeUsers: Long?,
    val agentClients: Long?,
    val exploreRuns: OperationsJourneyExploreRuns?,
    val dashboardRuns: OperationsJourneyDashboardRuns?,
    val definitionChanges: OperationsJourneyDefinitionChanges?,
)
data class OperationsJourneyExploreRuns(
    val total: Long,
    val succeeded: Long,
    val succeededByAgent: Long,
    val succeededUnknownSurface: Long,
    val sampleTotal: Long,
    val sampleSucceeded: Long,
    val externalTotal: Long,
    val externalSucceeded: Long,
)
data class OperationsJourneyDashboardRuns(val total: Long, val succeeded: Long, val succeededByAgent: Long)
data class OperationsJourneyDefinitionChanges(val total: Long, val byAgent: Long, val unknownSurface: Long)
