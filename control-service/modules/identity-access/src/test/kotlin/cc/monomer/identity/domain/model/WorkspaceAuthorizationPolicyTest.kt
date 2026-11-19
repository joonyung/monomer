package cc.monomer.identity.domain.model

import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.domain.id.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class WorkspaceAuthorizationPolicyTest {
    private val user = UserId.generate()
    private val workspace = WorkspaceId.generate()
    private val organization = OrganizationId.generate()
    private fun facts(role: WorkspaceRole?, member: Boolean = true) = WorkspaceActorAuthorizationFacts(
        user, workspace, organization, AuthorizationRevision(7), if (member) OrganizationRole.MEMBER else null,
        role,
    )
    @Test fun `workspace role is member role and only owner governs`() {
        for (direct in WorkspaceRole.entries) {
            val value = facts(direct)
            val snapshot = (WorkspaceAuthorizationPolicy.authorize(value) as WorkspaceActorAuthorizationDecision.Authorized).snapshot
            assertEquals(direct, snapshot.role)
            assertEquals(direct == WorkspaceRole.OWNER, WorkspaceAuthorizationPolicy.authorizeOwner(value) != null)
        }
        assertIs<WorkspaceActorAuthorizationDecision.Denied>(WorkspaceAuthorizationPolicy.authorize(facts(null)))
    }
    @Test fun `missing workspace denies membership and admin governance`() {
        val missing = facts(null).copy(
            organizationId = null,
            authorizationRevision = null,
            organizationRole = OrganizationRole.ADMIN,
        )
        assertEquals(
            WorkspaceActorAuthorizationDecision.Denied(WorkspaceActorAuthorizationDenial.WORKSPACE_NOT_FOUND),
            WorkspaceAuthorizationPolicy.authorize(missing),
        )
        assertNull(WorkspaceAuthorizationPolicy.authorizeOwner(missing))
        assertNull(WorkspaceAuthorizationPolicy.authorizeOwnerGovernance(missing))
    }
    @Test fun `missing organization membership denies even direct owner`() {
        val missing = facts(WorkspaceRole.OWNER, false)
        assertIs<WorkspaceActorAuthorizationDecision.Denied>(WorkspaceAuthorizationPolicy.authorize(missing))
        assertNull(WorkspaceAuthorizationPolicy.authorizeOwnerGovernance(missing))
    }
    @Test fun `organization admin governance does not admit workspace membership`() {
        val admin = facts(null).copy(organizationRole = OrganizationRole.ADMIN)
        val governance = assertNotNull(WorkspaceAuthorizationPolicy.authorizeOwnerGovernance(admin))
        assertNull(governance.role)
        assertEquals(OrganizationRole.ADMIN, governance.organizationRole)
        assertIs<WorkspaceActorAuthorizationDecision.Denied>(WorkspaceAuthorizationPolicy.authorize(admin))
    }
}
