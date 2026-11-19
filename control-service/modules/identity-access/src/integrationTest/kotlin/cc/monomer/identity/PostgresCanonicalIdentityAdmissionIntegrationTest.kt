@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.identity

import cc.monomer.identity.domain.model.DeveloperAccessPolicy

import cc.monomer.identity.application.IdentityAdmissionService
import cc.monomer.identity.application.agent.AgentOAuthTokenHasher
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.IdentityAdmissionDenial
import cc.monomer.identity.api.IdentityRoleAdmissionDecision
import cc.monomer.identity.api.IdentityRoleAdmissionRequest
import cc.monomer.identity.api.IdentityMembershipAdmissionDecision
import cc.monomer.identity.api.IdentityMembershipAdmissionRequest
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.port.CurrentUserCredentialVerifier
import cc.monomer.identity.outbound.postgres.PostgresCanonicalIdentityAdmissionSnapshot
import cc.monomer.identity.outbound.postgres.SpringTransactionRunner
import cc.monomer.identity.outbound.postgres.AuthSchemaVerifier
import cc.monomer.identity.outbound.jwt.JwtIssuer
import cc.monomer.identity.outbound.jwt.JwtVerifier
import cc.monomer.identity.outbound.jwt.JwtKeySource
import cc.monomer.identity.outbound.jwt.JwtProperties
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.sql.Timestamp
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

class PostgresCanonicalIdentityAdmissionIntegrationTest {
    private val dataSource get() = TargetIdentityPostgres.dataSource
    private val jdbc get() = NamedParameterJdbcTemplate(dataSource)
    private val now = Instant.parse("2026-08-20T00:00:00Z")
    private val organizationId = Uuid.generateV7().toJavaUuid()
    private val workspaceId = Uuid.generateV7().toJavaUuid()
    private val ownerUserId = Uuid.generateV7().toJavaUuid()
    private val userId = Uuid.generateV7().toJavaUuid()
    private val relationId = Uuid.generateV7().toJavaUuid()
    private val loginIdentityId = Uuid.generateV7().toJavaUuid()
    private val sessionId = Uuid.generateV7().toJavaUuid()
    private val agentGrantId = Uuid.generateV7().toJavaUuid()
    private val agentTokenId = Uuid.generateV7().toJavaUuid()
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val jwtKeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val jwtKeySource = JwtKeySource(
        privateKey = jwtKeyPair.private as RSAPrivateKey,
        publicKey = jwtKeyPair.public as RSAPublicKey,
        kid = "admission-test-key",
    )
    private val jwtProperties = JwtProperties(
        issuer = "monomer-auth",
        audience = "monomer-internal",
        accessTokenTtlSeconds = 900,
        refreshTokenTtlSeconds = 604800,
        privateKeyPem = "",
        privateKeyPath = "",
    )
    private val browserCredential = JwtIssuer(jwtKeySource, jwtProperties, clock)
        .issueBrowserSession(UserId(userId.toKotlinUuid()), SessionId(sessionId.toKotlinUuid())).serialized
    private val agentCredential = "agent-credential-${UUID.randomUUID()}"

    private lateinit var sessionVerifier: CurrentUserCredentialVerifier
    private lateinit var admission: IdentityAdmissionService

    @BeforeEach
    fun setUp() {
        TargetIdentityPostgres.dataSource.connection.use { connection ->
            TargetIdentityPostgres.applyCanonicalSchemaTwice(connection, javaClass.classLoader)
            AuthSchemaVerifier.verifyContractFacts(connection)
        }
        insertFixture()
        configureAdmission(now)
    }

