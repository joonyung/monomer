@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.explore.adapter.outbound.postgres

import cc.monomer.connector.api.ConnectorId
import cc.monomer.execution.api.ConnectorType
import cc.monomer.explore.api.ExploreConnectorBinding
import cc.monomer.explore.api.ExploreDigest
import cc.monomer.explore.api.ExploreResultRetention
import cc.monomer.explore.api.ExploreResultView
import cc.monomer.explore.api.ExploreRunFailureCode
import cc.monomer.explore.api.ExploreRunId
import cc.monomer.explore.api.ExploreRunKind
import cc.monomer.explore.api.ExploreRunPageBoundary
import cc.monomer.explore.api.ExploreRunState
import cc.monomer.explore.application.ExploreRunRepository
import cc.monomer.explore.domain.ExploreRun
import cc.monomer.explore.domain.ExploreTerminalReceipt
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionSynchronizationManager

/** PostgreSQL adapter for the Explore owner. */
class PostgresExploreRunRepository(
    private val dataSource: DataSource,
) : ExploreRunRepository {
    override fun lockAdmissionKey(workspaceId: WorkspaceId, idempotencyKey: String) {
        requireActiveTransaction()
        connection { connection ->
            connection.prepareStatement(INSERT_ADMISSION_KEY_SQL).use { statement ->
                statement.setObject(1, workspaceId.value.toJavaUuid())
                statement.setString(2, idempotencyKey)
                statement.executeUpdate()
            }
            connection.prepareStatement(LOCK_ADMISSION_KEY_SQL).use { statement ->
                statement.setObject(1, workspaceId.value.toJavaUuid())
                statement.setString(2, idempotencyKey)
                statement.executeQuery().use { resultSet -> check(resultSet.next()) }
            }
        }
    }

    override fun findByAdmissionKey(workspaceId: WorkspaceId, idempotencyKey: String): ExploreRun? {
        requireActiveTransaction()
        return connection { connection ->
            connection.prepareStatement(FIND_RUN_BY_ADMISSION_SQL).use { statement ->
                statement.setObject(1, workspaceId.value.toJavaUuid())
                statement.setString(2, idempotencyKey)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) loadRun(connection, resultSet.uuid("run_id"), true) else null
                }
            }
        }
    }

    override fun findRun(runId: ExploreRunId): ExploreRun? =
        standaloneConnection { connection -> loadRun(connection, runId.value, false) }

    override fun findRuns(
        workspaceId: WorkspaceId,
        ownerPrincipalId: PrincipalId,
        after: ExploreRunPageBoundary?,
        limit: Int,
    ): List<ExploreRun> {
        require(limit in 1..MAX_RUN_PAGE_QUERY_SIZE)
        return standaloneConnection { connection ->
            val sql = if (after == null) FIND_RUN_PAGE_SQL else FIND_RUN_PAGE_AFTER_SQL
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, workspaceId.value.toJavaUuid())
                statement.setObject(2, ownerPrincipalId.value.toJavaUuid())
                var index = 3
                if (after != null) {
                    statement.setInstant(index++, after.createdAt)
                    statement.setObject(index++, after.runId.value.toJavaUuid())
                }
                statement.setInt(index, limit)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            val receipt = resultSet.getObject("${RECEIPT_COLUMN_PREFIX}run_id")
                                ?.let { resultSet.readTerminalReceipt(RECEIPT_COLUMN_PREFIX) }
                            add(resultSet.readRun(receipt?.result, receipt?.failureMessage) { resultSet.readSemanticDetails() })
                        }
                    }
                }
            }
        }
    }

    override fun insertAdmittedRun(run: ExploreRun): Boolean {
        requireActiveTransaction()
        return connection { connection ->
            connection.prepareStatement(INSERT_RUN_SQL).use { statement ->
                var index = 1
                statement.setObject(index++, run.id.value.toJavaUuid())
                statement.setObject(index++, run.workspaceId.value.toJavaUuid())
                statement.setString(index++, run.idempotencyKey)
                statement.setObject(index++, run.ownerPrincipalId.value.toJavaUuid())
                statement.setObject(index++, run.connectorBinding.connectorId.value.toJavaUuid())
                statement.setString(index++, run.connectorBinding.connectorType.name)
                statement.setString(index++, run.kind.type.name)
                statement.setString(index++, run.requestDigest.value)
                statement.setString(index++, run.state.name)
                statement.setString(index++, run.failureCode?.name)
                statement.setLong(index++, run.revision)
                statement.setInstant(index++, run.createdAt)
                statement.setInstant(index++, run.updatedAt)
                statement.setString(index++, run.credentialSurface?.name)
                statement.setString(index, run.agentClientId?.value)
                if (statement.executeUpdate() != 1) return@connection false
            }
            if (run.kind is ExploreRun.Kind.Semantic) insertSemanticDetails(connection, run, run.kind)
            true
        }
    }

    override fun lockRunForOwnerMutation(runId: ExploreRunId): ExploreRun? {
        requireActiveTransaction()
        return connection { connection -> loadRun(connection, runId.value, true) }
    }

    override fun lockRunForResultRead(runId: ExploreRunId): ExploreRun? {
        requireActiveTransaction()
        return connection { connection -> loadRun(connection, runId.value, true) }
    }

    override fun findTerminalReceipt(
        workspaceId: WorkspaceId,
        settlementIdempotencyKey: String,
    ): ExploreTerminalReceipt? = readConnection { connection ->
        findTerminalReceiptInConnection(connection, workspaceId, settlementIdempotencyKey)
    }

    override fun commitTerminalReceipt(
        run: ExploreRun,
        receipt: ExploreTerminalReceipt,
        expectedRevision: Long,
    ): Boolean {
        requireActiveTransaction()
        if (
            run.state !in cc.monomer.explore.domain.TERMINAL_STATES ||
                run.revision != expectedRevision + 1 ||
                receipt.runId != run.id ||
                receipt.workspaceId != run.workspaceId ||
                receipt.terminalState != run.state ||
                receipt.failureCode != run.failureCode ||
                receipt.failureMessage != run.failureMessage ||
                receipt.result != run.visibleResult ||
                receipt.settledAt != run.updatedAt
        ) return false
        return connection { connection ->
            if (findTerminalReceiptInConnection(connection, receipt.workspaceId, receipt.settlementIdempotencyKey) != null) {
                return@connection false
            }
            if (!insertTerminalReceipt(connection, receipt)) return@connection false
            updateRun(connection, run, expectedRevision)
        }
    }

    override fun lockVisibleResultForRead(runId: ExploreRunId): ExploreResultView? {
        requireActiveTransaction()
        return connection { connection ->
            connection.prepareStatement(LOCK_VISIBLE_RESULT_SQL).use { statement ->
                statement.setObject(1, runId.value.toJavaUuid())
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.readResultView("") else null
                }
            }
        }
    }

    private fun insertSemanticDetails(connection: Connection, run: ExploreRun, kind: ExploreRun.Kind.Semantic) =
        connection.prepareStatement(INSERT_SEMANTIC_DETAILS_SQL).use { statement ->
            statement.setObject(1, run.id.value.toJavaUuid())
            statement.setObject(2, run.workspaceId.value.toJavaUuid())
            statement.setString(3, run.kind.type.name)
            statement.setString(4, SemanticQueryIntentJsonCodec.encode(kind.query))
            statement.setString(5, kind.outputColumnDisplays?.let { cc.monomer.semantic.api.MetricDisplayJsonCodec.encodeColumns(it).toString() })
            statement.setString(6, kind.reportingTimeZone?.id)
            check(statement.executeUpdate() == 1)
        }

    private fun loadRun(connection: Connection, runId: Uuid, lock: Boolean): ExploreRun? {
        val sql = if (lock) "$FIND_RUN_SQL FOR UPDATE" else FIND_RUN_SQL
        return connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, runId.toJavaUuid())
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) return@use null
                val receipt = loadTerminalReceipt(connection, runId, lock)
                resultSet.readRun(receipt?.result, receipt?.failureMessage) { loadSemanticDetails(connection, runId) }
            }
        }
    }

    private fun loadTerminalReceipt(
        connection: Connection,
        runId: Uuid,
        lock: Boolean,
    ): ExploreTerminalReceipt? {
        val sql = if (lock) "$FIND_TERMINAL_RECEIPT_BY_RUN_SQL FOR SHARE" else FIND_TERMINAL_RECEIPT_BY_RUN_SQL
        return connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, runId.toJavaUuid())
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.readTerminalReceipt("") else null
            }
        }
    }

    private fun findTerminalReceiptInConnection(
        connection: Connection,
        workspaceId: WorkspaceId,
        settlementIdempotencyKey: String,
    ): ExploreTerminalReceipt? = connection.prepareStatement(FIND_TERMINAL_RECEIPT_SQL).use { statement ->
        statement.setObject(1, workspaceId.value.toJavaUuid())
        statement.setString(2, settlementIdempotencyKey)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.readTerminalReceipt("") else null
        }
    }

    private fun insertTerminalReceipt(connection: Connection, receipt: ExploreTerminalReceipt): Boolean =
        connection.prepareStatement(INSERT_TERMINAL_RECEIPT_SQL).use { statement ->
            var index = 1
            statement.setObject(index++, receipt.workspaceId.value.toJavaUuid())
            statement.setString(index++, receipt.settlementIdempotencyKey)
            statement.setObject(index++, receipt.runId.value.toJavaUuid())
            statement.setString(index++, receipt.terminalState.name)
            statement.setString(index++, receipt.failureCode?.name)
            statement.setString(index++, receipt.failureMessage)
            statement.setString(index++, receipt.result?.objectKey)
            statement.setString(index++, receipt.result?.objectEtag)
            receipt.result?.byteSize?.let { statement.setLong(index++, it.asDatabaseLong()) }
                ?: statement.setNull(index++, Types.BIGINT)
            statement.setString(index++, receipt.result?.mediaType)
            receipt.result?.rowCount?.let { statement.setLong(index++, it.asDatabaseLong()) }
                ?: statement.setNull(index++, Types.BIGINT)
            receipt.result?.columnCount?.let { statement.setInt(index++, it) }
                ?: statement.setNull(index++, Types.INTEGER)
            statement.setInstant(index++, receipt.result?.retention?.expiresAt)
            statement.setObject(index++, receipt.result?.ownerPrincipalId?.value?.toJavaUuid())
            statement.setInstant(index, receipt.settledAt)
            statement.executeUpdate() == 1
        }

    private fun updateRun(connection: Connection, run: ExploreRun, expectedRevision: Long): Boolean =
        connection.prepareStatement(UPDATE_RUN_SQL).use { statement ->
            var index = 1
            statement.setString(index++, run.state.name)
            statement.setString(index++, run.failureCode?.name)
            statement.setLong(index++, run.revision)
            statement.setInstant(index++, run.updatedAt)
            statement.setInstant(index++, run.updatedAt.takeIf { run.state in cc.monomer.explore.domain.TERMINAL_STATES })
            statement.setObject(index++, run.id.value.toJavaUuid())
            statement.setLong(index, expectedRevision)
            statement.executeUpdate() == 1
        }

    private fun requireActiveTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive())
        check(TransactionSynchronizationManager.hasResource(dataSource))
    }

    private fun <T> connection(block: (Connection) -> T): T {
        val connection = DataSourceUtils.getConnection(dataSource)
        return try {
            check(!connection.autoCommit)
            block(connection)
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
    }

    private fun <T> readConnection(block: (Connection) -> T): T =
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            requireActiveTransaction()
            connection(block)
        } else {
            standaloneConnection(block)
        }

    private fun <T> standaloneConnection(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        check(connection.autoCommit)
        connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
        connection.autoCommit = false
        try {
            block(connection)
        } finally {
            connection.rollback()
        }
    }
}

