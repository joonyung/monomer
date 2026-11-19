package cc.monomer.control.auth.inbound.graphql

import cc.monomer.identity.application.grant.WorkspaceAuthorizationAssignments
import cc.monomer.identity.application.grant.WorkspaceAuthorizationAssignmentsUseCase
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import kotlin.uuid.Uuid
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.stereotype.Controller

@Controller
class WorkspaceAuthorizationAssignmentsResolver(
    private val workspaceAuthorizationAssignmentsUseCase: WorkspaceAuthorizationAssignmentsUseCase,
) {

    @QueryMapping
    suspend fun workspaceAuthorizationAssignments(
        @Argument workspaceId: String,
    ): WorkspaceAuthorizationAssignmentsView {
        val authentication = ReactiveSecurityContextHolder.getContext()
            .map { securityContext -> checkNotNull(securityContext.authentication) }
            .awaitSingle()
        return WorkspaceAuthorizationAssignmentsView.from(
            workspaceAuthorizationAssignmentsUseCase.read(
                WorkspaceAuthorizationAssignmentsUseCase.Command(
                    actorUserId = UserId(Uuid.parse(authentication.name)),
                    workspaceId = WorkspaceId(parseCanonicalUuid(workspaceId, "workspace id")),
                ),
            ),
        )
    }
}

data class WorkspaceAuthorizationAssignmentsView(
    val authorizationRevision: String,
    val members: List<WorkspaceMemberView>,
) {
    companion object {
        fun from(assignments: WorkspaceAuthorizationAssignments): WorkspaceAuthorizationAssignmentsView =
            WorkspaceAuthorizationAssignmentsView(
                authorizationRevision = assignments.authorizationRevision.toString(),
                members = assignments.members.map(WorkspaceMemberView::from),
            )
    }
}
