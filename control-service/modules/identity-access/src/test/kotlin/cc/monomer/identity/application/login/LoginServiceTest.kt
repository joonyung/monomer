package cc.monomer.identity.application.login

import cc.monomer.identity.domain.model.DeveloperAccessDeniedException

import cc.monomer.identity.domain.model.DeveloperAccessPolicy

import cc.monomer.identity.application.workspace.WorkspaceAuthorizationBootstrap
import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.application.identity.EmailChallengeRequiredException
import cc.monomer.identity.application.identity.IdentityLinkRequiredException
import cc.monomer.identity.application.identity.LoginIdentityResolver
import cc.monomer.identity.application.identity.NormalizedEmailUserResolver
import cc.monomer.identity.domain.id.LoginIdentityId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.Organization
import cc.monomer.identity.domain.model.AuthenticationAssertion
import cc.monomer.identity.domain.model.AuthenticationProtocol
import cc.monomer.identity.domain.model.EmailEvidence
import cc.monomer.identity.domain.model.LoginIdentity
import cc.monomer.identity.domain.model.LoginIdentityKey
import cc.monomer.identity.domain.model.LoginIdentityLinkedVia
import cc.monomer.identity.domain.model.User
import cc.monomer.identity.domain.model.Workspace
import cc.monomer.identity.domain.port.OrganizationRepository
import cc.monomer.identity.domain.port.AuditLogRepository
import cc.monomer.identity.domain.port.LoginIdentityRepository
import cc.monomer.identity.domain.port.TransactionRunner
import cc.monomer.identity.domain.port.UserRepository
import cc.monomer.identity.domain.port.WorkspaceRepository
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LoginServiceTest {
    private val now = Instant.parse("2026-07-11T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val identityRepository = mockk<LoginIdentityRepository>(relaxed = true)
    private val organizationRepository = mockk<OrganizationRepository>(relaxed = true)
    private val workspaceRepository = mockk<WorkspaceRepository>(relaxed = true)
    private val workspaceAuthorizationBootstrap = mockk<WorkspaceAuthorizationBootstrap>(relaxed = true)
    private val auditLogRepository = mockk<AuditLogRepository>(relaxed = true)
    private val transactionRunner = object : TransactionRunner {
        override suspend fun <T> run(action: () -> T): T = action()
        override suspend fun <T> readOnly(action: () -> T): T = action()
    }

    @Test
    fun `denied new developer login performs no identity or initial provisioning writes`() = runTest {
        val policy = DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.RESTRICTED, setOf("allowed@gmail.com"))
        val allowed = googleAssertion("allowed@gmail.com", EmailEvidence.AUTHORITATIVE)
        listOf(
            googleAssertion("denied@gmail.com", EmailEvidence.AUTHORITATIVE),
            allowed.copy(assertedEmailVerified = false),
            allowed.copy(emailEvidence = EmailEvidence.NON_AUTHORITATIVE),
            allowed.copy(identityKey = allowed.identityKey.copy(protocol = AuthenticationProtocol.DEVELOPMENT)),
        ).forEach { assertion ->
            assertThatThrownBy { kotlinx.coroutines.runBlocking { service(policy).login(assertion, LoginUseCase.FirstSignIn.CREATE_PERSONAL_ORGANIZATION) } }
                .isInstanceOf(DeveloperAccessDeniedException::class.java)
        }
        verify { listOf(userRepository, identityRepository, organizationRepository, workspaceRepository, workspaceAuthorizationBootstrap, auditLogRepository) wasNot Called }
    }

    @Test
    fun `existing identity is authoritative even when assertion email is non-authoritative and changed`() = runTest {
        val user = User(UserId.generate(), "original@example.com", "Alice")
        val identity = identity(user.userId, googleAssertion("changed@external.test", EmailEvidence.NON_AUTHORITATIVE))
        every { identityRepository.find(identity.key) } returns identity
        every { userRepository.findByIdForUpdate(user.userId) } returns user

        val result = service().login(googleAssertion("changed@external.test", EmailEvidence.NON_AUTHORITATIVE), LoginUseCase.FirstSignIn.CREATE_PERSONAL_ORGANIZATION)

        assertThat(result.user).isEqualTo(user)
        assertThat(result.loginIdentity).isEqualTo(identity)
        assertThat(result.newUser).isFalse()
        verify { listOf(organizationRepository, workspaceRepository, workspaceAuthorizationBootstrap, auditLogRepository) wasNot Called }
    }

    @Test
    fun `non-authoritative first Google login requires email challenge without creating user`() = runTest {
        val assertion = googleAssertion("alice@external.test", EmailEvidence.NON_AUTHORITATIVE)
        every { identityRepository.find(assertion.identityKey) } returns null

        assertThatThrownBy { kotlinx.coroutines.runBlocking { service().login(assertion, LoginUseCase.FirstSignIn.CREATE_PERSONAL_ORGANIZATION) } }
            .isInstanceOf(EmailChallengeRequiredException::class.java)
        verify(exactly = 0) { userRepository.insertIfPrimaryEmailAbsent(any()) }
    }

    @Test
    fun `new authoritative identity creates User Organization and default Workspace`() = runTest {
        val assertion = googleAssertion("alice@gmail.com", EmailEvidence.AUTHORITATIVE)
        every { identityRepository.find(assertion.identityKey) } returns null
        every { userRepository.findByPrimaryEmailForUpdate("alice@gmail.com") } returns null
        every { userRepository.insertIfPrimaryEmailAbsent(any()) } returns true
        every { identityRepository.insertIfAbsent(any()) } returns true

        val result = service(DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.RESTRICTED, setOf("alice@gmail.com"))).login(assertion, LoginUseCase.FirstSignIn.CREATE_PERSONAL_ORGANIZATION)

        assertThat(result.newUser).isTrue()
        assertThat(result.user.primaryEmail).isEqualTo("alice@gmail.com")
        assertThat(result.loginIdentity.linkedVia).isEqualTo(LoginIdentityLinkedVia.SIGNUP)
        verify(exactly = 1) { organizationRepository.insert(match { it.displayName == "Alice's organization" }) }
        verify(exactly = 1) { workspaceRepository.insert(any()) }
        verify(exactly = 1) {
            workspaceAuthorizationBootstrap.createInitialBundle(
                any(),
                result.user.userId,
                result.user.userId,
                now,
            )
        }
        verify(exactly = 1) {
            auditLogRepository.insert(match {
                it.actorId == result.user.userId.asPrincipal() && it.action == "organization.create" &&
                    it.organizationId != null && it.workspaceId == null &&
                    it.resourceType == "organization" && it.resourceId == it.organizationId.value &&
                    it.occurredAt == now
            })
        }
        verify(exactly = 1) {
            auditLogRepository.insert(match {
                it.actorId == result.user.userId.asPrincipal() && it.action == "workspace.create" &&
                    it.organizationId != null && it.workspaceId != null &&
                    it.resourceType == "workspace" && it.resourceId == it.workspaceId.value &&
                    it.occurredAt == now
            })
        }
    }

    @Test
    fun `new user arriving through an invitation gets no personal Organization or Workspace`() = runTest {
        val assertion = googleAssertion("invitee@gmail.com", EmailEvidence.AUTHORITATIVE)
        every { identityRepository.find(assertion.identityKey) } returns null
        every { userRepository.findByPrimaryEmailForUpdate("invitee@gmail.com") } returns null
        every { userRepository.insertIfPrimaryEmailAbsent(any()) } returns true
        every { identityRepository.insertIfAbsent(any()) } returns true

        val result = service().login(assertion, LoginUseCase.FirstSignIn.JOIN_BY_INVITATION)

        assertThat(result.newUser).isTrue()
        assertThat(result.loginIdentity.linkedVia).isEqualTo(LoginIdentityLinkedVia.SIGNUP)
        verify(exactly = 0) { organizationRepository.insert(any()) }
        verify(exactly = 0) { organizationRepository.insertMember(any()) }
        verify(exactly = 0) { workspaceRepository.insert(any()) }
        verify(exactly = 0) { workspaceAuthorizationBootstrap.createInitialBundle(any(), any(), any(), any()) }
        verify { auditLogRepository wasNot Called }
    }

    @Test
    fun `existing user signing in through an invitation keeps the existing login behavior`() = runTest {
        val assertion = googleAssertion("alice@gmail.com", EmailEvidence.AUTHORITATIVE)
        val user = User(UserId.generate(), "alice@gmail.com", "Alice")
        every { identityRepository.find(assertion.identityKey) } returns identity(user.userId, assertion)
        every { userRepository.findByIdForUpdate(user.userId) } returns user

        val result = service().login(assertion, LoginUseCase.FirstSignIn.JOIN_BY_INVITATION)

        assertThat(result.newUser).isFalse()
        verify(exactly = 0) { organizationRepository.insert(any()) }
    }

    @Test
    fun `precreated User cannot be claimed through former manual invitation path`() = runTest {
        val assertion = googleAssertion("invitee@acme.test", EmailEvidence.AUTHORITATIVE)
        val invited = User(UserId.generate(), "invitee@acme.test", "Invitee")
        every { identityRepository.find(assertion.identityKey) } returns null
        every { userRepository.findByPrimaryEmailForUpdate(invited.primaryEmail) } returns invited
        assertThatThrownBy { kotlinx.coroutines.runBlocking { service().login(assertion, LoginUseCase.FirstSignIn.CREATE_PERSONAL_ORGANIZATION) } }
            .isInstanceOf(IdentityLinkRequiredException::class.java)
        verify(exactly = 0) { identityRepository.insertIfAbsent(any()) }
    }

    @Test
    fun `pre-provisioned User cannot be claimed`() = runTest {
        val assertion = googleAssertion("preprovisioned@acme.test", EmailEvidence.AUTHORITATIVE)
        val preprovisionedUser = User(UserId.generate(), "preprovisioned@acme.test", "Preprovisioned")
        every { identityRepository.find(assertion.identityKey) } returns null
        every { userRepository.findByPrimaryEmailForUpdate(preprovisionedUser.primaryEmail) } returns preprovisionedUser

        assertThatThrownBy { kotlinx.coroutines.runBlocking { service().login(assertion, LoginUseCase.FirstSignIn.CREATE_PERSONAL_ORGANIZATION) } }
            .isInstanceOf(IdentityLinkRequiredException::class.java)
    }

    @Test
    fun `global identity resolver does not create initial organization or workspace`() {
        val assertion = googleAssertion("identity-only@gmail.com", EmailEvidence.AUTHORITATIVE)
        every { identityRepository.find(assertion.identityKey) } returns null
        every { userRepository.findByPrimaryEmailForUpdate("identity-only@gmail.com") } returns null
        every { userRepository.insertIfPrimaryEmailAbsent(any()) } returns true
        every { identityRepository.insertIfAbsent(any()) } returns true

        val result = identityResolver().resolve(assertion)

        assertThat(result.loginIdentity.linkedVia).isEqualTo(LoginIdentityLinkedVia.SIGNUP)
        verify(exactly = 0) { organizationRepository.insert(any()) }
        verify(exactly = 0) { workspaceRepository.insert(any()) }
    }

    private fun service(policy: DeveloperAccessPolicy = DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.OPEN, emptySet())) = LoginService(
        transactionRunner = transactionRunner,
        loginIdentityResolver = identityResolver(),
        organizationRepository = organizationRepository,
        workspaceRepository = workspaceRepository,
        workspaceAuthorizationBootstrap = workspaceAuthorizationBootstrap,
        auditLogRepository = auditLogRepository,
        auditEventFactory = AuditEventFactory(clock),
        clock = clock, developerAccessPolicy = policy)

    private fun identityResolver() = LoginIdentityResolver(
        normalizedEmailUserResolver = NormalizedEmailUserResolver(userRepository),
        userRepository = userRepository,
        loginIdentityRepository = identityRepository,
        clock = clock,
    )

    private fun googleAssertion(email: String, evidence: EmailEvidence) = AuthenticationAssertion(
        identityKey = LoginIdentityKey(AuthenticationProtocol.OIDC, "https://accounts.google.com", "google-sub"),
        authenticationSourceRef = "builtin/google",
        authenticationIssuer = "https://accounts.google.com",
        assertedEmail = email,
        assertedEmailVerified = evidence == EmailEvidence.AUTHORITATIVE,
        emailEvidence = evidence,
        displayName = "Alice",
        upstreamAuthenticatedAt = null,
        authenticationContext = null,
        authenticationMethods = emptyList(),
        upstreamSessionId = null,
    )

    private fun identity(userId: UserId, assertion: AuthenticationAssertion) = LoginIdentity(
        id = LoginIdentityId.generate(),
        userId = userId,
        key = assertion.identityKey,
        linkedVia = LoginIdentityLinkedVia.SIGNUP,
        linkedAt = now,
    )

}
