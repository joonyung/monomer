package cc.monomer.execution.adapter.outbound.postgres

import cc.monomer.execution.api.ArrowResultManifest
import cc.monomer.execution.api.ConnectorReference
import cc.monomer.execution.api.ConnectorRuntimePropertyValue
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.CredentialVersionReference
import cc.monomer.execution.api.ExecutableRequestKind
import cc.monomer.execution.api.ExecutionOwnerId
import cc.monomer.execution.api.ExecutionOwnerReference
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.ExecutionRunProgress
import cc.monomer.execution.api.ExecutionRunSnapshot
import cc.monomer.execution.api.ExecutionRunState
import cc.monomer.execution.api.PhysicalOutcome
import cc.monomer.execution.api.PhysicalOutcomeKind
import cc.monomer.execution.api.ResultSinkBinding
import cc.monomer.execution.api.ResultSinkBindingCanonicalizer
import cc.monomer.execution.api.ResultSinkKind
import cc.monomer.execution.api.RunCancelIntent
import cc.monomer.execution.api.RunRuntimeBindings
import cc.monomer.execution.api.Sha256Digest
import cc.monomer.execution.application.AdmittedExecutionRun
import cc.monomer.execution.application.StoredExecutionRunRead
import cc.monomer.identity.api.WorkspaceId
import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

private data class RunRow(
    val id: ExecutionRunId,
    val owner: ExecutionOwnerReference,
    val workspaceId: WorkspaceId,
    val idempotencyKey: String,
    val requestFingerprint: Sha256Digest,
    val connectorId: ConnectorReference,
    val connectorType: ConnectorType,
    val connectorProperties: Map<String, ConnectorRuntimePropertyValue>,
    val requestKind: ExecutableRequestKind,
    val canonicalRequestDigest: Sha256Digest,
    val runtimeBindings: RunRuntimeBindings,
    val dispatchDeadlineAt: Instant,
    val deadline: Instant,
    val state: ExecutionRunState,
    val physicalOutcomeKind: PhysicalOutcomeKind?,
    val physicalTerminalKey: String?,
    val physicalFailureCode: String?,
    val physicalSafeMessage: String?,
    val cancelIntent: RunCancelIntent?,
    val ownerSettledAt: Instant?,
    val ownerDiscardedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
    val executionOccupancyAcquiredAt: Instant?,
    val executionOccupancyReleasedAt: Instant?,
    val dispatchClaimedAt: Instant?,
    val lastHeartbeatAt: Instant?,
    val progress: ExecutionRunProgress?,
    val resultManifest: ArrowResultManifest?,
) {
    fun toSnapshot(): ExecutionRunSnapshot {
        val outcome =
            physicalOutcomeKind?.let { kind ->
                PhysicalOutcome(
                    terminalIdempotencyKey = requireNotNull(physicalTerminalKey),
                    kind = kind,
                    failureCode = physicalFailureCode,
                    safeMessage = physicalSafeMessage,
                    arrowResultManifest = resultManifest,
                )
            }
        return ExecutionRunSnapshot(
            id = id,
            owner = owner,
            workspaceId = workspaceId,
            connectorId = connectorId,
            connectorType = connectorType,
            connectorProperties = connectorProperties,
            requestKind = requestKind,
            canonicalRequestDigest = canonicalRequestDigest,
            runtimeBindings = runtimeBindings,
            dispatchDeadlineAt = dispatchDeadlineAt,
            deadline = deadline,
            state = state,
            physicalOutcome = outcome,
            cancelIntent = cancelIntent,
            ownerSettledAt = ownerSettledAt,
            ownerDiscardedAt = ownerDiscardedAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
            version = version,
            executionOccupancyAcquiredAt = executionOccupancyAcquiredAt,
            executionOccupancyReleasedAt = executionOccupancyReleasedAt,
            dispatchClaimedAt = dispatchClaimedAt,
            lastHeartbeatAt = lastHeartbeatAt,
            progress = progress,
            resultManifest = resultManifest,
        )
    }
}

internal fun ResultSet.toAdmittedRun(): AdmittedExecutionRun =
    toRunRow().let { row -> AdmittedExecutionRun(row.toSnapshot(), row.idempotencyKey, row.requestFingerprint) }

internal fun ResultSet.toStoredRunRead(): StoredExecutionRunRead {
    val runId = ExecutionRunId(getUuid("run_id"))
    return try {
        StoredExecutionRunRead.Readable(toAdmittedRun())
    } catch (_: RuntimeException) {
        StoredExecutionRunRead.Unreadable(runId)
    }
}

