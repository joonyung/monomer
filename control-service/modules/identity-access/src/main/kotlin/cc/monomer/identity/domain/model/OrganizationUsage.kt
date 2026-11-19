package cc.monomer.identity.domain.model

import cc.monomer.identity.api.OrganizationPlan

object FreeOrganizationLimits {
    const val ADMINISTERED_ORGANIZATIONS = 5
    const val MEMBERS = 30
    const val WORKSPACES = 5
    const val PENDING_INVITATIONS = 30
}

data class OrganizationResourceCounts(
    val members: Int,
    val workspaces: Int,
    val pendingInvitations: Int,
)

/** Current counts of the plan-limited Organization resources; [limits] is null for plans without free limits. */
data class OrganizationUsage(
    val plan: OrganizationPlan,
    val limits: OrganizationResourceCounts?,
    val usage: OrganizationResourceCounts,
) {
    companion object {
        fun of(plan: OrganizationPlan, usage: OrganizationResourceCounts): OrganizationUsage = OrganizationUsage(
            plan = plan,
            limits = if (plan == OrganizationPlan.FREE) {
                OrganizationResourceCounts(
                    members = FreeOrganizationLimits.MEMBERS,
                    workspaces = FreeOrganizationLimits.WORKSPACES,
                    pendingInvitations = FreeOrganizationLimits.PENDING_INVITATIONS,
                )
            } else {
                null
            },
            usage = usage,
        )
    }
}
