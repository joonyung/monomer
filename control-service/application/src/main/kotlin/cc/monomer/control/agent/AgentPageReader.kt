@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package cc.monomer.control.agent

import cc.monomer.agent.api.*
import cc.monomer.control.pages.publicpages.*
import cc.monomer.control.pagereader.*
import cc.monomer.pages.api.CurrentPage
import cc.monomer.pages.api.CurrentPagePlacement
import java.util.Base64
import kotlin.uuid.Uuid
import kotlinx.serialization.json.*

class AgentPageReader(private val reader: PageReaderService) {
    suspend fun <T> snapshot(action: suspend () -> T): T = reader.snapshot(action)

    suspend fun list(caller: AgentWorkspaceCaller, request: AgentListRequest, membershipEvidence: cc.monomer.identity.api.IdentityMembershipEvidence?): AgentListingOutcome = try {
        if (request.path.value == "workspace/pages") {
            val tree = reader.tree(caller.readerCaller(membershipEvidence))
            val after = try { request.cursor?.let {
                val parts = decode(it).split('|')
                require(parts.size == 4 && parts[0] == caller.workspaceId.toString() && parts[1] == request.path.value)
                require(parts[2].toLong().let { it >= 0 && it.toString() == parts[2] })
                val pageId = Uuid.parse(parts[3]).also { require(it.toString() == parts[3]) }
                if (parts[2] != tree.revision.toString()) return AgentListingOutcome.Rejected(problem("CURSOR_STALE", AgentWorkspaceProblemStatus.CONFLICT))
                pageId
            } } catch (_: IllegalArgumentException) { throw PageReaderProblem(PageReaderFailure.InvalidCursor) }
            val remaining = tree.pages.sortedBy { it.pageId.toString() }.filter { after == null || it.pageId.toString() > after.toString() }
            val selected = remaining.take(request.limit)
            AgentListingOutcome.Found(AgentResourceListingResponse(selected.map { AgentResourceListingItem.Page(path("workspace/pages/${it.pageId}"), it.body()) },
                if (remaining.size > selected.size) encode("${caller.workspaceId}|${request.path}|${tree.revision}|${selected.last().pageId}") else null,
                listOf(AgentAvailableOperation.Read(path("workspace/pages/tree")))))
        } else {
            val parts = request.path.value.split('/')
            val cursor = try { request.cursor?.let { unwrapCursor(caller, request.path, it) } }
                catch (_: IllegalArgumentException) { throw PageReaderProblem(PageReaderFailure.InvalidCursor) }
            if (AgentPagesPaths.sessions.matches(request.path.value)) {
                val result = reader.sessions(caller.readerCaller(membershipEvidence), parts[2], cursor, request.limit)
                AgentListingOutcome.Found(AgentResourceListingResponse(result.items.map {
                    AgentResourceListingItem.PageSession(path("${request.path}/${it.sessionId}"), it.createdAt, it.updatedAt)
                }, result.nextCursor?.let { wrapCursor(caller, request.path, it) }, emptyList()))
            } else {
                val result = reader.runs(caller.readerCaller(membershipEvidence), parts[2], parts[4], cursor, request.limit)
                AgentListingOutcome.Found(AgentResourceListingResponse(result.items.map {
                    AgentResourceListingItem.PageRun(path("${request.path}/${it.queryRunId}"), it.body())
                }, result.nextCursor?.let { wrapCursor(caller, request.path, it) }, emptyList()))
            }
        }
    } catch (failure: PageReaderProblem) { AgentListingOutcome.Rejected(failure.problem()) }
      catch (failure: IllegalArgumentException) { throw failure }
      catch (failure: kotlinx.coroutines.CancellationException) { throw failure }
      catch (failure: Exception) { AgentListingOutcome.Rejected(unavailable(failure)) }

