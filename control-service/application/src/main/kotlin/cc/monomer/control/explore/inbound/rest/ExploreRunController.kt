@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.explore.inbound.rest

import cc.monomer.control.human.auth.toHumanHttpException
import cc.monomer.control.agentapp.AgentAppResponse
import cc.monomer.control.agentapp.responses
import cc.monomer.connector.api.ConnectorId
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.auth.inbound.BearerAuthorization
import cc.monomer.control.auth.inbound.BearerCredentials
import cc.monomer.control.explore.CancelExploreRunRequest
import cc.monomer.control.explore.ExploreRunAccessService
import cc.monomer.control.explore.ExploreRunCancelOutcome
import cc.monomer.control.explore.ExploreRunReadOutcome
import cc.monomer.control.explore.ExploreRunResultRead
import com.fasterxml.jackson.annotation.JsonUnwrapped
import cc.monomer.control.explore.ExploreRunListOutcome
import cc.monomer.control.explore.ExploreRunCsvReadOutcome
import cc.monomer.control.explore.ExploreRunArtifactReadOutcome
import cc.monomer.control.explore.ExploreSemanticLaunchOutcome
import cc.monomer.control.explore.ExploreSemanticLaunchService
import cc.monomer.control.explore.ExploreSqlLaunchOutcome
import cc.monomer.control.explore.ExploreSqlLaunchService
import cc.monomer.control.explore.LaunchSemanticExploreRunCommand
import cc.monomer.control.explore.LaunchSqlExploreRunCommand
import cc.monomer.control.explore.ReadExploreRunCommand
import cc.monomer.control.explore.ListExploreRunsRequest
import cc.monomer.control.explore.ReadExploreResultArtifactRequest
import cc.monomer.control.explore.exploreRequestDigest
import cc.monomer.control.semantic.inbound.rest.SemanticQueryHttpRequest
import cc.monomer.control.semantic.inbound.rest.semanticQueryRejected
import cc.monomer.control.semantic.inbound.rest.toHttpValue
import cc.monomer.execution.api.ResultPreviewCell
import cc.monomer.explore.api.ExploreResolvedResult
import cc.monomer.explore.api.ExploreRunId
import cc.monomer.explore.api.ExploreRunView
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.PrincipalDisplayNames
import cc.monomer.identity.api.WorkspaceId
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.uuid.Uuid
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.flow.map
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

