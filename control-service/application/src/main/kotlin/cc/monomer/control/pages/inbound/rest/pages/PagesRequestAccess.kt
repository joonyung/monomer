package cc.monomer.control.pages.inbound.rest.pages

import cc.monomer.control.human.auth.toHumanHttpException

import cc.monomer.control.auth.inbound.oauth.JwtAuthentication
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.WorkspaceRoleAdmissionRequest
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.api.IdentityRoleEvidence
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.pages.domain.PageActor
import cc.monomer.pages.domain.PageActorKind
import cc.monomer.pages.domain.audit.AuditActorContext
import kotlin.uuid.Uuid
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

data class PagesAccess(
    val workspaceId: Uuid,
    val actor: PageActor,
    val auditActorContext: AuditActorContext,
    val credential: PresentedCredential,
)

interface PagesRequestAccess {
    suspend fun require(exchange: ServerWebExchange, rawWorkspaceId: String): PagesAccess
    suspend fun isCurrentExplorer(access: PagesAccess): Boolean
}

@Component
internal class WorkspacePagesRequestAccess(
    private val controlAdmissionFacade: ControlAdmissionFacade,
) : PagesRequestAccess {
    override suspend fun require(exchange: ServerWebExchange, rawWorkspaceId: String): PagesAccess {
        val workspaceId = canonicalWorkspaceId(rawWorkspaceId)
        val credential = presentedCredential(exchange)
        val evidence = when (val decision = explorerDecision(workspaceId, credential)) {
            is ControlAdmissionDecision.Admitted -> decision.context
            is ControlAdmissionDecision.Denied -> throw decision.toHumanHttpException()
        }
        return evidence.toPagesAccess(workspaceId.value, credential)
    }

    override suspend fun isCurrentExplorer(access: PagesAccess): Boolean =
        explorerDecision(WorkspaceId(access.workspaceId), access.credential) is ControlAdmissionDecision.Admitted

    private suspend fun explorerDecision(
        workspaceId: WorkspaceId,
        credential: PresentedCredential,
    ) = controlAdmissionFacade.authorizeWorkspaceRole(
        WorkspaceRoleAdmissionRequest(
            workspaceId = workspaceId,
            credential = credential,
            expectedOAuthResource = null,
            requiredRole = WorkspaceRole.EXPLORER,
        ),
    )

    private suspend fun presentedCredential(exchange: ServerWebExchange): PresentedCredential {
        val authentication = exchange.getPrincipal<JwtAuthentication>().awaitSingleOrNull()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.")
        return authentication.presentedCredential
    }

    private fun canonicalWorkspaceId(rawWorkspaceId: String): WorkspaceId {
        val workspaceId = runCatching { Uuid.parse(rawWorkspaceId) }
            .getOrElse { throw invalidWorkspaceId() }
        if (workspaceId.toString() != rawWorkspaceId) throw invalidWorkspaceId()
        return try {
            WorkspaceId(workspaceId)
        } catch (_: IllegalArgumentException) {
            throw invalidWorkspaceId()
        }
    }
}

private fun IdentityRoleEvidence.toPagesAccess(
    workspaceId: Uuid,
    credential: PresentedCredential,
): PagesAccess = PagesAccess(
    workspaceId = workspaceId,
    actor = PageActor.create(PageActorKind.USER, actor.id.value.toString()),
    auditActorContext = AuditActorContext(
        organizationId = organizationId.value,
        principalId = actor.id.value,
        actorRef = actor.id.value.toString(),
    ),
    credential = credential,
)

private fun invalidWorkspaceId(): PagesRestProblem = PagesRestProblem(
    HttpStatus.BAD_REQUEST,
    "INVALID_WORKSPACE_ID",
    null,
    null,
    emptyMap(),
    "Identifier must be a canonical UUID.",
)
