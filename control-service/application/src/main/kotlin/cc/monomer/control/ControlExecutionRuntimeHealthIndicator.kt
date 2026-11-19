package cc.monomer.control

import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component

@Component
class ControlExecutionRuntimeHealthIndicator(
    private val runtimeProperties: ControlRuntimeProperties,
    private val startupVerifierProvider: ObjectProvider<ControlExecutionRuntimeStartupVerifier>,
    private val recoveryLifecycleProvider: ObjectProvider<ExecutionRunRecoveryLifecycle>,
    private val clockProvider: ObjectProvider<Clock>,
) : HealthIndicator {
    override fun health(): Health {
        val startupVerified = startupVerifierProvider.ifAvailable?.verified == true
        val recovery = recoveryLifecycleProvider.ifAvailable?.snapshot()
        val limits = runtimeProperties.executionRuntime.limits
        val observedAt = clockProvider.ifAvailable?.instant() ?: Instant.now()
        val recoveryHealthy = recovery?.isHealthy(observedAt, limits.recoveryInterval) == true
        val builder = if (startupVerified && recoveryHealthy) Health.up() else Health.down()
        return builder
            .withDetail("serving", true)
            .withDetail("executionTarget", runtimeProperties.executionRuntime.executionTarget.toString())
            .withDetail("startupVerified", startupVerified)
            .withDetail("recoveryRunning", recovery?.running == true)
            .withDetail("recoveryFresh", recovery?.lastCompletedCycleAt.isFresh(observedAt, limits.recoveryInterval.multipliedBy(RECOVERY_STALE_INTERVALS)))
            .withDetail("recoveryCycleFailure", recovery?.cycleFailureType ?: "NONE")
            .withDetail("recoveryFailedCandidates", recovery?.failedCandidates ?: 0)
            .withDetail("recoveryCandidateFailure", recovery?.candidateFailureType ?: "NONE")
            .withDetail("recoveryUnreadableRuns", recovery?.unreadableRuns ?: 0)
            .build()
    }
}

/** A completed cycle older than this many recovery intervals means the loop has stalled. */
internal const val RECOVERY_STALE_INTERVALS: Long = 3

/**
 * Recovery health is the loop's liveness: it is running, its last cycle completed, and that completion
 * is fresh. A failed candidate stays visible in the details and the logs but does not take the
 * replica out of service, because every replica would see the same stuck run.
 */
internal fun ExecutionRunRecoverySnapshot.isHealthy(
    observedAt: Instant,
    recoveryInterval: Duration,
): Boolean =
    running &&
        cycleFailureType == null &&
        lastCompletedCycleAt.isFresh(observedAt, recoveryInterval.multipliedBy(RECOVERY_STALE_INTERVALS))

private fun Instant?.isFresh(
    observedAt: Instant,
    maximumAge: Duration,
): Boolean =
    this != null &&
        !isAfter(observedAt) &&
        isAfter(observedAt.minus(maximumAge))
