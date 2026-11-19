package cc.monomer.identity.application.workspace

import cc.monomer.identity.application.AccessDeniedException
import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.application.normalizedDisplayName
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.ReportingTimeZone
import cc.monomer.identity.domain.model.Workspace
import cc.monomer.identity.domain.port.AuditLogRepository
import cc.monomer.identity.domain.port.WorkspaceQuery
import cc.monomer.identity.domain.port.WorkspaceRepository
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface RenameWorkspaceUseCase {
    suspend fun rename(command: Command): Workspace
    data class Command(
        val actorUserId: UserId,
        val workspaceId: WorkspaceId,
        val displayName: String,
    )
}

class RenameWorkspaceService(
    private val workspaceMutationRunner: OwnerWorkspaceMutationRunner,
    private val workspaceQuery: WorkspaceQuery,
    private val workspaceRepository: WorkspaceRepository,
    private val auditLogRepository: AuditLogRepository,
    private val auditEventFactory: AuditEventFactory,
) : RenameWorkspaceUseCase {

    override suspend fun rename(command: RenameWorkspaceUseCase.Command): Workspace {
        val displayName = normalizedDisplayName(command.displayName, "workspace")
        val workspace = workspaceQuery.find(command.workspaceId)
            ?: throw AccessDeniedException("workspace ${command.workspaceId} not found")
        val renamed = workspace.copy(displayName = displayName)
        workspaceMutationRunner.runAsOrganizationAdminOrOwner(command.actorUserId, command.workspaceId) { _ ->
            workspaceRepository.updateDisplayName(command.workspaceId, displayName)
            auditLogRepository.insert(
                auditEventFactory.build(
                    actorId = command.actorUserId.asPrincipal(),
                    action = "workspace.rename",
                    organizationId = workspace.organizationId,
                    workspaceId = command.workspaceId,
                    resourceType = "workspace",
                    resourceId = command.workspaceId.value,
                    details = buildJsonObject {
                        put("displayName", displayName)
                    }.toString(),
                ),
            )
        }
        return renamed
    }
}

interface SetWorkspacePublicPagesUseCase {
    suspend fun set(command: Command): Workspace
    data class Command(val actorUserId: UserId, val workspaceId: WorkspaceId, val publicPages: Boolean)
}

class SetWorkspacePublicPagesService(
    private val workspaceMutationRunner: OwnerWorkspaceMutationRunner,
    private val workspaceQuery: WorkspaceQuery,
    private val workspaceRepository: WorkspaceRepository,
    private val auditLogRepository: AuditLogRepository,
    private val auditEventFactory: AuditEventFactory,
) : SetWorkspacePublicPagesUseCase {
    override suspend fun set(command: SetWorkspacePublicPagesUseCase.Command): Workspace {
        val workspace = workspaceQuery.find(command.workspaceId)
            ?: throw AccessDeniedException("workspace ${command.workspaceId} not found")
        workspaceMutationRunner.run(command.actorUserId, command.workspaceId) {
            if (workspaceRepository.updatePublicPages(command.workspaceId, command.publicPages)) {
                auditLogRepository.insert(auditEventFactory.build(
                    actorId = command.actorUserId.asPrincipal(),
                    action = "workspace.public_pages.set",
                    organizationId = workspace.organizationId,
                    workspaceId = command.workspaceId,
                    resourceType = "workspace",
                    resourceId = command.workspaceId.value,
                    details = buildJsonObject { put("publicPages", command.publicPages) }.toString(),
                ))
            }
        }
        return workspace.copy(publicPages = command.publicPages)
    }
}

interface SetWorkspaceReportingTimeZoneUseCase {
    suspend fun set(command: Command): Result
    data class Command(val actorUserId: UserId, val workspaceId: WorkspaceId, val timeZone: String)
    sealed interface Result {
        data class Set(val workspace: Workspace) : Result
        data class UnsupportedTimeZone(val timeZone: String) : Result
    }
}

class SetWorkspaceReportingTimeZoneService(
    private val workspaceMutationRunner: OwnerWorkspaceMutationRunner,
    private val workspaceQuery: WorkspaceQuery,
    private val workspaceRepository: WorkspaceRepository,
    private val auditLogRepository: AuditLogRepository,
    private val auditEventFactory: AuditEventFactory,
) : SetWorkspaceReportingTimeZoneUseCase {
    override suspend fun set(command: SetWorkspaceReportingTimeZoneUseCase.Command): SetWorkspaceReportingTimeZoneUseCase.Result {
        val reportingTimeZone = ReportingTimeZone.of(command.timeZone)
            ?: return SetWorkspaceReportingTimeZoneUseCase.Result.UnsupportedTimeZone(command.timeZone)
        val workspace = workspaceQuery.find(command.workspaceId)
            ?: throw AccessDeniedException("workspace ${command.workspaceId} not found")
        workspaceMutationRunner.runAsOrganizationAdminOrOwner(command.actorUserId, command.workspaceId) { _ ->
            if (workspaceRepository.updateReportingTimeZone(command.workspaceId, reportingTimeZone)) {
                auditLogRepository.insert(auditEventFactory.build(
                    actorId = command.actorUserId.asPrincipal(),
                    action = "workspace.reporting_time_zone.set",
                    organizationId = workspace.organizationId,
                    workspaceId = command.workspaceId,
                    resourceType = "workspace",
                    resourceId = command.workspaceId.value,
                    details = buildJsonObject { put("reportingTimeZone", reportingTimeZone.id) }.toString(),
                ))
            }
        }
        return SetWorkspaceReportingTimeZoneUseCase.Result.Set(workspace.copy(reportingTimeZone = reportingTimeZone))
    }
}
