package cc.monomer.authority.domain

import cc.monomer.authority.api.LineageEdge
import cc.monomer.authority.api.ResourceRef
import cc.monomer.identity.api.WorkspaceId

data class CurrentResourceNode(
    val resourceRef: ResourceRef,
    val workspaceId: WorkspaceId,
    val missingDefinitionName: String? = null,
)

data class CurrentResourceGraphSnapshot(
    val workspaceId: WorkspaceId,
    val nodes: List<CurrentResourceNode>,
    val edges: List<LineageEdge>,
    val unresolvedResourceRefs: Set<ResourceRef>,
)
