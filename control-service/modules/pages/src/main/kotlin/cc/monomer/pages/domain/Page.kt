package cc.monomer.pages.domain

import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class PageId(val value: Uuid) {
    companion object {
        fun generate(): PageId = PageId(Uuid.generateV7())
    }

    override fun toString(): String = value.toString()
}

enum class PageKind {
    FOLDER,
    DOCUMENT,
    DASHBOARD,
}

enum class PageActorKind {
    USER,
    AGENT,
    SYSTEM,
}

@Serializable
@ConsistentCopyVisibility
data class PageActor private constructor(
    val kind: PageActorKind,
    val id: String,
) {
    companion object {
        fun create(kind: PageActorKind, id: String): PageActor {
            require(id.isNotBlank()) { "actor id must not be blank" }
            require(id.length <= 200) { "actor id must not exceed 200 characters" }
            return PageActor(kind = kind, id = id)
        }
    }
}

/** Agent work may name the agent app's OAuth client id; work by any other actor names none. */
fun requirePageAgentClient(actor: PageActor, agentClientId: String?) {
    require(agentClientId == null || (actor.kind == PageActorKind.AGENT && agentClientId.isNotBlank() && agentClientId.length <= 2048)) {
        "only AGENT work records an agent client id"
    }
}

@ConsistentCopyVisibility
data class PageNavigationMetadata private constructor(
    val title: String?,
    val description: String?,
) {
    companion object {
        fun create(title: String?, description: String?): PageNavigationMetadataResult {
            val normalizedTitle = title?.trim()
            if (normalizedTitle != null && normalizedTitle.isBlank()) {
                return PageNavigationMetadataResult.Invalid(PageMetadataRejection.TITLE_BLANK)
            }
            if (normalizedTitle != null && normalizedTitle.length > 500) {
                return PageNavigationMetadataResult.Invalid(PageMetadataRejection.TITLE_TOO_LONG)
            }
            if (description != null && description.length > MAX_DESCRIPTION_LENGTH) {
                return PageNavigationMetadataResult.Invalid(PageMetadataRejection.DESCRIPTION_TOO_LONG)
            }
            return PageNavigationMetadataResult.Valid(
                PageNavigationMetadata(title = normalizedTitle, description = description),
            )
        }

        const val MAX_DESCRIPTION_LENGTH: Int = 1_000

        internal fun restore(title: String?, description: String?): PageNavigationMetadata =
            PageNavigationMetadata(title = title?.trim(), description = description)
    }
}

enum class PageMetadataRejection {
    TITLE_REQUIRED,
    TITLE_BLANK,
    TITLE_TOO_LONG,
    DESCRIPTION_TOO_LONG,
}

sealed interface PageNavigationMetadataResult {
    data class Valid(val metadata: PageNavigationMetadata) : PageNavigationMetadataResult
    data class Invalid(val reason: PageMetadataRejection) : PageNavigationMetadataResult
}

