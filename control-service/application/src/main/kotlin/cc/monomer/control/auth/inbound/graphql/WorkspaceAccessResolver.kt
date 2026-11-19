package cc.monomer.control.auth.inbound.graphql

import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.WorkspaceMembershipAdmissionRequest
import cc.monomer.control.auth.inbound.oauth.JwtAuthentication
import cc.monomer.identity.application.permission.WorkspaceAccessService
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.port.WorkspaceAccessQueryResult
import kotlin.uuid.Uuid
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.stereotype.Controller

@Controller
class WorkspaceAccessResolver(
    private val workspaceAccessService: WorkspaceAccessService,
    private val controlAdmissionFacade: ControlAdmissionFacade,
) {
    @QueryMapping
    suspend fun workspaceAccess(@Argument workspaceId: String): WorkspaceAccessView {
        val auth = ReactiveSecurityContextHolder.getContext()
            .map { securityContext -> checkNotNull(securityContext.authentication) }
            .awaitSingle()
        val jwt = auth as? JwtAuthentication ?: return denied()
        val parsedWorkspaceId = parseCanonicalWorkspaceId(workspaceId)
        val admission = controlAdmissionFacade.authorizeWorkspaceMembership(
            WorkspaceMembershipAdmissionRequest(parsedWorkspaceId, jwt.presentedCredential, null),
        )
        if (admission is ControlAdmissionDecision.Denied) return denied()
        return when (val result = workspaceAccessService.read(UserId(Uuid.parse(jwt.name)), WorkspaceId(parsedWorkspaceId.value))) {
            is WorkspaceAccessQueryResult.Denied -> denied()
            is WorkspaceAccessQueryResult.Authorized -> WorkspaceAccessView(
                granted = true,
                organizationId = result.authorization.organizationId.toString(),
                role = result.projection.role.name,
                owners = result.projection.owners.map { owner ->
                    WorkspaceOwnerSummaryView(userId = owner.userId.toString(), displayName = owner.displayName)
                },
            )
        }
    }

    private fun denied() = WorkspaceAccessView(false, null, null, emptyList())
}

data class WorkspaceAccessView(
    val granted: Boolean,
    val organizationId: String?,
    val role: String?,
    val owners: List<WorkspaceOwnerSummaryView>,
)

data class WorkspaceOwnerSummaryView(
    val userId: String,
    val displayName: String,
)
