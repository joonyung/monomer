package cc.monomer.identity

import cc.monomer.identity.application.login.LoginUseCase

import cc.monomer.identity.api.OrganizationPlan
import cc.monomer.identity.application.AdminAuthorization
import cc.monomer.identity.application.organization.CreateOrganizationService
import cc.monomer.identity.application.organization.CreateOrganizationUseCase
import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.application.identity.LoginIdentityResolver
import cc.monomer.identity.application.identity.NormalizedEmailUserResolver
import cc.monomer.identity.application.login.LoginService
import cc.monomer.identity.application.workspace.CreateWorkspaceService
import cc.monomer.identity.application.workspace.CreateWorkspaceUseCase
import cc.monomer.identity.application.workspace.WorkspaceAuthorizationBootstrap
import cc.monomer.identity.domain.id.*
import cc.monomer.identity.domain.model.*
import cc.monomer.identity.domain.port.AuditLogRepository
import cc.monomer.identity.outbound.postgres.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

class PostgresWorkspaceAuthorizationBootstrapIntegrationTest {
    private val jdbc get() = NamedParameterJdbcTemplate(TargetIdentityPostgres.dataSource)
    private val now = Instant.parse("2026-09-14T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private lateinit var transactions: SpringTransactionRunner
    private lateinit var organizationRepository: PostgresOrganizationRepository
    private lateinit var users: PostgresUserRepository
    private lateinit var createOrganizationService: CreateOrganizationService
    private lateinit var workspaces: CreateWorkspaceService
    private lateinit var login: LoginService

    @BeforeEach
    fun setup() {
        TargetIdentityPostgres.dataSource.connection.use {
            TargetIdentityPostgres.applyCanonicalSchemaTwice(it, javaClass.classLoader)
            AuthSchemaVerifier.verifyContractFacts(it)
        }
        val manager = DataSourceTransactionManager(TargetIdentityPostgres.dataSource)
        transactions = SpringTransactionRunner(
            TransactionTemplate(manager),
            TransactionTemplate(manager).apply { isReadOnly = true }, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        organizationRepository = PostgresOrganizationRepository(jdbc)
        users = PostgresUserRepository(jdbc)
        val workspaceRepository = PostgresWorkspaceRepository(jdbc)
        val bootstrap = WorkspaceAuthorizationBootstrap(
            PostgresWorkspaceMemberRepository(jdbc),
        )
        val audit = PostgresAuditLogRepository(jdbc)
        val auditFactory = AuditEventFactory(clock)
        createOrganizationService = CreateOrganizationService(
            transactions, organizationRepository, workspaceRepository, bootstrap, audit, auditFactory, clock,
        )
        workspaces = CreateWorkspaceService(
            transactions, AdminAuthorization(organizationRepository),
            workspaceRepository, organizationRepository, bootstrap, audit, auditFactory, clock,
        )
        login = LoginService(
            transactions,
            LoginIdentityResolver(
                NormalizedEmailUserResolver(users), users, PostgresLoginIdentityRepository(jdbc), clock,
            ),
            organizationRepository, workspaceRepository, bootstrap, audit, auditFactory, clock,
            DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.OPEN, emptySet()),
        )
    }

    @Test
    fun `workspace creation persists the nominated owner bundle and actor attribution`() = runBlocking<Unit> {
        val actor = user()
        val owner = user()
        val organization = organization(actor, owner)
        val workspace = workspaces.create(CreateWorkspaceUseCase.Command(actor, owner, organization, "New Workspace"))

        assertInitialBundle(workspace.id, owner, actor)
    }

    @Test
    fun `organization creation persists the nominated owner bundle and actor attribution`() = runBlocking<Unit> {
        val actor = user()
        val owner = user()
        val result = createOrganizationService.create(CreateOrganizationUseCase.Command(actor, owner, "New Organization"))

        assertInitialBundle(result.defaultWorkspace.id, owner, actor)
    }

    @Test
    fun `first login persists the initial user authorization bundle`() = runBlocking<Unit> {
        val result = login.login(assertion(), LoginUseCase.FirstSignIn.CREATE_PERSONAL_ORGANIZATION)

        assertThat(result.newUser).isTrue()
        val workspace = initialWorkspace(result.user.userId)
        assertInitialBundle(workspace.id, result.user.userId, result.user.userId)
        assertInitialCreationAudit(workspace, result.user.userId)
    }

    @Test
    fun `existing login preserves private page settings`() = runBlocking<Unit> {
        val assertion = assertion()
        val first = login.login(assertion, LoginUseCase.FirstSignIn.CREATE_PERSONAL_ORGANIZATION)
        val workspaceId = initialWorkspace(first.user.userId).id
        transactions.run {
            jdbc.update(
                "UPDATE identity_runtime.workspaces SET public_pages = true WHERE id = :workspace",
                mapOf("workspace" to workspaceId.value.toJavaUuid()),
            )
        }
        val before = persistedRows()

        val repeated = login.login(assertion, LoginUseCase.FirstSignIn.CREATE_PERSONAL_ORGANIZATION)

        assertThat(repeated.newUser).isFalse()
        assertThat(initialWorkspace(repeated.user.userId).id).isEqualTo(initialWorkspace(first.user.userId).id)
        assertThat(persistedRows()).isEqualTo(before)
        assertThat(initialWorkspace(repeated.user.userId).publicPages).isTrue()
    }

    @Test
    fun `owner role initialization failure rolls back workspace and the complete owner bundle`() = runBlocking<Unit> {
        val actor = user()
        val owner = user()
        val organization = organization(actor, owner)

        assertCreationRollsBack {
            workspaces.create(CreateWorkspaceUseCase.Command(actor, owner, organization, "Failed Workspace"))
        }
    }

    @Test
    fun `owner role initialization failure rolls back organization memberships and workspace bundle`() = runBlocking<Unit> {
        val actor = user()
        val owner = user()

        assertCreationRollsBack {
            createOrganizationService.create(CreateOrganizationUseCase.Command(actor, owner, "Failed Organization"))
        }
    }

    @Test
    fun `owner role initialization failure rolls back new user identity organization and workspace bundle`() = runBlocking<Unit> {
        assertCreationRollsBack { login.login(assertion(), LoginUseCase.FirstSignIn.CREATE_PERSONAL_ORGANIZATION) }
    }

    @Test
    fun `signup audit failure rolls back new user identity organization workspace and preceding audit`() = runBlocking<Unit> {
        val audit = PostgresAuditLogRepository(jdbc)
        var organizationAuditInserted = false
        val failingAudit = object : AuditLogRepository {
            override fun insert(entry: AuditLogEntry) {
                if (entry.action == "workspace.create") throw IllegalStateException("workspace audit insert failed")
                audit.insert(entry)
                organizationAuditInserted = true
            }
        }
        val failingLogin = LoginService(
            transactions,
            LoginIdentityResolver(
                NormalizedEmailUserResolver(users), users, PostgresLoginIdentityRepository(jdbc), clock,
            ),
            organizationRepository, PostgresWorkspaceRepository(jdbc),
            WorkspaceAuthorizationBootstrap(PostgresWorkspaceMemberRepository(jdbc)), failingAudit, AuditEventFactory(clock), clock,
            DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.OPEN, emptySet()),
        )
        val before = persistedRows()

        val failure = runCatching { failingLogin.login(assertion(), LoginUseCase.FirstSignIn.CREATE_PERSONAL_ORGANIZATION) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java).hasMessage("workspace audit insert failed")
        assertThat(organizationAuditInserted).isTrue()
        assertThat(persistedRows()).isEqualTo(before)
    }

