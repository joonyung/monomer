package cc.monomer.agent.api

object AgentSearchScope {
    fun supports(path: AgentResourcePath): Boolean {
        val segments = path.value.split('/')
        if (segments.first() != "workspace") return false
        if (segments.size == 1) return true
        if (segments.size == 2) return segments[1] in setOf("connectors", "semantic-models", "metrics", "dashboards", "pages")
        if (segments[1] != "connectors" || !CONNECTOR_ID.matches(segments[2])) return false
        if (segments.size == 3) return true
        if (segments[3] != "catalog") return false
        val catalogSegments = segments.drop(4)
        if (catalogSegments.isEmpty()) return true
        val schemaSegments = if (catalogSegments[0] == "catalogs") {
            if (catalogSegments.size <= 2) return true
            catalogSegments.drop(2)
        } else catalogSegments
        if (schemaSegments[0] != "schemas") return false
        return schemaSegments.size <= 2 || schemaSegments.size == 3 && schemaSegments[2] == "relations"
    }

    fun pagesOnly(path: AgentResourcePath, kinds: Set<AgentResourceKind>?): Boolean =
        path.value == "workspace/pages" || path.value == "workspace" && kinds == setOf(AgentResourceKind.PAGE)

    private val CONNECTOR_ID = Regex(AgentPathPatterns.ANY_VERSION_UUID)
}
