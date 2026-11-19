@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package cc.monomer.control.publicapi

import cc.monomer.agent.api.*

internal object AgentManagementJson {
    fun json(value: AgentManagementResource): Map<String, Any?> = mapOf(
        "kind" to value.kind.name,
        "id" to value.id.toString(),
    )

    fun json(value: AgentOrganizationSelf): Map<String, Any?> = mapOf(
        "userId" to value.userId.toString(),
        "displayName" to value.displayName,
        "role" to value.role.name,
    )

    fun json(value: AgentOrganizationBody): Map<String, Any?> = mapOf(
        "id" to value.id.toString(),
        "displayName" to value.displayName,
        "caller" to json(value.caller),
    )

    fun json(value: AgentOrganizationMemberBody): Map<String, Any?> = mapOf(
        "userId" to value.userId.toString(),
        "displayName" to value.displayName,
        "email" to value.email,
        "role" to value.role.name,
    )

    fun json(value: AgentOrganizationInvitationBody): Map<String, Any?> = mapOf(
        "id" to value.id.toString(),
        "email" to value.email,
        "status" to value.status.name,
        "expiresAt" to value.expiresAt.toString(),
        "createdAt" to value.createdAt.toString(),
        "lastDeliveryAttemptAt" to value.lastDeliveryAttemptAt?.toString(),
        "lastSentAt" to value.lastSentAt?.toString(),
        "workspaceAccess" to value.workspaceAccess.map { access -> mapOf(
            "workspaceId" to access.workspaceId.toString(),
            "workspaceDisplayName" to access.workspaceDisplayName,
            "role" to access.role.name,
        ) },
    ).filterValues { it != null }

    fun json(value: AgentWorkspaceAccessBody): Map<String, Any?> = mapOf(
        "role" to value.role.name,
        "organizationId" to value.organizationId.toString(),
        "authorizationRevision" to value.authorizationRevision.toString(),
        "reportingTimeZone" to value.reportingTimeZone.id,
    )

    fun json(value: AgentWorkspaceAuthorizationBody): Map<String, Any?> = mapOf(
        "authorizationRevision" to value.authorizationRevision.toString(),
        "members" to value.members.map { json(it) },
    )

    fun json(value: AgentLineageConnector): Map<String, Any?> = mapOf(
        "id" to value.id.toString(),
        "displayName" to value.displayName,
        "type" to value.type.name,
    )

    fun json(value: AgentLineageResource): Map<String, Any?> = mapOf(
        "ref" to json(value.ref),
        "label" to value.label,
        "missing" to value.missing,
        "address" to value.address,
        "description" to value.description,
        "connector" to value.connector?.let { json(it) },
    ).filterValues { it != null }

    fun json(value: AgentLineageEdge): Map<String, Any?> = mapOf(
        "dependent" to json(value.dependent),
        "parent" to json(value.parent),
    )

    fun json(value: AgentLineageEntityPeer): Map<String, Any?> = mapOf(
        "resource" to json(value.resource),
        "sharedEntities" to value.sharedEntities,
        "alreadyInCone" to value.alreadyInCone,
    )

    fun json(value: AgentLineageColumn): Map<String, Any?> = mapOf(
        "name" to value.name,
        "nativeType" to value.nativeType,
        "nullable" to value.nullable,
        "hasDefault" to value.hasDefault,
        "primaryKey" to value.primaryKey,
        "foreignKey" to value.foreignKey,
    )

    fun json(value: AgentLineageEntity): Map<String, Any?> = mapOf(
        "name" to value.name,
        "label" to value.label,
        "description" to value.description,
        "type" to value.type.name,
    ).filterValues { it != null }

    fun json(value: AgentLineageDimension): Map<String, Any?> = mapOf(
        "name" to value.name,
        "label" to value.label,
        "description" to value.description,
        "type" to value.type.name,
        "granularity" to value.granularity?.name,
    ).filterValues { it != null }

    fun json(value: AgentLineageMeasure): Map<String, Any?> = mapOf(
        "name" to value.name,
        "label" to value.label,
        "description" to value.description,
        "aggregation" to value.aggregation.name,
    ).filterValues { it != null }

    fun json(value: AgentLineageMetricInput): Map<String, Any?> = mapOf(
        "kind" to value.kind.name,
        "name" to value.name,
        "semanticModelName" to value.semanticModelName,
    ).filterValues { it != null }

    fun json(value: AgentLineageResourceBody): Map<String, Any?> = mapOf(
        "workspaceId" to value.workspaceId.toString(),
        "focus" to json(value.focus),
        "ancestors" to value.ancestors.map { json(it) },
        "descendants" to value.descendants.map { json(it) },
        "siblings" to value.siblings.map { json(it) },
        "missingDefinitions" to value.missingDefinitions.map { json(it) },
        "dashboardUsages" to value.dashboardUsages.map { usage -> mapOf("dashboardId" to usage.dashboardId.toString(), "title" to usage.title, "queryNames" to usage.queryNames, "metricIds" to usage.metricIds.map { it.toString() }) },
        "edges" to value.edges.map { json(it) },
        "siblingEdges" to value.siblingEdges.map { json(it) },
        "entityPeers" to value.entityPeers.map { json(it) },
        "detail" to json(value.detail),
    )

    fun json(value: AgentWorkspaceMemberBody): Map<String, Any?> = when (value) {
        is AgentWorkspaceMemberBody.Member -> mapOf("userId" to value.userId.toString(), "displayName" to value.displayName, "email" to value.email, "role" to value.role.name)
        is AgentWorkspaceMemberBody.Candidate -> mapOf("userId" to value.userId.toString(), "displayName" to value.displayName, "email" to value.email)
    }

    fun json(value: AgentLineageDetail): Map<String, Any?> = when (value) {
        is AgentLineageDetail.Relation -> mapOf("relation" to mapOf("columns" to value.columns.map { json(it) }))
        is AgentLineageDetail.SemanticModel -> mapOf("semanticModel" to mapOf("entities" to value.entities.map { json(it) }, "dimensions" to value.dimensions.map { json(it) }, "measures" to value.measures.map { json(it) }))
        is AgentLineageDetail.Metric -> mapOf("metric" to mapOf("type" to value.type.name, "inputs" to value.inputs.map { json(it) }, "timeGranularity" to value.timeGranularity?.name).filterValues { it != null })
    }
}
