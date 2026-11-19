package cc.monomer.authority.application

import cc.monomer.authority.api.CurrentResourceFocusCone
import cc.monomer.authority.api.CurrentResourceFocusConeDenial
import cc.monomer.authority.api.CurrentResourceFocusConeResolution
import cc.monomer.authority.api.LineageEdge
import cc.monomer.authority.api.ResourceRef
import cc.monomer.authority.domain.CurrentResourceGraphSnapshot
import cc.monomer.authority.domain.CurrentResourceNode
import cc.monomer.identity.api.WorkspaceId

/** Resolves one complete focus cone from a single current definition snapshot. */
class ResourceAuthorityFocusConeResolver {
    fun resolve(
        workspaceId: WorkspaceId,
        focus: ResourceRef,
        snapshot: CurrentResourceGraphSnapshot,
    ): CurrentResourceFocusConeResolution {
        if (snapshot.workspaceId != workspaceId) {
            return denied(CurrentResourceFocusConeDenial.CROSS_WORKSPACE_RESOURCE)
        }

        val nodesByRef = snapshot.nodes.groupBy { node -> node.resourceRef }
        val focusNode = nodesByRef[focus].orEmpty().let { nodes ->
            when {
                nodes.isEmpty() -> return denied(CurrentResourceFocusConeDenial.RESOURCE_MISSING)
                nodes.size != 1 ->
                    return denied(CurrentResourceFocusConeDenial.DUPLICATE_RESOURCE_OWNERSHIP)
                else -> nodes.single()
            }
        }
        if (focusNode.missingDefinitionName != null) return denied(CurrentResourceFocusConeDenial.RESOURCE_MISSING)
        if (focusNode.workspaceId != workspaceId) {
            return denied(CurrentResourceFocusConeDenial.CROSS_WORKSPACE_RESOURCE)
        }

        val parentsByDependent = snapshot.edges.groupBy(LineageEdge::dependent)
        val dependentsByParent = snapshot.edges.groupBy(LineageEdge::parent)

        val ancestors = when (val result = traverse(
            start = focus,
            adjacent = { resourceRef -> parentsByDependent[resourceRef].orEmpty().map(LineageEdge::parent) },
            nodesByRef = nodesByRef,
            unresolvedResourceRefs = snapshot.unresolvedResourceRefs,
            workspaceId = workspaceId,
        )) {
            is TraversalResult.Complete -> result.refs
            is TraversalResult.Denied -> return denied(result.reason)
        }
        val descendants = when (val result = traverse(
            start = focus,
            adjacent = { resourceRef -> dependentsByParent[resourceRef].orEmpty().map(LineageEdge::dependent) },
            nodesByRef = nodesByRef,
            unresolvedResourceRefs = snapshot.unresolvedResourceRefs,
            workspaceId = workspaceId,
        )) {
            is TraversalResult.Complete -> result.refs
            is TraversalResult.Denied -> return denied(result.reason)
        }

        val missingByRef = snapshot.nodes.filter { it.missingDefinitionName != null }.associateBy { it.resourceRef }
        val ancestorRefs = (ancestors - missingByRef.keys).sorted()
        val descendantRefs = (descendants - missingByRef.keys).sorted()
        val coneRefs = (listOf(focus) + ancestorRefs + descendantRefs).toSet()
        val siblingRefs = parentsByDependent[focus]
            .orEmpty()
            .asSequence()
            .map(LineageEdge::parent)
            .flatMap { parent -> dependentsByParent[parent].orEmpty().asSequence() }
            .map(LineageEdge::dependent)
            .filterNot { sibling -> sibling in coneRefs }
            .distinct()
            .sorted()
            .toList()
        for (sibling in siblingRefs) {
            val nodes = nodesByRef[sibling].orEmpty()
            if (nodes.size != 1) {
                return denied(
                    if (nodes.isEmpty()) CurrentResourceFocusConeDenial.UNRESOLVED_LINEAGE
                    else CurrentResourceFocusConeDenial.DUPLICATE_RESOURCE_OWNERSHIP,
                )
            }
            val node = nodes.single()
            if (node.workspaceId != workspaceId) {
                return denied(CurrentResourceFocusConeDenial.CROSS_WORKSPACE_LINEAGE)
            }
            if (sibling in snapshot.unresolvedResourceRefs) {
                return denied(CurrentResourceFocusConeDenial.UNRESOLVED_LINEAGE)
            }
        }
        val displayRefs = coneRefs + siblingRefs
        val missingRefs = ((ancestors + descendants).filter { it in missingByRef } + snapshot.edges
            .filter { it.dependent in displayRefs && it.parent in missingByRef }.map(LineageEdge::parent)).toSet()
        for (missingRef in missingRefs) {
            if (nodesByRef.getValue(missingRef).size != 1) {
                return denied(CurrentResourceFocusConeDenial.DUPLICATE_RESOURCE_OWNERSHIP)
            }
            if (missingByRef.getValue(missingRef).workspaceId != workspaceId) {
                return denied(CurrentResourceFocusConeDenial.CROSS_WORKSPACE_LINEAGE)
            }
        }
        val coneEdges = snapshot.edges
            .filter { edge -> edge.dependent in coneRefs && (edge.parent in coneRefs || edge.parent in missingRefs) }
            .distinct()
            .sorted()
        val focusParents = parentsByDependent[focus].orEmpty().mapTo(linkedSetOf(), LineageEdge::parent)
        val siblingEdges = snapshot.edges
            .filter { edge -> edge.dependent in siblingRefs && (edge.parent in focusParents || edge.parent in missingRefs) }
            .distinct()
            .sorted()

        return CurrentResourceFocusConeResolution.Resolved(
            CurrentResourceFocusCone(
                workspaceId = workspaceId,
                focus = focus,
                ancestors = ancestorRefs,
                descendants = descendantRefs,
                siblings = siblingRefs,
                edges = coneEdges,
                siblingEdges = siblingEdges,
                missingDefinitions = missingRefs.sorted().map { ref ->
                    cc.monomer.authority.api.MissingDefinitionReference(ref, checkNotNull(missingByRef.getValue(ref).missingDefinitionName))
                },
            ),
        )
    }

