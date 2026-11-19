package cc.monomer.identity.application.workspace

import cc.monomer.identity.application.AccessDeniedException
import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.application.InvalidDisplayNameException
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.ReportingTimeZone
import cc.monomer.identity.domain.model.Workspace
import cc.monomer.identity.domain.port.AuditLogRepository
import cc.monomer.identity.domain.port.AuthorizationAssignmentRepository
import cc.monomer.identity.domain.port.TransactionRunner
import cc.monomer.identity.domain.port.WorkspaceQuery
import cc.monomer.identity.domain.port.WorkspaceRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkspacePublicPagesServiceTest {
    private val actor = UserId.generate()
    private val workspace = Workspace(WorkspaceId.generate(), OrganizationId.generate(), "Workspace", Instant.EPOCH, false, ReportingTimeZone.UTC, null)
    private val query = mockk<WorkspaceQuery>()
    private val repository = mockk<WorkspaceRepository>()
    private val authorization = mockk<AuthorizationAssignmentRepository>()
    private val audit = mockk<AuditLogRepository>(relaxed = true)
    private val transaction = object : TransactionRunner {
        override suspend fun <T> run(action: () -> T): T = action()
        override suspend fun <T> readOnly(action: () -> T): T = action()
    }
    private val service = SetWorkspacePublicPagesService(
        OwnerWorkspaceMutationRunner(transaction, authorization), query, repository, audit, AuditEventFactory(Clock.systemUTC()),
    )
    private val renameService = RenameWorkspaceService(
        OwnerWorkspaceMutationRunner(transaction, authorization), query, repository, audit, AuditEventFactory(Clock.systemUTC()),
    )

    @Test
    fun `member cannot publish workspace pages`() = runBlocking<Unit> {
        coEvery { query.find(workspace.id) } returns workspace
        every { authorization.actorIsOwnerAfterLock(actor, workspace.id) } returns false
        val failure = runCatching { service.set(SetWorkspacePublicPagesUseCase.Command(actor, workspace.id, true)) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(AccessDeniedException::class.java)
        verify(exactly = 0) { repository.updatePublicPages(any(), any()) }
        verify(exactly = 0) { audit.insert(any()) }
    }

    @Test
    fun `organization admin without ownership cannot publish workspace pages but can rename the workspace`() = runBlocking<Unit> {
        coEvery { query.find(workspace.id) } returns workspace
        every { authorization.actorIsOrganizationAdminAfterLock(actor, workspace.id) } returns true
        every { authorization.actorIsOwnerAfterLock(actor, workspace.id) } returns false
        val failure = runCatching { service.set(SetWorkspacePublicPagesUseCase.Command(actor, workspace.id, true)) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(AccessDeniedException::class.java)
        verify(exactly = 0) { repository.updatePublicPages(any(), any()) }

        every { repository.updateDisplayName(workspace.id, "Renamed") } returns Unit
        val renamed = renameService.rename(RenameWorkspaceUseCase.Command(actor, workspace.id, "Renamed"))
        assertThat(renamed.displayName).isEqualTo("Renamed")
        verify(exactly = 1) { repository.updateDisplayName(workspace.id, "Renamed") }
        verify(exactly = 1) { audit.insert(match { it.action == "workspace.rename" && it.organizationId == workspace.organizationId }) }
    }

    @Test
    fun `member who is neither organization admin nor owner cannot rename the workspace`() = runBlocking<Unit> {
        coEvery { query.find(workspace.id) } returns workspace
        every { authorization.actorIsOrganizationAdminAfterLock(actor, workspace.id) } returns false
        every { authorization.actorIsOwnerAfterLock(actor, workspace.id) } returns false
        val failure = runCatching { renameService.rename(RenameWorkspaceUseCase.Command(actor, workspace.id, "Renamed")) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(AccessDeniedException::class.java)
        verify(exactly = 0) { repository.updateDisplayName(any(), any()) }
        verify(exactly = 0) { audit.insert(any()) }
    }

    @Test
    fun `rename stores the trimmed name and rejects a blank name before authorization or writes`() = runBlocking<Unit> {
        coEvery { query.find(workspace.id) } returns workspace
        every { authorization.actorIsOrganizationAdminAfterLock(actor, workspace.id) } returns false
        every { authorization.actorIsOwnerAfterLock(actor, workspace.id) } returns true
        every { repository.updateDisplayName(workspace.id, "Renamed") } returns Unit
        val renamed = renameService.rename(RenameWorkspaceUseCase.Command(actor, workspace.id, "  Renamed  "))
        assertThat(renamed.displayName).isEqualTo("Renamed")
        verify(exactly = 1) { audit.insert(match { it.action == "workspace.rename" && it.details == "{\"displayName\":\"Renamed\"}" }) }

        val failure = runCatching { renameService.rename(RenameWorkspaceUseCase.Command(actor, workspace.id, " \t ")) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(InvalidDisplayNameException::class.java)
            .hasMessage("workspace displayName must not be blank")
        verify(exactly = 1) { repository.updateDisplayName(any(), any()) }
        verify(exactly = 1) { authorization.actorIsOwnerAfterLock(actor, workspace.id) }
    }

    @Test
    fun `owner publishes without changing relation policy and audits only a transition`() = runBlocking<Unit> {
        coEvery { query.find(workspace.id) } returns workspace
        every { authorization.actorIsOwnerAfterLock(actor, workspace.id) } returns true
        every { repository.updatePublicPages(workspace.id, true) } returnsMany listOf(true, false)
        val updated = service.set(SetWorkspacePublicPagesUseCase.Command(actor, workspace.id, true))
        assertThat(updated.publicPages).isTrue()
        service.set(SetWorkspacePublicPagesUseCase.Command(actor, workspace.id, true))
        verify(exactly = 1) { audit.insert(match { it.action == "workspace.public_pages.set" && it.details == "{\"publicPages\":true}" }) }
    }
}
