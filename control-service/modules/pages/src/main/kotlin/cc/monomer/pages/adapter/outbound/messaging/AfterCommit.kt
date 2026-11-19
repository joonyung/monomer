package cc.monomer.pages.adapter.outbound.messaging

import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Defer [block] until the current transaction commits; run it immediately if there is
 * no active transaction. Ensures bus subscribers never observe events that were later
 * rolled back.
 */
internal inline fun afterCommit(crossinline block: () -> Unit) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = block()
        })
    } else {
        block()
    }
}