    @Test
    fun `public pages defaults private and owner changes are persisted audited with private workspace reader name`() = runBlocking<Unit> {
        val owner = user()
        val member = user()
        val organizationId = organization(owner, member)
        val workspace = workspaces.create(CreateWorkspaceUseCase.Command(owner, owner, organizationId, "Public pages"))
        transactions.run {
            jdbc.update("INSERT INTO identity_runtime.workspace_members(workspace_id,user_id, role) VALUES (:workspace,:member, 'EXPLORER')",
                mapOf("workspace" to workspace.id.value.toJavaUuid(), "member" to member.value.toJavaUuid()))
        }
        val query = PostgresWorkspaceQuery(jdbc, transactions)
        val publicQuery = PostgresWorkspacePublicPagesQuery(jdbc, transactions)
        val publicWorkspace = cc.monomer.identity.api.WorkspaceId(workspace.id.value)
        val repository = PostgresWorkspaceRepository(jdbc)
        val runner = cc.monomer.identity.application.workspace.OwnerWorkspaceMutationRunner(
            transactions, PostgresAuthorizationAssignmentRepository(jdbc),
        )
        val setter = cc.monomer.identity.application.workspace.SetWorkspacePublicPagesService(
            runner, query, repository, PostgresAuditLogRepository(jdbc), AuditEventFactory(clock),
        )
        fun command(actor: UserId, enabled: Boolean) = cc.monomer.identity.application.workspace.SetWorkspacePublicPagesUseCase.Command(actor, workspace.id, enabled)
        fun auditCount() = jdbc.queryForObject(
            "SELECT count(*) FROM identity_runtime.audit_log WHERE workspace_id = :workspace AND action = 'workspace.public_pages.set'",
            mapOf("workspace" to workspace.id.value.toJavaUuid()), Int::class.java,
        )!!
        assertThat(workspace.publicPages).isFalse()
        assertThat(publicQuery.isPublic(publicWorkspace)).isFalse()
        assertThat(publicQuery.pageReaderName(publicWorkspace)).isEqualTo("Public pages")
        assertThat(publicQuery.isPublic(cc.monomer.identity.api.WorkspaceId.generate())).isFalse()
        val initialRevision = revision(workspace.id)
        assertThat(runCatching { setter.set(command(member, true)) }.exceptionOrNull())
            .isInstanceOf(cc.monomer.identity.application.AccessDeniedException::class.java)
        assertThat(auditCount()).isZero()
        assertThat(revision(workspace.id)).isEqualTo(initialRevision)
        assertThat(setter.set(command(owner, true)).publicPages).isTrue()
        assertThat(query.find(workspace.id)!!.publicPages).isTrue()
        assertThat(publicQuery.isPublic(publicWorkspace)).isTrue()
        assertThat(auditCount()).isEqualTo(1)
        assertThat(revision(workspace.id)).isEqualTo(initialRevision + 1)
        setter.set(command(owner, true))
        assertThat(auditCount()).isEqualTo(1)
        assertThat(revision(workspace.id)).isEqualTo(initialRevision + 1)
        val failingAudit = io.mockk.mockk<cc.monomer.identity.domain.port.AuditLogRepository>()
        io.mockk.every { failingAudit.insert(any()) } throws IllegalStateException("audit insert failed")
        val failingSetter = cc.monomer.identity.application.workspace.SetWorkspacePublicPagesService(
            runner, query, repository, failingAudit, AuditEventFactory(clock),
        )
        assertThat(runCatching { failingSetter.set(command(owner, false)) }.isFailure).isTrue()
        assertThat(publicQuery.isPublic(publicWorkspace)).isTrue()
        assertThat(revision(workspace.id)).isEqualTo(initialRevision + 1)
        setter.set(command(owner, false))
        assertThat(publicQuery.isPublic(publicWorkspace)).isFalse()
        assertThat(publicQuery.pageReaderName(publicWorkspace)).isEqualTo("Public pages")
        assertThat(auditCount()).isEqualTo(2)
    }

