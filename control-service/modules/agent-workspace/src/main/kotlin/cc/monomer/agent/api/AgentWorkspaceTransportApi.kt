package cc.monomer.agent.api

import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId

interface AgentWorkspaceRequestDecoderPort {
    fun list(body: String): AgentListRequest

    fun search(body: String): AgentSearchRequest

    fun read(body: String): AgentReadRequest

    fun create(body: String): AgentWorkspaceDecodedRequest<AgentCreateRequest>

    fun edit(body: String): AgentWorkspaceDecodedRequest<AgentEditRequest>

    fun invoke(body: String): AgentWorkspaceDecodedRequest<AgentInvokeRequest>
}

data class AgentWorkspaceDecodedRequest<T>(
    val value: T,
)

class AgentWorkspaceRequestDecodingException(
    val violations: List<AgentProblemViolation>,
) : IllegalArgumentException(violations.first().message) {
    constructor(field: String, safeDetail: String) : this(
        listOf(AgentProblemViolation(field, "INVALID_FIELD", safeDetail, null, null)),
    )

    val field: String get() = violations.first().field
    val safeDetail: String get() = violations.first().message

    init {
        require(violations.size in 1..100)
    }
}

interface AgentWorkspaceCallerAuthenticationPort {
    suspend fun authenticate(
        workspaceId: WorkspaceId,
        credential: PresentedCredential,
        audience: AgentWorkspaceAdmissionAudience,
        request: AgentListRequest,
        requestId: AgentRequestId,
    ): AgentWorkspaceCallerAuthenticationOutcome

    suspend fun authenticate(
        workspaceId: WorkspaceId,
        credential: PresentedCredential,
        audience: AgentWorkspaceAdmissionAudience,
        request: AgentSearchRequest,
        requestId: AgentRequestId,
    ): AgentWorkspaceCallerAuthenticationOutcome

    suspend fun authenticate(
        workspaceId: WorkspaceId,
        credential: PresentedCredential,
        audience: AgentWorkspaceAdmissionAudience,
        request: AgentReadRequest,
        requestId: AgentRequestId,
    ): AgentWorkspaceCallerAuthenticationOutcome

    suspend fun authenticate(
        workspaceId: WorkspaceId,
        credential: PresentedCredential,
        audience: AgentWorkspaceAdmissionAudience,
        request: AgentCreateRequest,
        requestId: AgentRequestId,
    ): AgentWorkspaceCallerAuthenticationOutcome

    suspend fun authenticate(
        workspaceId: WorkspaceId,
        credential: PresentedCredential,
        audience: AgentWorkspaceAdmissionAudience,
        request: AgentEditRequest,
        requestId: AgentRequestId,
    ): AgentWorkspaceCallerAuthenticationOutcome

    suspend fun authenticate(
        workspaceId: WorkspaceId,
        credential: PresentedCredential,
        audience: AgentWorkspaceAdmissionAudience,
        request: AgentInvokeRequest,
        requestId: AgentRequestId,
    ): AgentWorkspaceCallerAuthenticationOutcome
}
