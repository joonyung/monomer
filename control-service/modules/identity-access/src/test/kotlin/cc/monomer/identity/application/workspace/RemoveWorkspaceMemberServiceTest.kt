package cc.monomer.identity.application.workspace

import cc.monomer.identity.application.AccessDeniedException
import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.AuditLogEntry
import cc.monomer.identity.domain.port.AuditLogRepository
import cc.monomer.identity.domain.port.AuthorizationAssignmentRepository
import cc.monomer.identity.domain.port.TransactionRunner
import cc.monomer.identity.domain.port.WorkspaceMemberRemoval
import cc.monomer.identity.domain.port.WorkspaceMemberRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RemoveWorkspaceMemberServiceTest {
    @Test
    fun `member removal records one exact audit event inside the owner mutation transaction`() = runTest {
        val actor = UserId.generate()
        val target = UserId.generate()
        val workspace = WorkspaceId.generate()
        val now = Instant.parse("2026-09-08T00:00:00Z")
        var insideTransaction = false
        val transactions = object : TransactionRunner {
            override suspend fun <T> run(action: () -> T): T {
                insideTransaction = true
                return try { action() } finally { insideTransaction = false }
            }
            override suspend fun <T> readOnly(action: () -> T): T = error("mutation must not use a read transaction")
        }
        val assignments = mockk<AuthorizationAssignmentRepository>()
        every { assignments.actorIsOrganizationAdminAfterLock(actor, workspace) } returns false
        every { assignments.actorIsOwnerAfterLock(actor, workspace) } answers {
            assertThat(insideTransaction).isTrue()
            true
        }
        val members = mockk<WorkspaceMemberRepository>()
        var present = true
        every { members.delete(workspace, target) } answers {
            assertThat(insideTransaction).isTrue()
            if (present) {
                present = false
                WorkspaceMemberRemoval.Removed
            } else WorkspaceMemberRemoval.NotFound
        }
        val auditEntries = mutableListOf<AuditLogEntry>()
        val audit = mockk<AuditLogRepository>()
        every { audit.insert(any()) } answers {
            assertThat(insideTransaction).isTrue()
            auditEntries += firstArg<AuditLogEntry>()
            Unit
        }
        val service = RemoveWorkspaceMemberService(
            OwnerWorkspaceMutationRunner(transactions, assignments), members, audit,
            AuditEventFactory(Clock.fixed(now, ZoneOffset.UTC)),
        )
        val command = RemoveWorkspaceMemberUseCase.Command(actor, workspace, target, audit = null)

        assertThat(service.remove(command)).isTrue()
        assertThat(service.remove(command)).isFalse()
        assertThat(auditEntries).hasSize(1)
        val entry = auditEntries.single()
        assertThat(entry.action).isEqualTo("workspace.member.remove")
        assertThat(entry.actorId).isEqualTo(actor.asPrincipal())
        assertThat(entry.workspaceId).isEqualTo(workspace)
        assertThat(entry.resourceType).isEqualTo("user")
        assertThat(entry.resourceId).isEqualTo(target.value)
        assertThat(entry.occurredAt).isEqualTo(now)
        assertThat(entry.details).isEqualTo("{}")
    }

    @Test
    fun `organization admin removes without workspace ownership and other actors are rejected`() = runTest {
        val admin = UserId.generate()
        val member = UserId.generate()
        val target = UserId.generate()
        val workspace = WorkspaceId.generate()
        val transactions = object : TransactionRunner {
            override suspend fun <T> run(action: () -> T): T = action()
            override suspend fun <T> readOnly(action: () -> T): T = error("mutation must not use a read transaction")
        }
        val assignments = mockk<AuthorizationAssignmentRepository>()
        every { assignments.actorIsOrganizationAdminAfterLock(admin, workspace) } returns true
        every { assignments.actorIsOrganizationAdminAfterLock(member, workspace) } returns false
        every { assignments.actorIsOwnerAfterLock(member, workspace) } returns false
        val members = mockk<WorkspaceMemberRepository>()
        every { members.delete(workspace, target) } returns WorkspaceMemberRemoval.Removed
        val audit = mockk<AuditLogRepository>(relaxed = true)
        val service = RemoveWorkspaceMemberService(
            OwnerWorkspaceMutationRunner(transactions, assignments), members, audit, AuditEventFactory(Clock.systemUTC()),
        )

        assertThatThrownBy { kotlinx.coroutines.runBlocking { service.remove(RemoveWorkspaceMemberUseCase.Command(member, workspace, target, null)) } }
            .isInstanceOf(AccessDeniedException::class.java)
        verify(exactly = 0) { members.delete(any(), any()) }
        assertThat(service.remove(RemoveWorkspaceMemberUseCase.Command(admin, workspace, target, null))).isTrue()
        verify(exactly = 0) { assignments.actorIsOwnerAfterLock(admin, workspace) }
        verify(exactly = 1) { audit.insert(match { it.action == "workspace.member.remove" && it.actorId == admin.asPrincipal() }) }
    }
}
