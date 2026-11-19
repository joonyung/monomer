package cc.monomer.dashboard.adapter.outbound.postgres

import cc.monomer.dashboard.api.DashboardSearchFields
import cc.monomer.dashboard.api.DashboardSearchReadPort
import cc.monomer.dashboard.application.toSearchFields
import org.springframework.transaction.support.TransactionSynchronizationManager
import javax.sql.DataSource

class PostgresDashboardSearchReadPort(
    dataSource: DataSource,
) : DashboardSearchReadPort {
    private val jdbc = PostgresDashboardJdbc(dataSource)

    override fun inventory(): List<DashboardSearchFields> {
        check(TransactionSynchronizationManager.isActualTransactionActive())
        return jdbc.queryMany(
            "SELECT dashboard_id, workspace_id, definition, created_at, updated_at, updated_by, credential_surface, agent_client_id FROM dashboard.dashboards",
            {},
        ) { row -> readDashboard(row).toSearchFields() }
    }
}
