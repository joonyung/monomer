package cc.monomer.control

import cc.monomer.control.dashboard.PostgresDashboardNotifications
import cc.monomer.dashboard.api.DashboardSessionId
import cc.monomer.execution.adapter.outbound.postgres.PostgresExecutionCancellationListener
import cc.monomer.execution.api.ExecutionRunId
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.sql.Connection
import java.sql.Statement
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.postgresql.PGConnection
import org.postgresql.PGNotification
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.boot.web.server.GracefulShutdownCallback
import org.springframework.boot.web.server.GracefulShutdownResult
import org.springframework.boot.web.server.WebServer
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle
import org.springframework.context.support.GenericApplicationContext
import org.springframework.test.util.ReflectionTestUtils

class NotificationListenerShutdownOrderingTest {
    @Test
    fun `notification subscriptions remain active until Boot HTTP drain completes`() = runBlocking {
        val changedRuns = LinkedBlockingQueue<ExecutionRunId>()
        val cancellationListener = PostgresExecutionCancellationListener(
            "jdbc:postgresql://localhost/unused", "unused", "unused",
            Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(10),
            { changedRuns.add(it) }, {},
        )
        val cancellationSubscription = Subscription(cancellationListener, "monomer_execution_cancel")
        val cancellationLifecycle = ExecutionCancellationListenerLifecycle(cancellationListener)
        val dashboardNotifications = PostgresDashboardNotifications(
            ControlProductPostgresProperties(
                "jdbc:postgresql://localhost/unused", "unused", "unused", 2,
                Duration.ofSeconds(1), Duration.ofMillis(250),
            ),
            ControlProductTransactionProperties(
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(100),
            ),
            Duration.ofMillis(10), mockk<DataSource>(),
        )
        val dashboardSubscription = Subscription(dashboardNotifications, "monomer_dashboard_changed")
        val drainCallback = CompletableFuture<GracefulShutdownCallback>()
        val webServer = mockk<WebServer>()
        every { webServer.shutDownGracefully(any()) } answers {
            drainCallback.complete(firstArg())
            Unit
        }
        val httpGracefulShutdown = WebServerGracefulShutdownLifecycle(webServer)
        val context = GenericApplicationContext()
        context.beanFactory.registerSingleton("cancellationListener", cancellationLifecycle)
        context.beanFactory.registerSingleton("dashboardNotifications", dashboardNotifications)
        context.beanFactory.registerSingleton("webServerGracefulShutdown", httpGracefulShutdown)
        var shutdown: CompletableFuture<Void>? = null
        try {
            context.refresh()
            assertEquals(WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE - 1, cancellationLifecycle.phase)
            assertEquals(WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE - 1, dashboardNotifications.phase)
            verify(exactly = 1) { cancellationSubscription.statement.execute("LISTEN monomer_execution_cancel") }
            verify(exactly = 1) { dashboardSubscription.statement.execute("LISTEN monomer_dashboard_changed") }

            shutdown = CompletableFuture.runAsync { context.close() }
            val completeDrain = drainCallback.get(5, TimeUnit.SECONDS)
            assertFalse(shutdown.isDone)
            assertTrue(cancellationLifecycle.isRunning)
            assertTrue(dashboardNotifications.isRunning)
            verify(exactly = 0) { cancellationSubscription.connection.abort(any()) }
            verify(exactly = 0) { dashboardSubscription.connection.abort(any()) }

            val runId = ExecutionRunId(Uuid.generateV7())
            cancellationSubscription.publish(runId.value.toString())
            assertEquals(runId, changedRuns.poll(5, TimeUnit.SECONDS))
            val sessionId = DashboardSessionId(Uuid.generateV7())
            val sessionChange = async(start = CoroutineStart.UNDISPATCHED) {
                dashboardNotifications.sessionChanges.first { it != null }
            }
            dashboardSubscription.publish("S|${sessionId.value}|UPDATED")
            assertEquals(sessionId to "UPDATED", withTimeout(5_000) { sessionChange.await() })

            completeDrain.shutdownComplete(GracefulShutdownResult.IDLE)
            shutdown.get(5, TimeUnit.SECONDS)
            assertFalse(cancellationLifecycle.isRunning)
            assertFalse(dashboardNotifications.isRunning)
            verify(exactly = 1) { cancellationSubscription.connection.abort(any()) }
            verify(exactly = 1) { dashboardSubscription.connection.abort(any()) }
        } finally {
            drainCallback.getNow(null)?.shutdownComplete(GracefulShutdownResult.IDLE)
            if (shutdown == null) context.close() else shutdown.get(5, TimeUnit.SECONDS)
            cancellationListener.close()
            dashboardNotifications.close()
        }
    }

    private class Subscription(listener: Any, private val channel: String) {
        val connection = mockk<Connection>(relaxed = true)
        val statement = mockk<Statement>(relaxed = true)
        private val notifications = LinkedBlockingQueue<PGNotification>()

        init {
            val postgresConnection = mockk<PGConnection>()
            val dataSource = mockk<PGSimpleDataSource>()
            every { dataSource.connection } returns connection
            every { connection.createStatement() } returns statement
            every { connection.unwrap(PGConnection::class.java) } returns postgresConnection
            every { postgresConnection.getNotifications(250) } answers {
                notifications.poll(250, TimeUnit.MILLISECONDS)?.let { arrayOf(it) } ?: emptyArray()
            }
            ReflectionTestUtils.setField(listener, "dataSource", dataSource)
        }

        fun publish(payload: String) {
            val notification = mockk<PGNotification>()
            every { notification.name } returns channel
            every { notification.parameter } returns payload
            notifications.add(notification)
        }
    }
}
