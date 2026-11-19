package cc.monomer.identity.application.workspace

import cc.monomer.identity.api.SampleWorkspace
import cc.monomer.identity.api.SampleWorkspaceProvisioningUseCase
import cc.monomer.identity.api.WorkspaceSampleMarker
import cc.monomer.identity.api.WorkspaceSampleState
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.application.AdminAuthorization
import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.ReportingTimeZone
import cc.monomer.identity.domain.model.Workspace
import cc.monomer.identity.domain.model.WorkspaceMembership
import cc.monomer.identity.domain.port.*
import java.time.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SampleWorkspaceService(
    private val transactionRunner: TransactionRunner,
    private val adminAuthorization: AdminAuthorization,
    private val workspaceRepository: WorkspaceRepository,
    private val sampleWorkspaceRepository: SampleWorkspaceRepository,
    private val workspaceAuthorizationBootstrap: WorkspaceAuthorizationBootstrap,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
    private val authorizationAssignmentRepository: AuthorizationAssignmentRepository,
    private val auditLogRepository: AuditLogRepository,
    private val auditEventFactory: AuditEventFactory,
    private val clock: Clock,
) : SampleWorkspaceProvisioningUseCase {
    override suspend fun create(
        actorUserId: cc.monomer.identity.api.PrincipalId,
        organizationId: cc.monomer.identity.api.OrganizationId,
        dataset: String,
    ): SampleWorkspace {
        require(dataset in setOf("acme-cloud@1", "acme-cloud@2", "acme-cloud@3")) { "Unsupported sample dataset" }
        val actor = UserId(actorUserId.value)
        val organization = OrganizationId(organizationId.value)
        return sampleOperation { transactionRunner.run {
            adminAuthorization.requireOrganizationAdminForWrite(actor.asPrincipal(), organization)
            sampleWorkspaceRepository.findSampleByOrganization(organization)?.let { return@run it.toSampleWorkspace() }
            val now = clock.instant()
            val workspace = Workspace(WorkspaceId.generate(), organization, "Acme Cloud (sample)", now, false, ReportingTimeZone.UTC,
                WorkspaceSampleMarker(dataset, WorkspaceSampleState.PROVISIONING, actorUserId))
            workspaceRepository.insert(workspace)
            workspaceAuthorizationBootstrap.createInitialBundle(workspace.id, actor, actor, now)
            auditLogRepository.insert(auditEventFactory.build(actor.asPrincipal(), "workspace.create", workspace.id,
                organization, "workspace", workspace.id.value, "{}"))
            workspace.toSampleWorkspace()
        } }
    }

    override suspend fun findByOrganization(actorUserId: cc.monomer.identity.api.PrincipalId, organizationId: cc.monomer.identity.api.OrganizationId): SampleWorkspace? =
        sampleOperation { transactionRunner.run {
            val organization = OrganizationId(organizationId.value)
            adminAuthorization.requireOrganizationAdminForWrite(UserId(actorUserId.value).asPrincipal(), organization)
            sampleWorkspaceRepository.findSampleByOrganization(organization)?.toSampleWorkspace()
        } }

    override suspend fun readMarker(workspaceId: cc.monomer.identity.api.WorkspaceId): WorkspaceSampleMarker? =
        transactionRunner.readOnly { sampleWorkspaceRepository.findSampleById(WorkspaceId(workspaceId.value))?.sample }

    override suspend fun readInitiatorEvidence(workspaceId: cc.monomer.identity.api.WorkspaceId, dataset: String): cc.monomer.identity.api.SampleWorkspaceInitiatorEvidence? {
        require(dataset in setOf("acme-cloud@1", "acme-cloud@2", "acme-cloud@3")) { "Unsupported sample dataset" }
        return transactionRunner.readOnly { sampleWorkspaceRepository.readSampleInitiatorEvidence(WorkspaceId(workspaceId.value), dataset) }
    }

    override suspend fun markReady(workspaceId: cc.monomer.identity.api.WorkspaceId, dataset: String) {
        require(dataset in setOf("acme-cloud@1", "acme-cloud@2", "acme-cloud@3")) { "Unsupported sample dataset" }
        transactionRunner.run { sampleWorkspaceRepository.markSampleReady(WorkspaceId(workspaceId.value), dataset) }
    }

    override suspend fun addAdminOwner(actorUserId: cc.monomer.identity.api.PrincipalId, workspaceId: cc.monomer.identity.api.WorkspaceId) {
        val actor = UserId(actorUserId.value)
        val workspace = WorkspaceId(workspaceId.value)
        sampleOperation { transactionRunner.run {
            val current = requireNotNull(sampleWorkspaceRepository.findSampleById(workspace)) { "Sample workspace is unavailable" }
            adminAuthorization.requireOrganizationAdminForWrite(actor.asPrincipal(), current.organizationId)
            val inserted = workspaceMemberRepository.insert(WorkspaceMembership(workspace, actor, actor, clock.instant(), WorkspaceRole.OWNER)) == WorkspaceMemberInsertion.Inserted
            if (inserted) auditLogRepository.insert(auditEventFactory.build(actor.asPrincipal(), "workspace.member.add", workspace,
                null, "user", actor.value, buildJsonObject { put("role", WorkspaceRole.OWNER.name) }.toString()))
            val changed = authorizationAssignmentRepository.setMemberRole(workspace, actor, WorkspaceRole.OWNER) == AuthorizationAssignmentTransition.Changed
            if (changed) auditLogRepository.insert(auditEventFactory.build(actor.asPrincipal(), "workspace.member.role.set", workspace,
                null, "user", actor.value, buildJsonObject { put("role", WorkspaceRole.OWNER.name) }.toString()))
        } }
    }
}

private suspend fun <T> sampleOperation(action: suspend () -> T): T = try {
    action()
} catch (_: cc.monomer.identity.application.OrganizationAdminRequiredException) {
    throw cc.monomer.identity.api.SampleWorkspaceAdminRequiredException()
} catch (_: FreeWorkspaceLimitException) {
    throw cc.monomer.identity.api.SampleWorkspaceLimitExceededException()
}

private fun Workspace.toSampleWorkspace() = SampleWorkspace(cc.monomer.identity.api.WorkspaceId(id.value),
    cc.monomer.identity.api.OrganizationId(organizationId.value), displayName, createdAt, requireNotNull(sample))
