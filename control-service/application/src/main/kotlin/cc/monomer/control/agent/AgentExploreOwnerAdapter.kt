@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.agent

import cc.monomer.agent.api.AgentPathPatterns.UUID_V7
import cc.monomer.agent.api.AgentActionOutcome
import cc.monomer.agent.api.AgentActionResult
import cc.monomer.agent.api.AgentActionResultReference
import cc.monomer.agent.api.AgentActionStatus
import cc.monomer.agent.api.AgentAvailableOperation
import cc.monomer.agent.api.AgentExploreOwnerPort
import cc.monomer.agent.api.AgentExploreResultColumn
import cc.monomer.agent.api.AgentExploreResultResourceBody
import cc.monomer.agent.api.AgentExploreRunRequest
import cc.monomer.agent.api.AgentExploreRunResourceBody
import cc.monomer.agent.api.AgentInvokeInput
import cc.monomer.agent.api.AgentInvokeRequest
import cc.monomer.agent.api.AgentListRequest
import cc.monomer.agent.api.AgentListingOutcome
import cc.monomer.agent.api.AgentReadRequest
import cc.monomer.agent.api.AgentResourceEnvelope
import cc.monomer.agent.api.AgentResourceKind
import cc.monomer.agent.api.AgentResourceListingItem
import cc.monomer.agent.api.AgentResourceListingResponse
import cc.monomer.agent.api.AgentResourceOutcome
import cc.monomer.agent.api.AgentResourcePath
import cc.monomer.agent.api.AgentSemanticElement
import cc.monomer.agent.api.AgentSemanticFilter
import cc.monomer.agent.api.AgentSemanticGroupBy
import cc.monomer.agent.api.AgentSemanticMetricTimeRange
import cc.monomer.agent.api.AgentSemanticOrder
import cc.monomer.agent.api.AgentSemanticOrderTarget
import cc.monomer.agent.api.AgentSemanticQuery
import cc.monomer.agent.api.AgentSemanticTimeRangeEndpoint
import cc.monomer.agent.api.AgentWorkspaceCaller
import cc.monomer.agent.api.AgentWorkspaceProblem
import cc.monomer.agent.api.AgentWorkspaceProblemStatus
import cc.monomer.connector.api.ConnectorId
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionDenial
import cc.monomer.control.ControlAdmissionStage
import cc.monomer.control.explore.CancelExploreRunRequest
import cc.monomer.control.explore.ExploreResultProjectionAccessOutcome
import cc.monomer.control.explore.ExploreRunAccessService
import cc.monomer.control.explore.ExploreRunCancelOutcome
import cc.monomer.control.explore.ExploreRunListOutcome
import cc.monomer.control.explore.ExploreRunSnapshotOutcome
import cc.monomer.control.explore.ExploreSemanticLaunchOutcome
import cc.monomer.control.explore.ExploreSemanticLaunchService
import cc.monomer.control.explore.ExploreSqlLaunchOutcome
import cc.monomer.control.explore.ExploreSqlLaunchService
import cc.monomer.control.explore.LaunchSemanticExploreRunCommand
import cc.monomer.control.explore.LaunchSqlExploreRunCommand
import cc.monomer.control.explore.ListExploreRunsRequest
import cc.monomer.control.explore.ReadExploreResultProjectionRequest
import cc.monomer.control.explore.ReadExploreRunCommand
import cc.monomer.control.explore.exploreRequestDigest
import cc.monomer.control.semantic.inbound.rest.MAX_SEMANTIC_QUERY_VIOLATIONS
import cc.monomer.control.semantic.inbound.rest.resourcePath
import cc.monomer.control.semantic.inbound.rest.toHttpValue
import cc.monomer.execution.api.ResultPreviewCell
import cc.monomer.explore.api.ExploreResultProjection
import cc.monomer.explore.api.ExploreRunId
import cc.monomer.explore.api.ExploreRunPageBoundary
import cc.monomer.explore.api.ExploreRunState
import cc.monomer.explore.api.ExploreRunView
import cc.monomer.identity.api.IdentityAdmissionDenial
import cc.monomer.semantic.api.SemanticBooleanFilterOperator
import cc.monomer.semantic.api.SemanticDateFilterOperator
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticElementReference
import cc.monomer.semantic.api.SemanticGroupBy
import cc.monomer.semantic.api.SemanticMetricTimeRange
import cc.monomer.semantic.api.SemanticNumberFilterOperator
import cc.monomer.semantic.api.SemanticQueryFilter
import cc.monomer.semantic.api.SemanticQueryOrder
import cc.monomer.semantic.api.SemanticQueryOrderDirection
import cc.monomer.semantic.api.SemanticQueryOrderTarget
import cc.monomer.semantic.api.SemanticQuerySpec
import cc.monomer.semantic.api.SemanticTextFilterOperator
import cc.monomer.semantic.api.SemanticTimeGranularity
import cc.monomer.semantic.api.SemanticTimeRangeEndpoint
import cc.monomer.semantic.api.SemanticTimestampFilterOperator
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid

