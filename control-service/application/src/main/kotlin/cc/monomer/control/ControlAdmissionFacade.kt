@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control

import cc.monomer.identity.api.*
import java.time.Clock
import java.time.Instant

class ControlAdmissionFacade(
    private val identityAdmissionPort: IdentityAdmissionPort,
    private val clock: Clock,
) {
    suspend fun authorizeCredential(request: IdentityCredentialAdmissionRequest): ControlAdmissionDecision<IdentityCredentialEvidence> =
        when (val decision = identityAdmissionPort.decide(request)) {
            is IdentityCredentialAdmissionDecision.Authorized -> ControlAdmissionDecision.Admitted(decision.evidence)
            is IdentityCredentialAdmissionDecision.Denied -> denied(decision.reason)
        }

    suspend fun authorizeMutation(request: MutationAdmissionRequest): ControlAdmissionDecision<AuthorizedMutationContext> {
        return when (val decision = authorizeWorkspaceRole(WorkspaceRoleAdmissionRequest(
            request.common.workspaceId, request.common.credential, request.common.expectedOAuthResource, request.intent.requiredRole,
        ))) {
            is ControlAdmissionDecision.Denied -> decision
            is ControlAdmissionDecision.Admitted -> with(decision.context) {
                ControlAdmissionDecision.Admitted(AuthorizedMutationContext(
                    organizationPlan = organizationPlan,
                    actor = actor,
                    organizationId = organizationId,
                    workspaceId = workspaceId,
                    authorizationRevision = authorizationRevision,
                    credentialKind = credentialKind,
                    credentialSurface = credentialSurface,
                    agentClientId = agentClientId,
                    role = role,
                    intent = request.intent,
                    admittedAt = clock.instant(),
                ))
            }
        }
    }

    suspend fun authorizeWorkspaceRole(request: WorkspaceRoleAdmissionRequest): ControlAdmissionDecision<IdentityRoleEvidence> =
        when (val decision = identityAdmissionPort.decide(IdentityRoleAdmissionRequest(
            request.workspaceId, request.credential, request.expectedOAuthResource, request.requiredRole,
        ))) {
            is IdentityRoleAdmissionDecision.Authorized -> {
                if (decision.evidence.workspaceId != request.workspaceId || decision.evidence.role < request.requiredRole) {
                    ControlAdmissionDecision.Denied(ControlAdmissionStage.IDENTITY, ControlAdmissionDenial.IDENTITY_EVIDENCE_MISMATCH)
                } else ControlAdmissionDecision.Admitted(decision.evidence)
            }
            is IdentityRoleAdmissionDecision.Denied -> denied(decision.reason)
        }

    suspend fun authorizeWorkspaceMembership(request: WorkspaceMembershipAdmissionRequest): ControlAdmissionDecision<IdentityMembershipEvidence> =
        when (val decision = identityAdmissionPort.decide(IdentityMembershipAdmissionRequest(
            request.workspaceId, request.credential, request.expectedOAuthResource,
        ))) {
            is IdentityMembershipAdmissionDecision.Authorized -> {
                if (decision.evidence.workspaceId != request.workspaceId) {
                    ControlAdmissionDecision.Denied(ControlAdmissionStage.IDENTITY, ControlAdmissionDenial.IDENTITY_EVIDENCE_MISMATCH)
                } else ControlAdmissionDecision.Admitted(decision.evidence)
            }
            is IdentityMembershipAdmissionDecision.Denied -> denied(decision.reason)
        }

    suspend fun authorizeExecution(request: ExecutionAdmissionRequest): ControlAdmissionDecision<AuthorizedExecutionContext> =
        when (val decision = authorizeWorkspaceRole(WorkspaceRoleAdmissionRequest(
            request.workspaceId, request.credential, request.expectedOAuthResource, WorkspaceRole.EXPLORER,
        ))) {
            is ControlAdmissionDecision.Denied -> decision
            is ControlAdmissionDecision.Admitted -> with(decision.context) {
                ControlAdmissionDecision.Admitted(AuthorizedExecutionContext(
                    actor, organizationId, workspaceId, authorizationRevision, credentialKind, credentialSurface,
                    agentClientId, request.intent, clock.instant(),
                ))
            }
        }

    private fun denied(reason: IdentityAdmissionDenial) = ControlAdmissionDecision.Denied(
        ControlAdmissionStage.IDENTITY, ControlAdmissionDenial.Identity(reason),
    )

    internal companion object {
        fun sampleWorkspaceProvisioningMutation(
            sample: SampleWorkspace,
            evidence: SampleWorkspaceInitiatorEvidence,
            intent: MutationIntent,
            admittedAt: Instant,
        ): AuthorizedMutationContext {
            requireSampleInitiator(sample, evidence)
            require(intent in setOf(MutationIntent.CONNECTOR_CREATE, MutationIntent.CATALOG_SELECTION_UPDATE,
                MutationIntent.SEMANTIC_MODEL_CREATE, MutationIntent.SEMANTIC_TIME_SPINE_UPDATE, MutationIntent.METRIC_CREATE)) {
                "Mutation is outside sample provisioning"
            }
            return AuthorizedMutationContext(evidence.organizationPlan, evidence.actor, evidence.organizationId, evidence.workspaceId,
                evidence.authorizationRevision, CredentialKind.SESSION, CredentialSurface.WEB, null, evidence.role, intent, admittedAt)
        }

        fun sampleWorkspaceCatalogIngest(
            sample: SampleWorkspace,
            evidence: SampleWorkspaceInitiatorEvidence,
            admittedAt: Instant,
        ): AuthorizedExecutionContext {
            requireSampleInitiator(sample, evidence)
            return AuthorizedExecutionContext(evidence.actor, evidence.organizationId, evidence.workspaceId, evidence.authorizationRevision,
                CredentialKind.SESSION, CredentialSurface.WEB, null, ExecutionIntent.CATALOG_INGEST, admittedAt)
        }

        private fun requireSampleInitiator(sample: SampleWorkspace, evidence: SampleWorkspaceInitiatorEvidence) {
            require(sample.sample.dataset in setOf("acme-cloud@1", "acme-cloud@2", "acme-cloud@3") && sample.sample.state == WorkspaceSampleState.PROVISIONING &&
                evidence.workspaceId == sample.workspaceId && evidence.organizationId == sample.organizationId &&
                evidence.actor.id == sample.sample.initiator && evidence.actor.kind == PrincipalKind.USER && evidence.role == WorkspaceRole.OWNER) {
                "Sample initiator authorization does not match"
            }
        }
    }
}