    @Test
    fun `credential admission keeps authenticated nonmember identity without workspace authority`() = runBlocking<Unit> {
        removeWorkspaceMember(userId)
        val request = cc.monomer.identity.api.IdentityCredentialAdmissionRequest(apiWorkspaceId(), PresentedCredential(agentCredential), ExpectedOAuthResource.AGENT_REST)
        val authorized = admission.decide(request) as cc.monomer.identity.api.IdentityCredentialAdmissionDecision.Authorized
        assertThat(authorized.evidence.actor.id.value.toJavaUuid()).isEqualTo(userId)
        assertThat(authorized.evidence.credentialKind).isEqualTo(cc.monomer.identity.api.CredentialKind.AGENT_OAUTH)
        assertThat(authorized.evidence.credentialSurface).isEqualTo(cc.monomer.identity.api.CredentialSurface.AGENT)
        assertThat(authorized.evidence.agentClientId).isEqualTo(cc.monomer.identity.api.AgentClientId("test-client"))
        assertDenied(agentMembership(), IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE)
        jdbc.update("UPDATE identity_runtime.agent_oauth_grants SET revoked_at = :now WHERE id = :id", mapOf("now" to Timestamp.from(now), "id" to agentGrantId))
        assertThat(admission.decide(request)).isEqualTo(cc.monomer.identity.api.IdentityCredentialAdmissionDecision.Denied(IdentityAdmissionDenial.CREDENTIAL_REVOKED))
    }

    private fun configureAdmission(currentTime: Instant) {
        val admissionClock = Clock.fixed(currentTime, ZoneOffset.UTC)
        sessionVerifier = JwtVerifier(jwtKeySource, jwtProperties, admissionClock)
        val transactionManager = DataSourceTransactionManager(dataSource)
        val writeTemplate = TransactionTemplate(transactionManager)
        val readOnlyTemplate = TransactionTemplate(transactionManager).apply {
            isReadOnly = true
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }
        val transactionRunner = SpringTransactionRunner(writeTemplate, readOnlyTemplate, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        val snapshot = PostgresCanonicalIdentityAdmissionSnapshot(
            jdbc = jdbc,
            transactionRunner = transactionRunner,
            currentUserCredentialVerifier = sessionVerifier,
            agentOAuthTokenHasher = AgentOAuthTokenHasher(),
            clock = admissionClock,
            agentResourceAudienceByExpectedResource = mapOf(
                ExpectedOAuthResource.AGENT_REST to "https://monomer.test/agent",
                ExpectedOAuthResource.AGENT_MCP to "https://monomer.test/mcp",
            ), developerAccessPolicy = DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.OPEN, emptySet()))
        admission = IdentityAdmissionService(snapshot)
    }

    @Test
    fun `browser session requires direct user workspace membership`() = runBlocking<Unit> {
        assertThat(browserMembership()).isInstanceOf(IdentityMembershipAdmissionDecision.Authorized::class.java)

        removeWorkspaceMember(userId)
        assertDenied(browserMembership(), IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE)
    }

    @Test
    fun `session revoke organization removal and workspace removal deny immediately`() = runBlocking<Unit> {
        assertThat(browserMembership()).isInstanceOf(IdentityMembershipAdmissionDecision.Authorized::class.java)

        jdbc.update(
            "UPDATE identity_runtime.sessions SET revoked_at = :revokedAt, revocation_reason = 'logout' WHERE id = :id",
            mapOf("revokedAt" to Timestamp.from(now), "id" to sessionId),
        )
        assertDenied(browserMembership(), IdentityAdmissionDenial.CREDENTIAL_REVOKED)

        jdbc.update(
            "UPDATE identity_runtime.sessions SET revoked_at = NULL, revocation_reason = NULL WHERE id = :id",
            mapOf("id" to sessionId),
        )
        jdbc.update(
            "DELETE FROM identity_runtime.organization_members WHERE organization_id = :organizationId AND user_id = :userId",
            mapOf("organizationId" to organizationId, "userId" to userId),
        )
        assertThat(browserMembership())
            .isInstanceOf(IdentityMembershipAdmissionDecision.Denied::class.java)

        jdbc.update(
            "INSERT INTO identity_runtime.organization_members(organization_id, user_id, role) VALUES(:organizationId, :userId, 'MEMBER')",
            mapOf("organizationId" to organizationId, "userId" to userId),
        )
        removeWorkspaceMember(userId)
        assertDenied(browserMembership(), IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE)
    }

    @Test
    fun `role evidence carries the current owning organization plan`() = runBlocking<Unit> {
        val request = IdentityRoleAdmissionRequest(
            workspaceId = apiWorkspaceId(), credential = PresentedCredential(agentCredential),
            expectedOAuthResource = ExpectedOAuthResource.AGENT_REST,
            requiredRole = cc.monomer.identity.api.WorkspaceRole.EDITOR,

        )
        for (plan in cc.monomer.identity.api.OrganizationPlan.entries) {
            jdbc.update("UPDATE identity_runtime.organizations SET plan = :plan WHERE id = :id",
                mapOf("plan" to plan.name.lowercase(), "id" to organizationId))
            val authorized = admission.decide(request) as IdentityRoleAdmissionDecision.Authorized
            assertThat(authorized.evidence.organizationPlan).isEqualTo(plan)
        }
    }