@ConsistentCopyVisibility
data class Page private constructor(
    val id: PageId,
    val workspaceId: Uuid,
    val kind: PageKind,
    val dashboardId: Uuid?,
    val navigationMetadata: PageNavigationMetadata,
    val createdAt: Instant,
    val createdBy: PageActor,
    val createdByAgentClientId: String?,
    val updatedAt: Instant,
    val updatedBy: PageActor,
    val updatedByAgentClientId: String?,
) {
    init {
        require((kind == PageKind.DASHBOARD) == (dashboardId != null))
        requirePageAgentClient(createdBy, createdByAgentClientId)
        requirePageAgentClient(updatedBy, updatedByAgentClientId)
    }

    fun updateNavigationMetadata(
        navigationMetadata: PageNavigationMetadata,
        actor: PageActor,
        agentClientId: String?,
        updatedAt: Instant,
    ): PageUpdateResult {
        val rejection = validateMetadata(navigationMetadata)
        return if (rejection == null) {
            PageUpdateResult.Updated(
                copy(
                    navigationMetadata = navigationMetadata,
                    updatedAt = updatedAt,
                    updatedBy = actor,
                    updatedByAgentClientId = agentClientId,
                ),
            )
        } else {
            PageUpdateResult.Invalid(rejection)
        }
    }

    companion object {
        fun createFolder(
            workspaceId: Uuid,
            navigationMetadata: PageNavigationMetadata,
            actor: PageActor,
            agentClientId: String?,
            createdAt: Instant,
        ): PageCreationResult = create(
            pageId = PageId.generate(),
            workspaceId = workspaceId,
            kind = PageKind.FOLDER,
            dashboardId = null,
            navigationMetadata = navigationMetadata,
            actor = actor,
            agentClientId = agentClientId,
            createdAt = createdAt,
        )

        fun createDocument(
            workspaceId: Uuid,
            navigationMetadata: PageNavigationMetadata,
            actor: PageActor,
            agentClientId: String?,
            createdAt: Instant,
        ): PageCreationResult = create(
            pageId = PageId.generate(),
            workspaceId = workspaceId,
            kind = PageKind.DOCUMENT,
            dashboardId = null,
            navigationMetadata = navigationMetadata,
            actor = actor,
            agentClientId = agentClientId,
            createdAt = createdAt,
        )

        fun createDashboard(
            workspaceId: Uuid,
            dashboardId: Uuid,
            navigationMetadata: PageNavigationMetadata,
            actor: PageActor,
            agentClientId: String?,
            createdAt: Instant,
        ): PageCreationResult = create(
            pageId = PageId.generate(),
            workspaceId = workspaceId,
            kind = PageKind.DASHBOARD,
            dashboardId = dashboardId,
            navigationMetadata = navigationMetadata,
            actor = actor,
            agentClientId = agentClientId,
            createdAt = createdAt,
        )

        internal fun restore(
            pageId: PageId,
            workspaceId: Uuid,
            kind: PageKind,
            dashboardId: Uuid?,
            navigationMetadata: PageNavigationMetadata,
            createdAt: Instant,
            createdBy: PageActor,
            createdByAgentClientId: String?,
            updatedAt: Instant,
            updatedBy: PageActor,
            updatedByAgentClientId: String?,
        ): Page {
            require(validateMetadata(navigationMetadata) == null) { "restored Page metadata is invalid" }
            return Page(
                id = pageId,
                workspaceId = workspaceId,
                kind = kind,
                dashboardId = dashboardId,
                navigationMetadata = navigationMetadata,
                createdAt = createdAt,
                createdBy = createdBy,
                createdByAgentClientId = createdByAgentClientId,
                updatedAt = updatedAt,
                updatedBy = updatedBy,
                updatedByAgentClientId = updatedByAgentClientId,
            )
        }

        private fun create(
            pageId: PageId,
            workspaceId: Uuid,
            kind: PageKind,
            dashboardId: Uuid?,
            navigationMetadata: PageNavigationMetadata,
            actor: PageActor,
            agentClientId: String?,
            createdAt: Instant,
        ): PageCreationResult {
            val rejection = validateMetadata(navigationMetadata)
            if (rejection != null) return PageCreationResult.Invalid(rejection)
            return PageCreationResult.Created(
                Page(
                    id = pageId,
                    workspaceId = workspaceId,
                    kind = kind,
                    dashboardId = dashboardId,
                    navigationMetadata = navigationMetadata,
                    createdAt = createdAt,
                    createdBy = actor,
                    createdByAgentClientId = agentClientId,
                    updatedAt = createdAt,
                    updatedBy = actor,
                    updatedByAgentClientId = agentClientId,
                ),
            )
        }

        private fun validateMetadata(
            navigationMetadata: PageNavigationMetadata,
        ): PageMetadataRejection? {
            val title = navigationMetadata.title
            return when {
                title == null -> PageMetadataRejection.TITLE_REQUIRED
                title.isBlank() -> PageMetadataRejection.TITLE_BLANK
                title.length > 500 -> PageMetadataRejection.TITLE_TOO_LONG
                navigationMetadata.description != null &&
                    navigationMetadata.description.length > PageNavigationMetadata.MAX_DESCRIPTION_LENGTH ->
                    PageMetadataRejection.DESCRIPTION_TOO_LONG
                else -> null
            }
        }
    }
}

sealed interface PageCreationResult {
    data class Created(val page: Page) : PageCreationResult
    data class Invalid(val reason: PageMetadataRejection) : PageCreationResult
}

sealed interface PageUpdateResult {
    data class Updated(val page: Page) : PageUpdateResult
    data class Invalid(val reason: PageMetadataRejection) : PageUpdateResult
}
