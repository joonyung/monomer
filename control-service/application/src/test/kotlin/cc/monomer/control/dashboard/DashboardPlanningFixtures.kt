@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.connector.api.ConnectorId
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.application.DashboardQueryPlanningPort
import cc.monomer.dashboard.domain.DashboardNamedQuery
import cc.monomer.dashboard.domain.DashboardResolvedQuery
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.IdentityCredentialEvidence
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import kotlinx.serialization.json.JsonObject
import java.time.Instant

fun semanticPlanningCommand(
    workspaceId: WorkspaceId,
    connectorId: ConnectorId,
    query: JsonObject,
    credential: PresentedCredential?,
    expectedOAuthResource: ExpectedOAuthResource?,
    deadline: Instant?,
    dashboardId: DashboardId,
    readerEvidence: IdentityCredentialEvidence?,
) = DashboardQueryPlanningPort.ResolveQueryCommand(
    workspaceId, DashboardNamedQuery.Semantic("query", connectorId, query), DashboardResolvedQuery.Semantic(query),
    credential, expectedOAuthResource, deadline, dashboardId, readerEvidence,
)
