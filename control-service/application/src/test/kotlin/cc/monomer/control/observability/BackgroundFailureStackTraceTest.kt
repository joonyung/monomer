package cc.monomer.control.observability

import cc.monomer.control.dashboard.DashboardScheduleLifecycle
import cc.monomer.dashboard.api.DashboardScheduleOwnerPort
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.mockk.coEvery
import io.mockk.mockk
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class BackgroundFailureStackTraceTest {
    @Test
    fun `schedule tick failure retains frames and causes without messages`() {
        val cause = NullPointerException("SELECT schedule-cause-secret").apply {
            stackTrace = arrayOf(StackTraceElement("cc.monomer.Schedule", "read", "Schedule.kt", 17))
        }
        val failure = IllegalStateException("password=schedule-failure-secret user@example.test", cause).apply {
            stackTrace = arrayOf(StackTraceElement("cc.monomer.Schedule", "tick", "Schedule.kt", 42))
            addSuppressed(IllegalArgumentException("suppressed-schedule-secret"))
        }
        val owner = mockk<DashboardScheduleOwnerPort>()
        coEvery { owner.tick() } throws failure
        val lifecycle = DashboardScheduleLifecycle(owner)
        val logger = LoggerFactory.getLogger(DashboardScheduleLifecycle::class.java) as Logger
        val events = LinkedBlockingQueue<ILoggingEvent>()
        val logs = object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                event.prepareForDeferredProcessing()
                events.add(event)
            }
        }.apply { start() }
        logger.addAppender(logs)
        try {
            lifecycle.start()
            val event = assertNotNull(events.poll(5, TimeUnit.SECONDS))
            val encoded = RequestLogTestSupport.encoded(event)
            assertTrue(encoded.contains("dashboard_schedule_tick_failed"))
            assertTrue(encoded.contains("stack_trace"))
            assertTrue(encoded.contains("cc.monomer.Schedule.tick(Schedule.kt:42)"))
            assertTrue(encoded.contains("Caused by: java.lang.NullPointerException"))
            assertTrue(encoded.contains("cc.monomer.Schedule.read(Schedule.kt:17)"))
            assertTrue(encoded.contains("Suppressed: java.lang.IllegalArgumentException"))
            assertFalse(encoded.contains("schedule-failure-secret"))
            assertFalse(encoded.contains("schedule-cause-secret"))
            assertFalse(encoded.contains("suppressed-schedule-secret"))
            assertFalse(encoded.contains("user@example.test"))
            assertFalse(encoded.contains("SELECT"))
        } finally {
            lifecycle.stop()
            logger.detachAppender(logs)
            logs.stop()
        }
    }
}
