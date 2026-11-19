@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.explore.adapter.outbound.postgres

import cc.monomer.execution.api.OwnerExecutionRunCancellationPort
import cc.monomer.explore.application.ExploreCancellationHandler
import cc.monomer.explore.application.ExploreRunRepository
import cc.monomer.explore.api.CancelExploreRunCommand
import cc.monomer.explore.api.CancelExploreRunOutcome
import cc.monomer.explore.api.ExploreRunId
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.sql.SQLException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate

class ExploreCancellationTransactionConflictTest {
    @Test
    fun `serialization and deadlock failures roll back cancellation and return typed conflict`() = runBlocking {
        listOf("40001", "40P01").forEach { sqlState ->
            val transactionManager = mockk<PlatformTransactionManager>(relaxed = true)
            val status = SimpleTransactionStatus()
            every { transactionManager.getTransaction(any()) } returns status
            val repository = mockk<ExploreRunRepository>()
            val cancellation = mockk<OwnerExecutionRunCancellationPort>()
            val request = command()
            every { repository.lockRunForOwnerMutation(request.runId) } throws SQLException("concurrent update", sqlState)
            val handler = ExploreCancellationHandler(
                repository,
                SpringExploreTransactionPort(TransactionTemplate(transactionManager), Dispatchers.Unconfined),
                cancellation,
            )

            assertEquals(CancelExploreRunOutcome.ConcurrentConflict, handler.cancel(request))
            verify(exactly = 1) { transactionManager.rollback(status) }
            verify(exactly = 0) { transactionManager.commit(any()) }
            verify(exactly = 0) { cancellation.requestCancellationInOwnerTransaction(any()) }
        }
    }

    @Test
    fun `unrelated database failures are not reported as caller conflicts`() = runBlocking {
        val transactionManager = mockk<PlatformTransactionManager>(relaxed = true)
        every { transactionManager.getTransaction(any()) } returns SimpleTransactionStatus()
        val port = SpringExploreTransactionPort(TransactionTemplate(transactionManager), Dispatchers.Unconfined)
        val failure = assertFailsWith<java.lang.reflect.UndeclaredThrowableException> {
            port.inTransaction<Unit> { throw SQLException("connection unavailable", "08006") }
        }
        assertEquals("08006", (failure.cause as SQLException).sqlState)
        verify(exactly = 1) { transactionManager.rollback(any()) }
    }

    private fun command() = CancelExploreRunCommand(
        workspaceId = WorkspaceId(Uuid.generateV7()),
        runId = ExploreRunId.generate(),
        ownerPrincipalId = PrincipalId(Uuid.generateV7()),
        reasonCode = "USER_REQUESTED",
        requestedAt = Instant.parse("2026-09-08T00:00:00Z"),
    )
}
