@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.agent

import cc.monomer.agent.api.AgentDashboardHistoryPaths
import cc.monomer.agent.api.AgentPathPatterns
import cc.monomer.agent.api.AgentPathPatterns.UUID_V7
import cc.monomer.agent.api.AgentDashboardDefinitionVersionBody
import cc.monomer.dashboard.api.*
import cc.monomer.control.dashboard.decodeDashboardHistoryCursor
import cc.monomer.control.dashboard.encodeDashboardHistoryCursor
import cc.monomer.control.dashboard.DashboardScheduleObservation
import cc.monomer.control.dashboard.dashboardQueryFailureMessage
import cc.monomer.agent.api.AgentDashboardSchedulePaths
import cc.monomer.agent.api.AgentDashboardSchedulesBody
import cc.monomer.agent.api.AgentDashboardScheduleOccurrenceBody
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import cc.monomer.control.dashboard.withQueryColumns
import cc.monomer.control.dashboard.DashboardResultUnavailableException

import cc.monomer.agent.api.AgentAvailableOperation

import cc.monomer.agent.api.AgentActionOutcome
import cc.monomer.agent.api.AgentActionResult
import cc.monomer.agent.api.AgentActionResultReference
import cc.monomer.agent.api.AgentActionStatus
import cc.monomer.agent.api.AgentCreateInput
import cc.monomer.agent.api.AgentCreateRequest
import cc.monomer.agent.api.AgentDashboardDraftResourceBody
import cc.monomer.agent.api.AgentDashboardDraftStatusBody
import cc.monomer.agent.api.AgentDashboardDraftTargetQueryRun
import cc.monomer.agent.api.AgentRunState
import cc.monomer.agent.api.AgentDashboardQueryRunBody
import cc.monomer.agent.api.AgentDashboardResultColumn
import cc.monomer.agent.api.AgentDashboardResultResourceBody
import cc.monomer.agent.api.AgentDashboardReadyResult
import cc.monomer.agent.api.AgentDashboardResourceBody
import cc.monomer.agent.api.AgentDashboardSessionCurrentQueryRun
import cc.monomer.agent.api.AgentDashboardSessionResourceBody
import cc.monomer.agent.api.AgentDashboardOwnerPort
import cc.monomer.agent.api.AgentEditRequest
import cc.monomer.agent.api.AgentInvokeInput
import cc.monomer.agent.api.AgentInvokeRequest
import cc.monomer.agent.api.AgentJsonPatch
import cc.monomer.agent.api.AgentJsonPatchException
import cc.monomer.agent.api.AgentListRequest
import cc.monomer.agent.api.AgentListingOutcome
import cc.monomer.agent.api.AgentProblemViolation
import cc.monomer.agent.api.AgentReadRequest
import cc.monomer.agent.api.AgentResourceEnvelope
import cc.monomer.agent.api.AgentResourceListingItem
import cc.monomer.agent.api.AgentResourceListingResponse
import cc.monomer.agent.api.AgentResourceKind
import cc.monomer.agent.api.AgentResourceOutcome
import cc.monomer.agent.api.AgentResourcePath
import cc.monomer.agent.api.AgentRevision
import cc.monomer.agent.api.AgentWorkspaceCaller
import cc.monomer.agent.api.AgentWorkspaceOperation
import cc.monomer.agent.api.AgentWorkspaceProblem
import cc.monomer.agent.api.AgentWorkspaceProblemStatus
import cc.monomer.control.WorkspaceRoleAdmissionRequest
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.dashboard.api.ApplyDraftCommand
import cc.monomer.dashboard.api.DiscardDraftCommand
import cc.monomer.dashboard.api.CancelDashboardDraftRunCommand
import cc.monomer.dashboard.api.CreateDashboardCommand
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardOwnerCommandMetadata
import cc.monomer.dashboard.api.DashboardOwnerDashboard
import cc.monomer.dashboard.api.DashboardOwnerDraft
import cc.monomer.dashboard.api.DashboardOwnerDraftStatus
import cc.monomer.dashboard.api.DashboardOwnerReadyResult
import cc.monomer.dashboard.api.DashboardOwnerOutcome
import cc.monomer.dashboard.api.DashboardOwnerPort
import cc.monomer.dashboard.api.DashboardOwnerQueryRun
import cc.monomer.dashboard.api.DashboardOwnerDashboardPageCursor
import cc.monomer.dashboard.api.DashboardOwnerQueryRunPageCursor
import cc.monomer.dashboard.api.DashboardOwnerResultLookupOutcome
import cc.monomer.dashboard.api.DashboardOwnerSession
import cc.monomer.dashboard.api.DashboardOwnerSessionPageCursor
import cc.monomer.dashboard.api.DashboardSessionId
import cc.monomer.dashboard.api.ResolveDashboardSessionCommand
import cc.monomer.dashboard.api.DashboardOwnerSessionResolution
import cc.monomer.dashboard.api.ListDashboardSessionQueryRunsCommand
import cc.monomer.dashboard.api.ListDashboardSessionsCommand
import cc.monomer.dashboard.api.ListDraftQueryRunsCommand
import cc.monomer.dashboard.api.OpenDashboardSessionCommand
import cc.monomer.dashboard.api.QueryRunId
import cc.monomer.dashboard.api.ReadDashboardDraftRunCommand
import cc.monomer.dashboard.api.ReadDashboardDraftResultCommand
import cc.monomer.dashboard.api.ReadDashboardSessionQueryRunCommand
import cc.monomer.dashboard.api.ReadDashboardSessionResultCommand
import cc.monomer.dashboard.api.RefreshDashboardSessionCommand
import cc.monomer.dashboard.api.RefreshDraftCommand
import cc.monomer.dashboard.api.ReplaceDraftDocumentCommand
import cc.monomer.dashboard.api.StartDraftCommand
import cc.monomer.dashboard.application.DashboardResultReadPort
import cc.monomer.identity.api.WorkspaceRole
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.Uuid

