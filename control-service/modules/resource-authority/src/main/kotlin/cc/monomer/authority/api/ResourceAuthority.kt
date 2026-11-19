@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.authority.api

import cc.monomer.identity.api.WorkspaceId
import kotlin.uuid.Uuid

/** Resources which can participate in the current lineage. */
enum class ResourceKind {
    RELATION,
    SEMANTIC_MODEL,
    METRIC,
}

data class ResourceRef(
    val kind: ResourceKind,
    val id: Uuid,
) : Comparable<ResourceRef> {
    override fun compareTo(other: ResourceRef): Int =
        kind.name.compareTo(other.kind.name)
            .takeIf { comparison -> comparison != 0 }
            ?: compareUnsignedUuidBytes(id, other.id)
}

data class LineageEdge(
    val dependent: ResourceRef,
    val parent: ResourceRef,
) : Comparable<LineageEdge> {
    init {
        require(dependent != parent) { "lineage edge cannot be self-referential" }
    }

    override fun compareTo(other: LineageEdge): Int =
        dependent.compareTo(other.dependent)
            .takeIf { comparison -> comparison != 0 }
            ?: parent.compareTo(other.parent)
}

/**
 * The current topology around one focused Resource.
 *
 * `ancestors` follows `dependent -> parent` edges from [focus], while
 * `descendants` follows the same edges in reverse. `siblings` contains
 * Resources that share an immediate parent with [focus] and are not already in
 * the focus cone. The response is one
 * complete cone read from one current definition snapshot; labels,
 * definitions, and any authorization verdict are intentionally absent.
 */
data class MissingDefinitionReference(val resourceRef: ResourceRef, val name: String) {
    init { require(name.isNotBlank()) }
}

data class CurrentResourceFocusCone(
    val workspaceId: WorkspaceId,
    val focus: ResourceRef,
    val ancestors: List<ResourceRef>,
    val descendants: List<ResourceRef>,
    val siblings: List<ResourceRef>,
    val edges: List<LineageEdge>,
    val siblingEdges: List<LineageEdge>,
    val missingDefinitions: List<MissingDefinitionReference>,
) {
    init {
        require(focus !in ancestors) { "focus must not be listed as an ancestor" }
        require(focus !in descendants) { "focus must not be listed as a descendant" }
        require(ancestors == ancestors.distinct().sorted()) {
            "resource focus cone ancestors must be sorted and unique"
        }
        require(descendants == descendants.distinct().sorted()) {
            "resource focus cone descendants must be sorted and unique"
        }
        require(siblings == siblings.distinct().sorted()) {
            "resource focus cone siblings must be sorted and unique"
        }
        require(focus !in siblings && siblings.none { it in ancestors || it in descendants }) {
            "resource focus cone siblings must remain outside the canonical cone"
        }
        val missingRefs = missingDefinitions.map { it.resourceRef }.toSet()
        require(missingRefs.size == missingDefinitions.size)
        require(missingRefs.none { it == focus || it in ancestors || it in descendants || it in siblings })
        val nodes = (listOf(focus) + ancestors + descendants + missingRefs).toSet()
        require(edges == edges.distinct().sorted()) {
            "resource focus cone edges must be sorted and unique"
        }
        require(edges.all { edge -> edge.dependent in nodes && edge.parent in nodes }) {
            "resource focus cone edges must remain inside the cone"
        }
        require(siblingEdges == siblingEdges.distinct().sorted()) {
            "resource focus cone sibling edges must be sorted and unique"
        }
        require(siblingEdges.all { edge -> edge.dependent in siblings && (edge.parent in ancestors || edge.parent in missingRefs) }) {
            "resource focus cone sibling edges must connect immediate siblings to focus ancestors"
        }
    }
}

enum class CurrentResourceFocusConeDenial {
    RESOURCE_MISSING,
    DUPLICATE_RESOURCE_OWNERSHIP,
    CROSS_WORKSPACE_RESOURCE,
    CROSS_WORKSPACE_LINEAGE,
    UNRESOLVED_LINEAGE,
    LINEAGE_CYCLE,
}

sealed interface CurrentResourceFocusConeResolution {
    data class Resolved(
        val cone: CurrentResourceFocusCone,
    ) : CurrentResourceFocusConeResolution

    data class Denied(
        val reason: CurrentResourceFocusConeDenial,
    ) : CurrentResourceFocusConeResolution
}

/** Reads a complete current ancestor/descendant cone for the Lineage surface. */
interface CurrentResourceFocusConePort {
    /** Reads current Resources and edges in the focused cone. */
    fun readCurrentFocusCone(
        workspaceId: WorkspaceId,
        focus: ResourceRef,
    ): CurrentResourceFocusConeResolution
}

private fun compareUnsignedUuidBytes(left: Uuid, right: Uuid): Int {
    val leftBytes = left.toByteArray()
    val rightBytes = right.toByteArray()
    for (index in leftBytes.indices) {
        val comparison = leftBytes[index].toInt().and(0xff)
            .compareTo(rightBytes[index].toInt().and(0xff))
        if (comparison != 0) {
            return comparison
        }
    }
    return 0
}