    private fun traverse(
        start: ResourceRef,
        adjacent: (ResourceRef) -> List<ResourceRef>,
        nodesByRef: Map<ResourceRef, List<CurrentResourceNode>>,
        unresolvedResourceRefs: Set<ResourceRef>,
        workspaceId: WorkspaceId,
    ): TraversalResult {
        val visited = linkedSetOf<ResourceRef>()
        val visiting = linkedSetOf<ResourceRef>()
        var denial: CurrentResourceFocusConeDenial? = null

        fun visit(resourceRef: ResourceRef): Boolean {
            if (!visiting.add(resourceRef)) {
                denial = CurrentResourceFocusConeDenial.LINEAGE_CYCLE
                return false
            }
            if (!visited.add(resourceRef)) {
                visiting -= resourceRef
                return true
            }

            val nodes = nodesByRef[resourceRef].orEmpty()
            if (nodes.isEmpty()) {
                denial = CurrentResourceFocusConeDenial.UNRESOLVED_LINEAGE
                return false
            }
            if (nodes.size != 1) {
                denial = CurrentResourceFocusConeDenial.DUPLICATE_RESOURCE_OWNERSHIP
                return false
            }
            val node = nodes.single()
            if (node.workspaceId != workspaceId) {
                denial = CurrentResourceFocusConeDenial.CROSS_WORKSPACE_LINEAGE
                return false
            }
            if (resourceRef in unresolvedResourceRefs) {
                denial = CurrentResourceFocusConeDenial.UNRESOLVED_LINEAGE
                return false
            }

            val complete = adjacent(resourceRef).distinct().all(::visit)
            visiting -= resourceRef
            return complete
        }

        return if (visit(start)) {
            TraversalResult.Complete(visited - start)
        } else {
            TraversalResult.Denied(
                denial ?: CurrentResourceFocusConeDenial.UNRESOLVED_LINEAGE,
            )
        }
    }

    private sealed interface TraversalResult {
        data class Complete(val refs: Set<ResourceRef>) : TraversalResult

        data class Denied(val reason: CurrentResourceFocusConeDenial) : TraversalResult
    }

    private fun denied(
        reason: CurrentResourceFocusConeDenial,
    ): CurrentResourceFocusConeResolution = CurrentResourceFocusConeResolution.Denied(reason)
}
