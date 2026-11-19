package cc.monomer.execution

import cc.monomer.execution.adapter.outbound.postgres.PostgresExecutionCancellationListener
import cc.monomer.execution.api.ExecutionRunId
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresExecutionCancellationListenerIntegrationTest {
    private val postgres = PostgreSQLContainer(
        DockerImageName.parse(
            "postgres:16.14-alpine3.24@" +
                "sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777",
        ).asCompatibleSubstituteFor("postgres"),
    ).withDatabaseName("execution_cancellation_ephemeral")
        .withUsername("execution_cancellation")
        .withPassword("execution_cancellation_test_only")

    @BeforeAll
    fun startPostgres() = postgres.start()

    @AfterAll
    fun stopPostgres() = postgres.stop()

    @Test
    fun `subscription is established before start returns and only committed notifications are delivered`() {
        val received = LinkedBlockingQueue<ExecutionRunId>()
        val reconciled = LinkedBlockingQueue<Unit>()
        listener({ received.add(it) }, { reconciled.add(Unit) }).use { listener ->
            listener.start()
            assertEquals(Unit, reconciled.poll())
            val rolledBack = ExecutionRunId.generate()
            val committed = ExecutionRunId.generate()
            connection().use { publisher ->
                publisher.autoCommit = false
                notify(publisher, rolledBack.toString())
                publisher.rollback()
                notify(publisher, "invalid-run-id")
                notify(publisher, committed.toString())
                assertNull(received.poll(150, TimeUnit.MILLISECONDS))
                publisher.commit()
            }
            assertEquals(committed, received.poll(5, TimeUnit.SECONDS))
            assertNull(received.poll(150, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun `connection loss resubscribes and rechecks live runs before processing further changes`() {
        val received = LinkedBlockingQueue<ExecutionRunId>()
        val reconciled = LinkedBlockingQueue<Unit>()
        listener({ received.add(it) }, { reconciled.add(Unit) }).use { listener ->
            listener.start()
            assertEquals(Unit, reconciled.poll())
            connection().use { controller ->
                controller.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
                            "WHERE datname = current_database() AND query = 'LISTEN monomer_execution_cancel'",
                    ).use { rows ->
                        assertTrue(rows.next())
                        assertTrue(rows.getBoolean(1))
                    }
                }
            }
            assertNotNull(reconciled.poll(5, TimeUnit.SECONDS))
            val changed = ExecutionRunId.generate()
            connection().use { notify(it, changed.toString()) }
            assertEquals(changed, received.poll(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `failed database reconciliation is retried after resubscription`() {
        val failReconciliation = AtomicBoolean(true)
        val reconciled = LinkedBlockingQueue<Unit>()
        listener(
            onRunChanged = { throw IllegalStateException("test reconciliation failure") },
            onReconnected = {
                if (failReconciliation.getAndSet(false)) {
                    throw IllegalStateException("test startup reconciliation failure")
                }
                reconciled.add(Unit)
            },
        ).use { listener ->
            assertFailsWith<IllegalStateException> { listener.start() }
            listener.start()
            assertEquals(Unit, reconciled.poll())
            connection().use { notify(it, ExecutionRunId.generate().toString()) }
            assertNotNull(reconciled.poll(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `startup failure is surfaced and close releases the dedicated subscription`() {
        listener({}, {}, password = "incorrect-test-password").use { listener ->
            assertFailsWith<IllegalStateException> { listener.start() }
        }
        val listener = listener({}, {})
        listener.start()
        val started = System.nanoTime()
        listener.close()
        listener.close()
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3))
        connection().use { controller ->
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var subscriptions: Int
            do {
                subscriptions = controller.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() " +
                            "AND query = 'LISTEN monomer_execution_cancel'",
                    ).use { rows ->
                        assertTrue(rows.next())
                        rows.getInt(1)
                    }
                }
                if (subscriptions != 0) Thread.sleep(10)
            } while (subscriptions != 0 && System.nanoTime() < deadline)
            assertEquals(0, subscriptions)
        }
    }

    private fun listener(
        onRunChanged: (ExecutionRunId) -> Unit,
        onReconnected: () -> Unit,
        password: String = postgres.password,
    ) = PostgresExecutionCancellationListener(
        jdbcUrl = postgres.jdbcUrl,
        username = postgres.username,
        password = password,
        connectionTimeout = Duration.ofSeconds(1),
        statementTimeout = Duration.ofSeconds(1),
        reconnectDelay = Duration.ofMillis(20),
        onRunChanged = onRunChanged,
        onReconnected = onReconnected,
    )

    private fun connection(): Connection =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun notify(connection: Connection, payload: String) {
        connection.prepareStatement("SELECT pg_notify('monomer_execution_cancel', ?)").use {
            it.setString(1, payload)
            it.execute()
        }
    }
}