private fun ResultSet.toRunRow(): RunRow {
    val resultSink =
        ResultSinkBinding(
            schemaVersion = getInt("result_sink_schema_version").toUInt(),
            kind = ResultSinkKind.valueOf(getString("result_sink_kind")),
            objectKey = getString("result_sink_object_key"),
            maximumArtifactRows = getNullableUnsignedLong("result_sink_maximum_artifact_rows"),
            maximumArtifactBytes = getNullableUnsignedLong("result_sink_maximum_bytes"),
            expiresAt = getNullableInstant("result_sink_expires_at"),
            maximumRecordBatchRows = getInt("result_sink_maximum_record_batch_rows"),
            maximumFrameBytes = getInt("result_sink_maximum_frame_bytes"),
            maximumCellBytes = getInt("result_sink_maximum_cell_bytes"),
            maximumMultipartPartBytes = getNullableInt("result_sink_maximum_multipart_part_bytes"),
            bindingDigest = Sha256Digest("0".repeat(64)),
        ).let { binding ->
            binding.copy(bindingDigest = ResultSinkBindingCanonicalizer.digest(binding))
        }
    val progress =
        getString("progress_phase")?.let { phase ->
            ExecutionRunProgress(
                rowsProcessed = getUnsignedLong("progress_rows_processed"),
                bytesProcessed = getUnsignedLong("progress_bytes_processed"),
                phase = phase,
                observedAt = getInstant("progress_observed_at"),
            )
        }
    val resultManifest =
        getString("result_object_key")?.let { objectKey ->
            ArrowResultManifest(
                objectKey = objectKey,
                byteSize = getUnsignedLong("result_byte_size"),
                objectEtag = getString("result_object_etag"),
                rowCount = getUnsignedLong("result_row_count"),
                columnCount = getInt("result_column_count"),
                mediaType = getString("result_media_type"),
            )
        }
    val cancelIntent =
        getString("cancel_reason_code")?.let { reason ->
            RunCancelIntent(reason, getInstant("cancel_requested_at"))
        }
    return RunRow(
        id = ExecutionRunId(getUuid("run_id")),
        owner =
            ExecutionOwnerReference(
                type = ExecutionOwnerType.valueOf(getString("owner_type")),
                id = ExecutionOwnerId(getUuid("owner_id")),
            ),
        workspaceId = WorkspaceId(getUuid("workspace_id")),
        idempotencyKey = getString("idempotency_key"),
        requestFingerprint = Sha256Digest(getString("request_fingerprint_sha256").trim()),
        connectorId = ConnectorReference(getUuid("connector_id")),
        connectorType = ConnectorType.valueOf(getString("connector_type")),
        connectorProperties = decodeConnectorProperties(getBytes("connector_properties")),
        requestKind = ExecutableRequestKind.valueOf(getString("request_kind")),
        canonicalRequestDigest = Sha256Digest(getString("canonical_request_sha256").trim()),
        runtimeBindings =
            RunRuntimeBindings(
                connectorBindingDigest = Sha256Digest(getString("connector_binding_sha256").trim()),
                credentialVersion = getNullableUuid("credential_version")?.let(::CredentialVersionReference),
                credentialKeyVersion = getString("credential_key_version"),
                credentialBindingDigest = Sha256Digest(getString("credential_binding_sha256").trim()),
                resultSink = resultSink,
            ),
        dispatchDeadlineAt = getInstant("dispatch_deadline_at"),
        deadline = getInstant("deadline"),
        state = ExecutionRunState.valueOf(getString("state")),
        physicalOutcomeKind = getString("physical_outcome_kind")?.let(PhysicalOutcomeKind::valueOf),
        physicalTerminalKey = getString("physical_terminal_idempotency_key"),
        physicalFailureCode = getString("physical_failure_code"),
        physicalSafeMessage = getString("physical_safe_message"),
        cancelIntent = cancelIntent,
        ownerSettledAt = getNullableInstant("owner_settled_at"),
        ownerDiscardedAt = getNullableInstant("owner_discarded_at"),
        createdAt = getInstant("created_at"),
        updatedAt = getInstant("updated_at"),
        version = getLong("version"),
        executionOccupancyAcquiredAt = getNullableInstant("execution_occupancy_acquired_at"),
        executionOccupancyReleasedAt = getNullableInstant("execution_occupancy_released_at"),
        dispatchClaimedAt = getNullableInstant("dispatch_claimed_at"),
        lastHeartbeatAt = getNullableInstant("last_heartbeat_at"),
        progress = progress,
        resultManifest = resultManifest,
    )
}

