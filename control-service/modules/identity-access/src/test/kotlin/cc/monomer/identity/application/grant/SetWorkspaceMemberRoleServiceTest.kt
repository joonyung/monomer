package cc.monomer.identity.application.grant

import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.application.AccessDeniedException
import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.application.audit.GovernanceAudit
import cc.monomer.identity.application.workspace.OwnerWorkspaceMutationRunner
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.AuditActorKind
import cc.monomer.identity.domain.model.AuditLogEntry
import cc.monomer.identity.domain.port.AuditLogRepository
import cc.monomer.identity.domain.port.AuthorizationAssignmentRepository
import cc.monomer.identity.domain.port.AuthorizationAssignmentTransition
import cc.monomer.identity.domain.port.LastWorkspaceOwnerException
import cc.monomer.identity.domain.port.TransactionRunner
import cc.monomer.identity.domain.port.WorkspaceMemberInsertion
import cc.monomer.identity.domain.port.WorkspaceMemberRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SetWorkspaceMemberRoleServiceTest {
    private val organization = OrganizationId.generate()
    private val workspace = WorkspaceId.generate()
    private val actor = UserId.generate()
    private val target = UserId.generate()
    private val now = Instant.parse("2026-10-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private var insideTransaction = false
    private val transactions = object : TransactionRunner {
        override suspend fun <T> run(action: () -> T): T {
            insideTransaction = true
            return try { action() } finally { insideTransaction = false }
        }
        override suspend fun <T> readOnly(action: () -> T): T = error("write transaction required")
    }
    private val assignments = mockk<AuthorizationAssignmentRepository>()
    private val members = mockk<WorkspaceMemberRepository>()
    private val audits = mockk<AuditLogRepository>()
    private val entries = mutableListOf<AuditLogEntry>()
    private val service = SetWorkspaceMemberRoleService(
        OwnerWorkspaceMutationRunner(transactions, assignments), assignments, audits,
        AuditEventFactory(clock), members, clock,
    )

    init {
        every { assignments.actorIsOrganizationAdminAfterLock(actor, workspace) } answers {
            assertThat(insideTransaction).isTrue()
            false
        }
        every { assignments.actorIsOwnerAfterLock(actor, workspace) } answers {
            assertThat(insideTransaction).isTrue()
            true
        }
        every { assignments.isEligibleMemberRoleTarget(workspace, target, true) } answers {
            assertThat(insideTransaction).isTrue()
            true
        }
        every { assignments.setMemberRole(workspace, target, any()) } answers {
            assertThat(insideTransaction).isTrue()
            AuthorizationAssignmentTransition.Changed
        }
        every { audits.insert(any()) } answers {
            assertThat(insideTransaction).isTrue()
            entries += firstArg<AuditLogEntry>()
            Unit
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["AGENT_MCP", "AGENT_REST"])
    fun `role change records actual actor target and verified Agent provenance in the mutation transaction`(surface: String) = runTest {
        val provenance = GovernanceAudit("request-role", surface, "https://client.example/metadata.json", organization)
        assertThat(service.set(command(provenance))).isTrue()
        assertThat(entries).hasSize(1)
        assertAudit(entries.single(), "workspace.member.role.set", WorkspaceRole.EDITOR, provenance)
        verify(exactly = 0) { members.insert(any()) }
    }

    @Test
    fun `administrator Owner assignment records new membership provenance in the same transaction`() = runTest {
        every { assignments.actorIsOrganizationAdminAfterLock(actor, workspace) } returns true
        every { assignments.isEligibleMemberRoleTarget(workspace, target, false) } returns true
        every { members.insert(any()) } answers {
            assertThat(insideTransaction).isTrue()
            WorkspaceMemberInsertion.Inserted
        }
        every { assignments.setMemberRole(workspace, target, WorkspaceRole.OWNER) } returns AuthorizationAssignmentTransition.NoTransition
        val provenance = GovernanceAudit("request-owner", "AGENT_MCP", "https://client.example", organization)
        assertThat(service.set(command(provenance, WorkspaceRole.OWNER))).isTrue()
        assertThat(entries).hasSize(1)
        assertAudit(entries.single(), "workspace.member.add", WorkspaceRole.OWNER, provenance)
    }

    @Test
    fun `same role emits no audit`() = runTest {
        every { assignments.setMemberRole(workspace, target, WorkspaceRole.EDITOR) } returns AuthorizationAssignmentTransition.NoTransition
        assertThat(service.set(command())).isFalse()
        verify(exactly = 0) { audits.insert(any()); members.insert(any()) }
    }

    @Test
    fun `denied actor emits no audit or role write`() = runTest {
        every { assignments.actorIsOwnerAfterLock(actor, workspace) } returns false
        assertFailsWith<AccessDeniedException> { service.set(command()) }
        verify(exactly = 0) { audits.insert(any()); members.insert(any()); assignments.setMemberRole(any(), any(), any()) }
    }

    @Test
    fun `unavailable target emits no audit or role write`() = runTest {
        every { assignments.isEligibleMemberRoleTarget(workspace, target, true) } returns false
        assertFailsWith<InvalidWorkspaceRoleTargetException> { service.set(command()) }
        verify(exactly = 0) { audits.insert(any()); members.insert(any()); assignments.setMemberRole(any(), any(), any()) }
    }

    @Test
    fun `last Owner rejection emits no audit`() = runTest {
        every { assignments.setMemberRole(workspace, target, WorkspaceRole.EDITOR) } throws LastWorkspaceOwnerException()
        assertFailsWith<LastWorkspaceOwnerException> { service.set(command()) }
        verify(exactly = 0) { audits.insert(any()); members.insert(any()) }
    }

    private fun command(audit: GovernanceAudit? = null, role: WorkspaceRole = WorkspaceRole.EDITOR) =
        SetWorkspaceMemberRoleUseCase.Command(actor, workspace, target, role, audit)

    private fun assertAudit(entry: AuditLogEntry, action: String, role: WorkspaceRole, provenance: GovernanceAudit) {
        assertThat(entry.action).isEqualTo(action)
        assertThat(entry.actorId).isEqualTo(actor.asPrincipal())
        assertThat(entry.organizationId).isEqualTo(organization)
        assertThat(entry.workspaceId).isEqualTo(workspace)
        assertThat(entry.resourceType).isEqualTo("user")
        assertThat(entry.resourceId).isEqualTo(target.value)
        assertThat(entry.actorKind).isEqualTo(AuditActorKind.AGENT)
        assertThat(entry.actorRef).isEqualTo(provenance.clientId)
        assertThat(entry.requestId).isEqualTo(provenance.requestId)
        assertThat(entry.occurredAt).isEqualTo(now)
        val details = Json.parseToJsonElement(entry.details).jsonObject
        assertThat(details.getValue("surface").jsonPrimitive.content).isEqualTo(provenance.surface)
        assertThat(details.getValue("role").jsonPrimitive.content).isEqualTo(role.name)
    }
}
