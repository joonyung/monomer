@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.agent

import cc.monomer.agent.api.AgentRoleRequirement
import cc.monomer.agent.api.AgentWorkspaceAdmissionIntent
import cc.monomer.agent.api.AgentWorkspaceOperation
import cc.monomer.agent.api.AgentWorkspaceProblem
import cc.monomer.agent.api.AgentWorkspaceProblemStatus
import cc.monomer.agent.api.requiredRole
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.WorkspaceMembershipAdmissionRequest
import cc.monomer.identity.api.AgentOAuthPort
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.OAuthOutcome
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.PrincipalDisplayNames
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.application.permission.WorkspaceAccessService
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.port.WorkspaceAccessQueryResult

/**
 * Explains a role denial on a Workspace the caller is a direct member of. Every fact comes from what that member can
 * already read: their own role, the Workspace name, and the Owner names the Web shows to members (W2). A caller
 * without membership gets no explanation, so a missing and an inaccessible Workspace stay indistinguishable.
 */
class AgentWorkspaceRoleProblems(
    private val admission: ControlAdmissionFacade,
    private val workspaceAccess: WorkspaceAccessService,
    private val oauth: AgentOAuthPort,
    private val displayNames: PrincipalDisplayNames,
) {
    suspend fun explain(
        workspaceId: WorkspaceId,
        credential: PresentedCredential,
        expectedOAuthResource: ExpectedOAuthResource,
        intent: AgentWorkspaceAdmissionIntent,
    ): AgentWorkspaceProblem? = try {
        describe(workspaceId, credential, expectedOAuthResource, intent)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun describe(
        workspaceId: WorkspaceId,
        credential: PresentedCredential,
        expectedOAuthResource: ExpectedOAuthResource,
        intent: AgentWorkspaceAdmissionIntent,
    ): AgentWorkspaceProblem? {
        val membership = when (val decision = admission.authorizeWorkspaceMembership(
            WorkspaceMembershipAdmissionRequest(workspaceId, credential, expectedOAuthResource),
        )) {
            is ControlAdmissionDecision.Admitted -> decision.context
            is ControlAdmissionDecision.Denied -> return null
        }
        val requiredRole = intent.requiredRole
        if (membership.role.includes(requiredRole)) return null
        val owners = when (val access = workspaceAccess.read(UserId(membership.actor.id.value), cc.monomer.identity.domain.id.WorkspaceId(workspaceId.value))) {
            is WorkspaceAccessQueryResult.Authorized -> access.projection.owners.map { it.displayName }.filter(String::isNotBlank).distinct().take(20)
            is WorkspaceAccessQueryResult.Denied -> return null
        }
        val workspaceName = (oauth.listWorkspaces(credential, expectedOAuthResource) as? OAuthOutcome.Success)
            ?.value?.firstOrNull { it.workspaceId == workspaceId }?.displayName
        val personName = displayNames.read(setOf(membership.actor.id.value))[membership.actor.id.value]
        return roleRequiredProblem(
            action = intent.actionPhrase(),
            scope = workspaceName?.let { "in $it" } ?: "in this Workspace",
            requiredRole = requiredRole.label(),
            currentRole = membership.role.label(),
            personName = personName,
            askOwners = owners,
            fallbackAsk = "a Workspace Owner",
            requirement = AgentRoleRequirement(requiredRole.name, membership.role.name, owners),
        )
    }
}

/** An Organization action needs the Admin role. The Admin roster is Admin-only, so no names are offered. */
internal fun organizationAdminRequiredProblem(personName: String?, organizationName: String?): AgentWorkspaceProblem =
    roleRequiredProblem(
        action = "This Organization action",
        scope = organizationName?.let { "in $it" } ?: "in this Organization",
        requiredRole = "Admin",
        currentRole = "Member",
        personName = personName,
        askOwners = emptyList(),
        fallbackAsk = "an Organization Admin",
        requirement = AgentRoleRequirement("ADMIN", "MEMBER", emptyList()),
    )

private fun roleRequiredProblem(
    action: String,
    scope: String,
    requiredRole: String,
    currentRole: String,
    personName: String?,
    askOwners: List<String>,
    fallbackAsk: String,
    requirement: AgentRoleRequirement,
): AgentWorkspaceProblem {
    val person = personName?.takeIf(String::isNotBlank) ?: "You"
    val holds = if (person == "You") "You are ${article(currentRole)} $currentRole." else "$person is ${article(currentRole)} $currentRole."
    val ask = when (askOwners.size) {
        0 -> fallbackAsk
        1, 2 -> askOwners.joinToString(" or ")
        else -> "${askOwners.first()} or another Owner"
    }
    val subject = if (person == "You") "you" else person
    return AgentWorkspaceProblem(
        code = "ROLE_REQUIRED",
        title = "$requiredRole role required",
        detail = "$action $scope needs the $requiredRole role. $holds",
        status = AgentWorkspaceProblemStatus.FORBIDDEN,
        retryable = false,
        currentRevision = null,
        violations = emptyList(),
        hints = listOf("Ask $ask to give $subject the $requiredRole role, then try again."),
        nextActions = emptyList(),
        retryAfterSeconds = null,
        roleRequirement = requirement,
        partitionFilters = emptyList(),
    )
}

private fun article(role: String): String = if (role.first().lowercaseChar() in "aeiou") "an" else "a"

private fun WorkspaceRole.label(): String = name.lowercase().replaceFirstChar(Char::uppercaseChar)

private fun AgentWorkspaceAdmissionIntent.actionPhrase(): String = when (this) {
    is AgentWorkspaceAdmissionIntent.ConnectorCreate -> "Adding a connector"
    is AgentWorkspaceAdmissionIntent.ConnectorEdit, is AgentWorkspaceAdmissionIntent.ConnectorRotate -> "Changing a connector"
    is AgentWorkspaceAdmissionIntent.ConnectorDelete -> "Deleting a connector"
    is AgentWorkspaceAdmissionIntent.SemanticModelCreate -> "Creating a semantic model"
    is AgentWorkspaceAdmissionIntent.SemanticModelEdit -> "Changing a semantic model"
    is AgentWorkspaceAdmissionIntent.MetricCreate -> "Creating a metric"
    is AgentWorkspaceAdmissionIntent.MetricEdit -> "Changing a metric"
    is AgentWorkspaceAdmissionIntent.SemanticDelete -> "Deleting a definition"
    is AgentWorkspaceAdmissionIntent.SemanticRestore -> "Restoring a definition"
    is AgentWorkspaceAdmissionIntent.SemanticCheck -> "Checking a definition"
    is AgentWorkspaceAdmissionIntent.CatalogEdit -> "Changing the Catalog selection"
    is AgentWorkspaceAdmissionIntent.CatalogInvoke -> "Refreshing the Catalog"
    is AgentWorkspaceAdmissionIntent.DashboardDelete -> "Deleting a dashboard"
    is AgentWorkspaceAdmissionIntent.DashboardRestore -> "Restoring a dashboard"
    is AgentWorkspaceAdmissionIntent.DashboardCreate -> "Creating a dashboard"
    is AgentWorkspaceAdmissionIntent.DashboardEdit, is AgentWorkspaceAdmissionIntent.DashboardInvoke -> "Changing a dashboard draft"
    is AgentWorkspaceAdmissionIntent.DashboardSessionInvoke -> "Running a dashboard"
    is AgentWorkspaceAdmissionIntent.ExploreRun -> "Running a query"
    is AgentWorkspaceAdmissionIntent.PagesCreate, is AgentWorkspaceAdmissionIntent.PagesEdit,
    is AgentWorkspaceAdmissionIntent.PagesInvoke -> "Changing Pages"
    is AgentWorkspaceAdmissionIntent.ManagementInvoke -> "Managing members"
    is AgentWorkspaceAdmissionIntent.List, is AgentWorkspaceAdmissionIntent.Read,
    is AgentWorkspaceAdmissionIntent.Search -> if (operation == AgentWorkspaceOperation.SEARCH) "Searching" else "Reading this resource"
}
