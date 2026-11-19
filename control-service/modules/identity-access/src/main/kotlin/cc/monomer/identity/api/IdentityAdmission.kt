@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.identity.api

import java.time.Instant
import kotlin.uuid.Uuid

@JvmInline
value class PrincipalId(val value: Uuid) {
    init {
        value.requireUuidV7("principal id")
    }

    companion object {
        fun generate(): PrincipalId = PrincipalId(Uuid.generateV7())

        fun parse(value: String): PrincipalId = PrincipalId(Uuid.parse(value))
    }

    override fun toString(): String = value.toString()
}

@JvmInline
value class OrganizationId(val value: Uuid) {
    init {
        value.requireUuidV7("organization id")
    }

    companion object {
        fun generate(): OrganizationId = OrganizationId(Uuid.generateV7())

        fun parse(value: String): OrganizationId = OrganizationId(Uuid.parse(value))
    }

    override fun toString(): String = value.toString()
}

@JvmInline
value class WorkspaceId(val value: Uuid) {
    init {
        value.requireIetfUuid("workspace id")
    }

    companion object {
        fun generate(): WorkspaceId = WorkspaceId(Uuid.generateV7())

        fun parse(value: String): WorkspaceId = WorkspaceId(Uuid.parse(value))
    }

    override fun toString(): String = value.toString()
}

@JvmInline
value class AuthorizationRevision(val value: Long) {
    init {
        require(value >= 0) { "authorization revision must be non-negative" }
    }
}

@JvmInline
value class PresentedCredential(val value: String) {
    init {
        require(value.isNotBlank()) { "presented credential must not be blank" }
    }

    override fun toString(): String = "[REDACTED]"
}

enum class PrincipalKind {
    USER,
}

data class CanonicalPrincipal(
    val id: PrincipalId,
    val kind: PrincipalKind,
)

enum class CredentialKind {
    SESSION,
    AGENT_OAUTH,
}

enum class CredentialSurface {
    WEB,
    AGENT,
}

/**
 * The OAuth `client_id` of the agent app behind an agent connection: an https Client ID Metadata Document URL
 * whose metadata Monomer fetched and validated, or the id of a client registered in Control configuration.
 * Not a secret; never a credential.
 */
@JvmInline
value class AgentClientId(val value: String) {
    init {
        require(isValid(value)) {
            "agent client id must be 1 to $MAX_LENGTH characters without whitespace or control characters"
        }
    }

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 2048

        /**
         * A client id is a URL or a registered name, so it never holds whitespace or control characters. The cutover's
         * SQL guard matches this rule character for character.
         */
        fun isValid(value: String): Boolean =
            value.isNotEmpty() && value.length <= MAX_LENGTH && value.none { it.isWhitespace() || it.isISOControl() }
    }
}

/** Agent work carries the app that did it; Web work carries none. */
fun requireAgentClientMatchesSurface(credentialSurface: CredentialSurface, agentClientId: AgentClientId?) {
    require((credentialSurface == CredentialSurface.AGENT) == (agentClientId != null)) {
        "an agent client id is present exactly for AGENT credentials"
    }
}

/** A recorded change names an agent app only for AGENT work; AGENT work recorded before apps were kept names none. */
fun requireRecordedAgentClient(credentialSurface: CredentialSurface?, agentClientId: AgentClientId?) {
    require(agentClientId == null || credentialSurface == CredentialSurface.AGENT) {
        "only AGENT work records an agent client id"
    }
}

enum class AgentScope(val wireValue: String) {
    WORKSPACE("agent.workspace"),
}

enum class ExpectedOAuthResource {
    AGENT_REST,
    AGENT_MCP,
}

enum class WorkspaceRole {
    VIEWER, EXPLORER, EDITOR, OWNER;

    fun includes(requiredRole: WorkspaceRole): Boolean = ordinal >= requiredRole.ordinal
}

data class IdentityCredentialAdmissionRequest(
    val workspaceId: WorkspaceId,
    val credential: PresentedCredential,
    val expectedOAuthResource: ExpectedOAuthResource?,
)

data class IdentityCredentialEvidence(
    val actor: CanonicalPrincipal,
    val credentialKind: CredentialKind,
    val credentialSurface: CredentialSurface,
    val agentClientId: AgentClientId?,
) {
    init {
        requireAgentClientMatchesSurface(credentialSurface, agentClientId)
    }
}

sealed interface IdentityCredentialAdmissionDecision {
    data class Authorized(val evidence: IdentityCredentialEvidence) : IdentityCredentialAdmissionDecision
    data class Denied(val reason: IdentityAdmissionDenial) : IdentityCredentialAdmissionDecision
}

data class IdentityMembershipAdmissionRequest(
    val workspaceId: WorkspaceId,
    val credential: PresentedCredential,
    val expectedOAuthResource: ExpectedOAuthResource?,
)

data class IdentityRoleAdmissionRequest(
    val workspaceId: WorkspaceId,
    val credential: PresentedCredential,
    val expectedOAuthResource: ExpectedOAuthResource?,
    val requiredRole: WorkspaceRole,
)

data class IdentityMembershipEvidence(
    val actor: CanonicalPrincipal,
    val organizationId: OrganizationId,
    val workspaceId: WorkspaceId,
    val authorizationRevision: AuthorizationRevision,
    val credentialKind: CredentialKind,
    val credentialSurface: CredentialSurface,
    val agentClientId: AgentClientId?,
    val role: WorkspaceRole,
) {
    init {
        requireAgentClientMatchesSurface(credentialSurface, agentClientId)
    }
}

