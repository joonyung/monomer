@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.identity.outbound.postgres

import cc.monomer.identity.domain.model.DeveloperAccessPolicy

import cc.monomer.identity.api.OrganizationPlan
import cc.monomer.identity.api.AgentClientId
import cc.monomer.identity.api.CanonicalPrincipal
import cc.monomer.identity.api.CredentialKind
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.PrincipalKind
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.application.CanonicalCredentialState
import cc.monomer.identity.application.CanonicalIdentityAdmissionSnapshot
import cc.monomer.identity.application.CanonicalIdentityAdmissionSnapshotPort
import cc.monomer.identity.application.agent.AgentOAuthTokenHasher
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.AgentOAuthGrantUse
import cc.monomer.identity.domain.port.CurrentUserCredentialVerifier
import cc.monomer.identity.domain.port.TransactionRunner
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import kotlin.uuid.toJavaUuid
import kotlin.uuid.Uuid
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class PostgresCanonicalIdentityAdmissionSnapshot(
    @param:Qualifier("postgresJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
    private val transactionRunner: TransactionRunner,
    private val currentUserCredentialVerifier: CurrentUserCredentialVerifier,
    private val agentOAuthTokenHasher: AgentOAuthTokenHasher,
    private val clock: Clock,
    private val agentResourceAudienceByExpectedResource: Map<ExpectedOAuthResource, String>,
    private val developerAccessPolicy: DeveloperAccessPolicy,
) : CanonicalIdentityAdmissionSnapshotPort {
    private val authorizationReader = PostgresWorkspaceAuthorizationReader(jdbc)

    init {
        require(agentResourceAudienceByExpectedResource.keys == ExpectedOAuthResource.entries.toSet()) {
            "Agent resource audiences must define every expected OAuth resource exactly once"
        }
        require(agentResourceAudienceByExpectedResource.values.all(String::isNotBlank)) {
            "Agent resource audiences must not be blank"
        }
        require(agentResourceAudienceByExpectedResource.values.toSet().size == ExpectedOAuthResource.entries.size) {
            "Agent resource audiences must remain distinct by expected OAuth resource"
        }
    }

    override suspend fun read(
        credential: PresentedCredential,
        workspaceId: WorkspaceId,
        expectedOAuthResource: ExpectedOAuthResource?,
    ): CanonicalIdentityAdmissionSnapshot {
        val (snapshot, grantUse) = transactionRunner.readOnly {
            when (val resolved = resolveCredential(credential, expectedOAuthResource)) {
                is ResolvedCredential.Failure ->
                    CanonicalIdentityAdmissionSnapshot(resolved.state, null, null) to null
                is ResolvedCredential.Success -> readAuthorization(resolved, workspaceId) to resolved.agentGrantUse
            }
        }
        val now = clock.instant()
        if (grantUse != null && AgentOAuthGrantUse.recordingDue(grantUse.lastUsedAt, now)) {
            recordAgentGrantUse(checkNotNull(jdbc.jdbcTemplate.dataSource), grantUse.grantId, now)
        }
        return snapshot
    }

    private fun readAuthorization(resolved: ResolvedCredential.Success, workspaceId: WorkspaceId): CanonicalIdentityAdmissionSnapshot {
        val domainWorkspaceId = cc.monomer.identity.domain.id.WorkspaceId(workspaceId.value)
        val workspaceContext = authorizationReader.readWorkspaceContext(domainWorkspaceId)
        val facts = authorizationReader.readActorFacts(
            actorUserId = resolved.userId,
            workspaceId = domainWorkspaceId,
            workspaceContext = workspaceContext,
        )
        return CanonicalIdentityAdmissionSnapshot(
            credential = resolved.state,
            workspaceAuthorization = facts,
            organizationPlan = facts.organizationId?.let { organizationId ->
                jdbc.query("SELECT plan FROM identity_runtime.organizations WHERE id = :id",
                    mapOf("id" to organizationId.value.toJavaUuid())) { rs, _ ->
                    OrganizationPlan.valueOf(rs.getString("plan").uppercase())
                }.firstOrNull()
            },
        )
    }

    private fun resolveCredential(
        credential: PresentedCredential,
        expectedOAuthResource: ExpectedOAuthResource?,
    ): ResolvedCredential {
        val verified = currentUserCredentialVerifier.verifyCurrentCredential(credential.value)
        if (verified != null) {
            val browser = verified.provenance as? cc.monomer.identity.domain.model.UserCredentialProvenance.BrowserSession
                ?: return ResolvedCredential.Failure(CanonicalCredentialState.BindingMismatch)
            if (expectedOAuthResource != null) {
                return ResolvedCredential.Failure(CanonicalCredentialState.BindingMismatch)
            }
            if (!developerAccessPolicy.isOpen && PostgresSessionRepository(jdbc).findById(browser.sessionId)?.let(developerAccessPolicy::allows) != true) {
                return ResolvedCredential.Failure(CanonicalCredentialState.Unavailable)
            }
            val session = jdbc.query(
                """
                SELECT user_id, revoked_at, absolute_expires_at
                  FROM identity_runtime.sessions
                 WHERE id = :sessionId
                """.trimIndent(),
                mapOf("sessionId" to browser.sessionId.value.toJavaUuid()),
            ) { resultSet, _ -> mapSession(resultSet) }.firstOrNull()
                ?: return ResolvedCredential.Failure(CanonicalCredentialState.Unavailable)
            if (session.userId != browser.userId.value) {
                return ResolvedCredential.Failure(CanonicalCredentialState.PrincipalMismatch)
            }
            if (session.revokedAt != null) {
                return ResolvedCredential.Failure(CanonicalCredentialState.Revoked)
            }
            if (!clock.instant().isBefore(session.absoluteExpiresAt)) {
                return ResolvedCredential.Failure(CanonicalCredentialState.Expired)
            }
            return ResolvedCredential.Success(
                userId = browser.userId,
                state = CanonicalCredentialState.Active(
                    actor = CanonicalPrincipal(
                        id = PrincipalId(browser.userId.value),
                        kind = PrincipalKind.USER,
                    ),
                    kind = CredentialKind.SESSION,
                    surface = CredentialSurface.WEB,
                    agentClientId = null,
                    expiresAt = verified.expiresAt,
                    agentScopes = emptySet(),
                ),
                agentGrantUse = null,
            )
        }

        if (expectedOAuthResource == null) {
            return ResolvedCredential.Failure(CanonicalCredentialState.BindingMismatch)
        }
        val tokenHash = agentOAuthTokenHasher.hash(credential.value)
        val agent = jdbc.query(
            """
            SELECT LEAST(access_token.expires_at, oauth_grant.issued_at + INTERVAL '30 days') AS access_token_expires_at,
                   access_token.revoked_at AS access_token_revoked_at,
                   oauth_grant.owner_user_id,
                   oauth_grant.oauth_client_id,
                   oauth_grant.resource_audience,
                   oauth_grant.scopes,
                   oauth_grant.revoked_at AS grant_revoked_at,
                   oauth_grant.id AS grant_id,
                   oauth_grant.last_used_at AS grant_last_used_at
              FROM identity_runtime.agent_oauth_access_tokens access_token
              JOIN identity_runtime.agent_oauth_grants oauth_grant
                ON oauth_grant.id = access_token.grant_id
              JOIN identity_runtime.users u ON u.id = oauth_grant.owner_user_id
              JOIN identity_runtime.principals p ON p.id = u.id AND p.kind = 'user'
             WHERE access_token.token_hash = :tokenHash
            """.trimIndent(),
            mapOf("tokenHash" to tokenHash),
        ) { resultSet, _ -> mapAgent(resultSet) }.firstOrNull()
            ?: return ResolvedCredential.Failure(CanonicalCredentialState.Unavailable)
        if (agent.accessTokenRevokedAt != null || agent.grantRevokedAt != null) {
            return ResolvedCredential.Failure(CanonicalCredentialState.Revoked)
        }
        if (!clock.instant().isBefore(agent.expiresAt)) {
            return ResolvedCredential.Failure(CanonicalCredentialState.Expired)
        }
        val expectedAudience = agentResourceAudienceByExpectedResource.getValue(expectedOAuthResource)
        if (agent.resourceAudience != expectedAudience) {
            return ResolvedCredential.Failure(CanonicalCredentialState.BindingMismatch)
        }
        if (!developerAccessPolicy.isOpen && !PostgresSessionRepository(jdbc).findLatestForUser(UserId(agent.ownerUserId)).any(developerAccessPolicy::allows)) {
            return ResolvedCredential.Failure(CanonicalCredentialState.Unavailable)
        }
        return ResolvedCredential.Success(
            userId = UserId(agent.ownerUserId),
            state = CanonicalCredentialState.Active(
                actor = CanonicalPrincipal(
                    id = PrincipalId(agent.ownerUserId),
                    kind = PrincipalKind.USER,
                ),
                kind = CredentialKind.AGENT_OAUTH,
                surface = CredentialSurface.AGENT,
                agentClientId = AgentClientId(agent.clientId),
                expiresAt = agent.expiresAt,
                agentScopes = agent.scopes,
            ),
            agentGrantUse = AgentGrantUse(agent.grantId, agent.lastUsedAt),
        )
    }

    private fun mapSession(resultSet: ResultSet): SessionRow =
        SessionRow(
            userId = resultSet.getUuid("user_id"),
            revokedAt = resultSet.getInstantNullable("revoked_at"),
            absoluteExpiresAt = resultSet.getInstant("absolute_expires_at"),
        )

    private fun mapAgent(resultSet: ResultSet): AgentRow =
        AgentRow(
            grantId = resultSet.getUuid("grant_id"),
            lastUsedAt = resultSet.getInstantNullable("grant_last_used_at"),
            ownerUserId = resultSet.getUuid("owner_user_id"),
            clientId = resultSet.getString("oauth_client_id"),
            resourceAudience = resultSet.getString("resource_audience"),
            scopes = resultSet.getStringArray("scopes").toSet(),
            expiresAt = resultSet.getInstant("access_token_expires_at"),
            accessTokenRevokedAt = resultSet.getInstantNullable("access_token_revoked_at"),
            grantRevokedAt = resultSet.getInstantNullable("grant_revoked_at"),
        )

    private data class SessionRow(
        val userId: Uuid,
        val revokedAt: Instant?,
        val absoluteExpiresAt: Instant,
    )

    private data class AgentRow(
        val grantId: Uuid,
        val lastUsedAt: Instant?,
        val ownerUserId: Uuid,
        val clientId: String,
        val resourceAudience: String,
        val scopes: Set<String>,
        val expiresAt: Instant,
        val accessTokenRevokedAt: Instant?,
        val grantRevokedAt: Instant?,
    )

    private data class AgentGrantUse(
        val grantId: Uuid,
        val lastUsedAt: Instant?,
    )

    private sealed interface ResolvedCredential {
        data class Success(
            val userId: UserId,
            val state: CanonicalCredentialState.Active,
            val agentGrantUse: AgentGrantUse?,
        ) : ResolvedCredential

        data class Failure(
            val state: CanonicalCredentialState,
        ) : ResolvedCredential
    }
}

private fun ResultSet.getStringArray(columnLabel: String): List<String> {
    val array = getArray(columnLabel)?.array as? Array<*> ?: return emptyList()
    return array.mapNotNull { it as? String }
}
