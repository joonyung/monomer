package cc.monomer.control.sample

import java.net.URI
import org.springframework.http.*
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ServerWebExchange

class SampleWorkspaceProblem(val status: HttpStatus, val code: String, val detail: String) : RuntimeException(detail)

@RestControllerAdvice(assignableTypes = [SampleWorkspaceController::class])
class SampleWorkspaceProblemHandler {
    @ExceptionHandler(SampleWorkspaceProblem::class)
    fun rejected(problem: SampleWorkspaceProblem, exchange: ServerWebExchange): ResponseEntity<ProblemDetail> {
        cc.monomer.control.observability.RequestLogContext.problem(problem.code)
        val body = ProblemDetail.forStatusAndDetail(problem.status, problem.detail).also {
            it.type = URI.create("https://monomer.cc/problems/${problem.code.lowercase().replace('_', '-')}")
            it.title = problem.status.reasonPhrase
            it.instance = URI.create(exchange.request.uri.rawPath)
            it.setProperty("code", problem.code)
            it.setProperty("requestId", exchange.request.id)
            it.setProperty("traceId", exchange.request.id)
            it.setProperty("retryable", false)
        }
        return ResponseEntity.status(problem.status).contentType(MediaType.APPLICATION_PROBLEM_JSON).header("Cache-Control", "no-store")
            .headers { if (problem.status == HttpStatus.UNAUTHORIZED) it.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer") }.body(body)
    }
}