    @Test
    fun `initial organization supports invitations and additional members`() = runBlocking<Unit> {
        val owner = login.login(assertion(), LoginUseCase.FirstSignIn.CREATE_PERSONAL_ORGANIZATION).user.userId
        val workspace = initialWorkspace(owner)
        val organizationQuery = PostgresOrganizationQuery(jdbc, transactions)
        val admin = AdminAuthorization(organizationRepository)
        val audit = PostgresAuditLogRepository(jdbc)
        val auditFactory = AuditEventFactory(clock)
        val invitations = cc.monomer.identity.application.organization.OrganizationInvitationService(
            transactions, PostgresOrganizationInvitationRepository(jdbc), organizationRepository, PostgresWorkspaceMemberRepository(jdbc), admin, audit, auditFactory,
            object : cc.monomer.identity.domain.port.InvitationDelivery {
                override suspend fun send(deliveryEmail: String, organizationDisplayName: String, invitedBy: User?, expiresAt: Instant, rawToken: String) = true
            }, clock,
        )
        val invitation = invitations.create(owner, workspace.organizationId, "${Uuid.generateV7()}@example.test", emptyList(), null)
        assertThat(invitation.outcome).isEqualTo(cc.monomer.identity.application.organization.OrganizationInvitationService.Outcome.SENT)
        val member = user()
        transactions.run {
            organizationRepository.insertMember(OrganizationMembership(workspace.organizationId, member, OrganizationRole.MEMBER, now))
        }
        assertThat(organizationQuery.findMembership(workspace.organizationId, member)?.role).isEqualTo(OrganizationRole.MEMBER)
        assertThat(PostgresWorkspaceQuery(jdbc, transactions).listAccessibleByUser(member)).isEmpty()
    }

