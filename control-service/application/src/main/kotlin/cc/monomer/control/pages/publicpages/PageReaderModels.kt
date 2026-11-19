package cc.monomer.control.pages.publicpages

import cc.monomer.dashboard.api.*
import cc.monomer.pages.api.*
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.json.*

data class PageReaderTree(val workspaceName: String, val revision: Long, val pages: List<CurrentPage>, val placements: List<CurrentPagePlacement>)
data class PageReaderDetail(val page: CurrentPage, val path: List<Uuid>, val children: List<Uuid>, val revision: Long)
data class PageReaderSession(val sessionId: DashboardSessionId, val title: String?, val createdAt: Instant, val updatedAt: Instant,
    val display: JsonObject, val parameterBindings: JsonObject, val initialVariables: JsonObject)
data class PageReaderSessionSummary(val sessionId: DashboardSessionId, val createdAt: Instant, val updatedAt: Instant)
data class PageReaderList<T>(val items: List<T>, val nextCursor: String?)
data class PageReaderRun(val queryRunId: QueryRunId, val queryName: String, val queryType: String, val state: String, val resultAvailable: Boolean,
    val failureCode: String?, val outputColumnNames: List<String>, val createdAt: Instant, val updatedAt: Instant)
data class PageReaderResolution(val variables: JsonObject, val queryRuns: Map<String, QueryRunId>)
data class PageReaderEntry(val session: PageReaderSession?, val initialQueryRuns: Map<String, PageReaderRun>)

internal fun DashboardOwnerSession.readerProjection(): PageReaderSession {
    val display = buildJsonObject {
        put("parameters", definition["parameters"] ?: JsonObject(emptyMap()))
        put("variables", definition["variables"] ?: JsonObject(emptyMap()))
        put("widgets", definition["widgets"] ?: JsonObject(emptyMap()))
        put("layout", definition["layout"] ?: JsonObject(emptyMap()))
        put("queryTypes", JsonObject((definition["queries"] as? JsonObject).orEmpty().mapValues { (_, entry) -> entry.jsonObject.getValue("type") }))
        put("queryNames", JsonArray((definition["queries"] as? JsonObject).orEmpty().keys.map(::JsonPrimitive)))
    }
    return PageReaderSession(sessionId, (definition["meta"] as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull,
        createdAt, updatedAt, display, JsonObject(parameterBindings), JsonObject(initialVariables))
}
internal fun DashboardOwnerQueryRun.readerProjection() = PageReaderRun(queryRunId, queryName, queryType, state, resultAvailable,
    if (state == "FAILED") "QUERY_FAILED" else if (state == "SUCCEEDED" && !resultAvailable) "RESULT_UNAVAILABLE" else null,
    outputColumnNames, createdAt, updatedAt)
