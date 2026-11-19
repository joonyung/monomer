package cc.monomer.dashboard.application

import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardSearchFields
import cc.monomer.dashboard.api.DashboardSearchProjection
import cc.monomer.identity.api.WorkspaceId

internal fun testSearchProjection(upsert: (DashboardSearchFields) -> Unit) = object : DashboardSearchProjection {
    override fun upsert(fields: DashboardSearchFields) = upsert.invoke(fields)
    override fun delete(workspaceId: WorkspaceId, dashboardId: DashboardId) = Unit
}
