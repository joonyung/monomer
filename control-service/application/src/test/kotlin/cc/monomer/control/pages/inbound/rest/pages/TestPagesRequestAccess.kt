package cc.monomer.control.pages.inbound.rest.pages

import cc.monomer.identity.api.PresentedCredential
import cc.monomer.pages.domain.PageActor
import cc.monomer.pages.domain.PageActorKind
import cc.monomer.pages.domain.audit.AuditActorContext
import kotlin.uuid.Uuid
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange

internal enum class TestAccess {
    VIEWER,
    MEMBER,
    EDITOR,
}

internal object TestAuthorities {
    fun viewer(): TestAccess = TestAccess.VIEWER

    fun member(): TestAccess = TestAccess.MEMBER

    fun editor(): TestAccess = TestAccess.EDITOR
}

internal data class RequestContext(
    val userId: Uuid,
    val organizationId: Uuid?,
    val workspaceId: Uuid?,
    val bearerToken: String?,
    val access: TestAccess,
)

internal object RequestContextAttributes {
    const val ATTRIBUTE_KEY: String = "pages-test-request-context"
}

internal fun testPagesRequestAccess(): PagesRequestAccess =
    object : PagesRequestAccess {
        override suspend fun require(exchange: ServerWebExchange, rawWorkspaceId: String): PagesAccess =
            access(exchange, rawWorkspaceId)

        override suspend fun isCurrentExplorer(access: PagesAccess): Boolean = true

        private fun access(
            exchange: ServerWebExchange,
            rawWorkspaceId: String,
        ): PagesAccess {
            val workspaceId = runCatching { Uuid.parse(rawWorkspaceId) }
                .getOrElse { throw invalidWorkspaceId() }
            if (workspaceId.toString() != rawWorkspaceId) throw invalidWorkspaceId()
            val context = exchange.attributes[RequestContextAttributes.ATTRIBUTE_KEY] as? RequestContext
                ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.")
            if (context.workspaceId != workspaceId) {
                throw PagesRestProblem(
                    HttpStatus.NOT_FOUND,
                    "WORKSPACE_NOT_FOUND",
                    null,
                    null,
                    emptyMap(),
                    "Pages resource was not found.",
                )
            }
            val organizationId = context.organizationId
                ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated organization context is required.")
            if (context.access == TestAccess.VIEWER) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Explorer role is required.").also { it.body.setProperty("code", "ROLE_REQUIRED") }
            val bearerToken = context.bearerToken
                ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.")
            return PagesAccess(
                workspaceId = workspaceId,
                actor = PageActor.create(PageActorKind.USER, context.userId.toString()),
                auditActorContext = AuditActorContext(
                    organizationId = organizationId,
                    principalId = context.userId,
                    actorRef = context.userId.toString(),
                ),
                credential = PresentedCredential(bearerToken),
            )
        }
    }

private fun invalidWorkspaceId(): PagesRestProblem = PagesRestProblem(
    HttpStatus.BAD_REQUEST,
    "INVALID_WORKSPACE_ID",
    null,
    null,
    emptyMap(),
    "Identifier must be a canonical UUID.",
)
