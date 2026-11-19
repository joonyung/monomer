package cc.monomer.identity.outbound.postgres

import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.api.WorkspaceReportingTimeZoneQuery
import cc.monomer.identity.domain.port.TransactionRunner
import java.time.ZoneId
import kotlin.uuid.toJavaUuid
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component

@Component
class PostgresWorkspaceReportingTimeZoneQuery(
    @param:Qualifier("postgresJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
    private val transactionRunner: TransactionRunner,
) : WorkspaceReportingTimeZoneQuery {
    override suspend fun reportingTimeZone(workspaceId: WorkspaceId): ZoneId = transactionRunner.readOnly {
        val storedTimeZone = jdbc.query(
            """SELECT workspace.reporting_time_zone FROM identity_runtime.workspaces workspace
               JOIN identity_runtime.organizations organization ON organization.id = workspace.organization_id
               WHERE workspace.id = :workspaceId""",
            mapOf("workspaceId" to workspaceId.value.toJavaUuid()),
        ) { row, _ -> row.getString("reporting_time_zone") }.singleOrNull()
            ?: throw WorkspaceReportingTimeZoneMissingException(workspaceId)
        storedReportingTimeZone(storedTimeZone).zoneId
    }
}

class WorkspaceReportingTimeZoneMissingException(workspaceId: WorkspaceId) :
    IllegalStateException("workspace $workspaceId does not exist; its reporting time zone cannot be read")
