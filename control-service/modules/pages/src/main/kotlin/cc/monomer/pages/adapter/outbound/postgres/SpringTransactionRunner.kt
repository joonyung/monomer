package cc.monomer.pages.adapter.outbound.postgres
import cc.monomer.pages.domain.common.TransactionRunner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.transaction.support.TransactionTemplate

class SpringTransactionRunner(
    @param:Qualifier("pagesTransactionTemplate")
    private val transactionTemplate: TransactionTemplate,
    @param:Qualifier("pagesReadOnlyTransactionTemplate")
    private val readOnlyTransactionTemplate: TransactionTemplate,
    private val blockingDispatcher: CoroutineDispatcher,
) : TransactionRunner {
    override suspend fun <T> run(block: () -> T): T {
        return withContext(blockingDispatcher) {
            transactionTemplate.execute { block() }
        }
    }
    override suspend fun <T> readOnly(block: () -> T): T {
        return withContext(blockingDispatcher) {
            readOnlyTransactionTemplate.execute { block() }
        }
    }
}
