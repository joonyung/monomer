package cc.monomer.identity.application.agent

import cc.monomer.identity.application.OpaqueCredentials

class AgentOAuthTokenHasher {
    fun hash(rawToken: String): String = OpaqueCredentials.hash(rawToken)
}
