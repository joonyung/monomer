@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.agent.api

import cc.monomer.agent.api.AgentPathPatterns.UUID_V7
import java.time.Instant
import kotlin.uuid.Uuid

enum class AgentPageKind { FOLDER, DOCUMENT, DASHBOARD }
enum class AgentPageContentFormat { MARKDOWN, NOTEBOOK, HTML }

enum class AgentPageActorKind { USER, AGENT, SYSTEM }

/**
 * Who did Pages work, as the Web shows it. [displayName] is the person's current name, null for a system actor or an
 * unreadable account; [agentApp] is the app behind AGENT work, null otherwise and for agent work recorded before apps
 * were kept.
 */
data class AgentPageActor(val kind: AgentPageActorKind, val id: String, val displayName: String?, val agentApp: AgentApp?) {
    init {
        require(id.isNotBlank())
        require(agentApp == null || kind == AgentPageActorKind.AGENT)
        require(displayName == null || kind != AgentPageActorKind.SYSTEM)
    }
}

/** [createdBy] and [updatedBy] accompany [createdAt] and [updatedAt]; the Viewer page reader returns none of them. */
data class AgentPageResourceBody(
    val pageKind: AgentPageKind,
    val title: String?,
    val description: String?,
    val displayTitle: String,
    val dashboard: AgentActionResultReference?,
    val latestVersion: AgentActionResultReference?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val documentFormat: AgentPageContentFormat?,
    val createdBy: AgentPageActor?,
    val updatedBy: AgentPageActor?,
) : AgentResourceBody {
    override val kind = AgentResourceKind.PAGE
    init {
        require((createdBy == null) == (createdAt == null) && (updatedBy == null) == (updatedAt == null))
        require(dashboard == null || pageKind == AgentPageKind.DASHBOARD)
        require(pageKind == AgentPageKind.DOCUMENT || latestVersion == null)
        require(dashboard == null || dashboard.kind == AgentResourceKind.DASHBOARD)
        require(latestVersion == null || latestVersion.kind == AgentResourceKind.PAGE_VERSION)
    }
}

data class AgentPageContentBody(val title: String, val format: AgentPageContentFormat, val source: String) : AgentResourceBody { override val kind = AgentResourceKind.PAGE_CONTENT }
data class AgentPageSessionBody(val title: String?, val display: kotlinx.serialization.json.JsonObject, val parameterBindings: Map<String, kotlinx.serialization.json.JsonElement>, val initialVariables: Map<String, kotlinx.serialization.json.JsonElement>, val createdAt: Instant, val updatedAt: Instant) : AgentResourceBody { override val kind = AgentResourceKind.PAGE_SESSION }
data class AgentPageRunBody(val queryName: String, val queryType: String, val state: String, val resultAvailable: Boolean, val failureCode: String?, val outputColumnNames: List<String>, val createdAt: Instant, val updatedAt: Instant) : AgentResourceBody { override val kind = AgentResourceKind.PAGE_QUERY_RUN }

data class AgentPagePlacement(val pageId: Uuid, val children: List<AgentPagePlacement>)
data class AgentPagesTreeResourceBody(val pages: List<AgentResourceListingItem.Page>, val placements: List<AgentPagePlacement>) : AgentResourceBody {
    override val kind = AgentResourceKind.PAGES_TREE
}
data class AgentPageVersionResourceBody(val format: AgentPageContentFormat, val byteSize: Long, val sha256: String, val finalizedAt: Instant, val finalizedBy: AgentPageActor) : AgentResourceBody {
    override val kind = AgentResourceKind.PAGE_VERSION
}
data class AgentPageUploadMetadata(val uploadRef: Uuid, val format: AgentPageContentFormat, val byteSize: Long, val sha256: String, val expiresAt: Instant)
data class AgentPageUploadPart(val partNumber: Int, val contentLength: Long, val url: String, val requiredHeaders: Map<String, String>) {
    override fun toString(): String = "AgentPageUploadPart([REDACTED])"
}
data class AgentPageUploadCompletedPart(val partNumber: Int, val eTag: String)

interface AgentPagesOwnerPort {
    suspend fun list(caller: AgentWorkspaceCaller, request: AgentListRequest): AgentListingOutcome
    suspend fun read(caller: AgentWorkspaceCaller, request: AgentReadRequest): AgentResourceOutcome
    suspend fun create(caller: AgentWorkspaceCaller, request: AgentCreateRequest): AgentActionOutcome
    suspend fun edit(caller: AgentWorkspaceCaller, request: AgentEditRequest): AgentActionOutcome
    suspend fun invoke(caller: AgentWorkspaceCaller, request: AgentInvokeRequest): AgentActionOutcome
}

object AgentPagesPaths {
    val page = Regex("^workspace/pages/$UUID_V7$")
    val content = Regex("^workspace/pages/$UUID_V7/content$")
    val sessions = Regex("^workspace/pages/$UUID_V7/sessions$")
    val session = Regex("^workspace/pages/$UUID_V7/sessions/$UUID_V7$")
    val runs = Regex("^workspace/pages/$UUID_V7/sessions/$UUID_V7/query-runs$")
    val run = Regex("^workspace/pages/$UUID_V7/sessions/$UUID_V7/query-runs/$UUID_V7$")
    val result = Regex("^workspace/pages/$UUID_V7/sessions/$UUID_V7/query-runs/$UUID_V7/result$")
    fun reader(path: String) = path == "workspace/pages" || path == "workspace/pages/tree" || page.matches(path) || content.matches(path) || sessions.matches(path) || session.matches(path) || runs.matches(path) || run.matches(path) || result.matches(path)
    val versions = Regex("^workspace/pages/$UUID_V7/versions$")
    val version = Regex("^workspace/pages/$UUID_V7/versions/(?:[1-9][0-9]{0,8}|1[0-9]{9}|20[0-9]{8}|21[0-3][0-9]{7}|214[0-6][0-9]{6}|2147[0-3][0-9]{5}|21474[0-7][0-9]{4}|214748[0-2][0-9]{3}|2147483[0-5][0-9]{2}|21474836[0-3][0-9]|214748364[0-7])$")
    val upload = Regex("^workspace/page-content-uploads/$UUID_V7$")
    fun editable(path: String): Boolean = path == "workspace/pages/tree" || page.matches(path)
    fun creatable(path: String): Boolean = path == "workspace/pages" || path == "workspace/page-content-uploads" || versions.matches(path)
    fun invokable(path: String, action: String): Boolean = (page.matches(path) && action == "delete") || (upload.matches(path) && action in setOf("complete", "abort"))
}
