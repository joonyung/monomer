package cc.monomer.identity.application.workspace

import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.application.grant.InvalidWorkspaceRoleTargetException
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.port.AuditLogRepository
import cc.monomer.identity.domain.port.AuthorizationAssignmentRepository
import cc.monomer.identity.domain.port.TransactionRunner
import cc.monomer.identity.domain.port.WorkspaceMemberInsertion
import cc.monomer.identity.domain.port.WorkspaceMemberRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AddWorkspaceMemberServiceTest {
    private val owner = UserId.generate()
    private val target = UserId.generate()
    private val workspace = WorkspaceId.generate()
    private val clock = Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneOffset.UTC)
    private var insideTransaction = false
    private val transactions = object : TransactionRunner {
        override suspend fun <T> run(action: () -> T): T {
            insideTransaction = true
            return try { action() } finally { insideTransaction = false }
        }
        override suspend fun <T> readOnly(action: () -> T): T = error("mutation must not use a read transaction")
    }
    private val assignments = mockk<AuthorizationAssignmentRepository>()
    private val members = mockk<WorkspaceMemberRepository>()
    private val audit = mockk<AuditLogRepository>(relaxed = true)
    private val service = AddWorkspaceMemberService(
        OwnerWorkspaceMutationRunner(transactions, assignments), assignments, members, audit, AuditEventFactory(clock), clock,
    )

    @Test
    fun `target outside the workspace organization is rejected as an invalid role target before any write`() {
        every { assignments.actorIsOwnerAfterLock(owner, workspace) } returns true
        every { assignments.isEligibleMemberRoleTarget(workspace, target, false) } answers {
            assertThat(insideTransaction).isTrue()
            false
        }

        assertThatThrownBy { runBlocking { service.add(command()) } }
            .isInstanceOf(InvalidWorkspaceRoleTargetException::class.java)
        verify(exactly = 0) { members.insert(any()) }
        verify(exactly = 0) { audit.insert(any()) }
    }

    @Test
    fun `eligible organization member is inserted with one audit event`() = runTest {
        every { assignments.actorIsOwnerAfterLock(owner, workspace) } returns true
        every { assignments.isEligibleMemberRoleTarget(workspace, target, false) } returns true
        every { members.insert(any()) } returns WorkspaceMemberInsertion.Inserted

        val membership = service.add(command())

        assertThat(membership.role).isEqualTo(WorkspaceRole.EXPLORER)
        verify(exactly = 1) { audit.insert(match { it.action == "workspace.member.add" && it.resourceId == target.value }) }
    }

    private fun command() = AddWorkspaceMemberUseCase.Command(owner, workspace, target, null, WorkspaceRole.EXPLORER)
}
