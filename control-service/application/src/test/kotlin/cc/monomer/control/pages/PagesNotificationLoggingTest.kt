@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.pages

import cc.monomer.pages.adapter.outbound.messaging.InstanceIdHolder
import cc.monomer.pages.adapter.outbound.messaging.PostgresNotificationListener
import cc.monomer.pages.adapter.outbound.messaging.pages.PostgresPagesDirectoryEventBus
import cc.monomer.pages.application.PagesDirectoryWakeup
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class PagesNotificationLoggingTest {
    @Test
    fun `malformed directory notification logs its type without exposing payload and keeps the stream open`() = runTest {
        val listener = mockk<PostgresNotificationListener>()
        val handler = slot<(String) -> Unit>()
        every { listener.registerHandler(PostgresPagesDirectoryEventBus.CHANNEL, capture(handler)) } just Runs
        val bus = PostgresPagesDirectoryEventBus(mockk<NamedParameterJdbcTemplate>(), listener, InstanceIdHolder("pages-local"))
        val workspaceId = Uuid.parse("019e501f-0000-7000-8000-000000000011")
        bus.register()
        val logger = LoggerFactory.getLogger(PostgresPagesDirectoryEventBus::class.java) as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        try {
            val received = async(start = CoroutineStart.UNDISPATCHED) { bus.subscribe(workspaceId).first() }
            handler.captured("""{"instance":"pages-remote","workspaceId":"$workspaceId","private":"user@example.test raw-secret-token SELECT private_data"}""")
            handler.captured("""{"instance":"pages-remote","workspaceId":"$workspaceId"}""")
            assertEquals(PagesDirectoryWakeup(workspaceId), received.await())
            val event = logs.list.single()
            val fields = cc.monomer.control.observability.RequestLogTestSupport.fields(event)
            assertEquals("pages_directory_notification_malformed", event.formattedMessage)
            assertEquals(setOf("event", "error.type", "error.stack_trace"), fields.keys)
            assertTrue((fields["error.type"] as String).startsWith("kotlinx.serialization."))
            assertTrue((fields["error.stack_trace"] as String).contains("PostgresPagesDirectoryEventBus"))
            assertTrue(event.argumentArray.isNullOrEmpty())
            assertFalse(fields.values.toString().contains("user@example.test"))
            assertFalse(fields.values.toString().contains("raw-secret-token"))
            assertFalse(fields.values.toString().contains("SELECT private_data"))
            assertFalse(fields.values.toString().contains(workspaceId.toString()))
        } finally {
            logger.detachAppender(logs)
        }
    }
}
