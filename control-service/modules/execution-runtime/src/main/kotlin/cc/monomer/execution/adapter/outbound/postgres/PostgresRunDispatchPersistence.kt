package cc.monomer.execution.adapter.outbound.postgres

import cc.monomer.execution.api.BinaryValue
import cc.monomer.execution.api.RunDispatchIntent
import cc.monomer.execution.api.RunDispatchIntentPersistenceOutcome
import cc.monomer.execution.api.RunDispatchIntentPort
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.Sha256Digest
import cc.monomer.execution.api.StoredDispatchIntentRead
import java.sql.Connection
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Durable dispatch intent for one ExecutionRun. Claiming the run itself is a
 * separate CAS; this adapter preserves the immutable assignment template that
 * the dispatcher materializes after the owner transaction commits.
 */
class PostgresRunDispatchPersistence(
    private val dataSource: DataSource,
    private val transactionOperations: TransactionOperations,
) : RunDispatchIntentPort {
    override fun persist(
        intent: RunDispatchIntent,
    ): RunDispatchIntentPersistenceOutcome =
        inTransaction run@{
            require(Sha256Digest.of(intent.assignmentTemplate.copyBytes()) == intent.templateDigest)
            val connection = transactionalConnection()
            val deadline =
                connection.prepareStatement(
                    """
                    SELECT deadline
                    FROM execution_run.runs
                    WHERE run_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, intent.runId.value.toJavaUuid())
                    statement.executeQuery().use { resultSet ->
                        if (!resultSet.next()) {
                            return@run RunDispatchIntentPersistenceOutcome.RUN_NOT_FOUND
                        }
                        resultSet.getObject("deadline", OffsetDateTime::class.java).toInstant()
                    }
                }
            if (intent.expiresAt.isAfter(deadline)) {
                return@run RunDispatchIntentPersistenceOutcome.BINDING_MISMATCH
            }
            val existing = findIntent(connection, intent.runId)
            if (existing != null) {
                return@run if (existing == intent) {
                    RunDispatchIntentPersistenceOutcome.EXACT_DUPLICATE
                } else {
                    RunDispatchIntentPersistenceOutcome.CONFLICT
                }
            }
            connection.prepareStatement(
                    """
                    INSERT INTO execution_run.dispatch_intents (
                        run_id, template_sha256, assignment_template,
                        expires_at, persisted_at
                    ) VALUES (?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, intent.runId.value.toJavaUuid())
                    statement.setString(2, intent.templateDigest.value)
                    statement.setBytes(3, intent.assignmentTemplate.copyBytes())
                    statement.setObject(4, intent.expiresAt.atOffset(ZoneOffset.UTC))
                    statement.setObject(5, intent.persistedAt.atOffset(ZoneOffset.UTC))
                    check(statement.executeUpdate() == 1)
                }
            RunDispatchIntentPersistenceOutcome.PERSISTED
        }

    override fun find(runId: ExecutionRunId): RunDispatchIntent? =
        inTransaction { findIntent(transactionalConnection(), runId) }

    override fun findAll(runIds: Set<ExecutionRunId>): List<StoredDispatchIntentRead> =
        if (runIds.isEmpty()) {
            emptyList()
        } else {
            inTransaction {
                val connection = transactionalConnection()
                connection.prepareStatement(
                    """
                    SELECT run_id, template_sha256, assignment_template, expires_at, persisted_at
                    FROM execution_run.dispatch_intents
                    WHERE run_id = ANY(?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setArray(1, connection.createArrayOf("uuid", runIds.map { it.value.toJavaUuid() }.toTypedArray()))
                    statement.executeQuery().use { resultSet ->
                        buildList {
                            while (resultSet.next()) {
                                val runId = ExecutionRunId(resultSet.getObject("run_id", java.util.UUID::class.java).toKotlinUuid())
                                add(
                                    try {
                                        StoredDispatchIntentRead.Readable(
                                            RunDispatchIntent(
                                                runId = runId,
                                                templateDigest = Sha256Digest(resultSet.getString("template_sha256").trim()),
                                                assignmentTemplate = BinaryValue.copyOf(resultSet.getBytes("assignment_template")),
                                                expiresAt = resultSet.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
                                                persistedAt = resultSet.getObject("persisted_at", OffsetDateTime::class.java).toInstant(),
                                            ),
                                        )
                                    } catch (_: IllegalArgumentException) {
                                        StoredDispatchIntentRead.Unreadable(runId)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

    private fun findIntent(
        connection: Connection,
        runId: ExecutionRunId,
    ): RunDispatchIntent? =
        connection.prepareStatement(
            """
            SELECT template_sha256, assignment_template, expires_at, persisted_at
            FROM execution_run.dispatch_intents
            WHERE run_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, runId.value.toJavaUuid())
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    null
                } else {
                    RunDispatchIntent(
                        runId = runId,
                        templateDigest = Sha256Digest(resultSet.getString("template_sha256").trim()),
                        assignmentTemplate = BinaryValue.copyOf(resultSet.getBytes("assignment_template")),
                        expiresAt = resultSet.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
                        persistedAt = resultSet.getObject("persisted_at", OffsetDateTime::class.java).toInstant(),
                    )
                }
            }
        }

    private fun <T> inTransaction(block: () -> T): T =
        requireNotNull(transactionOperations.execute { DispatchTransactionValue(block()) }).value

    private fun transactionalConnection(): Connection {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "run dispatch persistence requires an active transaction"
        }
        check(TransactionSynchronizationManager.hasResource(dataSource)) {
            "run dispatch persistence requires the Control DataSource transaction"
        }
        return DataSourceUtils.getConnection(dataSource)
    }
}

private data class DispatchTransactionValue<T>(val value: T)
