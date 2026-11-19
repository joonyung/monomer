package cc.monomer.identity.application.organization

import cc.monomer.identity.application.OpaqueCredentials
import cc.monomer.identity.application.AdminAuthorization
import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.domain.id.*
import cc.monomer.identity.domain.model.*
import cc.monomer.identity.domain.port.*
import io.mockk.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrganizationInvitationServiceTest {
    private val now = Instant.parse("2026-09-05T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val organization = OrganizationId.generate()
    private val actor = UserId.generate()
    private val recipient = UserId.generate()
    private val repository = mockk<OrganizationInvitationRepository>(relaxed = true)
    private val organizations = mockk<OrganizationRepository>(relaxed = true)
    private val audit = mockk<AuditLogRepository>(relaxed = true)
    private val workspaceMembers = mockk<WorkspaceMemberRepository>()
    private val delivery = mockk<InvitationDelivery>()
    private val transaction = object : TransactionRunner {
        override suspend fun <T> run(action: () -> T): T = action()
        override suspend fun <T> readOnly(action: () -> T): T = action()
    }
    private val service = OrganizationInvitationService(transaction, repository, organizations, workspaceMembers,
        AdminAuthorization(organizations), audit, AuditEventFactory(clock), delivery, clock)
    private val revenue = WorkspaceId.generate()
    private val removed = WorkspaceId.generate()
    private val raw = OpaqueCredentials.generate()
    private val invitation = OrganizationMembershipInvitation(Uuid.generateV7(), organization, "Invitee@Example.test", "invitee@example.test",
        OpaqueCredentials.hash(raw), actor, now.minusSeconds(120), now.plusSeconds(86400), now.minusSeconds(120), null, emptyList())
    private val preview = InvitationPreview(invitation, "Organization", null, emptyList())
    private val proof = OrganizationInvitationService.Proof(invitation.id, organization, invitation.tokenHash, now.plusSeconds(1800))
    private val browser = UserCredentialProvenance.BrowserSession(recipient, SessionId.generate())

    private fun prepare() {
        every { repository.lockOrganization(organization) } returns true
        every { repository.find(organization, invitation.id) } returns preview
        every { repository.currentAuthoritativeEmail(browser, now) } returns "  INVITEE@example.test "
        every { organizations.lockAndFindMembership(organization, recipient) } returns null
        every { organizations.lockAndFindMembership(organization, actor) } returns OrganizationMembership(organization, actor, OrganizationRole.ADMIN,
            now)
        every { repository.exists(organization) } returns true
    }

    @Test
    fun `accept creates only Member and consumes invitation`() = runBlocking<Unit> {
        prepare()
        assertThat(service.consume(proof, browser, true).outcome).isEqualTo(OrganizationInvitationService.Outcome.ACCEPTED)
        verify(exactly = 1) { organizations.insertMember(match { it.userId == recipient && it.role == OrganizationRole.MEMBER }) }
        verify(exactly = 1) { repository.delete(invitation.id) }
    }

    @Test
    fun `accept grants attached Workspace access in the same transaction and reports skipped access`() = runBlocking<Unit> {
        prepare()
        val grants = listOf(InvitationWorkspaceGrant(revenue, cc.monomer.identity.api.WorkspaceRole.EXPLORER),
            InvitationWorkspaceGrant(removed, cc.monomer.identity.api.WorkspaceRole.OWNER))
        every { repository.find(organization, invitation.id) } returns preview.copy(invitation = invitation.copy(workspaceAccess = grants))
        every { repository.lockWorkspacesInOrganization(organization, setOf(revenue, removed)) } returns mapOf(revenue to "Revenue Analytics")
        every { workspaceMembers.insert(any()) } returns WorkspaceMemberInsertion.Inserted

        val result = service.consume(proof, browser, true)

        assertThat(result.outcome).isEqualTo(OrganizationInvitationService.Outcome.ACCEPTED)
        assertThat(result.acceptedWorkspaceAccess!!.granted).containsExactly(
            InvitationWorkspaceAccess(revenue, "Revenue Analytics", cc.monomer.identity.api.WorkspaceRole.EXPLORER))
        assertThat(result.acceptedWorkspaceAccess.skipped).containsExactly(
            InvitationWorkspaceAccess(removed, null, cc.monomer.identity.api.WorkspaceRole.OWNER))
        verifyOrder {
            organizations.insertMember(match { it.userId == recipient })
            workspaceMembers.insert(WorkspaceMembership(revenue, recipient, actor, now, cc.monomer.identity.api.WorkspaceRole.EXPLORER))
            repository.delete(invitation.id)
        }
        verify(exactly = 1) { workspaceMembers.insert(any()) }
        verify(exactly = 1) { audit.insert(match { it.action == "workspace.member.add" && it.workspaceId == revenue }) }
    }

    @Test
    fun `attached Workspace access lapses when the inviter is no longer an Organization Admin`() = runBlocking<Unit> {
        val grants = listOf(InvitationWorkspaceGrant(revenue, cc.monomer.identity.api.WorkspaceRole.EDITOR))
        val offered = listOf(InvitationWorkspaceAccess(revenue, "Revenue Analytics", cc.monomer.identity.api.WorkspaceRole.EDITOR))
        val inviterStates = listOf<Pair<UserId?, OrganizationMembership?>>(
            actor to OrganizationMembership(organization, actor, OrganizationRole.MEMBER, now),
            actor to null,
            null to null,
        )
        for ((inviter, membership) in inviterStates) {
            clearMocks(organizations, workspaceMembers, repository, answers = false)
            prepare()
            every { organizations.lockAndFindMembership(organization, actor) } returns membership
            every { repository.find(organization, invitation.id) } returns
                InvitationPreview(invitation.copy(invitedByUserId = inviter, workspaceAccess = grants), "Organization", null, offered)

            val result = service.consume(proof, browser, true)

            assertThat(result.outcome).isEqualTo(OrganizationInvitationService.Outcome.ACCEPTED)
            assertThat(result.acceptedWorkspaceAccess!!.granted).isEmpty()
            assertThat(result.acceptedWorkspaceAccess.skipped).containsExactlyElementsOf(offered)
            verify(exactly = 1) { organizations.insertMember(match { it.userId == recipient && it.role == OrganizationRole.MEMBER }) }
            verify(exactly = 0) { workspaceMembers.insert(any()) }
            verify(exactly = 0) { repository.lockWorkspacesInOrganization(any(), any()) }
            verify(exactly = 1) { repository.delete(invitation.id) }
        }
    }

    @Test
    fun `member limit rejects acceptance before any Workspace access is granted`() = runBlocking<Unit> {
        prepare()
        every { repository.find(organization, invitation.id) } returns preview.copy(invitation = invitation.copy(
            workspaceAccess = listOf(InvitationWorkspaceGrant(revenue, cc.monomer.identity.api.WorkspaceRole.VIEWER))))
        every { organizations.insertMember(any()) } throws FreeOrganizationMemberLimitException()

        assertThat(service.consume(proof, browser, true).outcome).isEqualTo(OrganizationInvitationService.Outcome.MEMBER_LIMIT_REACHED)
        verify(exactly = 0) { workspaceMembers.insert(any()) }
        verify(exactly = 0) { repository.delete(any()) }
    }

    @Test
    fun `resolve reports an existing member before acceptance without consuming the invitation`() = runBlocking<Unit> {
        prepare()
        every { organizations.lockAndFindMembership(organization, recipient) } returns
            OrganizationMembership(organization, recipient, OrganizationRole.MEMBER, now)
        assertThat(service.resolve(proof, browser).outcome).isEqualTo(OrganizationInvitationService.Outcome.ALREADY_MEMBER)
        every { organizations.lockAndFindMembership(organization, recipient) } returns null
        assertThat(service.resolve(proof, browser).outcome).isEqualTo(OrganizationInvitationService.Outcome.AVAILABLE)
        every { repository.currentAuthoritativeEmail(browser, now) } returns "other@example.test"
        assertThat(service.resolve(proof, browser).outcome).isEqualTo(OrganizationInvitationService.Outcome.EMAIL_MISMATCH)
        verify(exactly = 0) { repository.delete(any()) }
        verify(exactly = 0) { organizations.insertMember(any()) }
    }

    @Test
    fun `create rejects an address invitation mail cannot reach before storing or sending`() = runBlocking<Unit> {
        prepare()
        for (email in listOf("alice@example..com", "josé@example.com", "alice@-example.com", "alice.@example.com")) {
            assertThat(service.create(actor, organization, email, emptyList(), governanceAudit = null).outcome)
                .`as`(email).isEqualTo(OrganizationInvitationService.Outcome.INVALID_EMAIL)
        }
        verify(exactly = 0) { repository.insert(any()) }
        coVerify(exactly = 0) { delivery.send(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `create stores Workspace access only for Workspaces of the Organization`() = runBlocking<Unit> {
        prepare()
        every { repository.membershipExistsByEmail(organization, "new@example.test") } returns false
        every { repository.findByEmail(organization, "new@example.test") } returns null
        every { repository.lockWorkspacesInOrganization(organization, setOf(revenue, removed)) } returns mapOf(revenue to "Revenue Analytics")
        val outside = listOf(InvitationWorkspaceGrant(revenue, cc.monomer.identity.api.WorkspaceRole.EDITOR),
            InvitationWorkspaceGrant(removed, cc.monomer.identity.api.WorkspaceRole.VIEWER))
        assertThat(service.create(actor, organization, "new@example.test", outside, governanceAudit = null).outcome)
            .isEqualTo(OrganizationInvitationService.Outcome.INVALID_WORKSPACE_ACCESS)
        val duplicate = listOf(InvitationWorkspaceGrant(revenue, cc.monomer.identity.api.WorkspaceRole.EDITOR),
            InvitationWorkspaceGrant(revenue, cc.monomer.identity.api.WorkspaceRole.VIEWER))
        assertThat(service.create(actor, organization, "new@example.test", duplicate, governanceAudit = null).outcome)
            .isEqualTo(OrganizationInvitationService.Outcome.INVALID_WORKSPACE_ACCESS)
        val tooMany = (1..OrganizationInvitationService.MAX_WORKSPACE_ACCESS + 1).map {
            InvitationWorkspaceGrant(WorkspaceId.generate(), cc.monomer.identity.api.WorkspaceRole.VIEWER)
        }
        assertThat(service.create(actor, organization, "new@example.test", tooMany, governanceAudit = null).outcome)
            .isEqualTo(OrganizationInvitationService.Outcome.INVALID_WORKSPACE_ACCESS)
        verify(exactly = 0) { repository.insert(any()) }

        val valid = listOf(InvitationWorkspaceGrant(revenue, cc.monomer.identity.api.WorkspaceRole.EDITOR))
        every { repository.lockWorkspacesInOrganization(organization, setOf(revenue)) } returns mapOf(revenue to "Revenue Analytics")
        every { repository.find(organization, any()) } answers { preview.copy(invitation = invitation.copy(id = secondArg(), workspaceAccess = valid)) }
        coEvery { delivery.send(any(), any(), any(), any(), any()) } returns true
        assertThat(service.create(actor, organization, "new@example.test", valid, governanceAudit = null).outcome)
            .isEqualTo(OrganizationInvitationService.Outcome.SENT)
        verify(exactly = 1) { repository.insert(match { it.workspaceAccess == valid && it.normalizedEmail == "new@example.test" }) }
    }

    @Test
    fun `only an Organization Admin can attach Workspace access to an invitation`() = runBlocking<Unit> {
        prepare()
        every { organizations.lockAndFindMembership(organization, actor) } returns OrganizationMembership(organization, actor, OrganizationRole.MEMBER, now)
        val grants = listOf(InvitationWorkspaceGrant(revenue, cc.monomer.identity.api.WorkspaceRole.OWNER))
        org.assertj.core.api.Assertions.assertThatThrownBy {
            runBlocking { service.create(actor, organization, "new@example.test", grants, governanceAudit = null) }
        }.isInstanceOf(cc.monomer.identity.application.AccessDeniedException::class.java)
        verify(exactly = 0) { repository.lockWorkspacesInOrganization(any(), any()) }
        verify(exactly = 0) { repository.insert(any()) }
    }

    @Test
    fun `existing membership consumes without membership writes`() = runBlocking<Unit> {
        prepare()
        every { organizations.lockAndFindMembership(organization, recipient) } returns
            OrganizationMembership(organization, recipient, OrganizationRole.ADMIN, now)
        assertThat(service.consume(proof, browser, true).outcome).isEqualTo(OrganizationInvitationService.Outcome.ALREADY_MEMBER)
        verify(exactly = 0) { organizations.insertMember(any()) }
        verify(exactly = 1) { repository.delete(invitation.id) }
    }

    @Test
    fun `wrong email revoked session and absent browser cannot accept or decline`() = runBlocking<Unit> {
        prepare()
        every { repository.currentAuthoritativeEmail(browser, now) } returns "other@example.test"
        for (accept in listOf(true, false)) {
            val mismatch = service.consume(proof, browser, accept)
            assertThat(mismatch.outcome).isEqualTo(OrganizationInvitationService.Outcome.EMAIL_MISMATCH)
            assertThat(mismatch.invitation?.invitation?.deliveryEmail).isEqualTo(invitation.deliveryEmail)
        }
        every { repository.currentAuthoritativeEmail(browser, now) } returns null
        assertThat(service.consume(proof, browser, true).outcome).isEqualTo(OrganizationInvitationService.Outcome.AUTHENTICATION_REQUIRED)
        assertThat(service.consume(proof, null, false).outcome).isEqualTo(OrganizationInvitationService.Outcome.AUTHENTICATION_REQUIRED)
        verify(exactly = 0) { organizations.insertMember(any()) }
        verify(exactly = 0) { repository.delete(any()) }
    }

    @Test
    fun `expired invitation expired proof and resend invalidated hash are unavailable`() = runBlocking<Unit> {
        prepare()
        assertThat(service.consume(proof.copy(expiresAt = now), browser, true).outcome).isEqualTo(OrganizationInvitationService.Outcome.UNAVAILABLE)
        every { repository.find(organization, invitation.id) } returns preview.copy(invitation = invitation.copy(expiresAt = now))
        assertThat(service.consume(proof, browser, false).outcome).isEqualTo(OrganizationInvitationService.Outcome.UNAVAILABLE)
        every { repository.find(organization, invitation.id) } returns preview.copy(invitation = invitation.copy(tokenHash = "rotated"))
        assertThat(service.consume(proof, browser, true).outcome).isEqualTo(OrganizationInvitationService.Outcome.UNAVAILABLE)
        verify(exactly = 0) { organizations.insertMember(any()) }
    }

    @Test
    fun `decline consumes invitation without changing membership`() = runBlocking<Unit> {
        prepare()
        assertThat(service.consume(proof, browser, false).outcome).isEqualTo(OrganizationInvitationService.Outcome.DECLINED)
        verify(exactly = 1) { repository.delete(invitation.id) }
        verify(exactly = 0) { organizations.insertMember(any()) }
    }

    @Test
    fun `pending invitation and existing members send no mail`() = runBlocking<Unit> {
        prepare()
        every { repository.membershipExistsByEmail(organization, invitation.normalizedEmail) } returns false
        every { repository.findByEmail(organization, invitation.normalizedEmail) } returns preview
        assertThat(service.create(actor, organization, invitation.deliveryEmail, emptyList(), governanceAudit = null).outcome).isEqualTo(OrganizationInvitationService.Outcome.INVITATION_PENDING)
        every { repository.membershipExistsByEmail(organization, invitation.normalizedEmail) } returns true
        assertThat(service.create(actor, organization, invitation.deliveryEmail, emptyList(), governanceAudit = null).outcome).isEqualTo(OrganizationInvitationService.Outcome.ALREADY_MEMBER)
        coVerify(exactly = 0) { delivery.send(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { repository.insert(any()) }
    }

    @Test
    fun `resend rotates hash resets sent evidence and delivery failure preserves invitation`() = runBlocking<Unit> {
        prepare()
        coEvery { delivery.send(any(), any(), any(), any(), any()) } returns false
        every { repository.replace(any()) } answers {
            val rotated = firstArg<OrganizationMembershipInvitation>()
            every { repository.find(organization, invitation.id) } returns preview.copy(invitation = rotated)
        }
        val result = service.resend(actor, organization, invitation.id, governanceAudit = null)
        assertThat(result.outcome).isEqualTo(OrganizationInvitationService.Outcome.DELIVERY_FAILED)
        verify(exactly = 1) { repository.replace(match { it.tokenHash != invitation.tokenHash && it.lastSentAt == null && it.expiresAt == now.plusSeconds(604800) }) }
        verify(exactly = 0) { repository.delete(any()) }
        verify(exactly = 0) { repository.markSent(any(), any(), any()) }
    }

    @Test
    fun `cooldown and aggregate rate limits reject before rotation or delivery`() = runBlocking<Unit> {
        prepare()
        every { repository.find(organization, invitation.id) } returns preview.copy(invitation = invitation.copy(lastDeliveryAttemptAt = now.minusSeconds(1)))
        assertThat(service.resend(actor, organization, invitation.id, governanceAudit = null).retryAfter).isEqualTo(59)
        every { repository.find(organization, invitation.id) } returns preview
        coEvery { delivery.send(any(), any(), any(), any(), any()) } returns false
        repeat(30) { service.resend(actor, organization, invitation.id, governanceAudit = null) }
        assertThat(service.resend(actor, organization, invitation.id, governanceAudit = null).retryAfter).isEqualTo(3600)
        verify(exactly = 30) { repository.replace(any()) }
        coVerify(exactly = 30) { delivery.send(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `organization rolling day cap combines attempts by different administrators`() = runBlocking<Unit> {
        prepare()
        coEvery { delivery.send(any(), any(), any(), any(), any()) } returns false
        val otherAdmins = listOf(actor, UserId.generate(), UserId.generate())
        otherAdmins.forEach { other ->
            every { organizations.lockAndFindMembership(organization, other) } returns OrganizationMembership(organization, other, OrganizationRole.ADMIN,
                now)
        }
        repeat(50) { service.resend(otherAdmins[it / 20], organization, invitation.id, governanceAudit = null) }
        assertThat(service.resend(otherAdmins.last(), organization, invitation.id, governanceAudit = null).retryAfter).isEqualTo(86400)
        coVerify(exactly = 50) { delivery.send(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `exchange accepts only full entropy token and hides expired consumed invalid links`() = runBlocking<Unit> {
        every { repository.findByHash(invitation.tokenHash) } returns preview
        assertThat(raw).hasSize(43)
        assertThat(invitation.tokenHash).hasSize(64).isNotEqualTo(raw)
        assertThat(service.exchange(raw)?.expectedTokenHash).isEqualTo(invitation.tokenHash)
        assertThat(service.exchange("bad")).isNull()
        every { repository.findByHash(invitation.tokenHash) } returns preview.copy(invitation = invitation.copy(expiresAt = now))
        assertThat(service.exchange(raw)).isNull()
        every { repository.findByHash(invitation.tokenHash) } returns null
        assertThat(service.exchange(raw)).isNull()
    }
}
