package cc.monomer.identity

import cc.monomer.identity.application.OpaqueCredentials
import cc.monomer.identity.api.OrganizationPlan
import cc.monomer.identity.application.AdminAuthorization
import cc.monomer.identity.application.organization.*
import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.application.workspace.*
import cc.monomer.identity.domain.id.*
import cc.monomer.identity.domain.model.*
import cc.monomer.identity.domain.port.*
import cc.monomer.identity.outbound.postgres.*
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

class PostgresFreeOrganizationLimitsIntegrationTest {
    private val jdbc get() = NamedParameterJdbcTemplate(TargetIdentityPostgres.dataSource)
    private val now = Instant.parse("2026-09-09T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private lateinit var transactions: SpringTransactionRunner
    private lateinit var organizationRepository: PostgresOrganizationRepository
    private lateinit var createOrganizationService: CreateOrganizationService
    private lateinit var workspaces: CreateWorkspaceService
    private lateinit var roles: UpdateOrganizationMemberRoleService
    private lateinit var invitations: OrganizationInvitationService
    private lateinit var invitationRepository: PostgresOrganizationInvitationRepository

    @BeforeEach
    fun setup() {
        TargetIdentityPostgres.dataSource.connection.use {
            TargetIdentityPostgres.applyCanonicalSchemaTwice(it, javaClass.classLoader)
        }
        val manager = DataSourceTransactionManager(TargetIdentityPostgres.dataSource)
        transactions = SpringTransactionRunner(TransactionTemplate(manager), TransactionTemplate(manager).apply { isReadOnly = true }, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        organizationRepository = PostgresOrganizationRepository(jdbc)
        val admin = AdminAuthorization(organizationRepository)
        val workspaceRepository = PostgresWorkspaceRepository(jdbc)
        val bootstrap = WorkspaceAuthorizationBootstrap(PostgresWorkspaceMemberRepository(jdbc))
        val audit = PostgresAuditLogRepository(jdbc)
        val auditFactory = AuditEventFactory(clock)
        createOrganizationService = CreateOrganizationService(transactions, organizationRepository, workspaceRepository, bootstrap, audit, auditFactory, clock)
        workspaces = CreateWorkspaceService(transactions, admin, workspaceRepository, organizationRepository, bootstrap, audit, auditFactory, clock)
        roles = UpdateOrganizationMemberRoleService(transactions, admin, organizationRepository, audit, auditFactory)
        invitationRepository = PostgresOrganizationInvitationRepository(jdbc)
        invitations = OrganizationInvitationService(transactions, invitationRepository, organizationRepository, PostgresWorkspaceMemberRepository(jdbc), admin, audit, auditFactory,
            object : InvitationDelivery {
                override suspend fun send(deliveryEmail: String, organizationDisplayName: String, invitedBy: User?, expiresAt: Instant, rawToken: String) = false
            }, clock)
    }

    @Test
    fun `creation and promotion compete for one user admin slot`() = runBlocking<Unit> {
        val target = user()
        val admin = user()
        repeat(4) { organization(target) }
        val promotionOrganization = organization(admin)
        member(promotionOrganization, target, OrganizationRole.MEMBER)
        val results = race(
            { createOrganizationService.create(CreateOrganizationUseCase.Command(target, target, "New Organization")) },
            { roles.update(UpdateOrganizationMemberRoleUseCase.Command(admin.asPrincipal(), promotionOrganization, target, OrganizationRole.ADMIN)) },
        )
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(results.mapNotNull { it.exceptionOrNull() }).allMatch { it is FreeOrganizationAdminLimitException }
        assertThat(adminCount(target)).isEqualTo(5)
    }

    @Test
    fun `two organization creations share the same user row and failed creation is atomic`() = runBlocking<Unit> {
        val target = user()
        repeat(4) { organization(target) }
        val results = race(
            { createOrganizationService.create(CreateOrganizationUseCase.Command(target, target, "First")) },
            { createOrganizationService.create(CreateOrganizationUseCase.Command(target, target, "Second")) },
        )
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(results.single { it.isFailure }.exceptionOrNull()).isInstanceOf(FreeOrganizationAdminLimitException::class.java)
        assertThat(adminCount(target)).isEqualTo(5)
    }

    @Test
    fun `new membership insertions share the last organization seat`() = runBlocking<Unit> {
        val admin = user()
        val organization = organization(admin)
        repeat(28) { member(organization, user(), OrganizationRole.MEMBER) }
        val first = user()
        val second = user()
        val results = race(
            { transactions.run { organizationRepository.insertMember(membership(organization, first, OrganizationRole.MEMBER)) } },
            { transactions.run { organizationRepository.insertMember(membership(organization, second, OrganizationRole.MEMBER)) } },
        )
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(results.single { it.isFailure }.exceptionOrNull()).isInstanceOf(FreeOrganizationMemberLimitException::class.java)
        assertThat(memberCount(organization)).isEqualTo(30)
    }

    @Test
    fun `workspace creations compete at five including default workspace`() = runBlocking<Unit> {
        val admin = user()
        val organization = createOrganizationService.create(CreateOrganizationUseCase.Command(admin, admin, "Workspace Organization")).organization.id
        repeat(3) { workspaces.create(CreateWorkspaceUseCase.Command(admin, admin, organization, "Workspace $it")) }
        val results = race(
            { workspaces.create(CreateWorkspaceUseCase.Command(admin, admin, organization, "First")) },
            { workspaces.create(CreateWorkspaceUseCase.Command(admin, admin, organization, "Second")) },
        )
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(results.single { it.isFailure }.exceptionOrNull()).isInstanceOf(FreeWorkspaceLimitException::class.java)
        assertThat(count("SELECT count(*) FROM identity_runtime.workspaces WHERE organization_id = :id", organization.value)).isEqualTo(5)
    }

    @Test
    fun `fifth free organization shares workspace and admin limits`() = runBlocking<Unit> {
        val admin = user()
        repeat(4) { organization(admin) }
        val organization = transactions.run {
            val id = OrganizationId.generate()
            organizationRepository.insert(Organization(id, "Fifth Organization", null, OrganizationPlan.FREE, now))
            organizationRepository.insertMember(membership(id, admin, OrganizationRole.ADMIN))
            id
        }
        repeat(5) { workspaces.create(CreateWorkspaceUseCase.Command(admin, admin, organization, "Workspace $it")) }
        val result = runCatching { workspaces.create(CreateWorkspaceUseCase.Command(admin, admin, organization, "Sixth")) }
        assertThat(result.exceptionOrNull()).isInstanceOf(FreeWorkspaceLimitException::class.java)
        assertThat(adminCount(admin)).isEqualTo(5)
    }

    @Test
    fun `concurrent creators can nominate each other as initial owner without user foreign key deadlock`() {
        val first = user()
        val second = user()
        val results = race(
            { createOrganizationService.create(CreateOrganizationUseCase.Command(first, second, "First")) },
            { createOrganizationService.create(CreateOrganizationUseCase.Command(second, first, "Second")) },
        )
        assertThat(results).allMatch { it.isSuccess }
        assertThat(adminCount(first)).isEqualTo(1)
        assertThat(adminCount(second)).isEqualTo(1)
    }

    @Test
    fun `valid pending invitations compete at thirty and resend does not add a slot`() = runBlocking<Unit> {
        val admin = user()
        val secondAdmin = user()
        val organization = organization(admin)
        member(organization, secondAdmin, OrganizationRole.ADMIN)
        val existing = (0 until 29).map { invitation(organization, admin, now.plusSeconds(3600)) }
        val expired = invitation(organization, admin, now.minusSeconds(1))
        val results = race(
            { invitations.create(admin, organization, "first-$organization@example.test", emptyList(), null) },
            { invitations.create(secondAdmin, organization, "second-$organization@example.test", emptyList(), null) },
        )
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(results.single { it.isFailure }.exceptionOrNull()).isInstanceOf(FreePendingInvitationLimitException::class.java)
        assertThat(invitations.resend(admin, organization, existing.first(), null).outcome).isEqualTo(OrganizationInvitationService.Outcome.DELIVERY_FAILED)
        assertThat(runCatching { invitations.resend(admin, organization, expired, null) }.exceptionOrNull()).isInstanceOf(FreePendingInvitationLimitException::class.java)
        assertThat(count("SELECT count(*) FROM identity_runtime.organization_membership_invitations WHERE organization_id = :id AND expires_at > '2026-09-09T00:00:00Z'", organization.value)).isEqualTo(30)
    }

    @Test
    fun `paid organizations are excluded from free member admin workspace and pending limits`() = runBlocking<Unit> {
        val target = user()
        repeat(5) { organization(target) }
        for (plan in listOf(OrganizationPlan.PRO, OrganizationPlan.ENTERPRISE)) {
            val admin = user()
            val organization = organization(admin, plan)
            member(organization, target, OrganizationRole.MEMBER)
            roles.update(UpdateOrganizationMemberRoleUseCase.Command(admin.asPrincipal(), organization, target, OrganizationRole.ADMIN))
            repeat(30) { transactions.run { organizationRepository.insertMember(membership(organization, user(), OrganizationRole.MEMBER)) } }
            repeat(6) { workspaces.create(CreateWorkspaceUseCase.Command(admin, admin, organization, "Workspace $it")) }
            repeat(30) { invitation(organization, admin, now.plusSeconds(3600)) }
            assertThat(invitations.create(admin, organization, "more-$organization@example.test", emptyList(), null).outcome).isEqualTo(OrganizationInvitationService.Outcome.DELIVERY_FAILED)
            assertThat(memberCount(organization)).isEqualTo(32)
        }
        assertThat(adminCount(target)).isEqualTo(5)
    }

    @Test
    fun `ordinary member participation has no organization count limit and admin demotion returns capacity`() = runBlocking<Unit> {
        val target = user()
        val admin = user()
        val managed = (0 until 5).map { organization(target) }
        member(managed.first(), admin, OrganizationRole.ADMIN)
        repeat(6) {
            val organization = organization(admin)
            transactions.run { organizationRepository.insertMember(membership(organization, target, OrganizationRole.MEMBER)) }
        }
        roles.update(UpdateOrganizationMemberRoleUseCase.Command(admin.asPrincipal(), managed.first(), target, OrganizationRole.MEMBER))
        createOrganizationService.create(CreateOrganizationUseCase.Command(target, target, "Replacement Organization"))
        assertThat(adminCount(target)).isEqualTo(5)
    }

    @Test
    fun `organization usage equals the counts the free limit checks enforce`() = runBlocking<Unit> {
        val admin = user()
        val inviter = user()
        val organization = createOrganizationService.create(CreateOrganizationUseCase.Command(admin, admin, "Usage Organization")).organization.id
        member(organization, inviter, OrganizationRole.ADMIN)
        val usageService = OrganizationUsageService(PostgresOrganizationGovernanceOperations(jdbc, transactions), clock)
        suspend fun usage() = usageService.read(OrganizationUsageUseCase.Command(admin, organization))

        assertThat(usage().plan).isEqualTo(OrganizationPlan.FREE)
        assertThat(usage().limits).isEqualTo(OrganizationResourceCounts(
            FreeOrganizationLimits.MEMBERS, FreeOrganizationLimits.WORKSPACES, FreeOrganizationLimits.PENDING_INVITATIONS,
        ))
        assertThat(usage().usage).isEqualTo(OrganizationResourceCounts(members = 2, workspaces = 1, pendingInvitations = 0))

        repeat(27) { member(organization, user(), OrganizationRole.MEMBER) }
        assertThat(usage().usage.members).isEqualTo(29)
        transactions.run { organizationRepository.insertMember(membership(organization, user(), OrganizationRole.MEMBER)) }
        assertThat(usage().usage.members).isEqualTo(FreeOrganizationLimits.MEMBERS)
        assertThat(runCatching { transactions.run { organizationRepository.insertMember(membership(organization, user(), OrganizationRole.MEMBER)) } }
            .exceptionOrNull()).isInstanceOf(FreeOrganizationMemberLimitException::class.java)
        assertThat(usage().usage.members).isEqualTo(memberCount(organization))

        repeat(3) { workspaces.create(CreateWorkspaceUseCase.Command(admin, admin, organization, "Workspace $it")) }
        assertThat(usage().usage.workspaces).isEqualTo(4)
        workspaces.create(CreateWorkspaceUseCase.Command(admin, admin, organization, "Last"))
        assertThat(usage().usage.workspaces).isEqualTo(FreeOrganizationLimits.WORKSPACES)
        assertThat(runCatching { workspaces.create(CreateWorkspaceUseCase.Command(admin, admin, organization, "Over")) }.exceptionOrNull())
            .isInstanceOf(FreeWorkspaceLimitException::class.java)

        repeat(29) { invitation(organization, inviter, now.plusSeconds(3600)) }
        invitation(organization, inviter, now.minusSeconds(1))
        invitation(organization, inviter, now)
        assertThat(usage().usage.pendingInvitations).isEqualTo(29)
        invitations.create(admin, organization, "last-$organization@example.test", emptyList(), null)
        assertThat(usage().usage.pendingInvitations).isEqualTo(FreeOrganizationLimits.PENDING_INVITATIONS)
        assertThat(runCatching { invitations.create(admin, organization, "over-$organization@example.test", emptyList(), null) }.exceptionOrNull())
            .isInstanceOf(FreePendingInvitationLimitException::class.java)
    }

    @Test
    fun `organization usage is readable only by its admins and paid plans expose no free limits`() = runBlocking<Unit> {
        val admin = user()
        val organization = organization(admin)
        val plainMember = user()
        member(organization, plainMember, OrganizationRole.MEMBER)
        val foreignAdmin = user()
        organization(foreignAdmin)
        val usageService = OrganizationUsageService(PostgresOrganizationGovernanceOperations(jdbc, transactions), clock)
        for (actor in listOf(plainMember, foreignAdmin, user())) {
            assertThat(runCatching { usageService.read(OrganizationUsageUseCase.Command(actor, organization)) }.exceptionOrNull())
                .isInstanceOf(cc.monomer.identity.application.AccessDeniedException::class.java)
        }
        assertThat(usageService.read(OrganizationUsageUseCase.Command(admin, organization)).usage.members).isEqualTo(2)

        val paidAdmin = user()
        val paidOrganization = organization(paidAdmin, OrganizationPlan.PRO)
        repeat(31) { transactions.run { organizationRepository.insertMember(membership(paidOrganization, user(), OrganizationRole.MEMBER)) } }
        val paidUsage = usageService.read(OrganizationUsageUseCase.Command(paidAdmin, paidOrganization))
        assertThat(paidUsage.plan).isEqualTo(OrganizationPlan.PRO)
        assertThat(paidUsage.limits).isNull()
        assertThat(paidUsage.usage).isEqualTo(OrganizationResourceCounts(members = 32, workspaces = 0, pendingInvitations = 0))
    }

    private fun user(): UserId {
        val user = UserId.generate()
        val parameters = mapOf("id" to user.value.toJavaUuid(), "email" to "$user@example.test", "now" to Timestamp.from(now))
        jdbc.update("INSERT INTO identity_runtime.principals(id,kind,display_name,created_at) VALUES(:id,'user','Test User',:now)", parameters)
        jdbc.update("INSERT INTO identity_runtime.users(id,primary_email,created_at) VALUES(:id,:email,:now)", parameters)
        return user
    }

    private suspend fun organization(admin: UserId, plan: OrganizationPlan = OrganizationPlan.FREE): OrganizationId = transactions.run {
        val organization = OrganizationId.generate()
        organizationRepository.insert(Organization(organization, "Test Organization", null, plan, now))
        member(organization, admin, OrganizationRole.ADMIN)
        organization
    }

    private fun member(organization: OrganizationId, user: UserId, role: OrganizationRole) {
        jdbc.update("""INSERT INTO identity_runtime.organization_members(organization_id,user_id,role,joined_at)
            VALUES(:organization,:user,:role,:now)""", mapOf("organization" to organization.value.toJavaUuid(),
            "user" to user.value.toJavaUuid(), "role" to role.name, "now" to Timestamp.from(now)))
    }

    private fun membership(organization: OrganizationId, user: UserId, role: OrganizationRole) =
        OrganizationMembership(organization, user, role, now)

    private suspend fun invitation(organization: OrganizationId, admin: UserId, expires: Instant): Uuid = transactions.run {
        val id = Uuid.generateV7()
        invitationRepository.insert(OrganizationMembershipInvitation(id, organization, "$id@example.test", "$id@example.test",
            OpaqueCredentials.hash(OpaqueCredentials.generate()), admin, now.minusSeconds(120), expires, now.minusSeconds(120), null, emptyList()))
        id
    }

    private fun adminCount(user: UserId) = count("""SELECT count(*) FROM identity_runtime.organization_members member
        JOIN identity_runtime.organizations organization ON organization.id = member.organization_id
        WHERE member.user_id = :id AND member.role = 'ADMIN'
        AND organization.plan = 'free'""", user.value)
    private fun memberCount(organization: OrganizationId) = count("SELECT count(*) FROM identity_runtime.organization_members WHERE organization_id = :id", organization.value)
    private fun count(sql: String, id: Uuid) = jdbc.queryForObject(sql, mapOf("id" to id.toJavaUuid()), Int::class.java)!!

    private fun race(vararg actions: suspend () -> Any): List<Result<Any>> {
        val ready = CountDownLatch(actions.size)
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(actions.size).use { executor ->
            val futures = actions.map { action -> executor.submit<Result<Any>> {
                ready.countDown()
                check(start.await(10, TimeUnit.SECONDS))
                runCatching { runBlocking { action() } }
            } }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            return futures.map { it.get(20, TimeUnit.SECONDS) }
        }
    }

    companion object {
        @JvmField @RegisterExtension internal val postgres = TargetIdentityPostgres
    }
}
