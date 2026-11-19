package cc.monomer.control.auth.inbound.agentoauth

import kotlinx.coroutines.CancellationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ServerWebInputException

@RestControllerAdvice(assignableTypes = [AgentOAuthController::class])
class AgentOAuthProblemHandler {
    @ExceptionHandler(Exception::class)
    fun handle(failure: Exception): ResponseEntity<Map<String, String>> {
        if (failure is CancellationException) throw failure
        val malformed = failure is ServerWebInputException || failure is IllegalArgumentException
        if (!malformed) cc.monomer.control.observability.RequestLogContext.failure(failure)
        cc.monomer.control.observability.RequestLogContext.problem(if (malformed) "invalid_request" else "temporarily_unavailable")
        return ResponseEntity.status(if (malformed) HttpStatus.BAD_REQUEST else HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("Referrer-Policy", "no-referrer")
            .headers { if (!malformed) it.set(HttpHeaders.RETRY_AFTER, "60") }
            .body(mapOf("error" to if (malformed) "invalid_request" else "temporarily_unavailable"))
    }
}
