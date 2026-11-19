package cc.monomer.control.human.auth

import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionDenial
import cc.monomer.control.ControlAdmissionStage
import cc.monomer.control.dashboard.DashboardProblemHandler
import cc.monomer.control.dashboard.rejectionProblem
import cc.monomer.control.pages.inbound.rest.pages.PageContentProblemHandler
import cc.monomer.control.pages.inbound.rest.pages.PageContentRestProblem
import cc.monomer.control.pages.inbound.rest.pages.PagesProblemHandler
import cc.monomer.control.pages.inbound.rest.pages.PagesRestProblem
import cc.monomer.control.pages.inbound.rest.pages.PagesRequestProblemHandler
import cc.monomer.control.pagereader.PageReaderFailure
import cc.monomer.control.pagereader.PageReaderOwnerRejected
import cc.monomer.control.pagereader.PageReaderProblem
import cc.monomer.control.pagereader.valueOrThrow
import cc.monomer.control.pages.publicpages.PublicPagesProblemHandler
import cc.monomer.control.semantic.inbound.rest.SemanticDefinitionProblemHandler
import cc.monomer.control.semantic.inbound.rest.SemanticQueryProblemHandler
import cc.monomer.dashboard.api.DashboardOwnerOutcome
import cc.monomer.identity.api.IdentityAdmissionDenial
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HumanErrorStatusContractTest {
    @Test
    fun `credential membership and role denials keep their status and public code through every owner advice`() {
        for (reason in IdentityAdmissionDenial.entries) {
            val expectedStatus = when (reason) {
                IdentityAdmissionDenial.CREDENTIAL_UNAVAILABLE, IdentityAdmissionDenial.CREDENTIAL_EXPIRED,
                IdentityAdmissionDenial.CREDENTIAL_REVOKED, IdentityAdmissionDenial.PRINCIPAL_MISMATCH,
                IdentityAdmissionDenial.AGENT_BINDING_MISMATCH, IdentityAdmissionDenial.AGENT_SCOPE_INSUFFICIENT -> 401
                IdentityAdmissionDenial.PERMISSION_UNAVAILABLE -> 403
                else -> 404
            }
            val code = when (expectedStatus) { 401 -> "AUTHENTICATION_REQUIRED"; 403 -> "ROLE_REQUIRED"; else -> "NOT_FOUND" }
            val exception = ControlAdmissionDecision.Denied(ControlAdmissionStage.IDENTITY, ControlAdmissionDenial.Identity(reason)).toHumanHttpException()
            assertEquals(expectedStatus, exception.statusCode.value(), reason.name)
            assertEquals(code, DashboardProblemHandler().handle(exception, exchange()).properties?.get("code"))
            assertEquals(code, SemanticQueryProblemHandler().responseStatus(exception, exchange()).body?.properties?.get("code"))
            assertEquals(code, SemanticDefinitionProblemHandler().responseStatus(exception, exchange()).body?.get("code"))
        }
    }

    @Test
    fun `missing Pages authentication keeps the authentication code and challenge`() {
        val response = PagesRequestProblemHandler().rejected(ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required."))
        assertEquals(401, response.statusCode.value())
        assertEquals("AUTHENTICATION_REQUIRED", response.body?.get("code"))
        assertEquals("Bearer", response.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE))
    }

    @Test
    fun `named dashboard concurrency problems are retryable 409 without Retry-After`() {
        for (code in listOf("SESSION_CONFLICT", "QUERY_RUN_CONFLICT", "DRAFT_CONFLICT", "EXECUTION_ADMISSION_CONFLICT", "QUERY_RUN_CANCEL_CONFLICT")) {
            val exchange = exchange()
            val problem = DashboardProblemHandler().handle(rejectionProblem(code, "Concurrent update"), exchange)
            assertEquals(409, problem.status, code)
            assertEquals(code, problem.properties?.get("code"))
            assertEquals(true, problem.properties?.get("retryable"))
            assertEquals(null, exchange.response.headers.getFirst(HttpHeaders.RETRY_AFTER))
        }
        val idempotency = DashboardProblemHandler().handle(rejectionProblem("IDEMPOTENCY_CONFLICT", null), exchange())
        assertEquals(false, idempotency.properties?.get("retryable"))
    }

    @Test
    fun `dashboard backpressure is retryable 503 and human value errors remain 422`() {
        val exchange = exchange()
        val problem = DashboardProblemHandler().handle(rejectionProblem("EXECUTION_BACKPRESSURED", "Active run limit"), exchange)
        assertEquals(503, problem.status)
        assertEquals(true, problem.properties?.get("retryable"))
        assertEquals("2", exchange.response.headers.getFirst(HttpHeaders.RETRY_AFTER))
        for (code in listOf("INVALID_DRAFT_VALUE", "INVALID_VARIABLE_VALUE", "INVALID_DRAFT_PARAMETERS", "INVALID_SESSION_PARAMETERS")) {
            assertEquals(422, DashboardProblemHandler().handle(rejectionProblem(code, "Repair the input"), exchange()).status)
        }
    }

    @Test
    fun `semantic and pages retryable 503 responses include Retry-After and suppress it when explicitly nonretryable`() {
        val unavailable = ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Temporarily unavailable")
        assertEquals("2", SemanticQueryProblemHandler().responseStatus(unavailable, exchange()).headers.getFirst(HttpHeaders.RETRY_AFTER))
        assertEquals("2", SemanticDefinitionProblemHandler().responseStatus(unavailable, exchange()).headers.getFirst(HttpHeaders.RETRY_AFTER))
        for (retryable in listOf(true, false)) {
            val pagesExchange = exchange()
            val pages = PagesProblemHandler().handle(PagesRestProblem(HttpStatus.SERVICE_UNAVAILABLE, "PAGE_UNAVAILABLE", null, null, mapOf("retryable" to retryable), "Unavailable"), pagesExchange)
            assertEquals(retryable, pages.properties?.get("retryable"))
            assertEquals(if (retryable) "2" else null, pagesExchange.response.headers.getFirst(HttpHeaders.RETRY_AFTER))
            val contentExchange = exchange()
            val content = PageContentProblemHandler().handle(PageContentRestProblem(HttpStatus.SERVICE_UNAVAILABLE, "PAGE_CONTENT_UNAVAILABLE", null, mapOf("retryable" to retryable), "Unavailable"), contentExchange)
            assertEquals(retryable, content.properties?.get("retryable"))
            assertEquals(if (retryable) "2" else null, contentExchange.response.headers.getFirst(HttpHeaders.RETRY_AFTER))
        }
    }

    @Test
    fun `page reader preserves owner values conflicts and backpressure across its HTTP boundary`() {
        val handler = PublicPagesProblemHandler()
        for ((code, status, retryable) in listOf(Triple("INVALID_VARIABLE_VALUE", 422, false), Triple("INVALID_SESSION_PARAMETERS", 422, false), Triple("QUERY_RUN_CONFLICT", 409, true), Triple("EXECUTION_ADMISSION_CONFLICT", 409, true))) {
            val rejection = assertFailsWith<PageReaderOwnerRejected> { DashboardOwnerOutcome.Rejected(code, "Repair this value").valueOrThrow() }
            val response = handler.inputRejected(rejection)
            assertEquals(status, response.statusCode.value())
            assertEquals(code, response.body?.get("code"))
            assertEquals("Repair this value", response.body?.get("detail"))
            assertEquals(retryable, response.body?.get("retryable"))
            assertEquals(null, response.headers.getFirst(HttpHeaders.RETRY_AFTER))
        }
        val backpressure = assertFailsWith<PageReaderProblem> { DashboardOwnerOutcome.Rejected("EXECUTION_BACKPRESSURED", null).valueOrThrow() }
        assertEquals(PageReaderFailure.Backpressured, backpressure.failure)
        val response = handler.readerProblem(backpressure, org.springframework.mock.web.server.MockServerWebExchange.from(org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/").build()))
        assertEquals(503, response.statusCode.value())
        assertEquals("EXECUTION_BACKPRESSURED", response.body?.get("code"))
        assertEquals(true, response.body?.get("retryable"))
        assertEquals("2", response.headers.getFirst(HttpHeaders.RETRY_AFTER))
    }

    private fun exchange() = MockServerWebExchange.from(MockServerHttpRequest.get("/contract-test"))
}