private fun ResultSet.readRun(
    result: ExploreResultView?,
    failureMessage: String?,
    semanticDetails: () -> SemanticRunDetails,
): ExploreRun {
    val workspaceId = WorkspaceId(uuid("workspace_id"))
    val binding = ExploreConnectorBinding(
        workspaceId = workspaceId,
        connectorId = ConnectorId(uuid("connector_id")),
        connectorType = ConnectorType.valueOf(getString("connector_type")),
    )
    val kind = when (ExploreRunKind.valueOf(getString("kind"))) {
        ExploreRunKind.SQL -> ExploreRun.Kind.Sql
        ExploreRunKind.SEMANTIC -> semanticDetails().let { details ->
            ExploreRun.Kind.Semantic(details.query, details.outputColumnDisplays, details.reportingTimeZone)
        }
    }
    return ExploreRun(
        id = ExploreRunId(uuid("run_id")),
        kind = kind,
        idempotencyKey = getString("idempotency_key"),
        ownerPrincipalId = PrincipalId(uuid("owner_principal_id")),
        credentialSurface = getString("credential_surface")?.let(cc.monomer.identity.api.CredentialSurface::valueOf),
        agentClientId = getString("agent_client_id")?.let { cc.monomer.identity.api.AgentClientId(it) },
        connectorBinding = binding,
        requestDigest = ExploreDigest(getString("request_digest")),
        state = ExploreRunState.valueOf(getString("state")),
        visibleResult = result,
        failureCode = getString("failure_code")?.let(ExploreRunFailureCode::valueOf),
        failureMessage = failureMessage,
        revision = getLong("revision"),
        createdAt = instant("created_at"),
        updatedAt = instant("updated_at"),
    )
}