    @Test
    fun `Agent OAuth workspace scope still requires a sufficient effective role`() = runBlocking<Unit> {
        val membership = admission.decide(
            IdentityMembershipAdmissionRequest(
                workspaceId = apiWorkspaceId(),
                credential = PresentedCredential(agentCredential),
                expectedOAuthResource = ExpectedOAuthResource.AGENT_REST,
            ),
        )
        assertThat(membership).isInstanceOf(IdentityMembershipAdmissionDecision.Authorized::class.java)

        val editRequest = IdentityRoleAdmissionRequest(
            workspaceId = apiWorkspaceId(),
            credential = PresentedCredential(agentCredential),
            expectedOAuthResource = ExpectedOAuthResource.AGENT_REST,
            requiredRole = cc.monomer.identity.api.WorkspaceRole.EDITOR,

        )
        assertThat(admission.decide(editRequest))
            .isInstanceOf(IdentityRoleAdmissionDecision.Authorized::class.java)

        jdbc.update(
            "UPDATE identity_runtime.workspace_members SET role = 'EXPLORER' WHERE workspace_id = :workspaceId AND user_id = :userId",
            mapOf("workspaceId" to workspaceId, "userId" to userId),
        )
        assertDenied(
            admission.decide(editRequest),
            IdentityAdmissionDenial.PERMISSION_UNAVAILABLE,
        )
    }

    @Test
    fun `Agent OAuth rejects wrong audience and removed Workspace membership`() = runBlocking<Unit> {
        assertThat(agentMembership()).isInstanceOf(IdentityMembershipAdmissionDecision.Authorized::class.java)
        jdbc.update(
            "UPDATE identity_runtime.agent_oauth_grants SET resource_audience = :audience WHERE id = :id",
            mapOf("audience" to "https://other.test/agent", "id" to agentGrantId),
        )
        assertDenied(agentMembership(), IdentityAdmissionDenial.AGENT_BINDING_MISMATCH)
        jdbc.update(
            "UPDATE identity_runtime.agent_oauth_grants SET resource_audience = 'https://monomer.test/agent' WHERE id = :id",
            mapOf("id" to agentGrantId),
        )
        removeWorkspaceMember(userId)
        assertDenied(agentMembership(), IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE)
    }

    @Test
    fun `Agent OAuth grant admits a new Workspace in another Organization through current membership`() = runBlocking<Unit> {
        val otherOrganizationId = Uuid.generateV7().toJavaUuid()
        val otherWorkspaceId = Uuid.generateV7().toJavaUuid()
        TransactionTemplate(DataSourceTransactionManager(dataSource)).execute {
            val parameters = mapOf("organizationId" to otherOrganizationId, "workspaceId" to otherWorkspaceId,
                "userId" to userId, "createdAt" to Timestamp.from(now))
            jdbc.update("INSERT INTO identity_runtime.organizations(id, display_name, created_at) VALUES (:organizationId, 'Other organization', :createdAt)", parameters)
            jdbc.update("INSERT INTO identity_runtime.organization_members(organization_id, user_id, role, joined_at) VALUES (:organizationId, :userId, 'ADMIN', :createdAt)", parameters)
            jdbc.update("INSERT INTO identity_runtime.workspaces(id, organization_id, display_name, authorization_revision, created_at) VALUES (:workspaceId, :organizationId, 'Other workspace', 1, :createdAt)", parameters)
            jdbc.update("INSERT INTO identity_runtime.workspace_members(workspace_id, user_id, invited_at, role) VALUES (:workspaceId, :userId, :createdAt, 'EDITOR')", parameters)
            jdbc.update("UPDATE identity_runtime.workspace_members SET role = 'OWNER' WHERE (workspace_id = :workspaceId AND user_id = :userId)", parameters)
        }
        val otherRequest = IdentityMembershipAdmissionRequest(
            workspaceId = WorkspaceId(otherWorkspaceId.toKotlinUuid()),
            credential = PresentedCredential(agentCredential),
            expectedOAuthResource = ExpectedOAuthResource.AGENT_REST,
        )
        assertThat(agentMembership()).isInstanceOf(IdentityMembershipAdmissionDecision.Authorized::class.java)
        assertThat(admission.decide(otherRequest)).isInstanceOf(IdentityMembershipAdmissionDecision.Authorized::class.java)
        removeWorkspaceMember(userId)
        assertDenied(agentMembership(), IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE)
        assertThat(admission.decide(otherRequest)).isInstanceOf(IdentityMembershipAdmissionDecision.Authorized::class.java)
    }

