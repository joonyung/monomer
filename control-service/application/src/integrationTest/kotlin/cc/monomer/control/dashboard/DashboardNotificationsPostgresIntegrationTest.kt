package cc.monomer.control.dashboard

import cc.monomer.control.ControlProductPostgresProperties
import cc.monomer.control.ControlProductTransactionProperties
import cc.monomer.control.EphemeralPostgresAuthoritySupport
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardSessionId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.measureTime
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.jdbc.datasource.DriverManagerDataSource

class DashboardNotificationsPostgresIntegrationTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-11T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `independent adapters deliver typed session and draft changes and late subscribers converge`() = runBlocking {
        adapter().use { sender -> adapter().use { receiver ->
            sender.start()
            receiver.start()
            val sessionId = DashboardSessionId.generate()
            val dashboardId = DashboardId.generate()
            val sessionSender = DashboardSessionEventHub(clock, sender)
            val draftSender = DashboardDraftEventHub(clock, sender)
            val sessionReceiver = DashboardSessionEventHub(clock, receiver)
            val draftReceiver = DashboardDraftEventHub(clock, receiver)
            val sessions = Channel<DashboardSessionInvalidation>(Channel.UNLIMITED)
            val drafts = Channel<DashboardDraftInvalidation>(Channel.UNLIMITED)
            val sessionCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                sessionReceiver.subscribe(sessionId).collect { sessions.send(it) }
            }
            val draftCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                draftReceiver.subscribe(dashboardId).collect { drafts.send(it) }
            }
            try {
                assertEquals("RECONNECTED", withTimeout(5_000) { sessions.receive() }.reason)
                assertEquals("RECONNECTED", withTimeout(5_000) { drafts.receive() }.reason)
                sessionSender.emit(sessionId, "QUERY_RUN_SETTLED")
                draftSender.emit(dashboardId, "DRAFT_CHANGED")
                assertEquals(
                    DashboardSessionInvalidation(sessionId, "QUERY_RUN_SETTLED", clock.instant()),
                    withTimeout(5_000) { sessions.receive() },
                )
                assertEquals(
                    DashboardDraftInvalidation(dashboardId, "DRAFT_CHANGED", clock.instant()),
                    withTimeout(5_000) { drafts.receive() },
                )
                assertEquals("RECONNECTED", withTimeout(5_000) { sessionReceiver.subscribe(sessionId).first() }.reason)
                assertEquals("RECONNECTED", withTimeout(5_000) { draftReceiver.subscribe(dashboardId).first() }.reason)

                sessionSender.emit(DashboardSessionId.generate(), "QUERY_RESOLVED")
                draftSender.emit(DashboardId.generate(), "DRAFT_CHANGED")
                notify(
                    "S|$sessionId|lowercase", "D|$dashboardId|DRAFT_CHANGED|extra", "S|not-a-uuid|QUERY_RESOLVED",
                    "S|00000000-0000-0000-0000-000000000000|QUERY_RESOLVED", "X|$sessionId|QUERY_RESOLVED",
                    "S|$sessionId|${"A".repeat(65)}", "D|$dashboardId|lowercase",
                    "S|$sessionId|BARRIER", "D|$dashboardId|BARRIER",
                )
                assertEquals("BARRIER", withTimeout(5_000) { sessions.receive() }.reason)
                assertEquals("BARRIER", withTimeout(5_000) { drafts.receive() }.reason)
            } finally {
                sessionCollector.cancel()
                draftCollector.cancel()
            }
        } }
    }

    @Test
    fun `rollback publishes nothing and settlement helpers publish only after commit`() = runBlocking {
        adapter().use { sender -> adapter().use { receiver ->
            sender.start()
            receiver.start()
            val sessionId = DashboardSessionId.generate()
            val dashboardId = DashboardId.generate()
            val sessionSender = DashboardSessionEventHub(clock, sender)
            val draftSender = DashboardDraftEventHub(clock, sender)
            val sessions = Channel<DashboardSessionInvalidation>(Channel.UNLIMITED)
            val drafts = Channel<DashboardDraftInvalidation>(Channel.UNLIMITED)
            val sessionCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                DashboardSessionEventHub(clock, receiver).subscribe(sessionId).collect { sessions.send(it) }
            }
            val draftCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                DashboardDraftEventHub(clock, receiver).subscribe(dashboardId).collect { drafts.send(it) }
            }
            try {
                assertEquals("RECONNECTED", withTimeout(5_000) { sessions.receive() }.reason)
                assertEquals("RECONNECTED", withTimeout(5_000) { drafts.receive() }.reason)
                postgres.transactionOperations.executeWithoutResult { status ->
                    sessionSender.emit(sessionId, "QUERY_RESOLVED")
                    draftSender.emit(dashboardId, "DRAFT_CHANGED")
                    publishSessionSettlementForTest(sessionSender, sessionId, "QUERY_RUN_SETTLED")
                    publishDraftSettlementForTest(draftSender, dashboardId, "QUERY_RUN_SETTLED")
                    status.setRollbackOnly()
                }
                notify("S|$sessionId|ROLLBACK_BARRIER", "D|$dashboardId|ROLLBACK_BARRIER")
                assertEquals("ROLLBACK_BARRIER", withTimeout(5_000) { sessions.receive() }.reason)
                assertEquals("ROLLBACK_BARRIER", withTimeout(5_000) { drafts.receive() }.reason)

                postgres.transactionOperations.executeWithoutResult {
                    publishSessionSettlementForTest(sessionSender, sessionId, "QUERY_RUN_SETTLED")
                    publishDraftSettlementForTest(draftSender, dashboardId, "QUERY_RUN_SETTLED")
                    notify("S|$sessionId|BEFORE_COMMIT_BARRIER", "D|$dashboardId|BEFORE_COMMIT_BARRIER")
                }
                assertEquals("BEFORE_COMMIT_BARRIER", withTimeout(5_000) { sessions.receive() }.reason)
                assertEquals("BEFORE_COMMIT_BARRIER", withTimeout(5_000) { drafts.receive() }.reason)
                assertEquals("QUERY_RUN_SETTLED", withTimeout(5_000) { sessions.receive() }.reason)
                assertEquals("QUERY_RUN_SETTLED", withTimeout(5_000) { drafts.receive() }.reason)
            } finally {
                sessionCollector.cancel()
                draftCollector.cancel()
            }
        } }
    }

    @Test
    fun `terminated listener reconnects existing subscribers and resumes cross instance delivery`() = runBlocking {
        adapter().use { sender -> adapter().use { receiver ->
            sender.start()
            val existingListeners = listenerPids()
            receiver.start()
            val receiverPid = (listenerPids() - existingListeners).single()
            val sessionId = DashboardSessionId.generate()
            val dashboardId = DashboardId.generate()
            val sessions = Channel<DashboardSessionInvalidation>(Channel.UNLIMITED)
            val drafts = Channel<DashboardDraftInvalidation>(Channel.UNLIMITED)
            val sessionCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                DashboardSessionEventHub(clock, receiver).subscribe(sessionId).collect { sessions.send(it) }
            }
            val draftCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                DashboardDraftEventHub(clock, receiver).subscribe(dashboardId).collect { drafts.send(it) }
            }
            try {
                assertEquals("RECONNECTED", withTimeout(5_000) { sessions.receive() }.reason)
                assertEquals("RECONNECTED", withTimeout(5_000) { drafts.receive() }.reason)
                postgres.dataSource.connection.use { connection ->
                    connection.prepareStatement("SELECT pg_terminate_backend(?)").use { statement ->
                        statement.setInt(1, receiverPid)
                        statement.executeQuery().use { result ->
                            assertTrue(result.next())
                            assertTrue(result.getBoolean(1))
                        }
                    }
                }
                assertEquals("RECONNECTED", withTimeout(5_000) { sessions.receive() }.reason)
                assertEquals("RECONNECTED", withTimeout(5_000) { drafts.receive() }.reason)
                assertTrue(receiver.isRunning())
                assertFalse(receiverPid in listenerPids())
                sender.publishSession(sessionId, "QUERY_RUN_SETTLED")
                sender.publishDraft(dashboardId, "DRAFT_CHANGED")
                assertEquals("QUERY_RUN_SETTLED", withTimeout(5_000) { sessions.receive() }.reason)
                assertEquals("DRAFT_CHANGED", withTimeout(5_000) { drafts.receive() }.reason)
            } finally {
                sessionCollector.cancel()
                draftCollector.cancel()
            }
        } }
    }

    @Test
    fun `lifecycle starts synchronously closes boundedly and fails startup for unavailable database`() {
        adapter().use { notifications ->
            assertEquals(WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE - 1, notifications.getPhase())
            notifications.start()
            notifications.start()
            assertTrue(notifications.isRunning())
            assertTrue(measureTime { notifications.close() }.inWholeMilliseconds < 3_000)
            assertFalse(notifications.isRunning())
            notifications.start()
            assertTrue(notifications.isRunning())
            var callbackInvoked = false
            notifications.stop { callbackInvoked = true }
            assertTrue(callbackInvoked)
            assertFalse(notifications.isRunning())
        }
        val properties = properties()
        val unavailable = properties.copy(
            jdbcUrl = properties.jdbcUrl.substringBefore('?').substringBeforeLast('/') + "/missing_dashboard_notification_test",
        )
        adapter(unavailable).use { notifications ->
            val elapsed = measureTime {
                assertFailsWith<IllegalStateException> { notifications.start() }
            }
            assertTrue(elapsed.inWholeMilliseconds < 5_000)
            assertFalse(notifications.isRunning())
        }
    }

    @Test
    fun `composition starts LISTEN before later server lifecycle and prevents readiness on failure`() {
        for (available in listOf(true, false)) {
            val postgresProperties = properties().let {
                if (available) it else it.copy(jdbcUrl = it.jdbcUrl.substringBefore('?').substringBeforeLast('/') + "/missing_dashboard_notification_test")
            }
            val notifications = DashboardApplicationComposition().postgresDashboardNotifications(
                postgres.dataSource,
                cc.monomer.control.ControlProductAuthorityProperties(
                    postgresProperties,
                    ControlProductTransactionProperties(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(100)),
                ),
                cc.monomer.control.testControlRuntimeProperties(),
            )
            var serving = false
            org.springframework.context.support.GenericApplicationContext().use { context ->
                context.registerBean("notifications", PostgresDashboardNotifications::class.java, java.util.function.Supplier { notifications })
                context.registerBean("server", org.springframework.context.SmartLifecycle::class.java, java.util.function.Supplier {
                    object : org.springframework.context.SmartLifecycle {
                        override fun start() {
                            assertTrue(notifications.isRunning())
                            assertTrue(listenerPids().isNotEmpty())
                            serving = true
                        }
                        override fun stop() { serving = false }
                        override fun isRunning() = serving
                        override fun getPhase() = Int.MAX_VALUE - 1
                    }
                })
                if (available) {
                    context.refresh()
                    assertTrue(serving)
                } else {
                    assertFailsWith<org.springframework.context.ApplicationContextException> { context.refresh() }
                    assertFalse(serving)
                }
            }
            assertFalse(notifications.isRunning())
        }
    }

    private fun notify(vararg payloads: String) {
        postgres.dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT pg_notify('monomer_dashboard_changed', ?)").use { statement ->
                payloads.forEach { payload ->
                    statement.setString(1, payload)
                    statement.execute()
                }
            }
        }
    }

    private fun listenerPids(): Set<Int> = postgres.dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT pid FROM pg_stat_activity WHERE datname = current_database() AND query = 'LISTEN monomer_dashboard_changed'",
        ).use { statement ->
            statement.executeQuery().use { result -> buildSet { while (result.next()) add(result.getInt(1)) } }
        }
    }

    private fun adapter(): PostgresDashboardNotifications = adapter(properties())

    private fun adapter(properties: ControlProductPostgresProperties): PostgresDashboardNotifications =
        PostgresDashboardNotifications(
            properties,
            ControlProductTransactionProperties(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(100)),
            Duration.ofMillis(20),
            publicationDataSource = postgres.dataSource,
        )

    private fun properties(): ControlProductPostgresProperties {
        val dataSource = postgres.newDataSource() as DriverManagerDataSource
        return ControlProductPostgresProperties(
            checkNotNull(dataSource.url), checkNotNull(dataSource.username), checkNotNull(dataSource.password),
            2, Duration.ofSeconds(1), Duration.ofMillis(250),
        )
    }

    companion object {
        @JvmField @RegisterExtension val postgres = EphemeralPostgresAuthoritySupport

        @JvmStatic @BeforeAll fun schemas() {
            postgres.verifyEphemeralIdentity()
            postgres.applyAuthoritySchemasTwice()
        }
    }
}