internal fun PreparedStatement.bindRunInsert(run: AdmittedExecutionRun) {
    val snapshot = run.snapshot
    var index = 1
    setObject(index++, snapshot.id.value.toJavaUuid())
    setString(index++, snapshot.owner.type.name)
    setObject(index++, snapshot.owner.id.value.toJavaUuid())
    setObject(index++, snapshot.workspaceId.value.toJavaUuid())
    setString(index++, run.idempotencyKey)
    setString(index++, run.requestFingerprint.value)
    setObject(index++, snapshot.connectorId.value.toJavaUuid())
    setString(index++, snapshot.connectorType.name)
    setBytes(index++, encodeConnectorProperties(snapshot.connectorProperties))
    setString(index++, snapshot.requestKind.name)
    setString(index++, snapshot.canonicalRequestDigest.value)
    setString(index++, snapshot.runtimeBindings.connectorBindingDigest.value)
    setNullableUuid(index++, snapshot.runtimeBindings.credentialVersion?.value)
    setString(index++, snapshot.runtimeBindings.credentialKeyVersion)
    setString(index++, snapshot.runtimeBindings.credentialBindingDigest.value)
    setInt(index++, snapshot.runtimeBindings.resultSink.schemaVersion.toInt())
    setString(index++, snapshot.runtimeBindings.resultSink.kind.name)
    setNullableString(index++, snapshot.runtimeBindings.resultSink.objectKey)
    setNullableBigDecimal(index++, snapshot.runtimeBindings.resultSink.maximumArtifactRows?.toBigDecimal())
    setNullableBigDecimal(index++, snapshot.runtimeBindings.resultSink.maximumArtifactBytes?.toBigDecimal())
    setNullableInstant(index++, snapshot.runtimeBindings.resultSink.expiresAt)
    setInt(index++, snapshot.runtimeBindings.resultSink.maximumRecordBatchRows)
    setInt(index++, snapshot.runtimeBindings.resultSink.maximumFrameBytes)
    setInt(index++, snapshot.runtimeBindings.resultSink.maximumCellBytes)
    setNullableInt(index++, snapshot.runtimeBindings.resultSink.maximumMultipartPartBytes)
    setInstant(index++, snapshot.dispatchDeadlineAt)
    bindRunMutable(snapshot, includeCreatedAt = true, startIndex = index)
}

internal fun PreparedStatement.bindRunMutable(
    snapshot: ExecutionRunSnapshot,
    includeCreatedAt: Boolean,
    startIndex: Int = 1,
): Int {
    var index = startIndex
    val outcome = snapshot.physicalOutcome
    val cancel = snapshot.cancelIntent
    setInstant(index++, snapshot.deadline)
    setString(index++, snapshot.state.name)
    setNullableString(index++, outcome?.terminalIdempotencyKey)
    setNullableString(index++, outcome?.kind?.name)
    setNullableString(index++, outcome?.failureCode)
    setNullableString(index++, outcome?.safeMessage)
    setNullableString(index++, cancel?.reasonCode)
    setNullableInstant(index++, cancel?.requestedAt)
    setNullableInstant(index++, snapshot.ownerSettledAt)
    setNullableInstant(index++, snapshot.ownerDiscardedAt)
    if (includeCreatedAt) setInstant(index++, snapshot.createdAt)
    setInstant(index++, snapshot.updatedAt)
    setLong(index++, snapshot.version)
    setNullableInstant(index++, snapshot.executionOccupancyAcquiredAt)
    setNullableInstant(index++, snapshot.executionOccupancyReleasedAt)
    setNullableInstant(index++, snapshot.dispatchClaimedAt)
    setNullableInstant(index++, snapshot.lastHeartbeatAt)
    setNullableBigDecimal(index++, snapshot.progress?.rowsProcessed?.toBigDecimal())
    setNullableBigDecimal(index++, snapshot.progress?.bytesProcessed?.toBigDecimal())
    setNullableString(index++, snapshot.progress?.phase)
    setNullableInstant(index++, snapshot.progress?.observedAt)
    setNullableString(index++, snapshot.resultManifest?.objectKey)
    setNullableString(index++, snapshot.resultManifest?.objectEtag)
    setNullableBigDecimal(index++, snapshot.resultManifest?.byteSize?.toBigDecimal())
    setNullableBigDecimal(index++, snapshot.resultManifest?.rowCount?.toBigDecimal())
    setNullableInt(index++, snapshot.resultManifest?.columnCount)
    setNullableString(index++, snapshot.resultManifest?.mediaType)
    return index
}

