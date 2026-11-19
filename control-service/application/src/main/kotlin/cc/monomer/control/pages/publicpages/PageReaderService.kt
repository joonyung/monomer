package cc.monomer.control.pages.publicpages

import cc.monomer.control.dashboard.DashboardResultUnavailableException
import cc.monomer.control.dashboard.DashboardSessionEntryReader
import cc.monomer.control.dashboard.withQueryColumns
import cc.monomer.dashboard.api.*
import cc.monomer.dashboard.application.DashboardResultReadPort
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.pages.api.*
import java.time.Clock
import java.time.Instant
import java.util.Base64
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import cc.monomer.control.pagereader.*
import cc.monomer.identity.api.WorkspacePublicPagesQuery

@org.springframework.stereotype.Service
class PageReaderService(
    private val access: PublicPagesAccess,
    private val pages: PagesCurrentReadPort,
    private val dashboards: DashboardOwnerPort,
    private val results: DashboardResultReadPort,
    private val entryReader: DashboardSessionEntryReader,
    private val publicPages: WorkspacePublicPagesQuery,
    private val clock: Clock,
) {
    suspend fun <T> snapshot(action: suspend () -> T): T = access.snapshot(action)

    suspend fun tree(caller: PageReaderCaller): PageReaderTree = access.snapshot {
        val tree = treeSnapshot(caller)
        PageReaderTree(publicPages.pageReaderName(caller.workspaceId) ?: missing(), tree.revision, tree.pages, tree.placements)
    }

    suspend fun page(caller: PageReaderCaller, pageId: String): PageReaderDetail = access.snapshot {
        val (tree, page) = pageSnapshot(caller, uuid(pageId))
        if (page.kind == "DASHBOARD") currentDashboard(caller.workspaceId, tree, page.pageId)
        val path = tree.placements.path(page.pageId) ?: missing()
        PageReaderDetail(page, path.map { it.pageId }, path.last().children.map { it.pageId }, tree.revision)
    }

    suspend fun content(caller: PageReaderCaller, pageId: String): CurrentPageContent {
        val workspace = caller.workspaceId
        val page = uuid(pageId)
        val version = access.snapshot {
            if (pageSnapshot(caller, page).second.kind != "DOCUMENT") missing()
            pages.latestContentVersion(workspace.value, page) ?: missing()
        }
        return when (val outcome = pages.content(workspace.value, page, version)) {
            is CurrentPageContentOutcome.Found -> outcome.content
            CurrentPageContentOutcome.NotFound -> missing()
            CurrentPageContentOutcome.Unavailable -> throw PageReaderProblem(PageReaderFailure.Unavailable)
        }
    }

    suspend fun sessions(
        caller: PageReaderCaller, pageId: String,
        cursor: String?, pageSize: Int?,
    ): PageReaderList<PageReaderSessionSummary> = access.snapshot {
        val workspace = caller.workspaceId
        val dashboard = dashboardSnapshot(caller, uuid(pageId))
        val result = dashboards.listSessions(ListDashboardSessionsCommand(workspace, dashboard,
            cursor?.let { raw ->
                val decoded = try { cc.monomer.control.dashboard.decodeSessionCursor(raw, dashboard) }
                    catch (_: org.springframework.web.server.ResponseStatusException) { throw PageReaderProblem(PageReaderFailure.InvalidCursor) }
                DashboardOwnerSessionPageCursor(decoded.dashboardId, decoded.createdAt, decoded.sessionId)
            }, size(pageSize))).valueOrThrow()
        if (result.items.any { it.dashboardId != dashboard }) missing()
        PageReaderList(result.items.map { PageReaderSessionSummary(it.sessionId, it.createdAt, it.updatedAt) },
            result.nextCursor?.let { cc.monomer.control.dashboard.encodeSessionCursor(cc.monomer.dashboard.application.DashboardSessionPageCursor(it.dashboardId, it.createdAt, it.sessionId)) })
    }

    suspend fun entry(
        caller: PageReaderCaller, pageId: String,
        sessionId: String?,
    ): PageReaderEntry {
        val workspace = caller.workspaceId
        val page = uuid(pageId)
        val dashboard = access.snapshot { dashboardSnapshot(caller, page) }
        val selected = entryReader.select(
            workspace, dashboard, sessionId?.let { DashboardSessionId(uuid(it)) },
            authorize = { access.snapshot { if (dashboardSnapshot(caller, page) != dashboard) missing() } },
            verifyResults = { lookups ->
                access.snapshot {
                    if (lookups.isEmpty()) {
                        if (dashboardSnapshot(caller, page) != dashboard) missing()
                    } else {
                        val candidateSessionId = lookups.first().queryRun.viewerSessionId ?: missing()
                        val session = sessionSnapshot(caller, page, candidateSessionId)
                        if (session.dashboardId != dashboard) missing()
                        lookups.forEach { requireRun(session, it.queryRun) }
                    }
                }
            },
        )
        selected.session?.let { selectedSession ->
            access.snapshot {
                val current = sessionSnapshot(caller, page, selectedSession.sessionId)
                if (current.dashboardId != dashboard) missing()
            }
        }
        return PageReaderEntry(selected.session?.readerProjection(), selected.initialQueryRuns.mapValues { it.value.readerProjection() })
    }

    suspend fun session(caller: PageReaderCaller, pageId: String, sessionId: String): PageReaderSession = access.snapshot {
        sessionSnapshot(caller, uuid(pageId), DashboardSessionId(uuid(sessionId))).readerProjection()
    }

    suspend fun resolve(
        caller: PageReaderCaller, pageId: String, sessionId: String,
        key: String, variables: JsonObject,
    ): PageReaderResolution {
        val (actor, session) = access.snapshot {
            val actor = admit(caller)
            actor to currentSession(caller.workspaceId, uuid(pageId), DashboardSessionId(uuid(sessionId)))
        }
        val resolution = dashboards.resolvePublicSession(ResolvePublicDashboardSessionCommand(
            caller.workspaceId, session.dashboardId, session.sessionId, variables, key, clock.instant(),
            caller.credential, caller.expectedOAuthResource, actor?.actor?.id?.toString(), uuid(pageId),
        )).valueOrThrow()
        return PageReaderResolution(JsonObject(resolution.variables), resolution.queryRuns)
    }

    suspend fun runs(
        caller: PageReaderCaller, pageId: String, sessionId: String,
        cursor: String?, pageSize: Int?,
    ): PageReaderList<PageReaderRun> = access.snapshot {
        val workspace = caller.workspaceId
        val session = sessionSnapshot(caller, uuid(pageId), DashboardSessionId(uuid(sessionId)))
        val result = dashboards.listSessionQueryRuns(ListDashboardSessionQueryRunsCommand(workspace, session.dashboardId, session.sessionId,
            cursor?.let { decodeCursor(it).let { (at, id) -> DashboardOwnerQueryRunPageCursor(at, QueryRunId(id)) } }, size(pageSize))).valueOrThrow()
        result.items.forEach { requireRun(session, it) }
        PageReaderList(result.items.map { it.readerProjection() }, result.nextCursor?.let { encodeCursor(it.createdAt, it.queryRunId.value) })
    }

    suspend fun run(
        caller: PageReaderCaller, pageId: String, sessionId: String, queryRunId: String,
    ): PageReaderRun = access.snapshot {
        val workspace = caller.workspaceId
        val session = sessionSnapshot(caller, uuid(pageId), DashboardSessionId(uuid(sessionId)))
        val run = dashboards.readSessionQueryRun(ReadDashboardSessionQueryRunCommand(workspace, session.dashboardId, session.sessionId, QueryRunId(uuid(queryRunId)))).valueOrThrow()
        requireRun(session, run)
        if (run.queryRunId.value != uuid(queryRunId)) missing()
        run.readerProjection()
    }

    suspend fun result(
        caller: PageReaderCaller, pageId: String, sessionId: String, queryRunId: String,
    ): PageReaderResult {
        val lookup = access.snapshot {
            val workspace = caller.workspaceId
            val session = sessionSnapshot(caller, uuid(pageId), DashboardSessionId(uuid(sessionId)))
            val outcome = dashboards.readSessionResult(ReadDashboardSessionResultCommand(workspace, session.dashboardId, session.sessionId, QueryRunId(uuid(queryRunId))))
            val lookup = when (outcome) {
                is DashboardOwnerResultLookupOutcome.Success -> outcome.value
                DashboardOwnerResultLookupOutcome.NotFound -> missing()
                is DashboardOwnerResultLookupOutcome.Rejected -> throw PageReaderProblem(PageReaderFailure.Conflict)
            }
            requireRun(session, lookup.queryRun)
            if (lookup.queryRun.queryRunId.value != uuid(queryRunId)) missing()
            if (lookup.queryRun.state != "SUCCEEDED" || !lookup.queryRun.resultAvailable) {
                throw PageReaderProblem(PageReaderFailure.Conflict)
            }
            lookup
        }
        val payload = try { results.read(lookup.resultManifest).withQueryColumns(lookup.queryRun.queryType, lookup.queryRun.outputColumnNames, lookup.queryRun.outputColumnDisplays) }
            catch (_: DashboardResultUnavailableException) { throw PageReaderProblem(PageReaderFailure.Unavailable) }
        return PageReaderResult(lookup.queryRun.queryRunId, lookup.queryRun.queryType, payload, lookup.queryRun.reportingTimeZone)
    }

    private fun size(value: Int?): Int = (value ?: 20).takeIf { it in 1..100 } ?: invalid()
    private fun encodeCursor(at: Instant, id: Uuid): String = Base64.getUrlEncoder().withoutPadding().encodeToString("$at|$id".toByteArray(Charsets.UTF_8))
    private fun decodeCursor(raw: String): Pair<Instant, Uuid> {
        fun invalidCursor(): Nothing = throw PageReaderProblem(PageReaderFailure.InvalidCursor)
        if (raw.length !in 1..512) invalidCursor()
        return try {
            val decoded = String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
            if (Base64.getUrlEncoder().withoutPadding().encodeToString(decoded.toByteArray(Charsets.UTF_8)) != raw) invalidCursor()
            val value = decoded.split('|')
            if (value.size != 2) invalidCursor()
            val at = Instant.parse(value[0])
            val id = Uuid.parse(value[1])
            if (at.toString() != value[0] || id.toString() != value[1]) invalidCursor()
            at to id
        } catch (_: IllegalArgumentException) { invalidCursor() }
        catch (_: java.time.format.DateTimeParseException) { invalidCursor() }
    }

    private suspend fun admit(caller: PageReaderCaller): cc.monomer.identity.api.IdentityCredentialEvidence? {
        val actor = caller.membershipEvidence?.let { evidence ->
            if (evidence.workspaceId != caller.workspaceId) missing()
            cc.monomer.identity.api.IdentityCredentialEvidence(evidence.actor, evidence.credentialKind, evidence.credentialSurface, evidence.agentClientId)
        } ?: access.requireWorkspace(caller.workspaceId, caller.credential, caller.expectedOAuthResource)
        if (caller.actorPrincipalId != null && actor?.actor?.id?.toString() != caller.actorPrincipalId) missing()
        return actor
    }

    private suspend fun treeSnapshot(caller: PageReaderCaller): CurrentPagesTree {
        admit(caller)
        return currentTree(caller.workspaceId)
    }

    private suspend fun currentTree(workspace: WorkspaceId): CurrentPagesTree {
        val tree = pages.tree(workspace.value)
        val placed = mutableSetOf<Uuid>()
        fun collect(placements: List<CurrentPagePlacement>) {
            placements.forEach { placed += it.pageId; collect(it.children) }
        }
        collect(tree.placements)
        return tree.copy(pages = tree.pages.filter { it.pageId in placed })
    }

    private suspend fun pageSnapshot(caller: PageReaderCaller, pageId: Uuid): Pair<CurrentPagesTree, CurrentPage> {
        admit(caller)
        return currentPage(caller.workspaceId, pageId)
    }

    private suspend fun currentPage(workspace: WorkspaceId, pageId: Uuid): Pair<CurrentPagesTree, CurrentPage> {
        val tree = currentTree(workspace)
        if (tree.placements.path(pageId) == null) missing()
        return tree to (tree.pages.firstOrNull { it.pageId == pageId } ?: missing())
    }

    private suspend fun dashboardSnapshot(caller: PageReaderCaller, pageId: Uuid): DashboardId {
        admit(caller)
        return currentDashboard(caller.workspaceId, pageId)
    }

    private suspend fun currentDashboard(workspace: WorkspaceId, pageId: Uuid): DashboardId =
        currentDashboard(workspace, currentTree(workspace), pageId)

    private suspend fun currentDashboard(workspace: WorkspaceId, tree: CurrentPagesTree, pageId: Uuid): DashboardId {
        val dashboardId = tree.pages.firstOrNull { it.pageId == pageId }?.dashboardId?.let(::DashboardId) ?: missing()
        tree.requirePlacedDashboardPage(pageId, dashboardId)
        val dashboard = dashboards.read(workspace, dashboardId).valueOrThrow()
        if (dashboard.workspaceId != workspace || dashboard.dashboardId != dashboardId) missing()
        return dashboardId
    }

    private suspend fun sessionSnapshot(caller: PageReaderCaller, pageId: Uuid, sessionId: DashboardSessionId): DashboardOwnerSession {
        admit(caller)
        return currentSession(caller.workspaceId, pageId, sessionId)
    }

    private suspend fun currentSession(workspace: WorkspaceId, pageId: Uuid, sessionId: DashboardSessionId): DashboardOwnerSession {
        val dashboard = currentDashboard(workspace, pageId)
        val session = dashboards.readSession(workspace, dashboard, sessionId).valueOrThrow()
        if (session.workspaceId != workspace || session.dashboardId != dashboard || session.sessionId != sessionId) missing()
        return session
    }

    private fun requireRun(session: DashboardOwnerSession, run: DashboardOwnerQueryRun) {
        if (run.dashboardId != session.dashboardId || run.viewerSessionId != session.sessionId ||
            run.queryName !in (session.definition["queries"] as? JsonObject).orEmpty()) missing()
    }
}

data class PageReaderCaller(
    val workspaceId: WorkspaceId,
    val credential: cc.monomer.identity.api.PresentedCredential?,
    val expectedOAuthResource: cc.monomer.identity.api.ExpectedOAuthResource?,
    val actorPrincipalId: String?,
    val membershipEvidence: cc.monomer.identity.api.IdentityMembershipEvidence?,
) {
    init {
        require(expectedOAuthResource == null || credential != null && actorPrincipalId != null)
        require(actorPrincipalId == null || credential != null)
        require(membershipEvidence == null || credential != null && actorPrincipalId != null)
    }
}

data class PageReaderResult(val queryRunId: QueryRunId, val queryType: String, val payload: DashboardResultReadPort.DashboardResultPayload, val reportingTimeZone: java.time.ZoneId?)
