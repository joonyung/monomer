package cc.monomer.identity

import cc.monomer.identity.api.SampleWorkspace
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.api.WorkspaceSampleState
import cc.monomer.identity.application.AdminAuthorization
import cc.monomer.identity.application.OrganizationAdminRequiredException
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import kotlin.uuid.toJavaUuid

class PostgresSampleWorkspaceIntegrationTest {
    private val jdbc get() = NamedParameterJdbcTemplate(TargetIdentityPostgres.dataSource)
    private val now = Instant.parse("2026-10-12T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private lateinit var transactions: SpringTransactionRunner
    private lateinit var organizations: PostgresOrganizationRepository
    private lateinit var workspaceRepository: PostgresWorkspaceRepository
    private lateinit var members: PostgresWorkspaceMemberRepository
    private lateinit var assignments: PostgresAuthorizationAssignmentRepository
    private lateinit var audit: PostgresAuditLogRepository
    private lateinit var service: SampleWorkspaceService
    private lateinit var ordinaryWorkspaces: CreateWorkspaceService

    @BeforeEach
    fun setup() {
        TargetIdentityPostgres.dataSource.connection.use { TargetIdentityPostgres.applyCanonicalSchemaTwice(it, javaClass.classLoader) }
        val manager = DataSourceTransactionManager(TargetIdentityPostgres.dataSource)
        transactions = SpringTransactionRunner(TransactionTemplate(manager), TransactionTemplate(manager).apply { isReadOnly = true }, Dispatchers.IO)
        organizations = PostgresOrganizationRepository(jdbc)
        workspaceRepository = PostgresWorkspaceRepository(jdbc)
        members = PostgresWorkspaceMemberRepository(jdbc)
        assignments = PostgresAuthorizationAssignmentRepository(jdbc)
        audit = PostgresAuditLogRepository(jdbc)
        service = newService(audit)
        ordinaryWorkspaces = CreateWorkspaceService(transactions, AdminAuthorization(organizations), workspaceRepository, organizations,
            WorkspaceAuthorizationBootstrap(members), audit, AuditEventFactory(clock), clock)
    }

    @Test
    fun `canonical marker fingerprint includes sample columns constraints and unique index`() {
        assertThat(jdbc.queryForObject("SELECT identity_runtime.compute_schema_catalog_fingerprint()", emptyMap<String, Any>(), String::class.java))
            .isEqualTo(AuthSchemaContract.SCHEMA_CATALOG_FINGERPRINT)
    }

    @Test
    fun `two instances create one atomically marked workspace with one initiator owner`() = runBlocking<Unit> {
        val first = user()
        val second = user()
        val organization = organization(first)
        transactions.run { organizations.insertMember(OrganizationMembership(organization, second, OrganizationRole.ADMIN, now)) }
        val otherService = newService(audit)
        val results = race({ create(first, organization) }, { otherService.create(principal(second), organizationApi(organization), "acme-cloud@1") })
        assertThat(results).allMatch { it.isSuccess }
        val samples = results.map { it.getOrThrow() as SampleWorkspace }
        assertThat(samples.map { it.workspaceId }.distinct()).hasSize(1)
        assertThat(samples.map { it.sample.initiator }.distinct()).hasSize(1)
        assertThat(samples.first().sample.state).isEqualTo(WorkspaceSampleState.PROVISIONING)
        assertThat(workspaceCount(organization)).isEqualTo(1)
        val workspace = samples.first().workspaceId
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_runtime.workspace_members WHERE workspace_id=:id AND role='OWNER'",
            mapOf("id" to workspace.value.toJavaUuid()), Int::class.java)).isEqualTo(1)
        assertThat(service.findByOrganization(principal(first), organizationApi(organization))).isEqualTo(samples.first())
        val evidence = service.readInitiatorEvidence(workspace, "acme-cloud@1")!!
        assertThat(evidence.actor.id).isEqualTo(samples.first().sample.initiator)
        assertThat(evidence.role).isEqualTo(WorkspaceRole.OWNER)
        assertThat(evidence.organizationId).isEqualTo(organizationApi(organization))
        assertThat(evidence.authorizationRevision.value).isGreaterThan(0)
    }

    @Test
    fun `failure after workspace and owner insertion rolls back marker owner and audit`() = runBlocking<Unit> {
        val actor = user()
        val organization = organization(actor)
        val failingAudit = object : AuditLogRepository by audit {
            override fun insert(entry: AuditLogEntry) { throw IllegalStateException("Injected audit failure") }
        }
        val failed = runCatching { newService(failingAudit).create(principal(actor), organizationApi(organization), "acme-cloud@1") }
        assertThat(failed.exceptionOrNull()).isNotNull()
        assertThat(workspaceCount(organization)).isZero()
        assertThat(service.findByOrganization(principal(actor), organizationApi(organization))).isNull()
        val resumed = create(actor, organization)
        assertThat(service.readMarker(resumed.workspaceId)).isEqualTo(resumed.sample)
        assertThat(workspaceCount(organization)).isEqualTo(1)
    }

    @Test
    fun `sample consumes fifth workspace slot but replay does not consume another`() = runBlocking<Unit> {
        val actor = user()
        val organization = organization(actor)
        repeat(4) { ordinaryWorkspaces.create(CreateWorkspaceUseCase.Command(actor, actor, organization, "Workspace $it")) }
        val sample = create(actor, organization)
        assertThat(workspaceCount(organization)).isEqualTo(5)
        assertThat(create(actor, organization)).isEqualTo(sample)
        assertThat(runCatching { ordinaryWorkspaces.create(CreateWorkspaceUseCase.Command(actor, actor, organization, "Sixth")) }.exceptionOrNull())
            .isInstanceOf(FreeWorkspaceLimitException::class.java)
        val fullActor = user()
        val fullOrganization = organization(fullActor)
        repeat(5) { ordinaryWorkspaces.create(CreateWorkspaceUseCase.Command(fullActor, fullActor, fullOrganization, "Workspace $it")) }
        assertThat(runCatching { create(fullActor, fullOrganization) }.exceptionOrNull()).isInstanceOf(cc.monomer.identity.api.SampleWorkspaceLimitExceededException::class.java)
        assertThat(service.findByOrganization(principal(fullActor), organizationApi(fullOrganization))).isNull()
    }

    @Test
    fun `sample and ordinary creation compete for last slot under same organization lock`() = runBlocking<Unit> {
        val actor = user()
        val organization = organization(actor)
        repeat(4) { ordinaryWorkspaces.create(CreateWorkspaceUseCase.Command(actor, actor, organization, "Workspace $it")) }
        val results = race({ create(actor, organization) }, { ordinaryWorkspaces.create(CreateWorkspaceUseCase.Command(actor, actor, organization, "Ordinary")) })
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(results.single { it.isFailure }.exceptionOrNull()).matches { it is FreeWorkspaceLimitException || it is cc.monomer.identity.api.SampleWorkspaceLimitExceededException }
        assertThat(workspaceCount(organization)).isEqualTo(5)
    }

    @Test
    fun `another admin becomes owner with ordinary audit and revoked initiator cannot provision`() = runBlocking<Unit> {
        val actor = user()
        val second = user()
        val plainMember = user()
        val foreignAdmin = user()
        val organization = organization(actor)
        organization(foreignAdmin)
        transactions.run {
            organizations.insertMember(OrganizationMembership(organization, second, OrganizationRole.ADMIN, now))
            organizations.insertMember(OrganizationMembership(organization, plainMember, OrganizationRole.MEMBER, now))
        }
        val sample = create(actor, organization)
        for (unauthorized in listOf(plainMember, foreignAdmin)) {
            assertThat(runCatching { service.addAdminOwner(principal(unauthorized), sample.workspaceId) }.exceptionOrNull())
                .isInstanceOf(cc.monomer.identity.api.SampleWorkspaceAdminRequiredException::class.java)
            assertThat(runCatching { create(unauthorized, organization) }.exceptionOrNull()).isInstanceOf(cc.monomer.identity.api.SampleWorkspaceAdminRequiredException::class.java)
            assertThat(runCatching { service.findByOrganization(principal(unauthorized), organizationApi(organization)) }.exceptionOrNull()).isInstanceOf(cc.monomer.identity.api.SampleWorkspaceAdminRequiredException::class.java)
        }
        service.addAdminOwner(principal(second), sample.workspaceId)
        service.addAdminOwner(principal(second), sample.workspaceId)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_runtime.workspace_members WHERE workspace_id=:id AND role='OWNER'",
            mapOf("id" to sample.workspaceId.value.toJavaUuid()), Int::class.java)).isEqualTo(2)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_runtime.audit_log WHERE workspace_id=:id AND action='workspace.member.add'",
            mapOf("id" to sample.workspaceId.value.toJavaUuid()), Int::class.java)).isEqualTo(1)
        transactions.run { assignments.setMemberRole(WorkspaceId(sample.workspaceId.value), actor, WorkspaceRole.EDITOR) }
        assertThat(service.readInitiatorEvidence(sample.workspaceId, "acme-cloud@1")).isNull()
        assertThat(service.readMarker(sample.workspaceId)!!.initiator).isEqualTo(principal(actor))
    }

    @Test
    fun `ready is idempotent and marker cannot be partially written changed or regressed`() = runBlocking<Unit> {
        val actor = user()
        val organization = organization(actor)
        val sample = create(actor, organization)
        service.markReady(sample.workspaceId, "acme-cloud@1")
        service.markReady(sample.workspaceId, "acme-cloud@1")
        assertThat(service.readMarker(sample.workspaceId)!!.state).isEqualTo(WorkspaceSampleState.READY)
        assertThat(service.readInitiatorEvidence(sample.workspaceId, "acme-cloud@1")).isNull()
        assertThat(runCatching { service.markReady(sample.workspaceId, "unknown@1") }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        for (change in listOf("sample_state='PROVISIONING'", "sample_dataset=NULL", "sample_initiator=NULL")) {
            assertThat(runCatching { jdbc.update("UPDATE identity_runtime.workspaces SET $change WHERE id=:id", mapOf("id" to sample.workspaceId.value.toJavaUuid())) }.exceptionOrNull()).isNotNull()
        }
        assertThat(runCatching { create(actor, organization, "unknown@1") }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        transactions.run {
            assertThat(runCatching { workspaceRepository.insert(Workspace(WorkspaceId.generate(), organization, "Duplicate sample", now, false, ReportingTimeZone.UTC, sample.sample)) }.exceptionOrNull()).isNotNull()
        }
    }

    @Test
    fun `v2 request resumes existing v1 and ready return preserves dataset and actor`() = runBlocking<Unit> {
        val actor = user()
        val organization = organization(actor)
        val original = create(actor, organization, "acme-cloud@1")
        assertThat(create(actor, organization, "acme-cloud@2")).isEqualTo(original)
        service.markReady(original.workspaceId, original.sample.dataset)
        val ready = create(actor, organization, "acme-cloud@2")
        assertThat(ready.sample.dataset).isEqualTo("acme-cloud@1")
        assertThat(ready.sample.initiator).isEqualTo(original.sample.initiator)
        assertThat(ready.sample.state).isEqualTo(WorkspaceSampleState.READY)
        assertThat(workspaceCount(organization)).isEqualTo(1)
        val otherActor = user()
        val latest = create(otherActor, organization(otherActor), "acme-cloud@2")
        assertThat(latest.sample.dataset).isEqualTo("acme-cloud@2")
        assertThat(service.readInitiatorEvidence(latest.workspaceId, "acme-cloud@2")).isNotNull()
        service.markReady(latest.workspaceId, "acme-cloud@2")
        assertThat(service.readMarker(latest.workspaceId)!!.state).isEqualTo(WorkspaceSampleState.READY)
        assertThat(runCatching { create(actor, organization, "acme-cloud@3") }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { jdbc.update("UPDATE identity_runtime.workspaces SET sample_dataset='acme-cloud@2' WHERE id=:id", mapOf("id" to original.workspaceId.value.toJavaUuid())) }.exceptionOrNull()).isNotNull()
    }

    private fun newService(auditRepository: AuditLogRepository) = SampleWorkspaceService(transactions, AdminAuthorization(organizations), workspaceRepository,
        workspaceRepository, WorkspaceAuthorizationBootstrap(members), members, assignments, auditRepository, AuditEventFactory(clock), clock)

    private suspend fun create(actor: UserId, organization: OrganizationId, dataset: String = "acme-cloud@1") =
        service.create(principal(actor), organizationApi(organization), dataset)
    private fun principal(user: UserId) = cc.monomer.identity.api.PrincipalId(user.value)
    private fun organizationApi(organization: OrganizationId) = cc.monomer.identity.api.OrganizationId(organization.value)
    private fun workspaceCount(organization: OrganizationId) = jdbc.queryForObject("SELECT count(*) FROM identity_runtime.workspaces WHERE organization_id=:id",
        mapOf("id" to organization.value.toJavaUuid()), Int::class.java)!!
    private fun user(): UserId {
        val user = UserId.generate()
        val parameters = mapOf("id" to user.value.toJavaUuid(), "email" to "$user@example.test", "now" to Timestamp.from(now))
        jdbc.update("INSERT INTO identity_runtime.principals(id,kind,display_name,created_at) VALUES(:id,'user','Test User',:now)", parameters)
        jdbc.update("INSERT INTO identity_runtime.users(id,primary_email,created_at) VALUES(:id,:email,:now)", parameters)
        return user
    }
    private suspend fun organization(admin: UserId): OrganizationId = transactions.run {
        val organization = OrganizationId.generate()
        organizations.insert(Organization(organization, "Test Organization", null, cc.monomer.identity.api.OrganizationPlan.FREE, now))
        organizations.insertMember(OrganizationMembership(organization, admin, OrganizationRole.ADMIN, now))
        organization
    }
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
