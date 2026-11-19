package cc.monomer.control.auth.inbound.agentoauth

import cc.monomer.control.auth.inbound.PendingAuthenticationBrowser
import cc.monomer.identity.application.OpaqueCredentials
import cc.monomer.identity.application.pending.*
import cc.monomer.control.ControlRuntimeProperties
import cc.monomer.control.auth.inbound.oauth.JwtAuthentication
import cc.monomer.control.publicapi.ControlPublicApiProperties
import cc.monomer.identity.api.*
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange

@RestController
class AgentOAuthController(
    private val oauth: AgentOAuthPort,
    private val properties: AgentOAuthProperties,
    private val runtime: ControlRuntimeProperties,
    private val publicApi: ControlPublicApiProperties,
    private val clock: Clock,
    private val pendingRequests: PendingAuthenticationRequests,
    private val browser: PendingAuthenticationBrowser,
) {
    private val attempts = LinkedHashMap<String, List<Instant>>()

    @GetMapping("/.well-known/oauth-authorization-server")
    fun metadata(): Map<String, Any> = mapOf(
        "issuer" to properties.issuer,
        "authorization_endpoint" to "${properties.issuer}/oauth/authorize",
        "token_endpoint" to "${properties.issuer}/oauth/token",
        "revocation_endpoint" to "${properties.issuer}/oauth/revoke",
        "response_types_supported" to listOf("code"),
        "grant_types_supported" to listOf("authorization_code", "refresh_token"),
        "code_challenge_methods_supported" to listOf("S256"),
        "token_endpoint_auth_methods_supported" to listOf("none"),
        "revocation_endpoint_auth_methods_supported" to listOf("none"),
        "scopes_supported" to listOf("agent.workspace"),
        "client_id_metadata_document_supported" to true,
        "authorization_response_iss_parameter_supported" to true,
    )

    @GetMapping("/.well-known/oauth-protected-resource/mcp")
    fun mcpMetadata(): Map<String, Any> = resourceMetadata(properties.mcpResource)

    @GetMapping("/.well-known/oauth-protected-resource/v2/workspaces")
    fun restMetadata(): Map<String, Any> = resourceMetadata(runtime.security.agentResource)

    private fun resourceMetadata(resource: String): Map<String, Any> = mapOf(
        "resource" to resource, "authorization_servers" to listOf(properties.issuer),
        "scopes_supported" to listOf("agent.workspace"), "bearer_methods_supported" to listOf("header"),
    )

    @GetMapping("/oauth/authorize")
    suspend fun authorize(exchange: ServerWebExchange): ResponseEntity<Any> {
        protect(exchange)
        if (!admit(exchange)) return limited()
        val params = parameters(exchange.request.queryParams, AUTHORIZATION_PARAMETERS) ?: return error("invalid_request")
        if (params["response_type"] != "code") return error("unsupported_response_type")
        val request = authorizationRequest(params) ?: return error("invalid_request")
        val client = when (val result = oauth.inspectAuthorization(request)) {
            is OAuthOutcome.Success -> result.value
            is OAuthOutcome.Failure -> return error(result.error.wireValue)
        }
        val pending = PendingAuthenticationPayload.Agent(client.clientId, client.clientName, setOf(request.redirectUri),
            request.redirectUri, request.resource, request.scopes, request.codeChallenge, request.codeChallengeMethod,
            params["state"], OpaqueCredentials.generate())
        val requestId = pendingRequests.createAgent(browser.start(exchange), pending)
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI("${publicApi.webOrigin.toString().trimEnd('/')}/oauth/consent?request=$requestId")).build()
    }

    @GetMapping("/oauth/consent")
    suspend fun consent(exchange: ServerWebExchange): ResponseEntity<Any> {
        protect(exchange)
        val auth = browserAuthentication() ?: return error("access_denied", HttpStatus.UNAUTHORIZED)
        if (oauth.listConnections(auth.presentedCredential, 1, null) is OAuthOutcome.Failure) return error("access_denied", HttpStatus.UNAUTHORIZED)
        val params = parameters(exchange.request.queryParams, setOf("request")) ?: return error("invalid_request")
        val requestId = params["request"] ?: return error("invalid_request")
        val binding = browser.read(exchange) ?: return error("invalid_request")
        val stored = pendingRequests.bindAgent(requestId, binding, auth.name) ?: return error("invalid_request")
        val pending = stored.payload as PendingAuthenticationPayload.Agent
        val clientUri = runCatching { URI(pending.client().clientId) }.getOrNull()
        val summary = when (val result = oauth.connectionSummary(auth.presentedCredential, pending.clientId)) {
            is OAuthOutcome.Failure -> return error("access_denied", HttpStatus.UNAUTHORIZED)
            is OAuthOutcome.Success -> result.value
        }
        return ResponseEntity.ok(mapOf(
            "request" to requestId, "csrfToken" to pending.csrfToken,
            "clientName" to pending.client().clientName, "clientId" to pending.client().clientId,
            "clientOrigin" to clientUri?.takeIf { it.scheme == "https" }?.let { "https://${it.rawAuthority}" },
            "localClient" to (URI(pending.authorizationRequest().redirectUri).scheme == "http"),
            "resource" to pending.authorizationRequest().resource,
            "expiresAt" to stored.expiresAt.toString(),
            "existingConnectionCount" to summary.activeCount,
            "latestConnectionIssuedAt" to summary.latestIssuedAt?.toString(),
        ))
    }

    // "Not you? Switch account": the signed-in user releases the request so the account chosen next
    // in this browser can review it. The request stays bound to this browser and keeps its expiry.
    @PostMapping("/oauth/consent/release", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun release(@RequestBody input: Map<String, Any?>, exchange: ServerWebExchange): ResponseEntity<Any> {
        protect(exchange)
        if (input.keys != setOf("request", "csrfToken")) return error("invalid_request")
        val requestId = input["request"] as? String ?: return error("invalid_request")
        val csrfToken = input["csrfToken"] as? String ?: return error("invalid_request")
        if (!sameOrigin(exchange)) return error("access_denied", HttpStatus.FORBIDDEN)
        val auth = browserAuthentication() ?: return error("access_denied", HttpStatus.UNAUTHORIZED)
        if (oauth.listConnections(auth.presentedCredential, 1, null) is OAuthOutcome.Failure) return error("access_denied", HttpStatus.UNAUTHORIZED)
        val binding = browser.read(exchange) ?: return error("invalid_request")
        if (!pendingRequests.releaseAgent(requestId, binding, auth.name, csrfToken)) return error("invalid_request")
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/oauth/consent", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun approve(@RequestBody input: Map<String, Any?>, exchange: ServerWebExchange): ResponseEntity<Any> {
        protect(exchange)
        if (input.keys != setOf("request", "csrfToken", "approve")) return error("invalid_request")
        val body = ConsentSubmission(input["request"] as? String ?: return error("invalid_request"),
            input["csrfToken"] as? String ?: return error("invalid_request"),
            input["approve"] as? Boolean ?: return error("invalid_request"))
        if (!sameOrigin(exchange)) return error("access_denied", HttpStatus.FORBIDDEN)
        val auth = browserAuthentication() ?: return error("access_denied", HttpStatus.UNAUTHORIZED)
        if (oauth.listConnections(auth.presentedCredential, 1, null) is OAuthOutcome.Failure) return error("access_denied", HttpStatus.UNAUTHORIZED)
        val binding = browser.read(exchange) ?: return error("invalid_request")
        val pending = pendingRequests.consumeAgent(body.request, binding, auth.name, body.csrfToken)?.payload
            as? PendingAuthenticationPayload.Agent ?: return error("invalid_request")
        if (!body.approve) return ResponseEntity.ok(mapOf("redirectUri" to callback(pending, mapOf("error" to "access_denied"))))
        return when (val result = oauth.authorize(OAuthConsentCommand(auth.presentedCredential, pending.authorizationRequest()))) {
            is OAuthOutcome.Success -> ResponseEntity.ok(mapOf("redirectUri" to callback(pending, mapOf("code" to result.value.code))))
            is OAuthOutcome.Failure -> error(result.error.wireValue)
        }
    }

    @PostMapping("/oauth/token", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    suspend fun token(exchange: ServerWebExchange): ResponseEntity<Any> {
        protect(exchange)
        if (!admit(exchange)) return limited()
        val params = parameters(exchange.formData.awaitSingle(), TOKEN_PARAMETERS) ?: return error("invalid_request")
        if (exchange.request.headers.containsHeader(HttpHeaders.AUTHORIZATION)) return error("invalid_client")
        val clientId = params["client_id"] ?: return error("invalid_client")
        val resource = params["resource"] ?: return error("invalid_target")
        val result = when (params["grant_type"]) {
            "authorization_code" -> {
                if (params.keys.any { it in setOf("refresh_token", "scope") }) return error("invalid_request")
                val code = params["code"]?.takeIf { it.isNotBlank() } ?: return error("invalid_grant")
                val verifier = params["code_verifier"]?.takeIf { it.isNotBlank() } ?: return error("invalid_grant")
                val redirect = params["redirect_uri"] ?: return error("invalid_request")
                oauth.exchangeCode(OAuthCodeExchange(clientId, redirect, resource, PresentedCredential(code), PresentedCredential(verifier)))
            }
            "refresh_token" -> {
                if (params.keys.any { it in setOf("code", "code_verifier", "redirect_uri") }) return error("invalid_request")
                if (params["scope"] != null && params["scope"] != "agent.workspace") return error("invalid_scope")
                val refresh = params["refresh_token"]?.takeIf { it.isNotBlank() } ?: return error("invalid_grant")
                oauth.refresh(OAuthRefreshExchange(clientId, resource, PresentedCredential(refresh)))
            }
            else -> return error("unsupported_grant_type")
        }
        return when (result) {
            is OAuthOutcome.Success -> ResponseEntity.ok(mapOf(
                "access_token" to result.value.accessToken, "token_type" to "Bearer",
                "expires_in" to result.value.expiresIn, "refresh_token" to result.value.refreshToken, "scope" to result.value.scope,
            ))
            is OAuthOutcome.Failure -> error(result.error.wireValue)
        }
    }

    @PostMapping("/oauth/revoke", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    suspend fun revoke(exchange: ServerWebExchange): ResponseEntity<Any> {
        protect(exchange)
        if (!admit(exchange)) return limited()
        val params = parameters(exchange.formData.awaitSingle(), setOf("client_id", "token", "token_type_hint")) ?: return error("invalid_request")
        if (exchange.request.headers.containsHeader(HttpHeaders.AUTHORIZATION)) return error("invalid_client")
        val client = params["client_id"]?.takeIf { it.isNotBlank() } ?: return error("invalid_client")
        val token = params["token"]?.takeIf { it.isNotBlank() } ?: return error("invalid_request")
        oauth.revokeToken(client, PresentedCredential(token))
        return ResponseEntity.ok().build()
    }

    @GetMapping("/oauth/connections")
    suspend fun connections(exchange: ServerWebExchange): ResponseEntity<Any> {
        protect(exchange)
        val auth = browserAuthentication() ?: return error("access_denied", HttpStatus.UNAUTHORIZED)
        val params = parameters(exchange.request.queryParams, setOf("limit", "cursor")) ?: return error("invalid_request")
        val limit = params["limit"]?.toIntOrNull() ?: if (params.containsKey("limit")) return error("invalid_request") else 20
        if (limit !in 1..100) return error("invalid_request")
        return when (val result = oauth.listConnections(auth.presentedCredential, limit, params["cursor"])) {
            is OAuthOutcome.Failure -> error(result.error.wireValue,
                if (result.error == OAuthError.INVALID_REQUEST) HttpStatus.BAD_REQUEST else HttpStatus.UNAUTHORIZED)
            is OAuthOutcome.Success -> ResponseEntity.ok(mapOf(
                "connections" to result.value.connections.map { connection -> mapOf(
                    "id" to connection.grantId.toString(),
                    "clientId" to connection.clientId, "clientName" to connection.clientName,
                    "resource" to connection.resource, "issuedAt" to connection.issuedAt.toString(),
                    "expiresAt" to connection.expiresAt.toString(), "revokedAt" to connection.revokedAt?.toString(),
                    "lastUsedAt" to connection.lastUsedAt?.toString(),
                ) },
                "nextCursor" to result.value.nextCursor,
            ))
        }
    }

    @PostMapping("/oauth/connections/{grantId}/revoke")
    suspend fun disconnect(@PathVariable grantId: String, exchange: ServerWebExchange): ResponseEntity<Any> {
        protect(exchange)
        if (!sameOrigin(exchange)) return error("access_denied", HttpStatus.FORBIDDEN)
        val auth = browserAuthentication() ?: return error("access_denied", HttpStatus.UNAUTHORIZED)
        val id = runCatching { Uuid.parse(grantId) }.getOrNull() ?: return error("invalid_request")
        return when (val result = oauth.revokeConnection(auth.presentedCredential, id)) {
            is OAuthOutcome.Success -> ResponseEntity.noContent().build()
            is OAuthOutcome.Failure -> error(result.error.wireValue, HttpStatus.FORBIDDEN)
        }
    }

    private suspend fun browserAuthentication(): JwtAuthentication? =
        ReactiveSecurityContextHolder.getContext().awaitSingleOrNull()?.authentication as? JwtAuthentication

    private fun sameOrigin(exchange: ServerWebExchange): Boolean =
        exchange.request.headers[HttpHeaders.ORIGIN] == listOf(publicApi.webOrigin.toString().trimEnd('/'))

    private fun callback(pending: PendingAuthenticationPayload.Agent, values: Map<String, String>): String {
        val parameters = values + ("iss" to properties.issuer) + (pending.state?.let { mapOf("state" to it) } ?: emptyMap())
        return pending.authorizationRequest().redirectUri + (if (URI(pending.authorizationRequest().redirectUri).rawQuery == null) "?" else "&") +
            parameters.entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}" }
    }

    private fun parameters(values: MultiValueMap<String, String>, allowed: Set<String>): Map<String, String>? {
        if (values.values.flatten().sumOf { it.length } > 16384 || values.keys.any { it !in allowed } || values.values.any { it.size != 1 || it.single().length > 8192 }) return null
        return values.mapValues { it.value.single() }
    }

    private fun authorizationRequest(params: Map<String, String>): OAuthAuthorizationRequest? { return OAuthAuthorizationRequest(
        params["client_id"] ?: return null, params["redirect_uri"] ?: return null,
        params["resource"] ?: return null, (params["scope"] ?: return null).split(" ").toSet(),
        params["code_challenge"] ?: return null, params["code_challenge_method"] ?: return null,
    ) }

    private fun protect(exchange: ServerWebExchange) {
        exchange.response.headers[HttpHeaders.CACHE_CONTROL] = "no-store"
        exchange.response.headers[HttpHeaders.PRAGMA] = "no-cache"
        exchange.response.headers["Referrer-Policy"] = "no-referrer"
        exchange.response.headers["X-Content-Type-Options"] = "nosniff"
    }

    private fun admit(exchange: ServerWebExchange): Boolean = synchronized(attempts) {
        val now = clock.instant()
        val key = exchange.request.remoteAddress?.address?.hostAddress ?: "unknown"
        attempts.entries.removeIf { entry -> entry.value.none { it > now.minusSeconds(60) } }
        val recent = attempts[key].orEmpty().filter { it > now.minusSeconds(60) }
        if (recent.size >= 60 || (key !in attempts && attempts.size >= 10000)) false
        else { attempts[key] = recent + now; true }
    }

    private fun limited(): ResponseEntity<Any> = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, "60").body(mapOf("error" to "temporarily_unavailable"))
    private fun error(code: String, status: HttpStatus = HttpStatus.BAD_REQUEST): ResponseEntity<Any> = ResponseEntity.status(status).body(mapOf("error" to code).also { cc.monomer.control.observability.RequestLogContext.problem(code) })

    private companion object {
        val AUTHORIZATION_PARAMETERS = setOf("response_type", "client_id", "redirect_uri", "resource", "scope", "code_challenge", "code_challenge_method", "state")
        val TOKEN_PARAMETERS = setOf("grant_type", "client_id", "resource", "code", "code_verifier", "redirect_uri", "refresh_token", "scope")
    }
}

private class ConsentSubmission(val request: String, val csrfToken: String, val approve: Boolean)
