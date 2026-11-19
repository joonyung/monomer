package cc.monomer.identity.application.workspace

import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.WorkspaceMembership
import cc.monomer.identity.domain.port.WorkspaceMemberInsertion
import cc.monomer.identity.domain.port.WorkspaceMemberRepository
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkspaceAuthorizationBootstrapTest {
    @Test
    fun `bootstrap creates only the nominated direct owner`() {
        val workspaceId = WorkspaceId.generate()
        val creatorId = UserId.generate()
        val ownerId = UserId.generate()
        val now = Instant.parse("2026-07-27T01:00:00Z")
        val memberships = mutableListOf<WorkspaceMembership>()
        val memberRepository = mockk<WorkspaceMemberRepository>()
        every { memberRepository.insert(capture(memberships)) } returns WorkspaceMemberInsertion.Inserted
        WorkspaceAuthorizationBootstrap(
            memberRepository,
        ).createInitialBundle(workspaceId, ownerId, creatorId, now)

        assertThat(memberships.single().userId).isEqualTo(ownerId)
        assertThat(memberships.single().role).isEqualTo(cc.monomer.identity.api.WorkspaceRole.OWNER)
        assertThat(memberships.single().invitedBy).isEqualTo(creatorId)
        assertThat(memberships).hasSize(1)
    }
}
