package cc.monomer.control.pages.sharing

import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.observability.RequestLogContext
import cc.monomer.control.pagereader.PublicPagesAccess
import cc.monomer.control.publicapi.ControlPublicApiProperties
import cc.monomer.dashboard.api.DashboardOwnerPort
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.api.WorkspacePublicPagesQuery
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.pages.api.*
import io.mockk.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.uuid.Uuid
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange

class PublicPageHtmlTest {
    private val properties = ControlPublicApiProperties(URI("https://app.dev.monomer.cc"))
    private val workspace = WorkspaceId(Uuid.generateV7())
    private val page = Uuid.generateV7()
    private val publicQuery = mockk<WorkspacePublicPagesQuery>()
    private val pages = mockk<PagesCurrentReadPort>()
    private val dashboard = mockk<DashboardOwnerPort>()
    private val shell = mockk<PublicPageAppShell>()
    private val transactions = object : ConsistentSnapshotTransactionRunner {
        override suspend fun <T> run(action: suspend () -> T): T = action()
    }
    private val access = PublicPagesAccess(publicQuery, mockk<ControlAdmissionFacade>(), transactions)
    private val controller = PublicPageHtmlController(shell, cc.monomer.control.pages.publicpages.PageReaderService(access, pages, dashboard, mockk(), mockk(), publicQuery, java.time.Clock.systemUTC()), properties)
    private val template = "<!doctype html><html><head>${PublicPageHtml.START}<title>Monomer</title>${PublicPageHtml.END}</head><body><div id=\"root\"></div><script src=\"/assets/unchanged-abc123.js\"></script></body></html>"
    private fun exchange(head: Boolean = false) = MockServerWebExchange.from(
        if (head) MockServerHttpRequest.head("/test?session=private") else MockServerHttpRequest.get("/test?session=private"),
    )

    @Test fun `public metadata is escaped bounded and leaves boot assets untouched`() = runTest {
        coEvery { shell.read() } returns template
        coEvery { publicQuery.isPublic(workspace) } returns true
        coEvery { pages.tree(workspace.value) } returns CurrentPagesTree(1,
            listOf(CurrentPage(page, "DOCUMENT", "MARKDOWN", null, null, "<script>\u0000\"&'", "x\n".repeat(400))),
            listOf(CurrentPagePlacement(page, emptyList())))
        val response = controller.html(workspace.toString(), page.toString(), exchange())
        assertEquals(200, response.statusCode.value())
        val body = response.body!!
        assertTrue(body.contains("<title>&lt;script&gt;&quot;&amp;&#39;</title>"))
        assertTrue(body.contains("content=\"${"x".repeat(300)}\""))
        assertTrue(body.contains("href=\"https://app.dev.monomer.cc/workspaces/$workspace/pages/$page\""))
        assertFalse(body.contains("session=private"))
        assertTrue(body.contains("https://app.dev.monomer.cc/social/monomer.png"))
        assertEquals(template.substringAfter(PublicPageHtml.END), body.substringAfter(PublicPageHtml.END))
        assertEquals("no-store", response.headers.cacheControl)
        assertNull(response.headers["Set-Cookie"])
        coVerify(exactly = 0) { pages.content(workspace.value, page, 1) }
        verify { dashboard wasNot Called }
        val head = controller.html(workspace.toString(), page.toString(), exchange(true))
        assertEquals("", head.body)
        assertEquals(response.headers, head.headers)
    }

    @Test fun `private and missing Page return identical generic shell after fresh fetch`() = runTest {
        coEvery { shell.read() } returns template
        coEvery { publicQuery.isPublic(workspace) } returns false
        val private = controller.html(workspace.toString(), page.toString(), exchange())
        coEvery { publicQuery.isPublic(workspace) } returns true
        coEvery { pages.tree(workspace.value) } returns CurrentPagesTree(1, emptyList(), emptyList())
        val missing = controller.html(workspace.toString(), page.toString(), exchange())
        assertEquals(200, private.statusCode.value())
        assertEquals(private, missing)
        assertEquals(template, missing.body)
        assertFalse(missing.body!!.contains("canonical"))
        coVerify(exactly = 2) { shell.read() }
    }

    @Test fun `canonical UUID outside owner identity format is generic missing after shell fetch`() = runTest {
        coEvery { shell.read() } returns template
        val nil = controller.html("00000000-0000-0000-0000-000000000000", page.toString(), exchange())
        assertEquals(200, nil.statusCode.value())
        assertEquals(template, nil.body)
        verify { publicQuery wasNot Called }
        val v4 = WorkspaceId(Uuid.parse("12345678-1234-4123-8123-123456789012"))
        coEvery { publicQuery.isPublic(v4) } returns false
        val missing = controller.html(v4.toString(), page.toString(), exchange())
        assertEquals(nil, missing)
        coVerify(exactly = 2) { shell.read() }
        coVerify(exactly = 1) { publicQuery.isPublic(v4) }
    }

