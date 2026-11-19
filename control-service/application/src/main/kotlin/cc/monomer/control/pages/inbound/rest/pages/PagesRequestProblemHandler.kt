package cc.monomer.control.pages.inbound.rest.pages

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [
    PagesController::class,
    PagesEventStreamController::class,
    PageContentController::class,
    PageContentViewerController::class,
])
internal class PagesRequestProblemHandler {
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException::class)
    fun rejected(exception: org.springframework.web.server.ResponseStatusException): ResponseEntity<Map<String, String>> {
        val code = exception.body.properties?.get("code") as? String ?: when (exception.statusCode.value()) {
            400 -> "INVALID_REQUEST"
            401 -> "AUTHENTICATION_REQUIRED"
            403 -> "FORBIDDEN"
            404 -> "NOT_FOUND"
            else -> "REQUEST_FAILED"
        }
        cc.monomer.control.observability.RequestLogContext.problem(code)
        return ResponseEntity.status(exception.statusCode)
            .headers { if (exception.statusCode == HttpStatus.UNAUTHORIZED) it.set("WWW-Authenticate", "Bearer") }
            .body(mapOf("code" to code))
    }
}
