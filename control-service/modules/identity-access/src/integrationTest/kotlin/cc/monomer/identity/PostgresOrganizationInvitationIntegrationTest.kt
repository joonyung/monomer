package cc.monomer.identity

import cc.monomer.identity.application.OpaqueCredentials
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.application.AdminAuthorization
import cc.monomer.identity.application.organization.OrganizationInvitationService
import cc.monomer.identity.application.organization.RemoveOrganizationMemberService
import cc.monomer.identity.application.organization.RemoveOrganizationMemberUseCase
import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.domain.id.*
import cc.monomer.identity.domain.model.*
import cc.monomer.identity.domain.port.InvitationDelivery
import cc.monomer.identity.outbound.postgres.*
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

class PostgresOrganizationInvitationIntegrationTest {
    private val jdbc get() = NamedParameterJdbcTemplate(TargetIdentityPostgres.dataSource)
    private val now = Instant.parse("2026-09-05T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val organization = OrganizationId.generate()
    private val admin = UserId.generate()
    private val recipient = UserId.generate()
    private val email = "recipient-$recipient@example.test"
    private lateinit var repository: PostgresOrganizationInvitationRepository
    private lateinit var service: OrganizationInvitationService
    private lateinit var transactions: SpringTransactionRunner
    private lateinit var deliveredToken: String

    @BeforeEach
    fun setup() {
        TargetIdentityPostgres.dataSource.connection.use {
            TargetIdentityPostgres.applyCanonicalSchemaTwice(it, javaClass.classLoader)
            AuthSchemaVerifier.verifyContractFacts(it)
        }
        val manager = DataSourceTransactionManager(TargetIdentityPostgres.dataSource)
        transactions = SpringTransactionRunner(TransactionTemplate(manager), TransactionTemplate(manager).apply { isReadOnly = true }, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        repository = PostgresOrganizationInvitationRepository(jdbc)
        val organizations = PostgresOrganizationRepository(jdbc)
        service = OrganizationInvitationService(transactions, repository, organizations, PostgresWorkspaceMemberRepository(jdbc), AdminAuthorization(organizations),
            PostgresAuditLogRepository(jdbc), AuditEventFactory(clock), object : InvitationDelivery {
                override suspend fun send(deliveryEmail: String, organizationDisplayName: String, invitedBy: User?, expiresAt: Instant, rawToken: String): Boolean { deliveredToken = rawToken; return false }
            }, clock)
        val params = mapOf("organization" to organization.value.toJavaUuid(), "admin" to admin.value.toJavaUuid(), "user" to recipient.value.toJavaUuid(),
            "now" to Timestamp.from(now), "email" to email, "adminEmail" to "admin-$admin@example.test")
        runBlocking { transactions.run {
        jdbc.update("INSERT INTO identity_runtime.organizations(id,display_name,created_at) VALUES(:organization,'Invitation Organization',:now)", params)
        jdbc.update("INSERT INTO identity_runtime.principals(id,kind,display_name,created_at) VALUES(:admin,'user','Admin',:now),(:user,'user','Recipient',:now)", params)
        jdbc.update("INSERT INTO identity_runtime.users(id,primary_email,created_at) VALUES(:admin,:adminEmail,:now),(:user,:email,:now)", params)
        jdbc.update("INSERT INTO identity_runtime.organization_members(organization_id,user_id,role,joined_at) VALUES(:organization,:admin,'ADMIN',:now)", params)
        } }
    }

    @Test
    fun `create stores no user or membership and failed delivery retains pending inventory`() = runBlocking<Unit> {
        val targetEmail = "new-$organization@example.test"
        val result = service.create(admin, organization, targetEmail, emptyList(), governanceAudit = null)
        assertThat(result.outcome).isEqualTo(OrganizationInvitationService.Outcome.DELIVERY_FAILED)
        assertThat(service.list(admin, organization)).hasSize(1)
        assertThat(count("SELECT count(*) FROM identity_runtime.users WHERE primary_email = :email", mapOf("email" to targetEmail))).isZero()
        assertThat(count("SELECT count(*) FROM identity_runtime.organization_members WHERE organization_id = :organization", organizationParams())).isEqualTo(1)
        assertThat(result.invitation!!.invitedBy!!.displayName).isEqualTo("Admin")
    }

    @Test
    fun `removed member can receive a fresh invitation and return without former workspace access`() = runBlocking<Unit> {
        val credential = session(email, "oidc", "builtin/google", "authoritative", true, false)
        assertThat(service.consume(invitation(email), credential, true).outcome).isEqualTo(OrganizationInvitationService.Outcome.ACCEPTED)
        val workspace = WorkspaceId.generate()
        transactions.run {
            val parameters = organizationParams() + mapOf("workspace" to workspace.value.toJavaUuid(), "admin" to admin.value.toJavaUuid())
            jdbc.update("INSERT INTO identity_runtime.workspaces(id,organization_id,display_name) VALUES(:workspace,:organization,'Previous Workspace')", parameters)
            jdbc.update("INSERT INTO identity_runtime.workspace_members(workspace_id,user_id,role) VALUES(:workspace,:admin,'OWNER'),(:workspace,:user,'EXPLORER')", parameters)
        }
        val organizations = PostgresOrganizationRepository(jdbc)
        val remove = RemoveOrganizationMemberService(transactions, AdminAuthorization(organizations),
            organizations, PostgresAuditLogRepository(jdbc), AuditEventFactory(clock))
        assertThat(remove.remove(RemoveOrganizationMemberUseCase.Command(admin.asPrincipal(), organization, recipient)))
            .isEqualTo(RemoveOrganizationMemberUseCase.Result.Removed)
        assertThat(count("SELECT count(*) FROM identity_runtime.organization_members WHERE organization_id=:organization AND user_id=:user", organizationParams())).isZero()
        assertThat(count("SELECT count(*) FROM identity_runtime.workspace_members WHERE user_id=:user", organizationParams())).isZero()
        val reinvitation = service.create(admin, organization, email, emptyList(), governanceAudit = null)
        assertThat(reinvitation.outcome).isEqualTo(OrganizationInvitationService.Outcome.DELIVERY_FAILED)
        assertThat(service.consume(checkNotNull(service.exchange(deliveredToken)), credential, true).outcome)
            .isEqualTo(OrganizationInvitationService.Outcome.ACCEPTED)
        assertMemberOnly()
        assertThat(count("SELECT count(*) FROM identity_runtime.audit_log WHERE organization_id=:organization AND resource_id=:user AND action='organization.member.remove'", organizationParams())).isEqualTo(1)
    }

    @Test
    fun `accept creates attached Workspace access atomically and skips a Workspace no longer in the Organization`() = runBlocking<Unit> {
        val revenue = WorkspaceId.generate()
        val removed = WorkspaceId.generate()
        val foreignOrganization = OrganizationId.generate()
        val foreign = WorkspaceId.generate()
        transactions.run {
            val parameters = organizationParams() + mapOf("revenue" to revenue.value.toJavaUuid(), "removed" to removed.value.toJavaUuid(),
                "admin" to admin.value.toJavaUuid(), "foreignOrganization" to foreignOrganization.value.toJavaUuid(), "foreign" to foreign.value.toJavaUuid())
            jdbc.update("INSERT INTO identity_runtime.workspaces(id,organization_id,display_name) VALUES(:revenue,:organization,'Revenue Analytics')", parameters)
            jdbc.update("INSERT INTO identity_runtime.workspace_members(workspace_id,user_id,role) VALUES(:revenue,:admin,'OWNER')", parameters)
            jdbc.update("INSERT INTO identity_runtime.organizations(id,display_name) VALUES(:foreignOrganization,'Other')", parameters)
            jdbc.update("INSERT INTO identity_runtime.organization_members(organization_id,user_id,role) VALUES(:foreignOrganization,:admin,'ADMIN')", parameters)
            jdbc.update("INSERT INTO identity_runtime.workspaces(id,organization_id,display_name) VALUES(:foreign,:foreignOrganization,'Foreign')", parameters)
            jdbc.update("INSERT INTO identity_runtime.workspace_members(workspace_id,user_id,role) VALUES(:foreign,:admin,'OWNER')", parameters)
        }
        val foreignGrant = listOf(InvitationWorkspaceGrant(foreign, WorkspaceRole.VIEWER))
        assertThat(service.create(admin, organization, "foreign-$organization@example.test", foreignGrant, governanceAudit = null).outcome)
            .isEqualTo(OrganizationInvitationService.Outcome.INVALID_WORKSPACE_ACCESS)
        val grants = listOf(InvitationWorkspaceGrant(revenue, WorkspaceRole.EXPLORER))
        assertThat(service.create(admin, organization, email, grants, governanceAudit = null).outcome)
            .isEqualTo(OrganizationInvitationService.Outcome.DELIVERY_FAILED)
        val stored = service.list(admin, organization).single()
        assertThat(stored.workspaceAccess.map { it.workspaceDisplayName }).containsExactly("Revenue Analytics")
        transactions.run {
            jdbc.update("INSERT INTO identity_runtime.organization_membership_invitation_workspaces(invitation_id,workspace_id,role) VALUES(:invitation,:removed,'EDITOR')",
                mapOf("invitation" to stored.invitation.id.toJavaUuid(), "removed" to removed.value.toJavaUuid()))
        }
        val proof = checkNotNull(service.exchange(deliveredToken))
        val credential = session(email, "oidc", "builtin/google", "authoritative", true, false)
        assertThat(service.resolve(proof, credential).invitation!!.workspaceAccess.map { it.workspaceDisplayName })
            .containsExactly("Revenue Analytics", null)

        val accepted = service.consume(proof, credential, true)

        assertThat(accepted.outcome).isEqualTo(OrganizationInvitationService.Outcome.ACCEPTED)
        assertThat(accepted.acceptedWorkspaceAccess!!.granted.map { it.workspaceId }).containsExactly(revenue)
        assertThat(accepted.acceptedWorkspaceAccess!!.skipped.map { it.workspaceId }).containsExactly(removed)
        assertThat(jdbc.queryForObject("SELECT role FROM identity_runtime.workspace_members WHERE workspace_id=:workspace AND user_id=:user",
            organizationParams() + ("workspace" to revenue.value.toJavaUuid()), String::class.java)).isEqualTo("EXPLORER")
        assertThat(count("SELECT count(*) FROM identity_runtime.workspace_members WHERE user_id=:user", organizationParams())).isEqualTo(1)
        assertThat(count("SELECT count(*) FROM identity_runtime.organization_membership_invitation_workspaces", emptyMap())).isZero()
        assertThat(count("SELECT count(*) FROM identity_runtime.audit_log WHERE action='workspace.member.add' AND resource_id=:user", organizationParams())).isEqualTo(1)
    }

    @Test
    fun `attached Workspace access is skipped when the inviter was demoted or removed`() = runBlocking<Unit> {
        val revenue = WorkspaceId.generate()
        val inviter = UserId.generate()
        transactions.run {
            val parameters = organizationParams() + mapOf("revenue" to revenue.value.toJavaUuid(), "admin" to admin.value.toJavaUuid(),
                "inviter" to inviter.value.toJavaUuid(), "inviterEmail" to "inviter-$inviter@example.test", "now" to Timestamp.from(now))
            jdbc.update("INSERT INTO identity_runtime.workspaces(id,organization_id,display_name) VALUES(:revenue,:organization,'Revenue Analytics')", parameters)
            jdbc.update("INSERT INTO identity_runtime.workspace_members(workspace_id,user_id,role) VALUES(:revenue,:admin,'OWNER')", parameters)
            jdbc.update("INSERT INTO identity_runtime.principals(id,kind,display_name,created_at) VALUES(:inviter,'user','Inviter',:now)", parameters)
            jdbc.update("INSERT INTO identity_runtime.users(id,primary_email,created_at) VALUES(:inviter,:inviterEmail,:now)", parameters)
            jdbc.update("INSERT INTO identity_runtime.organization_members(organization_id,user_id,role,joined_at) VALUES(:organization,:inviter,'ADMIN',:now)", parameters)
        }
        val organizations = PostgresOrganizationRepository(jdbc)
        val grants = listOf(InvitationWorkspaceGrant(revenue, WorkspaceRole.EXPLORER))
        for (change in listOf("demote", "remove")) {
            assertThat(service.create(inviter, organization, email, grants, governanceAudit = null).outcome)
                .isEqualTo(OrganizationInvitationService.Outcome.DELIVERY_FAILED)
            val proof = checkNotNull(service.exchange(deliveredToken))
            transactions.run {
                if (change == "demote") organizations.updateMemberRole(organization, inviter, OrganizationRole.MEMBER)
                else organizations.deleteMember(organization, inviter)
            }
            val credential = session(email, "oidc", "builtin/google", "authoritative", true, false)

            val accepted = service.consume(proof, credential, true)

            assertThat(accepted.outcome).isEqualTo(OrganizationInvitationService.Outcome.ACCEPTED)
            assertThat(accepted.acceptedWorkspaceAccess!!.granted).isEmpty()
            assertThat(accepted.acceptedWorkspaceAccess!!.skipped.map { it.workspaceId to it.workspaceDisplayName }).containsExactly(revenue to "Revenue Analytics")
            assertMemberOnly()
            if (change == "demote") transactions.run {
                organizations.deleteMember(organization, recipient)
                organizations.updateMemberRole(organization, inviter, OrganizationRole.ADMIN)
            }
        }
    }

    @Test
    fun `member limit keeps the invitation and its Workspace access without partial grants`() = runBlocking<Unit> {
        val revenue = WorkspaceId.generate()
        transactions.run {
            val parameters = organizationParams() + mapOf("revenue" to revenue.value.toJavaUuid(), "admin" to admin.value.toJavaUuid())
            jdbc.update("INSERT INTO identity_runtime.workspaces(id,organization_id,display_name) VALUES(:revenue,:organization,'Revenue Analytics')", parameters)
            jdbc.update("INSERT INTO identity_runtime.workspace_members(workspace_id,user_id,role) VALUES(:revenue,:admin,'OWNER')", parameters)
        }
        (0 until 29).forEach {
            val user = UserId.generate()
            val parameters = mapOf("id" to user.value.toJavaUuid(), "email" to "$user@example.test", "now" to Timestamp.from(now), "organization" to organization.value.toJavaUuid())
            jdbc.update("INSERT INTO identity_runtime.principals(id,kind,display_name,created_at) VALUES(:id,'user','Member',:now)", parameters)
            jdbc.update("INSERT INTO identity_runtime.users(id,primary_email,created_at) VALUES(:id,:email,:now)", parameters)
            jdbc.update("INSERT INTO identity_runtime.organization_members(organization_id,user_id,role,joined_at) VALUES(:organization,:id,'MEMBER',:now)", parameters)
        }
        service.create(admin, organization, email, listOf(InvitationWorkspaceGrant(revenue, WorkspaceRole.VIEWER)), governanceAudit = null)
        val proof = checkNotNull(service.exchange(deliveredToken))
        val credential = session(email, "oidc", "builtin/google", "authoritative", true, false)
        assertThat(service.consume(proof, credential, true).outcome).isEqualTo(OrganizationInvitationService.Outcome.MEMBER_LIMIT_REACHED)
        assertThat(count("SELECT count(*) FROM identity_runtime.workspace_members WHERE user_id=:user", organizationParams())).isZero()
        assertThat(count("SELECT count(*) FROM identity_runtime.organization_membership_invitation_workspaces", emptyMap())).isEqualTo(1)
    }

    @Test
    fun `same link concurrent accept creates one Member and replays unavailable`() = runBlocking<Unit> {
        val proof = invitation(email)
        val session = session(email, "oidc", "builtin/google", "authoritative", true, false)
        val outcomes = coroutineScope { listOf(async { service.consume(proof, session, true).outcome }, async { service.consume(proof, session, true).outcome }).map { it.await() } }
        assertThat(outcomes).containsExactlyInAnyOrder(OrganizationInvitationService.Outcome.ACCEPTED, OrganizationInvitationService.Outcome.UNAVAILABLE)
        assertMemberOnly()
    }

    @Test
    fun `two invitation emails resolving same user serialize membership admission`() = runBlocking<Unit> {
        val alias = "alias-$organization@example.test"
        val first = invitation(email)
        val second = invitation(alias)
        val firstSession = session(email, "oidc", "builtin/google", "authoritative", true, false)
        val secondSession = session(alias, "oidc", "builtin/google", "authoritative", true, false)
        val outcomes = coroutineScope { listOf(async { service.consume(first, firstSession, true).outcome }, async { service.consume(second, secondSession, true).outcome }).map { it.await() } }
        assertThat(outcomes).containsExactlyInAnyOrder(OrganizationInvitationService.Outcome.ACCEPTED, OrganizationInvitationService.Outcome.ALREADY_MEMBER)
        assertMemberOnly()
    }

    @Test
    fun `session authority must be current verified Google provenance`() = runBlocking<Unit> {
        val proof = invitation(email)
        val invalid = listOf(
            session(email, "development", "builtin/google", "authoritative", true, false),
            session(email, "oidc", "other/provider", "authoritative", true, false),
            session(email, "oidc", "builtin/google", "challenge_verified", true, false),
            session(email, "oidc", "builtin/google", "non_authoritative", true, false),
            session(email, "oidc", "builtin/google", "authoritative", false, false),
            session(email, "oidc", "builtin/google", "authoritative", true, true),
        )
        for (credential in invalid) assertThat(service.consume(proof, credential, true).outcome).isEqualTo(OrganizationInvitationService.Outcome.AUTHENTICATION_REQUIRED)
        val wrong = session("wrong@example.test", "oidc", "builtin/google", "authoritative", true, false)
        val mismatch = service.resolve(proof, wrong)
        assertThat(mismatch.outcome).isEqualTo(OrganizationInvitationService.Outcome.EMAIL_MISMATCH)
        assertThat(mismatch.invitation?.invitation?.deliveryEmail).isEqualTo(email)
        val rejectedDecline = service.consume(proof, wrong, false)
        assertThat(rejectedDecline.outcome).isEqualTo(OrganizationInvitationService.Outcome.EMAIL_MISMATCH)
        assertThat(rejectedDecline.invitation?.invitation?.deliveryEmail).isEqualTo(email)
        assertThat(count("SELECT count(*) FROM identity_runtime.organization_members WHERE organization_id = :organization AND user_id = :user", organizationParams())).isZero()
        assertThat(count("SELECT count(*) FROM identity_runtime.organization_membership_invitations WHERE organization_id = :organization", organizationParams())).isEqualTo(1)
        val good = session(email.uppercase(), "oidc", "builtin/google", "authoritative", true, false)
        assertThat(service.consume(proof, good, true).outcome).isEqualTo(OrganizationInvitationService.Outcome.ACCEPTED)
    }

    @Test
    fun `rotated hash cannot accept and delayed prior send acknowledgement is ignored`() = runBlocking<Unit> {
        val proof = invitation(email)
        val result = service.resend(admin, organization, proof.invitationId, governanceAudit = null)
        assertThat(result.outcome).isEqualTo(OrganizationInvitationService.Outcome.DELIVERY_FAILED)
        transactions.run { repository.markSent(proof.invitationId, proof.expectedTokenHash, now) }
        assertThat(service.list(admin, organization).single().invitation.lastSentAt).isNull()
        assertThat(service.consume(proof, session(email, "oidc", "builtin/google", "authoritative", true, false), true).outcome)
            .isEqualTo(OrganizationInvitationService.Outcome.UNAVAILABLE)
    }

    @Test
    fun `different users compete for final seat and losing valid invitation can retry`() = runBlocking<Unit> {
        val secondRecipient = UserId.generate()
        val secondEmail = "$secondRecipient@example.test"
        val additionalUsers = (0 until 28).map { UserId.generate() } + secondRecipient
        additionalUsers.forEach { user ->
            val parameters = mapOf("id" to user.value.toJavaUuid(), "email" to "$user@example.test", "now" to Timestamp.from(now))
            jdbc.update("INSERT INTO identity_runtime.principals(id,kind,display_name,created_at) VALUES(:id,'user','Recipient',:now)", parameters)
            jdbc.update("INSERT INTO identity_runtime.users(id,primary_email,created_at) VALUES(:id,:email,:now)", parameters)
            if (user != secondRecipient) jdbc.update("""INSERT INTO identity_runtime.organization_members(organization_id,user_id,role,joined_at)
                VALUES(:organization,:id,'MEMBER',:now)""", parameters + ("organization" to organization.value.toJavaUuid()))
        }
        val proofs = listOf(invitation(email), invitation(secondEmail))
        val sessions = listOf(session(email, "oidc", "builtin/google", "authoritative", true, false),
            session(secondEmail, "oidc", "builtin/google", "authoritative", true, false, secondRecipient))
        val results = coroutineScope { proofs.indices.map { index -> async { invitationsConsume(proofs[index], sessions[index]) } }.map { it.await() } }
        assertThat(results).containsExactlyInAnyOrder(OrganizationInvitationService.Outcome.ACCEPTED, OrganizationInvitationService.Outcome.MEMBER_LIMIT_REACHED)
        assertThat(count("SELECT count(*) FROM identity_runtime.organization_members WHERE organization_id = :organization", organizationParams())).isEqualTo(30)
        assertThat(count("SELECT count(*) FROM identity_runtime.organization_membership_invitations WHERE organization_id = :organization", organizationParams())).isEqualTo(1)
        val loser = results.indexOf(OrganizationInvitationService.Outcome.MEMBER_LIMIT_REACHED)
        transactions.run { PostgresOrganizationRepository(jdbc).deleteMember(organization, additionalUsers.first()) }
        assertThat(service.consume(proofs[loser], sessions[loser], true).outcome).isEqualTo(OrganizationInvitationService.Outcome.ACCEPTED)
        assertThat(count("SELECT count(*) FROM identity_runtime.organization_membership_invitations WHERE organization_id = :organization", organizationParams())).isZero()
    }

    private suspend fun invitationsConsume(proof: OrganizationInvitationService.Proof, session: UserCredentialProvenance.BrowserSession) =
        service.consume(proof, session, true).outcome

    private suspend fun invitation(targetEmail: String): OrganizationInvitationService.Proof {
        val raw = OpaqueCredentials.generate()
        val invitation = OrganizationMembershipInvitation(Uuid.generateV7(), organization, targetEmail, targetEmail.lowercase(), OpaqueCredentials.hash(raw),
            admin, now.minusSeconds(120), now.plusSeconds(604800), now.minusSeconds(120), null, emptyList())
        transactions.run { repository.lockOrganization(organization); repository.insert(invitation) }
        return checkNotNull(service.exchange(raw))
    }

    private fun session(assertedEmail: String, protocol: String, source: String, evidence: String, verified: Boolean, revoked: Boolean, sessionUser: UserId = recipient): UserCredentialProvenance.BrowserSession {
        val identity = Uuid.generateV7()
        val session = SessionId.generate()
        val params = mapOf("identity" to identity.toJavaUuid(), "session" to session.value.toJavaUuid(), "user" to sessionUser.value.toJavaUuid(),
            "now" to Timestamp.from(now), "expires" to Timestamp.from(now.plusSeconds(3600)), "email" to assertedEmail,
            "protocol" to protocol, "source" to source, "evidence" to evidence, "verified" to verified,
            "revoked" to if (revoked) Timestamp.from(now) else null, "reason" to if (revoked) "logout" else null, "subject" to identity.toString())
        jdbc.update("""INSERT INTO identity_runtime.login_identities(id,user_id,protocol,namespace,subject,linked_via,linked_at)
            VALUES(:identity,:user,:protocol,'https://accounts.google.com',:subject,'signup',:now)""", params)
        jdbc.update("""INSERT INTO identity_runtime.sessions(id,user_id,login_identity_id,protocol,authentication_source_ref,authentication_issuer,
            asserted_email,asserted_email_verified,email_evidence,created_at,last_refreshed_at,absolute_expires_at,revoked_at,revocation_reason)
            VALUES(:session,:user,:identity,:protocol,:source,'https://accounts.google.com',:email,:verified,:evidence,:now,:now,:expires,:revoked,:reason)""", params)
        return UserCredentialProvenance.BrowserSession(sessionUser, session)
    }
    private fun assertMemberOnly() {
        assertThat(jdbc.queryForObject("SELECT role FROM identity_runtime.organization_members WHERE organization_id = :organization AND user_id = :user", organizationParams(), String::class.java)).isEqualTo("MEMBER")
        assertThat(count("SELECT count(*) FROM identity_runtime.workspace_members WHERE user_id = :user", organizationParams())).isZero()
        assertThat(count("SELECT count(*) FROM identity_runtime.agent_oauth_grants WHERE owner_user_id = :user", organizationParams())).isZero()
        assertThat(count("SELECT count(*) FROM identity_runtime.organization_membership_invitations WHERE organization_id = :organization", organizationParams())).isZero()
    }
    private fun organizationParams(): Map<String, Any> = mapOf("organization" to organization.value.toJavaUuid(), "user" to recipient.value.toJavaUuid())
    private fun count(sql: String, params: Map<String, Any>) = jdbc.queryForObject(sql, params, Int::class.java)!!
    companion object {
        @JvmField @RegisterExtension internal val postgres = TargetIdentityPostgres
    }
}
