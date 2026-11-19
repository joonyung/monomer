package cc.monomer.agent.api

import cc.monomer.agent.api.AgentPathPatterns.SEMANTIC_NAME
import cc.monomer.agent.api.AgentPathPatterns.UUID_V7

object AgentSemanticHistoryPaths {
    val definition = Regex("^workspace/(?:semantic-models|metrics)/$SEMANTIC_NAME$")
    val history = Regex("^workspace/(?:semantic-models|metrics)/$SEMANTIC_NAME/history/$UUID_V7$")
    val version = Regex("^workspace/(?:semantic-models|metrics)/$SEMANTIC_NAME/history/$UUID_V7/$UUID_V7$")
}
