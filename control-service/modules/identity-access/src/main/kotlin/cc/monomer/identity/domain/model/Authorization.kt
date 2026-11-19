package cc.monomer.identity.domain.model

import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId

@JvmInline
value class AuthorizationRevision(val value: Long) {
    init {
        require(value >= 0) { "authorization revision must be non-negative" }
    }

    override fun toString(): String = value.toString()
}

data class WorkspaceActorAuthorizationFacts(
    val actorUserId: UserId,
    val workspaceId: WorkspaceId,
    val organizationId: OrganizationId?,
    val authorizationRevision: AuthorizationRevision?,
    val organizationRole: OrganizationRole?,
    val role: cc.monomer.identity.api.WorkspaceRole?,
) {
    init {
        require((organizationId == null) == (authorizationRevision == null))
    }
}

data class WorkspaceActorAuthorizationSnapshot(
    val actorUserId: UserId,
    val workspaceId: WorkspaceId,
    val organizationId: OrganizationId,
    val authorizationRevision: AuthorizationRevision,
    val organizationRole: OrganizationRole,
    val role: cc.monomer.identity.api.WorkspaceRole?,
)

enum class WorkspaceActorAuthorizationDenial {
    WORKSPACE_NOT_FOUND,
    ORGANIZATION_MEMBERSHIP_UNAVAILABLE,
    WORKSPACE_MEMBERSHIP_UNAVAILABLE,
}

sealed interface WorkspaceActorAuthorizationDecision {
    data class Authorized(
        val snapshot: WorkspaceActorAuthorizationSnapshot,
    ) : WorkspaceActorAuthorizationDecision

    data class Denied(
        val reason: WorkspaceActorAuthorizationDenial,
    ) : WorkspaceActorAuthorizationDecision
}

object WorkspaceAuthorizationPolicy {
    fun authorize(
        facts: WorkspaceActorAuthorizationFacts,
    ): WorkspaceActorAuthorizationDecision {
        val organizationId = facts.organizationId
            ?: return WorkspaceActorAuthorizationDecision.Denied(
                WorkspaceActorAuthorizationDenial.WORKSPACE_NOT_FOUND,
            )
        val authorizationRevision = checkNotNull(facts.authorizationRevision)
        if (facts.organizationRole == null) {
            return WorkspaceActorAuthorizationDecision.Denied(
                WorkspaceActorAuthorizationDenial.ORGANIZATION_MEMBERSHIP_UNAVAILABLE,
            )
        }
        if (facts.role == null) {
            return WorkspaceActorAuthorizationDecision.Denied(
                WorkspaceActorAuthorizationDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE,
            )
        }
        return WorkspaceActorAuthorizationDecision.Authorized(
            WorkspaceActorAuthorizationSnapshot(
                actorUserId = facts.actorUserId,
                workspaceId = facts.workspaceId,
                organizationId = organizationId,
                authorizationRevision = authorizationRevision,
                organizationRole = checkNotNull(facts.organizationRole),
                role = facts.role,
            ),
        )
    }

    fun authorizeOwnerGovernance(facts: WorkspaceActorAuthorizationFacts): WorkspaceActorAuthorizationSnapshot? {
        if (facts.organizationRole != OrganizationRole.ADMIN) {
            return authorizeOwner(facts)
        }
        val organizationId = facts.organizationId ?: return null
        return WorkspaceActorAuthorizationSnapshot(
            actorUserId = facts.actorUserId, workspaceId = facts.workspaceId, organizationId = organizationId,
            authorizationRevision = checkNotNull(facts.authorizationRevision), organizationRole = OrganizationRole.ADMIN,
            role = facts.role,
        )
    }

    fun authorizeOwner(
        facts: WorkspaceActorAuthorizationFacts,
    ): WorkspaceActorAuthorizationSnapshot? =
        (authorize(facts) as? WorkspaceActorAuthorizationDecision.Authorized)
            ?.snapshot
            ?.takeIf { it.role == cc.monomer.identity.api.WorkspaceRole.OWNER }
}
