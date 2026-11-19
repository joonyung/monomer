package cc.monomer.control.auth.inbound.oauth

import cc.monomer.control.observability.RequestLogContext
import cc.monomer.control.pages.publicpages.PublicPagesHttpPaths
import cc.monomer.identity.domain.model.DeveloperAccessPolicy
import cc.monomer.identity.outbound.jwt.JwtVerifier
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.domain.model.UserCredentialProvenance
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

class JwtAuthenticationWebFilter(
    private val verifier: JwtVerifier,
    private val developerAccessPolicy: DeveloperAccessPolicy,
    private val sessionRepository: cc.monomer.identity.domain.port.SessionRepository,
    private val transactionRunner: cc.monomer.identity.domain.port.TransactionRunner,
) : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val header = exchange.request.headers.getFirst(HttpHeaders.AUTHORIZATION)
        if (header == null) return chain.filter(exchange)
        if (!header.startsWith(BEARER_PREFIX)) return unauthenticated(exchange, chain)
        val token = header.substring(BEARER_PREFIX.length)
        return when (val outcome = verifier.verify(token)) {
            is JwtVerifier.Outcome.Valid -> {
                val allowed = kotlinx.coroutines.reactor.mono(org.springframework.core.PropagationContextElement()) {
                    transactionRunner.readOnly {
                        val browser = outcome.credential as? UserCredentialProvenance.BrowserSession
                        val session = browser?.let { sessionRepository.findById(it.sessionId) }
                        session != null && session.userId == browser.userId &&
                            session.revokedAt == null && java.time.Instant.now().isBefore(session.absoluteExpiresAt) &&
                            developerAccessPolicy.allows(session)
                    }
                }
                val authentication = JwtAuthentication(outcome.credential, PresentedCredential(token))
                allowed.flatMap { admitted ->
                    if (!admitted) unauthenticated(exchange, chain) else chain.filter(exchange).contextWrite(
                        ReactiveSecurityContextHolder.withSecurityContext(
                            Mono.just(SecurityContextImpl(authentication)),
                        ),
                    )
                }
            }
            is JwtVerifier.Outcome.Invalid -> unauthenticated(exchange, chain)
        }
    }

    private fun unauthenticated(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path
        if (!PublicPagesHttpPaths.contains(path.pathWithinApplication().value())) {
            return chain.filter(exchange)
        }
        val response = exchange.response
        response.statusCode = HttpStatus.UNAUTHORIZED
        response.headers.cacheControl = "no-store"
        if (!PublicPagesHttpPaths.declaresJsonProblem(exchange.request.method, path.pathWithinApplication())) {
            return response.setComplete()
        }
        exchange.getAttribute<RequestLogContext>(RequestLogContext.KEY)?.code = PUBLIC_PAGES_UNAUTHORIZED_CODE
        response.headers.contentType = MediaType.APPLICATION_JSON
        return response.writeWith(Mono.fromSupplier { response.bufferFactory().wrap(PUBLIC_PAGES_UNAUTHORIZED_BODY) })
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
        private const val PUBLIC_PAGES_UNAUTHORIZED_CODE = "UNAUTHORIZED"
        private val PUBLIC_PAGES_UNAUTHORIZED_BODY = """{"code":"$PUBLIC_PAGES_UNAUTHORIZED_CODE"}""".toByteArray(Charsets.UTF_8)
    }
}

/** Minimal Authentication carrying the user-id string from `sub`. */
class JwtAuthentication(
    val credential: UserCredentialProvenance,
    val presentedCredential: PresentedCredential,
) :
    AbstractAuthenticationToken(emptyList()) {
    init {
        isAuthenticated = true
    }
    override fun getCredentials(): Any = ""
    override fun getPrincipal(): UserCredentialProvenance = credential
    override fun getName(): String = credential.userId.toString()
}
