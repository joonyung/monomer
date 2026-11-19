package cc.monomer.identity

import cc.monomer.identity.api.OrganizationPlan
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.application.AccessDeniedException
import cc.monomer.identity.application.AdminAuthorization
import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.application.workspace.CreateWorkspaceService
import cc.monomer.identity.application.workspace.CreateWorkspaceUseCase
import cc.monomer.identity.application.workspace.OwnerWorkspaceMutationRunner
import cc.monomer.identity.application.workspace.SetWorkspaceReportingTimeZoneService
import cc.monomer.identity.application.workspace.SetWorkspaceReportingTimeZoneUseCase
import cc.monomer.identity.application.workspace.WorkspaceAuthorizationBootstrap
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.Organization
import cc.monomer.identity.domain.model.OrganizationMembership
import cc.monomer.identity.domain.model.OrganizationRole
import cc.monomer.identity.domain.model.ReportingTimeZone
import cc.monomer.identity.domain.model.User
import cc.monomer.identity.domain.model.WorkspaceMembership
import cc.monomer.identity.domain.port.ManagedWorkspacesQueryResult
import cc.monomer.identity.outbound.postgres.AuthSchemaVerifier
import cc.monomer.identity.outbound.postgres.PostgresAuditLogRepository
import cc.monomer.identity.outbound.postgres.PostgresAuthorizationAssignmentRepository
import cc.monomer.identity.outbound.postgres.PostgresOrganizationGovernanceOperations
import cc.monomer.identity.outbound.postgres.PostgresOrganizationRepository
import cc.monomer.identity.outbound.postgres.PostgresUserRepository
import cc.monomer.identity.outbound.postgres.PostgresWorkspaceMemberRepository
import cc.monomer.identity.outbound.postgres.PostgresWorkspaceQuery
import cc.monomer.identity.outbound.postgres.PostgresWorkspaceReportingTimeZoneQuery
import cc.monomer.identity.outbound.postgres.PostgresWorkspaceRepository
import cc.monomer.identity.outbound.postgres.SpringTransactionRunner
import cc.monomer.identity.outbound.postgres.WorkspaceReportingTimeZoneMissingException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

