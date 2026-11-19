package cc.monomer.pages.domain.common

// Convention:
//   run {}      → called from UseCase (application layer manages write transactions)
//   readOnly {} → called from QueryPort adapters (adapter composes suspend read operations)
//
// Write-side port methods that participate in a caller-managed transaction
// are declared as plain `fun` (not suspend). The UseCase wraps them in run {}.
// Read-side port methods are declared as `suspend fun` and the adapter
// internally wraps them in readOnly {}.
interface TransactionRunner {
    suspend fun <T> run(block: () -> T): T

    suspend fun <T> readOnly(block: () -> T): T
}
