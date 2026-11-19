package cc.monomer.control

import java.time.Duration
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ControlRuntimeLimitContractTest {
    @Test
    fun `execution limits require a positive run capacity`() {
        validExecutionLimits()

        assertThatThrownBy {
            validExecutionLimits().copy(
                maximumNonTerminalRunsPerWorkspace = 0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            validExecutionLimits().copy(
                maximumRunFrameBytes = 0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `owner read and settlement retry lifetimes stay explicitly bounded`() {
        validExecutionLimits()

        assertThatThrownBy {
            validExecutionLimits().copy(exploreResultRetention = Duration.ZERO)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            validExecutionLimits().copy(maximumExploreResultReadTransactionAttempts = 0)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            validExecutionLimits().copy(maximumExploreResultReadTransactionAttempts = 11)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `waiting and execution budgets accept one hour and preserve the stored deadline bound`() {
        validExecutionLimits().copy(
            dispatchReservationDuration = Duration.ofHours(1),
            executionDeadlineDuration = Duration.ofHours(1),
        )
        assertThatThrownBy {
            validExecutionLimits().copy(dispatchReservationDuration = Duration.ofHours(1))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            validExecutionLimits().copy(
                dispatchReservationDuration = Duration.ofHours(1).plusSeconds(1),
                executionDeadlineDuration = Duration.ofHours(1),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun validExecutionLimits(): ControlExecutionLimitsProperties =
        testExecutionLimits()
}
