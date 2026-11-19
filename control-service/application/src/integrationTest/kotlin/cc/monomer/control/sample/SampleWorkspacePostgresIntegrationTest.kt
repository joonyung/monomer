@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.sample

import cc.monomer.control.dashboard.DashboardApplicationComposition
import cc.monomer.control.jdbc.JdbcVirtualThreadDispatcher
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardCommandReceiptRepository
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardRepository
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardSearchFields
import cc.monomer.dashboard.api.DashboardSearchProjection
import cc.monomer.dashboard.api.DashboardSeedDefinitionUseCase
import cc.monomer.dashboard.application.DashboardResult
import cc.monomer.dashboard.application.DashboardSeedDefinitionService
import cc.monomer.dashboard.application.DashboardUseCase
import cc.monomer.identity.api.AgentClientId
import cc.monomer.identity.api.AuthorizationRevision
import cc.monomer.identity.api.AuthorizedMutationContext
import cc.monomer.identity.api.CanonicalPrincipal
import cc.monomer.identity.api.CredentialKind
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.MutationIntent
import cc.monomer.identity.api.PrincipalKind
import cc.monomer.identity.api.SampleWorkspaceProvisioningUseCase
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.api.WorkspaceSampleState
import cc.monomer.semantic.adapter.outbound.postgres.PostgresSemanticRepository
import cc.monomer.semantic.adapter.outbound.postgres.SemanticTransactionTimeouts
import cc.monomer.semantic.adapter.outbound.postgres.SpringSemanticTransactionPort
import cc.monomer.semantic.api.MetricView
import cc.monomer.semantic.api.PutMetricCommand
import cc.monomer.semantic.api.PutSemanticModelCommand
import cc.monomer.semantic.api.SemanticCurrentReadUseCase
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticMetricDefinition
import cc.monomer.semantic.api.SemanticModelView
import cc.monomer.semantic.api.SemanticMutationResult
import cc.monomer.semantic.api.SemanticSeedDefinitionUseCase
import cc.monomer.semantic.application.SemanticAuthorityService
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Duration
import cc.monomer.identity.application.AdminAuthorization
import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.application.workspace.SampleWorkspaceService
import cc.monomer.identity.application.workspace.WorkspaceAuthorizationBootstrap
import cc.monomer.identity.domain.id.*
import cc.monomer.identity.domain.model.Organization
import cc.monomer.identity.domain.model.OrganizationMembership
import cc.monomer.identity.domain.model.OrganizationRole
import cc.monomer.identity.outbound.postgres.*
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.uuid.toJavaUuid
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

