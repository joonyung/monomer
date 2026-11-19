package cc.monomer.pages.domain.audit

import cc.monomer.pages.domain.PageActor
import cc.monomer.pages.domain.PageActorKind
import java.time.Instant
import kotlin.uuid.Uuid
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PagesAuditEventTest {
    private val principalId = Uuid.parse("019e501f-0000-7000-8000-000000000099")
    private val context = AuditActorContext(Uuid.parse("019e501f-0000-7000-8000-000000000010"), principalId, principalId.toString())
    private val details = PagesAuditDetails("FOLDER", null, null, null, null)

    @Test
    fun `created events carry a v7 identity and the actor kind from the Page actor`() {
        for ((actorKind, auditKind) in listOf(PageActorKind.USER to AuditActorKind.USER, PageActorKind.AGENT to AuditActorKind.AGENT)) {
            val event = create(PageActor.create(actorKind, principalId.toString()))

            assertThat(event.eventId.toByteArray()[6].toInt() and 0xf0).isEqualTo(0x70)
            assertThat(event.actorKind).isEqualTo(auditKind)
            assertThat(event.organizationId).isEqualTo(context.organizationId)
            assertThat(event.actorPrincipalId).isEqualTo(principalId)
            assertThat(event.actorRef).isEqualTo(context.actorRef)
        }
    }

    @Test
    fun `system actors cannot enter the Pages audit records`() {
        assertThatThrownBy { create(PageActor.create(PageActorKind.SYSTEM, "pages-system")) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    private fun create(actor: PageActor) = PagesAuditEvent.create(
        workspaceId = Uuid.parse("019e501f-0000-7000-8000-000000000011"),
        actor = actor,
        actorContext = context,
        action = PagesAuditAction.FOLDER_CREATE,
        resourceId = Uuid.parse("019e501f-0000-7000-8000-000000000012"),
        requestId = "request-1",
        occurredAt = Instant.parse("2026-10-11T00:00:00Z"),
        details = details,
    )
}
