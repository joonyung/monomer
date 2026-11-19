package cc.monomer.control.auth

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import software.amazon.awssdk.core.SdkSystemSetting

@ResourceLock(Resources.SYSTEM_PROPERTIES)
@Timeout(15)
class SesInvitationDeliveryIntegrationTest {
    @Test
    fun `configured SES client does not retry server failures`() = runBlocking {
        val attempts = AtomicInteger()
        withServer(Duration.ofSeconds(3), { server ->
            server.createContext("/") { exchange ->
                attempts.incrementAndGet()
                exchange.requestBody.readAllBytes()
                exchange.responseHeaders.add("Content-Type", "application/json")
                val body = """{"message":"unavailable"}""".toByteArray()
                exchange.sendResponseHeaders(503, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }) { delivery ->
            assertThat(delivery.send("person@example.test", "Organization", null, Instant.now(), "test-token")).isFalse()
            assertThat(attempts.get()).isEqualTo(1)
        }
    }

    @Test
    fun `configured SES timeout returns false while server remains unresponsive`() = runBlocking {
        val attempts = AtomicInteger()
        val release = CountDownLatch(1)
        try {
            withServer(Duration.ofSeconds(1), { server ->
                server.createContext("/") { exchange ->
                    val attempt = attempts.incrementAndGet()
                    exchange.requestBody.readAllBytes()
                    if (attempt > 1) release.await(5, TimeUnit.SECONDS)
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    val body = """{"MessageId":"accepted"}""".toByteArray()
                    runCatching {
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                    exchange.close()
                }
            }) { delivery ->
                assertThat(delivery.send("person@example.test", "Organization", null, Instant.now(), "warmup-token")).isTrue()
                val start = System.nanoTime()
                assertThat(delivery.send("person@example.test", "Organization", null, Instant.now(), "timeout-token")).isFalse()
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3))
                assertThat(attempts.get()).isEqualTo(2)
                release.countDown()
            }
        } finally {
            release.countDown()
        }
    }

    private suspend fun withServer(
        timeout: Duration,
        configure: (HttpServer) -> Unit,
        test: suspend (SesInvitationDelivery) -> Unit,
    ) {
        check(System.getenv().keys.none { it.startsWith("AWS_ENDPOINT_URL") }) { "Hermetic SES test requires no AWS endpoint environment overrides" }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        server.executor = executor
        configure(server)
        server.start()
        val testProperties = mapOf(
            "aws.endpointUrl" to "http://127.0.0.1:${server.address.port}",
            SdkSystemSetting.AWS_ACCESS_KEY_ID.property() to "test-access-key",
            SdkSystemSetting.AWS_SECRET_ACCESS_KEY.property() to "test-secret-key",
            SdkSystemSetting.AWS_SESSION_TOKEN.property() to "test-session-token",
        )
        val previous = testProperties.keys.associateWith(System::getProperty)
        try {
            testProperties.forEach(System::setProperty)
            SesInvitationDelivery.create(
                InvitationDeliveryProperties.Ses("us-east-1", "invite@example.test", "", "", timeout),
                URI("http://localhost:3100/auth/callback"),
                true,
            ).use { test(it) }
        } finally {
            previous.forEach { (name, value) -> if (value == null) System.clearProperty(name) else System.setProperty(name, value) }
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
