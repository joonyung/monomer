package cc.monomer.dashboard.api

import cc.monomer.identity.api.WorkspaceId

interface DashboardSearchProjection {
    fun upsert(fields: DashboardSearchFields)
    fun delete(workspaceId: WorkspaceId, dashboardId: DashboardId)
}

data class DashboardSearchFields(
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val primaryNames: List<String>,
    val descriptions: List<String>,
    val structuralNames: List<String>,
)

interface DashboardSearchReadPort {
    fun inventory(): List<DashboardSearchFields>
}
