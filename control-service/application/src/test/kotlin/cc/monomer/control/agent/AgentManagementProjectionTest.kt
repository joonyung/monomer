package cc.monomer.control.agent

import cc.monomer.control.publicapi.AgentManagementJson
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.application.grant.WorkspaceAuthorizationAssignments
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.AuthorizationRevision
import cc.monomer.identity.domain.model.User
import cc.monomer.identity.domain.model.WorkspaceMember
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AgentManagementProjectionTest {
    @Test
    fun `authorization projects canonical member identity role and revision without inventory`() {
        val user = User(UserId.generate(), "member@example.test", "Organization Member")
        val projection = WorkspaceAuthorizationAssignments(AuthorizationRevision(12), listOf(WorkspaceMember(user, WorkspaceRole.EDITOR))).managementBody()

        val member = projection.members.single()
        assertThat(member.userId).isEqualTo(user.userId.value)
        assertThat(member.displayName).isEqualTo(user.displayName)
        assertThat(member.email).isEqualTo(user.primaryEmail)
        assertThat(member.role).isEqualTo(WorkspaceRole.EDITOR)
        assertThat(projection.authorizationRevision.value).isEqualTo(12)
        assertThat(AgentManagementJson.json(projection).keys).containsExactly("authorizationRevision", "members")
    }
}