private data class SemanticRunDetails(
    val query: cc.monomer.semantic.api.SemanticQuerySpec,
    val outputColumnDisplays: List<cc.monomer.semantic.api.SemanticOutputColumnDisplay>?,
    val reportingTimeZone: java.time.ZoneId?,
)

private fun loadSemanticDetails(connection: Connection, runId: Uuid): SemanticRunDetails =
    connection.prepareStatement(SELECT_SEMANTIC_DETAILS_SQL).use { statement ->
        statement.setObject(1, runId.toJavaUuid())
        statement.executeQuery().use { resultSet ->
            check(resultSet.next())
            resultSet.readSemanticDetails()
        }
    }

private fun ResultSet.readSemanticDetails(): SemanticRunDetails = SemanticRunDetails(
    query = SemanticQueryIntentJsonCodec.decode(checkNotNull(getString("query_json"))),
    outputColumnDisplays = getString("output_column_displays")?.let {
        cc.monomer.semantic.api.MetricDisplayJsonCodec.decodeColumns(kotlinx.serialization.json.Json.parseToJsonElement(it))
    },
    reportingTimeZone = getString("reporting_time_zone")?.let(java.time.ZoneId::of),
)

private fun ResultSet.readTerminalReceipt(columnPrefix: String): ExploreTerminalReceipt {
    val result = getString("${columnPrefix}object_key")?.let { readResultView(columnPrefix) }
    return ExploreTerminalReceipt(
        workspaceId = WorkspaceId(uuid("${columnPrefix}workspace_id")),
        settlementIdempotencyKey = getString("${columnPrefix}settlement_idempotency_key"),
        runId = ExploreRunId(uuid("${columnPrefix}run_id")),
        terminalState = ExploreRunState.valueOf(getString("${columnPrefix}terminal_state")),
        failureCode = getString("${columnPrefix}failure_code")?.let(ExploreRunFailureCode::valueOf),
        failureMessage = getString("${columnPrefix}failure_message"),
        result = result,
        settledAt = instant("${columnPrefix}settled_at"),
    )
}

