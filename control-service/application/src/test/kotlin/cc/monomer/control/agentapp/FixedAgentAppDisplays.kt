package cc.monomer.control.agentapp

import cc.monomer.identity.api.AgentAppDisplay
import cc.monomer.identity.api.AgentAppDisplays
import cc.monomer.identity.api.AgentClientId

/** Knows one agent app, Claude; every other client id has no retained connection. */
object FixedAgentAppDisplays : AgentAppDisplays {
    val CLAUDE = AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata")
    val CLAUDE_APP = AgentAppResponse(CLAUDE.value, "Claude", "claude.ai")

    override suspend fun read(clientIds: Set<AgentClientId>): Map<AgentClientId, AgentAppDisplay> =
        clientIds.filter { it == CLAUDE }.associateWith { AgentAppDisplay(it, "Claude", "claude.ai") }
}
