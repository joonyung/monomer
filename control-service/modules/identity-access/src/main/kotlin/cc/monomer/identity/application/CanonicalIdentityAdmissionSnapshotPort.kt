package cc.monomer.identity.application

import cc.monomer.identity.api.OrganizationPlan
import cc.monomer.identity.api.AgentClientId
import cc.monomer.identity.api.CanonicalPrincipal
import cc.monomer.identity.api.CredentialKind
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.domain.model.WorkspaceActorAuthorizationFacts
import java.time.Instant

interface CanonicalIdentityAdmissionSnapshotPort {
    suspend fun read(
        credential: PresentedCredential,
        workspaceId: WorkspaceId,
        expectedOAuthResource: ExpectedOAuthResource?,
    ): CanonicalIdentityAdmissionSnapshot
}

data class CanonicalIdentityAdmissionSnapshot(
    val credential: CanonicalCredentialState,
    val workspaceAuthorization: WorkspaceActorAuthorizationFacts?,
    val organizationPlan: OrganizationPlan?,
)

sealed interface CanonicalCredentialState {
    data object Unavailable : CanonicalCredentialState

    data object Expired : CanonicalCredentialState

    data object Revoked : CanonicalCredentialState

    data object BindingMismatch : CanonicalCredentialState

    data object PrincipalMismatch : CanonicalCredentialState

    data class Active(
        val actor: CanonicalPrincipal,
        val kind: CredentialKind,
        val surface: CredentialSurface,
        val agentClientId: AgentClientId?,
        val expiresAt: Instant,
        val agentScopes: Set<String>,
    ) : CanonicalCredentialState
}
