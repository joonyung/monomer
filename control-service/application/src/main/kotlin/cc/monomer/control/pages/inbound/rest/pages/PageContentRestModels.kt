package cc.monomer.control.pages.inbound.rest.pages

import cc.monomer.control.agentapp.responses
import cc.monomer.pages.application.content.PageContentUploadPart
import cc.monomer.pages.application.content.PageContentUploadView
import cc.monomer.pages.domain.content.PageVersionMetadata

data class CreatePageContentUploadRequest(
    val format: String,
    val byteSize: Long,
    val sha256: String,
)

data class CompletePageContentUploadRequest(
    val parts: List<CompletedPageContentUploadPartRequest>,
)

data class CompletedPageContentUploadPartRequest(
    val partNumber: Int,
    val eTag: String,
)

data class ReplacePageContentRequest(
    val uploadRef: String,
    val requestId: String,
)

data class PageContentUploadSessionResponse(
    val uploadRef: String,
    val format: String,
    val byteSize: Long,
    val sha256: String,
    val expiresAt: String,
    val partSizeBytes: Long,
    val parts: List<PageContentUploadPartResponse>,
)

data class PageContentUploadPartResponse(
    val partNumber: Int,
    val contentLength: Long,
    val url: String,
    val requiredHeaders: Map<String, String>,
)

data class PageContentUploadCompletionResponse(
    val result: String,
    val upload: PageContentUploadResponse,
)

data class PageContentUploadResponse(
    val uploadRef: String,
    val format: String,
    val byteSize: Long,
    val sha256: String,
    val expiresAt: String,
)

data class PageContentVersionMutationResponse(
    val result: String,
    val version: PageContentVersionResponse,
)

data class PageContentVersionPageResponse(
    val items: List<PageContentVersionResponse>,
    val nextBeforeVersionNo: Int?,
)

data class PageContentVersionResponse(
    val pageId: String,
    val versionNo: Int,
    val format: String,
    val contentHash: String,
    val contentSizeBytes: Long,
    val finalizedBy: PageContentActorResponse,
    val finalizedAt: String,
)

/**
 * [displayName] is the recorded person's current name; null for a system actor or an unreadable account.
 * [agentApp] is the agent app behind AGENT work; null otherwise and for agent work recorded before apps were kept.
 */
data class PageContentActorResponse(
    val kind: String,
    val id: String,
    val displayName: String?,
    val agentApp: cc.monomer.control.agentapp.AgentAppResponse?,
)

internal fun PageContentUploadView.toResponse(): PageContentUploadResponse = PageContentUploadResponse(
    uploadRef = uploadRef.toString(),
    format = format.name,
    byteSize = byteSize,
    sha256 = sha256.value,
    expiresAt = expiresAt.toString(),
)

internal fun PageContentUploadPart.toResponse(): PageContentUploadPartResponse = PageContentUploadPartResponse(
    partNumber = partNumber,
    contentLength = contentLength,
    url = uploadUrl,
    requiredHeaders = mapOf("Content-Length" to contentLength.toString()),
)

internal fun PageVersionMetadata.toResponse(finalizers: PageVersionFinalizers): PageContentVersionResponse = PageContentVersionResponse(
    pageId = pageId.toString(),
    versionNo = versionNumber.value,
    format = format.name,
    contentHash = contentHash.value,
    contentSizeBytes = contentSizeBytes,
    finalizedBy = PageContentActorResponse(
        finalizedBy.kind.name,
        finalizedBy.id,
        finalizers.names[finalizedBy.id],
        finalizedByAgentClientId?.let { finalizers.apps[cc.monomer.identity.api.AgentClientId(it)] },
    ),
    finalizedAt = finalizedAt.toString(),
)

/** The people who finalized versions, by principal id, and the agent apps behind AGENT versions. */
internal data class PageVersionFinalizers(
    val names: Map<String, String>,
    val apps: Map<cc.monomer.identity.api.AgentClientId, cc.monomer.control.agentapp.AgentAppResponse>,
)

/** Names and agent apps of the people who finalized [versions]; a system actor has no person to name. */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
internal suspend fun pageVersionFinalizers(
    principalDisplayNames: cc.monomer.identity.api.PrincipalDisplayNames,
    agentApps: cc.monomer.identity.api.AgentAppDisplays,
    versions: List<PageVersionMetadata>,
): PageVersionFinalizers {
    val userIds = versions.filter { it.finalizedBy.kind != cc.monomer.pages.domain.PageActorKind.SYSTEM }
        .mapNotNull { runCatching { kotlin.uuid.Uuid.parse(it.finalizedBy.id) }.getOrNull() }.toSet()
    val names = if (userIds.isEmpty()) emptyMap() else principalDisplayNames.read(userIds).mapKeys { (id, _) -> id.toString() }
    val apps = agentApps.responses(versions.mapNotNull { version -> version.finalizedByAgentClientId?.let { cc.monomer.identity.api.AgentClientId(it) } })
    return PageVersionFinalizers(names, apps)
}
