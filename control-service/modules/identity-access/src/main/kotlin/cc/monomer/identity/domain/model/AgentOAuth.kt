package cc.monomer.identity.domain.model

import java.time.Duration
import java.time.Instant

// A grant's last use is recorded at most once per interval so authenticating a request rarely writes.
object AgentOAuthGrantUse {
    val recordingInterval: Duration = Duration.ofHours(1)

    fun recordingDue(lastUsedAt: Instant?, now: Instant): Boolean =
        lastUsedAt == null || !now.isBefore(lastUsedAt.plus(recordingInterval))

    fun recordingThreshold(now: Instant): Instant = now.minus(recordingInterval)
}