    @Test
    fun `Agent OAuth token and grant revocation deny immediately`() = runBlocking<Unit> {
        jdbc.update(
            "UPDATE identity_runtime.agent_oauth_access_tokens SET revoked_at = :now WHERE id = :id",
            mapOf("now" to Timestamp.from(now), "id" to agentTokenId),
        )
        assertDenied(agentMembership(), IdentityAdmissionDenial.CREDENTIAL_REVOKED)
        jdbc.update("UPDATE identity_runtime.agent_oauth_access_tokens SET revoked_at = NULL WHERE id = :id", mapOf("id" to agentTokenId))
        jdbc.update(
            "UPDATE identity_runtime.agent_oauth_grants SET revoked_at = :now WHERE id = :id",
            mapOf("now" to Timestamp.from(now), "id" to agentGrantId),
        )
        assertDenied(agentMembership(), IdentityAdmissionDenial.CREDENTIAL_REVOKED)
    }

    @Test
    fun `Agent OAuth admission records grant use at most hourly and never on a failed credential`() = runBlocking<Unit> {
        jdbc.update(
            "UPDATE identity_runtime.agent_oauth_grants SET resource_audience = 'https://other.test/agent' WHERE id = :id",
            mapOf("id" to agentGrantId),
        )
        assertDenied(agentMembership(), IdentityAdmissionDenial.AGENT_BINDING_MISMATCH)
        assertThat(grantLastUsedAt()).isNull()
        jdbc.update(
            "UPDATE identity_runtime.agent_oauth_grants SET resource_audience = 'https://monomer.test/agent' WHERE id = :id",
            mapOf("id" to agentGrantId),
        )
        assertThat(browserMembership()).isInstanceOf(IdentityMembershipAdmissionDecision.Authorized::class.java)
        configureAdmission(now.plusSeconds(900))
        assertDenied(agentMembership(), IdentityAdmissionDenial.CREDENTIAL_EXPIRED)
        assertThat(grantLastUsedAt()).isNull()
        configureAdmission(now)

        assertThat(agentMembership()).isInstanceOf(IdentityMembershipAdmissionDecision.Authorized::class.java)
        assertThat(grantLastUsedAt()).isEqualTo(now)
        configureAdmission(now.plusSeconds(599))
        assertThat(agentMembership()).isInstanceOf(IdentityMembershipAdmissionDecision.Authorized::class.java)
        assertThat(grantLastUsedAt()).isEqualTo(now)

        jdbc.update(
            "UPDATE identity_runtime.agent_oauth_grants SET last_used_at = :lastUsedAt WHERE id = :id",
            mapOf("lastUsedAt" to Timestamp.from(now.minusSeconds(3600)), "id" to agentGrantId),
        )
        configureAdmission(now)
        assertThat(agentMembership()).isInstanceOf(IdentityMembershipAdmissionDecision.Authorized::class.java)
        assertThat(grantLastUsedAt()).isEqualTo(now)
    }

    @Test
    fun `recording use inside a caller's repeatable read transaction never joins, waits on, or fails it`() = runBlocking<Unit> {
        dataSource.connection.use { holder ->
            holder.autoCommit = false
            holder.prepareStatement("SELECT id FROM identity_runtime.agent_oauth_grants WHERE id = ? FOR UPDATE").use { statement ->
                statement.setObject(1, agentGrantId)
                statement.executeQuery().close()
            }
            val outer = TransactionTemplate(DataSourceTransactionManager(dataSource)).apply {
                isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            }
            val decisions = outer.execute {
                runBlocking { withTimeout(5_000) { listOf(async { agentMembership() }, async { agentMembership() }).awaitAll() } }
            }
            assertThat(decisions).allMatch { it is IdentityMembershipAdmissionDecision.Authorized }
            assertThat(grantLastUsedAt()).isNull()
            holder.rollback()
        }
        assertThat(agentMembership()).isInstanceOf(IdentityMembershipAdmissionDecision.Authorized::class.java)
        assertThat(grantLastUsedAt()).isEqualTo(now)
    }

