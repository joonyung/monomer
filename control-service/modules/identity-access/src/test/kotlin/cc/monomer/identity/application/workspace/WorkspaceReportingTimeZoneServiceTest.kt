package cc.monomer.identity.application.workspace

import cc.monomer.identity.application.AccessDeniedException
import cc.monomer.identity.application.audit.AuditEventFactory
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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkspaceReportingTimeZoneServiceTest {
    private val actor = UserId.generate()
    private val workspace = Workspace(WorkspaceId.generate(), OrganizationId.generate(), "Workspace", Instant.EPOCH, false, ReportingTimeZone.UTC, null)
    private val seoul = checkNotNull(ReportingTimeZone.of("Asia/Seoul"))
    private val query = mockk<WorkspaceQuery>()
    private val repository = mockk<WorkspaceRepository>()
    private val authorization = mockk<AuthorizationAssignmentRepository>()
    private val audit = mockk<AuditLogRepository>(relaxed = true)
    private val transaction = object : TransactionRunner {
        override suspend fun <T> run(action: () -> T): T = action()
        override suspend fun <T> readOnly(action: () -> T): T = action()
    }
    private val service = SetWorkspaceReportingTimeZoneService(
        OwnerWorkspaceMutationRunner(transaction, authorization), query, repository, audit, AuditEventFactory(Clock.systemUTC()),
    )

    private fun command(timeZone: String) = SetWorkspaceReportingTimeZoneUseCase.Command(actor, workspace.id, timeZone)

    @Test
    fun `an unlisted time zone is rejected before authorization or writes`() = runBlocking<Unit> {
        for (unsupported in listOf("US/Eastern", "Asia/Calcutta", "Etc/GMT-9", "+09:00", "asia/seoul", "")) {
            assertThat(service.set(command(unsupported)))
                .isEqualTo(SetWorkspaceReportingTimeZoneUseCase.Result.UnsupportedTimeZone(unsupported))
        }
        coVerify(exactly = 0) { query.find(any()) }
        verify(exactly = 0) { authorization.actorIsOwnerAfterLock(any(), any()) }
        verify(exactly = 0) { repository.updateReportingTimeZone(any(), any()) }
        verify(exactly = 0) { audit.insert(any()) }
    }

    @Test
    fun `owner sets the zone and only a change is audited`() = runBlocking<Unit> {
        coEvery { query.find(workspace.id) } returns workspace
        every { authorization.actorIsOrganizationAdminAfterLock(actor, workspace.id) } returns false
        every { authorization.actorIsOwnerAfterLock(actor, workspace.id) } returns true
        every { repository.updateReportingTimeZone(workspace.id, seoul) } returnsMany listOf(true, false)

        val first = service.set(command("Asia/Seoul"))
        val repeated = service.set(command("Asia/Seoul"))

        assertThat(first).isEqualTo(SetWorkspaceReportingTimeZoneUseCase.Result.Set(workspace.copy(reportingTimeZone = seoul)))
        assertThat(repeated).isEqualTo(first)
        verify(exactly = 1) {
            audit.insert(match {
                it.action == "workspace.reporting_time_zone.set" && it.organizationId == workspace.organizationId &&
                    it.workspaceId == workspace.id && it.details == "{\"reportingTimeZone\":\"Asia/Seoul\"}"
            })
        }
    }

    @Test
    fun `organization admin without ownership sets the zone`() = runBlocking<Unit> {
        coEvery { query.find(workspace.id) } returns workspace
        every { authorization.actorIsOrganizationAdminAfterLock(actor, workspace.id) } returns true
        every { repository.updateReportingTimeZone(workspace.id, seoul) } returns true

        val result = service.set(command("Asia/Seoul"))

        assertThat((result as SetWorkspaceReportingTimeZoneUseCase.Result.Set).workspace.reportingTimeZone).isEqualTo(seoul)
        verify(exactly = 0) { authorization.actorIsOwnerAfterLock(any(), any()) }
        verify(exactly = 1) { audit.insert(any()) }
    }

    @Test
    fun `member who is neither organization admin nor owner cannot set the zone`() = runBlocking<Unit> {
        coEvery { query.find(workspace.id) } returns workspace
        every { authorization.actorIsOrganizationAdminAfterLock(actor, workspace.id) } returns false
        every { authorization.actorIsOwnerAfterLock(actor, workspace.id) } returns false

        val failure = runCatching { service.set(command("Asia/Seoul")) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(AccessDeniedException::class.java)
        verify(exactly = 0) { repository.updateReportingTimeZone(any(), any()) }
        verify(exactly = 0) { audit.insert(any()) }
    }
}
