package cc.monomer.identity.domain.port

import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.Workspace

interface SampleWorkspaceRepository {
    fun findSampleByOrganization(organizationId: OrganizationId): Workspace?
    fun findSampleById(workspaceId: WorkspaceId): Workspace?
    fun readSampleInitiatorEvidence(workspaceId: WorkspaceId, dataset: String): cc.monomer.identity.api.SampleWorkspaceInitiatorEvidence?
    fun markSampleReady(workspaceId: WorkspaceId, dataset: String)
}
