package cc.monomer.control.human.auth

import java.net.URI
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import kotlin.test.assertEquals

class ConnectorCatalogProblemHandlerTest {
    @Test
    fun `admission problems preserve every problem field and challenge only authentication failures`() {
        val path = "/v2/workspaces/workspace/catalog/connector-summaries"
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("$path?query=orders"))
        for ((status, code, detail) in listOf(
            Triple(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication is required."),
            Triple(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workspace resource was not found."),
            Triple(HttpStatus.FORBIDDEN, "ROLE_REQUIRED", "The required Workspace role is unavailable."),
            Triple(HttpStatus.FORBIDDEN, "FORBIDDEN", "The Catalog request is forbidden."),
        )) {
            val response = ConnectorCatalogProblemHandler().rejected(
                ConnectorCatalogRequestProblem(status, code, detail),
                exchange,
            )
            val problem = requireNotNull(response.body)

            assertEquals(status, response.statusCode)
            assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.headers.contentType)
            assertEquals(if (status == HttpStatus.UNAUTHORIZED) "Bearer" else null, response.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE))
            assertEquals(URI.create("https://monomer.cc/problems/${code.lowercase().replace('_', '-')}"), problem.type)
            assertEquals(status.reasonPhrase, problem.title)
            assertEquals(status.value(), problem.status)
            assertEquals(detail, problem.detail)
            assertEquals(URI.create(path), problem.instance)
            assertEquals(
                mapOf("code" to code, "requestId" to exchange.request.id, "traceId" to exchange.request.id, "retryable" to false),
                problem.properties,
            )
        }
    }
}
