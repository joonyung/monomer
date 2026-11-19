package cc.monomer.control.pages.inbound.rest.pages

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

class PageContentRestProblem(
    val status: HttpStatus,
    val code: String,
    val requestId: String?,
    val properties: Map<String, Any?>,
    message: String,
) : RuntimeException(message)

@RestControllerAdvice(assignableTypes = [PageContentController::class, PageContentViewerController::class])
class PageContentProblemHandler {
    @ExceptionHandler(PageContentRestProblem::class)
    fun handle(exception: PageContentRestProblem, exchange: org.springframework.web.server.ServerWebExchange): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(exception.status, exception.message ?: exception.status.reasonPhrase)
        problem.title = exception.status.reasonPhrase
        cc.monomer.control.observability.RequestLogContext.problem(exception.code)
        problem.setProperty("code", exception.code)
        problem.setProperty("requestId", exchange.request.id)
        problem.setProperty("traceId", exchange.request.id)
        val retryable = exception.properties["retryable"] as? Boolean ?: (exception.status == HttpStatus.SERVICE_UNAVAILABLE)
        problem.setProperty("retryable", retryable)
        if (exception.status == HttpStatus.SERVICE_UNAVAILABLE && retryable) exchange.response.headers.set("Retry-After", "2")
        exception.properties.forEach { (key, value) -> problem.setProperty(key, value) }
        return problem
    }
}