@RestController
class ExploreRunController(
    private val sqlLaunchService: ExploreSqlLaunchService,
    private val semanticLaunchService: ExploreSemanticLaunchService,
    private val runAccessService: ExploreRunAccessService,
    private val objectMapper: ObjectMapper,
    private val agentApps: cc.monomer.identity.api.AgentAppDisplays,
    private val principalDisplayNames: PrincipalDisplayNames,
) {
    @PostMapping(
        "/v2/workspaces/{workspaceId}/explore/sql-runs",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun startSql(
        @PathVariable workspaceId: String,
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER) idempotencyKey: String,
        @RequestBody body: SqlExploreRunHttpRequest,
        exchange: ServerWebExchange,
    ): ResponseEntity<ExploreRunHttpView> {
        val workspace = parseWorkspaceId(workspaceId)
        val sql = body.sql?.trim().orEmpty()
        if (sql.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "sql is required")
        return when (
            val outcome = sqlLaunchService.start(
                LaunchSqlExploreRunCommand(
                    workspaceId = workspace,
                    credential = requireBearerCredential(exchange),
                    expectedOAuthResource = null,
                    idempotencyKey = requireIdempotencyKey(idempotencyKey),
                    connectorId = parseConnectorId(body.connectorId),
                    sql = sql,
                ),
            )
        ) {
            is ExploreSqlLaunchOutcome.Started -> accepted(outcome.run, outcome.sql)
            is ExploreSqlLaunchOutcome.Existing -> ResponseEntity.ok(httpView(outcome.run, outcome.sql))
            is ExploreSqlLaunchOutcome.AdmissionDenied -> throw admissionDenied(outcome.decision)
            ExploreSqlLaunchOutcome.ConnectorNotFound -> notFound("The connector was not found. It may have been deleted; choose another connector.")
            is ExploreSqlLaunchOutcome.ExecutionUnavailable -> throw ExploreExecutionUnavailable(outcome.failureCode, outcome.message, outcome.retryable)
            is ExploreSqlLaunchOutcome.SqlValidationFailed -> throw ExploreSqlRejected(outcome.code, outcome.message)
            ExploreSqlLaunchOutcome.IdempotencyConflict -> conflict("Explore SQL idempotency key conflicts.")
            ExploreSqlLaunchOutcome.Conflict -> conflict("Explore SQL run conflicts with current authority.")
        }
    }

    @PostMapping(
        "/v2/workspaces/{workspaceId}/explore/semantic-runs",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun startSemantic(
        @PathVariable workspaceId: String,
        @RequestHeader(name = IDEMPOTENCY_KEY_HEADER) idempotencyKey: String,
        @RequestBody body: SemanticQueryHttpRequest,
        exchange: ServerWebExchange,
    ): ResponseEntity<ExploreRunHttpView> {
        val workspace = parseWorkspaceId(workspaceId)
        val payload = body.toPayload()
        return when (
            val outcome = semanticLaunchService.start(
                LaunchSemanticExploreRunCommand(
                    workspaceId = workspace,
                    credential = requireBearerCredential(exchange),
                    expectedOAuthResource = null,
                    idempotencyKey = requireIdempotencyKey(idempotencyKey),
                    connectorId = payload.connectorId,
                    requestDigest = exploreRequestDigest(
                        "SEMANTIC",
                        objectMapper.writeValueAsString(payload.query.toHttpValue()),
                    ),
                    query = payload.query,
                ),
            )
        ) {
            is ExploreSemanticLaunchOutcome.Started -> accepted(outcome.run, outcome.sql)
            is ExploreSemanticLaunchOutcome.Existing -> ResponseEntity.ok(httpView(outcome.run))
            is ExploreSemanticLaunchOutcome.AdmissionDenied -> throw admissionDenied(outcome.decision)
            is ExploreSemanticLaunchOutcome.ValidationRejected -> throw ExploreSemanticValidationRejected(outcome.code, outcome.message, outcome.partitionFilters)
            is ExploreSemanticLaunchOutcome.ExecutionUnavailable -> throw ExploreExecutionUnavailable(outcome.failureCode, outcome.message, outcome.retryable)
            is ExploreSemanticLaunchOutcome.Invalid ->
                throw semanticQueryRejected(outcome.reasons, outcome.definitionIssues, "query")
            is ExploreSemanticLaunchOutcome.Unsupported ->
                throw cc.monomer.control.semantic.inbound.rest.semanticQueryUnsupported(outcome.capabilities)
            ExploreSemanticLaunchOutcome.ConnectorNotFound -> notFound("The connector was not found. It may have been deleted; choose another connector.")
            is ExploreSemanticLaunchOutcome.MetricNotFound -> throw cc.monomer.control.semantic.inbound.rest.semanticMetricNotFound(outcome.name)
            ExploreSemanticLaunchOutcome.NotFound -> notFound("Semantic query resources were not found.")
            ExploreSemanticLaunchOutcome.IdempotencyConflict -> conflict("Explore Semantic idempotency key conflicts.")
            ExploreSemanticLaunchOutcome.Conflict -> conflict("Explore Semantic run conflicts with current authority.")
        }
    }

    @GetMapping(
        "/v2/workspaces/{workspaceId}/explore/runs",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun listRecent(
        @PathVariable workspaceId: String,
        exchange: ServerWebExchange,
    ): ExploreRunsHttpView =
        when (
            val outcome = runAccessService.listRuns(
                ListExploreRunsRequest(
                    workspaceId = parseWorkspaceId(workspaceId),
                    credential = requireBearerCredential(exchange),
                    expectedOAuthResource = null,
                    after = null,
                    limit = 20,
                ),
            )
        ) {
            is ExploreRunListOutcome.Observed -> {
                val records = runAccessService.readRecordedHistory(outcome.page.runs)
                val apps = agentApps.responses(records.map { it.run.agentClientId })
                val names = principalDisplayNames.read(records.map { it.run.ownerPrincipalId.value }.toSet())
                ExploreRunsHttpView(records.map { record ->
                    record.run.toHttpView(record.sql, record.run.startedBy(names), record.run.agentClientId?.let(apps::get))
                })
            }
            is ExploreRunListOutcome.AdmissionDenied -> throw admissionDenied(outcome.decision)
        }

    @GetMapping(
        "/v2/workspaces/{workspaceId}/explore/runs/{runId}",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun read(
        @PathVariable workspaceId: String,
        @PathVariable runId: String,
        exchange: ServerWebExchange,
    ): ExploreRunDetailHttpView =
        when (
            val outcome = runAccessService.read(
                ReadExploreRunCommand(
                    workspaceId = parseWorkspaceId(workspaceId),
                    runId = parseRunId(runId),
                    credential = requireBearerCredential(exchange),
                    expectedOAuthResource = null,
                ),
            )
        ) {
            is ExploreRunReadOutcome.Observed -> ExploreRunDetailHttpView(
                run = httpView(outcome.run),
                resultState = outcome.result.state(),
                result = (outcome.result as? ExploreRunResultRead.Available)?.result?.toHttpView(),
            )
            is ExploreRunReadOutcome.AdmissionDenied -> throw admissionDenied(outcome.decision)
            ExploreRunReadOutcome.NotFound -> notFound("Explore run was not found.")
            ExploreRunReadOutcome.AccessDenied -> forbidden("Explore result is not authorized.")
            ExploreRunReadOutcome.TemporarilyUnavailable -> unavailable("Explore result is temporarily unavailable.")
        }

    @GetMapping("/v2/workspaces/{workspaceId}/explore/runs/{runId}/result")
    suspend fun downloadResult(
        @PathVariable workspaceId: String,
        @PathVariable runId: String,
        @RequestParam(required = false) format: String?,
        exchange: ServerWebExchange,
    ): ResponseEntity<reactor.core.publisher.Flux<DataBuffer>> =
        downloadResultResponse(workspaceId, runId, format, exchange, null)

    @GetMapping("/v2/workspaces/{workspaceId}/agent/explore/runs/{runId}/result")
    suspend fun downloadAgentResult(
        @PathVariable workspaceId: String,
        @PathVariable runId: String,
        @RequestParam(required = false) format: String?,
        exchange: ServerWebExchange,
    ): ResponseEntity<reactor.core.publisher.Flux<DataBuffer>> =
        downloadResultResponse(workspaceId, runId, format, exchange, ExpectedOAuthResource.AGENT_REST)

    private suspend fun downloadResultResponse(
        workspaceId: String,
        runId: String,
        requestedFormat: String?,
        exchange: ServerWebExchange,
        expectedOAuthResource: ExpectedOAuthResource?,
    ): ResponseEntity<reactor.core.publisher.Flux<DataBuffer>> {
        val format = requestedFormat ?: "arrow"
        if (format !in setOf("csv", "arrow")) badRequest("format must be csv or arrow")
        val command = ReadExploreResultArtifactRequest(
            workspaceId = parseWorkspaceId(workspaceId),
            runId = parseRunId(runId),
            credential = requireBearerCredential(exchange),
            expectedOAuthResource = expectedOAuthResource,
        )
        val content: kotlinx.coroutines.flow.Flow<java.nio.ByteBuffer>
        val mediaType: String
        val contentLength: Long?
        if (format == "csv") {
            when (val outcome = runAccessService.readCsv(command)) {
                is ExploreRunCsvReadOutcome.Visible -> {
                    content = outcome.csv.content
                    mediaType = outcome.csv.mediaType
                    contentLength = null
                }
                is ExploreRunCsvReadOutcome.AdmissionDenied -> throw admissionDenied(outcome.decision)
                ExploreRunCsvReadOutcome.NotFound -> notFound("Explore result was not found.")
                ExploreRunCsvReadOutcome.AccessDenied -> forbidden("Explore result is not authorized.")
                ExploreRunCsvReadOutcome.RetentionExpired -> gone("Explore result retention expired.")
                ExploreRunCsvReadOutcome.TemporarilyUnavailable -> unavailable("Explore result is temporarily unavailable.")
                ExploreRunCsvReadOutcome.ArtifactUnavailable -> conflict("Explore result artifact is not available.")
                ExploreRunCsvReadOutcome.UnsupportedSchema -> conflict("This result cannot be represented as CSV. Download the original Arrow file.")
            }
        } else {
            when (val outcome = runAccessService.readArtifact(command)) {
                is ExploreRunArtifactReadOutcome.Visible -> {
                    content = outcome.artifact.content
                    mediaType = outcome.artifact.mediaType
                    contentLength = outcome.artifact.contentLength
                }
                is ExploreRunArtifactReadOutcome.AdmissionDenied -> throw admissionDenied(outcome.decision)
                ExploreRunArtifactReadOutcome.NotFound -> notFound("Explore result was not found.")
                ExploreRunArtifactReadOutcome.AccessDenied -> forbidden("Explore result is not authorized.")
                ExploreRunArtifactReadOutcome.RetentionExpired -> gone("Explore result retention expired.")
                ExploreRunArtifactReadOutcome.TemporarilyUnavailable -> unavailable("Explore result is temporarily unavailable.")
                ExploreRunArtifactReadOutcome.ArtifactUnavailable -> conflict("Explore result artifact is not available.")
            }
        }
        val response = ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(mediaType))
            .headers { headers ->
                headers.contentDisposition = ContentDisposition.attachment()
                    .filename("explore-$runId.$format", StandardCharsets.UTF_8).build()
                headers.cacheControl = CacheControl.noStore().headerValue
                headers.set("X-Content-Type-Options", "nosniff")
                headers.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; sandbox")
                headers.set("Referrer-Policy", "no-referrer")
            }
        if (contentLength != null) response.contentLength(contentLength)
        return response.body(content.map { buffer ->
            DefaultDataBufferFactory.sharedInstance.wrap(buffer) as DataBuffer
        }.asFlux(org.springframework.core.PropagationContextElement()))
    }

    @PostMapping(
        "/v2/workspaces/{workspaceId}/explore/runs/{runId}:cancel",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    suspend fun cancel(
        @PathVariable workspaceId: String,
        @PathVariable runId: String,
        exchange: ServerWebExchange,
    ): ResponseEntity<ExploreRunHttpView> =
        when (
            val outcome = runAccessService.cancel(
                CancelExploreRunRequest(
                    workspaceId = parseWorkspaceId(workspaceId),
                    runId = parseRunId(runId),
                    credential = requireBearerCredential(exchange),
                    expectedOAuthResource = null,
                    reasonCode = "USER_REQUESTED",
                ),
            )
        ) {
            is ExploreRunCancelOutcome.Accepted -> accepted(outcome.run, recordedSql(outcome.run))
            is ExploreRunCancelOutcome.AlreadyTerminal -> ResponseEntity.ok(httpView(outcome.run))
            is ExploreRunCancelOutcome.AdmissionDenied -> throw admissionDenied(outcome.decision)
            ExploreRunCancelOutcome.AccessDenied -> forbidden("Explore run is not owned by the current actor.")
            ExploreRunCancelOutcome.NotFound -> notFound("Explore run was not found.")
            ExploreRunCancelOutcome.ConcurrentConflict -> conflict("Explore run conflicts with current state.")
        }

    private suspend fun accepted(run: ExploreRunView, sql: String): ResponseEntity<ExploreRunHttpView> =
        ResponseEntity.status(HttpStatus.ACCEPTED)
            .header(HttpHeaders.LOCATION, "/v2/workspaces/${run.workspaceId}/explore/runs/${run.id}")
            .header(HttpHeaders.RETRY_AFTER, "2")
            .body(httpView(run, sql))

    private suspend fun recordedSql(run: ExploreRunView): String =
        runAccessService.readRecordedSql(run) ?: unavailable("Explore run SQL history is temporarily unavailable.")

    private suspend fun httpView(run: ExploreRunView): ExploreRunHttpView = httpView(run, recordedSql(run))

    private suspend fun httpView(run: ExploreRunView, sql: String): ExploreRunHttpView =
        run.toHttpView(sql, run.startedBy(principalDisplayNames.read(setOf(run.ownerPrincipalId.value))), agentAppOf(run))

    private suspend fun agentAppOf(run: ExploreRunView): AgentAppResponse? =
        run.agentClientId?.let { clientId -> agentApps.responses(listOf(clientId))[clientId] }

    private companion object {
        const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
    }
}

data class SqlExploreRunHttpRequest(
    val connectorId: String?,
    val sql: String?,
)

data class ExploreRunHttpView(
    val runId: String,
    val kind: String,
    val workspaceId: String,
    val connectorId: String,
    val requestDigest: String,
    val state: String,
    val revision: Long,
    val resultAvailable: Boolean,
    val failureCode: String?,
    val failureDetail: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val resultMetadata: ExploreResultMetadataHttpView?,
    val sql: String,
    val query: Map<String, Any?>?,
    /** The IANA zone this run's Semantic SQL was rendered in; null for SQL runs and Semantic runs recorded before zones were kept. */
    val reportingTimeZone: String?,
    /** The person whose Web session or agent connection started the run. */
    val startedBy: ExploreActorHttpView,
    val credentialSurface: String?,
    /** The agent app that started an AGENT run; null for a Web run or an agent run recorded before apps were kept. */
    val agentApp: AgentAppResponse?,
)

/** A recorded person; [displayName] is null when the account no longer has a readable name. */
data class ExploreActorHttpView(val id: String, val displayName: String?)

/** The exact run read: the run view plus its result preview, or the reason there is none. */
data class ExploreRunDetailHttpView(
    @get:JsonUnwrapped val run: ExploreRunHttpView,
    val resultState: String,
    val result: ExploreResultHttpView?,
)

data class ExploreRunsHttpView(
    val runs: List<ExploreRunHttpView>,
)

data class ExploreResultMetadataHttpView(
    val byteSize: String,
    val mediaType: String,
    val rowCount: String,
    val columnCount: Int,
    val expiresAt: Instant,
)

data class ExploreResultHttpView(
    val columns: List<ExploreResultColumnHttpView>,
    val rows: List<List<Any?>>,
    val returnedRowCount: Int,
    val stopReason: String,
)

data class ExploreResultColumnHttpView(
    val ordinal: Int,
    val name: String,
    val logicalType: String,
    val nullable: Boolean,
    val display: cc.monomer.control.semantic.inbound.rest.MetricDisplayDto?,
)

private fun ExploreRunResultRead.state(): String = when (this) {
    is ExploreRunResultRead.Available -> "AVAILABLE"
    ExploreRunResultRead.None -> "NONE"
    ExploreRunResultRead.Expired -> "EXPIRED"
    ExploreRunResultRead.PreviewUnavailable -> "PREVIEW_UNAVAILABLE"
}

private fun ExploreRunView.startedBy(names: Map<Uuid, String>): ExploreActorHttpView =
    ExploreActorHttpView(ownerPrincipalId.toString(), names[ownerPrincipalId.value])

private fun ExploreRunView.toHttpView(sql: String, startedBy: ExploreActorHttpView, agentApp: AgentAppResponse?): ExploreRunHttpView =
    ExploreRunHttpView(
        runId = id.toString(),
        kind = kind.type.name,
        workspaceId = workspaceId.toString(),
        connectorId = connectorBinding.connectorId.toString(),
        requestDigest = requestDigest.toString(),
        state = state.name,
        revision = revision,
        resultAvailable = resultAvailable,
        failureCode = failureCode?.name,
        failureDetail = failureDetail,
        createdAt = createdAt,
        updatedAt = updatedAt,
        resultMetadata = resultMetadata?.let { metadata ->
            ExploreResultMetadataHttpView(
                byteSize = metadata.byteSize.toString(),
                mediaType = metadata.mediaType,
                rowCount = metadata.rowCount.toString(),
                columnCount = metadata.columnCount,
                expiresAt = metadata.expiresAt,
            )
        },
        sql = sql,
        query = (kind as? ExploreRunView.Kind.Semantic)?.query?.toHttpValue(),
        reportingTimeZone = (kind as? ExploreRunView.Kind.Semantic)?.reportingTimeZone?.id,
        startedBy = startedBy,
        credentialSurface = credentialSurface?.name,
        agentApp = agentApp,
    )

private fun ExploreResolvedResult.toHttpView(): ExploreResultHttpView =
    ExploreResultHttpView(
        columns = columns.mapIndexed { index, column ->
            ExploreResultColumnHttpView(
                ordinal = column.ordinal,
                name = column.name,
                logicalType = column.logicalType.name,
                nullable = column.nullable,
                display = columnDisplays.getOrNull(index)?.let(cc.monomer.control.semantic.inbound.rest.MetricDisplayDto::fromDomain),
            )
        },
        rows = rows.map { row -> row.map(ResultPreviewCell::toHttpValue) },
        returnedRowCount = returnedRowCount,
        stopReason = stopReason.name,
    )

private fun ResultPreviewCell.toHttpValue(): Any? =
    when (this) {
        ResultPreviewCell.Null -> null
        is ResultPreviewCell.BooleanValue -> value
        is ResultPreviewCell.Text -> value
        is ResultPreviewCell.Number -> canonicalValue
        is ResultPreviewCell.LocalDateValue -> canonicalValue
        is ResultPreviewCell.LocalDateTimeValue -> canonicalValue
        is ResultPreviewCell.InstantValue -> canonicalValue
    }

private fun parseWorkspaceId(raw: String): WorkspaceId =
    try {
        WorkspaceId.parse(raw)
    } catch (_: RuntimeException) {
        badRequest("invalid workspace id: $raw")
    }

private fun parseConnectorId(raw: String?): ConnectorId =
    try {
        ConnectorId(Uuid.parse(requireNotNull(raw) { "connectorId is required" }))
    } catch (_: RuntimeException) {
        badRequest("invalid connector id")
    }

private fun parseRunId(raw: String): ExploreRunId =
    try {
        ExploreRunId(Uuid.parse(raw))
    } catch (_: RuntimeException) {
        badRequest("invalid run id: $raw")
    }

private fun requireIdempotencyKey(value: String): String {
    if (!value.matches(Regex("^[A-Za-z0-9._:-]{1,200}$"))) {
        badRequest("Idempotency-Key must be 1..200 characters matching [A-Za-z0-9._:-]+")
    }
    return value
}

private fun requireBearerCredential(exchange: ServerWebExchange): PresentedCredential =
    when (val bearer = BearerCredentials.fromFirstAuthorizationHeader(exchange.request.headers)) {
        is BearerAuthorization.Presented -> bearer.credential
        BearerAuthorization.Missing, BearerAuthorization.Malformed ->
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.")
    }

private fun admissionDenied(decision: ControlAdmissionDecision.Denied): ResponseStatusException =
    decision.toHumanHttpException()

private fun badRequest(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
private fun forbidden(message: String): Nothing = throw ResponseStatusException(HttpStatus.FORBIDDEN, message)
private fun notFound(message: String): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND, message)
private fun conflict(message: String): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, message)
private fun gone(message: String): Nothing = throw ResponseStatusException(HttpStatus.GONE, message)
private fun unavailable(message: String): Nothing = throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message)
