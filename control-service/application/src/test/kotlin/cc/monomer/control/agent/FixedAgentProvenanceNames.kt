@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.agent

import cc.monomer.control.agentapp.FixedAgentAppDisplays
import cc.monomer.identity.api.PrincipalDisplayNames
import kotlin.uuid.Uuid

/** Knows one person, Mina Park; every other principal has no readable name. */
object FixedPrincipalDisplayNames : PrincipalDisplayNames {
    val MINA: Uuid = Uuid.parse("01900000-0000-7000-8000-00000000a11c")

    override suspend fun read(userIds: Set<Uuid>): Map<Uuid, String> =
        userIds.filter { it == MINA }.associateWith { "Mina Park" }
}

/** Names Mina Park and the Claude app, as [AgentProvenanceNames] would from Identity. */
fun fixedAgentProvenanceNames(): AgentProvenanceNames = AgentProvenanceNames(FixedPrincipalDisplayNames, FixedAgentAppDisplays)