class SampleWorkspacePostgresIntegrationTest {
    private fun postgres() = PostgreSQLContainer(DockerImageName.parse(
        "postgres:17.10-alpine3.24@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("sample_workspace_gate_ephemeral").withUsername("sample_gate").withPassword("sample_gate_test_only")

    @Test
    fun `two coordinators and response loss produce one workspace and one seed set`() = runBlocking<Unit> {
        postgres().use { postgres ->
            postgres.start()
            val dataSource = PGSimpleDataSource().apply { setURL(postgres.jdbcUrl); user = postgres.username; password = postgres.password }
            val owner = identity(dataSource)
            val fixture = SampleProvisioningFixture(owner.api, owner.api.create(owner.actor, owner.organization, "acme-cloud@1").workspaceId)
            val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val first = fixture.coordinator(PostgresSampleWorkspaceLock(dataSource, Dispatchers.IO), firstScope)
                val second = fixture.coordinator(PostgresSampleWorkspaceLock(dataSource, Dispatchers.IO), secondScope)
                val outputs = listOf(async { first.start(owner.actor, owner.organization) }, async { second.start(owner.actor, owner.organization) }).awaitAll()
                assertEquals(outputs.first().workspaceId, outputs.last().workspaceId)
                withTimeout(15000) { while (owner.api.findByOrganization(owner.actor, owner.organization)!!.sample.state != WorkspaceSampleState.READY) delay(25) }
                val reopened = second.start(owner.actor, owner.organization)
                assertEquals(WorkspaceSampleState.READY, reopened.sample.state)
                assertEquals(1, NamedParameterJdbcTemplate(dataSource).queryForObject("SELECT count(*) FROM identity_runtime.workspaces", emptyMap<String,Any>(), Int::class.java))
                assertEquals(14, fixture.metrics.size)
                assertEquals(2, fixture.createdDashboards.size)
                assertTrue(fixture.actors.all { it == owner.actor })
                assertEquals(1, fixture.startedIngests.get())
            } finally { firstScope.cancel(); secondScope.cancel() }
        }
    }

    @Test
    fun `cancelling provisioning releases actual session lock and another coordinator resumes`() = runBlocking<Unit> {
        postgres().use { postgres ->
            postgres.start()
            val dataSource = PGSimpleDataSource().apply { setURL(postgres.jdbcUrl); user = postgres.username; password = postgres.password }
            val owner = identity(dataSource)
            val fixture = SampleProvisioningFixture(owner.api, owner.api.create(owner.actor, owner.organization, "acme-cloud@1").workspaceId)
            fixture.ingestionRunning = true
            val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val first = fixture.coordinator(PostgresSampleWorkspaceLock(dataSource, Dispatchers.IO), firstScope)
                val second = fixture.coordinator(PostgresSampleWorkspaceLock(dataSource, Dispatchers.IO), secondScope)
                val initial = first.start(owner.actor, owner.organization)
                withTimeout(10000) { while (fixture.startedIngests.get() == 0) delay(25) }
                firstScope.coroutineContext[Job]!!.cancelAndJoin()
                assertEquals(WorkspaceSampleState.PROVISIONING, owner.api.findByOrganization(owner.actor, owner.organization)!!.sample.state)
                fixture.ingestionRunning = false
                val resumed = second.start(owner.actor, owner.organization)
                assertEquals(initial.workspaceId, resumed.workspaceId)
                withTimeout(15000) { while (owner.api.findByOrganization(owner.actor, owner.organization)!!.sample.state != WorkspaceSampleState.READY) delay(25) }
                dataSource.connection.use { connection -> connection.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?,0))").use { statement ->
                    statement.setString(1, owner.organization.toString())
                    statement.executeQuery().use { rows -> assertTrue(rows.next() && rows.getBoolean(1)) }
                } }
                assertEquals(14, fixture.metrics.size)
                assertEquals(2, fixture.createdDashboards.size)
            } finally { firstScope.cancel(); secondScope.cancel() }
        }
    }

    @Test
    fun `contended lock cancellation closes its dedicated connection and acquired lock releases after failure`() = runBlocking<Unit> {
        postgres().use { postgres ->
            postgres.start()
            val dataSource = PGSimpleDataSource().apply { setURL(postgres.jdbcUrl); user = postgres.username; password = postgres.password }
            val organization = cc.monomer.identity.api.OrganizationId.generate()
            val lock = PostgresSampleWorkspaceLock(dataSource, Dispatchers.IO)
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first = launch { lock.withLock(organization) { held.complete(Unit); release.await() } }
            held.await()
            val calls = AtomicInteger()
            val waiting = launch { lock.withLock(organization) { calls.incrementAndGet() } }
            delay(200)
            waiting.cancelAndJoin()
            assertEquals(0, calls.get())
            release.complete(Unit); first.join()
            assertFailsWith<IllegalStateException> { lock.withLock(organization) { error("Injected owner failure") } }
            lock.withLock(organization) { calls.incrementAndGet() }
            assertEquals(1, calls.get())
            dataSource.connection.use { connection -> connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM pg_locks WHERE locktype='advisory'").use { rows -> assertTrue(rows.next()); assertEquals(0, rows.getInt(1)) }
            } }
        }
    }

    @Test
    fun `seeded definitions record no attribution while receipts keep the initiator across a resumed provisioning`() = runBlocking<Unit> {
        postgres().use { postgres ->
            postgres.start()
            val dataSource = PGSimpleDataSource().apply { setURL(postgres.jdbcUrl); user = postgres.username; password = postgres.password }
            val owner = identity(dataSource)
            applySchemas(dataSource, "db/semantic/schema.sql", "db/dashboard/schema.sql")
            val sample = owner.api.create(owner.actor, owner.organization, "acme-cloud@3")
            val workspace = sample.workspaceId
            val fixture = SampleProvisioningFixture(owner.api, workspace)
            val owners = SeedOwners(dataSource, fixture)
            var loseModelResponse = true
            var loseDashboardResponse = true
            val semanticSeeds = object : SemanticSeedDefinitionUseCase {
                override suspend fun seedModel(command: PutSemanticModelCommand) = owners.semantic.seedModel(command).also {
                    if (loseModelResponse) { loseModelResponse = false; error("Injected lost Model response") }
                }
                override suspend fun seedMetric(command: PutMetricCommand) = owners.semantic.seedMetric(command)
            }
            val dashboardSeeds = DashboardSeedDefinitionUseCase { command ->
                owners.dashboardSeeds.seed(command).also { if (loseDashboardResponse) { loseDashboardResponse = false; error("Injected lost Dashboard response") } }
            }
            val seed = SampleDefinitionBundle().read("acme-cloud@3", fixture.connectorId, java.time.LocalDate.parse("2026-10-12"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val coordinator = SampleWorkspaceProvisioning(owner.api, fixture.connectors, fixture.catalog, semanticSeeds, owners.read(workspace, seed),
                    fixture.spines, dashboardSeeds, SampleDefinitionBundle(), PostgresSampleWorkspaceLock(dataSource, Dispatchers.IO), scope, fixture.clock)
                withTimeout(30000) {
                    while (owner.api.findByOrganization(owner.actor, owner.organization)!!.sample.state != WorkspaceSampleState.READY) {
                        assertEquals(workspace, coordinator.start(owner.actor, owner.organization).workspaceId)
                        delay(100)
                    }
                }
            } finally { scope.cancel() }
            assertFalse(loseModelResponse)
            assertFalse(loseDashboardResponse)
            assertEquals(3, fixture.startedIngests.get())

            val definitions = seed.models.size + seed.metrics.size
            assertEquals(seed.models.size, count(dataSource, "SELECT count(*) FROM semantic.semantic_models WHERE workspace_id = :workspace", workspace))
            assertEquals(seed.metrics.size, count(dataSource, "SELECT count(*) FROM semantic.metrics WHERE workspace_id = :workspace", workspace))
            assertEquals(seed.dashboards.size, count(dataSource, "SELECT count(*) FROM dashboard.dashboards WHERE workspace_id = :workspace", workspace))
            for (table in listOf("semantic.semantic_models", "semantic.metrics")) {
                assertEquals(0, count(dataSource, "SELECT count(*) FROM $table WHERE workspace_id = :workspace AND (actor_id IS NOT NULL OR credential_surface IS NOT NULL OR agent_client_id IS NOT NULL)", workspace))
            }
            assertEquals(0, count(dataSource, "SELECT count(*) FROM dashboard.dashboards WHERE workspace_id = :workspace AND (updated_by IS NOT NULL OR credential_surface IS NOT NULL OR agent_client_id IS NOT NULL)", workspace))
            assertEquals(definitions, count(dataSource, "SELECT count(*) FROM semantic.definition_changes WHERE workspace_id = :workspace AND action = 'CREATED' AND actor_id IS NULL AND credential_surface IS NULL AND agent_client_id IS NULL", workspace))
            assertEquals(definitions, count(dataSource, "SELECT count(*) FROM semantic.definition_changes WHERE workspace_id = :workspace", workspace))
            assertEquals(seed.dashboards.size, count(dataSource, "SELECT count(*) FROM dashboard.definition_changes WHERE workspace_id = :workspace AND action = 'CREATED' AND actor_id IS NULL AND credential_surface IS NULL AND agent_client_id IS NULL", workspace))
            assertEquals(seed.dashboards.size, count(dataSource, "SELECT count(*) FROM dashboard.definition_changes WHERE workspace_id = :workspace", workspace))
            assertEquals(definitions, count(dataSource, "SELECT count(*) FROM semantic.mutation_receipts WHERE workspace_id = :workspace AND actor_id = :actor AND idempotency_key LIKE 'sample:acme-cloud@3:%'", workspace, owner.actor))
            assertEquals(definitions, count(dataSource, "SELECT count(*) FROM semantic.mutation_receipts WHERE workspace_id = :workspace", workspace))
            assertEquals(seed.dashboards.size, count(dataSource, "SELECT count(*) FROM dashboard.command_receipts WHERE workspace_id = :workspace AND actor_id = :actor::text AND command_kind = 'CREATE_DASHBOARD' AND idempotency_key LIKE 'sample:acme-cloud@3:%'", workspace, owner.actor))
            assertEquals(seed.dashboards.size, count(dataSource, "SELECT count(*) FROM dashboard.command_receipts WHERE workspace_id = :workspace", workspace))

            val initiator = CanonicalPrincipal(owner.actor, PrincipalKind.USER)
            val model = seed.models.first()
            val other = CanonicalPrincipal(cc.monomer.identity.api.PrincipalId.generate(), PrincipalKind.USER)
            assertEquals(SemanticMutationResult.IdempotencyConflict, owners.semantic.seedModel(PutSemanticModelCommand(
                owners.authorization(other, sample, CredentialSurface.WEB, null, MutationIntent.SEMANTIC_MODEL_CREATE), "sample:acme-cloud@3:model:${model.name.value}", null, model, emptyList())))
            val replayed = assertIs<SemanticMutationResult.Committed<*>>(owners.semantic.seedModel(PutSemanticModelCommand(
                owners.authorization(initiator, sample, CredentialSurface.WEB, null, MutationIntent.SEMANTIC_MODEL_CREATE), "sample:acme-cloud@3:model:${model.name.value}", null, model, emptyList())))
            assertNotNull(replayed.value)
            assertEquals(seed.models.size, count(dataSource, "SELECT count(*) FROM semantic.semantic_models WHERE workspace_id = :workspace", workspace))

            val claude = AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata")
            val measured = seed.models.first { it.measures.isNotEmpty() }
            val measure = measured.measures.first().name
            for ((name, surface, agent) in listOf(Triple("web_created_metric", CredentialSurface.WEB, null), Triple("agent_created_metric", CredentialSurface.AGENT, claude))) {
                assertIs<SemanticMutationResult.Committed<MetricView>>(owners.semantic.putMetric(PutMetricCommand(owners.authorization(initiator, sample, surface, agent, MutationIntent.METRIC_CREATE),
                    name, null, SemanticMetricDefinition.Simple(3u, SemanticElementName(name), null, null, null, emptyList(), measured.name, measure, display = null))))
                assertEquals(1, count(dataSource, "SELECT count(*) FROM semantic.metrics WHERE workspace_id = :workspace AND definition ->> 'name' = '$name' AND actor_id = :actor AND credential_surface = '${surface.name}' AND agent_client_id IS NOT DISTINCT FROM ${agent?.let { "'${it.value}'" } ?: "NULL"}", workspace, owner.actor))
                assertEquals(1, count(dataSource, "SELECT count(*) FROM semantic.definition_changes c JOIN semantic.metrics m ON m.metric_id = c.definition_id WHERE c.workspace_id = :workspace AND m.definition ->> 'name' = '$name' AND c.action = 'CREATED' AND c.actor_id = :actor AND c.credential_surface = '${surface.name}'", workspace, owner.actor))
                val definition = seed.dashboards.values.first()
                val created = assertIs<DashboardResult.Found>(owners.dashboards.create(DashboardUseCase.CreateCommand(workspace, definition, owner.actor.toString(), owner.actor.toString(),
                    "normal-$name", fixture.now, surface, agent))).dashboard
                assertEquals(owner.actor.toString(), created.updatedBy)
                assertEquals(1, count(dataSource, "SELECT count(*) FROM dashboard.dashboards WHERE dashboard_id = '${created.id}'::uuid AND updated_by = :actor::text AND credential_surface = '${surface.name}'", workspace, owner.actor))
                assertEquals(1, count(dataSource, "SELECT count(*) FROM dashboard.definition_changes WHERE dashboard_id = '${created.id}'::uuid AND action = 'CREATED' AND actor_id = :actor AND credential_surface = '${surface.name}'", workspace, owner.actor))
            }
        }
    }

    private class SeedOwners(dataSource: PGSimpleDataSource, private val fixture: SampleProvisioningFixture) {
        private val manager = DataSourceTransactionManager(dataSource)
        private val repository = PostgresSemanticRepository(dataSource, com.fasterxml.jackson.databind.ObjectMapper())
        private val transactions = SpringSemanticTransactionPort(dataSource,
            TransactionTemplate(manager).apply { isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ; timeout = 10 },
            TransactionTemplate(manager).apply { isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED; timeout = 10 },
            Dispatchers.IO, SemanticTransactionTimeouts(Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(2)))
        val semantic = SemanticAuthorityService(repository, transactions, fixture.clock, mockk(relaxed = true))
        val dashboards: DashboardUseCase = DashboardApplicationComposition().dashboardUseCase(object : DashboardSearchProjection {
                override fun upsert(fields: DashboardSearchFields) = Unit
                override fun delete(workspaceId: cc.monomer.identity.api.WorkspaceId, dashboardId: DashboardId) = Unit
            },
            DashboardApplicationComposition().dashboardTransactionPort(JdbcVirtualThreadDispatcher(dataSource), TransactionTemplate(manager).apply { timeout = 5 }),
            PostgresDashboardRepository(dataSource), PostgresDashboardCommandReceiptRepository(dataSource))
        val dashboardSeeds = DashboardSeedDefinitionService(dashboards)

        fun read(workspace: cc.monomer.identity.api.WorkspaceId, seed: SampleDefinitions): SemanticCurrentReadUseCase = mockk<SemanticCurrentReadUseCase>().also { read ->
            seed.models.map { it.name }.forEach { name ->
                coEvery { read.findModelByName(workspace, name) } coAnswers {
                    transactions.inSnapshotTransaction { repository.findModelByName(workspace, name) }
                        ?.let { SemanticModelView(it.id, it.workspaceId, it.definition, it.createdAt, it.updatedAt, emptyList()) }
                }
            }
            seed.metrics.map { it.name }.forEach { name ->
                coEvery { read.findMetricByName(workspace, name) } coAnswers {
                    transactions.inSnapshotTransaction { repository.findMetricByName(workspace, name) }
                        ?.let { MetricView(it.id, it.workspaceId, it.definition, it.createdAt, it.updatedAt, emptyList()) }
                }
            }
            coEvery { read.findTimeSpineConfigurations(workspace) } coAnswers { fixture.createdSpines.values.toList() }
        }

        fun authorization(actor: CanonicalPrincipal, sample: cc.monomer.identity.api.SampleWorkspace, surface: CredentialSurface, agent: AgentClientId?, intent: MutationIntent) =
            AuthorizedMutationContext(cc.monomer.identity.api.OrganizationPlan.FREE, actor, sample.organizationId, sample.workspaceId, AuthorizationRevision(1),
                if (surface == CredentialSurface.AGENT) CredentialKind.AGENT_OAUTH else CredentialKind.SESSION, surface, agent, WorkspaceRole.OWNER, intent, fixture.now)
    }

    private fun applySchemas(dataSource: PGSimpleDataSource, vararg resources: String) {
        dataSource.connection.use { connection -> connection.createStatement().use { statement ->
            resources.forEach { statement.execute(javaClass.classLoader.getResource(it)!!.readText()) }
        } }
    }

    private fun count(dataSource: PGSimpleDataSource, sql: String, workspace: cc.monomer.identity.api.WorkspaceId, actor: cc.monomer.identity.api.PrincipalId? = null): Int =
        NamedParameterJdbcTemplate(dataSource).queryForObject(sql, buildMap<String, Any> {
            put("workspace", workspace.value.toJavaUuid())
            actor?.let { put("actor", it.value.toJavaUuid()) }
        }, Int::class.java)!!

    private data class Identity(val api: SampleWorkspaceProvisioningUseCase, val actor: cc.monomer.identity.api.PrincipalId, val organization: cc.monomer.identity.api.OrganizationId)
    private suspend fun identity(dataSource: PGSimpleDataSource): Identity {
        dataSource.connection.use { connection -> connection.createStatement().use { statement ->
            statement.execute(javaClass.classLoader.getResource("db/identity-runtime/schema.sql")!!.readText())
        } }
        val jdbc = NamedParameterJdbcTemplate(dataSource)
        val manager = DataSourceTransactionManager(dataSource)
        val transactions = SpringTransactionRunner(TransactionTemplate(manager), TransactionTemplate(manager).apply { isReadOnly = true }, Dispatchers.IO)
        val organizations = PostgresOrganizationRepository(jdbc)
        val workspaces = PostgresWorkspaceRepository(jdbc)
        val members = PostgresWorkspaceMemberRepository(jdbc)
        val clock = Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), ZoneOffset.UTC)
        val actor = UserId.generate()
        val organization = OrganizationId.generate()
        val parameters = mapOf("id" to actor.value.toJavaUuid(), "email" to "$actor@example.test", "now" to Timestamp.from(clock.instant()))
        jdbc.update("INSERT INTO identity_runtime.principals(id,kind,display_name,created_at) VALUES(:id,'user','Test User',:now)", parameters)
        jdbc.update("INSERT INTO identity_runtime.users(id,primary_email,created_at) VALUES(:id,:email,:now)", parameters)
        transactions.run {
            organizations.insert(Organization(organization, "Test Organization", null, cc.monomer.identity.api.OrganizationPlan.FREE, clock.instant()))
            organizations.insertMember(OrganizationMembership(organization, actor, OrganizationRole.ADMIN, clock.instant()))
        }
        return Identity(SampleWorkspaceService(transactions, AdminAuthorization(organizations), workspaces, workspaces, WorkspaceAuthorizationBootstrap(members),
            members, PostgresAuthorizationAssignmentRepository(jdbc), PostgresAuditLogRepository(jdbc), AuditEventFactory(clock), clock),
            cc.monomer.identity.api.PrincipalId(actor.value), cc.monomer.identity.api.OrganizationId(organization.value))
    }
}
