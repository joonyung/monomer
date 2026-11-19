package cc.monomer.dashboard.application

import cc.monomer.dashboard.api.DashboardCurrentSemanticReferences
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.domain.DashboardDefinition
import cc.monomer.dashboard.domain.DashboardJson
import kotlinx.serialization.json.JsonObject

fun dashboardSemanticReferences(dashboardId: DashboardId, document: JsonObject): DashboardCurrentSemanticReferences {
    val meta = DashboardJson.objectNode(document.getValue("meta"), "meta")
    val queries = DashboardJson.objectNode(document.getValue("queries"), "queries")
    return DashboardCurrentSemanticReferences(
        dashboardId = dashboardId,
        title = DashboardJson.text(meta.getValue("title"), "meta.title"),
        queryMetricNames = queries.mapNotNull { (name, value) ->
            val query = DashboardJson.objectNode(value, "queries.$name")
            when (DashboardJson.text(query.getValue("type"), "queries.$name.type")) {
                "SEMANTIC" -> {
                    val semanticQuery = DashboardJson.objectNode(query.getValue("query"), "queries.$name.query")
                    name to DashboardJson.arrayNode(semanticQuery.getValue("metrics"), "queries.$name.query.metrics").mapIndexed { index, metric ->
                        DashboardJson.text(metric, "queries.$name.query.metrics[$index]").also {
                            require(DashboardDefinition.isSemanticName(it)) { "queries.$name.query.metrics[$index] is not a semantic name" }
                        }
                    }.toSet()
                }
                "SQL" -> null
                else -> error("queries.$name.type must be SEMANTIC or SQL")
            }
        }.toMap(),
    )
}
