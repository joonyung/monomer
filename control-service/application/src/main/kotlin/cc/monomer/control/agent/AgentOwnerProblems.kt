package cc.monomer.control.agent

import cc.monomer.agent.api.AgentWorkspaceProblem
import cc.monomer.agent.api.AgentWorkspaceProblemStatus
import cc.monomer.agent.api.AgentActionOutcome
import cc.monomer.agent.api.AgentResourceOutcome
import cc.monomer.agent.api.AgentListingOutcome
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionDenial
import cc.monomer.identity.api.IdentityAdmissionDenial
import kotlinx.coroutines.CancellationException
import cc.monomer.semantic.application.SemanticMetricFlowEngineFaultException
import cc.monomer.semantic.application.SemanticMetricFlowInvalidQueryException
import cc.monomer.semantic.application.SemanticMetricFlowUnsupportedException

internal object AgentOwnerProblems {
    val ownerUnavailable = AgentWorkspaceProblem(
        code = "OWNER_UNAVAILABLE",
        title = "Service Unavailable",
        detail = "Monomer could not complete the request right now. Try again shortly.",
        status = AgentWorkspaceProblemStatus.SERVICE_UNAVAILABLE,
        retryable = true,
        currentRevision = null,
        violations = emptyList(),
        hints = emptyList(),
        nextActions = emptyList(),
        retryAfterSeconds = null,
        roleRequirement = null,
        partitionFilters = emptyList(),
    )

    val ownerNotInstalled = AgentWorkspaceProblem(
        code = "OWNER_UNAVAILABLE",
        title = "Service Unavailable",
        detail = "This Monomer deployment does not provide the requested resource.",
        status = AgentWorkspaceProblemStatus.SERVICE_UNAVAILABLE,
        retryable = false,
        currentRevision = null,
        violations = emptyList(),
        hints = emptyList(),
        nextActions = emptyList(),
        retryAfterSeconds = null,
        roleRequirement = null,
        partitionFilters = emptyList(),
    )

    val dashboardCreateUnconfirmed = ownerUnavailable.copy(
        detail = "Monomer could not confirm whether the Dashboard was created. List Dashboards before sending the create request again.",
        retryable = false,
    )

    val exploreStartUnconfirmed = ownerUnavailable.copy(
        detail = "Monomer could not confirm whether the Explore run was started. List Explore runs before sending the run request again.",
        retryable = false,
    )

    val semanticCreateUnconfirmed = ownerUnavailable.copy(
        detail = "Monomer could not confirm whether the Semantic resource was created. Read the Semantic resource before sending the create request again.",
        retryable = false,
    )

    val invalidCursor = AgentWorkspaceProblem(
        code = "CURSOR_INVALID",
        title = "Invalid cursor",
        detail = "The cursor does not belong to this list. List the same path again without a cursor.",
        status = AgentWorkspaceProblemStatus.BAD_REQUEST,
        retryable = false,
        currentRevision = null,
        violations = emptyList(),
        hints = emptyList(),
        nextActions = emptyList(),
        retryAfterSeconds = null,
        roleRequirement = null,
        partitionFilters = emptyList(),
    )
}

