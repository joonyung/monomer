@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.publicapi

import cc.monomer.control.agent.toHttpValue

import cc.monomer.control.publicapi.AgentWorkspaceJson.agentResourceJson
import cc.monomer.control.publicapi.AgentWorkspaceJson.agentListingJson
import cc.monomer.control.publicapi.AgentWorkspaceJson.agentActionJson
import cc.monomer.control.publicapi.AgentWorkspaceJson.listingItemJson
import cc.monomer.control.publicapi.AgentWorkspaceJson.availableOperationJson
import cc.monomer.agent.api.AgentActionOutcome
import cc.monomer.agent.api.AgentActionResult
import cc.monomer.agent.api.AgentActionStatus
import cc.monomer.agent.api.AgentAvailableOperation
import cc.monomer.agent.api.AgentCreateRequest
import cc.monomer.agent.api.AgentEditRequest
import cc.monomer.agent.api.AgentInvokeRequest
import cc.monomer.agent.api.AgentListRequest
import cc.monomer.agent.api.AgentListingOutcome
import cc.monomer.agent.api.AgentReadRequest
import cc.monomer.agent.api.AgentResourceEnvelope
import cc.monomer.agent.api.AgentResourceListingResponse
import cc.monomer.agent.api.AgentResourceOutcome
import cc.monomer.agent.api.AgentResourcePath
import cc.monomer.agent.api.AgentWorkspaceAdmissionAudience
import cc.monomer.agent.api.AgentWorkspaceAdmissionDenial
import cc.monomer.agent.api.AgentWorkspaceCaller
import cc.monomer.agent.api.AgentWorkspaceCallerAuthenticationOutcome
import cc.monomer.agent.api.AgentWorkspaceHandler
import cc.monomer.agent.api.AgentWorkspaceProblem
import cc.monomer.agent.api.AgentWorkspaceProblemStatus
import cc.monomer.agent.api.AgentWorkspaceResponseRelations
import cc.monomer.agent.api.AgentWorkspaceRequestDecoderPort
import cc.monomer.agent.api.AgentWorkspaceRequestDecodingException
import cc.monomer.agent.api.AgentSearchOutcome
import cc.monomer.agent.api.AgentSearchRequest
import cc.monomer.agent.api.AgentRequestId
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

data class AgentWorkspaceHttpPorts(
    val callerAuthenticator: cc.monomer.agent.api.AgentWorkspaceCallerAuthenticationPort,
    val handler: AgentWorkspaceHandler,
    val requestDecoder: AgentWorkspaceRequestDecoderPort,
)

interface AgentWorkspaceTransportCredentialResolver {
    suspend fun resolve(request: ServerRequest): AgentWorkspaceTransportCredentialResolution
}

sealed interface AgentWorkspaceTransportCredentialResolution {
    data class Resolved(val credential: PresentedCredential) : AgentWorkspaceTransportCredentialResolution
    data object Missing : AgentWorkspaceTransportCredentialResolution
}

