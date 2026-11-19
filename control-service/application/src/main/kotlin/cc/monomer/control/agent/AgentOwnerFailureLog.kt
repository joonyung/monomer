package cc.monomer.control.agent

import cc.monomer.control.jdbc.causeTypeChain
import cc.monomer.control.jdbc.sqlStateChain
import org.slf4j.LoggerFactory

private val agentOwnerLogger = LoggerFactory.getLogger("cc.monomer.control.agent.AgentOwner")

internal fun logAgentOwnerFailure(owner: String, failure: Exception) {
    agentOwnerLogger.atWarn()
        .addKeyValue("event", "agent_owner_failed")
        .addKeyValue("owner_adapter", owner)
        .setCause(failure)
        .addKeyValue("failure_types", failure.causeTypeChain())
        .addKeyValue("sql_states", failure.sqlStateChain())
        .log("agent_owner_failed")
}