data class AdmissionRequest(
    val workspaceId: WorkspaceId,
    val credential: PresentedCredential,
    val expectedOAuthResource: ExpectedOAuthResource?,
)

data class MutationAdmissionRequest(val common: AdmissionRequest, val intent: MutationIntent)

data class WorkspaceRoleAdmissionRequest(
    val workspaceId: WorkspaceId,
    val credential: PresentedCredential,
    val expectedOAuthResource: ExpectedOAuthResource?,
    val requiredRole: WorkspaceRole,
)

data class WorkspaceMembershipAdmissionRequest(
    val workspaceId: WorkspaceId,
    val credential: PresentedCredential,
    val expectedOAuthResource: ExpectedOAuthResource?,
)

data class ExecutionAdmissionRequest(
    val workspaceId: WorkspaceId,
    val credential: PresentedCredential,
    val expectedOAuthResource: ExpectedOAuthResource?,
    val intent: ExecutionIntent,
)

enum class ControlAdmissionStage { REQUEST, IDENTITY }

sealed interface ControlAdmissionDenial {
    data object INVALID_EXECUTION_TARGET : ControlAdmissionDenial
    data object IDENTITY_EVIDENCE_MISMATCH : ControlAdmissionDenial
    data class Identity(val reason: IdentityAdmissionDenial) : ControlAdmissionDenial
}

sealed interface ControlAdmissionDecision<out T> {
    data class Admitted<T>(val context: T) : ControlAdmissionDecision<T>
    data class Denied(val stage: ControlAdmissionStage, val reason: ControlAdmissionDenial) : ControlAdmissionDecision<Nothing>
}
