package cc.monomer.explore.application

import cc.monomer.explore.api.ExploreInitialAdmissionTransaction
import cc.monomer.explore.api.ExploreResultReadTransaction
import cc.monomer.explore.api.ExploreResultView
import cc.monomer.explore.api.ExploreRunId
import cc.monomer.explore.api.ExploreRunPageBoundary
import cc.monomer.explore.domain.ExploreRun
import cc.monomer.explore.domain.ExploreTerminalReceipt
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.api.PrincipalId

interface ExploreTransactionPort {
    suspend fun <T> inTransaction(block: () -> T): T
}

class ExploreTransactionConcurrentConflictException(
    cause: Throwable,
) : RuntimeException(cause)

interface ActiveExploreInitialAdmissionTransactionPort {
    fun <T> inActiveInitialAdmissionTransaction(
        block: (ExploreInitialAdmissionTransaction) -> T,
    ): T
}

interface ExploreInitialAdmissionTransactionPort {
    suspend fun <T> inInitialAdmissionTransaction(
        block: (ExploreInitialAdmissionTransaction) -> T,
    ): T
}

interface ExploreResultReadTransactionPort {
    suspend fun <T> inResultReadTransaction(
        block: (ExploreResultReadTransaction) -> T,
    ): T
}

class ExploreResultReadTransactionConcurrentConflictException(
    cause: Throwable,
) : RuntimeException(cause)

interface ActiveExploreOwnerSettlementTransactionPort {
    fun <T> inActiveOwnerSettlementTransaction(block: () -> T): T
}

interface ExploreRunRepository {
    fun lockAdmissionKey(workspaceId: WorkspaceId, idempotencyKey: String)

    fun findByAdmissionKey(workspaceId: WorkspaceId, idempotencyKey: String): ExploreRun?

    fun findRun(runId: ExploreRunId): ExploreRun?

    fun findRuns(
        workspaceId: WorkspaceId,
        ownerPrincipalId: PrincipalId,
        after: ExploreRunPageBoundary?,
        limit: Int,
    ): List<ExploreRun>

    fun insertAdmittedRun(run: ExploreRun): Boolean

    fun lockRunForOwnerMutation(runId: ExploreRunId): ExploreRun?

    fun lockRunForResultRead(runId: ExploreRunId): ExploreRun?

    fun findTerminalReceipt(
        workspaceId: WorkspaceId,
        settlementIdempotencyKey: String,
    ): ExploreTerminalReceipt?

    fun commitTerminalReceipt(
        run: ExploreRun,
        receipt: ExploreTerminalReceipt,
        expectedRevision: Long,
    ): Boolean

    fun lockVisibleResultForRead(runId: ExploreRunId): ExploreResultView?
}
