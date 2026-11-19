package cc.monomer.control.dashboard

import cc.monomer.control.ControlProductPostgresProperties
import cc.monomer.control.ControlProductTransactionProperties
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardSessionId
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.uuid.Uuid
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.postgresql.PGConnection
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.SmartLifecycle
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionSynchronizationManager

class PostgresDashboardNotifications(
    postgres: ControlProductPostgresProperties,
    transaction: ControlProductTransactionProperties,
    private val reconnectDelay: Duration,
    private val publicationDataSource: DataSource,
) : AutoCloseable, SmartLifecycle {
    private val dataSource = PGSimpleDataSource().apply {
        setURL(postgres.jdbcUrl)
        user = postgres.username
        password = postgres.password
        connectTimeout = timeoutSeconds(postgres.connectionTimeout)
        loginTimeout = timeoutSeconds(postgres.connectionTimeout)
        socketTimeout = timeoutSeconds(transaction.statementTimeout)
        options = "${options.orEmpty()} -c statement_timeout=${transaction.statementTimeout.toMillis()}".trim()
    }
    private val statementTimeoutSeconds = timeoutSeconds(transaction.statementTimeout)
    private val mutableSessionChanges = MutableSharedFlow<Pair<DashboardSessionId, String>?>(
        replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val mutableDraftChanges = MutableSharedFlow<Pair<DashboardId, String>?>(
        replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val sessionChanges: SharedFlow<Pair<DashboardSessionId, String>?> = mutableSessionChanges.asSharedFlow()
    val draftChanges: SharedFlow<Pair<DashboardId, String>?> = mutableDraftChanges.asSharedFlow()
    private val running = AtomicBoolean(false)
    private val connection = AtomicReference<Connection?>()
    private var worker: Thread? = null

    init {
        require(reconnectDelay.toMillis() > 0)
    }

    fun publishSession(id: DashboardSessionId, reason: String) = publish("S", id.value, reason)

    fun publishDraft(id: DashboardId, reason: String) = publish("D", id.value, reason)

    override fun getPhase(): Int = WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE - 1

    override fun isRunning(): Boolean = running.get()

    @Synchronized
    override fun start() {
        if (running.get()) return
        check(worker?.isAlive != true) { "Dashboard notification listener is still stopping" }
        try {
            connection.set(openSubscription())
            invalidateAll()
            running.set(true)
            worker = Thread(::listen, "monomer-dashboard-notifications").also {
                it.isDaemon = true
                it.start()
            }
        } catch (failure: Exception) {
            running.set(false)
            closeConnection()
            throw IllegalStateException("Dashboard notification subscription startup failed: ${failure.javaClass.simpleName}")
        }
    }

    override fun stop() = close()

    override fun stop(callback: Runnable) {
        close()
        callback.run()
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

    private fun publish(kind: String, id: Uuid, reason: String) {
        require(reasonPattern.matches(reason)) { "Invalid dashboard notification reason" }
        val payload = "$kind|$id|$reason"
        val transactionActive = TransactionSynchronizationManager.isActualTransactionActive()
        if (transactionActive) {
            // getConnection can enlist a different DataSource; require the owner's existing binding first.
            check(TransactionSynchronizationManager.hasResource(publicationDataSource)) {
                "Dashboard notifications require the publication DataSource transaction"
            }
        }
        try {
            val opened = DataSourceUtils.getConnection(publicationDataSource)
            try {
                if (transactionActive) {
                    check(DataSourceUtils.isConnectionTransactional(opened, publicationDataSource)) {
                        "Dashboard notifications require the publication DataSource transaction"
                    }
                } else {
                    opened.autoCommit = true
                }
                opened.prepareStatement("SELECT pg_notify(?, ?)").use { statement ->
                    statement.queryTimeout = statementTimeoutSeconds
                    statement.setString(1, channel)
                    statement.setString(2, payload)
                    statement.execute()
                }
            } finally {
                DataSourceUtils.releaseConnection(opened, publicationDataSource)
            }
        } catch (failure: SQLException) {
            if (transactionActive) throw failure
            logger.warning("Dashboard notification publication failed: ${failure.javaClass.simpleName}")
            invalidateAll()
        } catch (failure: CannotGetJdbcConnectionException) {
            if (transactionActive) throw failure
            logger.warning("Dashboard notification publication failed: ${failure.javaClass.simpleName}")
            invalidateAll()
        }
    }

    private fun invalidateAll() {
        mutableSessionChanges.tryEmit(null)
        mutableDraftChanges.tryEmit(null)
    }

    private fun openSubscription(): Connection {
        val opened = dataSource.connection
        try {
            opened.autoCommit = true
            opened.createStatement().use { statement ->
                statement.queryTimeout = statementTimeoutSeconds
                statement.execute("LISTEN $channel")
            }
            return opened
        } catch (failure: Exception) {
            runCatching { opened.abort { command -> command.run() } }
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
                        invalidateAll()
                    }
                    val notifications = subscribed.unwrap(PGConnection::class.java).getNotifications(250)
                    for (notification in notifications.orEmpty()) {
                        if (!running.get()) return
                        if (notification.name == channel) receive(notification.parameter)
                    }
                } catch (failure: Exception) {
                    closeConnection()
                    if (!running.get()) return
                    logger.warning("Dashboard notification subscription failed: ${failure.javaClass.simpleName}")
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

    private fun receive(payload: String) {
        if (payload.length > 103) return
        val parts = payload.split('|')
        if (parts.size != 3 || !reasonPattern.matches(parts[2])) return
        try {
            val id = Uuid.parse(parts[1])
            when (parts[0]) {
                "S" -> mutableSessionChanges.tryEmit(DashboardSessionId(id) to parts[2])
                "D" -> mutableDraftChanges.tryEmit(DashboardId(id) to parts[2])
            }
        } catch (_: IllegalArgumentException) {
            return
        }
    }

    private fun closeConnection() {
        connection.getAndSet(null)?.let { opened ->
            runCatching { opened.abort { command -> command.run() } }
                .onFailure {
                    logger.warning("Dashboard notification connection close failed: ${it.javaClass.simpleName}")
                }
        }
    }

    private companion object {
        const val channel = "monomer_dashboard_changed"
        val reasonPattern = Regex("[A-Z][A-Z_]{0,63}")
        val logger: Logger = Logger.getLogger(PostgresDashboardNotifications::class.java.name)

        fun timeoutSeconds(timeout: Duration): Int {
            require(timeout.toMillis() > 0)
            return Math.toIntExact((timeout.toMillis() - 1) / 1_000 + 1)
        }
    }
}