    @Test
    fun `Browser session and Agent OAuth token expire at the current instant`() = runBlocking<Unit> {
        val expiresAt = now.plusSeconds(600)
        jdbc.update(
            "UPDATE identity_runtime.agent_oauth_access_tokens SET expires_at = :expiresAt WHERE id = :id",
            mapOf("expiresAt" to Timestamp.from(expiresAt), "id" to agentTokenId),
        )
        configureAdmission(expiresAt)
        assertDenied(browserMembership(), IdentityAdmissionDenial.CREDENTIAL_EXPIRED)
        assertDenied(agentMembership(), IdentityAdmissionDenial.CREDENTIAL_EXPIRED)
    }

    @Test
    fun `Browser JWT and opaque Agent credentials cannot cross credential surfaces`() = runBlocking<Unit> {
        assertDenied(admission.decide(IdentityMembershipAdmissionRequest(
            workspaceId = apiWorkspaceId(),
            credential = PresentedCredential(browserCredential),
            expectedOAuthResource = ExpectedOAuthResource.AGENT_REST,
        )), IdentityAdmissionDenial.AGENT_BINDING_MISMATCH)
        assertDenied(admission.decide(IdentityMembershipAdmissionRequest(
            workspaceId = apiWorkspaceId(),
            credential = PresentedCredential(agentCredential),
            expectedOAuthResource = null,
        )), IdentityAdmissionDenial.AGENT_BINDING_MISMATCH)
    }

    @Test
    fun `authoring assignments have no generic resource scope table`() {
        val table = jdbc.queryForObject(
            "SELECT to_regclass('identity_runtime.capability_assignments')",
            emptyMap<String, Any>(),
            String::class.java,
        )
        assertThat(table).isNull()
    }

    private suspend fun agentMembership() = admission.decide(
        IdentityMembershipAdmissionRequest(
            workspaceId = apiWorkspaceId(),
            credential = PresentedCredential(agentCredential),
            expectedOAuthResource = ExpectedOAuthResource.AGENT_REST,
        ),
    )

    private suspend fun browserMembership() = admission.decide(
        IdentityMembershipAdmissionRequest(
            workspaceId = apiWorkspaceId(),
            credential = PresentedCredential(browserCredential),
            expectedOAuthResource = null,
        ),
    )

    private fun insertFixture() {
        TransactionTemplate(DataSourceTransactionManager(dataSource)).execute {
            insertFixtureRows()
        }
    }

