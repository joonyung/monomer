package cc.monomer.control.publicapi

import cc.monomer.control.observability.addCorrelationId
import cc.monomer.agent.api.AgentProblemViolation
import cc.monomer.agent.api.AgentCreateInput
import cc.monomer.agent.api.AgentCreateRequest
import cc.monomer.agent.api.AgentInvokeInput
import cc.monomer.agent.api.AgentInvokeRequest
import cc.monomer.agent.api.AgentWorkspaceProblem
import cc.monomer.agent.api.AgentWorkspaceProblemStatus
import cc.monomer.agent.api.AgentRequestId
import cc.monomer.control.jdbc.causeTypeChain
import cc.monomer.control.jdbc.sqlStateChain
import org.slf4j.LoggerFactory

object AgentTransportProblems {
    val resourceNotFound = AgentWorkspaceProblem(
        "NOT_FOUND", "Not Found", "The requested Agent resource was not found.",
        AgentWorkspaceProblemStatus.NOT_FOUND, false,
        currentRevision = null,
        violations = emptyList(),
        hints = emptyList(),
        nextActions = emptyList(),
        retryAfterSeconds = null,
        roleRequirement = null,
        partitionFilters = emptyList(),
    )
    val forbidden = AgentWorkspaceProblem(
        "ROLE_REQUIRED", "Role required", "The caller does not have the role required to access this resource.",
        AgentWorkspaceProblemStatus.FORBIDDEN, false,
        currentRevision = null,
        violations = emptyList(),
        hints = emptyList(),
        nextActions = emptyList(),
        retryAfterSeconds = null,
        roleRequirement = null,
        partitionFilters = emptyList(),
    )
    val authorizationEvidenceMismatch = AgentWorkspaceProblem(
        "AUTHORIZATION_EVIDENCE_MISMATCH", "Forbidden", "The authorization evidence did not match the requested Agent resource.",
        AgentWorkspaceProblemStatus.FORBIDDEN, false,
        currentRevision = null,
        violations = emptyList(),
        hints = emptyList(),
        nextActions = emptyList(),
        retryAfterSeconds = null,
        roleRequirement = null,
        partitionFilters = emptyList(),
    )
    val ownerUnavailable = AgentWorkspaceProblem(
        "OWNER_UNAVAILABLE", "Service Unavailable", "The Agent workspace owner is temporarily unavailable.",
        AgentWorkspaceProblemStatus.SERVICE_UNAVAILABLE, true,
        currentRevision = null,
        violations = emptyList(),
        hints = emptyList(),
        nextActions = emptyList(),
        retryAfterSeconds = null,
        roleRequirement = null,
        partitionFilters = emptyList(),
    )
    val ownerResponseInvalid = AgentWorkspaceProblem(
        "AGENT_OWNER_RESPONSE_INVALID", "Service Unavailable", "The Agent workspace owner returned a response outside the declared route contract.",
        AgentWorkspaceProblemStatus.SERVICE_UNAVAILABLE, true,
        currentRevision = null,
        violations = emptyList(),
        hints = emptyList(),
        nextActions = emptyList(),
        retryAfterSeconds = null,
        roleRequirement = null,
        partitionFilters = emptyList(),
    )

    fun ownerResponseInvalid(request: Any): AgentWorkspaceProblem = failureForRequest(ownerResponseInvalid, request)

    fun ownerUnavailable(request: Any): AgentWorkspaceProblem = failureForRequest(ownerUnavailable, request)

    private fun failureForRequest(problem: AgentWorkspaceProblem, request: Any): AgentWorkspaceProblem = when {
        request is AgentCreateRequest && request.input.let {
            it is AgentCreateInput.Dashboard || it is AgentCreateInput.SemanticModel || it is AgentCreateInput.Metric
        } -> problem.copy(
            detail = "Monomer could not confirm the result of the create request. Read or list the target resource before sending the create request again.",
            retryable = false,
        )
        request is AgentInvokeRequest && request.action == "run" && request.input is AgentInvokeInput.ExploreRun -> problem.copy(
            detail = "Monomer could not confirm the result of the run request. List Explore runs before sending the run request again.",
            retryable = false,
        )
        else -> problem
    }

    fun requestInvalid(violations: List<AgentProblemViolation>): AgentWorkspaceProblem = AgentWorkspaceProblem(
        "AGENT_REQUEST_INVALID", "Bad Request", "The agent request does not match its declared contract.",
        AgentWorkspaceProblemStatus.BAD_REQUEST, false, null, violations,
        hints = emptyList(),
        nextActions = emptyList(),
        retryAfterSeconds = null,
        roleRequirement = null,
        partitionFilters = emptyList(),
    )

    const val RETRY_AFTER_SECONDS = 2L

    fun retryAfterSeconds(status: Int, retryable: Boolean, problemRetryAfterSeconds: Long?): Long? =
        if (status == AgentWorkspaceProblemStatus.TOO_MANY_REQUESTS.value || status == AgentWorkspaceProblemStatus.SERVICE_UNAVAILABLE.value && retryable) {
            problemRetryAfterSeconds ?: RETRY_AFTER_SECONDS
        } else {
            null
        }
}

private val agentTransportLogger = LoggerFactory.getLogger("cc.monomer.control.publicapi.AgentTransport")

internal fun logAgentTransportFailure(transport: String, requestId: AgentRequestId, failure: Exception) {
    agentTransportLogger.atWarn()
        .addKeyValue("event", "agent_transport_failed")
        .addKeyValue("transport", transport)
        .addCorrelationId("request_id", requestId.value)
        .setCause(failure)
        .addKeyValue("failure_types", failure.causeTypeChain())
        .addKeyValue("sql_states", failure.sqlStateChain())
        .log("agent_transport_failed")
}