    @Test
    fun `accessible workspaces carry the direct workspace role and omit admin-only reach`() = runBlocking<Unit> {
        val owner = login.login(assertion(), LoginUseCase.FirstSignIn.CREATE_PERSONAL_ORGANIZATION).user.userId
        val workspace = initialWorkspace(owner)
        val admin = user()
        val viewerAdmin = user()
        transactions.run {
            organizationRepository.insertMember(OrganizationMembership(workspace.organizationId, admin, OrganizationRole.ADMIN, now))
            organizationRepository.insertMember(OrganizationMembership(workspace.organizationId, viewerAdmin, OrganizationRole.ADMIN, now))
            PostgresWorkspaceMemberRepository(jdbc).insert(
                cc.monomer.identity.domain.model.WorkspaceMembership(workspace.id, viewerAdmin, owner, now, cc.monomer.identity.api.WorkspaceRole.VIEWER),
            )
        }
        val query = PostgresWorkspaceQuery(jdbc, transactions)

        assertThat(query.listAccessibleByUser(admin)).isEmpty()
        val viewerAccess = query.listAccessibleByUser(viewerAdmin).single()
        assertThat(viewerAccess.workspace.id).isEqualTo(workspace.id)
        assertThat(viewerAccess.role).isEqualTo(cc.monomer.identity.api.WorkspaceRole.VIEWER)
    }

    @Test
    fun `concurrent first callbacks bootstrap exactly one organization and workspace`() = runBlocking<Unit> {
        val assertion = assertion()
        val before = persistedRows()
        val results = raceLogins(assertion)
        assertThat(results.count { it.newUser }).isEqualTo(1)
        assertThat(results.map { it.user.userId }.distinct()).hasSize(1)
        assertThat(results.map { it.loginIdentity.id }.distinct()).hasSize(1)
        val after = persistedRows()
        listOf("principals", "users", "login_identities", "organizations", "organization_members", "workspaces",
            "workspace_members").forEach { table ->
            assertThat(after.getValue(table) - before.getValue(table)).describedAs(table).isEqualTo(1)
        }
        val result = results.first()
        val workspace = initialWorkspace(result.user.userId)
        assertInitialBundle(workspace.id, result.user.userId, result.user.userId)
        assertInitialCreationAudit(workspace, result.user.userId)
        assertThat(after.getValue("audit_log") - before.getValue("audit_log")).isEqualTo(2)
    }

