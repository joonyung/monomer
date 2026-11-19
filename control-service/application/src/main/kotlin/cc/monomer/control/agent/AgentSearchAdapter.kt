package cc.monomer.control.agent

import cc.monomer.agent.api.AgentResourceListingItem
import cc.monomer.agent.api.AgentResourceKind
import cc.monomer.agent.api.AgentResourceEnvelope
import cc.monomer.agent.api.AgentResourceOutcome
import cc.monomer.agent.api.AgentReadRequest
import cc.monomer.agent.api.AgentPagesOwnerPort
import cc.monomer.agent.api.AgentResourcePath
import cc.monomer.agent.api.AgentSearchOutcome
import cc.monomer.agent.api.AgentSearchPort
import cc.monomer.agent.api.AgentSearchQueryResult
import cc.monomer.agent.api.AgentSearchRequest
import cc.monomer.agent.api.AgentSearchResponse
import cc.monomer.agent.api.AgentSearchScope
import cc.monomer.agent.api.AgentWorkspaceCaller
import cc.monomer.agent.api.AgentWorkspaceProblem
import cc.monomer.agent.api.AgentWorkspaceProblemStatus
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.search.api.SearchIndex
import cc.monomer.search.api.SearchMatch
import cc.monomer.search.api.SearchResourceKind
import cc.monomer.search.api.SearchText
import java.util.UUID
import java.util.Locale

class AgentSearchAdapter(
    private val searchIndex: SearchIndex,
    private val transactionRunner: ConsistentSnapshotTransactionRunner,
    private val admitted: suspend (AgentWorkspaceCaller, AgentSearchRequest) -> Boolean,
    private val scopeExists: suspend (AgentWorkspaceCaller, AgentResourcePath) -> Boolean,
    private val currentListings: suspend (AgentWorkspaceCaller, List<AgentResourcePath>) -> List<AgentResourceListingItem>,
    private val pagesOwner: AgentPagesOwnerPort,
) : AgentSearchPort {
    // Match ECMAScript whitespace used by the Web Page search.
    private val pageQueryWhitespace = Regex("""[\s\p{Zs}\u2028\u2029\uFEFF]+""")

    override suspend fun search(caller: AgentWorkspaceCaller, request: AgentSearchRequest): AgentSearchOutcome {
        if (!AgentSearchScope.supports(request.path) || request.queries.any { !SearchText.hasTokens(it) }) {
            return AgentSearchOutcome.Rejected(AgentWorkspaceProblem(
                "INVALID_REQUEST", "Invalid search request", "Use a supported scope and queries containing letters or numbers.",
                AgentWorkspaceProblemStatus.BAD_REQUEST, false,
                currentRevision = null,
                violations = emptyList(),
                hints = emptyList(),
                nextActions = emptyList(),
                retryAfterSeconds = null,
                roleRequirement = null,
                partitionFilters = emptyList(),
            ))
        }
        return agentOwnerGuard("AgentSearchAdapter", AgentOwnerProblems.ownerUnavailable, AgentSearchOutcome::Rejected) {
            transactionRunner.run {
                if (!admitted(caller, request)) return@run AgentSearchOutcome.Rejected(AgentWorkspaceProblem(
                    "FORBIDDEN", "Forbidden", "Current Workspace membership and role are required.", AgentWorkspaceProblemStatus.FORBIDDEN, false,
                    currentRevision = null,
                    violations = emptyList(),
                    hints = emptyList(),
                    nextActions = emptyList(),
                    retryAfterSeconds = null,
                    roleRequirement = null,
                    partitionFilters = emptyList(),
                ))
                if (!scopeExists(caller, request.path)) return@run AgentSearchOutcome.NotFound
                val requestedKinds = request.kinds ?: AgentSearchRequest.SUPPORTED_KINDS
                val kinds = if (request.path.value == "workspace/pages") emptySet() else requestedKinds
                    .filter { it != AgentResourceKind.PAGE }.map { SearchResourceKind.valueOf(it.name) }.toSet()
                val kindNames = kinds.map { it.name }.toSet()
                val pages = if (AgentResourceKind.PAGE in requestedKinds && request.path.value in setOf("workspace", "workspace/pages")) {
                    when (val outcome = pagesOwner.read(caller, AgentReadRequest(AgentResourcePath.parse("workspace/pages/tree"), null))) {
                        is AgentResourceOutcome.Found -> (outcome.resource as AgentResourceEnvelope.PagesTree).body.pages
                        is AgentResourceOutcome.Rejected -> return@run AgentSearchOutcome.Rejected(outcome.problem)
                        AgentResourceOutcome.NotFound -> return@run AgentSearchOutcome.NotFound
                        AgentResourceOutcome.Forbidden -> return@run AgentSearchOutcome.Rejected(AgentWorkspaceProblem(
                            "FORBIDDEN", "Forbidden", "Current Workspace membership is required.", AgentWorkspaceProblemStatus.FORBIDDEN, false,
                            currentRevision = null,
                            violations = emptyList(),
                            hints = emptyList(),
                            nextActions = emptyList(),
                            retryAfterSeconds = null,
                            roleRequirement = null,
                            partitionFilters = emptyList(),
                        ))
                        AgentResourceOutcome.Unavailable -> error("Current Pages tree unavailable")
                    }
                } else emptyList()
                val results = request.queries.map { query ->
                    val items = linkedMapOf<AgentResourcePath, AgentResourceListingItem>()
                    var offset = 0
                    while (kinds.isNotEmpty() && items.size < request.limitPerQuery) {
                        val candidates = searchIndex.candidates(
                            UUID.fromString(caller.workspaceId.toString()), request.path.value, kinds, query, SearchMatch.WHOLE_TOKENS, 100, offset,
                        ).map(AgentResourcePath::parse)
                        if (candidates.isEmpty()) break
                        val current = currentListings(caller, candidates).associateBy { it.path }
                        for (path in candidates) {
                            val item = current[path] ?: continue
                            if (item.kind.name !in kindNames) continue
                            items.putIfAbsent(path, item)
                            if (items.size == request.limitPerQuery) break
                        }
                        if (candidates.size < 100) break
                        offset += candidates.size
                    }
                    val words = query.lowercase(Locale.ROOT).split(pageQueryWhitespace).filter { it.isNotEmpty() }
                    for (page in pages) {
                        if (items.size == request.limitPerQuery) break
                        val title = page.body.displayTitle.lowercase(Locale.ROOT)
                        if (words.all { it in title }) items.putIfAbsent(page.path, page)
                    }
                    AgentSearchQueryResult(query, items.values.toList())
                }
                AgentSearchOutcome.Found(AgentSearchResponse(results))
            }
        }
    }
}
