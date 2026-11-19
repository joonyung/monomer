package cc.monomer.identity.domain.model

import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.WorkspaceId
import java.time.Instant

data class Workspace(
    val id: WorkspaceId,
    val organizationId: OrganizationId,
    val displayName: String,
    val createdAt: Instant,
    val publicPages: Boolean,
    val reportingTimeZone: ReportingTimeZone,
    val sample: cc.monomer.identity.api.WorkspaceSampleMarker?,
)

// A Workspace the user can open, with the role their direct membership grants.
data class AccessibleWorkspace(
    val workspace: Workspace,
    val role: WorkspaceRole,
)
