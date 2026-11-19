package cc.monomer.control.human.auth

import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionDenial
import cc.monomer.identity.api.IdentityAdmissionDenial
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

internal fun ControlAdmissionDecision.Denied.toHumanHttpException(): ResponseStatusException {
    val status = when (val denial = reason) {
        is ControlAdmissionDenial.Identity -> when (denial.reason) {
            IdentityAdmissionDenial.CREDENTIAL_UNAVAILABLE,
            IdentityAdmissionDenial.CREDENTIAL_EXPIRED,
            IdentityAdmissionDenial.CREDENTIAL_REVOKED,
            IdentityAdmissionDenial.PRINCIPAL_MISMATCH,
            IdentityAdmissionDenial.AGENT_BINDING_MISMATCH,
            IdentityAdmissionDenial.AGENT_SCOPE_INSUFFICIENT -> HttpStatus.UNAUTHORIZED
            IdentityAdmissionDenial.WORKSPACE_UNAVAILABLE,
            IdentityAdmissionDenial.ORGANIZATION_MEMBERSHIP_UNAVAILABLE,
            IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE -> HttpStatus.NOT_FOUND
            IdentityAdmissionDenial.PERMISSION_UNAVAILABLE -> HttpStatus.FORBIDDEN
        }
        ControlAdmissionDenial.IDENTITY_EVIDENCE_MISMATCH,
        ControlAdmissionDenial.INVALID_EXECUTION_TARGET -> HttpStatus.BAD_REQUEST
    }
    val code = when (status) {
        HttpStatus.UNAUTHORIZED -> "AUTHENTICATION_REQUIRED"
        HttpStatus.NOT_FOUND -> "NOT_FOUND"
        HttpStatus.FORBIDDEN -> "ROLE_REQUIRED"
        else -> "INVALID_REQUEST"
    }
    val detail = when (status) {
        HttpStatus.UNAUTHORIZED -> "Authentication is required."
        HttpStatus.NOT_FOUND -> "The resource was not found."
        HttpStatus.FORBIDDEN -> "The required workspace role is missing."
        else -> "The authorization request is invalid."
    }
    return ResponseStatusException(status, detail).also { it.body.setProperty("code", code) }
}
