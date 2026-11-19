package cc.monomer.execution.adapter.outbound.postgres

import cc.monomer.execution.application.ExecutionRuntimeTransactionRunner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.springframework.transaction.support.TransactionOperations

class SpringExecutionRuntimeTransactionRunner(
    private val transactionOperations: TransactionOperations,
    private val blockingDispatcher: CoroutineDispatcher,
) : ExecutionRuntimeTransactionRunner {
    override suspend fun <T> run(block: () -> T): T =
        withContext(blockingDispatcher) {
            requireNotNull(
                transactionOperations.execute {
                    TransactionValue(block())
                },
            ).value
        }
}

private data class TransactionValue<T>(
    val value: T,
)
