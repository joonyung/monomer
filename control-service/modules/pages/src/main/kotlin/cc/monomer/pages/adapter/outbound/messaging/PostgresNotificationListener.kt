package cc.monomer.pages.adapter.outbound.messaging

import cc.monomer.pages.adapter.outbound.postgres.PostgresProperties
import cc.monomer.pages.adapter.outbound.messaging.pages.PostgresPagesDirectoryEventBus
import jakarta.annotation.PreDestroy
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * One long-lived PG connection outside any pool. LISTENs on every channel with a registered
 * handler; polls `pg.getNotifications(timeout)` on a dedicated daemon thread and routes each
 * notification to its handlers. Reconnects with exponential backoff on connection loss.
 *
 * Lifecycle: open + LISTEN on [ApplicationReadyEvent] (so every bus bean has registered its
 * handler via its own `@PostConstruct` first), stop on `@PreDestroy`.
 */
class PostgresNotificationListener(
    private val props: PostgresProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val handlers: MutableMap<String, CopyOnWriteArrayList<(String) -> Unit>> = ConcurrentHashMap()
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    fun registerHandler(channel: String, handler: (payload: String) -> Unit) {
        handlers.computeIfAbsent(channel) { CopyOnWriteArrayList() }.add(handler)
    }

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = thread(isDaemon = true, name = "pg-notify-listener", start = true) {
            loop()
        }
    }

    @PreDestroy
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        worker?.interrupt()
        worker?.join(3_000)
    }

    private fun loop() {
        var backoffMs = INITIAL_BACKOFF_MS
        while (running.get()) {
            var conn: Connection? = null
            try {
                conn = openConnection()
                val pg = conn.unwrap(PGConnection::class.java)
                for (channel in handlers.keys) {
                    conn.createStatement().use { it.execute("""LISTEN "$channel"""") }
                    logger.atInfo()
                        .addKeyValue("event", "pages_postgres_notification_listening")
                        .addKeyValue("channel", PostgresPagesDirectoryEventBus.CHANNEL.takeIf { it == channel })
                        .addKeyValue("pid", pg.backendPID)
                        .log("pages_postgres_notification_listening")
                }
                backoffMs = INITIAL_BACKOFF_MS
                pollLoop(conn, pg)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (t: Throwable) {
                logger.atWarn()
                    .addKeyValue("event", "pages_postgres_notification_listener_failed")
                    .addKeyValue("backoff_ms", backoffMs)
                    .setCause(t)
                    .log("pages_postgres_notification_listener_failed")
                safeClose(conn)
                if (!running.get()) return
                try {
                    Thread.sleep(backoffMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            } finally {
                safeClose(conn)
            }
        }
    }

    private fun pollLoop(conn: Connection, pg: PGConnection) {
        while (running.get()) {
            val notes = pg.getNotifications(POLL_TIMEOUT_MS)
            if (notes.isNullOrEmpty()) continue
            for (n in notes) {
                val channel = n.name
                val payload = n.parameter ?: ""
                val subscribers = handlers[channel] ?: continue
                for (h in subscribers) {
                    try {
                        h(payload)
                    } catch (t: Throwable) {
                        logger.atWarn()
                            .addKeyValue("event", "pages_postgres_notification_handler_failed")
                            .addKeyValue("channel", PostgresPagesDirectoryEventBus.CHANNEL.takeIf { it == channel })
                            .setCause(t)
                            .log("pages_postgres_notification_handler_failed")
                    }
                }
            }
        }
    }

    private fun openConnection(): Connection {
        return DriverManager.getConnection(props.jdbcUrl, props.username, props.password).apply {
            autoCommit = true
        }
    }

    private fun safeClose(conn: Connection?) {
        runCatching { conn?.close() }
    }

    companion object {
        private const val POLL_TIMEOUT_MS: Int = 5_000
        private const val INITIAL_BACKOFF_MS: Long = 500
        private const val MAX_BACKOFF_MS: Long = 30_000
    }
}
