@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.control.pagereader.*

import kotlinx.coroutines.CoroutineDispatcher
import cc.monomer.connector.api.ConnectorProductType
import cc.monomer.connector.api.ConnectorWorkspaceReadUseCase
import cc.monomer.connector.api.ConnectorExecutionAccessInOwnerTransactionPort
import cc.monomer.connector.api.ConnectorExecutionAccessResult
import cc.monomer.connector.api.ConnectorRuntimeBindingProjectionPort
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.ControlRuntimeProperties
import cc.monomer.dashboard.api.QueryRunId
import cc.monomer.dashboard.api.DashboardSessionId
import cc.monomer.dashboard.application.DashboardExecutionAdmissionPort
import cc.monomer.dashboard.application.DashboardExecutionCancellationPort
import cc.monomer.dashboard.application.DashboardQueryRunSettlementUseCase
import cc.monomer.dashboard.application.DashboardResultReadPort
import cc.monomer.dashboard.application.DashboardQueryPlanningPort
import cc.monomer.dashboard.application.DashboardTransactionPort
import cc.monomer.dashboard.application.QueryRunRepository
import cc.monomer.dashboard.application.DashboardQueryResolution
import cc.monomer.dashboard.domain.DashboardNamedQuery
import cc.monomer.dashboard.domain.DashboardResolvedQuery
import cc.monomer.dashboard.domain.DashboardBindValue
import cc.monomer.dashboard.domain.DashboardValueType
import cc.monomer.execution.api.QueryParameterValue
import cc.monomer.execution.api.NullQueryParameterType
import cc.monomer.execution.api.ArrowResultManifest
import cc.monomer.execution.api.ConnectorReference
import cc.monomer.execution.api.ExecutionOwnerId
import cc.monomer.execution.api.ExecutionOwnerReference
import cc.monomer.execution.api.ExecutionOwnerType
import cc.monomer.execution.api.ExecutionRunAdmissionPort
import cc.monomer.execution.api.ExecutionRunSnapshot
import cc.monomer.execution.api.ExecutionRunOwnerSettlementPort
import cc.monomer.execution.api.OwnerExecutionRunCancellationPort
import cc.monomer.execution.api.PhysicalOutcomeKind
import cc.monomer.execution.api.RawSqlExecutionRequest
import cc.monomer.execution.api.RawSqlExecutionRequestCanonicalizer
import cc.monomer.execution.api.RawSqlRunSpecCommand
import cc.monomer.execution.api.RawSqlRunSpecFactory
import cc.monomer.execution.api.ResultSinkBinding
import cc.monomer.execution.api.ResultLogicalType
import cc.monomer.execution.api.Sha256Digest
import cc.monomer.semantic.api.SemanticRenderRequest
import cc.monomer.semantic.api.SemanticRenderResult
import cc.monomer.semantic.application.SemanticQueryUseCase
import cc.monomer.control.semantic.inbound.rest.SemanticQueryHttpRequest
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.DateDayVector
import org.apache.arrow.vector.Decimal256Vector
import org.apache.arrow.vector.DecimalVector
import org.apache.arrow.vector.FieldVector
import org.apache.arrow.vector.TimeStampMicroTZVector
import org.apache.arrow.vector.VarBinaryVector
import org.apache.arrow.vector.ipc.ArrowFileReader
import org.apache.arrow.vector.ipc.SeekableReadChannel
import org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager

internal fun emitDashboardSessionInvalidationAfterCommit(
    eventHub: DashboardSessionEventHub,
    sessionId: DashboardSessionId,
    reason: String,
) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) return
    eventHub.emit(sessionId, reason)
}

internal fun emitDashboardDraftInvalidationAfterCommit(
    eventHub: DashboardDraftEventHub,
    dashboardId: cc.monomer.dashboard.api.DashboardId,
    reason: String,
) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) return
    eventHub.emit(dashboardId, reason)
}

class SpringDashboardTransactionPort(
    private val transactionOperations: TransactionOperations,
    private val blockingDispatcher: CoroutineDispatcher,
) : DashboardTransactionPort {
    override suspend fun <T> inTransaction(block: () -> T): T {
        return withContext(blockingDispatcher) {
            requireNotNull(transactionOperations.execute { TransactionValue(block()) }).value
        }
    }

    private data class TransactionValue<T>(val value: T)
}

