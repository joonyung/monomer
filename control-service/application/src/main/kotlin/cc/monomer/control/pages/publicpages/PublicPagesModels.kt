package cc.monomer.control.pages.publicpages

import cc.monomer.pages.api.CurrentPage
import cc.monomer.pages.api.CurrentPagePlacement
import kotlinx.serialization.json.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

data class PublicPageResponse(val pageId: String, val kind: String, val documentFormat: String?, val title: String?, val displayTitle: String, val description: String?)
data class PublicPlacementResponse(val pageId: String, val children: List<PublicPlacementResponse>)
data class PublicTreeResponse(val workspaceName: String, val revision: Long, val pages: List<PublicPageResponse>, val placements: List<PublicPlacementResponse>)
data class PublicPageDetailResponse(val page: PublicPageResponse, val path: List<String>, val children: List<String>, val revision: Long)
data class PublicSessionResponse(
    val sessionId: String, val title: String?, val createdAt: String, val updatedAt: String,
    val display: JsonNode, val parameterBindings: JsonNode, val initialVariables: JsonNode,
)
data class PublicSessionSummaryResponse(val sessionId: String, val createdAt: String, val updatedAt: String)
data class PublicListResponse<T>(val items: List<T>, val nextCursor: String?)
data class PublicRunResponse(
    val queryRunId: String, val queryName: String, val queryType: String, val state: String, val resultAvailable: Boolean,
    val failureCode: String?, val outputColumnNames: List<String>, val createdAt: String, val updatedAt: String,
)
data class PublicResolutionResponse(val variables: JsonNode, val queryRuns: Map<String, String>)
data class PublicEntryResponse(val session: PublicSessionResponse?, val initialQueryRuns: Map<String, PublicRunResponse>)

internal fun CurrentPage.response() = PublicPageResponse(pageId.toString(), kind, documentFormat, title, displayTitle, description)
internal fun CurrentPagePlacement.response(): PublicPlacementResponse = PublicPlacementResponse(pageId.toString(), children.map { it.response() })
internal fun PageReaderSession.response(mapper: ObjectMapper) = PublicSessionResponse(
    sessionId.toString(), title, createdAt.toString(), updatedAt.toString(), mapper.readTree(display.toString()),
    mapper.readTree(parameterBindings.toString()), mapper.readTree(initialVariables.toString()))
internal fun PageReaderRun.response() = PublicRunResponse(queryRunId.toString(), queryName, queryType, state, resultAvailable,
    failureCode, outputColumnNames, createdAt.toString(), updatedAt.toString())
internal fun PageReaderTree.response() = PublicTreeResponse(workspaceName, revision, pages.map { it.response() }, placements.map { it.response() })
internal fun PageReaderDetail.response() = PublicPageDetailResponse(page.response(), path.map { it.toString() }, children.map { it.toString() }, revision)
internal fun PageReaderSessionSummary.response() = PublicSessionSummaryResponse(sessionId.toString(), createdAt.toString(), updatedAt.toString())
internal fun PageReaderResolution.response(mapper: ObjectMapper) = PublicResolutionResponse(mapper.readTree(variables.toString()), queryRuns.mapValues { it.value.toString() })
internal fun PageReaderEntry.response(mapper: ObjectMapper) = PublicEntryResponse(session?.response(mapper), initialQueryRuns.mapValues { it.value.response() })
internal fun <T, R> PageReaderList<T>.response(convert: (T) -> R) = PublicListResponse(items.map(convert), nextCursor)
