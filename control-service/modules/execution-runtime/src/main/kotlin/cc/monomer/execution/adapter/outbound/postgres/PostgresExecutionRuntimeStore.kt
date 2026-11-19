package cc.monomer.execution.adapter.outbound.postgres

import cc.monomer.execution.api.ExecutionOwnerReference
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ExecutionRunSnapshot
import cc.monomer.execution.application.AdmittedExecutionRun
import cc.monomer.execution.application.ExecutionRunRepository
import cc.monomer.execution.application.RunInsertOutcome
import cc.monomer.execution.application.StoredExecutionRunRead
import cc.monomer.identity.api.WorkspaceId
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import javax.sql.DataSource
import kotlin.uuid.toJavaUuid
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionSynchronizationManager

class PostgresExecutionRuntimeStore(
    private val dataSource: DataSource,
) : ExecutionRunRepository {
    override fun requireAmbientTransaction() {
        currentConnection()
    }

    override fun find(runId: ExecutionRunId): AdmittedExecutionRun? =
        queryOne(
            """
            SELECT *
            FROM execution_run.runs
            WHERE run_id = ?
            """.trimIndent(),
            { statement -> statement.setObject(1, runId.value.toJavaUuid()) },
            ResultSet::toAdmittedRun,
        )

    override fun findAll(runIds: Set<ExecutionRunId>): List<StoredExecutionRunRead> {
        if (runIds.isEmpty()) return emptyList()
        return queryMany(
            """
            SELECT *
            FROM execution_run.runs
            WHERE run_id = ANY(?)
            """.trimIndent(),
            { statement ->
                statement.setArray(1, statement.connection.createArrayOf("uuid", runIds.map { it.value.toJavaUuid() }.toTypedArray()))
            },
            ResultSet::toStoredRunRead,
        )
    }

    override fun findForUpdate(runId: ExecutionRunId): AdmittedExecutionRun? {
        check(currentConnection().transactionIsolation == Connection.TRANSACTION_READ_COMMITTED) {
            "run mutation requires READ COMMITTED for current row locking"
        }
        return queryOne(
            """
            SELECT * FROM execution_run.runs WHERE run_id = ? FOR UPDATE
            """.trimIndent(),
            { statement -> statement.setObject(1, runId.value.toJavaUuid()) },
            ResultSet::toAdmittedRun,
        )
    }

    override fun findByIdempotencyKey(
        owner: ExecutionOwnerReference,
        idempotencyKey: String,
    ): AdmittedExecutionRun? =
        queryOne(
            """
            SELECT run_id
            FROM execution_run.runs
            WHERE owner_type = ? AND owner_id = ? AND idempotency_key = ?
            """.trimIndent(),
            { statement ->
                statement.setString(1, owner.type.name)
                statement.setObject(2, owner.id.value.toJavaUuid())
                statement.setString(3, idempotencyKey)
            },
            { resultSet -> ExecutionRunId(resultSet.getUuid("run_id")) },
        )?.let(::find)

    override fun lockWorkspaceRunAdmission(workspaceId: WorkspaceId) {
        prepare(
            """
            SELECT pg_catalog.pg_advisory_xact_lock(1836018546, pg_catalog.hashtext(?))
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, workspaceId.toString())
            statement.executeQuery().use { resultSet -> check(resultSet.next()) }
        }
    }

    override fun countExecutionOccupancy(workspaceId: WorkspaceId): Int = queryOne(
        """SELECT COUNT(*) AS run_count FROM execution_run.runs
           WHERE workspace_id = ? AND execution_occupancy_acquired_at IS NOT NULL
             AND execution_occupancy_released_at IS NULL""",
        { it.setObject(1, workspaceId.value.toJavaUuid()) },
        { it.getInt("run_count") },
    ) ?: 0

    override fun findOccupancyRecoveryCandidates(observedAt: Instant, staleAfter: Instant, maximumRuns: Int): List<StoredExecutionRunRead> =
        findCandidates(
            """WHERE execution_occupancy_acquired_at IS NOT NULL AND execution_occupancy_released_at IS NULL
                 AND (deadline <= ? OR COALESCE(last_heartbeat_at, dispatch_claimed_at) <= ?)
               ORDER BY COALESCE(last_heartbeat_at, dispatch_claimed_at), run_id""",
            { statement ->
                statement.setInstant(1, observedAt)
                statement.setInstant(2, staleAfter)
                statement.setInt(3, maximumRuns)
            },
        )

    override fun countNonTerminalRuns(workspaceId: WorkspaceId): Int =
        queryOne(
            """
            SELECT COUNT(*) AS run_count
            FROM execution_run.runs
            WHERE workspace_id = ?
              AND state NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
            """.trimIndent(),
            { statement -> statement.setObject(1, workspaceId.value.toJavaUuid()) },
            { resultSet -> resultSet.getInt("run_count") },
        ) ?: 0

    override fun insert(run: AdmittedExecutionRun): RunInsertOutcome =
        withUniqueConflictRecovery(
            insert = {
                prepare(runInsertSql).use { statement ->
                    statement.bindRunInsert(run)
                    statement.executeUpdate()
                }
                RunInsertOutcome.INSERTED
            },
            onUniqueConflict = {
                val existing = findByIdempotencyKey(run.snapshot.owner, run.idempotencyKey)
                if (existing == run) RunInsertOutcome.EXACT_DUPLICATE else RunInsertOutcome.CONFLICT
            },
        )

    override fun compareAndSet(
        expectedVersion: Long,
        snapshot: ExecutionRunSnapshot,
    ): Boolean =
        prepare(runUpdateSql).use { statement ->
            val cancellationPersisted = snapshot.cancelIntent != null && find(snapshot.id)?.snapshot?.cancelIntent == null
            val nextIndex = statement.bindRunMutable(snapshot, includeCreatedAt = false)
            statement.setObject(nextIndex, snapshot.id.value.toJavaUuid())
            statement.setLong(nextIndex + 1, expectedVersion)
            val updated = statement.executeUpdate() == 1
            if (updated && cancellationPersisted) {
                prepare("SELECT pg_catalog.pg_notify('monomer_execution_cancel', ?)").use { notification ->
                    notification.setString(1, snapshot.id.toString())
                    notification.executeQuery().use { check(it.next()) }
                }
            }
            updated
        }

    override fun findOrphanCandidates(
        observedAt: Instant,
        staleAfter: Instant,
        maximumRuns: Int,
    ): List<StoredExecutionRunRead> =
        findCandidates(
            """
            WHERE state = 'RUNNING'
              AND dispatch_claimed_at IS NOT NULL
              AND COALESCE(last_heartbeat_at, dispatch_claimed_at) <= ?
              AND deadline > ?
            ORDER BY COALESCE(last_heartbeat_at, dispatch_claimed_at), run_id
            """.trimIndent(),
            bind = { statement ->
                statement.setInstant(1, staleAfter)
                statement.setInstant(2, observedAt)
                statement.setInt(3, maximumRuns)
            },
        )

    override fun findDispatchDeadlineCandidates(
        observedAt: Instant,
        maximumRuns: Int,
    ): List<StoredExecutionRunRead> =
        findCandidates(
            """
            WHERE state = 'RUNNING'
              AND execution_occupancy_acquired_at IS NULL
              AND dispatch_deadline_at <= ?
            ORDER BY dispatch_deadline_at, run_id
            """.trimIndent(),
            bind = { statement ->
                statement.setInstant(1, observedAt)
                statement.setInt(2, maximumRuns)
            },
        )

    override fun findExecutionDeadlineCandidates(
        observedAt: Instant,
        maximumRuns: Int,
    ): List<StoredExecutionRunRead> =
        findCandidates(
            """
            WHERE state = 'RUNNING'
              AND dispatch_claimed_at IS NOT NULL
              AND deadline <= ?
            ORDER BY deadline, run_id
            """.trimIndent(),
            bind = { statement ->
                statement.setInstant(1, observedAt)
                statement.setInt(2, maximumRuns)
            },
        )

    override fun findArtifactCleanupCandidates(
        observedAt: Instant,
        maximumRuns: Int,
    ): List<StoredExecutionRunRead> = findCandidates(
        """
        WHERE (state IN ('FAILED', 'CANCELLED') OR (state = 'SUCCEEDED' AND owner_discarded_at IS NOT NULL))
          AND result_sink_kind = 'ARTIFACT_UPLOAD'
          AND artifact_cleanup_completed_at IS NULL
          AND (dispatch_claimed_at IS NULL OR deadline <= ?)
        ORDER BY artifact_cleanup_last_attempted_at NULLS FIRST, run_id
        """.trimIndent(),
        bind = { statement ->
            statement.setInstant(1, observedAt)
            statement.setInt(2, maximumRuns)
        },
    )

    override fun beginArtifactCleanup(
        runId: ExecutionRunId,
        expectedVersion: Long,
        attemptedAt: Instant,
    ): Boolean = prepare(
        """
        UPDATE execution_run.runs
        SET artifact_cleanup_last_attempted_at = ?, version = version + 1
        WHERE run_id = ? AND version = ?
          AND (state IN ('FAILED', 'CANCELLED') OR (state = 'SUCCEEDED' AND owner_discarded_at IS NOT NULL))
          AND result_sink_kind = 'ARTIFACT_UPLOAD'
          AND artifact_cleanup_completed_at IS NULL
          AND (dispatch_claimed_at IS NULL OR deadline <= ?)
        """.trimIndent(),
    ).use { statement ->
        statement.setInstant(1, attemptedAt)
        statement.setObject(2, runId.value.toJavaUuid())
        statement.setLong(3, expectedVersion)
        statement.setInstant(4, attemptedAt)
        statement.executeUpdate() == 1
    }

    override fun completeArtifactCleanup(
        runId: ExecutionRunId,
        expectedVersion: Long,
        completedAt: Instant,
    ): Boolean = prepare(
        """
        UPDATE execution_run.runs
        SET artifact_cleanup_completed_at = ?, version = version + 1
        WHERE run_id = ? AND version = ?
          AND (state IN ('FAILED', 'CANCELLED') OR (state = 'SUCCEEDED' AND owner_discarded_at IS NOT NULL))
          AND result_sink_kind = 'ARTIFACT_UPLOAD'
          AND artifact_cleanup_completed_at IS NULL
          AND artifact_cleanup_last_attempted_at IS NOT NULL
          AND (dispatch_claimed_at IS NULL OR deadline <= ?)
        """.trimIndent(),
    ).use { statement ->
        statement.setInstant(1, completedAt)
        statement.setObject(2, runId.value.toJavaUuid())
        statement.setLong(3, expectedVersion)
        statement.setInstant(4, completedAt)
        statement.executeUpdate() == 1
    }

    override fun invalidateArtifactCleanup(runId: ExecutionRunId, expectedVersion: Long): Boolean = prepare(
        """
        UPDATE execution_run.runs
        SET artifact_cleanup_completed_at = NULL, version = version + 1
        WHERE run_id = ? AND version = ?
          AND (state IN ('FAILED', 'CANCELLED') OR (state = 'SUCCEEDED' AND owner_discarded_at IS NOT NULL))
          AND result_sink_kind = 'ARTIFACT_UPLOAD'
        """.trimIndent(),
    ).use { statement ->
        statement.setObject(1, runId.value.toJavaUuid())
        statement.setLong(2, expectedVersion)
        statement.executeUpdate() == 1
    }

    override fun findOwnerSettlementCandidates(
        ownerTypes: Set<ExecutionOwnerType>,
        maximumRuns: Int,
    ): List<StoredExecutionRunRead> {
        require(ownerTypes.isNotEmpty())
        val sortedTypes = ownerTypes.sortedBy(ExecutionOwnerType::name)
        val placeholders = sortedTypes.joinToString(", ") { "?" }
        return findCandidates(
            """
            WHERE physical_outcome_kind IS NOT NULL
              AND owner_settled_at IS NULL
              AND owner_discarded_at IS NULL
              AND owner_type IN ($placeholders)
            ORDER BY updated_at, run_id
            """.trimIndent(),
            bind = { statement ->
                sortedTypes.forEachIndexed { index, type -> statement.setString(index + 1, type.name) }
                statement.setInt(sortedTypes.size + 1, maximumRuns)
            },
        )
    }

    private fun findCandidates(
        predicate: String,
        bind: (PreparedStatement) -> Unit,
    ): List<StoredExecutionRunRead> =
        queryMany(
            """
            SELECT *
            FROM execution_run.runs
            $predicate
            LIMIT ?
            """.trimIndent(),
            bind,
            ResultSet::toStoredRunRead,
        )

    private fun <T> withUniqueConflictRecovery(
        insert: () -> T,
        onUniqueConflict: () -> T,
    ): T {
        val connection = currentConnection()
        val savepoint = connection.setSavepoint()
        return try {
            insert().also { connection.releaseSavepoint(savepoint) }
        } catch (failure: SQLException) {
            if (failure.sqlState != UNIQUE_VIOLATION_SQL_STATE) throw failure
            connection.rollback(savepoint)
            connection.releaseSavepoint(savepoint)
            onUniqueConflict()
        }
    }

    private fun prepare(sql: String): PreparedStatement = currentConnection().prepareStatement(sql)

    private fun <T> queryOne(
        sql: String,
        bind: (PreparedStatement) -> Unit,
        map: (ResultSet) -> T,
    ): T? =
        prepare(sql).use { statement ->
            bind(statement)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) map(resultSet) else null
            }
        }

    private fun <T> queryMany(
        sql: String,
        bind: (PreparedStatement) -> Unit,
        map: (ResultSet) -> T,
    ): List<T> =
        prepare(sql).use { statement ->
            bind(statement)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) add(map(resultSet))
                }
            }
        }

    private fun currentConnection(): Connection {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "execution-runtime repository call requires an active Control transaction"
        }
        check(TransactionSynchronizationManager.hasResource(dataSource)) {
            "execution-runtime repository call requires the Control DataSource transaction"
        }
        return DataSourceUtils.getConnection(dataSource)
    }

    private companion object {
        const val UNIQUE_VIOLATION_SQL_STATE = "23505"
    }
}