class AgentExploreOwnerAdapter(
    private val runAccessService: ExploreRunAccessService,
    private val sqlLaunchService: ExploreSqlLaunchService,
    private val semanticLaunchService: ExploreSemanticLaunchService,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
    private val provenanceNames: AgentProvenanceNames,
) : AgentExploreOwnerPort {
    override suspend fun list(
        caller: AgentWorkspaceCaller,
        request: AgentListRequest,
    ): AgentListingOutcome {
        if (request.path.value != RUN_COLLECTION_PATH) return AgentListingOutcome.NotFound
        val after = try {
            request.cursor?.let(::decodeCursor)
        } catch (_: IllegalArgumentException) {
            return AgentListingOutcome.Rejected(AgentOwnerProblems.invalidCursor)
        }
        return agentOwnerGuard("AgentExploreOwnerAdapter", AgentOwnerProblems.ownerUnavailable, AgentListingOutcome::Rejected) {
            when (
                val outcome = runAccessService.listRuns(
                    ListExploreRunsRequest(
                        workspaceId = caller.workspaceId,
                        credential = caller.presentedCredential,
                        expectedOAuthResource = caller.expectedOAuthResource,
                        after = after,
                        limit = request.limit,
                    ),
                )
            ) {
                is ExploreRunListOutcome.Observed -> AgentListingOutcome.Found(
                    AgentResourceListingResponse(
                        items = outcome.page.runs.map(::listingItem),
                        nextCursor = outcome.page.next?.let(::encodeCursor),
                        availableOperations = runCollectionOperations(request.path),
                    ),
                )
                is ExploreRunListOutcome.AdmissionDenied -> outcome.decision.toAgentListingOutcome()
            }
        }
    }

    override suspend fun read(
        caller: AgentWorkspaceCaller,
        request: AgentReadRequest,
    ): AgentResourceOutcome {
        val resultMatch = RESULT_PATH.matchEntire(request.path.value)
        if (resultMatch != null) {
            val runId = parseRunId(resultMatch.groupValues[1]) ?: return AgentResourceOutcome.NotFound
            return agentOwnerGuard("AgentExploreOwnerAdapter", AgentOwnerProblems.ownerUnavailable, AgentResourceOutcome::Rejected) {
                readResult(caller, request, runId)
            }
        }
        val runMatch = RUN_PATH.matchEntire(request.path.value) ?: return AgentResourceOutcome.NotFound
        val runId = parseRunId(runMatch.groupValues[1]) ?: return AgentResourceOutcome.NotFound
        return agentOwnerGuard("AgentExploreOwnerAdapter", AgentOwnerProblems.ownerUnavailable, AgentResourceOutcome::Rejected) {
            when (
                val outcome = runAccessService.readSnapshot(
                    ReadExploreRunCommand(
                        workspaceId = caller.workspaceId,
                        runId = runId,
                        credential = caller.presentedCredential,
                        expectedOAuthResource = caller.expectedOAuthResource,
                    ),
                )
            ) {
                is ExploreRunSnapshotOutcome.Observed -> {
                    val agentApp = provenanceNames.read(emptyList(), listOf(outcome.run.agentClientId)).app(outcome.run.agentClientId)
                    AgentResourceOutcome.Found(
                        AgentResourceEnvelope.ExploreRun(path = request.path, body = outcome.run.toAgentBody(outcome.sql, agentApp), availableOperations = outcome.run.availableOperations()),
                    )
                }
                is ExploreRunSnapshotOutcome.AdmissionDenied -> outcome.decision.toAgentResourceOutcome()
                ExploreRunSnapshotOutcome.AccessDenied -> AgentResourceOutcome.Forbidden
                ExploreRunSnapshotOutcome.NotFound -> AgentResourceOutcome.NotFound
                ExploreRunSnapshotOutcome.TemporarilyUnavailable -> AgentResourceOutcome.Rejected(AgentOwnerProblems.ownerUnavailable)
            }
        }
    }

    override suspend fun invoke(
        caller: AgentWorkspaceCaller,
        request: AgentInvokeRequest,
    ): AgentActionOutcome {
        val input = request.input
        val failureProblem = if (request.path.value == RUN_COLLECTION_PATH && request.action == "run") {
            AgentOwnerProblems.exploreStartUnconfirmed
        } else AgentOwnerProblems.ownerUnavailable
        return agentOwnerGuard("AgentExploreOwnerAdapter", failureProblem, AgentActionOutcome::Rejected) {
            when {
                request.path.value == RUN_COLLECTION_PATH &&
                    request.action == "run" &&
                    input is AgentInvokeInput.ExploreRun -> start(caller, request, input.request)
                RUN_PATH.matches(request.path.value) &&
                    request.action == "cancel" &&
                    input is AgentInvokeInput.None -> cancel(caller, request)
                else -> AgentActionOutcome.NotFound
            }
        }
    }

    private suspend fun readResult(
        caller: AgentWorkspaceCaller,
        request: AgentReadRequest,
        runId: ExploreRunId,
    ): AgentResourceOutcome = when (
        val outcome = runAccessService.readResult(
            ReadExploreResultProjectionRequest(
                workspaceId = caller.workspaceId,
                runId = runId,
                credential = caller.presentedCredential,
                expectedOAuthResource = caller.expectedOAuthResource,
            ),
        )
    ) {
        is ExploreResultProjectionAccessOutcome.Observed -> {
            if (request.download == null) {
                AgentResourceOutcome.Found(
                    AgentResourceEnvelope.ExploreResult(path = request.path, body = outcome.projection.toAgentBody(null), availableOperations = emptyList()),
                )
            } else {
                when (val download = runAccessService.readDownloadLink(
                    cc.monomer.control.explore.ReadExploreResultArtifactRequest(
                        workspaceId = caller.workspaceId,
                        runId = runId,
                        credential = caller.presentedCredential,
                        expectedOAuthResource = caller.expectedOAuthResource,
                    ),
                )) {
                    is cc.monomer.control.explore.ExploreRunDownloadLinkReadOutcome.Visible -> AgentResourceOutcome.Found(
                        AgentResourceEnvelope.ExploreResult(
                            path = request.path,
                            body = outcome.projection.toAgentBody(cc.monomer.agent.api.AgentExploreResultDownloadLink(
                                download.link.url, download.link.fileName, download.link.mediaType,
                                download.link.byteSize.toString(), download.link.expiresAt,
                            )),
                            availableOperations = emptyList(),
                        ),
                    )
                    is cc.monomer.control.explore.ExploreRunDownloadLinkReadOutcome.AdmissionDenied -> download.decision.toAgentResourceOutcome()
                    cc.monomer.control.explore.ExploreRunDownloadLinkReadOutcome.NotFound,
                    cc.monomer.control.explore.ExploreRunDownloadLinkReadOutcome.RetentionExpired -> AgentResourceOutcome.NotFound
                    cc.monomer.control.explore.ExploreRunDownloadLinkReadOutcome.AccessDenied -> AgentResourceOutcome.Forbidden
                    cc.monomer.control.explore.ExploreRunDownloadLinkReadOutcome.TemporarilyUnavailable -> AgentResourceOutcome.Rejected(AgentOwnerProblems.ownerUnavailable)
                    cc.monomer.control.explore.ExploreRunDownloadLinkReadOutcome.ArtifactUnavailable -> rejectedResource(
                        "EXPLORE_RESULT_UNAVAILABLE", "Explore result artifact is not available.",
                        AgentWorkspaceProblemStatus.CONFLICT, false,
                    )
                }
            }
        }
        is ExploreResultProjectionAccessOutcome.AdmissionDenied -> outcome.decision.toAgentResourceOutcome()
        ExploreResultProjectionAccessOutcome.AccessDenied -> AgentResourceOutcome.Forbidden
        ExploreResultProjectionAccessOutcome.NotFound,
        ExploreResultProjectionAccessOutcome.RetentionExpired,
        -> AgentResourceOutcome.NotFound
        ExploreResultProjectionAccessOutcome.TemporarilyUnavailable -> rejectedResource(
            "OWNER_UNAVAILABLE",
            "Explore result is temporarily unavailable.",
            AgentWorkspaceProblemStatus.SERVICE_UNAVAILABLE,
            true,
        )
        ExploreResultProjectionAccessOutcome.PreviewUnavailable -> rejectedResource(
            "EXPLORE_RESULT_UNAVAILABLE",
            "Explore result preview is not available.",
            AgentWorkspaceProblemStatus.CONFLICT,
            false,
        )
    }

    private suspend fun start(
        caller: AgentWorkspaceCaller,
        request: AgentInvokeRequest,
        input: AgentExploreRunRequest,
    ): AgentActionOutcome = when (input) {
        is AgentExploreRunRequest.Sql -> when (
            val outcome = sqlLaunchService.start(
                LaunchSqlExploreRunCommand(
                    workspaceId = caller.workspaceId,
                    credential = caller.presentedCredential,
                    expectedOAuthResource = caller.expectedOAuthResource,
                    idempotencyKey = caller.requestId.value,
                    connectorId = ConnectorId(input.connectorId),
                    sql = input.statement,
                ),
            )
        ) {
            is ExploreSqlLaunchOutcome.Started -> accepted(request, outcome.run)
            is ExploreSqlLaunchOutcome.Existing -> completed(request, outcome.run)
            is ExploreSqlLaunchOutcome.AdmissionDenied -> outcome.decision.toAgentActionOutcome()
            ExploreSqlLaunchOutcome.ConnectorNotFound -> connectorNotFoundAction()
            is ExploreSqlLaunchOutcome.ExecutionUnavailable -> rejectedAction(
                if (outcome.failureCode == "EXECUTION_UNAVAILABLE") "OWNER_UNAVAILABLE" else outcome.failureCode,
                outcome.message,
                AgentWorkspaceProblemStatus.SERVICE_UNAVAILABLE,
                outcome.retryable,
            )
            is ExploreSqlLaunchOutcome.SqlValidationFailed -> rejectedAction(
                outcome.code,
                outcome.message,
                AgentWorkspaceProblemStatus.CONFLICT,
                false,
            )
            ExploreSqlLaunchOutcome.IdempotencyConflict,
            ExploreSqlLaunchOutcome.Conflict,
            -> conflictAction("Explore SQL run conflicts with current owner state.")
        }
        is AgentExploreRunRequest.Semantic -> run {
            val query = try {
                input.query.toDomain()
            } catch (_: IllegalArgumentException) {
                return@run AgentActionOutcome.Rejected(exploreRequestProblem())
            }
            when (
                val outcome = semanticLaunchService.start(
                    LaunchSemanticExploreRunCommand(
                        workspaceId = caller.workspaceId,
                        credential = caller.presentedCredential,
                        expectedOAuthResource = caller.expectedOAuthResource,
                        idempotencyKey = caller.requestId.value,
                        connectorId = ConnectorId(input.connectorId),
                        requestDigest = exploreRequestDigest(
                            "SEMANTIC",
                            objectMapper.writeValueAsString(query.toHttpValue()),
                        ),
                        query = query,
                    ),
                )
            ) {
                is ExploreSemanticLaunchOutcome.Started -> accepted(request, outcome.run)
                is ExploreSemanticLaunchOutcome.Existing -> completed(request, outcome.run)
                is ExploreSemanticLaunchOutcome.AdmissionDenied -> outcome.decision.toAgentActionOutcome()
                is ExploreSemanticLaunchOutcome.ValidationRejected -> AgentActionOutcome.Rejected(
                    problem(outcome.code, outcome.message, AgentWorkspaceProblemStatus.CONFLICT, false)
                        .copy(partitionFilters = outcome.partitionFilters.map { it.toAgent() }),
                )
                is ExploreSemanticLaunchOutcome.ExecutionUnavailable -> rejectedAction(
                    outcome.failureCode, outcome.message, AgentWorkspaceProblemStatus.SERVICE_UNAVAILABLE, outcome.retryable,
                )
                is ExploreSemanticLaunchOutcome.Invalid -> AgentActionOutcome.Rejected(
                    AgentWorkspaceProblem(
                        code = "INVALID_SEMANTIC_QUERY",
                        title = "Explore operation rejected",
                        detail = outcome.reasons.joinToString(",") { it.code.name },
                        status = AgentWorkspaceProblemStatus.BAD_REQUEST,
                        retryable = false,
                        currentRevision = null,
                        violations = (
                            outcome.reasons.map { reason ->
                                cc.monomer.agent.api.AgentProblemViolation(
                                    field = reason.field?.let { "input.query.$it" } ?: "input.query",
                                    code = reason.code.name,
                                    message = if (reason.code == cc.monomer.semantic.api.SemanticInvalidReasonCode.QUERY_FILTER_UNRENDERABLE &&
                                        reason.field?.endsWith(".kind") == true) {
                                        cc.monomer.control.semantic.inbound.rest.SEMANTIC_FILTER_KIND_VIOLATION_MESSAGE
                                    } else {
                                        "The Semantic query cannot execute with the current definitions and selected Connector."
                                    },
                                    operationIndex = null,
                                    path = null,
                                )
                            } + outcome.definitionIssues.map { issue ->
                                cc.monomer.agent.api.AgentProblemViolation(
                                    field = (issue.definition?.resourcePath() ?: "input.query").take(AGENT_VIOLATION_TEXT_LENGTH),
                                    code = issue.code.name,
                                    message = issue.message,
                                    operationIndex = null,
                                    path = null,
                                )
                            }
                        ).take(MAX_SEMANTIC_QUERY_VIOLATIONS),
                        hints = emptyList(),
                        nextActions = emptyList(),
                        retryAfterSeconds = null,
                        roleRequirement = null,
                        partitionFilters = emptyList(),
                    ),
                )
                is ExploreSemanticLaunchOutcome.Unsupported -> rejectedAction(
                    "UNSUPPORTED_SEMANTIC_QUERY",
                    outcome.capabilities.joinToString(",") { it.name },
                    AgentWorkspaceProblemStatus.BAD_REQUEST,
                    false,
                )
                ExploreSemanticLaunchOutcome.ConnectorNotFound -> connectorNotFoundAction()
                is ExploreSemanticLaunchOutcome.MetricNotFound -> AgentActionOutcome.Rejected(
                    AgentWorkspaceProblem(
                        code = "NOT_FOUND",
                        title = "Metric not found",
                        detail = "Metric `${outcome.name}` was not found. It may have been deleted.",
                        status = AgentWorkspaceProblemStatus.NOT_FOUND,
                        retryable = false,
                        currentRevision = null,
                        violations = listOf(cc.monomer.agent.api.AgentProblemViolation(
                            field = "workspace/metrics/${outcome.name.value}",
                            code = "METRIC_NOT_FOUND",
                            message = "Metric `${outcome.name}` was not found. It may have been deleted.",
                            operationIndex = null,
                            path = null,
                        )),
                        hints = emptyList(),
                        nextActions = emptyList(),
                        retryAfterSeconds = null,
                        roleRequirement = null,
                        partitionFilters = emptyList(),
                    ),
                )
                ExploreSemanticLaunchOutcome.NotFound -> AgentActionOutcome.NotFound
                ExploreSemanticLaunchOutcome.IdempotencyConflict,
                ExploreSemanticLaunchOutcome.Conflict,
                -> conflictAction("Explore Semantic run conflicts with current owner state.")
            }
        }
    }

    private suspend fun cancel(
        caller: AgentWorkspaceCaller,
        request: AgentInvokeRequest,
    ): AgentActionOutcome {
        val match = RUN_PATH.matchEntire(request.path.value) ?: return AgentActionOutcome.NotFound
        val runId = parseRunId(match.groupValues[1]) ?: return AgentActionOutcome.NotFound
        return when (
            val outcome = runAccessService.cancel(
                CancelExploreRunRequest(
                    workspaceId = caller.workspaceId,
                    runId = runId,
                    credential = caller.presentedCredential,
                    expectedOAuthResource = caller.expectedOAuthResource,
                    reasonCode = "AGENT_REQUESTED",
                ),
            )
        ) {
            is ExploreRunCancelOutcome.Accepted -> accepted(request, outcome.run)
            is ExploreRunCancelOutcome.AlreadyTerminal -> completed(request, outcome.run)
            is ExploreRunCancelOutcome.AdmissionDenied -> outcome.decision.toAgentActionOutcome()
            ExploreRunCancelOutcome.AccessDenied -> AgentActionOutcome.Forbidden
            ExploreRunCancelOutcome.NotFound -> AgentActionOutcome.NotFound
            ExploreRunCancelOutcome.ConcurrentConflict -> conflictAction(
                "Explore run changed while cancellation was requested.",
            )
        }
    }

    private fun accepted(request: AgentInvokeRequest, run: ExploreRunView): AgentActionOutcome =
        actionResult(request, run, AgentActionStatus.ACCEPTED)

    private fun completed(request: AgentInvokeRequest, run: ExploreRunView): AgentActionOutcome =
        actionResult(request, run, AgentActionStatus.COMPLETED)

    private fun actionResult(
        request: AgentInvokeRequest,
        run: ExploreRunView,
        status: AgentActionStatus,
    ): AgentActionOutcome = AgentActionOutcome.Completed(
        AgentActionResult.Invoke(path = request.path, action = request.action, status = status, result = AgentActionResultReference(AgentResourceKind.EXPLORE_RUN, runPath(run.id))),
    )

    private fun listingItem(run: ExploreRunView) = AgentResourceListingItem.ExploreRun(path = runPath(run.id), runKind = cc.monomer.agent.api.AgentExploreRunKind.valueOf(run.kind.type.name), connectorId = run.connectorBinding.connectorId.value, state = cc.monomer.agent.api.AgentRunState.valueOf(run.state.name), createdAt = run.createdAt, updatedAt = run.updatedAt)

    private fun ExploreRunView.toAgentBody(sql: String, agentApp: cc.monomer.agent.api.AgentApp?): AgentExploreRunResourceBody = when (val runKind = kind) {
        ExploreRunView.Kind.Sql -> AgentExploreRunResourceBody.Sql(
            connectorId = connectorBinding.connectorId.value,
            state = state.name,
            failureCode = failureCode?.name,
            failureDetail = failureDetail,
            createdAt = createdAt,
            updatedAt = updatedAt,
            sql = sql,
            credentialSurface = credentialSurface,
            agentApp = agentApp,
        )
        is ExploreRunView.Kind.Semantic -> AgentExploreRunResourceBody.Semantic(
            connectorId = connectorBinding.connectorId.value,
            state = state.name,
            failureCode = failureCode?.name,
            failureDetail = failureDetail,
            createdAt = createdAt,
            updatedAt = updatedAt,
            sql = sql,
            query = runKind.query.toAgentQuery(),
            credentialSurface = credentialSurface,
            agentApp = agentApp,
        )
    }

    private fun ExploreResultProjection.toAgentBody(download: cc.monomer.agent.api.AgentExploreResultDownloadLink?) = AgentExploreResultResourceBody(
        columns = columns.map { column -> AgentExploreResultColumn(column.name, column.logicalType.name, display = column.display.toAgentDisplay()) },
        rows = rows.map { row -> row.map(ResultPreviewCell::toAgentValue) },
        totalRowCount = totalRowCount.toString(),
        returnedRowCount = returnedRowCount,
        truncated = truncated,
        expiresAt = expiresAt,
        download = download,
        reportingTimeZone = (run.kind as? ExploreRunView.Kind.Semantic)?.reportingTimeZone,
    )

    private fun ExploreRunView.availableOperations(): List<AgentAvailableOperation> = when {
        state == ExploreRunState.RUNNING -> listOf(
            AgentAvailableOperation.Invoke(path = runPath(id), action = "cancel", inputKind = null),
        )
        state == ExploreRunState.SUCCEEDED &&
            resultAvailable &&
            resultMetadata?.expiresAt?.isAfter(clock.instant()) == true -> listOf(
            AgentAvailableOperation.Read(path = resultPath(id)),
        )
        else -> emptyList()
    }

    private fun runCollectionOperations(path: AgentResourcePath) = listOf(
        AgentAvailableOperation.Invoke(path = path, action = "run", inputKind = "SEMANTIC_EXPLORE_RUN"),
        AgentAvailableOperation.Invoke(path = path, action = "run", inputKind = "SQL_EXPLORE_RUN"),
    )

    private fun encodeCursor(boundary: ExploreRunPageBoundary): String {
        val payload = """{"version":1,"path":"$RUN_COLLECTION_PATH","createdAt":"${boundary.createdAt}","runId":"${boundary.runId}"}"""
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeCursor(cursor: String): ExploreRunPageBoundary {
        val decoded = String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
        val match = CURSOR_PAYLOAD.matchEntire(decoded) ?: throw IllegalArgumentException("invalid cursor payload")
        val createdAt = try {
            Instant.parse(match.groupValues[1])
        } catch (failure: DateTimeParseException) {
            throw IllegalArgumentException("invalid cursor timestamp", failure)
        }
        val runId = parseRunId(match.groupValues[2]) ?: throw IllegalArgumentException("invalid cursor run id")
        val boundary = ExploreRunPageBoundary(createdAt, runId)
        require(createdAt.toString() == match.groupValues[1] && encodeCursor(boundary) == cursor)
        return boundary
    }

    private fun parseRunId(raw: String): ExploreRunId? = try {
        ExploreRunId(Uuid.parse(raw)).takeIf { it.toString() == raw }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun rejectedResource(
        code: String,
        detail: String,
        status: AgentWorkspaceProblemStatus,
        retryable: Boolean,
    ) = AgentResourceOutcome.Rejected(problem(code, detail, status, retryable))

    private fun rejectedAction(
        code: String,
        detail: String,
        status: AgentWorkspaceProblemStatus,
        retryable: Boolean,
    ) = AgentActionOutcome.Rejected(problem(code, detail, status, retryable))

    private fun connectorNotFoundAction() = rejectedAction(
        "CONNECTOR_NOT_FOUND",
        "The connector was not found. It may have been deleted; choose another connector.",
        AgentWorkspaceProblemStatus.NOT_FOUND,
        false,
    )

    private fun conflictAction(detail: String) = rejectedAction(
        "EXPLORE_RUN_CONFLICT",
        detail,
        AgentWorkspaceProblemStatus.CONFLICT,
        false,
    )

    private fun problem(
        code: String,
        detail: String,
        status: AgentWorkspaceProblemStatus,
        retryable: Boolean,
    ) = AgentWorkspaceProblem(
        code = code,
        title = "Explore operation rejected",
        detail = detail,
        status = status,
        retryable = retryable,
        currentRevision = null,
        violations = emptyList(),
        hints = emptyList(),
        nextActions = emptyList(),
        retryAfterSeconds = null,
        roleRequirement = null,
        partitionFilters = emptyList(),
    )

    private fun runPath(runId: ExploreRunId) = AgentResourcePath.parse("$RUN_COLLECTION_PATH/$runId")
    private fun resultPath(runId: ExploreRunId) = AgentResourcePath.parse("${runPath(runId)}/result")

    private fun ControlAdmissionDecision.Denied.toAgentListingOutcome(): AgentListingOutcome =
        when (toExploreAdmissionOutcome()) {
            ExploreAdmissionOutcome.INVALID_REQUEST -> AgentListingOutcome.Rejected(exploreRequestProblem())
            ExploreAdmissionOutcome.NOT_FOUND -> AgentListingOutcome.NotFound
            ExploreAdmissionOutcome.UNAUTHENTICATED -> AgentListingOutcome.Rejected(unauthenticatedProblem())
            ExploreAdmissionOutcome.FORBIDDEN -> AgentListingOutcome.Forbidden
        }

    private fun ControlAdmissionDecision.Denied.toAgentResourceOutcome(): AgentResourceOutcome =
        when (toExploreAdmissionOutcome()) {
            ExploreAdmissionOutcome.INVALID_REQUEST -> AgentResourceOutcome.Rejected(exploreRequestProblem())
            ExploreAdmissionOutcome.NOT_FOUND -> AgentResourceOutcome.NotFound
            ExploreAdmissionOutcome.UNAUTHENTICATED -> AgentResourceOutcome.Rejected(unauthenticatedProblem())
            ExploreAdmissionOutcome.FORBIDDEN -> AgentResourceOutcome.Forbidden
        }

    private fun ControlAdmissionDecision.Denied.toAgentActionOutcome(): AgentActionOutcome =
        when (toExploreAdmissionOutcome()) {
            ExploreAdmissionOutcome.INVALID_REQUEST -> AgentActionOutcome.Rejected(exploreRequestProblem())
            ExploreAdmissionOutcome.NOT_FOUND -> AgentActionOutcome.NotFound
            ExploreAdmissionOutcome.UNAUTHENTICATED -> AgentActionOutcome.Rejected(unauthenticatedProblem())
            ExploreAdmissionOutcome.FORBIDDEN -> AgentActionOutcome.Forbidden
        }

    private fun ControlAdmissionDecision.Denied.toExploreAdmissionOutcome(): ExploreAdmissionOutcome = when (stage) {
        ControlAdmissionStage.REQUEST -> ExploreAdmissionOutcome.INVALID_REQUEST
        ControlAdmissionStage.IDENTITY ->
            if (reason is ControlAdmissionDenial.Identity && reason.reason in setOf(IdentityAdmissionDenial.WORKSPACE_UNAVAILABLE, IdentityAdmissionDenial.ORGANIZATION_MEMBERSHIP_UNAVAILABLE, IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE)) {
                ExploreAdmissionOutcome.NOT_FOUND
            } else if (reason is ControlAdmissionDenial.Identity && reason.reason.isCredentialAdmissionDenial()) {
                ExploreAdmissionOutcome.UNAUTHENTICATED
            } else {
                ExploreAdmissionOutcome.FORBIDDEN
            }
    }

    private fun IdentityAdmissionDenial.isCredentialAdmissionDenial(): Boolean = this in setOf(
        IdentityAdmissionDenial.CREDENTIAL_UNAVAILABLE,
        IdentityAdmissionDenial.CREDENTIAL_EXPIRED,
        IdentityAdmissionDenial.CREDENTIAL_REVOKED,
        IdentityAdmissionDenial.PRINCIPAL_MISMATCH,
        IdentityAdmissionDenial.AGENT_BINDING_MISMATCH,
        IdentityAdmissionDenial.AGENT_SCOPE_INSUFFICIENT,
    )

    private fun exploreRequestProblem() = problem(
        code = "EXPLORE_REQUEST_INVALID",
        detail = "The Explore request is invalid.",
        status = AgentWorkspaceProblemStatus.BAD_REQUEST,
        retryable = false,
    )

    private fun unauthenticatedProblem() = AgentWorkspaceProblem(
        code = "AUTHENTICATION_REQUIRED",
        title = "Unauthorized",
        detail = "A valid credential is required.",
        status = AgentWorkspaceProblemStatus.UNAUTHORIZED,
        retryable = false,
        currentRevision = null,
        violations = emptyList(),
        hints = emptyList(),
        nextActions = emptyList(),
        retryAfterSeconds = null,
        roleRequirement = null,
        partitionFilters = emptyList(),
    )

    private companion object {
        const val RUN_COLLECTION_PATH = "workspace/explore/runs"
        const val AGENT_VIOLATION_TEXT_LENGTH = 512
        val RUN_PATH = Regex("^$RUN_COLLECTION_PATH/($UUID_V7)$")
        val RESULT_PATH = Regex("^$RUN_COLLECTION_PATH/($UUID_V7)/result$")
        val CURSOR_PAYLOAD = Regex(
            "^\\{\\\"version\\\":1,\\\"path\\\":\\\"workspace/explore/runs\\\",\\\"createdAt\\\":\\\"([^\\\"]+)\\\",\\\"runId\\\":\\\"($UUID_V7)\\\"}$",
        )
    }

    private enum class ExploreAdmissionOutcome {
        INVALID_REQUEST,
        NOT_FOUND,
        UNAUTHENTICATED,
        FORBIDDEN,
    }
}

private fun AgentSemanticQuery.toDomain() = SemanticQuerySpec(
    metrics = metrics.map(::SemanticElementName),
    groupBys = groupBys.map(AgentSemanticGroupBy::toDomain),
    metricTimeRange = metricTimeRange?.toDomain(),
    filters = filters.map(AgentSemanticFilter::toDomain),
    orderBy = orderBy.map(AgentSemanticOrder::toDomain),
    rowLimit = rowLimit,
)

private fun AgentSemanticGroupBy.toDomain(): SemanticGroupBy = when (this) {
    is AgentSemanticGroupBy.Dimension -> SemanticGroupBy.Dimension(
        SemanticElementName(semanticModelName),
        SemanticElementName(name),
        granularity?.let { SemanticTimeGranularity.valueOf(it.name) },
    )
    is AgentSemanticGroupBy.Entity -> SemanticGroupBy.Entity(
        SemanticElementName(semanticModelName),
        SemanticElementName(name),
    )
    is AgentSemanticGroupBy.MetricTime -> SemanticGroupBy.MetricTime(
        SemanticTimeGranularity.valueOf(granularity.name),
    )
}

private fun AgentSemanticElement.toDomain() = SemanticElementReference(
    SemanticElementName(semanticModelName),
    SemanticElementName(name),
)

private fun AgentSemanticMetricTimeRange.toDomain() = SemanticMetricTimeRange(
    start.toDomain(),
    end.toDomain(),
)

private fun AgentSemanticTimeRangeEndpoint.toDomain(): SemanticTimeRangeEndpoint = when (this) {
    is AgentSemanticTimeRangeEndpoint.Date -> SemanticTimeRangeEndpoint.Date(value)
    is AgentSemanticTimeRangeEndpoint.Timestamp -> SemanticTimeRangeEndpoint.Timestamp(value)
}

private fun AgentSemanticFilter.toDomain(): SemanticQueryFilter = when (this) {
    is AgentSemanticFilter.Text -> SemanticQueryFilter.Text(
        dimension.toDomain(),
        SemanticTextFilterOperator.valueOf(operator.name),
        values,
    )
    is AgentSemanticFilter.Number -> SemanticQueryFilter.Number(
        dimension.toDomain(),
        SemanticNumberFilterOperator.valueOf(operator.name),
        values,
    )
    is AgentSemanticFilter.Boolean -> SemanticQueryFilter.Boolean(
        dimension.toDomain(),
        SemanticBooleanFilterOperator.valueOf(operator.name),
        values,
    )
    is AgentSemanticFilter.Date -> SemanticQueryFilter.Date(
        dimension.toDomain(),
        SemanticDateFilterOperator.valueOf(operator.name),
        values,
    )
    is AgentSemanticFilter.Timestamp -> SemanticQueryFilter.Timestamp(
        dimension.toDomain(),
        SemanticTimestampFilterOperator.valueOf(operator.name),
        values,
    )
}

private fun AgentSemanticOrder.toDomain() = SemanticQueryOrder(
    target = when (val value = target) {
        is AgentSemanticOrderTarget.Metric -> SemanticQueryOrderTarget.Metric(SemanticElementName(value.metricName))
        is AgentSemanticOrderTarget.GroupBy -> SemanticQueryOrderTarget.GroupBy(value.groupBy.toDomain())
    },
    direction = SemanticQueryOrderDirection.valueOf(direction.name),
)

private fun ResultPreviewCell.toAgentValue(): JsonElement = when (this) {
    ResultPreviewCell.Null -> JsonNull
    is ResultPreviewCell.BooleanValue -> JsonPrimitive(value)
    is ResultPreviewCell.Text -> JsonPrimitive(value)
    is ResultPreviewCell.Number -> JsonPrimitive(canonicalValue)
    is ResultPreviewCell.LocalDateValue -> JsonPrimitive(canonicalValue)
    is ResultPreviewCell.LocalDateTimeValue -> JsonPrimitive(canonicalValue)
    is ResultPreviewCell.InstantValue -> JsonPrimitive(canonicalValue)
}

private fun SemanticQuerySpec.toAgentQuery() = AgentSemanticQuery(
    schemaVersion = 4u,
    metrics = metrics.map { it.value },
    groupBys = groupBys.map(SemanticGroupBy::toAgentGroupBy),
    metricTimeRange = metricTimeRange?.let {
        AgentSemanticMetricTimeRange(it.start.toAgentEndpoint(), it.end.toAgentEndpoint())
    },
    filters = filters.map(SemanticQueryFilter::toAgentFilter),
    orderBy = orderBy.map {
        AgentSemanticOrder(
            target = when (val target = it.target) {
                is SemanticQueryOrderTarget.Metric -> AgentSemanticOrderTarget.Metric(target.metricName.value)
                is SemanticQueryOrderTarget.GroupBy -> AgentSemanticOrderTarget.GroupBy(target.groupBy.toAgentGroupBy())
            },
            direction = cc.monomer.agent.api.AgentSemanticOrderDirection.valueOf(it.direction.name),
        )
    },
    rowLimit = rowLimit,
)

private fun SemanticGroupBy.toAgentGroupBy(): AgentSemanticGroupBy = when (this) {
    is SemanticGroupBy.Dimension -> AgentSemanticGroupBy.Dimension(
        semanticModelName.value, name.value,
        granularity?.let { cc.monomer.agent.api.AgentTimeGranularity.valueOf(it.name) },
    )
    is SemanticGroupBy.Entity -> AgentSemanticGroupBy.Entity(semanticModelName.value, name.value)
    is SemanticGroupBy.MetricTime -> AgentSemanticGroupBy.MetricTime(
        cc.monomer.agent.api.AgentTimeGranularity.valueOf(granularity.name),
    )
}

private fun SemanticElementReference.toAgentElement() = AgentSemanticElement(
    semanticModelName.value, name.value,
    granularity?.let { cc.monomer.agent.api.AgentTimeGranularity.valueOf(it.name) },
)

private fun SemanticTimeRangeEndpoint.toAgentEndpoint(): AgentSemanticTimeRangeEndpoint = when (this) {
    is SemanticTimeRangeEndpoint.Date -> AgentSemanticTimeRangeEndpoint.Date(value)
    is SemanticTimeRangeEndpoint.Timestamp -> AgentSemanticTimeRangeEndpoint.Timestamp(value)
}

private fun SemanticQueryFilter.toAgentFilter(): AgentSemanticFilter = when (this) {
    is SemanticQueryFilter.Text -> AgentSemanticFilter.Text(
        dimension.toAgentElement(), cc.monomer.agent.api.AgentSemanticTextFilterOperator.valueOf(operator.name), values,
    )
    is SemanticQueryFilter.Number -> AgentSemanticFilter.Number(
        dimension.toAgentElement(), cc.monomer.agent.api.AgentSemanticNumberFilterOperator.valueOf(operator.name), values,
    )
    is SemanticQueryFilter.Boolean -> AgentSemanticFilter.Boolean(
        dimension.toAgentElement(), cc.monomer.agent.api.AgentSemanticBooleanFilterOperator.valueOf(operator.name), values,
    )
    is SemanticQueryFilter.Date -> AgentSemanticFilter.Date(
        dimension.toAgentElement(), cc.monomer.agent.api.AgentSemanticDateFilterOperator.valueOf(operator.name), values,
    )
    is SemanticQueryFilter.Timestamp -> AgentSemanticFilter.Timestamp(
        dimension.toAgentElement(), cc.monomer.agent.api.AgentSemanticTimestampFilterOperator.valueOf(operator.name), values,
    )
}
