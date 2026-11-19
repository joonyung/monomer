package cc.monomer.control

import cc.monomer.agent.api.AgentRequestId
import cc.monomer.control.agent.logAgentOwnerFailure
import cc.monomer.control.publicapi.logAgentTransportFailure
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class AgentFailureLoggingTest {
    @Test
    fun `owner and transport failures log identifiers and safe types without exception content`() {
        val failure = IllegalStateException("user@example.test raw-secret-token", SQLException("private SQL statement", "40001"))
        val ownerLogger = LoggerFactory.getLogger("cc.monomer.control.agent.AgentOwner") as Logger
        val transportLogger = LoggerFactory.getLogger("cc.monomer.control.publicapi.AgentTransport") as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        ownerLogger.addAppender(logs)
        transportLogger.addAppender(logs)
        try {
            logAgentOwnerFailure("AgentExploreOwnerAdapter", failure)
            logAgentTransportFailure("REST", AgentRequestId("srv_agent-failure"), failure)
            assertEquals(listOf("agent_owner_failed", "agent_transport_failed"), logs.list.map { it.formattedMessage })
            assertEquals("AgentExploreOwnerAdapter", logs.list[0].keyValuePairs.associate { it.key to it.value }["owner_adapter"])
            val transportFields = logs.list[1].keyValuePairs.associate { it.key to it.value }
            assertEquals("REST", transportFields["transport"])
            assertEquals("srv_agent-failure", transportFields["request_id"])
            logs.list.forEach { event ->
                val fields = event.keyValuePairs.associate { it.key to it.value }
                val encoded = cc.monomer.control.observability.RequestLogTestSupport.encoded(event)
                assertTrue(encoded.contains("stack_trace"))
                assertTrue(encoded.contains("AgentFailureLoggingTest"))
                assertEquals("java.lang.IllegalStateException -> java.sql.SQLException", fields["failure_types"])
                assertEquals("40001", fields["sql_states"])
                assertFalse(encoded.contains("user@example.test"))
                assertFalse(encoded.contains("raw-secret-token"))
                assertFalse(encoded.contains("private SQL statement"))
            }
        } finally {
            ownerLogger.detachAppender(logs)
            transportLogger.detachAppender(logs)
        }
    }
}
