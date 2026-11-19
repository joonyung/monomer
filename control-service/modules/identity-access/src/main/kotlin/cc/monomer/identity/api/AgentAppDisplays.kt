package cc.monomer.identity.api

import java.net.URI
import java.net.URISyntaxException
import java.time.Instant

/**
 * How an agent app is shown next to the work it did. [name] is the client name the app presented in its most
 * recent connection; it is self-asserted and only for display. [verifiedHost] is the host of an https client id,
 * whose metadata Monomer fetched from that host before any connection was granted; null for a registered client.
 */
data class AgentAppDisplay(
    val clientId: AgentClientId,
    val name: String,
    val verifiedHost: String?,
)

interface AgentAppDisplays {
    /**
     * Reads apps from connections of any state, so revoked and expired connections still name their app. A client
     * id that no retained connection names keeps its verified host as its name; a registered id without one is absent.
     */
    suspend fun read(clientIds: Set<AgentClientId>): Map<AgentClientId, AgentAppDisplay>

    companion object {
        const val MAX_CLIENT_IDS = 200
    }
}

/** The host of an https client id; null for any other id. */
fun AgentClientId.verifiedHost(): String? {
    val uri = try { URI(value) } catch (_: URISyntaxException) { return null }
    if (!uri.scheme.equals("https", ignoreCase = true)) return null
    return uri.host?.lowercase()?.takeIf(String::isNotBlank)
}

/** An agent app seen in a Workspace's recent work and the latest time it was seen there. */
data class RecentAgentClient(
    val clientId: AgentClientId,
    val lastSeenAt: Instant,
) {
    companion object {
        /** Each owner scans at most this many of its latest Workspace records for agent apps. */
        const val MAX_SCAN = 500
    }
}