class AgentDashboardOwnerAdapter(
    private val dashboardOwnerPort: DashboardOwnerPort,
    private val scheduleObservation: DashboardScheduleObservation,
    private val resultReadPort: DashboardResultReadPort,
    private val controlAdmissionFacade: ControlAdmissionFacade,
    private val clock: Clock,
    private val provenanceNames: AgentProvenanceNames,
    private val draftMutationCallback: (DashboardId, String) -> Unit,
) : AgentDashboardOwnerPort {
    override suspend fun list(
        caller: AgentWorkspaceCaller,
        request: AgentListRequest,
    ): AgentListingOutcome {
        val admission = dashboardEditAdmission(caller)
        if (admission is ControlAdmissionDecision.Denied) return admission.toAgentListingDenial()
        if (AgentDashboardHistoryPaths.history.matches(request.path.value)) return listHistory(caller, request)
        if (AgentDashboardSchedulePaths.occurrences.matches(request.path.value)) return listOccurrences(caller, request)
        val cursor = try {
            request.cursor?.let { decodeCursor(request.path, it) }
        } catch (_: IllegalArgumentException) {
            return AgentListingOutcome.Rejected(AgentOwnerProblems.invalidCursor)
        }
        return agentOwnerGuard("AgentDashboardOwnerAdapter", AgentOwnerProblems.ownerUnavailable, AgentListingOutcome::Rejected) {
            when {
                request.path.value == AgentPathPatterns.DASHBOARD_COLLECTION -> {
                    val outcome = dashboardOwnerPort.listDashboards(
                        caller.workspaceId,
                        cursor?.let { DashboardOwnerDashboardPageCursor(it.createdAt, DashboardId(it.id)) },
                        request.limit,
                    )
                    when (outcome) {
                        is DashboardOwnerOutcome.Success -> AgentListingOutcome.Found(
                            AgentResourceListingResponse(
                                outcome.value.items.map { item ->
                                    AgentResourceListingItem.Dashboard(path = dashboardPath(item.dashboardId), title = item.title, description = item.description, updatedAt = item.updatedAt)
                                },
                                outcome.value.nextCursor?.let { encodeCursor(request.path, it.updatedAt, it.dashboardId.value) },
                                if (isDashboardEditAuthorized(caller)) {
                                    listOf(AgentAvailableOperation.Create(path = request.path, inputKind = "DASHBOARD_DEFINITION"))
                                } else {
                                    emptyList()
                                },
                            ),
                        )
                        else -> outcome.toListingOutcome()
                    }
                }
                AgentPathPatterns.dashboardDraftQueryRuns.matches(request.path.value) -> listDraftRuns(caller, request, cursor)
                AgentPathPatterns.dashboardSessions.matches(request.path.value) -> listSessions(caller, request, cursor)
                AgentPathPatterns.dashboardSessionQueryRuns.matches(request.path.value) -> listSessionRuns(caller, request, cursor)
                else -> AgentListingOutcome.NotFound
            }
        }
    }

    override suspend fun read(
        caller: AgentWorkspaceCaller,
        request: AgentReadRequest,
    ): AgentResourceOutcome {
        val admission = dashboardEditAdmission(caller)
        if (admission is ControlAdmissionDecision.Denied) return admission.toAgentResourceDenial()
        val dashboardId = dashboardId(request.path) ?: return AgentResourceOutcome.NotFound
        return agentOwnerGuard("AgentDashboardOwnerAdapter", AgentOwnerProblems.ownerUnavailable, AgentResourceOutcome::Rejected) {
            when {
                AgentDashboardHistoryPaths.version.matches(request.path.value) -> readVersion(caller, request)
                AgentDashboardSchedulePaths.schedules.matches(request.path.value) || AgentDashboardSchedulePaths.draftSchedules.matches(request.path.value) -> {
                    val body = scheduleObservation.schedules(caller.workspaceId, dashboardId, AgentDashboardSchedulePaths.draftSchedules.matches(request.path.value))
                        ?: return AgentResourceOutcome.NotFound
                    AgentResourceOutcome.Found(AgentResourceEnvelope.DashboardSchedules(request.path, AgentDashboardSchedulesBody(body), emptyList()))
                }
                AgentDashboardSchedulePaths.occurrence.matches(request.path.value) -> {
                    val segments = request.path.value.split('/')
                    val body = scheduleObservation.read(caller.workspaceId, dashboardId, segments[4], Instant.parse(segments[5]))
                        ?: return AgentResourceOutcome.NotFound
                    val session = body["sessionId"]?.jsonPrimitive?.contentOrNull
                    val operations = if (session == null) emptyList() else body.getValue("initialQueryRuns").jsonObject.values.map {
                        AgentAvailableOperation.Read(AgentResourcePath.parse("workspace/dashboards/$dashboardId/sessions/$session/query-runs/${it.jsonPrimitive.content}"))
                    }
                    AgentResourceOutcome.Found(AgentResourceEnvelope.DashboardScheduleOccurrence(request.path, AgentDashboardScheduleOccurrenceBody(body), operations))
                }
                AgentPathPatterns.dashboardDraftResult.matches(request.path.value) || AgentPathPatterns.dashboardSessionResult.matches(request.path.value) ->
                    readResult(caller, request.path)
                AgentPathPatterns.dashboard.matches(request.path.value) ->
                    dashboardOwnerPort.read(caller.workspaceId, dashboardId).toDashboardResourceOutcome(caller, request.path)
                AgentPathPatterns.dashboardDraft.matches(request.path.value) ->
                    dashboardOwnerPort.readDraft(caller.workspaceId, dashboardId).toDraftResourceOutcome(request.path)
                AgentPathPatterns.dashboardDraftStatus.matches(request.path.value) ->
                    dashboardOwnerPort.readDraftStatus(caller.workspaceId, dashboardId).toDraftStatusResourceOutcome(request.path)
                AgentPathPatterns.dashboardDraftQueryRun.matches(request.path.value) -> {
                    val queryRunId = queryRunId(request.path) ?: return AgentResourceOutcome.NotFound
                    dashboardOwnerPort.readDashboardDraftRun(
                        ReadDashboardDraftRunCommand(caller.workspaceId, dashboardId, queryRunId),
                    ).toQueryRunResourceOutcome(request.path, draft = true)
                }
                AgentPathPatterns.dashboardSession.matches(request.path.value) -> {
                    val sessionId = sessionId(request.path) ?: return AgentResourceOutcome.NotFound
                    dashboardOwnerPort.readSession(caller.workspaceId, dashboardId, sessionId)
                        .toSessionResourceOutcome(request.path)
                }
                AgentPathPatterns.dashboardSessionQueryRun.matches(request.path.value) -> {
                    val sessionId = sessionId(request.path) ?: return AgentResourceOutcome.NotFound
                    val queryRunId = queryRunId(request.path) ?: return AgentResourceOutcome.NotFound
                    dashboardOwnerPort.readSessionQueryRun(
                        ReadDashboardSessionQueryRunCommand(caller.workspaceId, dashboardId, sessionId, queryRunId),
                    ).toQueryRunResourceOutcome(request.path, draft = false)
                }
                else -> AgentResourceOutcome.NotFound
            }
        }
    }

    private suspend fun listOccurrences(caller: AgentWorkspaceCaller, request: AgentListRequest): AgentListingOutcome {
        val dashboardId = dashboardId(request.path) ?: return AgentListingOutcome.NotFound
        return agentOwnerGuard("AgentDashboardOwnerAdapter", AgentOwnerProblems.ownerUnavailable, AgentListingOutcome::Rejected) {
            try {
                val page = scheduleObservation.list(caller.workspaceId, dashboardId, request.cursor, request.limit)
                    ?: return AgentListingOutcome.NotFound
                AgentListingOutcome.Found(AgentResourceListingResponse(
                    page.getValue("items").jsonArray.map { item ->
                        val body = item.jsonObject
                        val path = AgentResourcePath.parse("${request.path.value}/${body.getValue("scheduleKey").jsonPrimitive.content}/${body.getValue("scheduledAt").jsonPrimitive.content}")
                        AgentResourceListingItem.DashboardScheduleOccurrence(path, cc.monomer.agent.api.AgentDashboardOccurrenceSummary(
                            scheduleKey = body.getValue("scheduleKey").jsonPrimitive.content,
                            scheduledAt = Instant.parse(body.getValue("scheduledAt").jsonPrimitive.content),
                            decidedAt = Instant.parse(body.getValue("decidedAt").jsonPrimitive.content),
                            outcome = body.getValue("outcome").jsonPrimitive.content,
                            reason = body["reason"]?.jsonPrimitive?.contentOrNull,
                            sessionId = body["sessionId"]?.jsonPrimitive?.contentOrNull?.let(Uuid::parse),
                            resultState = body["resultState"]?.jsonPrimitive?.contentOrNull,
                        ))
                    },
                    page["nextCursor"]?.jsonPrimitive?.contentOrNull,
                    emptyList(),
                ))
            } catch (_: cc.monomer.control.dashboard.DashboardOccurrenceCursorInvalidException) {
                AgentListingOutcome.Rejected(AgentOwnerProblems.invalidCursor)
            }
        }
    }

    override suspend fun create(
        caller: AgentWorkspaceCaller,
        request: AgentCreateRequest,
    ): AgentActionOutcome {
        val admission = dashboardEditAdmission(caller)
        if (admission is ControlAdmissionDecision.Denied) return admission.toAgentActionDenial()
        val input = request.input as? AgentCreateInput.Dashboard ?: return AgentActionOutcome.NotFound
        if (request.path.value != AgentPathPatterns.DASHBOARD_COLLECTION) return AgentActionOutcome.NotFound
        return agentOwnerGuard("AgentDashboardOwnerAdapter", AgentOwnerProblems.dashboardCreateUnconfirmed, AgentActionOutcome::Rejected) {
            when (val outcome = dashboardOwnerPort.create(
                CreateDashboardCommand(caller.workspaceId, input.definition, metadata(caller)),
            )) {
                is DashboardOwnerOutcome.Success -> completed(
                    operation = AgentWorkspaceOperation.CREATE,
                    requestPath = request.path,
                    action = null,
                    resultKind = AgentResourceKind.DASHBOARD,
                    resultPath = dashboardPath(outcome.value.dashboardId),
                )
                else -> outcome.toActionOutcome()
            }
        }
    }

    override suspend fun edit(
        caller: AgentWorkspaceCaller,
        request: AgentEditRequest,
    ): AgentActionOutcome {
        val admission = dashboardEditAdmission(caller)
        if (admission is ControlAdmissionDecision.Denied) return admission.toAgentActionDenial()
        val dashboardId = dashboardId(request.path) ?: return AgentActionOutcome.NotFound
        val expectedRevision = request.revision ?: return rejected(
            "REVISION_REQUIRED", "Dashboard edits require the current revision.",
        )
        return agentOwnerGuard("AgentDashboardOwnerAdapter", AgentOwnerProblems.ownerUnavailable, AgentActionOutcome::Rejected) {
            if (!AgentPathPatterns.dashboardDraft.matches(request.path.value)) return AgentActionOutcome.NotFound
            when (val current = dashboardOwnerPort.readDraft(caller.workspaceId, dashboardId)) {
                is DashboardOwnerOutcome.Success -> {
                    val document = JsonObject(
                        mapOf(
                            "definition" to current.value.definition,
                            "parameterBindings" to JsonObject(current.value.parameterBindings),
                            "variables" to JsonObject(current.value.variables),
                        ),
                    )
                    val patched = try {
                        AgentJsonPatch.apply(document, request.patch).jsonObject
                    } catch (exception: AgentJsonPatchException) {
                        return rejected(
                            "PATCH_INVALID",
                            "The Dashboard draft patch operation is invalid.",
                            violation = patchViolation(exception),
                        )
                    } catch (_: IllegalArgumentException) {
                        return rejected("PATCH_INVALID", "The Dashboard draft patch is invalid.")
                    }
                    val definition = patched["definition"]?.jsonObject ?: return rejected(
                        "PATCH_INVALID",
                        "The Dashboard draft patch must retain a definition object.",
                    )
                    val parameterBindings = patched["parameterBindings"]?.jsonObject ?: return rejected(
                        "PATCH_INVALID",
                        "The Dashboard draft patch must retain parameterBindings.",
                    )
                    val variables = patched["variables"]?.jsonObject ?: return rejected(
                        "PATCH_INVALID",
                        "The Dashboard draft patch must retain variables.",
                    )
                    if (patched.keys != setOf("definition", "parameterBindings", "variables")) return rejected(
                        "PATCH_INVALID",
                        "The Dashboard draft editable document is closed.",
                    )
                    when (val outcome = dashboardOwnerPort.replaceDraftDocument(
                        ReplaceDraftDocumentCommand(
                            workspaceId = caller.workspaceId,
                            dashboardId = dashboardId,
                            expectedRevision = expectedRevision.value,
                            definition = definition,
                            parameterBindings = parameterBindings,
                            variables = variables,
                            metadata = metadata(caller),
                        ),
                    )) {
                        is DashboardOwnerOutcome.Success -> {
                            draftMutationCallback(dashboardId, "DRAFT_CHANGED")
                            completed(
                                operation = AgentWorkspaceOperation.EDIT,
                                requestPath = request.path,
                                action = null,
                                resultKind = AgentResourceKind.DASHBOARD_DRAFT,
                                resultPath = request.path,
                            )
                        }
                        else -> outcome.toActionOutcome()
                    }
                }
                else -> current.toActionOutcome()
            }
        }
    }

    override suspend fun invoke(
        caller: AgentWorkspaceCaller,
        request: AgentInvokeRequest,
    ): AgentActionOutcome {
        val admission = dashboardEditAdmission(caller)
        if (admission is ControlAdmissionDecision.Denied) return admission.toAgentActionDenial()
        return agentOwnerGuard("AgentDashboardOwnerAdapter", AgentOwnerProblems.ownerUnavailable, AgentActionOutcome::Rejected) {
            when (val input = request.input) {
                is AgentInvokeInput.DashboardDraftStart -> start(caller, request, input.parameterBindings)
                is AgentInvokeInput.DashboardDraftApply -> apply(caller, request, input.expectedRevision)
                is AgentInvokeInput.DashboardDraftDiscard -> discard(caller, request, input.expectedRevision)
                is AgentInvokeInput.DashboardQueryRefresh -> refresh(caller, request, input.queryNames)
                is AgentInvokeInput.DashboardSessionResolve -> resolveSession(caller, request, input.variables, null)
                is AgentInvokeInput.DashboardSessionRefresh -> resolveSession(caller, request, input.variables, input.queryNames)
                is AgentInvokeInput.DashboardSessionOpen -> openSession(caller, request, input.parameterBindings)
                AgentInvokeInput.None -> when {
                    AgentPathPatterns.dashboard.matches(request.path.value) && request.action == "delete" -> deleteDashboard(caller, request)
                    AgentDashboardHistoryPaths.version.matches(request.path.value) && request.action == "restore" -> restoreDashboard(caller, request)
                    else -> cancel(caller, request)
                }
                is AgentInvokeInput.WorkspaceMemberAdd, is AgentInvokeInput.WorkspaceMemberRole,
                is AgentInvokeInput.PageDelete, is AgentInvokeInput.PageContentUploadComplete,
                is AgentInvokeInput.ConnectorCredentialRotate, is AgentInvokeInput.ExploreRun,
                is AgentInvokeInput.SemanticModelCheck, is AgentInvokeInput.MetricCheck -> AgentActionOutcome.NotFound
            }
        }
    }

    private suspend fun listHistory(caller: AgentWorkspaceCaller, request: AgentListRequest): AgentListingOutcome {
        return agentOwnerGuard("AgentDashboardOwnerAdapter", AgentOwnerProblems.ownerUnavailable, AgentListingOutcome::Rejected) {
            val id = dashboardId(request.path) ?: return AgentListingOutcome.NotFound
            val cursor = try { decodeDashboardHistoryCursor(request.cursor, caller.workspaceId, id) }
                catch (_: org.springframework.web.server.ResponseStatusException) { return AgentListingOutcome.Rejected(AgentOwnerProblems.invalidCursor) }
            when (val outcome = dashboardOwnerPort.listHistory(ListDashboardHistoryCommand(caller.workspaceId, id, cursor, request.limit))) {
                is DashboardOwnerOutcome.Success -> {
                    val page = outcome.value
                    val names = provenanceNames.read(page.changes.map { it.actorId }, page.changes.map { it.agentClientId })
                    AgentListingOutcome.Found(AgentResourceListingResponse(page.changes.map { version ->
                        AgentResourceListingItem.DashboardDefinitionVersion(AgentResourcePath.parse("${request.path.value}/${version.changeId}"),
                            id.value, page.name, page.deleted, version.changeId, version.action.name, version.occurredAt,
                            names.actor(version.actorId), version.credentialSurface, names.app(version.agentClientId), version.restorable)
                    }, page.nextCursor?.let(::encodeDashboardHistoryCursor), emptyList()))
                }
                else -> outcome.toListingOutcome()
            }
        }
    }

    private suspend fun readVersion(caller: AgentWorkspaceCaller, request: AgentReadRequest): AgentResourceOutcome {
        val id = dashboardId(request.path) ?: return AgentResourceOutcome.NotFound
        val changeId = Uuid.parse(request.path.value.substringAfterLast('/'))
        val version = when (val outcome = dashboardOwnerPort.readHistory(ReadDashboardHistoryCommand(caller.workspaceId, id, changeId))) {
            is DashboardOwnerOutcome.Success -> outcome.value
            else -> return outcome.toResourceOutcome()
        }
        val history = when (val outcome = dashboardOwnerPort.listHistory(ListDashboardHistoryCommand(caller.workspaceId, id, null, 1))) {
            is DashboardOwnerOutcome.Success -> outcome.value
            else -> return outcome.toResourceOutcome()
        }
        val names = provenanceNames.read(listOf(version.actorId), listOf(version.agentClientId))
        return AgentResourceOutcome.Found(AgentResourceEnvelope.DashboardDefinitionVersion(request.path,
            AgentDashboardDefinitionVersionBody(caller.workspaceId, id.value, version.title, history.deleted, version.changeId, version.action.name,
                version.definition, version.occurredAt, names.actor(version.actorId), version.credentialSurface, names.app(version.agentClientId), version.restorable),
            if (version.restorable) listOf(AgentAvailableOperation.Invoke(request.path, "restore", null)) else emptyList()))
    }

    private suspend fun deleteDashboard(caller: AgentWorkspaceCaller, request: AgentInvokeRequest): AgentActionOutcome {
        val admitted = when (val decision = controlAdmissionFacade.authorizeWorkspaceRole(WorkspaceRoleAdmissionRequest(
            caller.workspaceId, caller.presentedCredential, caller.expectedOAuthResource, WorkspaceRole.EXPLORER,
        ))) { is ControlAdmissionDecision.Admitted -> decision.context; is ControlAdmissionDecision.Denied -> return decision.toAgentActionDenial() }
        val id = dashboardId(request.path) ?: return AgentActionOutcome.NotFound
        return when (val outcome = dashboardOwnerPort.delete(DeleteDashboardCommand(caller.workspaceId, id, DashboardDeletionMetadata(
            admitted.actor.id.toString(), admitted.organizationId, caller.presentedCredential, caller.expectedOAuthResource,
            clock.instant(), cc.monomer.identity.api.CredentialSurface.AGENT, admitted.agentClientId,
        )))) {
            is DashboardOwnerOutcome.Success -> AgentActionOutcome.Completed(AgentActionResult.Invoke(request.path, request.action,
                AgentActionStatus.COMPLETED, AgentActionResultReference(AgentResourceKind.COLLECTION, AgentResourcePath.parse(AgentPathPatterns.DASHBOARD_COLLECTION))))
            else -> outcome.toActionOutcome()
        }
    }

    private suspend fun restoreDashboard(caller: AgentWorkspaceCaller, request: AgentInvokeRequest): AgentActionOutcome {
        val id = dashboardId(request.path) ?: return AgentActionOutcome.NotFound
        return when (val outcome = dashboardOwnerPort.restore(RestoreDashboardCommand(caller.workspaceId, id,
            Uuid.parse(request.path.value.substringAfterLast('/')), metadata(caller)))) {
            is DashboardOwnerOutcome.Success -> AgentActionOutcome.Completed(AgentActionResult.Invoke(request.path, request.action,
                AgentActionStatus.COMPLETED, AgentActionResultReference(AgentResourceKind.DASHBOARD, dashboardPath(id))))
            else -> outcome.toActionOutcome()
        }
    }

    private suspend fun start(
        caller: AgentWorkspaceCaller,
        request: AgentInvokeRequest,
        parameterBindings: Map<String, kotlinx.serialization.json.JsonElement>,
    ): AgentActionOutcome {
        val dashboardId = dashboardId(request.path) ?: return AgentActionOutcome.NotFound
        if (!AgentPathPatterns.dashboardDraft.matches(request.path.value)) return AgentActionOutcome.NotFound
        return dashboardOwnerPort.startDraft(
            StartDraftCommand(caller.workspaceId, dashboardId, parameterBindings, metadata(caller)),
        ).toDraftActionOutcome(caller, request, "DRAFT_STARTED") { draftMutationCallback(dashboardId, "DRAFT_STARTED") }
    }

    private suspend fun apply(
        caller: AgentWorkspaceCaller,
        request: AgentInvokeRequest,
        expectedRevision: Long,
    ): AgentActionOutcome {
        val dashboardId = dashboardId(request.path) ?: return AgentActionOutcome.NotFound
        if (!AgentPathPatterns.dashboardDraft.matches(request.path.value)) return AgentActionOutcome.NotFound
        return dashboardOwnerPort.applyDraft(
            ApplyDraftCommand(caller.workspaceId, dashboardId, expectedRevision, metadata(caller)),
        ).toDashboardActionOutcome(caller, request) { draftMutationCallback(dashboardId, "DRAFT_APPLIED") }
    }

    private suspend fun discard(
        caller: AgentWorkspaceCaller,
        request: AgentInvokeRequest,
        expectedRevision: Long,
    ): AgentActionOutcome {
        val dashboardId = dashboardId(request.path) ?: return AgentActionOutcome.NotFound
        if (!AgentPathPatterns.dashboardDraft.matches(request.path.value)) return AgentActionOutcome.NotFound
        return when (val outcome = dashboardOwnerPort.discardDraft(
            DiscardDraftCommand(caller.workspaceId, dashboardId, expectedRevision, metadata(caller)),
        )) {
            is DashboardOwnerOutcome.Success -> {
                draftMutationCallback(dashboardId, "DRAFT_DISCARDED")
                AgentActionOutcome.Completed(
                    AgentActionResult.Invoke(
                        path = request.path,
                        action = request.action,
                        status = AgentActionStatus.COMPLETED,
                        result = AgentActionResultReference(AgentResourceKind.DASHBOARD_DRAFT, draftPath(outcome.value.dashboardId)),
                    ),
                )
            }
            else -> outcome.toActionOutcome()
        }
    }

    private suspend fun refresh(
        caller: AgentWorkspaceCaller,
        request: AgentInvokeRequest,
        queryNames: Set<String>,
    ): AgentActionOutcome {
        val dashboardId = dashboardId(request.path) ?: return AgentActionOutcome.NotFound
        if (!AgentPathPatterns.dashboardDraft.matches(request.path.value)) return AgentActionOutcome.NotFound
        return dashboardOwnerPort.refreshDraft(
            RefreshDraftCommand(caller.workspaceId, dashboardId, queryNames, metadata(caller)),
        ).toDraftActionOutcome(caller, request, "DRAFT_REFRESH_STARTED") { draftMutationCallback(dashboardId, "DRAFT_REFRESH_STARTED") }
    }

    private suspend fun resolveSession(
        caller: AgentWorkspaceCaller,
        request: AgentInvokeRequest,
        variables: Map<String, kotlinx.serialization.json.JsonElement>,
        queryNames: Set<String>?,
    ): AgentActionOutcome {
        val dashboardId = dashboardId(request.path) ?: return AgentActionOutcome.NotFound
        val sessionId = sessionId(request.path) ?: return AgentActionOutcome.NotFound
        if (!AgentPathPatterns.dashboardSession.matches(request.path.value)) return AgentActionOutcome.NotFound
        val outcome = if (queryNames == null) dashboardOwnerPort.resolveSession(
            ResolveDashboardSessionCommand(caller.workspaceId, dashboardId, sessionId, variables, metadata(caller)),
        ) else dashboardOwnerPort.refreshSession(
            RefreshDashboardSessionCommand(caller.workspaceId, dashboardId, sessionId, variables, queryNames, metadata(caller)),
        )
        return outcome.toSessionActionOutcome(request)
    }

    private suspend fun openSession(
        caller: AgentWorkspaceCaller,
        request: AgentInvokeRequest,
        parameterBindings: Map<String, kotlinx.serialization.json.JsonElement>,
    ): AgentActionOutcome {
        val dashboardId = dashboardId(request.path) ?: return AgentActionOutcome.NotFound
        if (!AgentPathPatterns.dashboardSessions.matches(request.path.value)) return AgentActionOutcome.NotFound
        return when (val outcome = dashboardOwnerPort.openSession(
            OpenDashboardSessionCommand(caller.workspaceId, dashboardId, parameterBindings, metadata(caller)),
        )) {
            is DashboardOwnerOutcome.Success -> AgentActionOutcome.Completed(
                AgentActionResult.Invoke(path = request.path, action = request.action, status = AgentActionStatus.ACCEPTED, result = AgentActionResultReference(
                        AgentResourceKind.DASHBOARD_SESSION,
                        sessionPath(dashboardId, outcome.value.sessionId),
                    )),
            )
            else -> outcome.toActionOutcome()
        }
    }

    private suspend fun cancel(
        caller: AgentWorkspaceCaller,
        request: AgentInvokeRequest,
    ): AgentActionOutcome {
        val dashboardId = dashboardId(request.path) ?: return AgentActionOutcome.NotFound
        val queryRunId = queryRunId(request.path) ?: return AgentActionOutcome.NotFound
        if (!AgentPathPatterns.dashboardDraftQueryRun.matches(request.path.value)) return AgentActionOutcome.NotFound
        return dashboardOwnerPort.cancelDashboardDraftRun(
            CancelDashboardDraftRunCommand(caller.workspaceId, dashboardId, queryRunId, metadata(caller)),
        ).let { outcome ->
            when (outcome) {
                is DashboardOwnerOutcome.Success -> {
                    draftMutationCallback(dashboardId, "DRAFT_QUERY_RUN_CANCEL_REQUESTED")
                    AgentActionOutcome.Completed(
                        AgentActionResult.Invoke(path = request.path, action = request.action, status = AgentActionStatus.ACCEPTED, result = AgentActionResultReference(AgentResourceKind.DASHBOARD_QUERY_RUN, request.path)),
                    )
                }
                else -> outcome.toActionOutcome()
            }
        }
    }

    private fun completed(
        operation: AgentWorkspaceOperation,
        requestPath: AgentResourcePath,
        action: String?,
        resultKind: AgentResourceKind,
        resultPath: AgentResourcePath,
    ): AgentActionOutcome = AgentActionOutcome.Completed(
        when (operation) {
            AgentWorkspaceOperation.CREATE -> AgentActionResult.Create(requestPath, AgentActionResultReference(resultKind, resultPath))
            AgentWorkspaceOperation.EDIT -> AgentActionResult.Edit(requestPath, AgentActionResultReference(resultKind, resultPath))
            AgentWorkspaceOperation.INVOKE -> AgentActionResult.Invoke(requestPath, requireNotNull(action), AgentActionStatus.COMPLETED, AgentActionResultReference(resultKind, resultPath))
            else -> error("unsupported Dashboard command")
        },
    )

    private suspend fun listDraftRuns(
        caller: AgentWorkspaceCaller,
        request: AgentListRequest,
        cursor: CursorBoundary?,
    ): AgentListingOutcome {
        val dashboardId = dashboardId(request.path) ?: return AgentListingOutcome.NotFound
        return when (val outcome = dashboardOwnerPort.listDraftQueryRuns(
            ListDraftQueryRunsCommand(
                caller.workspaceId,
                dashboardId,
                cursor?.let { DashboardOwnerQueryRunPageCursor(it.createdAt, QueryRunId(it.id)) },
                request.limit,
            ),
        )) {
            is DashboardOwnerOutcome.Success -> AgentListingOutcome.Found(
                AgentResourceListingResponse(
                    outcome.value.items.map { run -> runListingItem(run, draftRunPath(dashboardId, run.queryRunId)) },
                    outcome.value.nextCursor?.let { encodeCursor(request.path, it.createdAt, it.queryRunId.value) },
                    emptyList(),
                ),
            )
            else -> outcome.toListingOutcome()
        }
    }

    private suspend fun listSessions(
        caller: AgentWorkspaceCaller,
        request: AgentListRequest,
        cursor: CursorBoundary?,
    ): AgentListingOutcome {
        val dashboardId = dashboardId(request.path) ?: return AgentListingOutcome.NotFound
        return when (val outcome = dashboardOwnerPort.listSessions(
            ListDashboardSessionsCommand(
                caller.workspaceId,
                dashboardId,
                cursor?.let { DashboardOwnerSessionPageCursor(dashboardId, it.createdAt, DashboardSessionId(it.id)) },
                request.limit,
            ),
        )) {
            is DashboardOwnerOutcome.Success -> AgentListingOutcome.Found(
                AgentResourceListingResponse(
                    outcome.value.items.map { item ->
                        AgentResourceListingItem.DashboardSession(path = sessionPath(dashboardId, item.sessionId), parameterBindings = item.parameterBindings, runningQueryRuns = item.runningQueryRuns.map { run ->
                                AgentDashboardSessionCurrentQueryRun(
                                    run.queryName,
                                    sessionRunPath(dashboardId, item.sessionId, run.queryRunId),
                                    run.state,
                                )
                            }, createdAt = item.createdAt, updatedAt = item.updatedAt, lastQueryRunAt = item.lastQueryRunAt)
                    },
                    outcome.value.nextCursor?.let { encodeCursor(request.path, it.createdAt, it.sessionId.value) },
                    listOf(AgentAvailableOperation.Invoke(path = request.path, action = "open", inputKind = "DASHBOARD_SESSION_OPEN")),
                ),
            )
            else -> outcome.toListingOutcome()
        }
    }

    private suspend fun listSessionRuns(
        caller: AgentWorkspaceCaller,
        request: AgentListRequest,
        cursor: CursorBoundary?,
    ): AgentListingOutcome {
        val dashboardId = dashboardId(request.path) ?: return AgentListingOutcome.NotFound
        val sessionId = sessionId(request.path) ?: return AgentListingOutcome.NotFound
        return when (val outcome = dashboardOwnerPort.listSessionQueryRuns(
            ListDashboardSessionQueryRunsCommand(
                caller.workspaceId,
                dashboardId,
                sessionId,
                cursor?.let { DashboardOwnerQueryRunPageCursor(it.createdAt, QueryRunId(it.id)) },
                request.limit,
            ),
        )) {
            is DashboardOwnerOutcome.Success -> AgentListingOutcome.Found(
                AgentResourceListingResponse(
                    outcome.value.items.map { run ->
                        runListingItem(run, sessionRunPath(dashboardId, sessionId, run.queryRunId))
                    },
                    outcome.value.nextCursor?.let { encodeCursor(request.path, it.createdAt, it.queryRunId.value) },
                    emptyList(),
                ),
            )
            else -> outcome.toListingOutcome()
        }
    }

    private suspend fun readResult(
        caller: AgentWorkspaceCaller,
        path: AgentResourcePath,
    ): AgentResourceOutcome {
        val dashboardId = dashboardId(path) ?: return AgentResourceOutcome.NotFound
        val queryRunId = queryRunId(path) ?: return AgentResourceOutcome.NotFound
        val lookup = if (AgentPathPatterns.dashboardDraftResult.matches(path.value)) {
            dashboardOwnerPort.readDashboardDraftResult(
                ReadDashboardDraftResultCommand(caller.workspaceId, dashboardId, queryRunId),
            )
        } else {
            val sessionId = sessionId(path) ?: return AgentResourceOutcome.NotFound
            dashboardOwnerPort.readSessionResult(
                ReadDashboardSessionResultCommand(caller.workspaceId, dashboardId, sessionId, queryRunId),
            )
        }
        return when (lookup) {
            is DashboardOwnerResultLookupOutcome.Success -> {
            val payload = try {
                resultReadPort.read(lookup.value.resultManifest)
                    .withQueryColumns(lookup.value.queryRun.queryType, lookup.value.queryRun.outputColumnNames, lookup.value.queryRun.outputColumnDisplays)
            } catch (_: DashboardResultUnavailableException) {
                return AgentResourceOutcome.Rejected(
                    rejectedProblem("RESULT_UNAVAILABLE", "The Dashboard result is unavailable.", AgentWorkspaceProblemStatus.SERVICE_UNAVAILABLE),
                )
            }
            AgentResourceOutcome.Found(
                AgentResourceEnvelope.DashboardQueryResult(path = path, body = AgentDashboardResultResourceBody(
                        lookup.value.queryRun.queryType,
                        payload.columns.map { column -> AgentDashboardResultColumn(column.name, column.logicalType, column.nullable, display = column.display.toAgentDisplay()) },
                        payload.rows,
                        lookup.value.resultManifest.rowCount.toString(),
                        payload.rows.size,
                        payload.rows.size.toULong() < lookup.value.resultManifest.rowCount,
                        lookup.value.queryRun.reportingTimeZone,
                    ), availableOperations = emptyList()),
            )
            }
            DashboardOwnerResultLookupOutcome.NotFound -> AgentResourceOutcome.NotFound
            is DashboardOwnerResultLookupOutcome.Rejected -> AgentResourceOutcome.Rejected(
                rejectedProblem(lookup.code, dashboardQueryFailureMessage(lookup.code, null) ?: "The Dashboard result is unavailable.", AgentWorkspaceProblemStatus.CONFLICT),
            )
        }
    }

    private fun DashboardOwnerOutcome<DashboardOwnerDashboard>.toDashboardActionOutcome(
        caller: AgentWorkspaceCaller,
        request: AgentInvokeRequest,
        afterCommit: () -> Unit,
    ): AgentActionOutcome = when (this) {
        is DashboardOwnerOutcome.Success -> {
            afterCommit()
            completed(
                operation = AgentWorkspaceOperation.INVOKE,
                requestPath = request.path,
                action = request.action,
                resultKind = AgentResourceKind.DASHBOARD,
                resultPath = dashboardPath(value.dashboardId),
            )
        }
        else -> toActionOutcome()
    }

    private fun DashboardOwnerOutcome<DashboardOwnerDraft>.toDraftActionOutcome(
        caller: AgentWorkspaceCaller,
        request: AgentInvokeRequest,
        reason: String,
        afterCommit: () -> Unit,
    ): AgentActionOutcome = when (this) {
        is DashboardOwnerOutcome.Success -> {
            afterCommit()
            AgentActionOutcome.Completed(
                AgentActionResult.Invoke(path = request.path, action = request.action, status = AgentActionStatus.ACCEPTED, result = AgentActionResultReference(AgentResourceKind.DASHBOARD_DRAFT, draftPath(value.dashboardId))),
            )
        }
        else -> toActionOutcome()
    }

    private fun DashboardOwnerOutcome<DashboardOwnerSessionResolution>.toSessionActionOutcome(
        request: AgentInvokeRequest,
    ): AgentActionOutcome = when (this) {
        is DashboardOwnerOutcome.Success -> AgentActionOutcome.Completed(
            AgentActionResult.DashboardSessionResolution(
                path = request.path, action = request.action, status = AgentActionStatus.ACCEPTED,
                result = AgentActionResultReference(AgentResourceKind.DASHBOARD_SESSION, request.path),
                variables = value.variables,
                queryRuns = value.queryRuns.mapValues { (_, runId) -> AgentResourcePath.parse("${request.path.value}/query-runs/${runId.value}") },
            ),
        )
        else -> toActionOutcome()
    }

    private suspend fun DashboardOwnerOutcome<DashboardOwnerDashboard>.toDashboardResourceOutcome(
        caller: AgentWorkspaceCaller,
        path: AgentResourcePath,
    ): AgentResourceOutcome = when (this) {
        is DashboardOwnerOutcome.Success -> AgentResourceOutcome.Found(
            AgentResourceEnvelope.Dashboard(path = path, body = value.toAgentBody(provenanceNames.read(listOf(value.updatedBy), listOf(value.agentClientId))), availableOperations = dashboardAvailableOperations(
                    caller,
                    value.dashboardId,
                )),
        )
        else -> toResourceOutcome()
    }

    private suspend fun DashboardOwnerOutcome<DashboardOwnerDraft>.toDraftResourceOutcome(
        path: AgentResourcePath,
    ): AgentResourceOutcome = when (this) {
        is DashboardOwnerOutcome.Success -> AgentResourceOutcome.Found(
            AgentResourceEnvelope.DashboardDraft(path = path, revision = AgentRevision(value.authoringRevision), body = value.toAgentBody(provenanceNames.read(listOf(value.lastEditedBy), listOf(value.lastEditAgentClientId))), availableOperations = draftAvailableOperations(value)),
        )
        else -> toResourceOutcome()
    }

    private fun DashboardOwnerOutcome<DashboardOwnerDraftStatus>.toDraftStatusResourceOutcome(
        path: AgentResourcePath,
    ): AgentResourceOutcome = when (this) {
        is DashboardOwnerOutcome.Success -> AgentResourceOutcome.Found(
            AgentResourceEnvelope.DashboardDraftStatus(path, AgentDashboardDraftStatusBody(
                draftRevision = AgentRevision(value.authoringRevision),
                targetQueryRuns = value.targetQueryRuns.mapValues { (_, target) ->
                    AgentDashboardDraftTargetQueryRun(draftRunPath(value.dashboardId, target.queryRunId), AgentRunState.valueOf(target.state))
                },
                readyResults = value.readyResults.mapValues { (_, ready) -> ready.toAgentReadyResult(value.dashboardId) },
            )),
        )
        else -> toResourceOutcome()
    }

    private fun DashboardOwnerOutcome<DashboardOwnerSession>.toSessionResourceOutcome(
        path: AgentResourcePath,
    ): AgentResourceOutcome = when (this) {
        is DashboardOwnerOutcome.Success -> AgentResourceOutcome.Found(
            AgentResourceEnvelope.DashboardSession(path = path, body = value.toAgentBody(), availableOperations = buildList {
                    add(AgentAvailableOperation.Invoke(path = path, action = "resolve", inputKind = "DASHBOARD_SESSION_RESOLVE"))
                    if (value.definition["queries"]?.jsonObject?.isNotEmpty() == true) {
                        add(AgentAvailableOperation.Invoke(path = path, action = "refresh", inputKind = "DASHBOARD_SESSION_REFRESH"))
                    }
                    add(AgentAvailableOperation.List(path = AgentResourcePath.parse("${path.value}/query-runs")))
                }),
        )
        else -> toResourceOutcome()
    }

    private suspend fun DashboardOwnerOutcome<DashboardOwnerQueryRun>.toQueryRunResourceOutcome(
        path: AgentResourcePath,
        draft: Boolean,
    ): AgentResourceOutcome = when (this) {
        is DashboardOwnerOutcome.Success -> AgentResourceOutcome.Found(
            AgentResourceEnvelope.DashboardQueryRun(path = path, body = value.toAgentBody(provenanceNames.read(emptyList(), listOf(value.agentClientId)).app(value.agentClientId)), availableOperations = when {
                    draft && value.state == "RUNNING" -> listOf(
                        AgentAvailableOperation.Invoke(path = path, action = "cancel", inputKind = null),
                    )
                    value.state == "SUCCEEDED" && value.resultAvailable -> listOf(
                        AgentAvailableOperation.Read(path = AgentResourcePath.parse("${path.value}/result")),
                    )
                    else -> emptyList()
                }),
        )
        else -> toResourceOutcome()
    }

    private fun DashboardOwnerOutcome.Rejected.safeDetail(): String = when (code) {
        "INVALID_DASHBOARD_DEFINITION" -> message ?: "The Dashboard definition is invalid."
        "INVALID_DRAFT_VALUE", "INVALID_VARIABLE_VALUE", "INVALID_DRAFT_PARAMETERS", "INVALID_SESSION_PARAMETERS",
        "INVALID_SEMANTIC_QUERY", "UNSUPPORTED_SEMANTIC_QUERY" -> message ?: OWNER_REJECTION_DETAILS[code] ?: "The Dashboard input is invalid."
        "DRAFT_DISCARD_INPUT_MISSING" -> message ?: "Restore the applied inputs in the Draft, then discard again."
        "DASHBOARD_NOT_RESTORABLE" -> message ?: "This Dashboard version cannot be restored."
        else -> dashboardQueryFailureMessage(code, null) ?: OWNER_REJECTION_DETAILS[code] ?: "The Dashboard operation was rejected."
    }

    private fun <T> DashboardOwnerOutcome<T>.toResourceOutcome(): AgentResourceOutcome = when (this) {
        DashboardOwnerOutcome.NotFound -> AgentResourceOutcome.NotFound
        is DashboardOwnerOutcome.Conflict -> AgentResourceOutcome.Rejected(
            rejectedProblem("REVISION_CONFLICT", "The Dashboard revision is stale.", AgentWorkspaceProblemStatus.CONFLICT, currentRevision),
        )
        is DashboardOwnerOutcome.Rejected -> AgentResourceOutcome.Rejected(toProblem())
        is DashboardOwnerOutcome.Success -> error("successful owner outcome must be mapped")
    }

    private fun <T> DashboardOwnerOutcome<T>.toActionOutcome(): AgentActionOutcome = when (this) {
        DashboardOwnerOutcome.NotFound -> AgentActionOutcome.NotFound
        is DashboardOwnerOutcome.Conflict -> AgentActionOutcome.Rejected(
            rejectedProblem("REVISION_CONFLICT", "The Dashboard revision is stale.", AgentWorkspaceProblemStatus.CONFLICT, currentRevision),
        )
        is DashboardOwnerOutcome.Rejected -> AgentActionOutcome.Rejected(toProblem())
        is DashboardOwnerOutcome.Success -> error("successful owner outcome must be mapped")
    }

    private fun <T> DashboardOwnerOutcome<T>.toListingOutcome(): AgentListingOutcome = when (this) {
        DashboardOwnerOutcome.NotFound -> AgentListingOutcome.NotFound
        is DashboardOwnerOutcome.Conflict -> AgentListingOutcome.Rejected(
            rejectedProblem("REVISION_CONFLICT", "The Dashboard revision is stale.", AgentWorkspaceProblemStatus.CONFLICT, currentRevision),
        )
        is DashboardOwnerOutcome.Rejected -> AgentListingOutcome.Rejected(
            toProblem(),
        )
        is DashboardOwnerOutcome.Success -> error("successful owner outcome must be mapped")
    }

    private fun DashboardOwnerOutcome.Rejected.toProblem() = rejectedProblem(code, safeDetail(), when (code) {
        "DASHBOARD_DELETE_RETRY_REQUIRED", "EXECUTION_BACKPRESSURED" -> AgentWorkspaceProblemStatus.SERVICE_UNAVAILABLE
        "IDEMPOTENCY_CONFLICT", "DASHBOARD_NOT_RESTORABLE" -> AgentWorkspaceProblemStatus.CONFLICT
        in CONCURRENCY_CONFLICT_CODES -> AgentWorkspaceProblemStatus.CONFLICT
        else -> AgentWorkspaceProblemStatus.BAD_REQUEST
    })

    private fun DashboardOwnerDashboard.toAgentBody(provenance: AgentProvenance) = AgentDashboardResourceBody(
        definition = definition,
        createdAt = createdAt,
        updatedAt = updatedAt,
        updatedBy = provenance.actor(updatedBy),
        credentialSurface = credentialSurface,
        agentApp = provenance.app(agentClientId),
    )

    private fun DashboardOwnerDraft.toAgentBody(provenance: AgentProvenance) = AgentDashboardDraftResourceBody(
        definition = definition,
        parameterBindings = parameterBindings,
        variables = variables,
        targetQueryRuns = targetQueryRuns.mapValues { (_, value) -> draftRunPath(dashboardId, value) },
        readyResults = readyResults.mapValues { (_, value) -> value.toAgentReadyResult(dashboardId) },
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastEditedBy = provenance.actor(lastEditedBy),
        lastEditedAt = lastEditedAt,
        lastEditSurface = lastEditSurface,
        lastEditAgentApp = provenance.app(lastEditAgentClientId),
    )

    private fun DashboardOwnerReadyResult.toAgentReadyResult(dashboardId: DashboardId) = AgentDashboardReadyResult(
        authoringRevision = AgentRevision(authoringRevision),
        parameterBindings = parameterBindings,
        variables = variables,
        queryRuns = queryRuns.mapValues { (_, runId) -> draftRunPath(dashboardId, runId) },
    )

    private fun DashboardOwnerQueryRun.toAgentBody(agentApp: cc.monomer.agent.api.AgentApp?) = AgentDashboardQueryRunBody(
        queryName = queryName,
        queryType = queryType,
        connectorId = connectorId.value,
        state = state,
        failureCode = failureCode,
        failureDetail = failureMessage,
        outputColumnNames = outputColumnNames,
        createdAt = createdAt,
        updatedAt = updatedAt,
        credentialSurface = credentialSurface,
        agentApp = agentApp,
    )

    private fun DashboardOwnerSession.toAgentBody() = AgentDashboardSessionResourceBody(
        definition = definition,
        parameterBindings = parameterBindings,
        initialVariables = initialVariables,
        runningQueryRuns = runningQueryRuns.map { run ->
            AgentDashboardSessionCurrentQueryRun(run.queryName, sessionRunPath(dashboardId, sessionId, run.queryRunId), run.state)
        },
    )

    private suspend fun dashboardAvailableOperations(caller: AgentWorkspaceCaller, dashboardId: DashboardId) = buildList {
        if (isDashboardEditAuthorized(caller)) {
            if (dashboardOwnerPort.readDraft(caller.workspaceId, dashboardId) is DashboardOwnerOutcome.Success) {
                add(AgentAvailableOperation.Read(path = draftPath(dashboardId)))
            }
            add(AgentAvailableOperation.Invoke(path = draftPath(dashboardId), action = "start", inputKind = "DASHBOARD_DRAFT_START"))
        }
        add(AgentAvailableOperation.Read(path = AgentResourcePath.parse("${dashboardPath(dashboardId).value}/schedules")))
        add(AgentAvailableOperation.List(path = AgentResourcePath.parse("${dashboardPath(dashboardId).value}/occurrences")))
        add(AgentAvailableOperation.List(AgentResourcePath.parse("${dashboardPath(dashboardId).value}/history")))
        add(AgentAvailableOperation.Invoke(dashboardPath(dashboardId), "delete", null))
        add(AgentAvailableOperation.List(path = sessionCollectionPath(dashboardId)))
        add(AgentAvailableOperation.Invoke(path = sessionCollectionPath(dashboardId), action = "open", inputKind = "DASHBOARD_SESSION_OPEN"))
    }

    private fun draftAvailableOperations(draft: DashboardOwnerDraft) = buildList {
        add(AgentAvailableOperation.Read(path = draftStatusPath(draft.dashboardId)))
        add(AgentAvailableOperation.Read(path = AgentResourcePath.parse("${draftPath(draft.dashboardId).value}/schedules")))
        add(AgentAvailableOperation.Edit(path = draftPath(draft.dashboardId)))
        add(AgentAvailableOperation.Invoke(path = draftPath(draft.dashboardId), action = "apply", inputKind = "DASHBOARD_DRAFT_APPLY"))
        add(AgentAvailableOperation.Invoke(path = draftPath(draft.dashboardId), action = "discard", inputKind = "DASHBOARD_DRAFT_DISCARD"))
        if (draft.definition["queries"]?.jsonObject?.isNotEmpty() == true) {
            add(AgentAvailableOperation.Invoke(path = draftPath(draft.dashboardId), action = "refresh", inputKind = "DASHBOARD_QUERY_REFRESH"))
        }
        add(AgentAvailableOperation.List(path = draftRunCollectionPath(draft.dashboardId)))
    }

    private fun metadata(caller: AgentWorkspaceCaller) = DashboardOwnerCommandMetadata(
        actorId = caller.actorPrincipalId,
        credential = caller.presentedCredential,
        expectedOAuthResource = caller.expectedOAuthResource,
        idempotencyKey = caller.requestId.value,
        requestedAt = clock.instant(),
        credentialSurface = cc.monomer.identity.api.CredentialSurface.AGENT,
        agentClientId = caller.agentClientId,
    )

    private suspend fun dashboardEditAdmission(caller: AgentWorkspaceCaller) =
        controlAdmissionFacade.authorizeWorkspaceRole(
            WorkspaceRoleAdmissionRequest(
                workspaceId = caller.workspaceId,
                credential = caller.presentedCredential,
                expectedOAuthResource = caller.expectedOAuthResource,
                requiredRole = WorkspaceRole.EXPLORER,
            ),
        )

    private suspend fun isDashboardEditAuthorized(caller: AgentWorkspaceCaller): Boolean =
        dashboardEditAdmission(caller) is ControlAdmissionDecision.Admitted

    private fun dashboardId(path: AgentResourcePath): DashboardId? =
        DASHBOARD_ID_PATTERN.find(path.value)?.groupValues?.get(1)?.let { DashboardId(Uuid.parse(it)) }

    private fun queryRunId(path: AgentResourcePath): QueryRunId? =
        QUERY_RUN_ID_PATTERN.find(path.value)?.groupValues?.drop(1)?.lastOrNull(String::isNotEmpty)
            ?.let { QueryRunId(Uuid.parse(it)) }

    private fun sessionId(path: AgentResourcePath): DashboardSessionId? =
        SESSION_ID_PATTERN.find(path.value)?.groupValues?.get(2)?.let { DashboardSessionId(Uuid.parse(it)) }

    private fun dashboardPath(id: DashboardId): AgentResourcePath = AgentResourcePath.parse("workspace/dashboards/${id.value}")
    private fun draftPath(id: DashboardId): AgentResourcePath = AgentResourcePath.parse("workspace/dashboards/${id.value}/draft")
    private fun draftStatusPath(id: DashboardId) = AgentResourcePath.parse("${draftPath(id).value}/status")
    private fun draftRunCollectionPath(id: DashboardId) = AgentResourcePath.parse("${draftPath(id).value}/query-runs")
    private fun draftRunPath(id: DashboardId, runId: QueryRunId) = AgentResourcePath.parse("${draftRunCollectionPath(id).value}/${runId.value}")
    private fun sessionCollectionPath(id: DashboardId) = AgentResourcePath.parse("${dashboardPath(id).value}/sessions")
    private fun sessionPath(id: DashboardId, sessionId: DashboardSessionId) =
        AgentResourcePath.parse("${sessionCollectionPath(id).value}/${sessionId.value}")
    private fun sessionRunPath(id: DashboardId, sessionId: DashboardSessionId, runId: QueryRunId) =
        AgentResourcePath.parse("${sessionPath(id, sessionId).value}/query-runs/${runId.value}")

    private fun runListingItem(run: DashboardOwnerQueryRun, path: AgentResourcePath) = AgentResourceListingItem.DashboardQueryRun(path = path, queryName = run.queryName, queryType = run.queryType, connectorId = run.connectorId.value, state = cc.monomer.agent.api.AgentRunState.valueOf(run.state), createdAt = run.createdAt, updatedAt = run.updatedAt)

    private fun encodeCursor(path: AgentResourcePath, createdAt: Instant, id: Uuid): String {
        val payload = "1|${path.value}|$createdAt|$id"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeCursor(path: AgentResourcePath, cursor: String): CursorBoundary {
        val decoded = String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
        val parts = decoded.split('|')
        require(parts.size == 4 && parts[0] == "1" && parts[1] == path.value)
        val boundary = CursorBoundary(Instant.parse(parts[2]), Uuid.parse(parts[3]))
        require(encodeCursor(path, boundary.createdAt, boundary.id) == cursor)
        return boundary
    }

    private data class CursorBoundary(val createdAt: Instant, val id: Uuid)

    private fun rejected(
        code: String,
        detail: String,
        status: AgentWorkspaceProblemStatus = AgentWorkspaceProblemStatus.BAD_REQUEST,
        violation: AgentProblemViolation? = null,
    ) = AgentActionOutcome.Rejected(rejectedProblem(code, detail, status, violation = violation))

    private fun rejectedProblem(
        code: String,
        detail: String,
        status: AgentWorkspaceProblemStatus = AgentWorkspaceProblemStatus.BAD_REQUEST,
        currentRevision: Long? = null,
        violation: AgentProblemViolation? = null,
    ) = AgentWorkspaceProblem(
        code = code,
        title = code.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase),
        detail = detail,
        status = status,
        retryable = status == AgentWorkspaceProblemStatus.SERVICE_UNAVAILABLE || code in CONCURRENCY_CONFLICT_CODES,
        currentRevision = currentRevision?.let(::AgentRevision),
        violations = listOfNotNull(violation),
        hints = emptyList(),
        nextActions = emptyList(),
        retryAfterSeconds = null,
        roleRequirement = null,
        partitionFilters = emptyList(),
    )

    private fun patchViolation(exception: AgentJsonPatchException) = AgentProblemViolation(
        field = "patch[${exception.operationIndex}]",
        code = "PATCH_OPERATION_INVALID",
        message = "The JSON Patch operation cannot be applied to the current document.",
        operationIndex = exception.operationIndex,
        path = AgentJsonPatch.safeDiagnosticPath(exception.path),
    )

    private companion object {
        val CONCURRENCY_CONFLICT_CODES = setOf(
            "SESSION_CONFLICT", "QUERY_RUN_CONFLICT", "DRAFT_CONFLICT", "EXECUTION_ADMISSION_CONFLICT", "QUERY_RUN_CANCEL_CONFLICT",
        )
        val OWNER_REJECTION_DETAILS = mapOf(
            "IDEMPOTENCY_CONFLICT" to "This request conflicts with an earlier Dashboard command recorded under the same request ID.",
            "IDEMPOTENCY_RECEIPT_INVALID" to "The earlier Dashboard command recorded under this request ID cannot be replayed.",
            "DASHBOARD_DELETE_RETRY_REQUIRED" to "A Dashboard query run changed while the Dashboard was being deleted. Retry the delete.",
            "INVALID_CURSOR" to "The History cursor belongs to another Dashboard.",
            "INVALID_DRAFT_PARAMETERS" to "The parameter bindings must give a valid value for every declared parameter.",
            "INVALID_DRAFT_DOCUMENT" to "The Draft parameterBindings and variables must give a valid value for every declared parameter and Variable.",
            "INVALID_SESSION_PARAMETERS" to "The parameter bindings must give a valid value for every declared parameter.",
            "INVALID_VARIABLE_VALUE" to "The variables must give a valid value for every declared Variable.",
            "INVALID_QUERY_BINDING" to "A query input value cannot be bound to its query.",
            "EMPTY_VALUE_REJECTED" to "A query uses an input that has no value. Set the input before running the query.",
            "UNKNOWN_QUERY" to "The request names a query that the Dashboard does not declare.",
            "QUERY_RUN_NOT_FOUND" to "The query run could not be found to cancel.",
            "REFRESH_QUERY_RUN_UNAVAILABLE" to "A query outside the refresh has no run for the current inputs. Resolve the Session before refreshing.",
            "DRAFT_CONFLICT" to "Another request changed the Draft at the same time. Read the Draft and retry.",
            "QUERY_RUN_CANCEL_CONFLICT" to "A replaced query run could not be cancelled. Retry the request.",
            "EXECUTION_BACKPRESSURED" to "This Workspace has too many queries running. Retry after some finish.",
            "INVALID_SEMANTIC_QUERY" to "A Semantic query in the Dashboard is invalid.",
            "UNSUPPORTED_SEMANTIC_QUERY" to "A Semantic query uses a capability that its connector does not support.",
            "SEMANTIC_QUERY_NOT_FOUND" to "A Semantic query references a metric that is not available in this Workspace.",
        )
        val DASHBOARD_ID_PATTERN = Regex("^workspace/dashboards/($UUID_V7)(?:/(?:draft|sessions|schedules|occurrences|history)(?:/.*)?)?$")
        val SESSION_ID_PATTERN = Regex("^workspace/dashboards/($UUID_V7)/sessions/($UUID_V7)(?:/.*)?$")
        val QUERY_RUN_ID_PATTERN = Regex("^workspace/dashboards/($UUID_V7)/(?:draft/query-runs/($UUID_V7)|sessions/$UUID_V7/query-runs/($UUID_V7))(?:/result)?$")
    }
}
