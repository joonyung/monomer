@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.pages.api

import java.time.Instant
import kotlin.uuid.Uuid

interface PageActivityReadPort {
    /**
     * [actorKind] keeps only changes recorded as `USER` (the Web) or `AGENT` (an agent connection); null keeps both.
     * [agentClientId] keeps only changes recorded with that agent app.
     */
    suspend fun readRecent(
        workspaceId: Uuid,
        before: Instant?,
        beforePageId: Uuid?,
        actorId: String?,
        actorKind: String?,
        agentClientId: String?,
        limit: Int,
    ): List<PageActivity>

    /** Agent apps in the latest [scanLimit] Document changes of the Workspace, each with its latest change time. */
    suspend fun readRecentAgentClients(workspaceId: Uuid, scanLimit: Int): List<PageAgentClientUse>
}

data class PageAgentClientUse(
    val agentClientId: String,
    val lastSeenAt: Instant,
)

data class PageActivity(
    val pageId: Uuid,
    val title: String,
    val occurredAt: Instant,
    val actorId: String,
    val actorKind: String,
    val agentClientId: String?,
    val contentVersion: Int?,
)
