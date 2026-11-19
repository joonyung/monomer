@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.agent

import cc.monomer.agent.api.AgentActor
import cc.monomer.agent.api.AgentApp
import cc.monomer.agent.api.AgentPageActor
import cc.monomer.agent.api.AgentPageActorKind
import cc.monomer.control.agentapp.responses
import cc.monomer.identity.api.AgentAppDisplays
import cc.monomer.identity.api.AgentClientId
import cc.monomer.identity.api.PrincipalDisplayNames
import kotlin.uuid.Uuid

/**
 * Names the people and agent apps recorded on resources an Agent read returns, with the same names and apps the Web
 * shows. Owner adapters call it only after the caller is admitted to read those resources.
 */
class AgentProvenanceNames(
    private val principalDisplayNames: PrincipalDisplayNames,
    private val agentAppDisplays: AgentAppDisplays,
) {
    suspend fun read(actorIds: Collection<String?>, clientIds: Collection<AgentClientId?>): AgentProvenance {
        val userIds = actorIds.mapNotNull { id -> id?.let { runCatching { Uuid.parse(it) }.getOrNull() } }.toSet()
        val names = userIds.chunked(PRINCIPAL_BATCH)
            .flatMap { chunk -> principalDisplayNames.read(chunk.toSet()).entries }
            .associate { (id, name) -> id.toString() to name }
        val apps = agentAppDisplays.responses(clientIds)
            .mapValues { (_, app) -> AgentApp(app.clientId, app.name, app.verifiedHost) }
        return AgentProvenance(names, apps)
    }

    private companion object {
        /** PrincipalDisplayNames reads at most this many people per call. */
        const val PRINCIPAL_BATCH = 200
    }
}

class AgentProvenance(
    private val names: Map<String, String>,
    private val apps: Map<AgentClientId, AgentApp>,
) {
    fun actor(principalId: String?): AgentActor? = principalId?.let { AgentActor(it, names[it]) }

    fun app(clientId: AgentClientId?): AgentApp? = clientId?.let(apps::get)

    /** A system actor has no person to name. */
    fun pageActor(kind: AgentPageActorKind, id: String, agentClientId: String?): AgentPageActor = AgentPageActor(
        kind = kind,
        id = id,
        displayName = if (kind == AgentPageActorKind.SYSTEM) null else names[id],
        agentApp = agentClientId?.let { app(AgentClientId(it)) },
    )
}
