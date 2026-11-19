package cc.monomer.control.operations

import cc.monomer.identity.api.*
import cc.monomer.explore.api.*
import cc.monomer.connector.api.*
import io.mockk.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.ResponseStatusException
import kotlin.test.*

class OperationsQueriesControllerTest {
    private val auth=mockk<OperationsAccessPort>()
    private val identity=mockk<IdentityOperationsQueries>()
    private val explore=mockk<ExploreOperationsQueries>()
    private val connector=mockk<ConnectorOperationsQueries>()
    private val access=mockk<OperationsAccess>()
    private val controller=OperationsQueriesController(auth,identity,explore,connector)
    private val from="2026-09-01T00:00:00Z"
    private val to="2026-09-08T00:00:00Z"
    private val id="00000000-0000-0000-0000-000000000001"
    init { coEvery { auth.authenticate(any()) } returns access; every { access.userId } returns id }
    private fun exchange(vararg authorization:String)=MockServerWebExchange.from(MockServerHttpRequest.get("/ops/v1/runs").header("Authorization",*authorization).build())
    @Test fun `all operations reads require operations authorization`()=runBlocking {
        coEvery { auth.authenticate(any()) } throws OperationsForbiddenException()
        val calls:List<suspend ()->Any> = listOf(
            {controller.overview(from,to,exchange("Bearer token"))},
            {controller.users(null,null,null,null,null,exchange("Bearer token"))},
            {controller.user(id,exchange("Bearer token"))},
            {controller.workspaces(null,null,null,from,to,null,null,exchange("Bearer token"))},
            {controller.workspace(id,from,to,exchange("Bearer token"))},
            {controller.runs(null,null,null,null,from,to,null,null,exchange("Bearer token"))},
            {controller.run(id,exchange("Bearer token"))},
        )
        calls.forEach { assertFailsWith<OperationsForbiddenException>{it()} }
        coVerify(exactly=0){identity.overview(any(),any(),any())}
    }
    @Test fun `missing or ambiguous credentials fail before owner calls`()=runBlocking {
        for(request in listOf(exchange(),exchange("Bearer one","Bearer two"))){
            assertFailsWith<OperationsUnauthenticatedException>{controller.overview(from,to,request)}
            assertEquals("no-store",request.response.headers.cacheControl)
        }
        coVerify(exactly=0){auth.authenticate(any())}
    }
    @Test fun `blank or malformed bearer credentials fail before owner calls`()=runBlocking {
        for(authorization in listOf("Bearer ","Bearer   ","Bearer \t","bearer token","Bearertoken","Basic dG9rZW4=")){
            val request=exchange(authorization)
            assertFailsWith<OperationsUnauthenticatedException>{controller.overview(from,to,request)}
            assertEquals("no-store",request.response.headers.cacheControl)
        }
        coVerify(exactly=0){auth.authenticate(any())}
    }
    @Test fun `a blank bearer token is answered with the operations 401 Problem`() {
        val client=WebTestClient.bindToController(controller).controllerAdvice(OperationsQueriesProblemHandler()).build()
        client.get().uri("/ops/v1/overview").header("Authorization","Bearer   ").exchange()
            .expectStatus().isUnauthorized
            .expectBody().jsonPath("$.detail").isEqualTo("Operations authentication required.")
        coVerify(exactly=0){auth.authenticate(any())}
    }
    @Test fun `invalid dates limits and cursor boundaries are bad requests`()=runBlocking {
        for(start in listOf("invalid","2025-01-01T00:00:00Z",to)){
            assertEquals(400,assertFailsWith<ResponseStatusException>{controller.overview(start,to,exchange("Bearer token"))}.statusCode.value())
        }
        assertEquals(400,assertFailsWith<ResponseStatusException>{controller.runs(null,null,null,null,from,to,null,101,exchange("Bearer token"))}.statusCode.value())
        assertEquals(400,assertFailsWith<ResponseStatusException>{controller.runs(null,null,null,null,null,null,"cursor",50,exchange("Bearer token"))}.statusCode.value())
    }
    @Test fun `cursor is canonical and bound to all filters`() {
        val boundary=OperationsBoundary(Instant.parse(from),id)
        assertEquals(boundary,OperationsBoundary.decode(boundary.encode("filter-one"),"filter-one"))
        assertFailsWith<IllegalArgumentException>{OperationsBoundary.decode(boundary.encode("filter-one"),"filter-two")}
        assertFailsWith<IllegalArgumentException>{OperationsBoundary.decode(boundary.encode("filter-one")+"=","filter-one")}
    }
    @Test fun `owner failure is unavailable rather than a zero filled panel`()=runBlocking {
        coEvery { identity.overview(access,any(),any()) } throws org.springframework.dao.DataAccessResourceFailureException("private detail")
        coEvery { explore.overview(access,any(),any()) } returns OperationsExploreOverview(0,0,0,0,0,0,null,emptyList(),emptyList())
        val response=controller.overview(from,to,exchange("Bearer token"))
        assertNull(response.identity)
        assertEquals(listOf("identity"),response.unavailablePanels)
        assertEquals(7,response.explore!!.daily.size)
        assertNull(response.explore.successRate)
    }
    @Test fun `audit records route and completion without credentials or search text`()=runBlocking {
        val logger=org.slf4j.LoggerFactory.getLogger(OperationsQueriesController::class.java) as ch.qos.logback.classic.Logger
        val logs=ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply{start()}
        logger.addAppender(logs)
        try {
            every { access.clientId } returns "monomer-operations-local"
            coEvery { identity.users(any(),any(),any(),any(),any(),any(),any()) } returns emptyList()
            val request=MockServerWebExchange.from(MockServerHttpRequest.get("/ops/v1/users?q=private-search").header("Authorization","Bearer private-credential").build())
            request.attributes[org.springframework.web.reactive.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE]="/ops/v1/users"
            controller.users("private-search",null,null,null,null,request)
            request.response.statusCode=org.springframework.http.HttpStatus.OK
            request.response.setComplete().block()
            val event=logs.list.single()
            val fields=event.mdcPropertyMap + event.keyValuePairs.associate { it.key to it.value }
            assertEquals("operations_read",event.formattedMessage)
            assertEquals("operations_read",fields["event"])
            assertEquals(request.request.id,fields["request_id"])
            assertEquals("/ops/v1/users",fields["route"])
            assertEquals(200,fields["status"])
            assertIs<Long>(fields["elapsed_ms"])
            assertFalse(fields.values.contains(id))
            assertFalse(fields.values.contains("private-search"))
            assertFalse(fields.values.contains("private-credential"))
        } finally { logger.detachAppender(logs) }
    }

    @Test fun `operations Problems use the response request id for both correlation fields`() {
        val request=exchange()
        val handler=OperationsQueriesProblemHandler()
        val problems=listOf(handler.unauthenticated(request),handler.forbidden(request),handler.malformed(request),
            handler.unavailable(request),handler.invalid(ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND),request))
        problems.forEach {
            assertEquals(request.request.id,it.properties?.get("requestId"))
            assertEquals(request.request.id,it.properties?.get("traceId"))
        }
    }

}