    @Test
    fun `returning identity without memberships never creates an organization`() = runBlocking<Unit> {
        val assertion = assertion()
        val user = user()
        transactions.run {
            check(PostgresLoginIdentityRepository(jdbc).insertIfAbsent(LoginIdentity(
                LoginIdentityId.generate(), user, assertion.identityKey, LoginIdentityLinkedVia.SIGNUP, now,
            )))
        }
        val before = persistedRows()
        repeat(2) {
            val result = login.login(assertion, LoginUseCase.FirstSignIn.CREATE_PERSONAL_ORGANIZATION)
            assertThat(result.user.userId).isEqualTo(user)
            assertThat(result.newUser).isFalse()
        }
        assertThat(persistedRows()).isEqualTo(before)
        assertThat(PostgresOrganizationQuery(jdbc, transactions).listMembershipsByUser(user)).isEmpty()
    }

    @Test
    fun `initial organization counts toward the fifth free admin organization under competing creations`() = runBlocking<Unit> {
        val user = login.login(assertion(), LoginUseCase.FirstSignIn.CREATE_PERSONAL_ORGANIZATION).user.userId
        repeat(3) { createOrganizationService.create(CreateOrganizationUseCase.Command(user, user, "Additional Organization")) }
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val ready = java.util.concurrent.CountDownLatch(2)
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            val futures = (1..2).map { index -> executor.submit<Result<Any>> {
                ready.countDown()
                check(start.await(10, java.util.concurrent.TimeUnit.SECONDS))
                runCatching { runBlocking { createOrganizationService.create(CreateOrganizationUseCase.Command(user, user, "Competing $index")) } }
            } }
            check(ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }
            assertThat(results.count { it.isSuccess }).isEqualTo(1)
            assertThat(results.single { it.isFailure }.exceptionOrNull())
                .isInstanceOf(cc.monomer.identity.domain.port.FreeOrganizationAdminLimitException::class.java)
            assertThat(PostgresOrganizationQuery(jdbc, transactions).listMembershipsByUser(user)).hasSize(5)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun raceLogins(assertion: AuthenticationAssertion): List<cc.monomer.identity.application.login.LoginUseCase.Response> {
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val ready = java.util.concurrent.CountDownLatch(2)
        val start = java.util.concurrent.CountDownLatch(1)
        return try {
            val futures = (1..2).map { executor.submit<cc.monomer.identity.application.login.LoginUseCase.Response> {
                ready.countDown()
                check(start.await(10, java.util.concurrent.TimeUnit.SECONDS))
                runBlocking { login.login(assertion, LoginUseCase.FirstSignIn.CREATE_PERSONAL_ORGANIZATION) }
            } }
            check(ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
            start.countDown()
            futures.map { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private suspend fun initialWorkspace(userId: UserId): Workspace {
        val accessible = PostgresWorkspaceQuery(jdbc, transactions).listAccessibleByUser(userId).single()
        assertThat(accessible.role).isEqualTo(cc.monomer.identity.api.WorkspaceRole.OWNER)
        return accessible.workspace
    }

    private fun revision(workspace: WorkspaceId): Long = jdbc.queryForObject(
        "SELECT authorization_revision FROM identity_runtime.workspaces WHERE id = :id",
        mapOf("id" to workspace.value.toJavaUuid()), Long::class.java,
    )!!

    private fun assertInitialBundle(workspace: WorkspaceId, owner: UserId, actor: UserId) {
        val parameters = mapOf("workspace" to workspace.value.toJavaUuid())
        assertThat(jdbc.queryForObject("SELECT public_pages FROM identity_runtime.workspaces WHERE id = :workspace", parameters, Boolean::class.java)).isFalse()
        assertThat(jdbc.queryForList(
            "SELECT user_id FROM identity_runtime.workspace_members WHERE workspace_id = :workspace", parameters,
        ).map { it["user_id"] }).containsExactly(owner.value.toJavaUuid())
        assertThat(jdbc.queryForList(
            "SELECT user_id FROM identity_runtime.workspace_members WHERE workspace_id = :workspace AND role = 'OWNER'", parameters,
        ).map { it["user_id"] }).containsExactly(owner.value.toJavaUuid())
    }

    private fun assertInitialCreationAudit(workspace: Workspace, actor: UserId) {
        val events = jdbc.queryForList(
            """SELECT actor_id, action, organization_id, workspace_id, resource_type, resource_id
               FROM identity_runtime.audit_log WHERE organization_id = :organization ORDER BY action""",
            mapOf("organization" to workspace.organizationId.value.toJavaUuid()),
        )
        assertThat(events).containsExactly(
            mapOf(
                "actor_id" to actor.value.toJavaUuid(), "action" to "organization.create",
                "organization_id" to workspace.organizationId.value.toJavaUuid(), "workspace_id" to null,
                "resource_type" to "organization", "resource_id" to workspace.organizationId.value.toJavaUuid(),
            ),
            mapOf(
                "actor_id" to actor.value.toJavaUuid(), "action" to "workspace.create",
                "organization_id" to workspace.organizationId.value.toJavaUuid(), "workspace_id" to workspace.id.value.toJavaUuid(),
                "resource_type" to "workspace", "resource_id" to workspace.id.value.toJavaUuid(),
            ),
        )
    }

    private suspend fun assertCreationRollsBack(create: suspend () -> Any) {
        val before = persistedRows()
        jdbc.jdbcTemplate.execute(
            "ALTER TABLE identity_runtime.workspace_members ADD CONSTRAINT test_reject_initial_owner_role CHECK (false) NOT VALID",
        )
        try {
            val failure = runCatching { create() }.exceptionOrNull()
            assertThat(failure).isInstanceOf(DataIntegrityViolationException::class.java)
            assertThat(failure).hasStackTraceContaining("test_reject_initial_owner_role")
            assertThat(persistedRows()).isEqualTo(before)
        } finally {
            jdbc.jdbcTemplate.execute(
                "ALTER TABLE identity_runtime.workspace_members DROP CONSTRAINT test_reject_initial_owner_role",
            )
        }
    }

    private fun persistedRows(): Map<String, Int> = listOf(
        "principals", "users", "login_identities", "organizations", "organization_members", "workspaces",
        "workspace_members",
        "audit_log",
    ).associateWith { table ->
        jdbc.jdbcTemplate.queryForObject("SELECT count(*) FROM identity_runtime.$table", Int::class.java)!!
    }

    private fun workspaceCount(table: String, workspace: WorkspaceId): Int = jdbc.queryForObject(
        "SELECT count(*) FROM identity_runtime.$table WHERE workspace_id = :workspace",
        mapOf("workspace" to workspace.value.toJavaUuid()), Int::class.java,
    )!!

    private suspend fun user(): UserId = transactions.run {
        val user = User(UserId.generate(), "${Uuid.generateV7()}@example.test", "Test User")
        check(users.insertIfPrimaryEmailAbsent(user))
        user.userId
    }

    private suspend fun organization(actor: UserId, owner: UserId): OrganizationId = transactions.run {
        val organization = OrganizationId.generate()
        organizationRepository.insert(Organization(organization, "Existing Organization", null, OrganizationPlan.FREE, now))
        organizationRepository.insertMember(OrganizationMembership(organization, actor, OrganizationRole.ADMIN, now))
        organizationRepository.insertMember(OrganizationMembership(organization, owner, OrganizationRole.MEMBER, now))
        organization
    }

    private fun assertion(): AuthenticationAssertion {
        val subject = Uuid.generateV7().toString()
        return AuthenticationAssertion(
            LoginIdentityKey(AuthenticationProtocol.OIDC, "https://accounts.google.com", subject),
            "builtin/google", "https://accounts.google.com", "$subject@example.test", true,
            EmailEvidence.AUTHORITATIVE, "New User", null, null, emptyList(), null,
        )
    }

    companion object {
        @JvmField
        @RegisterExtension
        internal val postgres = TargetIdentityPostgres
    }
}
