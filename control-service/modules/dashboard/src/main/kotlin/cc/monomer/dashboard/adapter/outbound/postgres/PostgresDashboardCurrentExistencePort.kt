package cc.monomer.dashboard.adapter.outbound.postgres

import cc.monomer.dashboard.api.DashboardCurrentExistencePort
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.identity.api.WorkspaceId
import javax.sql.DataSource

class PostgresDashboardCurrentExistencePort(
    dataSource: DataSource,
) : DashboardCurrentExistencePort {
    private val jdbc = PostgresDashboardJdbc(dataSource)

    override fun existsInCallerTransaction(workspaceId: WorkspaceId, dashboardId: DashboardId): Boolean {
        jdbc.requireOwnerTransaction()
        return jdbc.queryOne("SELECT dashboard_id FROM dashboard.dashboards WHERE workspace_id = ? AND dashboard_id = ? FOR SHARE", {
            it.setUuid(1, workspaceId.value); it.setUuid(2, dashboardId.value)
        }, { true }) ?: false
    }
}
