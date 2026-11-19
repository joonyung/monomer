package cc.monomer.execution.adapter.outbound.postgres

import cc.monomer.execution.api.ExecutionRunId
import java.sql.Connection
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import kotlin.uuid.Uuid
import org.postgresql.PGConnection
import org.postgresql.ds.PGSimpleDataSource

class PostgresExecutionCancellationListener(
    jdbcUrl: String,
    username: String,
    password: String,
    connectionTimeout: Duration,
    statementTimeout: Duration,
    private val reconnectDelay: Duration,
    private val onRunChanged: (ExecutionRunId) -> Unit,
    private val onReconnected: () -> Unit,
) : AutoCloseable {
    private val dataSource = PGSimpleDataSource().apply {
        setURL(jdbcUrl)
        user = username
        this.password = password
        connectTimeout = timeoutSeconds(connectionTimeout)
        loginTimeout = timeoutSeconds(connectionTimeout)
        socketTimeout = timeoutSeconds(statementTimeout)
        options = "${options.orEmpty()} -c statement_timeout=${statementTimeout.toMillis()}".trim()
    }
    private val statementTimeoutSeconds = timeoutSeconds(statementTimeout)
    private val running = AtomicBoolean(false)
    private val connection = AtomicReference<Connection?>()
    private var worker: Thread? = null

    init {
        require(reconnectDelay.toMillis() > 0)
    }

    @Synchronized
    fun start() {
        if (running.get()) return
        check(worker?.isAlive != true) { "Execution cancellation listener is still stopping" }
        try {
            connection.set(openSubscription())
            onReconnected()
            running.set(true)
            worker = Thread(::listen, "monomer-execution-cancellation").also {
                it.isDaemon = true
                it.start()
            }
        } catch (failure: Exception) {
            running.set(false)
            closeConnection()
            throw IllegalStateException("Execution cancellation subscription startup failed", failure)
        }
    }

    @Synchronized
    override fun close() {
        running.set(false)
        worker?.interrupt()
        closeConnection()
        try {
            worker?.join(2_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun openSubscription(): Connection {
        val opened = dataSource.connection
        try {
            opened.autoCommit = true
            opened.createStatement().use { statement ->
                statement.queryTimeout = statementTimeoutSeconds
                statement.execute("LISTEN monomer_execution_cancel")
            }
            return opened
        } catch (failure: Exception) {
            runCatching { opened.close() }
            throw failure
        }
    }

    private fun listen() {
        try {
            while (running.get()) {
                try {
                    val subscribed = connection.get() ?: openSubscription().also {
                        connection.set(it)
                        if (!running.get()) return
                        onReconnected()
                    }
                    val notifications = subscribed.unwrap(PGConnection::class.java).getNotifications(250)
                    for (notification in notifications.orEmpty()) {
                        if (!running.get()) return
                        if (notification.name != "monomer_execution_cancel") continue
                        val runId = try {
                            ExecutionRunId(Uuid.parse(notification.parameter))
                        } catch (_: IllegalArgumentException) {
                            continue
                        }
                        onRunChanged(runId)
                    }
                } catch (failure: Exception) {
                    closeConnection()
                    if (!running.get()) return
                    logger.warning("Execution cancellation subscription failed: ${failure.javaClass.simpleName}")
                    try {
                        Thread.sleep(reconnectDelay.toMillis())
                    } catch (_: InterruptedException) {
                        return
                    }
                }
            }
        } finally {
            closeConnection()
            running.set(false)
        }
    }

    private fun closeConnection() {
        connection.getAndSet(null)?.let { opened ->
            runCatching { opened.abort { command -> command.run() } }
                .onFailure {
                    logger.warning("Execution cancellation connection close failed: ${it.javaClass.simpleName}")
                }
        }
    }

    private companion object {
        val logger: Logger = Logger.getLogger(PostgresExecutionCancellationListener::class.java.name)

        fun timeoutSeconds(timeout: Duration): Int {
            require(timeout.toMillis() > 0)
            return Math.toIntExact((timeout.toMillis() - 1) / 1_000 + 1)
        }
    }
}