class AgentWorkspacePublicApiRouter(
    private val agentPorts: AgentWorkspaceHttpPorts,
    private val oauthResourceMetadataUrl: String,
    private val properties: ControlPublicApiProperties,
    private val credentialResolver: AgentWorkspaceTransportCredentialResolver,

    private val contractValidator: PublicControlContractValidator,
    private val oauth: cc.monomer.identity.api.AgentOAuthPort,
) {
    fun routes(): RouterFunction<ServerResponse> = RouterFunctions.route()
        .POST("/v2/workspaces/{workspaceId}/agent/list") { request -> list(request) }
        .POST("/v2/workspaces/{workspaceId}/agent/search") { request -> search(request) }
        .POST("/v2/workspaces/{workspaceId}/agent/read") { request -> read(request) }
        .POST("/v2/workspaces/{workspaceId}/agent/create") { request -> create(request) }
        .POST("/v2/workspaces/{workspaceId}/agent/edit") { request -> edit(request) }
        .POST("/v2/workspaces/{workspaceId}/agent/invoke") { request -> invoke(request) }
        .build()

    fun organizationRoutes(
        oauth: cc.monomer.identity.api.AgentOAuthPort,
        owner: cc.monomer.agent.api.AgentManagementOwnerPort,
    ): RouterFunction<ServerResponse> = RouterFunctions.route()
        .POST("/v2/agent/list") { request -> discovery(request, oauth) }
        .POST("/v2/agent/organizations/{organizationId}/list") { request -> organization(request, oauth, owner, AgentHttpRoute.LIST) }
        .POST("/v2/agent/organizations/{organizationId}/read") { request -> organization(request, oauth, owner, AgentHttpRoute.READ) }
        .POST("/v2/agent/organizations/{organizationId}/create") { request -> organization(request, oauth, owner, AgentHttpRoute.CREATE) }
        .POST("/v2/agent/organizations/{organizationId}/invoke") { request -> organization(request, oauth, owner, AgentHttpRoute.INVOKE) }
        .build()

    private fun discovery(request: ServerRequest, oauth: cc.monomer.identity.api.AgentOAuthPort): Mono<ServerResponse> = mono(org.springframework.core.PropagationContextElement()) {
        val requestId = requestId(request)
        validateRequest(request)?.let { return@mono it }
        val credential = credentialResolver.resolve(request) as? AgentWorkspaceTransportCredentialResolution.Resolved
            ?: return@mono authenticationProblem(request, requestId, AgentAuthentication.Rejected.MissingCredential)
        try {
            val identity = oauth.validateCredential(credential.credential, cc.monomer.identity.api.ExpectedOAuthResource.AGENT_REST)
            if (identity !is cc.monomer.identity.api.OAuthOutcome.Success)
                return@mono authenticationProblem(request, requestId, AgentAuthentication.Rejected.MissingCredential)
            cc.monomer.control.observability.RequestLogContext.agent(identity.value.clientId)
            val input = decodeRequest { objectMapper.readTree(bodyText(request)) } ?: return@mono invalidRequest(request, requestId)
            if (!input.isObject || input.size() != 1 || !input.path("path").isTextual) return@mono invalidRequest(request, requestId)
            val outcome = when (input.path("path").asText()) {
                "organizations" -> when (val result = oauth.listOrganizations(credential.credential, cc.monomer.identity.api.ExpectedOAuthResource.AGENT_REST)) {
                    is cc.monomer.identity.api.OAuthOutcome.Success -> AgentWorkspaceJson.organizationsJson(result.value)
                    is cc.monomer.identity.api.OAuthOutcome.Failure -> return@mono authenticationProblem(request, requestId, AgentAuthentication.Rejected.MissingCredential)
                }
                "workspaces" -> when (val result = oauth.listWorkspaces(credential.credential, cc.monomer.identity.api.ExpectedOAuthResource.AGENT_REST)) {
                    is cc.monomer.identity.api.OAuthOutcome.Success -> AgentWorkspaceJson.workspacesJson(result.value)
                    is cc.monomer.identity.api.OAuthOutcome.Failure -> return@mono authenticationProblem(request, requestId, AgentAuthentication.Rejected.MissingCredential)
                }
                else -> return@mono invalidRequest(request, requestId)
            }
            val bytes = ownerProjection { objectMapper.writeValueAsBytes(outcome) }
            ServerResponse.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(bytes).awaitSingle()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: AgentWorkspaceRequestDecodingException) { invalidRequest(request, requestId)
        } catch (failure: OwnerResponseInvalid) {
            logAgentTransportFailure("REST", requestId, failure)
            invalidOwnerResponse(request, requestId)
        } catch (failure: IllegalArgumentException) {
            logAgentTransportFailure("REST", requestId, failure)
            internalFailure(request, requestId)
        } catch (failure: Exception) {
            logAgentTransportFailure("REST", requestId, failure)
            unavailable(request, requestId)
        }
    }

    private fun organization(
        request: ServerRequest,
        oauth: cc.monomer.identity.api.AgentOAuthPort,
        owner: cc.monomer.agent.api.AgentManagementOwnerPort,
        route: AgentHttpRoute,
    ): Mono<ServerResponse> = mono(org.springframework.core.PropagationContextElement()) {
        val requestId = requestId(request)
        validateRequest(request)?.let { return@mono it }
        val credential = credentialResolver.resolve(request) as? AgentWorkspaceTransportCredentialResolution.Resolved
            ?: return@mono authenticationProblem(request, requestId, AgentAuthentication.Rejected.MissingCredential)
        val organizationId = runCatching { request.pathVariable("organizationId").let { raw -> cc.monomer.identity.api.OrganizationId(kotlin.uuid.Uuid.parse(raw)).takeIf { it.value.toString() == raw } } }.getOrNull()
            ?: return@mono malformedPath(request, requestId)
        try {
            val caller = when (val result = cc.monomer.agent.api.AgentOrganizationCaller.authenticate(organizationId, oauth, credential.credential, AgentWorkspaceAdmissionAudience.REST, requestId)) {
                is cc.monomer.identity.api.OAuthOutcome.Success -> result.value
                is cc.monomer.identity.api.OAuthOutcome.Failure -> return@mono authenticationProblem(request, requestId, AgentAuthentication.Rejected.MissingCredential)
            }
            cc.monomer.control.observability.RequestLogContext.agent(caller.clientId)
            val body = bodyText(request)
            when (route) {
                AgentHttpRoute.LIST -> {
                    val input = decodeRequest { agentPorts.requestDecoder.list(body) }
                    if (!cc.monomer.agent.api.AgentManagementPaths.organization(input.path.value)) throw AgentWorkspaceRequestDecodingException("path", "Use an Organization resource path.")
                    listingOutcome(request, requestId, input, owner.list(caller, input))
                }
                AgentHttpRoute.READ -> {
                    val input = decodeRequest { agentPorts.requestDecoder.read(body) }
                    if (!cc.monomer.agent.api.AgentManagementPaths.organization(input.path.value)) throw AgentWorkspaceRequestDecodingException("path", "Use an Organization resource path.")
                    when (val outcome = owner.read(caller, input)) {
                        is AgentResourceOutcome.Found -> if (AgentWorkspaceResponseRelations.matches(input, outcome.resource) && (outcome.resource !is AgentResourceEnvelope.Organization || (outcome.resource as AgentResourceEnvelope.Organization).body.id == caller.organizationId.value)) successResource(requestId, outcome.resource) else invalidOwnerResponse(request, requestId)
                        is AgentResourceOutcome.Rejected -> ownerProblem(request, requestId, route, outcome.problem)
                        AgentResourceOutcome.NotFound -> notFound(request, requestId)
                        AgentResourceOutcome.Forbidden -> forbidden(request, requestId)
                        AgentResourceOutcome.Unavailable -> unavailable(request, requestId)
                    }
                }
                AgentHttpRoute.CREATE -> {
                    val input = decodeRequest { agentPorts.requestDecoder.create(body).value }
                    if (!cc.monomer.agent.api.AgentManagementPaths.organization(input.path.value)) throw AgentWorkspaceRequestDecodingException("path", "Use an Organization resource path.")
                    actionOutcome(request, requestId, route, input, owner.create(caller, input))
                }
                AgentHttpRoute.INVOKE -> {
                    val input = decodeRequest { agentPorts.requestDecoder.invoke(body).value }
                    if (!cc.monomer.agent.api.AgentManagementPaths.organization(input.path.value)) throw AgentWorkspaceRequestDecodingException("path", "Use an Organization resource path.")
                    actionOutcome(request, requestId, route, input, owner.invoke(caller, input))
                }
                else -> error("Unsupported Organization operation")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AgentWorkspaceRequestDecodingException) {
            invalidRequest(request, requestId, failure.violations)
        } catch (failure: OwnerResponseInvalid) {
            logAgentTransportFailure("REST", requestId, failure)
            invalidOwnerResponse(request, requestId)
        } catch (failure: IllegalArgumentException) {
            logAgentTransportFailure("REST", requestId, failure)
            internalFailure(request, requestId)
        } catch (failure: Exception) {
            logAgentTransportFailure("REST", requestId, failure)
            unavailable(request, requestId)
        }
    }

    private fun list(request: ServerRequest): Mono<ServerResponse> = workspaceRoute(
        request,
        agentPorts.requestDecoder::list,
        AgentListRequest::path,
        { workspaceId, credential, requestId, input ->
            agentPorts.callerAuthenticator.authenticate(workspaceId, credential, AgentWorkspaceAdmissionAudience.REST, input, requestId)
        },
    ) { requestId, caller, input -> listingOutcome(request, requestId, input, agentPorts.handler.list(caller, input)) }

    private fun search(request: ServerRequest): Mono<ServerResponse> = workspaceRoute(
        request,
        agentPorts.requestDecoder::search,
        AgentSearchRequest::path,
        { workspaceId, credential, requestId, input ->
            agentPorts.callerAuthenticator.authenticate(workspaceId, credential, AgentWorkspaceAdmissionAudience.REST, input, requestId)
        },
    ) { requestId, caller, input -> resourceOutcome(request, requestId, AgentHttpRoute.SEARCH, input, agentPorts.handler.search(caller, input)) }

    private fun read(request: ServerRequest): Mono<ServerResponse> = workspaceRoute(
        request,
        agentPorts.requestDecoder::read,
        AgentReadRequest::path,
        { workspaceId, credential, requestId, input ->
            agentPorts.callerAuthenticator.authenticate(workspaceId, credential, AgentWorkspaceAdmissionAudience.REST, input, requestId)
        },
    ) { requestId, caller, input ->
        resourceOutcome(request, requestId, caller.workspaceId, AgentHttpRoute.READ, input, agentPorts.handler.read(caller, input))
    }

    private fun create(request: ServerRequest): Mono<ServerResponse> = workspaceRoute(
        request,
        { body -> agentPorts.requestDecoder.create(body).value },
        AgentCreateRequest::path,
        { workspaceId, credential, requestId, input ->
            agentPorts.callerAuthenticator.authenticate(workspaceId, credential, AgentWorkspaceAdmissionAudience.REST, input, requestId)
        },
    ) { requestId, caller, input -> actionOutcome(request, requestId, AgentHttpRoute.CREATE, input, agentPorts.handler.create(caller, input)) }

    private fun edit(request: ServerRequest): Mono<ServerResponse> = workspaceRoute(
        request,
        { body -> agentPorts.requestDecoder.edit(body).value },
        AgentEditRequest::path,
        { workspaceId, credential, requestId, input ->
            agentPorts.callerAuthenticator.authenticate(workspaceId, credential, AgentWorkspaceAdmissionAudience.REST, input, requestId)
        },
    ) { requestId, caller, input -> actionOutcome(request, requestId, AgentHttpRoute.EDIT, input, agentPorts.handler.edit(caller, input)) }

    private fun invoke(request: ServerRequest): Mono<ServerResponse> = workspaceRoute(
        request,
        { body -> agentPorts.requestDecoder.invoke(body).value },
        AgentInvokeRequest::path,
        { workspaceId, credential, requestId, input ->
            agentPorts.callerAuthenticator.authenticate(workspaceId, credential, AgentWorkspaceAdmissionAudience.REST, input, requestId)
        },
    ) { requestId, caller, input -> actionOutcome(request, requestId, AgentHttpRoute.INVOKE, input, agentPorts.handler.invoke(caller, input)) }

    private fun <Input : Any> workspaceRoute(
        request: ServerRequest,
        decode: (String) -> Input,
        path: (Input) -> AgentResourcePath,
        authenticateCaller: suspend (WorkspaceId, PresentedCredential, AgentRequestId, Input) -> AgentWorkspaceCallerAuthenticationOutcome,
        respond: suspend (AgentRequestId, AgentWorkspaceCaller, Input) -> ServerResponse,
    ): Mono<ServerResponse> = mono(org.springframework.core.PropagationContextElement()) {
        val requestId = requestId(request)
        validateRequest(request)?.let { return@mono it }
        val credential = credentialResolver.resolve(request) as? AgentWorkspaceTransportCredentialResolution.Resolved
            ?: return@mono authenticationProblem(request, requestId, AgentAuthentication.Rejected.MissingCredential)
        val identity = try {
            oauth.validateCredential(credential.credential, cc.monomer.identity.api.ExpectedOAuthResource.AGENT_REST)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IllegalArgumentException) {
            logAgentTransportFailure("REST", requestId, failure)
            return@mono internalFailure(request, requestId)
        } catch (failure: Exception) {
            logAgentTransportFailure("REST", requestId, failure)
            return@mono unavailable(request, requestId)
        }
        if (identity !is cc.monomer.identity.api.OAuthOutcome.Success)
            return@mono authenticationProblem(request, requestId, AgentAuthentication.Rejected.MissingCredential)
        cc.monomer.control.observability.RequestLogContext.agent(identity.value.clientId)
        val workspaceId = parseWorkspaceId(request) ?: return@mono malformedPath(request, requestId)
        val input = try {
            decode(bodyText(request))
        } catch (exception: AgentWorkspaceRequestDecodingException) {
            return@mono invalidRequest(request, requestId, exception.violations)
        } catch (_: IllegalArgumentException) {
            return@mono invalidRequest(request, requestId)
        }
        if (cc.monomer.agent.api.AgentManagementPaths.organization(path(input).value)) return@mono invalidRequest(request, requestId)
        ownerExchange(request, requestId, input) {
            val authentication = authenticate(credential.credential) { presented -> authenticateCaller(workspaceId, presented, requestId, input) }
            val caller = when (authentication) {
                is AgentAuthentication.Authenticated -> authentication.caller
                is AgentAuthentication.Rejected -> return@ownerExchange authenticationProblem(request, requestId, authentication)
            }
            respond(requestId, caller, input)
        }
    }

    private suspend fun ownerExchange(
        request: ServerRequest,
        requestId: AgentRequestId,
        input: Any,
        exchange: suspend () -> ServerResponse,
    ): ServerResponse = try {
        exchange()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: OwnerResponseInvalid) {
        logAgentTransportFailure("REST", requestId, failure)
        problem(request, requestId, AgentTransportProblems.ownerResponseInvalid(input))
    } catch (failure: cc.monomer.semantic.application.SemanticMetricFlowEngineFaultException) {
        logAgentTransportFailure("REST", requestId, failure)
        internalFailure(request, requestId)
    } catch (failure: IllegalArgumentException) {
        logAgentTransportFailure("REST", requestId, failure)
        internalFailure(request, requestId)
    } catch (failure: Exception) {
        logAgentTransportFailure("REST", requestId, failure)
        problem(request, requestId, AgentTransportProblems.ownerUnavailable(input))
    }

    private inline fun <Input> decodeRequest(decode: () -> Input): Input = try {
        decode()
    } catch (failure: AgentWorkspaceRequestDecodingException) {
        throw failure
    } catch (_: IllegalArgumentException) {
        throw AgentWorkspaceRequestDecodingException("request", "The request does not match its declared contract.")
    } catch (_: com.fasterxml.jackson.core.JsonProcessingException) {
        throw AgentWorkspaceRequestDecodingException("request", "The request body is invalid JSON.")
    }

    private suspend fun internalFailure(request: ServerRequest, requestId: AgentRequestId): ServerResponse =
        problem(request, requestId, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal Server Error",
            "The Agent request could not be completed. Read the target resource before resending a create or start request.", false, null)

    private suspend fun validateRequest(request: ServerRequest): ServerResponse? {
        if (request.queryParams().isNotEmpty()) {
            return problem(request, requestId(request), HttpStatus.BAD_REQUEST, "UNDECLARED_QUERY_PARAMETER", "Bad Request", "The request contains an undeclared query parameter.", false, null)
        }
        val values = request.headers().header(HttpHeaders.CONTENT_TYPE)
        val contentType = values.singleOrNull()?.let { runCatching { MediaType.parseMediaType(it) }.getOrNull() }
        return if (contentType?.type.equals("application", true) && contentType?.subtype.equals("json", true)) {
            null
        } else {
            problem(request, requestId(request), HttpStatus.BAD_REQUEST, "AGENT_REQUEST_CONTENT_TYPE_INVALID", "Bad Request", "Agent workspace requests require Content-Type application/json.", false, null)
        }
    }

    private suspend fun bodyText(request: ServerRequest): String {
        try {
            val bytes = request.bodyToMono(ByteArray::class.java).defaultIfEmpty(ByteArray(0)).awaitSingle()
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            throw AgentWorkspaceRequestDecodingException("request", "The request body must be valid UTF-8.")
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            throw AgentWorkspaceRequestDecodingException("request", "The request body is invalid.")
        }
    }

    private suspend fun authenticate(
        credential: PresentedCredential,
        authenticate: suspend (PresentedCredential) -> AgentWorkspaceCallerAuthenticationOutcome,
    ): AgentAuthentication {
        return when (val outcome = authenticate(credential)) {
            is AgentWorkspaceCallerAuthenticationOutcome.Authenticated -> {
                cc.monomer.control.observability.RequestLogContext.agent(outcome.caller.agentClientId.value)
                AgentAuthentication.Authenticated(outcome.caller)
            }
            is AgentWorkspaceCallerAuthenticationOutcome.Denied -> AgentAuthentication.Rejected.Denied(outcome.reason, outcome.problem)
            AgentWorkspaceCallerAuthenticationOutcome.InvalidRequest -> AgentAuthentication.Rejected.InvalidRequest
            AgentWorkspaceCallerAuthenticationOutcome.EvidenceMismatch -> AgentAuthentication.Rejected.EvidenceMismatch
        }
    }

    private suspend fun resourceOutcome(
        request: ServerRequest,
        requestId: AgentRequestId,
        workspaceId: WorkspaceId,
        route: AgentHttpRoute,
        input: AgentReadRequest,
        outcome: AgentResourceOutcome,
    ): ServerResponse = when (outcome) {
        is AgentResourceOutcome.Found -> {
            val valid = AgentWorkspaceResponseRelations.matches(input, outcome.resource) &&
                AgentWorkspaceResponseRelations.belongsToWorkspace(workspaceId, outcome.resource)
            if (valid) successResource(requestId, outcome.resource) else invalidOwnerResponse(request, requestId)
        }
        is AgentResourceOutcome.Rejected -> ownerProblem(request, requestId, route, outcome.problem)
        AgentResourceOutcome.NotFound -> notFound(request, requestId)
        AgentResourceOutcome.Forbidden -> forbidden(request, requestId)
        AgentResourceOutcome.Unavailable -> unavailable(request, requestId)
    }

    private suspend fun resourceOutcome(
        request: ServerRequest,
        requestId: AgentRequestId,
        route: AgentHttpRoute,
        input: AgentSearchRequest,
        outcome: AgentSearchOutcome,
    ): ServerResponse = when (outcome) {
        is AgentSearchOutcome.Found -> {
            val results = outcome.response.results
            val valid = results.map { it.query } == input.queries && results.all { result ->
                result.items.size <= input.limitPerQuery &&
                    result.items.map { it.path }.distinct().size == result.items.size &&
                    result.items.all { item ->
                        item.kind in (input.kinds ?: AgentSearchRequest.SUPPORTED_KINDS) &&
                            (item.path == input.path || item.path.value.startsWith("${input.path.value}/"))
                    }
            }
            if (!valid) invalidOwnerResponse(request, requestId)
            else {
                val body = ownerProjection { objectMapper.writeValueAsBytes(contractValidator.agentSearch(mapOf(
                    "requestId" to requestId.value,
                    "results" to results.map { result -> mapOf("query" to result.query, "items" to result.items.map(::listingItemJson)) },
                ))) }
                ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                    .bodyValue(body).awaitSingle()
            }
        }
        is AgentSearchOutcome.Rejected -> ownerProblem(request, requestId, route, outcome.problem)
        AgentSearchOutcome.NotFound -> notFound(request, requestId)
    }

    private suspend fun listingOutcome(
        request: ServerRequest,
        requestId: AgentRequestId,
        input: AgentListRequest,
        outcome: AgentListingOutcome,
    ): ServerResponse = when (outcome) {
        is AgentListingOutcome.Found -> successListing(requestId, outcome.listing)
        is AgentListingOutcome.Rejected -> ownerProblem(request, requestId, AgentHttpRoute.LIST, outcome.problem)
        AgentListingOutcome.NotFound -> notFound(request, requestId)
        AgentListingOutcome.Forbidden -> forbidden(request, requestId)
        AgentListingOutcome.Unavailable -> unavailable(request, requestId)
    }

    private suspend fun actionOutcome(
        request: ServerRequest,
        requestId: AgentRequestId,
        route: AgentHttpRoute,
        input: Any,
        outcome: AgentActionOutcome,
    ): ServerResponse = when (outcome) {
        is AgentActionOutcome.Completed -> {
            val matches = when (input) {
                is AgentCreateRequest -> AgentWorkspaceResponseRelations.matches(input, outcome.action)
                is AgentEditRequest -> AgentWorkspaceResponseRelations.matches(input, outcome.action)
                is AgentInvokeRequest -> AgentWorkspaceResponseRelations.matches(input, outcome.action)
                else -> false
            }
            val statusMatchesRoute = outcome.action.status == AgentActionStatus.COMPLETED ||
                route == AgentHttpRoute.INVOKE && outcome.action.status == AgentActionStatus.ACCEPTED
            if (matches && statusMatchesRoute) successAction(requestId, outcome.action, parseWorkspaceId(request))
            else problem(request, requestId, AgentTransportProblems.ownerResponseInvalid(input))
        }
        is AgentActionOutcome.Rejected -> ownerProblem(request, requestId, route, outcome.problem)
        AgentActionOutcome.NotFound -> notFound(request, requestId)
        AgentActionOutcome.Forbidden -> forbidden(request, requestId)
        AgentActionOutcome.Unavailable -> unavailable(request, requestId)
    }

    private suspend fun successResource(requestId: AgentRequestId, resource: AgentResourceEnvelope): ServerResponse {
        val body = ownerProjection { objectMapper.writeValueAsBytes(contractValidator.agentResource(agentResourceJson(requestId, resource))) }
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
            .bodyValue(body).awaitSingle()
    }

    private suspend fun successListing(requestId: AgentRequestId, listing: AgentResourceListingResponse): ServerResponse {
        val body = ownerProjection { objectMapper.writeValueAsBytes(contractValidator.agentListing(agentListingJson(requestId, listing))) }
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
            .bodyValue(body).awaitSingle()
    }

    private suspend fun successAction(requestId: AgentRequestId, action: AgentActionResult, workspaceId: WorkspaceId?): ServerResponse {
        val body = ownerProjection { objectMapper.writeValueAsBytes(contractValidator.agentAction(agentActionJson(requestId, action, workspaceId, properties.webOrigin))) }
        val response = when (action.status) {
            AgentActionStatus.COMPLETED -> ServerResponse.ok()
            AgentActionStatus.ACCEPTED -> ServerResponse.accepted()
                .header(HttpHeaders.RETRY_AFTER, AgentTransportProblems.RETRY_AFTER_SECONDS.toString())
        }
        return response.contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
            .bodyValue(body).awaitSingle()
    }

    private inline fun <Projection> ownerProjection(project: () -> Projection): Projection = try {
        project()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw OwnerResponseInvalid(failure)
    }

    private fun parseWorkspaceId(request: ServerRequest): WorkspaceId? = runCatching {
        request.pathVariable("workspaceId").let { raw -> WorkspaceId.parse(raw).takeIf { it.value.toString() == raw } }
    }.getOrNull()

    private fun requestId(request: ServerRequest): AgentRequestId = AgentRequestId(request.exchange().request.id)

    private suspend fun authenticationFailure(request: ServerRequest, requestId: AgentRequestId): ServerResponse =
        problem(request, requestId, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Unauthorized", "A valid credential is required.", false, null)

    private suspend fun authenticationProblem(
        request: ServerRequest,
        requestId: AgentRequestId,
        authentication: AgentAuthentication.Rejected,
    ): ServerResponse = when (authentication) {
        AgentAuthentication.Rejected.MissingCredential -> authenticationFailure(request, requestId)
        is AgentAuthentication.Rejected.Denied -> when (authentication.reason) {
            AgentWorkspaceAdmissionDenial.UNAUTHENTICATED -> authenticationFailure(request, requestId)
            AgentWorkspaceAdmissionDenial.NOT_FOUND -> notFound(request, requestId)
            AgentWorkspaceAdmissionDenial.FORBIDDEN -> authentication.problem?.let { problem(request, requestId, it) } ?: forbidden(request, requestId)
            AgentWorkspaceAdmissionDenial.UNAVAILABLE -> unavailable(request, requestId)
        }
        AgentAuthentication.Rejected.InvalidRequest -> invalidRequest(request, requestId)
        AgentAuthentication.Rejected.EvidenceMismatch -> problem(request, requestId, AgentTransportProblems.authorizationEvidenceMismatch)
    }

    private suspend fun malformedPath(request: ServerRequest, requestId: AgentRequestId): ServerResponse =
        problem(request, requestId, HttpStatus.BAD_REQUEST, "INVALID_PATH_PARAMETER", "Bad Request", "The request path contains an invalid identifier.", false, null)

    private suspend fun invalidRequest(request: ServerRequest, requestId: AgentRequestId): ServerResponse =
        invalidRequest(request, requestId, emptyList())

    private suspend fun invalidRequest(
        request: ServerRequest,
        requestId: AgentRequestId,
        violations: List<cc.monomer.agent.api.AgentProblemViolation>,
    ): ServerResponse = problem(request, requestId, AgentTransportProblems.requestInvalid(violations))

    private suspend fun forbidden(request: ServerRequest, requestId: AgentRequestId): ServerResponse =
        problem(request, requestId, AgentTransportProblems.forbidden)

    private suspend fun notFound(request: ServerRequest, requestId: AgentRequestId): ServerResponse =
        problem(request, requestId, AgentTransportProblems.resourceNotFound)

    private suspend fun unavailable(request: ServerRequest, requestId: AgentRequestId): ServerResponse =
        problem(request, requestId, AgentTransportProblems.ownerUnavailable)

    private suspend fun ownerProblem(
        request: ServerRequest,
        requestId: AgentRequestId,
        route: AgentHttpRoute,
        ownerProblem: AgentWorkspaceProblem,
    ): ServerResponse = if (ownerProblem.status !in route.declaredProblemStatuses) {
        invalidOwnerResponse(request, requestId)
    } else {
        problem(request, requestId, ownerProblem)
    }

    private suspend fun problem(request: ServerRequest, requestId: AgentRequestId, ownerProblem: AgentWorkspaceProblem): ServerResponse =
        problem(
            request,
            requestId,
            HttpStatusCode.valueOf(ownerProblem.status.value),
            ownerProblem.code,
            ownerProblem.title,
            ownerProblem.detail,
            ownerProblem.retryable,
            ownerProblem.currentRevision?.value,
            ownerProblem.violations,
            ownerProblem.hints,
            ownerProblem.nextActions,
            ownerProblem.retryAfterSeconds,
            ownerProblem.roleRequirement,
            ownerProblem.partitionFilters,
        )

    private suspend fun invalidOwnerResponse(request: ServerRequest, requestId: AgentRequestId): ServerResponse =
        problem(request, requestId, AgentTransportProblems.ownerResponseInvalid)

    private suspend fun problem(
        request: ServerRequest,
        requestId: AgentRequestId,
        status: HttpStatusCode,
        code: String,
        title: String,
        detail: String,
        retryable: Boolean,
        currentRevision: Long?,
    ): ServerResponse = problem(
        request,
        requestId,
        status,
        code,
        title,
        detail,
        retryable,
        currentRevision,
        emptyList(),
        emptyList(),
        emptyList(),
        null,
        null,
        emptyList(),
    )

    private suspend fun problem(
        request: ServerRequest,
        requestId: AgentRequestId,
        status: HttpStatusCode,
        code: String,
        title: String,
        detail: String,
        retryable: Boolean,
        currentRevision: Long?,
        violations: List<cc.monomer.agent.api.AgentProblemViolation>,
        hints: List<String>,
        nextActions: List<AgentAvailableOperation>,
        retryAfterSeconds: Long?,
        roleRequirement: cc.monomer.agent.api.AgentRoleRequirement?,
        partitionFilters: List<cc.monomer.agent.api.AgentPartitionFilter>,
    ): ServerResponse {
        cc.monomer.control.observability.RequestLogContext.problem(code)
        val body = linkedMapOf<String, Any?>(
            "type" to "https://monomer.cc/problems/${code.lowercase().replace('_', '-')}",
            "title" to title,
            "status" to status.value(),
            "detail" to detail,
            "instance" to request.path(),
            "code" to code,
            "requestId" to requestId.value,
            "traceId" to requestId.value,
            "retryable" to retryable,
        )
        if (currentRevision != null) body["current"] = mapOf("revision" to currentRevision.toString())
        if (violations.isNotEmpty()) {
            body["violations"] = violations.map { violation ->
                linkedMapOf<String, Any?>(
                    "field" to violation.field,
                    "code" to violation.code,
                    "message" to violation.message,
                ).apply {
                    putOptional("operationIndex", violation.operationIndex)
                    putOptional("path", violation.path)
                }
            }
        }
        if (hints.isNotEmpty()) body["hints"] = hints
        if (nextActions.isNotEmpty()) body["nextActions"] = nextActions.map(::availableOperationJson)
        if (partitionFilters.isNotEmpty()) body["partitionFilters"] = partitionFilters.map { it.toHttpValue() }
        if (roleRequirement != null) {
            body["requiredRole"] = roleRequirement.requiredRole
            body["currentRole"] = roleRequirement.currentRole
            body["askOwners"] = roleRequirement.askOwners
        }
        val response = ServerResponse.status(status).contentType(MediaType.valueOf("application/problem+json")).cacheControl(CacheControl.noStore())
        if (status == HttpStatus.UNAUTHORIZED) response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer resource_metadata=\"$oauthResourceMetadataUrl\", scope=\"agent.workspace\"")
        AgentTransportProblems.retryAfterSeconds(status.value(), retryable, retryAfterSeconds)?.let {
            response.header(HttpHeaders.RETRY_AFTER, it.toString())
        }
        val validatedBody = ownerProjection { contractValidator.problem(body) }
        return response.bodyValue(validatedBody).awaitSingle()
    }

    private fun MutableMap<String, Any?>.putOptional(key: String, value: Any?) { if (value != null) put(key, value) }

    private class OwnerResponseInvalid(cause: Exception) : RuntimeException(cause)

    private sealed interface AgentAuthentication {
        data class Authenticated(val caller: AgentWorkspaceCaller) : AgentAuthentication

        sealed interface Rejected : AgentAuthentication {
            data object MissingCredential : Rejected
            data class Denied(val reason: AgentWorkspaceAdmissionDenial, val problem: AgentWorkspaceProblem?) : Rejected
            data object InvalidRequest : Rejected
            data object EvidenceMismatch : Rejected
        }
    }

    private enum class AgentHttpRoute {
        LIST, SEARCH, READ, CREATE, EDIT, INVOKE;
        val declaredProblemStatuses: Set<AgentWorkspaceProblemStatus>
            get() = if (this == SEARCH) BASE else BASE + setOf(AgentWorkspaceProblemStatus.CONFLICT, AgentWorkspaceProblemStatus.TOO_MANY_REQUESTS)
        companion object {
            val BASE = setOf(AgentWorkspaceProblemStatus.BAD_REQUEST, AgentWorkspaceProblemStatus.UNAUTHORIZED, AgentWorkspaceProblemStatus.FORBIDDEN, AgentWorkspaceProblemStatus.NOT_FOUND, AgentWorkspaceProblemStatus.SERVICE_UNAVAILABLE)
        }
    }

    private companion object {
        val objectMapper = ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    }
}
