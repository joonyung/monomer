package cc.monomer.control.pagereader

import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionDenial
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.WorkspaceMembershipAdmissionRequest
import cc.monomer.dashboard.api.*
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.IdentityAdmissionDenial
import cc.monomer.identity.api.IdentityCredentialEvidence
import cc.monomer.identity.api.IdentityCredentialAdmissionRequest
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.api.WorkspacePublicPagesQuery
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.pages.api.*
import kotlin.uuid.Uuid
import org.springframework.stereotype.Component

@Component
class PublicPagesAccess(
    private val publicPages: WorkspacePublicPagesQuery,
    private val admission: ControlAdmissionFacade,
    private val transactions: ConsistentSnapshotTransactionRunner,
) {
    suspend fun <T> snapshot(action: suspend () -> T): T = transactions.run(action)

    suspend fun requireWorkspace(
        workspaceId: WorkspaceId,
        credential: PresentedCredential?,
        expectedOAuthResource: ExpectedOAuthResource?,
    ): IdentityCredentialEvidence? {
        if (credential != null) {
            when (val decision = admission.authorizeWorkspaceMembership(
                WorkspaceMembershipAdmissionRequest(workspaceId, credential, expectedOAuthResource),
            )) {
                is ControlAdmissionDecision.Admitted -> return IdentityCredentialEvidence(
                    decision.context.actor, decision.context.credentialKind, decision.context.credentialSurface,
                    decision.context.agentClientId,
                )
                is ControlAdmissionDecision.Denied -> {
                    val nonMember = isNonMemberDenial(decision)
                    if (expectedOAuthResource != null || !nonMember) missing()
                }
            }
            val actor = when (val decision = admission.authorizeCredential(
                IdentityCredentialAdmissionRequest(workspaceId, credential, expectedOAuthResource),
            )) {
                is ControlAdmissionDecision.Admitted -> decision.context
                is ControlAdmissionDecision.Denied -> throw PageReaderProblem(PageReaderFailure.Unauthorized)
            }
            if (!publicPages.isPublic(workspaceId)) missing()
            return actor
        }
        if (!publicPages.isPublic(workspaceId)) missing()
        return null
    }

    fun isNonMemberDenial(denied: ControlAdmissionDecision.Denied): Boolean {
        val reason = (denied.reason as? ControlAdmissionDenial.Identity)?.reason
        if (reason in INVALID_CREDENTIAL_DENIALS) throw PageReaderProblem(PageReaderFailure.Unauthorized)
        return reason in NON_MEMBER_DENIALS
    }

    private companion object {
        val INVALID_CREDENTIAL_DENIALS = setOf(
            IdentityAdmissionDenial.CREDENTIAL_UNAVAILABLE,
            IdentityAdmissionDenial.CREDENTIAL_EXPIRED,
            IdentityAdmissionDenial.CREDENTIAL_REVOKED,
            IdentityAdmissionDenial.PRINCIPAL_MISMATCH,
            IdentityAdmissionDenial.AGENT_BINDING_MISMATCH,
            IdentityAdmissionDenial.AGENT_SCOPE_INSUFFICIENT,
        )
        val NON_MEMBER_DENIALS = setOf(
            IdentityAdmissionDenial.WORKSPACE_UNAVAILABLE,
            IdentityAdmissionDenial.ORGANIZATION_MEMBERSHIP_UNAVAILABLE,
            IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE,
        )
    }
}

internal fun List<CurrentPagePlacement>.path(id: Uuid): List<CurrentPagePlacement>? {
    for (placement in this) {
        if (placement.pageId == id) return listOf(placement)
        placement.children.path(id)?.let { return listOf(placement) + it }
    }
    return null
}

internal fun <T> DashboardOwnerOutcome<T>.valueOrThrow(): T = when (this) {
    is DashboardOwnerOutcome.Success -> value
    DashboardOwnerOutcome.NotFound -> missing()
    is DashboardOwnerOutcome.Conflict -> throw PageReaderProblem(PageReaderFailure.Conflict)
    is DashboardOwnerOutcome.Rejected -> {
        if (code in setOf("INVALID_VARIABLE_VALUE", "INVALID_SESSION_PARAMETERS", "SESSION_CONFLICT", "QUERY_RUN_CONFLICT", "DRAFT_CONFLICT", "EXECUTION_ADMISSION_CONFLICT", "QUERY_RUN_CANCEL_CONFLICT")) {
            throw PageReaderOwnerRejected(code, message ?: "The Dashboard request was rejected.")
        }
        throw PageReaderProblem(when (code) {
        "IDEMPOTENCY_CONFLICT" -> PageReaderFailure.IdempotencyConflict
        "DASHBOARD_QUERY_UNAUTHORIZED" -> PageReaderFailure.NotFound
        "EXECUTION_BACKPRESSURED" -> PageReaderFailure.Backpressured
        else -> PageReaderFailure.InvalidRequest
        })
    }
}

internal fun missing(): Nothing = throw PageReaderProblem(PageReaderFailure.NotFound)
internal fun invalid(): Nothing = throw PageReaderProblem(PageReaderFailure.InvalidRequest)
internal fun uuid(raw: String): Uuid {
    val value = try { Uuid.parse(raw) } catch (_: IllegalArgumentException) { invalid() }
    if (value.toString() != raw) invalid()
    return value
}

enum class PageReaderFailure { NotFound, Conflict, IdempotencyConflict, Backpressured, Unavailable, InvalidCursor, InvalidRequest, Unauthorized }
class PageReaderProblem(val failure: PageReaderFailure) : RuntimeException(failure.name)

suspend fun PagesCurrentReadPort.requirePlacedDashboardPage(workspace: WorkspaceId, pageId: Uuid, dashboardId: DashboardId): CurrentPage =
    tree(workspace.value).requirePlacedDashboardPage(pageId, dashboardId)

fun CurrentPagesTree.requirePlacedDashboardPage(pageId: Uuid, dashboardId: DashboardId): CurrentPage {
    if (placements.path(pageId) == null) missing()
    return pages.firstOrNull { it.pageId == pageId && it.kind == "DASHBOARD" && it.dashboardId == dashboardId.value } ?: missing()
}

class PageReaderOwnerRejected(val code: String, val detail: String) : RuntimeException(code)