    suspend fun read(caller: AgentWorkspaceCaller, request: AgentReadRequest, membershipEvidence: cc.monomer.identity.api.IdentityMembershipEvidence?): AgentResourceOutcome = try {
        if (request.download != null) return AgentResourceOutcome.NotFound
        val parts = request.path.value.split('/')
        val context = caller.readerCaller(membershipEvidence)
        when {
            request.path.value == "workspace/pages/tree" -> {
                val tree = reader.tree(context)
                AgentResourceOutcome.Found(AgentResourceEnvelope.PagesTree(request.path, AgentRevision(tree.revision),
                    AgentPagesTreeResourceBody(tree.pages.map { AgentResourceListingItem.Page(path("workspace/pages/${it.pageId}"), it.body()) }, tree.placements.map { it.body() }), emptyList()))
            }
            AgentPagesPaths.page.matches(request.path.value) -> {
                val detail = reader.page(context, parts[2])
                AgentResourceOutcome.Found(AgentResourceEnvelope.Page(request.path, AgentRevision(detail.revision), detail.page.body(), buildList {
                    add(AgentAvailableOperation.Read(path("workspace/pages/tree")))
                    if (detail.page.kind == "DOCUMENT") add(AgentAvailableOperation.Read(path("${request.path}/content")))
                    if (detail.page.kind == "DASHBOARD") add(AgentAvailableOperation.List(path("${request.path}/sessions")))
                }))
            }
            AgentPagesPaths.content.matches(request.path.value) -> {
                val content = reader.content(context, parts[2])
                AgentResourceOutcome.Found(AgentResourceEnvelope.PageContent(request.path,
                    AgentPageContentBody(content.title, AgentPageContentFormat.valueOf(content.format), content.source), emptyList()))
            }
            AgentPagesPaths.session.matches(request.path.value) -> {
                val session = reader.session(context, parts[2], parts[4])
                AgentResourceOutcome.Found(AgentResourceEnvelope.PageSession(request.path,
                    AgentPageSessionBody(session.title, session.display, session.parameterBindings, session.initialVariables, session.createdAt, session.updatedAt),
                    listOf(AgentAvailableOperation.List(path("${request.path}/query-runs")), AgentAvailableOperation.Invoke(request.path, "resolve", "DASHBOARD_SESSION_RESOLVE"))))
            }
            AgentPagesPaths.run.matches(request.path.value) -> {
                val run = reader.run(context, parts[2], parts[4], parts[6])
                AgentResourceOutcome.Found(AgentResourceEnvelope.PageRun(request.path, run.body(),
                    if (run.state == "SUCCEEDED" && run.resultAvailable) listOf(AgentAvailableOperation.Read(path("${request.path}/result"))) else emptyList()))
            }
            else -> {
                val read = reader.result(context, parts[2], parts[4], parts[6])
                val result = read.payload
                AgentResourceOutcome.Found(AgentResourceEnvelope.DashboardQueryResult(request.path,
                    AgentDashboardResultResourceBody(read.queryType, result.columns.map { AgentDashboardResultColumn(it.name, it.logicalType, it.nullable, display = it.display.toAgentDisplay()) }, result.rows,
                        result.totalRowCount.toString(), result.returnedRowCount, result.truncated, read.reportingTimeZone), emptyList()))
            }
        }
    } catch (failure: PageReaderProblem) { AgentResourceOutcome.Rejected(failure.problem()) }
      catch (failure: IllegalArgumentException) { throw failure }
      catch (failure: kotlinx.coroutines.CancellationException) { throw failure }
      catch (failure: Exception) { AgentResourceOutcome.Rejected(unavailable(failure)) }

    suspend fun resolve(caller: AgentWorkspaceCaller, request: AgentInvokeRequest): AgentActionOutcome = try {
        val input = request.input as? AgentInvokeInput.DashboardSessionResolve ?: return AgentActionOutcome.NotFound
        val parts = request.path.value.split('/')
        val resolution = reader.resolve(caller.readerCaller(null), parts[2], parts[4], caller.requestId.value, JsonObject(input.variables))
        AgentActionOutcome.Completed(AgentActionResult.DashboardSessionResolution(request.path, "resolve", AgentActionStatus.ACCEPTED,
            AgentActionResultReference(AgentResourceKind.PAGE_SESSION, request.path), resolution.variables,
            resolution.queryRuns.mapValues { path("${request.path}/query-runs/${it.value}") }))
    } catch (failure: cc.monomer.control.pagereader.PageReaderOwnerRejected) {
        val concurrencyConflict = failure.code in setOf("SESSION_CONFLICT", "QUERY_RUN_CONFLICT", "DRAFT_CONFLICT", "EXECUTION_ADMISSION_CONFLICT", "QUERY_RUN_CANCEL_CONFLICT")
        AgentActionOutcome.Rejected(problem(failure.code, if (concurrencyConflict) AgentWorkspaceProblemStatus.CONFLICT else AgentWorkspaceProblemStatus.BAD_REQUEST)
            .copy(detail = failure.detail, retryable = concurrencyConflict))
    } catch (failure: PageReaderProblem) { AgentActionOutcome.Rejected(failure.problem()) }
      catch (failure: IllegalArgumentException) { throw failure }
      catch (failure: kotlinx.coroutines.CancellationException) { throw failure }
      catch (failure: Exception) { AgentActionOutcome.Rejected(unavailable(failure)) }

