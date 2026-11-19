@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.auth.inbound.graphql

import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.application.grant.WorkspaceAuthorizationAssignments
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.AuthorizationRevision
import cc.monomer.identity.domain.model.User
import cc.monomer.identity.domain.model.WorkspaceMember
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkspaceAuthorizationAssignmentsViewTest {
    @Test
    fun `owner authorization view exposes canonical members and revision`() {
        val user = User(UserId.generate(), "grace@example.com", "Grace Editor")
        val assignments = WorkspaceAuthorizationAssignments(
            authorizationRevision = AuthorizationRevision(8),
            members = listOf(WorkspaceMember(user, WorkspaceRole.EDITOR)),
        )

        val view = WorkspaceAuthorizationAssignmentsView.from(assignments)

        assertThat(view.authorizationRevision).isEqualTo("8")
        assertThat(view.members).containsExactly(WorkspaceMemberView.from(assignments.members.single()))
        assertThat(checkNotNull(view.members.single().user).email).isEqualTo(user.primaryEmail)
    }
}
