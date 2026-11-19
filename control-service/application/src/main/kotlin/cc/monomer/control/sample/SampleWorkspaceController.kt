package cc.monomer.control.sample

import cc.monomer.control.auth.inbound.oauth.JwtAuthentication
import cc.monomer.identity.api.*
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

@RestController
class SampleWorkspaceController(private val provisioning: SampleWorkspaceProvisioning) {
    @PostMapping("/v2/organizations/{organizationId}/sample-workspace")
    suspend fun start(@PathVariable organizationId: String, exchange: ServerWebExchange): ResponseEntity<SampleWorkspaceResponse> = ownerErrors {
        val actor = actor(exchange)
        val sample = provisioning.start(actor, organization(organizationId))
        ResponseEntity.status(if (sample.sample.state == WorkspaceSampleState.READY) HttpStatus.OK else HttpStatus.ACCEPTED)
            .header("Cache-Control", "no-store").body(sample.toResponse())
    }

    @GetMapping("/v2/organizations/{organizationId}/sample-workspace")
    suspend fun read(@PathVariable organizationId: String, exchange: ServerWebExchange): ResponseEntity<SampleWorkspaceResponse> = ownerErrors {
        val actor = actor(exchange)
        val sample = provisioning.read(actor, organization(organizationId))
            ?: throw SampleWorkspaceProblem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Sample workspace was not found.")
        ResponseEntity.ok().header("Cache-Control", "no-store").body(sample.toResponse())
    }

    private suspend fun actor(exchange: ServerWebExchange): PrincipalId = exchange.getPrincipal<JwtAuthentication>().awaitSingleOrNull()
        ?.let { PrincipalId.parse(it.name) }
        ?: throw SampleWorkspaceProblem(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication is required.")

    private fun organization(raw: String): OrganizationId = try {
        OrganizationId.parse(raw).also { require(it.toString() == raw) }
    } catch (_: IllegalArgumentException) {
        throw SampleWorkspaceProblem(HttpStatus.BAD_REQUEST, "INVALID_ORGANIZATION_ID", "Use a canonical Organization ID.")
    }

    private suspend fun <T> ownerErrors(action: suspend () -> T): T = try {
        action()
    } catch (_: SampleWorkspaceAdminRequiredException) {
        throw SampleWorkspaceProblem(HttpStatus.FORBIDDEN, "ROLE_REQUIRED", "Organization Admin is required.")
    } catch (_: SampleWorkspaceLimitExceededException) {
        throw SampleWorkspaceProblem(HttpStatus.CONFLICT, "WORKSPACE_LIMIT_REACHED", "The free Organization Workspace limit has been reached.")
    }
}

data class SampleWorkspaceResponse(val workspaceId: String, val organizationId: String, val displayName: String, val sample: SampleMarkerResponse)
data class SampleMarkerResponse(val dataset: String, val state: WorkspaceSampleState, val initiator: String)
private fun SampleWorkspace.toResponse() = SampleWorkspaceResponse(workspaceId.toString(), organizationId.toString(), displayName,
    SampleMarkerResponse(sample.dataset, sample.state, sample.initiator.toString()))