    @Test fun `shell failure produces generic 503 before owner metadata read`() = runTest {
        val failure = IllegalStateException("upstream internal diagnostics")
        coEvery { shell.read() } throws failure
        val log = RequestLogContext("srv_test", "user")
        val response = withRequestLog(log) { controller.html(workspace.toString(), page.toString(), exchange()) }
        assertEquals(503, response.statusCode.value())
        assertEquals("2", response.headers.getFirst("Retry-After"))
        assertEquals("no-store", response.headers.cacheControl)
        assertFalse(response.body!!.contains("diagnostics"))
        assertSame(failure, log.exception)
        verify { publicQuery wasNot Called }
    }

    @Test fun `shell timeout produces generic 503 recorded for the failure log`() = runTest {
        coEvery { shell.read() } coAnswers { kotlinx.coroutines.withTimeout(1) { kotlinx.coroutines.awaitCancellation() } }
        val log = RequestLogContext("srv_test", "user")
        val response = withRequestLog(log) { controller.html(workspace.toString(), page.toString(), exchange()) }
        assertEquals(503, response.statusCode.value())
        assertEquals("2", response.headers.getFirst("Retry-After"))
        assertIs<kotlinx.coroutines.TimeoutCancellationException>(log.exception)
        verify { publicQuery wasNot Called }
    }

    private suspend fun <T> withRequestLog(log: RequestLogContext, action: suspend () -> T): T {
        val accessor = RequestLogContext.Companion.Accessor()
        accessor.setValue(log)
        try {
            return action()
        } finally {
            accessor.setValue()
        }
    }

    @Test fun `metadata slot must be unique and inside head`() {
        PublicPageHtml.validateShell(template)
        for (invalid in listOf(template.replace(PublicPageHtml.START, ""), template + PublicPageHtml.END,
            template.replace(PublicPageHtml.START, PublicPageHtml.START.repeat(2)),
            "<head></head>${PublicPageHtml.START}${PublicPageHtml.END}")) {
            assertFailsWith<IllegalArgumentException> { PublicPageHtml.validateShell(invalid) }
        }
        assertFailsWith<IllegalArgumentException> { PublicPageHtml.origin(URI("https://evil@example.com")) }
        assertFailsWith<IllegalArgumentException> { PublicPageHtml.origin(URI("http://app.dev.monomer.cc")) }
    }

    @Test fun `oversized unacceptable or cancelled shell stops body subscription`() {
        for ((accepted, size) in listOf(false to 1, true to (256 * 1024 + 1))) {
            val body = BoundedAppShellBody(accepted)
            val subscription = mockk<Flow.Subscription>(relaxed = true)
            body.onSubscribe(subscription)
            if (accepted) body.onNext(listOf(ByteBuffer.wrap(ByteArray(size))))
            assertTrue(body.getBody().isCompletedExceptionally)
            verify { subscription.cancel() }
        }
        val cancelled = BoundedAppShellBody(true)
        val late = mockk<Flow.Subscription>(relaxed = true)
        cancelled.cancel()
        cancelled.onSubscribe(late)
        verify { late.cancel() }
        verify(exactly = 0) { late.request(any()) }
    }

    @Test fun `whole fetch timeout cancels pending HTTP and only requests fixed index`() = runTest {
        val client = mockk<HttpClient>()
        val future = CompletableFuture<HttpResponse<ByteArray>>()
        val request = slot<HttpRequest>()
        every { client.sendAsync(capture(request), any<HttpResponse.BodyHandler<ByteArray>>()) } returns future
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> { PublicPageAppShell(properties, client).read() }
        assertTrue(future.isCancelled)
        assertEquals(URI("https://app.dev.monomer.cc/index.html"), request.captured.uri())
        assertEquals("GET", request.captured.method())
        assertFalse(request.captured.headers().firstValue("Authorization").isPresent)
        assertFalse(request.captured.headers().firstValue("Cookie").isPresent)
    }
    @Test fun `real slow shell body is disconnected by total fetch deadline`() = kotlinx.coroutines.runBlocking {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val disconnected = java.util.concurrent.CountDownLatch(1)
        server.createContext("/index.html") { exchange ->
            try {
                exchange.responseHeaders.add("Content-Type", "text/html")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { output ->
                    repeat(60) {
                        output.write(ByteArray(1024) { 32 })
                        output.flush()
                        Thread.sleep(100)
                    }
                }
            } catch (_: java.io.IOException) {
                disconnected.countDown()
            } finally {
                exchange.close()
            }
        }
        server.start()
        val actual = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
        val client = mockk<HttpClient>()
        every { client.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<ByteArray>>()) } answers {
            actual.sendAsync(HttpRequest.newBuilder(URI("http://127.0.0.1:${server.address.port}/index.html")).GET().build(), secondArg())
        }
        try {
            assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> { PublicPageAppShell(properties, client).read() }
            assertTrue(disconnected.await(3, java.util.concurrent.TimeUnit.SECONDS), "HTTP cancellation must close the streaming upstream")
        } finally {
            server.stop(0)
            actual.close()
        }
    }

}
