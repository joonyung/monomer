package cc.monomer.identity.domain.port

/**
 * Coroutine-aware transactional boundary. Write transactions use [run]; read-only paths
 * (QueryPort adapters) use [readOnly]. The implementation dispatches the action onto a
 * virtual-thread dispatcher so blocking JDBC inside is safe.
 */
interface TransactionRunner {
    suspend fun <T> run(action: () -> T): T

    suspend fun <T> readOnly(action: () -> T): T
}

interface ConsistentSnapshotTransactionRunner {
    suspend fun <T> run(action: suspend () -> T): T
}

class ConsistentSnapshotRetryException(
    message: String,
) : RuntimeException(message)
