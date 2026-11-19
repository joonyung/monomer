package cc.monomer.pages.adapter.outbound.messaging.pages

import cc.monomer.pages.application.PagesDirectoryWakeup
import cc.monomer.pages.adapter.outbound.messaging.InstanceIdHolder
import cc.monomer.pages.adapter.outbound.messaging.PostgresNotificationListener
import io.mockk.every
import io.mockk.mockk
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class PostgresPagesDirectoryEventBusTest {
    private val workspaceId = Uuid.parse("019e501f-0000-7000-8000-000000000011")
    private val otherWorkspaceId = Uuid.parse("019e501f-0000-7000-8000-000000000012")
    private val localInstanceId = "pages-local"

    private class CapturingListener {
        val listener = mockk<PostgresNotificationListener>()
        var channel: String? = null
        var handler: ((String) -> Unit)? = null

        init {
            every { listener.registerHandler(any(), any()) } answers {
                channel = firstArg()
                handler = secondArg()
            }
        }
    }

    @Test
    fun `register listens on the Pages directory channel`() {
        val listener = CapturingListener()

        newBus(listener)

        assertThat(listener.channel).isEqualTo(PostgresPagesDirectoryEventBus.CHANNEL)
        assertThat(listener.handler).isNotNull()
    }

    @Test
    fun `publish delivers a local wakeup after the transaction boundary`() = runTest {
        val bus = newBus(CapturingListener())
        val wakeup = PagesDirectoryWakeup(workspaceId)
        val received = async(start = CoroutineStart.UNDISPATCHED) { bus.subscribe(workspaceId).first() }

        bus.publish(wakeup)

        assertThat(received.await()).isEqualTo(wakeup)
    }

    @Test
    fun `remote notifications are decoded and filtered by workspace`() = runTest {
        val listener = CapturingListener()
        val bus = newBus(listener)
        val received = async(start = CoroutineStart.UNDISPATCHED) { bus.subscribe(workspaceId).first() }

        listener.handler!!(remoteEnvelope("pages-remote", otherWorkspaceId))
        listener.handler!!(remoteEnvelope("pages-remote", workspaceId))

        assertThat(received.await()).isEqualTo(
            PagesDirectoryWakeup(workspaceId),
        )
    }

    @Test
    fun `self-origin and malformed notifications are ignored without closing the stream`() = runTest {
        val listener = CapturingListener()
        val bus = newBus(listener)
        val received = async(start = CoroutineStart.UNDISPATCHED) { bus.subscribe(workspaceId).first() }

        listener.handler!!("{not-json}")
        listener.handler!!(remoteEnvelope(localInstanceId, workspaceId))
        listener.handler!!(remoteEnvelope("pages-remote", workspaceId))

        assertThat(received.await()).isEqualTo(PagesDirectoryWakeup(workspaceId))
    }

    private fun newBus(listener: CapturingListener): PostgresPagesDirectoryEventBus {
        val jdbc = mockk<NamedParameterJdbcTemplate>()
        val plainJdbc = mockk<JdbcTemplate>(relaxed = true)
        every { jdbc.jdbcTemplate } returns plainJdbc
        return PostgresPagesDirectoryEventBus(jdbc, listener.listener, InstanceIdHolder(localInstanceId)).also { it.register() }
    }

    private fun remoteEnvelope(instance: String, workspaceId: Uuid): String =
        """{"instance":"$instance","workspaceId":"$workspaceId"}"""
}
