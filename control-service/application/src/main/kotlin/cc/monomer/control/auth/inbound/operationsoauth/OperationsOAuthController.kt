package cc.monomer.control.auth.inbound.operationsoauth

import cc.monomer.identity.application.OpaqueCredentials
import cc.monomer.control.auth.inbound.BearerAuthorization
import cc.monomer.control.auth.inbound.BearerCredentials
import cc.monomer.control.auth.inbound.PendingAuthenticationBrowser
import cc.monomer.control.auth.inbound.agentoauth.AgentOAuthProperties
import cc.monomer.control.auth.inbound.oauth.JwtAuthentication
import cc.monomer.control.publicapi.ControlPublicApiProperties
import cc.monomer.identity.api.*
import cc.monomer.identity.application.me.MeUseCase
import cc.monomer.identity.application.operations.*
import cc.monomer.identity.application.pending.*
import cc.monomer.identity.domain.id.UserId
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.*
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange

@RestController
class OperationsOAuthController(private val oauth: OperationsOAuthService, private val pending: PendingAuthenticationRequests,
    private val browser: PendingAuthenticationBrowser, private val publicApi: ControlPublicApiProperties,
    private val properties: AgentOAuthProperties, private val me: MeUseCase) {
    @GetMapping("/ops/oauth/authorize")
    suspend fun authorize(exchange: ServerWebExchange): ResponseEntity<Any> {
        protect(exchange)
        val values = parameters(exchange.request.queryParams, setOf("response_type","client_id","redirect_uri","resource","scope","code_challenge","code_challenge_method","state"))
        require(values["response_type"] == "code" && values["scope"] == "ops.read" && values["code_challenge_method"] == "S256")
        val request = authorization(values)
        oauth.validate(request)
        val state = values["state"]?.takeIf { it.length in 32..256 && it.matches(Regex("[A-Za-z0-9_-]+")) } ?: throw IllegalArgumentException()
        val payload = PendingAuthenticationPayload.Operations(request.clientId, request.redirectUri, request.resource, request.challenge, state, OpaqueCredentials.generate())
        val reference = pending.createOperations(browser.start(exchange), payload)
        return ResponseEntity.status(HttpStatus.FOUND).location(URI("${publicApi.webOrigin.toString().trimEnd('/')}/ops/consent?request=$reference")).build()
    }
    @GetMapping("/ops/oauth/consent")
    suspend fun consent(exchange: ServerWebExchange): ResponseEntity<Any> {
        protect(exchange)
        val credential = browserCredential(exchange)
        val user = oauth.browserUser(credential)
        val reference = parameters(exchange.request.queryParams, setOf("request"))["request"] ?: throw IllegalArgumentException()
        val binding = browser.read(exchange) ?: throw IllegalArgumentException()
        val stored = pending.bindOperations(reference, binding, user) ?: throw IllegalArgumentException()
        val payload = stored.payload as PendingAuthenticationPayload.Operations
        return ResponseEntity.ok(mapOf("request" to reference, "csrfToken" to payload.csrfToken, "clientName" to "Monomer local operations",
            "resource" to payload.resource, "expiresAt" to stored.expiresAt.toString()))
    }
    @PostMapping("/ops/oauth/consent", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun approve(@RequestBody body: OperationsConsentSubmission, exchange: ServerWebExchange): ResponseEntity<Any> {
        protect(exchange)
        if (exchange.request.headers[HttpHeaders.ORIGIN] != listOf(publicApi.webOrigin.toString().trimEnd('/'))) throw OperationsForbiddenException()
        val credential = browserCredential(exchange)
        val user = oauth.browserUser(credential)
        val binding = browser.read(exchange) ?: throw IllegalArgumentException()
        val payload = pending.consumeOperations(body.request, binding, user, body.csrfToken)?.payload as? PendingAuthenticationPayload.Operations ?: throw IllegalArgumentException()
        val response = if (body.approve) mapOf("code" to oauth.authorize(credential, OperationsAuthorization(payload.clientId,payload.redirectUri,payload.resource,payload.codeChallenge))) else mapOf("error" to "access_denied")
        val values = response + mapOf("state" to payload.state,"iss" to properties.issuer)
        return ResponseEntity.ok(mapOf("redirectUri" to payload.redirectUri + "?" + values.entries.joinToString("&") { (key,value) -> "$key=${URLEncoder.encode(value,StandardCharsets.UTF_8)}" }))
    }
    @PostMapping("/ops/oauth/token", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    suspend fun token(exchange: ServerWebExchange): ResponseEntity<Any> {
        protect(exchange)
        require(!exchange.request.headers.containsHeader(HttpHeaders.AUTHORIZATION))
        val values = parameters(exchange.formData.awaitSingle(), setOf("grant_type","client_id","redirect_uri","resource","code","code_verifier"))
        require(values["grant_type"] == "authorization_code")
        val verifier = values["code_verifier"] ?: throw IllegalArgumentException()
        val request = authorization(values + ("code_challenge" to OpaqueCredentials.s256CodeChallenge(verifier)))
        val token = oauth.exchange(values["code"] ?: throw IllegalArgumentException(), verifier, request)
        return ResponseEntity.ok(mapOf("access_token" to token,"token_type" to "Bearer","expires_in" to 3600,"scope" to "ops.read"))
    }
    @PostMapping("/ops/oauth/revoke", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    suspend fun revoke(exchange: ServerWebExchange): ResponseEntity<Any> {
        protect(exchange)
        require(!exchange.request.headers.containsHeader(HttpHeaders.AUTHORIZATION))
        val values = parameters(exchange.formData.awaitSingle(), setOf("token","client_id"))
        require(values["client_id"] == OperationsOAuthService.CLIENT_ID)
        oauth.revoke(PresentedCredential(values["token"]?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException()))
        return ResponseEntity.ok().build()
    }
    @GetMapping("/ops/v1/me")
    suspend fun currentUser(exchange: ServerWebExchange): ResponseEntity<Any> {
        protect(exchange)
        val credential = when (val bearer = BearerCredentials.fromSingleAuthorizationHeader(exchange.request.headers)) {
            is BearerAuthorization.Presented -> bearer.credential
            BearerAuthorization.Missing, BearerAuthorization.Malformed -> throw OperationsUnauthenticatedException()
        }
        val access = oauth.authenticate(credential)
        val user = me.me(MeUseCase.Command(UserId(kotlin.uuid.Uuid.parse(access.userId)))).user
        return ResponseEntity.ok(mapOf("userId" to access.userId,"displayName" to user.displayName,"expiresAt" to access.expiresAt.toString()))
    }
    private suspend fun browserCredential(exchange: ServerWebExchange): PresentedCredential {
        if (exchange.request.headers[HttpHeaders.AUTHORIZATION]?.size != 1) throw OperationsUnauthenticatedException()
        return (ReactiveSecurityContextHolder.getContext().awaitSingleOrNull()?.authentication as? JwtAuthentication)?.presentedCredential ?: throw OperationsUnauthenticatedException()
    }
    private fun authorization(values: Map<String,String>) = OperationsAuthorization(values["client_id"] ?: throw IllegalArgumentException(),values["redirect_uri"] ?: throw IllegalArgumentException(),values["resource"] ?: throw IllegalArgumentException(),values["code_challenge"] ?: throw IllegalArgumentException())
    private fun parameters(values: MultiValueMap<String,String>, allowed: Set<String>): Map<String,String> {
        require(values.keys.all { it in allowed } && values.values.all { it.size == 1 && it.single().length <= 8192 } && values.values.flatten().sumOf(String::length) <= 16384)
        return values.mapValues { it.value.single() }
    }
    private fun protect(exchange: ServerWebExchange) {
        exchange.response.headers[HttpHeaders.CACHE_CONTROL] = "no-store"
        exchange.response.headers[HttpHeaders.PRAGMA] = "no-cache"
        exchange.response.headers["Referrer-Policy"] = "no-referrer"
        exchange.response.headers["X-Content-Type-Options"] = "nosniff"
    }
}
class OperationsConsentSubmission(val request: String, val csrfToken: String, val approve: Boolean)

@RestControllerAdvice(assignableTypes = [OperationsOAuthController::class])
class OperationsOAuthProblemHandler {
    @ExceptionHandler(OperationsUnauthenticatedException::class)
    fun unauthorized(exchange: ServerWebExchange): ResponseEntity<Map<String,String>> {
        val tokenExchange = exchange.request.path.value() == "/ops/oauth/token"
        return ResponseEntity.status(if (tokenExchange) HttpStatus.BAD_REQUEST else HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.CACHE_CONTROL,"no-store").body(mapOf("error" to if (tokenExchange) "invalid_grant" else "invalid_token").also { cc.monomer.control.observability.RequestLogContext.problem(it.getValue("error")) })
    }
    @ExceptionHandler(OperationsForbiddenException::class)
    fun forbidden() = ResponseEntity.status(HttpStatus.FORBIDDEN).header(HttpHeaders.CACHE_CONTROL,"no-store").body(mapOf("error" to "access_denied").also { cc.monomer.control.observability.RequestLogContext.problem("access_denied") })
    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid() = ResponseEntity.badRequest().header(HttpHeaders.CACHE_CONTROL,"no-store").body(mapOf("error" to "invalid_request").also { cc.monomer.control.observability.RequestLogContext.problem("invalid_request") })
}
