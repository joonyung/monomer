package cc.monomer.identity.application

import cc.monomer.identity.api.AgentScope
import cc.monomer.identity.api.IdentityAdmissionDenial
import cc.monomer.identity.api.IdentityAdmissionPort
import cc.monomer.identity.api.IdentityRoleAdmissionDecision
import cc.monomer.identity.api.IdentityRoleAdmissionRequest
import cc.monomer.identity.api.IdentityRoleEvidence
import cc.monomer.identity.api.IdentityMembershipAdmissionDecision
import cc.monomer.identity.api.IdentityMembershipAdmissionRequest
import cc.monomer.identity.api.IdentityMembershipEvidence
import cc.monomer.identity.api.PrincipalKind
import cc.monomer.identity.domain.model.WorkspaceActorAuthorizationDecision
import cc.monomer.identity.domain.model.WorkspaceActorAuthorizationFacts
import cc.monomer.identity.domain.model.WorkspaceActorAuthorizationSnapshot

class IdentityAdmissionService(
    private val snapshotPort: CanonicalIdentityAdmissionSnapshotPort,
) : IdentityAdmissionPort {
    override suspend fun decide(request: cc.monomer.identity.api.IdentityCredentialAdmissionRequest): cc.monomer.identity.api.IdentityCredentialAdmissionDecision {
        val credential = snapshotPort.read(request.credential, request.workspaceId, request.expectedOAuthResource).credential
        if (credential !is CanonicalCredentialState.Active) return cc.monomer.identity.api.IdentityCredentialAdmissionDecision.Denied(credential.denial())
        if (credential.lacksWorkspaceScope()) {
            return cc.monomer.identity.api.IdentityCredentialAdmissionDecision.Denied(IdentityAdmissionDenial.AGENT_SCOPE_INSUFFICIENT)
        }
        if (credential.actor.kind != PrincipalKind.USER) return cc.monomer.identity.api.IdentityCredentialAdmissionDecision.Denied(IdentityAdmissionDenial.PRINCIPAL_MISMATCH)
        return cc.monomer.identity.api.IdentityCredentialAdmissionDecision.Authorized(cc.monomer.identity.api.IdentityCredentialEvidence(credential.actor, credential.kind, credential.surface, credential.agentClientId))
    }

    override suspend fun decide(
        request: IdentityMembershipAdmissionRequest,
    ): IdentityMembershipAdmissionDecision {
        val snapshot = snapshotPort.read(
            credential = request.credential,
            workspaceId = request.workspaceId,
            expectedOAuthResource = request.expectedOAuthResource,
        )
        val credential = snapshot.credential
        if (credential !is CanonicalCredentialState.Active) {
            return IdentityMembershipAdmissionDecision.Denied(credential.denial())
        }
        if (credential.lacksWorkspaceScope()) {
            return deniedMembership(IdentityAdmissionDenial.AGENT_SCOPE_INSUFFICIENT)
        }
        val workspaceAuthorization = snapshot.workspaceAuthorization
            ?: return deniedMembership(IdentityAdmissionDenial.WORKSPACE_UNAVAILABLE)
        val authorization = authorize(workspaceAuthorization)
            ?: return deniedMembership(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE)
        val role = checkNotNull(authorization.role)
        if (credential.actor.kind != PrincipalKind.USER) {
            return deniedMembership(IdentityAdmissionDenial.PRINCIPAL_MISMATCH)
        }
        return IdentityMembershipAdmissionDecision.Authorized(
            IdentityMembershipEvidence(
                actor = credential.actor,
                organizationId = authorization.organizationId.toApiOrganizationId(),
                workspaceId = authorization.workspaceId.toApiWorkspaceId(),
                authorizationRevision = authorization.authorizationRevision.toApiRevision(),
                credentialKind = credential.kind,
                credentialSurface = credential.surface,
                agentClientId = credential.agentClientId,
                role = role,
            ),
        )
    }

    override suspend fun decide(
        request: IdentityRoleAdmissionRequest,
    ): IdentityRoleAdmissionDecision {
        val snapshot = snapshotPort.read(
            credential = request.credential,
            workspaceId = request.workspaceId,
            expectedOAuthResource = request.expectedOAuthResource,
        )
        val credential = snapshot.credential
        if (credential !is CanonicalCredentialState.Active) {
            return IdentityRoleAdmissionDecision.Denied(credential.denial())
        }
        if (credential.lacksWorkspaceScope()) {
            return deniedRole(IdentityAdmissionDenial.AGENT_SCOPE_INSUFFICIENT)
        }
        val workspaceAuthorization = snapshot.workspaceAuthorization
            ?: return deniedRole(IdentityAdmissionDenial.WORKSPACE_UNAVAILABLE)
        val authorization = authorize(workspaceAuthorization)
            ?: return deniedRole(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE)
        val role = checkNotNull(authorization.role)
        if (credential.actor.kind != PrincipalKind.USER) return deniedRole(IdentityAdmissionDenial.PRINCIPAL_MISMATCH)
        if (!role.includes(request.requiredRole)) return deniedRole(IdentityAdmissionDenial.PERMISSION_UNAVAILABLE)
        return IdentityRoleAdmissionDecision.Authorized(
            IdentityRoleEvidence(
                organizationPlan = snapshot.organizationPlan ?: return deniedRole(IdentityAdmissionDenial.WORKSPACE_UNAVAILABLE),
                actor = credential.actor,
                organizationId = authorization.organizationId.toApiOrganizationId(),
                workspaceId = authorization.workspaceId.toApiWorkspaceId(),
                authorizationRevision = authorization.authorizationRevision.toApiRevision(),
                credentialKind = credential.kind,
                credentialSurface = credential.surface,
                agentClientId = credential.agentClientId,
                role = role,
            ),
        )
    }

    private fun CanonicalCredentialState.Active.lacksWorkspaceScope(): Boolean =
        kind == cc.monomer.identity.api.CredentialKind.AGENT_OAUTH && AgentScope.WORKSPACE.wireValue !in agentScopes

    private fun CanonicalCredentialState.denial(): IdentityAdmissionDenial = when (this) {
        CanonicalCredentialState.Unavailable -> IdentityAdmissionDenial.CREDENTIAL_UNAVAILABLE
        CanonicalCredentialState.Expired -> IdentityAdmissionDenial.CREDENTIAL_EXPIRED
        CanonicalCredentialState.Revoked -> IdentityAdmissionDenial.CREDENTIAL_REVOKED
        CanonicalCredentialState.BindingMismatch -> IdentityAdmissionDenial.AGENT_BINDING_MISMATCH
        CanonicalCredentialState.PrincipalMismatch -> IdentityAdmissionDenial.PRINCIPAL_MISMATCH
        is CanonicalCredentialState.Active -> error("active credential has no denial")
    }

    private fun deniedMembership(reason: IdentityAdmissionDenial) =
        IdentityMembershipAdmissionDecision.Denied(reason)

    private fun deniedRole(reason: IdentityAdmissionDenial) =
        IdentityRoleAdmissionDecision.Denied(reason)

    private fun authorize(
        facts: WorkspaceActorAuthorizationFacts,
    ): WorkspaceActorAuthorizationSnapshot? =
        (cc.monomer.identity.domain.model.WorkspaceAuthorizationPolicy.authorize(facts)
            as? WorkspaceActorAuthorizationDecision.Authorized)
            ?.snapshot
}

private fun cc.monomer.identity.domain.id.OrganizationId.toApiOrganizationId() =
    cc.monomer.identity.api.OrganizationId(value)

private fun cc.monomer.identity.domain.id.WorkspaceId.toApiWorkspaceId() =
    cc.monomer.identity.api.WorkspaceId(value)

private fun cc.monomer.identity.domain.model.AuthorizationRevision.toApiRevision() =
    cc.monomer.identity.api.AuthorizationRevision(value)