    private fun insertFixtureRows() {
        val parameters = mapOf(
            "createdAt" to Timestamp.from(now),
            "organizationId" to organizationId,
            "workspaceId" to workspaceId,
            "ownerUserId" to ownerUserId,
            "userId" to userId,
            "loginIdentityId" to loginIdentityId,
            "sessionId" to sessionId,
        )
        jdbc.update(
            "INSERT INTO identity_runtime.organizations(id, display_name, created_at) VALUES (:organizationId, 'Admission test organization', :createdAt)",
            parameters
        )
        jdbc.update(
            "INSERT INTO identity_runtime.principals(id, kind, display_name, created_at) VALUES (:ownerUserId, 'user', 'Workspace owner', :createdAt), (:userId, 'user', 'Admission user', :createdAt)",
            parameters,
        )
        jdbc.update(
            "INSERT INTO identity_runtime.users(id, primary_email, created_at) VALUES (:ownerUserId, :ownerEmail, :createdAt), (:userId, :email, :createdAt)",
            parameters + mapOf(
                "ownerEmail" to "owner-${ownerUserId}@example.com",
                "email" to "admission-${userId}@example.com",
            ),
        )
        jdbc.update(
            "INSERT INTO identity_runtime.login_identities(id, user_id, protocol, namespace, subject, linked_via, linked_at) VALUES (:loginIdentityId, :userId, 'development', 'test', :subject, 'signup', :createdAt)",
            parameters + mapOf("subject" to "admission-${userId}"),
        )
        jdbc.update(
            "INSERT INTO identity_runtime.workspaces(id, organization_id, display_name, authorization_revision, created_at) VALUES (:workspaceId, :organizationId, 'Admission workspace', 1, :createdAt)",
            parameters,
        )
        jdbc.update(
            "INSERT INTO identity_runtime.organization_members(organization_id, user_id, role, joined_at) VALUES (:organizationId, :ownerUserId, 'ADMIN', :createdAt), (:organizationId, :userId, 'MEMBER', :createdAt)",
            parameters,
        )
        jdbc.update(
            "INSERT INTO identity_runtime.workspace_members(workspace_id, user_id, invited_at, role) VALUES (:workspaceId, :ownerUserId, :createdAt, 'EXPLORER')",
            parameters,
        )
        jdbc.update(
            "UPDATE identity_runtime.workspace_members SET role = 'OWNER' WHERE (workspace_id = :workspaceId AND user_id = :ownerUserId)",
            parameters,
        )
        jdbc.update(
            "INSERT INTO identity_runtime.workspace_members(workspace_id, user_id, invited_at, role) VALUES (:workspaceId, :userId, :createdAt, 'EDITOR')",
            parameters,
        )
        jdbc.update(
            """
            INSERT INTO identity_runtime.sessions(
                id, user_id, login_identity_id, protocol, authentication_source_ref,
                authentication_issuer, asserted_email, asserted_email_verified, email_evidence,
                authentication_methods, created_at, last_refreshed_at, absolute_expires_at
            ) VALUES (
                :sessionId, :userId, :loginIdentityId, 'development', 'test', 'test',
                :email, TRUE, 'authoritative', '{}', :createdAt, :createdAt, :expiresAt
            )
            """.trimIndent(),
            parameters + mapOf(
                "email" to "admission-${userId}@example.com",
                "expiresAt" to Timestamp.from(now.plusSeconds(600)),
            ),
        )
        jdbc.update(
            "INSERT INTO identity_runtime.agent_oauth_grants(id, owner_user_id, oauth_client_id, resource_audience, scopes, issued_at) VALUES (:grantId, :userId, 'test-client', 'https://monomer.test/agent', ARRAY['agent.workspace']::TEXT[], :createdAt)",
            mapOf(
                "grantId" to agentGrantId,
                "userId" to userId,
                "organizationId" to organizationId,
                "createdAt" to Timestamp.from(now),
            ),
        )
        jdbc.update(
            "INSERT INTO identity_runtime.agent_oauth_access_tokens(id, grant_id, token_hash, issued_at, expires_at) VALUES (:tokenId, :grantId, :tokenHash, :createdAt, :expiresAt)",
            mapOf(
                "tokenId" to agentTokenId,
                "grantId" to agentGrantId,
                "tokenHash" to AgentOAuthTokenHasher().hash(agentCredential),
                "createdAt" to Timestamp.from(now),
                "expiresAt" to Timestamp.from(now.plusSeconds(900)),
            ),
        )
    }

    private fun grantLastUsedAt(): Instant? = jdbc.queryForObject(
        "SELECT last_used_at FROM identity_runtime.agent_oauth_grants WHERE id = :id",
        mapOf("id" to agentGrantId),
        Timestamp::class.java,
    )?.toInstant()

    private fun removeWorkspaceMember(userId: UUID) {
        jdbc.update(
            "DELETE FROM identity_runtime.workspace_members WHERE workspace_id = :workspaceId AND user_id = :userId",
            mapOf("workspaceId" to workspaceId, "userId" to userId),
        )
    }

    private fun apiWorkspaceId() = WorkspaceId(workspaceId.toKotlinUuid())

    private fun assertDenied(
        decision: IdentityMembershipAdmissionDecision,
        denial: IdentityAdmissionDenial,
    ) {
        assertThat(decision).isEqualTo(IdentityMembershipAdmissionDecision.Denied(denial))
    }

    private fun assertDenied(
        decision: IdentityRoleAdmissionDecision,
        denial: IdentityAdmissionDenial,
    ) {
        assertThat(decision).isEqualTo(IdentityRoleAdmissionDecision.Denied(denial))
    }

    companion object {
        @JvmField
        @RegisterExtension
        internal val postgres = TargetIdentityPostgres
    }
}
