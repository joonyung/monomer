package cc.monomer.identity.domain.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentOAuthGrantUseTest {
    private val now = Instant.parse("2026-10-02T12:00:00Z")

    @Test
    fun `first use and uses an hour or more apart are recorded`() {
        assertTrue(AgentOAuthGrantUse.recordingDue(null, now))
        assertTrue(AgentOAuthGrantUse.recordingDue(now.minusSeconds(3600), now))
        assertTrue(AgentOAuthGrantUse.recordingDue(now.minusSeconds(86400), now))
    }

    @Test
    fun `uses within the hour are not recorded again`() {
        assertFalse(AgentOAuthGrantUse.recordingDue(now, now))
        assertFalse(AgentOAuthGrantUse.recordingDue(now.minusSeconds(3599), now))
        assertEquals(now.minusSeconds(3600), AgentOAuthGrantUse.recordingThreshold(now))
    }
}