class PostgresWorkspaceReportingTimeZoneIntegrationTest {
    private val jdbc get() = NamedParameterJdbcTemplate(TargetIdentityPostgres.dataSource)
    private val now = Instant.parse("2026-10-12T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private lateinit var transactions: SpringTransactionRunner
    private lateinit var organizations: PostgresOrganizationRepository
    private lateinit var users: PostgresUserRepository
    private lateinit var workspaces: CreateWorkspaceService
    private lateinit var setter: SetWorkspaceReportingTimeZoneService
    private lateinit var workspaceQuery: PostgresWorkspaceQuery
    private lateinit var reportingTimeZoneQuery: PostgresWorkspaceReportingTimeZoneQuery

    @BeforeEach
    fun setup() {
        TargetIdentityPostgres.dataSource.connection.use {
            TargetIdentityPostgres.applyCanonicalSchemaTwice(it, javaClass.classLoader)
            AuthSchemaVerifier.verifyContractFacts(it)
        }
        val manager = DataSourceTransactionManager(TargetIdentityPostgres.dataSource)
        transactions = SpringTransactionRunner(
            TransactionTemplate(manager),
            TransactionTemplate(manager).apply { isReadOnly = true },
            blockingDispatcher = Dispatchers.Unconfined,
        )
        organizations = PostgresOrganizationRepository(jdbc)
        users = PostgresUserRepository(jdbc)
        val workspaceRepository = PostgresWorkspaceRepository(jdbc)
        val audit = PostgresAuditLogRepository(jdbc)
        val auditFactory = AuditEventFactory(clock)
        workspaceQuery = PostgresWorkspaceQuery(jdbc, transactions)
        reportingTimeZoneQuery = PostgresWorkspaceReportingTimeZoneQuery(jdbc, transactions)
        workspaces = CreateWorkspaceService(
            transactions, AdminAuthorization(organizations), workspaceRepository, organizations,
            WorkspaceAuthorizationBootstrap(PostgresWorkspaceMemberRepository(jdbc)), audit, auditFactory, clock,
        )
        setter = SetWorkspaceReportingTimeZoneService(
            OwnerWorkspaceMutationRunner(transactions, PostgresAuthorizationAssignmentRepository(jdbc)),
            workspaceQuery, workspaceRepository, audit, auditFactory,
        )
    }

    @Test
    fun `new workspaces report in UTC and every role reads the stored zone`() = runBlocking<Unit> {
        val admin = user()
        val owner = user()
        val organization = organization(admin, owner)
        val workspace = workspaces.create(CreateWorkspaceUseCase.Command(admin, owner, organization, "Reporting"))
        val viewer = member(organization, workspace.id, WorkspaceRole.VIEWER)

        assertThat(workspace.reportingTimeZone).isEqualTo(ReportingTimeZone.UTC)
        assertThat(storedTimeZone(workspace.id)).isEqualTo("UTC")
        assertThat(reportingTimeZoneQuery.reportingTimeZone(publicId(workspace.id))).isEqualTo(ZoneId.of("UTC"))

        set(owner, workspace.id, "Asia/Seoul")

        val seoul = ZoneId.of("Asia/Seoul")
        assertThat(storedTimeZone(workspace.id)).isEqualTo("Asia/Seoul")
        assertThat(reportingTimeZoneQuery.reportingTimeZone(publicId(workspace.id))).isEqualTo(seoul)
        assertThat(workspaceQuery.find(workspace.id)!!.reportingTimeZone.zoneId).isEqualTo(seoul)
        assertThat(workspaceQuery.listAccessibleByUser(viewer).single().workspace.reportingTimeZone.zoneId).isEqualTo(seoul)
        val managed = PostgresOrganizationGovernanceOperations(jdbc, transactions).readManagedWorkspaces(admin, organization)
        assertThat((managed as ManagedWorkspacesQueryResult.Authorized).workspaces.single().reportingTimeZone.zoneId).isEqualTo(seoul)
    }

    @Test
    fun `owner and organization admin set the zone, other roles cannot, and only a change is audited`() = runBlocking<Unit> {
        val admin = user()
        val owner = user()
        val organization = organization(admin, owner)
        val workspace = workspaces.create(CreateWorkspaceUseCase.Command(admin, owner, organization, "Reporting"))
        val members = listOf(WorkspaceRole.EDITOR, WorkspaceRole.EXPLORER, WorkspaceRole.VIEWER)
            .associateWith { role -> member(organization, workspace.id, role) }
        val revision = authorizationRevision(workspace.id)

        for ((role, member) in members) {
            val failure = runCatching { set(member, workspace.id, "Asia/Seoul") }.exceptionOrNull()
            assertThat(failure).describedAs(role.name).isInstanceOf(AccessDeniedException::class.java)
        }
        assertThat(storedTimeZone(workspace.id)).isEqualTo("UTC")
        assertThat(audits(workspace.id)).isEmpty()

        assertThat(set(owner, workspace.id, "Asia/Seoul").workspace.reportingTimeZone.id).isEqualTo("Asia/Seoul")
        set(owner, workspace.id, "Asia/Seoul")
        assertThat(audits(workspace.id)).containsExactly(owner.value.toJavaUuid() to "Asia/Seoul")

        assertThat(set(admin, workspace.id, "America/New_York").workspace.reportingTimeZone.id).isEqualTo("America/New_York")
        set(admin, workspace.id, "America/New_York")
        assertThat(storedTimeZone(workspace.id)).isEqualTo("America/New_York")
        assertThat(audits(workspace.id)).containsExactlyInAnyOrder(
            owner.value.toJavaUuid() to "Asia/Seoul", admin.value.toJavaUuid() to "America/New_York",
        )
        assertThat(authorizationRevision(workspace.id)).isEqualTo(revision)
    }

    @Test
    fun `an unlisted zone is rejected without a write`() = runBlocking<Unit> {
        val admin = user()
        val owner = user()
        val workspace = workspaces.create(CreateWorkspaceUseCase.Command(admin, owner, organization(admin, owner), "Reporting"))

        for (unsupported in listOf("US/Eastern", "Asia/Calcutta", "Etc/GMT-9", "+09:00", "Mars/Olympus_Mons")) {
            assertThat(setter.set(SetWorkspaceReportingTimeZoneUseCase.Command(owner, workspace.id, unsupported)))
                .isEqualTo(SetWorkspaceReportingTimeZoneUseCase.Result.UnsupportedTimeZone(unsupported))
        }
        assertThat(storedTimeZone(workspace.id)).isEqualTo("UTC")
        assertThat(audits(workspace.id)).isEmpty()
    }

    @Test
    fun `reading the zone of a missing workspace fails explicitly`() = runBlocking<Unit> {
        val missing = cc.monomer.identity.api.WorkspaceId.generate()

        val failure = runCatching { reportingTimeZoneQuery.reportingTimeZone(missing) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(WorkspaceReportingTimeZoneMissingException::class.java)
            .hasMessageContaining(missing.toString())
    }

    @Test
    fun `PostgreSQL accepts every listed zone name`() {
        val postgresZones = jdbc.jdbcTemplate.queryForList("SELECT name FROM pg_timezone_names", String::class.java).toSet()

        assertThat(postgresZones).containsAll(ReportingTimeZone.supportedIds)
    }

    private suspend fun set(actor: UserId, workspace: WorkspaceId, timeZone: String) =
        setter.set(SetWorkspaceReportingTimeZoneUseCase.Command(actor, workspace, timeZone))
            as SetWorkspaceReportingTimeZoneUseCase.Result.Set

    private fun publicId(workspace: WorkspaceId) = cc.monomer.identity.api.WorkspaceId(workspace.value)

    private fun storedTimeZone(workspace: WorkspaceId): String = jdbc.queryForObject(
        "SELECT reporting_time_zone FROM identity_runtime.workspaces WHERE id = :id",
        mapOf("id" to workspace.value.toJavaUuid()), String::class.java,
    )!!

    private fun authorizationRevision(workspace: WorkspaceId): Long = jdbc.queryForObject(
        "SELECT authorization_revision FROM identity_runtime.workspaces WHERE id = :id",
        mapOf("id" to workspace.value.toJavaUuid()), Long::class.java,
    )!!

    private fun audits(workspace: WorkspaceId): List<Pair<java.util.UUID, String>> = jdbc.query(
        """SELECT actor_id, details->>'reportingTimeZone' AS reporting_time_zone FROM identity_runtime.audit_log
           WHERE workspace_id = :workspace AND action = 'workspace.reporting_time_zone.set'""",
        mapOf("workspace" to workspace.value.toJavaUuid()),
    ) { row, _ -> row.getObject("actor_id", java.util.UUID::class.java) to row.getString("reporting_time_zone") }

    private suspend fun user(): UserId = transactions.run {
        val user = User(UserId.generate(), "${Uuid.generateV7()}@example.test", "Test User")
        check(users.insertIfPrimaryEmailAbsent(user))
        user.userId
    }

    private suspend fun organization(admin: UserId, owner: UserId): OrganizationId = transactions.run {
        val organization = OrganizationId.generate()
        organizations.insert(Organization(organization, "Reporting Organization", null, OrganizationPlan.FREE, now))
        organizations.insertMember(OrganizationMembership(organization, admin, OrganizationRole.ADMIN, now))
        organizations.insertMember(OrganizationMembership(organization, owner, OrganizationRole.MEMBER, now))
        organization
    }

    private suspend fun member(organization: OrganizationId, workspace: WorkspaceId, role: WorkspaceRole): UserId {
        val member = user()
        transactions.run {
            organizations.insertMember(OrganizationMembership(organization, member, OrganizationRole.MEMBER, now))
            PostgresWorkspaceMemberRepository(jdbc).insert(WorkspaceMembership(workspace, member, null, now, role))
        }
        return member
    }

    companion object {
        @JvmField
        @RegisterExtension
        internal val postgres = TargetIdentityPostgres
    }
}