    private fun AgentWorkspaceCaller.readerCaller(evidence: cc.monomer.identity.api.IdentityMembershipEvidence?) = PageReaderCaller(workspaceId, presentedCredential, expectedOAuthResource, actorPrincipalId, evidence)
    private fun CurrentPage.body() = AgentPageResourceBody(AgentPageKind.valueOf(kind), title, description, displayTitle, null, null, null, null, documentFormat?.let(AgentPageContentFormat::valueOf), null, null)
    private fun CurrentPagePlacement.body(): AgentPagePlacement = AgentPagePlacement(pageId, children.map { it.body() })
    private fun PageReaderRun.body() = AgentPageRunBody(queryName, queryType, state, resultAvailable, failureCode, outputColumnNames, createdAt, updatedAt)
    private fun path(value: String) = AgentResourcePath.parse(value)
    private fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decode(value: String) = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8).also { require(encode(it) == value) }
    private fun wrapCursor(caller: AgentWorkspaceCaller, path: AgentResourcePath, cursor: String) =
        if (AgentPagesPaths.sessions.matches(path.value)) encode("${caller.workspaceId}|$path|$cursor")
        else encode("${caller.workspaceId}|$path|${decode(cursor)}")
    private fun unwrapCursor(caller: AgentWorkspaceCaller, path: AgentResourcePath, cursor: String): String {
        val parts = decode(cursor).split('|')
        if (AgentPagesPaths.sessions.matches(path.value)) {
            require(parts.size == 3 && parts[0] == caller.workspaceId.toString() && parts[1] == path.value)
            return parts[2]
        }
        require(parts.size == 4 && parts[0] == caller.workspaceId.toString() && parts[1] == path.value)
        return encode("${parts[2]}|${parts[3]}")
    }
    private fun problem(code: String, status: AgentWorkspaceProblemStatus) = AgentWorkspaceProblem(
        code = code,
        title = "Page reader request rejected",
        detail = "Read the current Pages tree and select an available Page and Session.",
        status = status,
        retryable = false,
        currentRevision = null,
        violations = emptyList(),
        hints = emptyList(),
        nextActions = emptyList(),
        retryAfterSeconds = null,
        roleRequirement = null,
        partitionFilters = emptyList(),
    )
    private fun PageReaderProblem.problem(): AgentWorkspaceProblem = when (failure) {
        PageReaderFailure.NotFound -> problem("NOT_FOUND", AgentWorkspaceProblemStatus.NOT_FOUND)
        PageReaderFailure.Conflict -> problem("CONFLICT", AgentWorkspaceProblemStatus.CONFLICT)
        PageReaderFailure.IdempotencyConflict -> problem("IDEMPOTENCY_CONFLICT", AgentWorkspaceProblemStatus.CONFLICT)
        PageReaderFailure.InvalidCursor -> AgentOwnerProblems.invalidCursor
        PageReaderFailure.InvalidRequest -> problem("INVALID_REQUEST", AgentWorkspaceProblemStatus.BAD_REQUEST)
        PageReaderFailure.Unauthorized -> problem("UNAUTHORIZED", AgentWorkspaceProblemStatus.UNAUTHORIZED)
        PageReaderFailure.Unavailable -> AgentOwnerProblems.ownerUnavailable
        PageReaderFailure.Backpressured -> AgentOwnerProblems.ownerUnavailable.copy(
            code = "EXECUTION_BACKPRESSURED",
            detail = "This Workspace has too many queries running. Retry after some finish.",
            retryAfterSeconds = 2,
        )
    }
    private fun unavailable(failure: Exception): AgentWorkspaceProblem {
        logAgentOwnerFailure("AgentPageReader", failure)
        return AgentOwnerProblems.ownerUnavailable
    }
}
