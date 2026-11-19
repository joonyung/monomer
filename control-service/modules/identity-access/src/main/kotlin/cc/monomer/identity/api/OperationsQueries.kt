package cc.monomer.identity.api

import java.time.Instant

interface IdentityOperationsQueries {
    suspend fun overview(access: OperationsAccess, from: Instant, to: Instant): OperationsIdentityOverview
    suspend fun users(access: OperationsAccess, q: String?, from: Instant?, to: Instant?, before: Instant?, beforeId: String?, limit: Int): List<OperationsUser>
    suspend fun user(access: OperationsAccess, id: String): OperationsUserDetail?
    suspend fun workspaces(access: OperationsAccess, q: String?, organizationId: String?, activeWorkspaceIds: Set<String>?, before: Instant?, beforeId: String?, limit: Int): List<OperationsWorkspace>
    suspend fun workspace(access: OperationsAccess, id: String): OperationsWorkspaceDetail?
    suspend fun workspaceNames(access: OperationsAccess, ids: Set<String>): Map<String, String>
    suspend fun journeyPopulation(access: OperationsAccess, createdBefore: Instant, excludedOrganizationIds: Set<String>, limit: Int): OperationsJourneyPopulation
    suspend fun firstAgentAuthorizations(access: OperationsAccess, organizationIds: Set<String>): Map<String, Instant>
}
data class OperationsUser(val id: String, val name: String, val email: String, val createdAt: Instant, val organizationCount: Long, val workspaceCount: Long)
data class OperationsOrganizationMembership(val id: String, val name: String, val role: String)
data class OperationsWorkspaceMembership(val id: String, val name: String, val organizationId: String, val role: String)
data class OperationsUserDetail(val user: OperationsUser, val organizations: List<OperationsOrganizationMembership>, val workspaces: List<OperationsWorkspaceMembership>)
data class OperationsWorkspace(val id: String, val name: String, val organizationId: String, val organizationName: String, val createdAt: Instant, val memberCount: Long)
data class OperationsMember(val id: String, val name: String, val email: String, val role: String)
data class OperationsWorkspaceDetail(val workspace: OperationsWorkspace, val members: List<OperationsMember>)
data class OperationsIdentityDaily(val date: String, val newUsers: Long)
data class OperationsIdentityOverview(val currentUsers: Long, val currentOrganizations: Long, val currentWorkspaces: Long, val newUsers: Long, val daily: List<OperationsIdentityDaily>, val recentUsers: List<OperationsUser>)
data class OperationsJourneyContact(val name: String, val email: String)
data class OperationsJourneyOrganization(val id: String, val name: String, val createdAt: Instant, val memberUserIds: Set<String>, val workspaceIds: Set<String>, val contacts: List<OperationsJourneyContact>)
data class OperationsJourneyPopulation(val organizations: List<OperationsJourneyOrganization>, val excludedOrganizations: Long)
