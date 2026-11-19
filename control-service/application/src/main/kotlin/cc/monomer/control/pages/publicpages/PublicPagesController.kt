package cc.monomer.control.pages.publicpages

import cc.monomer.control.pagereader.*
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.WorkspaceMembershipAdmissionRequest
import cc.monomer.control.auth.inbound.oauth.JwtAuthentication
import cc.monomer.identity.api.WorkspacePublicPagesQuery
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.api.PresentedCredential
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import kotlinx.coroutines.reactor.awaitSingleOrNull
import cc.monomer.control.dashboard.DashboardResultResponse
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.pages.api.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange
import tools.jackson.databind.ObjectMapper

@RestController
class PagesAccessController(
    private val access: PublicPagesAccess,
    private val admission: ControlAdmissionFacade,
    private val publicPages: WorkspacePublicPagesQuery,
) {
    @GetMapping("/v2/workspaces/{workspaceId}/pages/access")
    suspend fun access(@PathVariable workspaceId: String, exchange: ServerWebExchange): Map<String, String> =
        mapOf("mode" to mode(WorkspaceId(uuid(workspaceId)), exchange))

    suspend fun mode(workspaceId: WorkspaceId, exchange: ServerWebExchange): String {
        val authentication = (ReactiveSecurityContextHolder.getContext().awaitSingleOrNull()?.authentication as? JwtAuthentication)
            ?: exchange.getPrincipal<JwtAuthentication>().awaitSingleOrNull()
        return access.snapshot {
            if (authentication != null) {
                val decision = admission.authorizeWorkspaceMembership(
                    WorkspaceMembershipAdmissionRequest(workspaceId, authentication.presentedCredential, null),
                )
                if (decision is ControlAdmissionDecision.Admitted) return@snapshot if (decision.context.role == WorkspaceRole.VIEWER) "VIEWER" else "MEMBER"
                if (decision is ControlAdmissionDecision.Denied && !access.isNonMemberDenial(decision)) missing()
            }
            if (!publicPages.isPublic(workspaceId)) missing()
            "PUBLIC"
        }
    }
}

@RestController
@RequestMapping("/v2/public/workspaces/{workspaceId}/pages")
class PublicPagesController(
    private val reader: PageReaderService,
    private val mapper: ObjectMapper,
) {
    @GetMapping("/tree")
    suspend fun tree(@PathVariable workspaceId: String): PublicTreeResponse = reader.tree(caller(workspaceId)).response()

    @GetMapping("/{pageId}")
    suspend fun page(@PathVariable workspaceId: String, @PathVariable pageId: String): PublicPageDetailResponse =
        reader.page(caller(workspaceId), pageId).response()

    @GetMapping("/{pageId}/content")
    suspend fun content(@PathVariable workspaceId: String, @PathVariable pageId: String): CurrentPageContent =
        reader.content(caller(workspaceId), pageId)

    @GetMapping("/{pageId}/sessions")
    suspend fun sessions(
        @PathVariable workspaceId: String, @PathVariable pageId: String,
        @RequestParam(required = false) cursor: String?, @RequestParam(required = false) pageSize: Int?,
    ): PublicListResponse<PublicSessionSummaryResponse> = reader.sessions(caller(workspaceId), pageId, cursor, pageSize).response { it.response() }

    @GetMapping("/{pageId}/sessions/entry")
    suspend fun entry(
        @PathVariable workspaceId: String, @PathVariable pageId: String,
        @RequestParam(required = false) sessionId: String?,
    ): PublicEntryResponse = reader.entry(caller(workspaceId), pageId, sessionId).response(mapper)

    @GetMapping("/{pageId}/sessions/{sessionId}")
    suspend fun session(@PathVariable workspaceId: String, @PathVariable pageId: String, @PathVariable sessionId: String): PublicSessionResponse =
        reader.session(caller(workspaceId), pageId, sessionId).response(mapper)

    @PostMapping("/{pageId}/sessions/{sessionId}:resolve")
    suspend fun resolve(
        @PathVariable workspaceId: String, @PathVariable pageId: String, @PathVariable sessionId: String,
        @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String,
    ): PublicResolutionResponse {
        uuid(key)
        val document = try { STRICT_JSON.readTree(body) } catch (_: RuntimeException) { invalid() }
        if (document == null || !document.isObject || document.properties().map { it.key }.toSet() != setOf("variables") || !document["variables"].isObject) invalid()
        return reader.resolve(caller(workspaceId), pageId, sessionId, key, Json.parseToJsonElement(document["variables"].toString()) as JsonObject).response(mapper)
    }

    @GetMapping("/{pageId}/sessions/{sessionId}/query-runs")
    suspend fun runs(
        @PathVariable workspaceId: String, @PathVariable pageId: String, @PathVariable sessionId: String,
        @RequestParam(required = false) cursor: String?, @RequestParam(required = false) pageSize: Int?,
    ): PublicListResponse<PublicRunResponse> = reader.runs(caller(workspaceId), pageId, sessionId, cursor, pageSize).response { it.response() }

    @GetMapping("/{pageId}/sessions/{sessionId}/query-runs/{queryRunId}")
    suspend fun run(
        @PathVariable workspaceId: String, @PathVariable pageId: String, @PathVariable sessionId: String, @PathVariable queryRunId: String,
    ): PublicRunResponse = reader.run(caller(workspaceId), pageId, sessionId, queryRunId).response()

    @GetMapping("/{pageId}/sessions/{sessionId}/query-runs/{queryRunId}/result")
    suspend fun result(
        @PathVariable workspaceId: String, @PathVariable pageId: String, @PathVariable sessionId: String, @PathVariable queryRunId: String,
    ): DashboardResultResponse {
        val result = reader.result(caller(workspaceId), pageId, sessionId, queryRunId)
        return DashboardResultResponse(result.queryRunId.toString(), result.queryType, result.payload.columns.map(cc.monomer.control.dashboard.DashboardResultColumnResponse::from),
            result.payload.rows.map { row -> row.map { mapper.readTree(it.toString()) } }, result.reportingTimeZone?.id)
    }

    suspend fun httpCredential(): PresentedCredential? =
        (ReactiveSecurityContextHolder.getContext().awaitSingleOrNull()?.authentication as? JwtAuthentication)?.presentedCredential


    private suspend fun caller(workspaceId: String) = PageReaderCaller(WorkspaceId(uuid(workspaceId)), httpCredential(), null, null, null)

    private companion object {
        val STRICT_JSON = tools.jackson.databind.json.JsonMapper.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()
    }
}
