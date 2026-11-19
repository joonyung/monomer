package cc.monomer.dashboard.application

import cc.monomer.dashboard.api.DashboardSearchFields
import cc.monomer.dashboard.domain.Dashboard
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive

internal fun Dashboard.toSearchFields(): DashboardSearchFields = DashboardSearchFields(
    workspaceId = workspaceId,
    dashboardId = id,
    primaryNames = listOf(definition.meta.title),
    descriptions = listOfNotNull(definition.meta.description),
    structuralNames = definition.queries.values.filterIsInstance<cc.monomer.dashboard.domain.DashboardNamedQuery.Semantic>().flatMap { namedQuery ->
        (namedQuery.query.getValue("metrics") as JsonArray).map { it.jsonPrimitive.content }
    }.distinct(),
)
