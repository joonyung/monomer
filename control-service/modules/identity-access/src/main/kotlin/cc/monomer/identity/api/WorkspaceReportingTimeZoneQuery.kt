package cc.monomer.identity.api

import java.time.ZoneId

interface WorkspaceReportingTimeZoneQuery {
    suspend fun reportingTimeZone(workspaceId: WorkspaceId): ZoneId
}