internal fun ResultSet.getUuid(columnLabel: String): Uuid =
    getObject(columnLabel, UUID::class.java).toKotlinUuid()

private fun ResultSet.getNullableUuid(columnLabel: String): Uuid? =
    getObject(columnLabel, UUID::class.java)?.toKotlinUuid()

private fun ResultSet.getInstant(columnLabel: String): Instant =
    getObject(columnLabel, OffsetDateTime::class.java).toInstant()

private fun ResultSet.getNullableInstant(columnLabel: String): Instant? =
    getObject(columnLabel, OffsetDateTime::class.java)?.toInstant()

private fun ResultSet.getNullableInt(columnLabel: String): Int? =
    getObject(columnLabel)?.let { (it as Number).toInt() }

private fun ResultSet.getUnsignedLong(columnLabel: String): ULong =
    getBigDecimal(columnLabel).toBigIntegerExact().toString().toULong()

private fun ResultSet.getNullableUnsignedLong(columnLabel: String): ULong? =
    getObject(columnLabel)?.let { getBigDecimal(columnLabel).toBigIntegerExact().toString().toULong() }

internal fun PreparedStatement.setInstant(index: Int, value: Instant) {
    setObject(index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
}

private fun PreparedStatement.setNullableInstant(index: Int, value: Instant?) {
    if (value == null) setNull(index, Types.TIMESTAMP_WITH_TIMEZONE) else setInstant(index, value)
}

private fun PreparedStatement.setNullableString(index: Int, value: String?) {
    if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
}

private fun PreparedStatement.setNullableInt(index: Int, value: Int?) {
    if (value == null) setNull(index, Types.INTEGER) else setInt(index, value)
}

private fun PreparedStatement.setNullableUuid(index: Int, value: Uuid?) {
    if (value == null) setNull(index, Types.OTHER) else setObject(index, value.toJavaUuid())
}

private fun PreparedStatement.setNullableBigDecimal(index: Int, value: BigDecimal?) {
    if (value == null) setNull(index, Types.NUMERIC) else setBigDecimal(index, value)
}

private fun ULong.toBigDecimal(): BigDecimal = BigDecimal(toString())

internal const val runInsertSql =
    """
    INSERT INTO execution_run.runs (
        run_id, owner_type, owner_id, workspace_id, idempotency_key,
        request_fingerprint_sha256, connector_id, connector_type, connector_properties,
        request_kind, canonical_request_sha256, connector_binding_sha256,
        credential_version, credential_key_version, credential_binding_sha256,
        result_sink_schema_version, result_sink_kind, result_sink_object_key,
        result_sink_maximum_artifact_rows, result_sink_maximum_bytes, result_sink_expires_at,
        result_sink_maximum_record_batch_rows, result_sink_maximum_frame_bytes,
        result_sink_maximum_cell_bytes, result_sink_maximum_multipart_part_bytes,
        dispatch_deadline_at, deadline, state, physical_terminal_idempotency_key,
        physical_outcome_kind, physical_failure_code, physical_safe_message,
        cancel_reason_code, cancel_requested_at, owner_settled_at, owner_discarded_at, created_at, updated_at,
        version, execution_occupancy_acquired_at, execution_occupancy_released_at, dispatch_claimed_at, last_heartbeat_at, progress_rows_processed,
        progress_bytes_processed, progress_phase, progress_observed_at, result_object_key,
        result_object_etag, result_byte_size, result_row_count, result_column_count,
        result_media_type
    ) VALUES (
        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
    )
    """

internal const val runUpdateSql =
    """
    UPDATE execution_run.runs
    SET deadline = ?, state = ?, physical_terminal_idempotency_key = ?,
        physical_outcome_kind = ?, physical_failure_code = ?, physical_safe_message = ?,
        cancel_reason_code = ?, cancel_requested_at = ?, owner_settled_at = ?, owner_discarded_at = ?,
        updated_at = ?, version = ?, execution_occupancy_acquired_at = ?, execution_occupancy_released_at = ?, dispatch_claimed_at = ?, last_heartbeat_at = ?,
        progress_rows_processed = ?, progress_bytes_processed = ?, progress_phase = ?,
        progress_observed_at = ?, result_object_key = ?, result_object_etag = ?,
        result_byte_size = ?, result_row_count = ?, result_column_count = ?, result_media_type = ?
    WHERE run_id = ? AND version = ?
    """
