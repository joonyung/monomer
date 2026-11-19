package cc.monomer.agent.api

object AgentPathPatterns {
    const val UUID_V7 = "[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
    const val ANY_VERSION_UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"

    // Lower snake case without a repeated group: java.util.regex recurses once per group repetition.
    const val SEMANTIC_NAME = "(?![a-z0-9_]*__)[a-z](?:[a-z0-9_]*[a-z0-9])?"

    const val DASHBOARD_COLLECTION = "workspace/dashboards"
    val dashboard = Regex("^workspace/dashboards/$UUID_V7$")
    val dashboardDraft = Regex("^workspace/dashboards/$UUID_V7/draft$")
    val dashboardDraftStatus = Regex("^workspace/dashboards/$UUID_V7/draft/status$")
    val dashboardDraftQueryRuns = Regex("^workspace/dashboards/$UUID_V7/draft/query-runs$")
    val dashboardDraftQueryRun = Regex("^workspace/dashboards/$UUID_V7/draft/query-runs/$UUID_V7$")
    val dashboardDraftResult = Regex("^workspace/dashboards/$UUID_V7/draft/query-runs/$UUID_V7/result$")
    val dashboardSessions = Regex("^workspace/dashboards/$UUID_V7/sessions$")
    val dashboardSession = Regex("^workspace/dashboards/$UUID_V7/sessions/$UUID_V7$")
    val dashboardSessionQueryRuns = Regex("^workspace/dashboards/$UUID_V7/sessions/$UUID_V7/query-runs$")
    val dashboardSessionQueryRun = Regex("^workspace/dashboards/$UUID_V7/sessions/$UUID_V7/query-runs/$UUID_V7$")
    val dashboardSessionResult = Regex("^workspace/dashboards/$UUID_V7/sessions/$UUID_V7/query-runs/$UUID_V7/result$")
    val exploreRun = Regex("^workspace/explore/runs/$UUID_V7$")
    val exploreResult = Regex("^workspace/explore/runs/$UUID_V7/result$")
    val connector = Regex("^workspace/connectors/$ANY_VERSION_UUID$")

    private val catalogIngestPath = Regex("^workspace/connectors/$ANY_VERSION_UUID/catalog(?:/catalogs/[^/]+)?$")
    private val catalogOptionCollectionPath = Regex(
        "^workspace/connectors/$ANY_VERSION_UUID/catalog/(?:catalog-selection|(?:catalogs/[^/]+/)?selection)/options/" +
            "(?:catalogs|schemas|schemas/[^/]+/relations)$",
    )
    private val catalogJobPath = Regex(
        "^workspace/connectors/$ANY_VERSION_UUID/catalog(?:" +
            "/(?:catalog-selection|(?:catalogs/[^/]+/)?selection)/option-refreshes/" +
            "(?:catalogs|schemas)(?:/[^/]+/relations)?/$UUID_V7" +
            "|(?:/catalogs/[^/]+)?/ingestions/$UUID_V7)$",
    )

    fun catalogInvokeDeclared(path: String, action: String): Boolean = when (action) {
        "ingest" -> catalogIngestPath.matches(path)
        "refresh" -> catalogOptionCollectionPath.matches(path)
        "cancel" -> catalogJobPath.matches(path)
        else -> false
    }
}
