package cc.monomer.agent.application

import cc.monomer.agent.api.AgentWorkspaceCaller
import cc.monomer.agent.api.AgentWorkspaceCallerAuthenticationOutcome
import cc.monomer.agent.api.AgentWorkspaceCallerAuthenticationPort
import cc.monomer.agent.api.AgentWorkspaceAdmissionAudience
import cc.monomer.agent.api.AgentWorkspaceAdmissionIntents
import cc.monomer.agent.api.AgentWorkspaceAdmissionPort
import cc.monomer.agent.api.AgentCreateRequest
import cc.monomer.agent.api.AgentEditRequest
import cc.monomer.agent.api.AgentInvokeRequest
import cc.monomer.agent.api.AgentListRequest
import cc.monomer.agent.api.AgentReadRequest
import cc.monomer.agent.api.AgentRequestId
import cc.monomer.agent.api.AgentSearchRequest
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId

class AgentWorkspaceCallerAuthenticator(
    private val admissionPort: AgentWorkspaceAdmissionPort,
) : AgentWorkspaceCallerAuthenticationPort {
    override suspend fun authenticate(
        workspaceId: WorkspaceId,
        credential: PresentedCredential,
        audience: AgentWorkspaceAdmissionAudience,
        request: AgentListRequest,
        requestId: AgentRequestId,
    ): AgentWorkspaceCallerAuthenticationOutcome =
        authenticate(
            workspaceId = workspaceId,
            credential = credential,
            audience = audience,
            intent = AgentWorkspaceAdmissionIntents.list(request),
            requestId = requestId,
        )

    override suspend fun authenticate(
        workspaceId: WorkspaceId,
        credential: PresentedCredential,
        audience: AgentWorkspaceAdmissionAudience,
        request: AgentSearchRequest,
        requestId: AgentRequestId,
    ): AgentWorkspaceCallerAuthenticationOutcome =
        authenticate(
            workspaceId = workspaceId,
            credential = credential,
            audience = audience,
            intent = AgentWorkspaceAdmissionIntents.search(request),
            requestId = requestId,
        )

    override suspend fun authenticate(
        workspaceId: WorkspaceId,
        credential: PresentedCredential,
        audience: AgentWorkspaceAdmissionAudience,
        request: AgentReadRequest,
        requestId: AgentRequestId,
    ): AgentWorkspaceCallerAuthenticationOutcome =
        authenticate(
            workspaceId = workspaceId,
            credential = credential,
            audience = audience,
            intent = AgentWorkspaceAdmissionIntents.read(request),
            requestId = requestId,
        )

    override suspend fun authenticate(
        workspaceId: WorkspaceId,
        credential: PresentedCredential,
        audience: AgentWorkspaceAdmissionAudience,
        request: AgentCreateRequest,
        requestId: AgentRequestId,
    ): AgentWorkspaceCallerAuthenticationOutcome =
        authenticate(
            workspaceId = workspaceId,
            credential = credential,
            audience = audience,
            intent = AgentWorkspaceAdmissionIntents.create(request),
            requestId = requestId,
        )

    override suspend fun authenticate(
        workspaceId: WorkspaceId,
        credential: PresentedCredential,
        audience: AgentWorkspaceAdmissionAudience,
        request: AgentEditRequest,
        requestId: AgentRequestId,
    ): AgentWorkspaceCallerAuthenticationOutcome =
        authenticate(
            workspaceId = workspaceId,
            credential = credential,
            audience = audience,
            intent = AgentWorkspaceAdmissionIntents.edit(request),
            requestId = requestId,
        )

    override suspend fun authenticate(
        workspaceId: WorkspaceId,
        credential: PresentedCredential,
        audience: AgentWorkspaceAdmissionAudience,
        request: AgentInvokeRequest,
        requestId: AgentRequestId,
    ): AgentWorkspaceCallerAuthenticationOutcome =
        authenticate(
            workspaceId = workspaceId,
            credential = credential,
            audience = audience,
            intent = AgentWorkspaceAdmissionIntents.invoke(request),
            requestId = requestId,
        )

    private suspend fun authenticate(
        workspaceId: WorkspaceId,
        credential: PresentedCredential,
        audience: AgentWorkspaceAdmissionAudience,
        intent: cc.monomer.agent.api.AgentWorkspaceAdmissionIntent?,
        requestId: AgentRequestId,
    ): AgentWorkspaceCallerAuthenticationOutcome {
        if (intent == null) {
            return AgentWorkspaceCallerAuthenticationOutcome.InvalidRequest
        }
        return AgentWorkspaceCaller.authenticateRole(
            admissionPort = admissionPort,
            workspaceId = workspaceId,
            credential = credential,
            audience = audience,
            intent = intent,
            requestId = requestId,
        )
    }
}
