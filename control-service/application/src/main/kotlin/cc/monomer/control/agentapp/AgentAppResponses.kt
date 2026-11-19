package cc.monomer.control.agentapp

import cc.monomer.identity.api.AgentAppDisplay
import cc.monomer.identity.api.AgentAppDisplays
import cc.monomer.identity.api.AgentClientId

/**
 * The agent app behind agent work, as the Web shows it: "via {name}", with [verifiedHost] as the host Monomer
 * verified for an https client id. [name] is the app's self-asserted client name and is only for display.
 */
data class AgentAppResponse(
    val clientId: String,
    val name: String,
    val verifiedHost: String?,
)

fun AgentAppDisplay.toResponse(): AgentAppResponse = AgentAppResponse(clientId.value, name, verifiedHost)

/** Display apps for every recorded client id; an app no connection names is absent, so its work reads "via agent". */
suspend fun AgentAppDisplays.responses(clientIds: Collection<AgentClientId?>): Map<AgentClientId, AgentAppResponse> =
    clientIds.filterNotNull().toSet().chunked(AgentAppDisplays.MAX_CLIENT_IDS)
        .flatMap { chunk -> read(chunk.toSet()).values }
        .associate { display -> display.clientId to display.toResponse() }
