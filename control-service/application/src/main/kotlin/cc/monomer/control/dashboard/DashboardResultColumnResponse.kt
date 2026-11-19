package cc.monomer.control.dashboard

import cc.monomer.control.semantic.inbound.rest.MetricDisplayDto
import cc.monomer.dashboard.application.DashboardResultReadPort

data class DashboardResultColumnResponse(val name: String, val logicalType: String, val nullable: Boolean, val display: MetricDisplayDto?) {
    companion object {
        fun from(column: DashboardResultReadPort.Column): DashboardResultColumnResponse =
            DashboardResultColumnResponse(column.name, column.logicalType, column.nullable, column.display?.let(MetricDisplayDto::fromDomain))
    }
}
