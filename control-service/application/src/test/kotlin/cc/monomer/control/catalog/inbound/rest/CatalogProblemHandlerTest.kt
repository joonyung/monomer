package cc.monomer.control.catalog.inbound.rest

import cc.monomer.catalog.api.CatalogConflictReason
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import kotlin.test.assertEquals

class CatalogProblemHandlerTest {
    @Test
    fun `conflict responses preserve every Problem field for all reasons`() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.put("/v2/workspaces/workspace/catalog/selection"))
        for (reason in CatalogConflictReason.entries) {
            val (code, detail) = when (reason) {
                CatalogConflictReason.ACTIVE_INGESTION ->
                    "CATALOG_SELECTION_ACTIVE_INGESTION" to
                        "Finish or cancel the active Catalog ingestion before changing Catalog selection."
                CatalogConflictReason.INVALID_CANDIDATE ->
                    "CATALOG_SELECTION_INVALID_CANDIDATE" to
                        "The Catalog selection is stale or invalid. Refresh the Catalog options and try again."
            }

            val response = CatalogProblemHandler().catalogConflict(reason.toCatalogConflictProblem(), exchange)

            assertEquals(HttpStatus.CONFLICT, response.statusCode)
            assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.headers.contentType)
            assertEquals(
                CatalogProblemResponse(
                    type = "https://monomer.cc/problems/${code.lowercase().replace('_', '-')}",
                    title = "Conflict",
                    status = 409,
                    detail = detail,
                    instance = "/v2/workspaces/workspace/catalog/selection",
                    code = code,
                    requestId = exchange.request.id,
                    traceId = exchange.request.id,
                    retryable = false,
                ),
                response.body,
                reason.name,
            )
        }
    }
}
