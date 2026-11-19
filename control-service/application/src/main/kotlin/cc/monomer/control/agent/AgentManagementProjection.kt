@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package cc.monomer.control.agent

import cc.monomer.agent.api.*
import cc.monomer.control.lineage.*
import cc.monomer.identity.application.grant.WorkspaceAuthorizationAssignments
import cc.monomer.identity.domain.model.*
import java.time.Instant

internal fun WorkspaceMember.managementBody() = AgentWorkspaceMemberBody.Member(user.userId.value, user.displayName, user.primaryEmail, role)
internal fun User.managementCandidate() = AgentWorkspaceMemberBody.Candidate(userId.value, displayName, primaryEmail)
internal fun OrganizationMember.managementBody() = AgentOrganizationMemberBody(user.userId.value, user.displayName, user.primaryEmail,
    AgentOrganizationRole.valueOf(role.name))
internal fun InvitationPreview.managementBody(now: Instant) = invitation.let {
    AgentOrganizationInvitationBody(it.id, it.deliveryEmail, if (now >= it.expiresAt) AgentInvitationStatus.EXPIRED else AgentInvitationStatus.PENDING,
        it.expiresAt, it.createdAt, it.lastDeliveryAttemptAt, it.lastSentAt,
        workspaceAccess.mapNotNull { access ->
            access.workspaceDisplayName?.let { name -> AgentInvitationWorkspaceAccessBody(access.workspaceId.value, name, access.role) }
        })
}
internal fun WorkspaceAuthorizationAssignments.managementBody() = AgentWorkspaceAuthorizationBody(
    AgentRevision(authorizationRevision.value),
    members.map { it.managementBody() })
internal fun cc.monomer.authority.api.ResourceRef.managementRef() = AgentManagementResource(AgentManagementResourceKind.valueOf(kind.name), id)
internal fun LineageConnector.managementBody() = AgentLineageConnector(id.value, displayName, type)
internal fun LineageResource.managementBody() = AgentLineageResource(ref.managementRef(), label, address, description, connector?.managementBody(), missing)
internal fun LineageGraph.managementBody() = AgentLineageResourceBody(
    workspaceId.value, focus.managementBody(), ancestors.map { it.managementBody() }, descendants.map { it.managementBody() }, siblings.map { it.managementBody() },
    edges.map { AgentLineageEdge(it.dependent.managementRef(), it.parent.managementRef()) },
    siblingEdges.map { AgentLineageEdge(it.dependent.managementRef(), it.parent.managementRef()) },
    entityPeers.map { AgentLineageEntityPeer(it.resource.managementBody(), it.sharedEntities, it.alreadyInCone) },
    when (focus.ref.kind) {
        cc.monomer.authority.api.ResourceKind.RELATION -> AgentLineageDetail.Relation(checkNotNull(detail.relation).columns.map {
            AgentLineageColumn(it.name, it.nativeType, it.nullable, it.hasDefault, it.primaryKey, it.foreignKey) })
        cc.monomer.authority.api.ResourceKind.SEMANTIC_MODEL -> checkNotNull(detail.semanticModel).let { model -> AgentLineageDetail.SemanticModel(
            model.entities.map { AgentLineageEntity(it.name, it.label, it.description, AgentSemanticEntityKind.valueOf(it.type.name)) },
            model.dimensions.map { AgentLineageDimension(it.name, it.label, it.description, AgentSemanticDimensionKind.valueOf(it.type.name), it.granularity?.let { value -> AgentTimeGranularity.valueOf(value.name) }) },
            model.measures.map { AgentLineageMeasure(it.name, it.label, it.description, AgentSemanticAggregation.valueOf(it.aggregation.name)) }) }
        cc.monomer.authority.api.ResourceKind.METRIC -> checkNotNull(detail.metric).let { metric -> AgentLineageDetail.Metric(
            AgentMetricType.valueOf(metric.type.name), metric.inputs.map { AgentLineageMetricInput(AgentLineageMetricInputKind.valueOf(it.kind.name), it.name, it.semanticModelName) },
            metric.timeGranularity?.let { AgentTimeGranularity.valueOf(it.name) }) }
    }, missingDefinitions.map { it.managementBody() }, dashboardUsages.map { AgentLineageDashboardUsage(it.dashboardId, it.title, it.queryNames, it.metricIds) })