data class IdentityRoleEvidence(
    val organizationPlan: OrganizationPlan,
    val actor: CanonicalPrincipal,
    val organizationId: OrganizationId,
    val workspaceId: WorkspaceId,
    val authorizationRevision: AuthorizationRevision,
    val credentialKind: CredentialKind,
    val credentialSurface: CredentialSurface,
    val agentClientId: AgentClientId?,
    val role: WorkspaceRole,
) {
    init {
        requireAgentClientMatchesSurface(credentialSurface, agentClientId)
    }
}

enum class IdentityAdmissionDenial {
    CREDENTIAL_UNAVAILABLE,
    CREDENTIAL_EXPIRED,
    CREDENTIAL_REVOKED,
    PRINCIPAL_MISMATCH,
    AGENT_BINDING_MISMATCH,
    AGENT_SCOPE_INSUFFICIENT,
    WORKSPACE_UNAVAILABLE,
    ORGANIZATION_MEMBERSHIP_UNAVAILABLE,
    WORKSPACE_MEMBERSHIP_UNAVAILABLE,
    PERMISSION_UNAVAILABLE,
}

sealed interface IdentityMembershipAdmissionDecision {
    data class Authorized(
        val evidence: IdentityMembershipEvidence,
    ) : IdentityMembershipAdmissionDecision

    data class Denied(
        val reason: IdentityAdmissionDenial,
    ) : IdentityMembershipAdmissionDecision
}

sealed interface IdentityRoleAdmissionDecision {
    data class Authorized(
        val evidence: IdentityRoleEvidence,
    ) : IdentityRoleAdmissionDecision

    data class Denied(
        val reason: IdentityAdmissionDenial,
    ) : IdentityRoleAdmissionDecision
}

interface IdentityAdmissionPort {
    suspend fun decide(request: IdentityCredentialAdmissionRequest): IdentityCredentialAdmissionDecision

    suspend fun decide(
        request: IdentityMembershipAdmissionRequest,
    ): IdentityMembershipAdmissionDecision

    suspend fun decide(
        request: IdentityRoleAdmissionRequest,
    ): IdentityRoleAdmissionDecision

}

enum class MutationIntent(val requiredRole: WorkspaceRole) {
    CONNECTOR_CREATE(WorkspaceRole.OWNER),
    CONNECTOR_UPDATE(WorkspaceRole.OWNER),
    CONNECTOR_DELETE(WorkspaceRole.OWNER),
    CATALOG_SELECTION_UPDATE(WorkspaceRole.EDITOR),
    SEMANTIC_MODEL_CREATE(WorkspaceRole.EDITOR),
    SEMANTIC_MODEL_UPDATE(WorkspaceRole.EDITOR),
    SEMANTIC_MODEL_DELETE(WorkspaceRole.EDITOR),
    SEMANTIC_TIME_SPINE_UPDATE(WorkspaceRole.EDITOR),
    METRIC_CREATE(WorkspaceRole.EDITOR),
    METRIC_UPDATE(WorkspaceRole.EDITOR),
    METRIC_DELETE(WorkspaceRole.EDITOR),
}

enum class ExecutionIntent {
    EXPLORE_RUN,
    CATALOG_INGEST,
}

class AuthorizedMutationContext(
    val organizationPlan: OrganizationPlan,
    val actor: CanonicalPrincipal,
    val organizationId: OrganizationId,
    val workspaceId: WorkspaceId,
    val authorizationRevision: AuthorizationRevision,
    val credentialKind: CredentialKind,
    val credentialSurface: CredentialSurface,
    val agentClientId: AgentClientId?,
    val role: WorkspaceRole,
    val intent: MutationIntent,
    val admittedAt: Instant,
) {
    init {
        requireAgentClientMatchesSurface(credentialSurface, agentClientId)
    }

    companion object {
        fun restore(
            organizationPlan: OrganizationPlan,
            actor: CanonicalPrincipal,
            organizationId: OrganizationId,
            workspaceId: WorkspaceId,
            authorizationRevision: AuthorizationRevision,
            credentialKind: CredentialKind,
            credentialSurface: CredentialSurface,
            agentClientId: AgentClientId?,
            role: WorkspaceRole,
            intent: MutationIntent,
            admittedAt: Instant,
        ): AuthorizedMutationContext =
            AuthorizedMutationContext(
                organizationPlan = organizationPlan,
                actor = actor,
                organizationId = organizationId,
                workspaceId = workspaceId,
                authorizationRevision = authorizationRevision,
                credentialKind = credentialKind,
                credentialSurface = credentialSurface,
                agentClientId = agentClientId,
                role = role,
                intent = intent,
                admittedAt = admittedAt,
            )
    }
}

class AuthorizedExecutionContext(
    val actor: CanonicalPrincipal,
    val organizationId: OrganizationId,
    val workspaceId: WorkspaceId,
    val authorizationRevision: AuthorizationRevision,
    val credentialKind: CredentialKind,
    val credentialSurface: CredentialSurface,
    val agentClientId: AgentClientId?,
    val intent: ExecutionIntent,
    val admittedAt: Instant,
) {
    init {
        requireAgentClientMatchesSurface(credentialSurface, agentClientId)
    }
}

private fun Uuid.requireIetfUuid(label: String) {
    require(this != Uuid.NIL) { "$label must not be nil" }
    require(toByteArray()[8].toInt().and(0xc0) == 0x80) {
        "$label must use the IETF UUID variant"
    }
}

private fun Uuid.requireUuidV7(label: String) {
    requireIetfUuid(label)
    require(toByteArray()[6].toInt().and(0xf0) == 0x70) {
        "$label must be UUIDv7"
    }
}
