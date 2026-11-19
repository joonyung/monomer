package cc.monomer.execution.driver.trino

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrinoJdbcDestinationTest {
    @Test
    fun redirectsCannotReachAnUnapprovedLoopbackEndpoint() {
        withServers { source, target, targetRequests, sourceRequests ->
            val factory = TrinoJdbcConnectionFactory(setOf(source.address))
            factory.open("jdbc:trino://localhost:${source.address.port}", properties().apply {
                setProperty("socksProxy", "127.0.0.1:${target.address.port}")
            }).use { connection ->
                assertFailsWith<SQLException> { connection.createStatement().use { it.executeQuery("SELECT 1") } }
            }
            assertTrue(sourceRequests.get() > 0)
            assertEquals(0, targetRequests.get())
        }
    }

    @Test
    fun approvedRedirectDestinationsStillExecuteThroughTheGuardedClient() {
        withServers { source, target, targetRequests, sourceRequests ->
            val factory = TrinoJdbcConnectionFactory(setOf(source.address, target.address))
            factory.open("jdbc:trino://localhost:${source.address.port}", properties()).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT 1").use { rows ->
                        assertTrue(rows.next())
                        assertEquals(1, rows.getInt(1))
                    }
                }
            }
            assertTrue(sourceRequests.get() > 0)
            assertTrue(targetRequests.get() > 0)
        }
    }

    private fun properties() = Properties().apply {
        setProperty("user", "fixture")
        setProperty("timeout", "1s")
    }

    private fun withServers(operation: (HttpServer, HttpServer, AtomicInteger, AtomicInteger) -> Unit) {
        val source = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = AtomicInteger()
        val sourceRequests = AtomicInteger()
        source.createContext("/") { exchange ->
            sourceRequests.incrementAndGet()
            exchange.requestBody.close()
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${target.address.port}/result")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        target.createContext("/") { exchange ->
            requests.incrementAndGet()
            val body = """{"id":"fixture","infoUri":"http://localhost/fixture","columns":[{"name":"value","type":"bigint","typeSignature":{"rawType":"bigint","arguments":[]}}],"data":[[1]],"stats":{"state":"FINISHED","queued":false,"scheduled":true,"nodes":1,"totalSplits":1,"queuedSplits":0,"runningSplits":0,"completedSplits":1,"cpuTimeMillis":0,"wallTimeMillis":0,"queuedTimeMillis":0,"elapsedTimeMillis":0,"processedRows":1,"processedBytes":8,"physicalInputBytes":8,"peakMemoryBytes":0,"spilledBytes":0}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        source.start()
        target.start()
        try { operation(source, target, requests, sourceRequests) } finally {
            source.stop(0)
            target.stop(0)
        }
    }
}