internal fun ControlAdmissionDecision.Denied.toAgentAdmissionProblem(): AgentWorkspaceProblem {
    val denial = (reason as? ControlAdmissionDenial.Identity)?.reason
    val (status, code, detail) = when (denial) {
        IdentityAdmissionDenial.CREDENTIAL_UNAVAILABLE, IdentityAdmissionDenial.CREDENTIAL_EXPIRED,
        IdentityAdmissionDenial.CREDENTIAL_REVOKED, IdentityAdmissionDenial.PRINCIPAL_MISMATCH,
        IdentityAdmissionDenial.AGENT_BINDING_MISMATCH, IdentityAdmissionDenial.AGENT_SCOPE_INSUFFICIENT ->
            Triple(AgentWorkspaceProblemStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "A valid credential is required.")
        IdentityAdmissionDenial.WORKSPACE_UNAVAILABLE, IdentityAdmissionDenial.ORGANIZATION_MEMBERSHIP_UNAVAILABLE,
        IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE ->
            Triple(AgentWorkspaceProblemStatus.NOT_FOUND, "NOT_FOUND", "The requested resource was not found.")
        IdentityAdmissionDenial.PERMISSION_UNAVAILABLE ->
            Triple(AgentWorkspaceProblemStatus.FORBIDDEN, "ROLE_REQUIRED", "Your Workspace role does not allow this action.")
        null -> Triple(AgentWorkspaceProblemStatus.BAD_REQUEST, "AGENT_REQUEST_INVALID", "The request is invalid.")
    }
    return AgentOwnerProblems.ownerUnavailable.copy(
        code = code,
        title = when (status) {
            AgentWorkspaceProblemStatus.UNAUTHORIZED -> "Unauthorized"
            AgentWorkspaceProblemStatus.NOT_FOUND -> "Not Found"
            AgentWorkspaceProblemStatus.FORBIDDEN -> "Role required"
            else -> "Invalid request"
        },
        detail = detail,
        status = status,
        retryable = false,
    )
}

internal fun ControlAdmissionDecision.Denied.toAgentActionDenial(): AgentActionOutcome {
    val problem = toAgentAdmissionProblem()
    return when (problem.status) {
        AgentWorkspaceProblemStatus.NOT_FOUND -> AgentActionOutcome.NotFound
        AgentWorkspaceProblemStatus.FORBIDDEN -> AgentActionOutcome.Forbidden
        else -> AgentActionOutcome.Rejected(problem)
    }
}

internal fun ControlAdmissionDecision.Denied.toAgentResourceDenial(): AgentResourceOutcome {
    val problem = toAgentAdmissionProblem()
    return when (problem.status) {
        AgentWorkspaceProblemStatus.NOT_FOUND -> AgentResourceOutcome.NotFound
        AgentWorkspaceProblemStatus.FORBIDDEN -> AgentResourceOutcome.Forbidden
        else -> AgentResourceOutcome.Rejected(problem)
    }
}

internal fun ControlAdmissionDecision.Denied.toAgentListingDenial(): AgentListingOutcome {
    val problem = toAgentAdmissionProblem()
    return when (problem.status) {
        AgentWorkspaceProblemStatus.NOT_FOUND -> AgentListingOutcome.NotFound
        AgentWorkspaceProblemStatus.FORBIDDEN -> AgentListingOutcome.Forbidden
        else -> AgentListingOutcome.Rejected(problem)
    }
}

internal inline fun <T> agentOwnerGuard(
    owner: String,
    failureProblem: AgentWorkspaceProblem,
    rejected: (AgentWorkspaceProblem) -> T,
    operation: () -> T,
): T = try {
    operation()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (fault: SemanticMetricFlowEngineFaultException) {
    throw fault
} catch (invalid: SemanticMetricFlowInvalidQueryException) {
    rejected(AgentOwnerProblems.ownerUnavailable.copy(
        code = "SEMANTIC_QUERY_INVALID",
        title = "Invalid Semantic query",
        detail = "The Semantic query cannot execute with the current definitions and selected Connector.",
        status = AgentWorkspaceProblemStatus.BAD_REQUEST,
        retryable = false,
        violations = listOf(cc.monomer.agent.api.AgentProblemViolation(
            "input", invalid.reason.name, "The Semantic query cannot execute with the current definitions and selected Connector.", null, null,
        )),
    ))
} catch (unsupported: SemanticMetricFlowUnsupportedException) {
    rejected(AgentOwnerProblems.ownerUnavailable.copy(
        code = "SEMANTIC_QUERY_UNSUPPORTED",
        title = "Unsupported Semantic query",
        detail = "The Semantic query uses a capability MetricFlow does not support.",
        status = AgentWorkspaceProblemStatus.BAD_REQUEST,
        retryable = false,
    ))
} catch (invalid: IllegalArgumentException) {
    throw invalid
} catch (failure: Exception) {
    logAgentOwnerFailure(owner, failure)
    rejected(failureProblem)
}