private fun ResultSet.readResultView(columnPrefix: String): ExploreResultView = ExploreResultView(
    runId = ExploreRunId(uuid("${columnPrefix}run_id")),
    objectKey = getString("${columnPrefix}object_key"),
    objectEtag = getString("${columnPrefix}object_etag"),
    byteSize = getLong("${columnPrefix}byte_size").toULong(),
    mediaType = getString("${columnPrefix}media_type"),
    rowCount = getLong("${columnPrefix}row_count").toULong(),
    columnCount = getInt("${columnPrefix}column_count"),
    retention = ExploreResultRetention(instant("${columnPrefix}retention_expires_at")),
    ownerPrincipalId = PrincipalId(uuid("${columnPrefix}owner_principal_id")),
    activatedAt = instant("${columnPrefix}settled_at"),
)

private fun PreparedStatement.setInstant(index: Int, value: Instant?) {
    if (value == null) setNull(index, Types.TIMESTAMP_WITH_TIMEZONE)
    else setObject(index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
}

private fun ResultSet.uuid(column: String): Uuid = checkNotNull(getObject(column, UUID::class.java)?.toKotlinUuid())
private fun ResultSet.instant(column: String): Instant = checkNotNull(getObject(column, OffsetDateTime::class.java)).toInstant()
private fun ULong.asDatabaseLong(): Long {
    require(this <= Long.MAX_VALUE.toULong())
    return toLong()
}

private const val INSERT_ADMISSION_KEY_SQL =
    "INSERT INTO explore.admission_keys (workspace_id, idempotency_key) VALUES (?, ?) ON CONFLICT DO NOTHING"
private const val LOCK_ADMISSION_KEY_SQL =
    "SELECT workspace_id FROM explore.admission_keys WHERE workspace_id = ? AND idempotency_key = ? FOR UPDATE"
private const val FIND_RUN_BY_ADMISSION_SQL =
    "SELECT run_id FROM explore.runs WHERE workspace_id = ? AND idempotency_key = ?"
private const val RECEIPT_COLUMN_PREFIX = "receipt_"
private const val FIND_RUN_PAGE_SELECT =
    """
    SELECT runs.run_id, runs.workspace_id, runs.idempotency_key, runs.owner_principal_id,
           runs.connector_id, runs.connector_type, runs.kind, runs.request_digest, runs.state, runs.failure_code,
           runs.revision, runs.created_at, runs.updated_at, runs.credential_surface, runs.agent_client_id,
           receipts.workspace_id AS receipt_workspace_id,
           receipts.settlement_idempotency_key AS receipt_settlement_idempotency_key,
           receipts.run_id AS receipt_run_id, receipts.terminal_state AS receipt_terminal_state,
           receipts.failure_code AS receipt_failure_code, receipts.failure_message AS receipt_failure_message,
           receipts.object_key AS receipt_object_key, receipts.object_etag AS receipt_object_etag,
           receipts.byte_size AS receipt_byte_size, receipts.media_type AS receipt_media_type,
           receipts.row_count AS receipt_row_count, receipts.column_count AS receipt_column_count,
           receipts.retention_expires_at AS receipt_retention_expires_at,
           receipts.owner_principal_id AS receipt_owner_principal_id, receipts.settled_at AS receipt_settled_at,
           details.query_json, details.output_column_displays, details.reporting_time_zone
    FROM explore.runs runs
    LEFT JOIN explore.terminal_receipts receipts ON receipts.run_id = runs.run_id
    LEFT JOIN explore.semantic_run_details details
      ON details.run_id = runs.run_id AND details.workspace_id = runs.workspace_id AND details.kind = runs.kind
    WHERE runs.workspace_id = ? AND runs.owner_principal_id = ?
    """
private const val FIND_RUN_PAGE_SQL =
    """
    $FIND_RUN_PAGE_SELECT
    ORDER BY runs.created_at DESC, runs.run_id DESC
    LIMIT ?
    """
private const val FIND_RUN_PAGE_AFTER_SQL =
    """
    $FIND_RUN_PAGE_SELECT
      AND (runs.created_at, runs.run_id) < (?, ?)
    ORDER BY runs.created_at DESC, runs.run_id DESC
    LIMIT ?
    """
private val FIND_RUN_SQL =
    """
    SELECT run_id, workspace_id, idempotency_key, owner_principal_id,
           connector_id, connector_type, kind, request_digest, state, failure_code,
           revision, created_at, updated_at, credential_surface, agent_client_id
    FROM explore.runs
    WHERE run_id = ?
    """.trimIndent()
private const val INSERT_RUN_SQL =
    """
    INSERT INTO explore.runs (
        run_id, workspace_id, idempotency_key, owner_principal_id,
        connector_id, connector_type, kind, request_digest,
        state, failure_code, revision, created_at, updated_at, credential_surface, agent_client_id
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """
private const val INSERT_SEMANTIC_DETAILS_SQL =
    "INSERT INTO explore.semantic_run_details (run_id, workspace_id, kind, query_json, output_column_displays, reporting_time_zone) VALUES (?, ?, ?, ?, ?::jsonb, ?)"
private const val SELECT_SEMANTIC_DETAILS_SQL =
    "SELECT query_json, output_column_displays, reporting_time_zone FROM explore.semantic_run_details WHERE run_id = ? AND kind = 'SEMANTIC'"
private const val UPDATE_RUN_SQL =
    """
    UPDATE explore.runs
    SET state = ?, failure_code = ?, revision = ?, updated_at = ?, terminal_at = ?
    WHERE run_id = ? AND revision = ?
    """
private const val FIND_TERMINAL_RECEIPT_SQL =
    """
    SELECT workspace_id, settlement_idempotency_key, run_id, terminal_state,
           failure_code, failure_message, object_key, object_etag, byte_size,
           media_type, row_count, column_count, retention_expires_at,
           owner_principal_id, settled_at
    FROM explore.terminal_receipts
    WHERE workspace_id = ? AND settlement_idempotency_key = ?
    FOR SHARE
    """
private const val INSERT_TERMINAL_RECEIPT_SQL =
    """
    INSERT INTO explore.terminal_receipts (
        workspace_id, settlement_idempotency_key, run_id, terminal_state,
        failure_code, failure_message, object_key, object_etag, byte_size,
        media_type, row_count, column_count, retention_expires_at,
        owner_principal_id, settled_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT DO NOTHING
    """
private const val FIND_TERMINAL_RESULT_SQL =
    """
    SELECT run_id, object_key, object_etag, byte_size, media_type, row_count, column_count,
           retention_expires_at, owner_principal_id, settled_at
    FROM explore.terminal_receipts
    WHERE run_id = ? AND terminal_state = 'SUCCEEDED'
    """
private const val FIND_TERMINAL_RECEIPT_BY_RUN_SQL =
    """
    SELECT workspace_id, settlement_idempotency_key, run_id, terminal_state,
           failure_code, failure_message, object_key, object_etag, byte_size,
           media_type, row_count, column_count, retention_expires_at,
           owner_principal_id, settled_at
    FROM explore.terminal_receipts
    WHERE run_id = ?
    """
private const val LOCK_VISIBLE_RESULT_SQL = "$FIND_TERMINAL_RESULT_SQL FOR SHARE"
private const val MAX_RUN_PAGE_QUERY_SIZE = 101
