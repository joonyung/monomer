@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.semantic.inbound.rest

import cc.monomer.control.human.auth.toHumanHttpException
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.WorkspaceRoleAdmissionRequest
import cc.monomer.control.auth.inbound.BearerAuthorization
import cc.monomer.control.auth.inbound.BearerCredentials
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.api.AuthorizedMutationContext
import cc.monomer.identity.api.MutationIntent
import cc.monomer.control.AdmissionRequest
import cc.monomer.control.MutationAdmissionRequest
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

class SemanticWorkspaceRequestAuthorizer(
    private val controlAdmissionFacade: ControlAdmissionFacade,
) {
    suspend fun authorizeExplorer(
        exchange: ServerWebExchange,
        workspaceId: WorkspaceId,
    ) {
        when (
            val decision = controlAdmissionFacade.authorizeWorkspaceRole(
                WorkspaceRoleAdmissionRequest(
                    workspaceId = workspaceId,
                    credential = exchange.requiredBearerCredential(),
                    expectedOAuthResource = null,
                    requiredRole = WorkspaceRole.EXPLORER,
                ),
            )
        ) {
            is ControlAdmissionDecision.Admitted -> Unit
            is ControlAdmissionDecision.Denied -> throw decision.toHumanHttpException()
        }
    }

    suspend fun authorizeMutation(
        exchange: ServerWebExchange,
        workspaceId: WorkspaceId,
        intent: MutationIntent,
    ): AuthorizedMutationContext = when (
        val decision = controlAdmissionFacade.authorizeMutation(
            MutationAdmissionRequest(
                AdmissionRequest(workspaceId, exchange.requiredBearerCredential(), null),
                intent,
            ),
        )
    ) {
        is ControlAdmissionDecision.Admitted -> decision.context
        is ControlAdmissionDecision.Denied -> throw decision.toHumanHttpException()
    }

}

private fun ServerWebExchange.requiredBearerCredential(): PresentedCredential =
    when (val bearer = BearerCredentials.fromFirstAuthorizationHeader(request.headers)) {
        is BearerAuthorization.Presented -> bearer.credential
        BearerAuthorization.Missing -> throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer credential")
        BearerAuthorization.Malformed -> throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid bearer credential")
    }