class DashboardQueryPlanningAdapter(
    private val dataSource: javax.sql.DataSource,
    private val objectMapper: ObjectMapper,
    private val semanticQueryUseCase: SemanticQueryUseCase,
    private val controlAdmissionFacade: ControlAdmissionFacade,
    private val consistentSnapshotTransactionRunner: ConsistentSnapshotTransactionRunner,
    private val planningTransactionOperations: TransactionOperations,
    private val blockingDispatcher: CoroutineDispatcher,
    private val pageAccess: cc.monomer.control.pagereader.PublicPagesAccess,
    private val pages: cc.monomer.pages.api.PagesCurrentReadPort,
    private val connectorRead: ConnectorWorkspaceReadUseCase,
) : DashboardQueryPlanningPort {
    override suspend fun admitPageReader(
        workspaceId: cc.monomer.identity.api.WorkspaceId, dashboardId: cc.monomer.dashboard.api.DashboardId, pageId: kotlin.uuid.Uuid,
        credential: cc.monomer.identity.api.PresentedCredential?, expectedOAuthResource: cc.monomer.identity.api.ExpectedOAuthResource?,
    ): cc.monomer.dashboard.api.DashboardOwnerOutcome<cc.monomer.identity.api.IdentityCredentialEvidence?> =
        consistentSnapshotTransactionRunner.run {
            try {
                val actor = pageAccess.requireWorkspace(workspaceId, credential, expectedOAuthResource)
                pages.requirePlacedDashboardPage(workspaceId, pageId, dashboardId)
                cc.monomer.dashboard.api.DashboardOwnerOutcome.Success(actor)
            } catch (_: PageReaderProblem) {
                cc.monomer.dashboard.api.DashboardOwnerOutcome.Rejected("DASHBOARD_QUERY_UNAUTHORIZED", null)
            }
        }

    override suspend fun resolveBatch(
        commands: List<DashboardQueryPlanningPort.ResolveQueryCommand>,
    ): List<DashboardQueryResolution> {
        if (commands.isEmpty()) return emptyList()
        require(commands.size <= 64)
        val deadline = requireNotNull(commands.first().deadline)
        require(commands.all { it.workspaceId == commands.first().workspaceId && it.deadline == deadline && it.credential == null && it.readerEvidence == null })
        val started = System.nanoTime()
        val sqlConnectorIds = commands.filter { it.query is DashboardNamedQuery.Sql }.map { it.query.connectorId }.toSet()
        val connectorRemaining = java.time.Duration.between(Instant.now(), deadline).toMillis()
        if (connectorRemaining <= 0) throw java.util.concurrent.TimeoutException("Planning deadline exceeded")
        val sqlConnectorTypes = if (sqlConnectorIds.isEmpty()) emptyMap() else withTimeout(connectorRemaining) {
            connectorRead.findByWorkspaceAndIds(commands.first().workspaceId, sqlConnectorIds)
        }.associate { it.connectorId to it.connectorType }
        val requests = commands.map { command ->
            when (val query = command.resolvedQuery) {
                is DashboardResolvedQuery.Sql -> null
                is DashboardResolvedQuery.Semantic -> try {
                    SemanticRenderRequest(command.workspaceId, command.query.connectorId, SemanticQueryHttpRequest(
                        connectorId = command.query.connectorId.toString(),
                        query = objectMapper.readTree(query.query.toString()),
                    ).toPayload().query)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
        }
        val remaining = java.time.Duration.between(Instant.now(), deadline).toMillis()
        if (remaining <= 0) throw java.util.concurrent.TimeoutException("Planning deadline exceeded")
        val rendered = try {
            val semanticRequests = requests.filterNotNull()
            if (semanticRequests.isEmpty()) emptyList<SemanticRenderResult>().iterator()
            else withTimeout(remaining) { semanticQueryUseCase.renderBatch(semanticRequests, deadline) }.iterator()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (generateSequence<Throwable>(failure) { it.cause }.any { it is java.sql.SQLException && it.sqlState == "57014" }) {
                throw java.util.concurrent.TimeoutException("Planning deadline exceeded")
            }
            throw failure
        } finally {
            logger.atInfo()
                .addKeyValue("event", "dashboard_planning_batch_timing")
                .addKeyValue("queries", commands.size)
                .addKeyValue("total_ms", (System.nanoTime() - started) / 1_000_000)
                .log("dashboard_planning_batch_timing")
        }
        return commands.mapIndexed { index, command ->
            val query = command.query
            if (query is DashboardNamedQuery.Sql) {
                sqlConnectorTypes[query.connectorId]?.let { resolveSql(query, command.resolvedQuery, it) }
                    ?: DashboardQueryResolution.Rejected("CONNECTOR_NOT_FOUND", null)
            } else if (requests[index] == null) DashboardQueryResolution.Rejected("INVALID_SEMANTIC_QUERY", null)
            else when (val result = rendered.next()) {
                is SemanticRenderResult.Rendered -> if (result.render.workspaceId != command.workspaceId) {
                    DashboardQueryResolution.Rejected("DASHBOARD_QUERY_UNAUTHORIZED", null)
                } else DashboardQueryResolution.Resolved(
                    command.resolvedQuery, result.render.sql, result.render.outputColumns.map { it.name.value },
                    null, null, null, emptyList(),
                    outputColumnDisplays = result.render.outputColumns.map { cc.monomer.semantic.api.SemanticOutputColumnDisplay(it.name, it.display) },
                    reportingTimeZone = result.render.reportingTimeZone,
                )
                is SemanticRenderResult.Invalid -> DashboardQueryResolution.Rejected("INVALID_SEMANTIC_QUERY", result.reasons.joinToString(",") { it.code.name })
                is SemanticRenderResult.Unsupported -> DashboardQueryResolution.Rejected("UNSUPPORTED_SEMANTIC_QUERY", result.capabilities.joinToString(",") { it.name })
                is SemanticRenderResult.MetricNotFound -> DashboardQueryResolution.Rejected("SEMANTIC_QUERY_NOT_FOUND", null)
                SemanticRenderResult.NotFoundOrOutsideWorkspace -> DashboardQueryResolution.Rejected("SEMANTIC_QUERY_NOT_FOUND", null)
            }
        }
    }

    override suspend fun resolve(
        command: DashboardQueryPlanningPort.ResolveQueryCommand,
    ): DashboardQueryResolution {
        val started = System.nanoTime()
        var snapshotNanos = 0L
        var renderNanos = 0L
        var outcome = "success"
        suspend fun timedSnapshot(): DashboardQueryResolution {
            val snapshotStarted = System.nanoTime()
            try {
                return resolveInSnapshot(command) { renderNanos += it }
            } finally {
                snapshotNanos += System.nanoTime() - snapshotStarted
            }
        }
        try {
            val deadline = command.deadline
            if (deadline == null) return consistentSnapshotTransactionRunner.run { timedSnapshot() }
            val remaining = java.time.Duration.between(Instant.now(), deadline).toMillis()
            if (remaining <= 0) throw java.util.concurrent.TimeoutException("Planning deadline exceeded")
            return try {
                withTimeout(remaining) {
                    val bridgeContext = kotlin.coroutines.coroutineContext.minusKey(kotlin.coroutines.ContinuationInterceptor)
                    runInterruptible(blockingDispatcher) {
                        requireNotNull(planningTransactionOperations.execute {
                            runBlocking(bridgeContext) { timedSnapshot() }
                        })
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generateSequence<Throwable>(failure) { it.cause }.any { it is java.sql.SQLException && it.sqlState == "57014" }) {
                    throw java.util.concurrent.TimeoutException("Planning deadline exceeded")
                }
                throw failure
            }
        } catch (cancelled: CancellationException) {
            outcome = if (cancelled is kotlinx.coroutines.TimeoutCancellationException) "timeout" else "cancelled"
            throw cancelled
        } catch (failure: Exception) {
            outcome = if (failure is java.util.concurrent.TimeoutException) "timeout" else "exception"
            throw failure
        } finally {
            val totalNanos = System.nanoTime() - started
            logger.atInfo()
                .addKeyValue("event", "dashboard_planning_timing")
                .addKeyValue("outcome", outcome)
                .addKeyValue("deadline_bounded", command.deadline != null)
                .addKeyValue("total_ms", totalNanos / 1_000_000)
                .addKeyValue("transaction_outside_snapshot_ms", (totalNanos - snapshotNanos) / 1_000_000)
                .addKeyValue("snapshot_wall_ms", snapshotNanos / 1_000_000)
                .addKeyValue("render_wall_ms", renderNanos / 1_000_000)
                .log("dashboard_planning_timing")
        }
    }

    private suspend fun resolveInSnapshot(
        command: DashboardQueryPlanningPort.ResolveQueryCommand,
        recordRenderNanos: (Long) -> Unit,
    ): DashboardQueryResolution {
        if (command.credential == null || command.deadline != null) {
            val connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource)
            try {
                val remaining = command.deadline?.let { java.time.Duration.between(Instant.now(), it).toMillis() } ?: 5_000L
                if (remaining <= 0) throw java.util.concurrent.TimeoutException("Planning deadline exceeded")
                connection.prepareStatement("SELECT set_config('statement_timeout', ?, true), set_config('lock_timeout', ?, true)").use {
                    it.setString(1, "${remaining}ms")
                    it.setString(2, "${minOf(remaining, 2_000L)}ms")
                    it.execute()
                }
            } finally {
                org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(connection, dataSource)
            }
        }

        val planned = when (val query = command.query) {
            is DashboardNamedQuery.Sql -> {
                require(command.resolvedQuery is DashboardResolvedQuery.Sql)
                val connector = connectorRead.findByWorkspaceAndId(command.workspaceId, query.connectorId)
                    ?: return DashboardQueryResolution.Rejected("CONNECTOR_NOT_FOUND", null)
                when (val result = resolveSql(query, command.resolvedQuery, connector.connectorType)) {
                    is DashboardQueryResolution.Resolved -> result
                    is DashboardQueryResolution.Rejected -> return result
                }
            }
            is DashboardNamedQuery.Semantic -> {
                val resolved = command.resolvedQuery as DashboardResolvedQuery.Semantic
                val payload = try {
                    SemanticQueryHttpRequest(
                        connectorId = query.connectorId.toString(),
                        query = objectMapper.readTree(resolved.query.toString()),
                    ).toPayload()
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { return DashboardQueryResolution.Rejected("INVALID_SEMANTIC_QUERY", null) }
                val renderStarted = System.nanoTime()
                val rendered = try {
                    semanticQueryUseCase.render(SemanticRenderRequest(command.workspaceId, query.connectorId, payload.query))
                } finally { recordRenderNanos(System.nanoTime() - renderStarted) }
                val render = when (rendered) {
                    is SemanticRenderResult.Rendered -> rendered.render
                    is SemanticRenderResult.Invalid -> return DashboardQueryResolution.Rejected("INVALID_SEMANTIC_QUERY", rendered.reasons.joinToString(",") { it.code.name })
                    is SemanticRenderResult.Unsupported -> return DashboardQueryResolution.Rejected("UNSUPPORTED_SEMANTIC_QUERY", rendered.capabilities.joinToString(",") { it.name })
                    is SemanticRenderResult.MetricNotFound -> return DashboardQueryResolution.Rejected("SEMANTIC_QUERY_NOT_FOUND", null)
                    SemanticRenderResult.NotFoundOrOutsideWorkspace -> return DashboardQueryResolution.Rejected("SEMANTIC_QUERY_NOT_FOUND", null)
                }
                if (render.workspaceId != command.workspaceId) return DashboardQueryResolution.Rejected("DASHBOARD_QUERY_UNAUTHORIZED", null)
                DashboardQueryResolution.Resolved(resolved, render.sql, render.outputColumns.map { it.name.value }, null, null, null, emptyList(), outputColumnDisplays = render.outputColumns.map { cc.monomer.semantic.api.SemanticOutputColumnDisplay(it.name, it.display) }, reportingTimeZone = render.reportingTimeZone)
            }
        }

        val authorization = command.readerEvidence ?: command.credential?.let { credential ->
            when (val admission = controlAdmissionFacade.authorizeWorkspaceRole(
                cc.monomer.control.WorkspaceRoleAdmissionRequest(command.workspaceId, credential, command.expectedOAuthResource, cc.monomer.identity.api.WorkspaceRole.EXPLORER),
            )) {
                is ControlAdmissionDecision.Admitted -> cc.monomer.identity.api.IdentityCredentialEvidence(
                    admission.context.actor, admission.context.credentialKind, admission.context.credentialSurface,
                    admission.context.agentClientId,
                )
                is ControlAdmissionDecision.Denied -> return DashboardQueryResolution.Rejected("DASHBOARD_QUERY_UNAUTHORIZED", null)
            }
        }


        return planned.copy(
            actorId = authorization?.actor?.id?.toString(),
            credentialSurface = authorization?.credentialSurface,
            agentClientId = authorization?.agentClientId,
        )
    }

    private fun resolveSql(
        query: DashboardNamedQuery.Sql,
        resolvedQuery: DashboardResolvedQuery,
        connectorType: ConnectorProductType,
    ): DashboardQueryResolution {
        require(resolvedQuery is DashboardResolvedQuery.Sql)
        val named = connectorType == ConnectorProductType.DATABRICKS
        if (named && query.template.namedSqlUtf8ByteLength() > MAXIMUM_RAW_SQL_UTF8_BYTES) {
            return DashboardQueryResolution.Rejected("RAW_SQL_VALIDATION_REJECTED", "Databricks Raw SQL exceeds the maximum UTF-8 byte length")
        }
        return DashboardQueryResolution.Resolved(
            resolvedQuery, if (named) query.template.namedSql() else query.template.executableSql, query.columns,
            null, null, null, if (named) query.template.namedParameterNames() else List(resolvedQuery.bindings.size) { "" },
            outputColumnDisplays = null,
            reportingTimeZone = null,
        )
    }

    private companion object {
        const val MAXIMUM_RAW_SQL_UTF8_BYTES = 1_048_576
        val logger = LoggerFactory.getLogger(DashboardQueryPlanningAdapter::class.java)
    }
}

internal fun DashboardBindValue.toExecutionParameter(name: String): QueryParameterValue = when (this) {
    is DashboardBindValue.Text -> QueryParameterValue.Text(value, name = name)
    is DashboardBindValue.Number -> QueryParameterValue.Decimal(value, name = name)
    is DashboardBindValue.Boolean -> QueryParameterValue.BooleanValue(value, name = name)
    is DashboardBindValue.Date -> QueryParameterValue.Date(value, name = name)
    is DashboardBindValue.Timestamp -> QueryParameterValue.Timestamp(value, name = name)
    is DashboardBindValue.Null -> QueryParameterValue.Null(when (type) {
        DashboardValueType.TEXT -> NullQueryParameterType.TEXT
        DashboardValueType.NUMBER -> NullQueryParameterType.DECIMAL
        DashboardValueType.BOOLEAN -> NullQueryParameterType.BOOLEAN
        DashboardValueType.DATE -> NullQueryParameterType.DATE
        DashboardValueType.TIMESTAMP -> NullQueryParameterType.TIMESTAMP
        else -> error("SQL binding type must be scalar")
    }, name = name)
}

class DashboardExecutionAdmissionAdapter(
    private val connectorAccess: ConnectorExecutionAccessInOwnerTransactionPort,
    private val connectorProjection: ConnectorRuntimeBindingProjectionPort,
    private val rawSqlRunSpecFactory: RawSqlRunSpecFactory,
    private val admissionPort: ExecutionRunAdmissionPort,
    private val clock: Clock,
    runtimeProperties: ControlRuntimeProperties,
) : DashboardExecutionAdmissionPort {
    private val limits = runtimeProperties.executionRuntime.limits

    override fun lockWorkspace(workspaceId: cc.monomer.identity.api.WorkspaceId) = admissionPort.lockWorkspaceInOwnerTransaction(workspaceId)

    override fun hasUnreleasedOccupancy(workspaceId: cc.monomer.identity.api.WorkspaceId, queryRunIds: Set<QueryRunId>): Boolean =
        admissionPort.hasUnreleasedOccupancyInOwnerTransaction(workspaceId, queryRunIds.map { cc.monomer.execution.api.ExecutionRunId(it.value) }.toSet())

    override fun admit(command: DashboardExecutionAdmissionPort.AdmissionCommand): DashboardExecutionAdmissionPort.AdmissionOutcome {
        val binding = when (
            val access = connectorAccess.readCurrentForExecutionInOwnerTransaction(
                command.workspaceId,
                command.connectorId,
            )
        ) {
            is ConnectorExecutionAccessResult.Granted -> access.binding
            ConnectorExecutionAccessResult.NotFoundOrOutsideWorkspace ->
                return DashboardExecutionAdmissionPort.AdmissionOutcome.Rejected("CONNECTOR_NOT_FOUND")
        }
        val named = ConnectorProductType.fromStorageValue(binding.connectorType) == ConnectorProductType.DATABRICKS
        check(if (named) {
            command.parameterNames.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) } &&
                command.parameterNames.map { it.lowercase(java.util.Locale.ROOT) }.toSet().size == command.parameterNames.size
        } else command.parameterNames.all(String::isEmpty)) { "Dashboard parameter names do not match the connector type" }
        val runtimeBinding = connectorProjection.project(binding)
        val reservedAt = clock.instant()
        val dispatchDeadline = reservedAt.plus(limits.dispatchReservationDuration)
        val deadline = dispatchDeadline.plus(limits.executionDeadlineDuration)
        val request = RawSqlExecutionRequest(
            schemaVersion = RawSqlExecutionRequest.SCHEMA_VERSION,
            canonicalRequestDigest = Sha256Digest("0".repeat(64)),
            sql = command.sql,
            parameters = command.bindings.mapIndexed { index, value -> value.toExecutionParameter(command.parameterNames[index]) },
            maximumResultRows = command.rowLimit.toULong(),
            maximumArtifactBytes = MAXIMUM_DASHBOARD_ARTIFACT_BYTES,
            maximumRecordBatchRows = DEFAULT_RECORD_BATCH_ROWS,
            maximumRecordBatchBytes = limits.maximumRunFrameBytes,
            maximumCellBytes = MAXIMUM_DASHBOARD_CELL_BYTES,
            maximumMultipartPartBytes = ResultSinkBinding.MAXIMUM_MULTIPART_PART_BYTES,
            validateRawSqlBeforeRun = command.validateRawSqlBeforeRun,
            expectedColumnNames = command.expectedColumnNames,
        ).let { unbound ->
            unbound.copy(
                canonicalRequestDigest = RawSqlExecutionRequestCanonicalizer.canonicalDigest(
                    unbound,
                    command.resultObjectKey,
                ),
            )
        }
        val spec = rawSqlRunSpecFactory.create(
            RawSqlRunSpecCommand(
                runId = cc.monomer.execution.api.ExecutionRunId(command.queryRunId.value),
                workspaceId = command.workspaceId,
                objectKey = command.resultObjectKey,
                connector = runtimeBinding,
                request = request,
                deadline = deadline,
            ),
        )
        val expectedOwner = ExecutionOwnerReference(
            type = ExecutionOwnerType.DASHBOARD,
            id = ExecutionOwnerId(command.queryRunId.value),
        )
        val expectedConnectorId = ConnectorReference(runtimeBinding.connectorId.value)
        val admission = admissionPort.reserveInOwnerTransaction(
            ExecutionRunAdmissionPort.ReserveCommand(
                runId = cc.monomer.execution.api.ExecutionRunId(command.queryRunId.value),
                owner = expectedOwner,
                workspaceId = command.workspaceId,
                idempotencyKey = command.idempotencyKey,
                requestFingerprint = spec.canonicalRequestDigest,
                connectorId = expectedConnectorId,
                connectorType = runtimeBinding.connectorType,
                connectorProperties = runtimeBinding.properties,
                requestKind = cc.monomer.execution.api.ExecutableRequestKind.QUERY,
                runtimeBindings = spec.runtimeBindings,
                assignmentTemplate = spec.assignmentTemplate,
                dispatchIntentExpiresAt = dispatchDeadline,
                deadline = deadline,
                reservedAt = reservedAt,
            ),
        )
        return when (admission) {
            is ExecutionRunAdmissionPort.ReserveOutcome.Reserved -> DashboardExecutionAdmissionPort.AdmissionOutcome.Admitted
            is ExecutionRunAdmissionPort.ReserveOutcome.Existing ->
                if (admission.snapshot.matchesDashboardAdmission(
                        command = command,
                        owner = expectedOwner,
                        connectorId = expectedConnectorId,
                        connectorType = runtimeBinding.connectorType,
                        connectorProperties = runtimeBinding.properties,
                        requestFingerprint = spec.canonicalRequestDigest,
                        runtimeBindings = spec.runtimeBindings,
                        dispatchDeadline = dispatchDeadline,
                        deadline = deadline,
                    )
                ) {
                    DashboardExecutionAdmissionPort.AdmissionOutcome.Existing
                } else {
                    DashboardExecutionAdmissionPort.AdmissionOutcome.Rejected("IDEMPOTENCY_CONFLICT")
                }
            ExecutionRunAdmissionPort.ReserveOutcome.Backpressured -> DashboardExecutionAdmissionPort.AdmissionOutcome.Backpressured
            ExecutionRunAdmissionPort.ReserveOutcome.IdempotencyConflict ->
                DashboardExecutionAdmissionPort.AdmissionOutcome.Rejected("IDEMPOTENCY_CONFLICT")
            ExecutionRunAdmissionPort.ReserveOutcome.InvalidDispatchTemplate ->
                DashboardExecutionAdmissionPort.AdmissionOutcome.Rejected("INVALID_DISPATCH_TEMPLATE")
        }
    }

    private companion object {
        const val DEFAULT_RECORD_BATCH_ROWS = 1_024
        const val MAXIMUM_DASHBOARD_ARTIFACT_BYTES: ULong = 67_108_864uL
        const val MAXIMUM_DASHBOARD_CELL_BYTES = 64 * 1_024
    }
}

private fun ExecutionRunSnapshot.matchesDashboardAdmission(
    command: DashboardExecutionAdmissionPort.AdmissionCommand,
    owner: ExecutionOwnerReference,
    connectorId: ConnectorReference,
    connectorType: cc.monomer.execution.api.ConnectorType,
    connectorProperties: Map<String, cc.monomer.execution.api.ConnectorRuntimePropertyValue>,
    requestFingerprint: Sha256Digest,
    runtimeBindings: cc.monomer.execution.api.RunRuntimeBindings,
    dispatchDeadline: Instant,
    deadline: Instant,
): Boolean =
    id == cc.monomer.execution.api.ExecutionRunId(command.queryRunId.value) &&
        this.owner == owner &&
        workspaceId == command.workspaceId &&
        this.connectorId == connectorId &&
        this.connectorType == connectorType &&
        this.connectorProperties == connectorProperties &&
        requestKind == cc.monomer.execution.api.ExecutableRequestKind.QUERY &&
        canonicalRequestDigest == requestFingerprint &&
        this.runtimeBindings == runtimeBindings &&
        dispatchDeadlineAt == dispatchDeadline &&
        this.deadline == deadline

class DashboardExecutionCancellationAdapter(
    private val cancellationPort: OwnerExecutionRunCancellationPort,
    private val clock: Clock,
) : DashboardExecutionCancellationPort {
    override fun requestCancel(command: DashboardExecutionCancellationPort.CancelCommand): DashboardExecutionCancellationPort.CancellationOutcome =
        when (
            cancellationPort.requestCancellationInOwnerTransaction(
                OwnerExecutionRunCancellationPort.RequestCancellationCommand(
                    runId = cc.monomer.execution.api.ExecutionRunId(command.queryRunId.value),
                    owner = ExecutionOwnerReference(
                        ExecutionOwnerType.DASHBOARD,
                        ExecutionOwnerId(command.queryRunId.value),
                    ),
                    workspaceId = command.workspaceId,
                    reasonCode = command.reason,
                    requestedAt = clock.instant(),
                ),
            )
        ) {
            is OwnerExecutionRunCancellationPort.CancellationOutcome.Applied,
            is OwnerExecutionRunCancellationPort.CancellationOutcome.Duplicate,
            -> DashboardExecutionCancellationPort.CancellationOutcome.Requested
            is OwnerExecutionRunCancellationPort.CancellationOutcome.AlreadyTerminal ->
                DashboardExecutionCancellationPort.CancellationOutcome.AlreadyTerminal
            OwnerExecutionRunCancellationPort.CancellationOutcome.Conflict ->
                DashboardExecutionCancellationPort.CancellationOutcome.NotFound
        }
}

class DashboardExecutionSettlementAdapter(
    private val dashboardSettlement: DashboardQueryRunSettlementUseCase,
    private val queryRunRepository: QueryRunRepository,
    private val eventHub: DashboardSessionEventHub,
    private val draftEventHub: DashboardDraftEventHub,
) : ExecutionRunOwnerSettlementPort {
    override val ownerType: ExecutionOwnerType = ExecutionOwnerType.DASHBOARD

    override fun settleInOwnerTransaction(command: ExecutionRunOwnerSettlementPort.SettleCommand): ExecutionRunOwnerSettlementPort.SettlementOutcome {
        val outcome = command.run.physicalOutcome ?: return ExecutionRunOwnerSettlementPort.SettlementOutcome.Conflict
        val state = when (outcome.kind) {
            PhysicalOutcomeKind.SUCCEEDED -> DashboardQueryRunSettlementUseCase.State.SUCCEEDED
            PhysicalOutcomeKind.FAILED -> DashboardQueryRunSettlementUseCase.State.FAILED
            PhysicalOutcomeKind.CANCELLED -> DashboardQueryRunSettlementUseCase.State.CANCELLED
        }
        return when (
            dashboardSettlement.settleInOwnerTransaction(
                DashboardQueryRunSettlementUseCase.Command(
                    workspaceId = command.run.workspaceId,
                    queryRunId = QueryRunId(command.run.id.value),
                    state = state,
                    result = outcome.arrowResultManifest,
                    failureCode = outcome.failureCode,
                    failureMessage = outcome.safeMessage.takeIf { outcome.kind == PhysicalOutcomeKind.FAILED },
                    settledAt = command.settledAt,
                ),
            )
        ) {
            DashboardQueryRunSettlementUseCase.Outcome.Applied -> {
                queryRunRepository.find(QueryRunId(command.run.id.value))?.let { run ->
                    val viewerSessionId = run.viewerSessionId
                    if (viewerSessionId != null) {
                        emitDashboardSessionInvalidationAfterCommit(
                            eventHub = eventHub,
                            sessionId = viewerSessionId,
                            reason = "QUERY_RUN_SETTLED",
                        )
                    } else {
                        emitDashboardDraftInvalidationAfterCommit(
                            eventHub = draftEventHub,
                            dashboardId = run.dashboardId,
                            reason = "QUERY_RUN_SETTLED",
                        )
                    }
                }
                ExecutionRunOwnerSettlementPort.SettlementOutcome.Completed
            }
            DashboardQueryRunSettlementUseCase.Outcome.Duplicate -> ExecutionRunOwnerSettlementPort.SettlementOutcome.Duplicate
            DashboardQueryRunSettlementUseCase.Outcome.Conflict,
            DashboardQueryRunSettlementUseCase.Outcome.NotFound,
            -> ExecutionRunOwnerSettlementPort.SettlementOutcome.Conflict
        }
    }
}

class DashboardResultReader(
    private val artifactStore: cc.monomer.execution.api.ExecutionArtifactStore,
) : DashboardResultReadPort {
    override suspend fun read(reference: ArrowResultManifest): DashboardResultReadPort.DashboardResultPayload {
        val started = System.nanoTime()
        var headNanos = 0L
        var readNanos = 0L
        var decodeNanos = 0L
        var outcome = "success"
        try {
            return withContext(Dispatchers.IO) {
                try {
                    val headStarted = System.nanoTime()
                    val head = try { artifactStore.head(reference.objectKey) }
                        finally { headNanos = System.nanoTime() - headStarted }
                        ?: error("dashboard result artifact is unavailable")
                    require(head.objectKey == reference.objectKey)
                    require(head.byteSize == reference.byteSize)
                    require(normalizeEtag(head.etag) == normalizeEtag(reference.objectEtag))
                    require(head.byteSize <= MAXIMUM_RESULT_BYTES.toULong()) { "dashboard result exceeds byte budget" }
                    val readStarted = System.nanoTime()
                    val bytes = try { artifactStore.read(reference.objectKey) }
                        finally { readNanos = System.nanoTime() - readStarted }
                    val decodeStarted = System.nanoTime()
                    try {
                        require(bytes.size.toULong() == reference.byteSize) { "dashboard result artifact size changed" }
                        RootAllocator(MAXIMUM_RESULT_BYTES).use { allocator ->
                            ByteArrayReadableSeekableByteChannel(bytes).use { channel ->
                                ArrowFileReader(SeekableReadChannel(channel), allocator).use { reader ->
                                    val schema = reader.vectorSchemaRoot.schema
                                    val columns = schema.fields.map { field ->
                                        DashboardResultReadPort.Column(
                                            name = field.name,
                                            logicalType = field.toDashboardLogicalType().name,
                                            nullable = field.fieldType.isNullable,
                                            display = null,
                                        )
                                    }
                                    require(columns.size <= MAXIMUM_COLUMNS)
                                    require(columns.map { it.name }.toSet().size == columns.size) {
                                        "dashboard result returned duplicate column names"
                                    }
                                    val rows = mutableListOf<List<JsonElement>>()
                                    reader.recordBlocks.forEach { block ->
                                        reader.loadRecordBatch(block)
                                        val root = reader.vectorSchemaRoot
                                        require(root.schema == schema) { "dashboard result schema changed between batches" }
                                        repeat(root.rowCount) { rowIndex ->
                                            require(rows.size < MAXIMUM_RESULT_ROWS) { "dashboard result exceeds row budget" }
                                            rows += root.fieldVectors.mapIndexed { index, vector ->
                                                vector.toDashboardJson(
                                                    rowIndex,
                                                    ResultLogicalType.valueOf(columns[index].logicalType),
                                                )
                                            }
                                        }
                                    }
                                    require(columns.size == reference.columnCount) {
                                        "dashboard result column count differs from manifest"
                                    }
                                    require(rows.size.toULong() == reference.rowCount) {
                                        "dashboard result row count differs from manifest"
                                    }
                                    DashboardResultReadPort.DashboardResultPayload(columns, rows)
                                }
                            }
                        }
                    } finally {
                        bytes.fill(0)
                        decodeNanos = System.nanoTime() - decodeStarted
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    throw DashboardResultUnavailableException(failure)
                }
            }
        } catch (cancelled: CancellationException) {
            outcome = "cancelled"
            throw cancelled
        } catch (failure: Exception) {
            outcome = "exception"
            throw failure
        } finally {
            val totalNanos = System.nanoTime() - started
            if (totalNanos >= 250_000_000L) {
                logger.atInfo()
                    .addKeyValue("event", "dashboard_result_timing")
                    .addKeyValue("outcome", outcome)
                    .addKeyValue("total_ms", totalNanos / 1_000_000)
                    .addKeyValue("head_wall_ms", headNanos / 1_000_000)
                    .addKeyValue("read_wall_ms", readNanos / 1_000_000)
                    .addKeyValue("decode_wall_ms", decodeNanos / 1_000_000)
                    .log("dashboard_result_timing")
            }
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(DashboardResultReader::class.java)
        const val MAXIMUM_RESULT_BYTES = 64L * 1_024 * 1_024
        const val MAXIMUM_RESULT_ROWS = 100_000
        const val MAXIMUM_COLUMNS = 1_000
    }
}

class DashboardResultUnavailableException(cause: Throwable) :
    RuntimeException("dashboard result is unavailable", cause)

internal fun DashboardResultReadPort.DashboardResultPayload.withQueryColumns(
    queryType: String,
    outputColumnNames: List<String>,
    outputColumnDisplays: List<cc.monomer.semantic.api.SemanticOutputColumnDisplay>?,
): DashboardResultReadPort.DashboardResultPayload = when (queryType) {
    "SEMANTIC" -> withSemanticColumns(outputColumnNames, outputColumnDisplays)
    "SQL" -> {
        if (columns.map { it.name } != outputColumnNames) {
            throw DashboardResultUnavailableException(IllegalArgumentException("dashboard artifact columns do not match the recorded SQL columns"))
        }
        copy(columns = columns.map { it.copy(display = null) })
    }
    else -> throw DashboardResultUnavailableException(IllegalArgumentException("dashboard query type is unsupported"))
}

internal fun DashboardResultReadPort.DashboardResultPayload.withSemanticColumns(
    outputColumnNames: List<String>,
    outputColumnDisplays: List<cc.monomer.semantic.api.SemanticOutputColumnDisplay>?,
): DashboardResultReadPort.DashboardResultPayload {
    try {
        val projection = cc.monomer.semantic.api.SemanticResultColumns.fromKeys(
            outputColumnNames.map { cc.monomer.semantic.api.SemanticResultColumnKey(it) },
        )
        require(columns.map { it.name } == projection.map { it.sqlName }) {
            "dashboard artifact columns do not match the Semantic projection"
        }
        val displays = cc.monomer.semantic.api.storedMetricDisplays(projection.map { it.key.value }, outputColumnDisplays)
        return copy(columns = columns.mapIndexed { index, column -> column.copy(name = projection[index].key.value, display = displays[index]) })
    } catch (failure: IllegalArgumentException) {
        throw DashboardResultUnavailableException(failure)
    }
}

private fun org.apache.arrow.vector.types.pojo.Field.toDashboardLogicalType(): ResultLogicalType {
    val metadata = getMetadata()
    val logicalType = metadata[DECIMAL_LOGICAL_TYPE_KEY]
    val physicalEncoding = metadata[DECIMAL_PHYSICAL_ENCODING_KEY]
    if (logicalType == DECIMAL_LOGICAL_TYPE_VALUE || physicalEncoding == DECIMAL_PHYSICAL_ENCODING_VALUE) {
        require(type is org.apache.arrow.vector.types.pojo.ArrowType.Utf8)
        require(logicalType == DECIMAL_LOGICAL_TYPE_VALUE)
        require(physicalEncoding == DECIMAL_PHYSICAL_ENCODING_VALUE)
        return ResultLogicalType.DECIMAL
    }
    return when (val type = type) {
        is org.apache.arrow.vector.types.pojo.ArrowType.Bool -> ResultLogicalType.BOOLEAN
        is org.apache.arrow.vector.types.pojo.ArrowType.Int -> ResultLogicalType.INTEGER
        is org.apache.arrow.vector.types.pojo.ArrowType.FloatingPoint -> ResultLogicalType.FLOAT
        is org.apache.arrow.vector.types.pojo.ArrowType.Decimal -> ResultLogicalType.DECIMAL
        is org.apache.arrow.vector.types.pojo.ArrowType.Date -> ResultLogicalType.DATE
        is org.apache.arrow.vector.types.pojo.ArrowType.Timestamp ->
            if (type.timezone == null) ResultLogicalType.DATETIME else ResultLogicalType.TIMESTAMP
        is org.apache.arrow.vector.types.pojo.ArrowType.Time -> ResultLogicalType.TIME
        is org.apache.arrow.vector.types.pojo.ArrowType.Binary,
        is org.apache.arrow.vector.types.pojo.ArrowType.FixedSizeBinary,
        -> ResultLogicalType.BINARY
        is org.apache.arrow.vector.types.pojo.ArrowType.Utf8,
        is org.apache.arrow.vector.types.pojo.ArrowType.LargeUtf8,
        is org.apache.arrow.vector.types.pojo.ArrowType.Utf8View,
        -> ResultLogicalType.TEXT
        else -> ResultLogicalType.JSON
    }
}

private fun FieldVector.toDashboardJson(rowIndex: Int, logicalType: ResultLogicalType): JsonElement {
    if (isNull(rowIndex)) return JsonNull
    val value = when (logicalType) {
        ResultLogicalType.BOOLEAN -> JsonPrimitive(getObject(rowIndex) as Boolean)
        ResultLogicalType.DATE -> JsonPrimitive(
            if (this is DateDayVector) LocalDate.ofEpochDay(get(rowIndex).toLong()).toString() else getObject(rowIndex).toString(),
        )
        ResultLogicalType.DATETIME -> JsonPrimitive(
            (getObject(rowIndex) as java.time.LocalDateTime).also {
                require(it.nano % 1_000 == 0) { "DATETIME exceeds microsecond precision" }
            }.format(CANONICAL_MICROSECOND_LOCAL_DATE_TIME),
        )
        ResultLogicalType.TIMESTAMP -> {
            val instant = if (this is TimeStampMicroTZVector) {
                val micros = get(rowIndex)
                Instant.ofEpochSecond(
                    Math.floorDiv(micros, 1_000_000L),
                    Math.floorMod(micros, 1_000_000L) * 1_000L,
                )
            } else getObject(rowIndex) as? Instant ?: Instant.parse(getObject(rowIndex).toString())
            JsonPrimitive(CANONICAL_MICROSECOND_INSTANT.format(instant))
        }
        ResultLogicalType.INTEGER, ResultLogicalType.DECIMAL, ResultLogicalType.FLOAT -> JsonPrimitive(
            when (this) {
                is DecimalVector, is Decimal256Vector -> (getObject(rowIndex) as java.math.BigDecimal).toPlainString()
                else -> getObject(rowIndex).toString().let { text ->
                    if ('E' in text) java.math.BigDecimal(text).stripTrailingZeros().toPlainString() else text
                }
            },
        )
        ResultLogicalType.BINARY -> JsonPrimitive(
            Base64.getEncoder().encodeToString(
                when (this) {
                    is VarBinaryVector -> getObject(rowIndex) as ByteArray
                    else -> getObject(rowIndex).toString().toByteArray(Charsets.UTF_8)
                },
            ),
        )
        ResultLogicalType.TIME -> JsonPrimitive(
            if (this is org.apache.arrow.vector.TimeMicroVector) {
                java.time.LocalTime.ofNanoOfDay(get(rowIndex) * 1_000)
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS"))
            } else {
                getObject(rowIndex).toString()
            },
        )
        ResultLogicalType.TEXT, ResultLogicalType.JSON -> JsonPrimitive(getObject(rowIndex).toString())
    }
    return value
}

private fun normalizeEtag(value: String): String = value.trim().trim('"')

private const val DECIMAL_LOGICAL_TYPE_KEY = "cc.monomer.logical_type"
private const val DECIMAL_LOGICAL_TYPE_VALUE = "DECIMAL"
private const val DECIMAL_PHYSICAL_ENCODING_KEY = "cc.monomer.physical_encoding"
private const val DECIMAL_PHYSICAL_ENCODING_VALUE = "UTF8_CANONICAL_DECIMAL"
private val CANONICAL_MICROSECOND_INSTANT: DateTimeFormatter =
    DateTimeFormatterBuilder().appendInstant(6).toFormatter()

private val CANONICAL_MICROSECOND_LOCAL_DATE_TIME = java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS")
