package cc.monomer.agent.api

import cc.monomer.agent.api.AgentPathPatterns.ANY_VERSION_UUID

object AgentManagementPaths {
    val organizationCollection = Regex("^organization/(?:members|invitations|workspaces(?:/$ANY_VERSION_UUID/members)?)$")
    val organizationWorkspaceMember = Regex("^organization/workspaces/$ANY_VERSION_UUID/members/$ANY_VERSION_UUID$")
    val organizationMember = Regex("^organization/members/$ANY_VERSION_UUID$")
    val invitation = Regex("^organization/invitations/$ANY_VERSION_UUID$")
    val workspaceCollection = Regex("^workspace/(?:members|member-candidates|lineage/(?:relations|semantic-models|metrics))$")
    val workspaceMember = Regex("^workspace/members/$ANY_VERSION_UUID$")
    val memberCandidate = Regex("^workspace/member-candidates/$ANY_VERSION_UUID$")
    val lineageResource = Regex("^workspace/lineage/(?:relations|semantic-models|metrics)/$ANY_VERSION_UUID$")
    fun organization(path: String): Boolean = path == "organization" || organizationCollection.matches(path) || organizationWorkspaceMember.matches(path) || organizationMember.matches(path) || invitation.matches(path)
    fun workspace(path: String): Boolean = workspaceCollection.matches(path) || workspaceMember.matches(path) || memberCandidate.matches(path) || lineageResource.matches(path) || path in setOf("workspace/access", "workspace/authorization")
    fun readable(path: String): Boolean = path == "organization" || organizationWorkspaceMember.matches(path) || organizationMember.matches(path) || invitation.matches(path) || workspaceMember.matches(path) || memberCandidate.matches(path) || lineageResource.matches(path) || path in setOf("workspace/access", "workspace/authorization")
    fun invocation(path: String, action: String, inputKind: String?): Boolean = when {
        organizationWorkspaceMember.matches(path) -> (action == "remove-member" && inputKind == null) || (action == "set-role" && inputKind == "WORKSPACE_MEMBER_ROLE")
        invitation.matches(path) -> action in setOf("resend", "cancel") && inputKind == null
        path == "workspace/members" -> action == "add-member" && inputKind == "WORKSPACE_MEMBER_ADD"
        workspaceMember.matches(path) -> (action == "remove-member" && inputKind == null) || (action == "set-role" && inputKind == "WORKSPACE_MEMBER_ROLE")
        else -> false
    }
    fun invocation(request: AgentInvokeRequest): Boolean = invocation(request.path.value, request.action, when(request.input) {
        is AgentInvokeInput.WorkspaceMemberAdd -> "WORKSPACE_MEMBER_ADD"
        is AgentInvokeInput.WorkspaceMemberRole -> "WORKSPACE_MEMBER_ROLE"
        AgentInvokeInput.None -> null
        else -> return false
    })
}
