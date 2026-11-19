package cc.monomer.control.operations

import cc.monomer.connector.adapter.outbound.postgres.PostgresConnectorOperationsQueries
import cc.monomer.control.EphemeralPostgresAuthoritySupport
import cc.monomer.control.jdbc.JdbcVirtualThreadDispatcher
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardOperationsQueries
import cc.monomer.explore.adapter.outbound.postgres.PostgresExploreOperationsQueries
import cc.monomer.identity.api.OperationsAccess
import cc.monomer.identity.api.OperationsAccessPort
import cc.monomer.identity.outbound.postgres.PostgresAgentAppDisplays
import cc.monomer.identity.outbound.postgres.PostgresIdentityOperationsQueries
import cc.monomer.identity.outbound.postgres.SpringTransactionRunner
import cc.monomer.semantic.adapter.outbound.postgres.PostgresSemanticOperationsQueries
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import kotlin.test.*

class OperationsJourneysPostgresIntegrationTest {
    private val support = EphemeralPostgresAuthoritySupport
    private val fixtures by lazy { JdbcTemplate(support.dataSource) }
    private fun at(value: String): Timestamp = Timestamp.from(Instant.parse(value))
    private fun uuid(suffix: String): UUID = UUID.fromString("00000000-0000-0000-0000-" + suffix.padStart(12, '0'))

    private val adminA1 = uuid("a1"); private val adminA2 = uuid("a3"); private val adminA3 = uuid("a2"); private val adminA4 = uuid("a4")
    private val adminB = uuid("b1"); private val sharedUser = uuid("c1"); private val pageReader = uuid("d1")
    private val adminX = uuid("e1"); private val adminLegacy = uuid("f1")
    private val acme = uuid("1a"); private val beta = uuid("1b"); private val internal = uuid("1c"); private val legacy = uuid("1d"); private val future = uuid("1e")
    private val workspaceA = uuid("2a"); private val workspaceA2 = uuid("2a2"); private val workspaceB = uuid("2b"); private val workspaceX = uuid("2c")
    private val claude = "https://claude.ai/oauth/mcp-oauth-client-metadata"
    private val codex = "codex-cli"

    @Test fun `journeys attribute retained owner records to current members in UTC weeks`() = runBlocking {
        support.verifyEphemeralIdentity()
        support.applyAuthoritySchemasTwice()
        insertIdentity()
        insertConnectors()
        insertExploreRuns()
        insertDashboardRecords()
        insertSemanticRecords()

        val seoulSessions = object : DelegatingDataSource(support.dataSource) {
            override fun getConnection(): Connection = super.getConnection().also { connection ->
                connection.createStatement().use { it.execute("SET TIME ZONE 'Asia/Seoul'") }
            }
        }
        val sessionClock = JdbcTemplate(seoulSessions)
        assertEquals("Asia/Seoul", sessionClock.queryForObject("SHOW TimeZone", String::class.java))
        assertEquals(LocalDate.parse("2026-09-21"),
            sessionClock.queryForObject("SELECT date_trunc('week', TIMESTAMPTZ '2026-09-20T23:59:59Z')::date", LocalDate::class.java),
            "a bare date_trunc in this session would move the Sunday boundary row into the next week")

        val controller = controller(seoulSessions)
        val response = controller.journeys(request("from=2026-09-14T00:00:00Z&to=2026-10-12T00:00:00Z" +
            "&excludeOrganizationId=$internal&excludeOrganizationId=$internal&excludeOrganizationId=${uuid("999")}"))

        assertEquals(listOf("2026-09-14", "2026-09-21", "2026-09-28", "2026-10-05"), response.weeks)
        assertEquals(1L, response.excludedOrganizations)
        assertEquals(emptyList<String>(), response.unavailableSources)
        assertEquals(listOf(beta, acme, legacy).map(UUID::toString), response.items.map { it.organizationId })
        assertEquals(listOf(1L, 1L, 1L, 2L), response.weeklyTotals.map { it.activeOrganizations })
        assertEquals(listOf(1L, 1L, 1L, 2L), response.weeklyTotals.map { it.activeUsers }, "the shared member active in both Organizations counts once")

        val acmeItem = response.items.single { it.organizationId == acme.toString() }
        assertEquals(5L, acmeItem.memberCount)
        assertEquals(2L, acmeItem.workspaceCount)
        assertEquals(listOf("Ada", "Cy", "Bo"), acmeItem.contacts.map { it.name }, "ADMINs by user creation then id, at most three")
        assertEquals(listOf("Claude", "Codex"), acmeItem.agentApps)
        val (sundayWeek, mondayWeek, thirdWeek, inProgressWeek) = acmeItem.weekly
        assertEquals(OperationsJourneyExploreRuns(1, 1, 0, 1, 0, 0, 1, 1), sundayWeek.exploreRuns)
        assertEquals(OperationsJourneyDashboardRuns(1, 0, 0), sundayWeek.dashboardRuns, "Sunday-evening UTC QueryRun stays in its UTC week")
        assertEquals(OperationsJourneyDefinitionChanges(2, 0, 0), sundayWeek.definitionChanges, "Sunday-evening UTC Dashboard and Semantic changes stay in their UTC week")
        assertEquals(true, sundayWeek.active)
        assertEquals(OperationsJourneyExploreRuns(2, 1, 1, 0, 0, 0, 2, 1), mondayWeek.exploreRuns, "a failed AGENT run is not succeededByAgent")
        assertEquals(OperationsJourneyDashboardRuns(2, 1, 1), mondayWeek.dashboardRuns, "Page reader, scheduled, anonymous and unparsable actors are excluded")
        assertEquals(OperationsJourneyDefinitionChanges(2, 1, 0), mondayWeek.definitionChanges)
        assertEquals(1L, mondayWeek.activeUsers)
        assertEquals(2L, mondayWeek.agentClients)
        assertEquals(OperationsJourneyDefinitionChanges(1, 0, 1), thirdWeek.definitionChanges)
        assertEquals(true, thirdWeek.active)
        assertEquals(OperationsJourneyExploreRuns(1, 1, 0, 0, 0, 0, 1, 1), inProgressWeek.exploreRuns)
        assertEquals(1L, inProgressWeek.activeUsers)
        assertEquals(OperationsJourneyMilestones(
            firstSampleConnectorAt = null,
            firstSampleAgentRunAt = null,
            firstConnectorAt = Instant.parse("2026-07-02T00:00:00Z"),
            firstAgentAuthorizedAt = Instant.parse("2026-09-01T00:00:00Z"),
            firstSucceededResultAt = Instant.parse("2026-08-03T00:00:00Z"),
            firstMetricAt = Instant.parse("2026-09-24T00:00:00Z"),
            firstDashboardAt = Instant.parse("2026-09-23T00:00:00Z"),
            firstAgentActivityAt = Instant.parse("2026-09-21T00:00:00Z"),
        ), acmeItem.milestones)

        val betaItem = response.items.single { it.organizationId == beta.toString() }
        assertEquals(listOf("Bea", "Sam"), betaItem.contacts.map { it.name })
        assertEquals(listOf("Claude"), betaItem.agentApps)
        assertEquals(listOf(false, false, false, true), betaItem.weekly.map { it.active })
        val betaWeek = betaItem.weekly.last()
        assertEquals(OperationsJourneyExploreRuns(2, 1, 0, 0, 0, 0, 2, 1), betaWeek.exploreRuns)
        assertEquals(OperationsJourneyDefinitionChanges(2, 1, 0), betaWeek.definitionChanges, "changes of a deleted Dashboard still count")
        assertEquals(2L, betaWeek.activeUsers)
        assertEquals(1L, betaWeek.agentClients)
        assertEquals(OperationsJourneyMilestones(
            firstSampleConnectorAt = null,
            firstSampleAgentRunAt = null,
            firstConnectorAt = Instant.parse("2026-09-29T11:00:00Z"),
            firstAgentAuthorizedAt = Instant.parse("2026-10-01T00:00:00Z"),
            firstSucceededResultAt = Instant.parse("2026-10-06T10:00:00Z"),
            firstMetricAt = null,
            firstDashboardAt = Instant.parse("2026-10-06T08:00:00Z"),
            firstAgentActivityAt = Instant.parse("2026-10-06T08:00:00Z"),
        ), betaItem.milestones)

        val legacyItem = response.items.single { it.organizationId == legacy.toString() }
        assertEquals(0L, legacyItem.workspaceCount)
        assertEquals(listOf(false, false, false, false), legacyItem.weekly.map { it.active })
        assertEquals(OperationsJourneyMilestones(null, null, null, null, null, null, null, null), legacyItem.milestones)

        val unfiltered = controller.journeys(request("from=2026-09-14T00:00:00Z&to=2026-10-12T00:00:00Z"))
        assertEquals(0L, unfiltered.excludedOrganizations)
        assertEquals(listOf(beta, internal, acme, legacy).map(UUID::toString), unfiltered.items.map { it.organizationId })
        assertEquals(listOf(1L, 2L, 2L, 2L), unfiltered.weeklyTotals.map { it.activeOrganizations })

        verifySampleActivity(controller, seoulSessions)

        insertBulkOrganizations(1_001)
        val identity = PostgresIdentityOperationsQueries(seoulSessions, identityRunner(seoulSessions))
        assertEquals(1_001, identity.journeyPopulation(access(), Instant.parse("2026-10-12T00:00:00Z"), emptySet(), 1_001).organizations.size)
        val overCap = assertFailsWith<ResponseStatusException> { controller.journeys(request("from=2026-09-14T00:00:00Z&to=2026-10-12T00:00:00Z")) }
        assertEquals(422, overCap.statusCode.value())
        assertEquals("Too many organizations for the journeys view.", overCap.reason)
        Unit
    }

    private suspend fun verifySampleActivity(controller: OperationsJourneysController, dataSource: DataSource) {
        val sampleOrganization = uuid("51")
        val sampleWorkspace = uuid("52")
        val connectorId = UUID.randomUUID()
        val version = UUID.randomUUID()
        val dashboardId = UUID.randomUUID()
        inTransaction {
            insertOrganization(sampleOrganization, "Sample only", "2026-07-01T00:00:00Z", listOf(sharedUser to "ADMIN"), listOf(sampleWorkspace))
            fixtures.update("""INSERT INTO connector.credential_versions(credential_version_id,workspace_id,connector_id,connector_type,key_version,nonce,additional_authenticated_data,ciphertext,envelope_digest,created_at)
                VALUES(?,?,?,'duckdb','test',decode(repeat('00',12),'hex'),decode('00','hex'),decode(repeat('00',17),'hex'),?,?)""",
                version, sampleWorkspace, connectorId, "sha256:" + "a".repeat(64), at("2026-07-01T01:00:00Z"))
            fixtures.update("""INSERT INTO connector.connectors(connector_id,workspace_id,connector_type,display_name,properties,credential_version_id,authority_revision,created_at,updated_at)
                VALUES(?,?,'duckdb','Acme Cloud','{"sample_dataset":"acme-cloud@1"}',?,1,?,?)""",
                connectorId, sampleWorkspace, version, at("2026-07-01T01:00:00Z"), at("2026-07-01T01:00:00Z"))
            fixtures.update("INSERT INTO dashboard.dashboards(dashboard_id,workspace_id,definition,created_at,updated_at) VALUES(?,?,'{}'::jsonb,?,?)",
                dashboardId, sampleWorkspace, at("2026-07-01T02:00:00Z"), at("2026-07-01T02:00:00Z"))
            fixtures.update("""INSERT INTO dashboard.definition_changes(change_id,workspace_id,dashboard_id,title,action,definition,actor_id,credential_surface,agent_client_id,occurred_at)
                VALUES(?,?,?,'Sample','CREATED','{}'::jsonb,?,'WEB',NULL,?)""",
                UUID.randomUUID(), sampleWorkspace, dashboardId, sharedUser, at("2026-07-01T02:00:00Z"))
            fixtures.update("""INSERT INTO dashboard.definition_changes(change_id,workspace_id,dashboard_id,title,action,definition,actor_id,credential_surface,agent_client_id,occurred_at)
                VALUES(?,?,?,'Sample','APPLIED','{}'::jsonb,?,'AGENT',?,?)""",
                UUID.randomUUID(), sampleWorkspace, dashboardId, sharedUser, claude, at("2026-07-02T02:00:00Z"))
            fun sampleRun(actor: UUID, state: String, surface: String, createdAt: String) {
                val id = UUID.randomUUID()
                fixtures.update("""INSERT INTO explore.runs(run_id,workspace_id,idempotency_key,owner_principal_id,credential_surface,connector_id,connector_type,kind,request_digest,state,failure_code,revision,created_at,updated_at,terminal_at,agent_client_id)
                    VALUES(?,?,?,?,?,?,'DUCKDB','SQL',?,?,?,0,?,?,?,?)""",
                    id, sampleWorkspace, "sample-$id", actor, surface, connectorId, "sha256:" + "a".repeat(64), state,
                    if (state == "FAILED") "EXECUTION_FAILED" else null, at(createdAt), at(createdAt), at(createdAt), if (surface == "AGENT") claude else null)
            }
            sampleRun(pageReader, "SUCCEEDED", "AGENT", "2026-07-01T03:00:00Z")
            sampleRun(sharedUser, "FAILED", "AGENT", "2026-08-03T12:34:56Z")
            sampleRun(sharedUser, "SUCCEEDED", "WEB", "2026-09-22T00:00:00Z")
            sampleRun(sharedUser, "FAILED", "AGENT", "2026-09-22T01:00:00Z")
            fun queryRun(actor: String, createdAt: String) {
                fixtures.update("""INSERT INTO dashboard.query_runs(query_run_id,workspace_id,dashboard_id,query_name,query_type,connector_id,resolved_query,output_column_names,
                    actor_id,credential_surface,state,failure_code,failure_message,created_at,updated_at,agent_client_id)
                    VALUES(?,?,?,'sample','SQL',?,'{}'::jsonb,'[]'::jsonb,?,'AGENT','FAILED','EXECUTION_FAILED','private failure',?,?,?)""",
                    UUID.randomUUID(), sampleWorkspace, dashboardId, connectorId, actor, at(createdAt), at(createdAt), claude)
            }
            queryRun("  ${sharedUser.toString().uppercase()} ", "2026-08-02T12:34:56Z")
            queryRun(pageReader.toString(), "2026-07-01T03:00:00Z")
        }
        val item = controller.journeys(request("from=2026-09-14T00:00:00Z&to=2026-10-12T00:00:00Z"))
            .items.single { it.organizationId == sampleOrganization.toString() }
        assertNull(item.milestones.firstConnectorAt, "sample connector does not reach Connected a warehouse")
        assertEquals(Instant.parse("2026-07-01T01:00:00Z"), item.milestones.firstSampleConnectorAt)
        assertEquals(Instant.parse("2026-08-02T12:34:56Z"), item.milestones.firstSampleAgentRunAt,
            "all-history actual Agent query run wins; older definition changes and non-members do not")
        assertEquals(Instant.parse("2026-07-02T02:00:00Z"), item.milestones.firstAgentActivityAt, "existing definition-inclusive milestone keeps its meaning")
        assertEquals(OperationsJourneyExploreRuns(2, 1, 0, 0, 2, 1, 0, 0), item.weekly[1].exploreRuns)
        val owner = PostgresDashboardOperationsQueries(dataSource, readTransaction(dataSource), JdbcVirtualThreadDispatcher(dataSource))
            .journeyActivity(access(), setOf(sampleWorkspace.toString()), Instant.parse("2026-09-14T00:00:00Z"), Instant.parse("2026-10-12T00:00:00Z"))
        assertEquals(Instant.parse("2026-08-02T12:34:56Z"), owner.actorMilestones.single { it.actorId == sharedUser.toString() }.firstAgentQueryRunAt)
        assertTrue(owner.weeklyQueryRuns.isEmpty(), "milestone query is independent of the visible period")
    }

    private fun access(): OperationsAccess = mockk<OperationsAccess>().also { every { it.expiresAt } returns Instant.now().plusSeconds(3600) }

    private fun readTransaction(dataSource: DataSource) = TransactionTemplate(DataSourceTransactionManager(dataSource)).apply {
        isReadOnly = true
        timeout = 5
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
    }

    private fun identityRunner(dataSource: DataSource) =
        SpringTransactionRunner(readTransaction(dataSource), readTransaction(dataSource), JdbcVirtualThreadDispatcher(dataSource))

    private fun controller(dataSource: DataSource): OperationsJourneysController {
        val authorization = mockk<OperationsAccessPort>()
        val access = access()
        coEvery { authorization.authenticate(any()) } returns access
        val dispatcher = JdbcVirtualThreadDispatcher(dataSource)
        return OperationsJourneysController(
            authorization,
            PostgresIdentityOperationsQueries(dataSource, identityRunner(dataSource)),
            PostgresConnectorOperationsQueries(dataSource, readTransaction(dataSource), dispatcher),
            PostgresExploreOperationsQueries(dataSource, readTransaction(dataSource), dispatcher),
            PostgresDashboardOperationsQueries(dataSource, readTransaction(dataSource), dispatcher),
            PostgresSemanticOperationsQueries(dataSource, readTransaction(dataSource), dispatcher),
            PostgresAgentAppDisplays(NamedParameterJdbcTemplate(dataSource), identityRunner(dataSource)),
            Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC),
        )
    }

    private fun request(query: String) = MockServerWebExchange.from(
        MockServerHttpRequest.get("/ops/v1/journeys?$query").header("Authorization", "Bearer operations").build(),
    )

    private fun inTransaction(block: () -> Unit) {
        TransactionTemplate(DataSourceTransactionManager(support.dataSource)).executeWithoutResult { block() }
    }

    private fun insertUser(id: UUID, name: String, email: String, createdAt: String) {
        fixtures.update("INSERT INTO identity_runtime.principals(id,kind,display_name) VALUES(?,'user',?)", id, name)
        fixtures.update("INSERT INTO identity_runtime.users(id,primary_email,created_at) VALUES(?,?,?)", id, email, at(createdAt))
    }

    private fun insertOrganization(id: UUID, name: String, createdAt: String, members: List<Pair<UUID, String>>, workspaces: List<UUID>) {
        fixtures.update("INSERT INTO identity_runtime.organizations(id,display_name,created_at) VALUES(?,?,?)", id, name, at(createdAt))
        members.forEach { (user, role) ->
            fixtures.update("INSERT INTO identity_runtime.organization_members(organization_id,user_id,role) VALUES(?,?,?)", id, user, role)
        }
        workspaces.forEach { workspace ->
            fixtures.update("INSERT INTO identity_runtime.workspaces(id,organization_id,display_name,created_at,public_pages) VALUES(?,?,'Fixture',?,true)",
                workspace, id, at(createdAt))
            fixtures.update("INSERT INTO identity_runtime.workspace_members(workspace_id,user_id,role) VALUES(?,?,'OWNER')", workspace, members.first().first)
        }
    }

    private fun insertGrant(owner: UUID, client: String, label: String, issuedAt: String, revokedAt: String?) {
        fixtures.update("""INSERT INTO identity_runtime.agent_oauth_grants(id,owner_user_id,oauth_client_id,client_label,resource_audience,scopes,issued_at,revoked_at)
            VALUES(?,?,?,?,'http://127.0.0.1:11077/mcp','{agent.workspace}',?,?)""", UUID.randomUUID(), owner, client, label, at(issuedAt), revokedAt?.let(::at))
    }

    private fun insertIdentity() = inTransaction {
        insertUser(adminA1, "Ada", "ada@acme.example", "2026-01-01T00:00:00Z")
        insertUser(adminA2, "Bo", "bo@acme.example", "2026-01-02T00:00:00Z")
        insertUser(adminA3, "Cy", "cy@acme.example", "2026-01-02T00:00:00Z")
        insertUser(adminA4, "Di", "di@acme.example", "2026-01-03T00:00:00Z")
        insertUser(adminB, "Bea", "bea@beta.example", "2026-02-01T00:00:00Z")
        insertUser(sharedUser, "Sam", "sam@example.test", "2026-03-01T00:00:00Z")
        insertUser(pageReader, "Reader", "reader@example.test", "2026-04-01T00:00:00Z")
        insertUser(adminX, "Operator", "operator@monomer.test", "2026-01-01T00:00:00Z")
        insertUser(adminLegacy, "Lee", "lee@legacy.example", "2026-05-01T00:00:00Z")
        insertOrganization(acme, "Acme", "2026-07-01T08:00:00Z",
            listOf(adminA1 to "ADMIN", adminA2 to "ADMIN", adminA3 to "ADMIN", adminA4 to "ADMIN", sharedUser to "MEMBER"), listOf(workspaceA, workspaceA2))
        insertOrganization(beta, "Beta", "2026-09-29T10:00:00Z", listOf(adminB to "ADMIN", sharedUser to "ADMIN"), listOf(workspaceB))
        insertOrganization(internal, "Internal", "2026-09-20T00:00:00Z", listOf(adminX to "ADMIN"), listOf(workspaceX))
        insertOrganization(legacy, "Legacy", "2026-06-01T00:00:00Z", listOf(adminLegacy to "ADMIN"), emptyList())
        insertOrganization(future, "Future", "2026-10-12T00:00:00Z", listOf(adminLegacy to "ADMIN"), emptyList())
        insertGrant(adminA1, claude, "Claude", "2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z")
        insertGrant(sharedUser, codex, "Codex", "2026-10-01T00:00:00Z", null)
        insertGrant(pageReader, claude, "Claude", "2026-01-01T00:00:00Z", null)
    }

    private fun insertConnectors() {
        fun connector(workspace: UUID, createdAt: String, deleted: Boolean) {
            val connectorId = UUID.randomUUID(); val version = UUID.randomUUID()
            fixtures.update("""INSERT INTO connector.credential_versions(credential_version_id,workspace_id,connector_id,connector_type,key_version,nonce,additional_authenticated_data,ciphertext,envelope_digest,created_at)
                VALUES(?,?,?,'postgresql','test',decode(repeat('00',12),'hex'),decode('00','hex'),decode(repeat('00',17),'hex'),?,?)""",
                version, workspace, connectorId, "sha256:" + "a".repeat(64), at(createdAt))
            fixtures.update("""INSERT INTO connector.connectors(connector_id,workspace_id,connector_type,display_name,properties,credential_version_id,authority_revision,created_at,updated_at,deleted_at)
                VALUES(?,?,'postgresql','Fixture','{"host":"example.test","port":"5432","database":"fixture"}',?,1,?,?,?)""",
                connectorId, workspace, if (deleted) null else version, at(createdAt), at(createdAt), if (deleted) at(createdAt) else null)
        }
        connector(workspaceA, "2026-07-02T00:00:00Z", deleted = true)
        connector(workspaceA2, "2026-08-01T00:00:00Z", deleted = false)
        connector(workspaceB, "2026-09-29T11:00:00Z", deleted = false)
    }

    private fun insertExploreRuns() {
        fun run(workspace: UUID, owner: UUID, state: String, surface: String?, client: String?, createdAt: String) {
            val runId = UUID.randomUUID()
            fixtures.update("""INSERT INTO explore.runs(run_id,workspace_id,idempotency_key,owner_principal_id,credential_surface,connector_id,connector_type,kind,request_digest,state,failure_code,revision,created_at,updated_at,terminal_at,agent_client_id)
                VALUES(?,?,?,?,?,?,'POSTGRESQL','SQL',?,?,?,0,?,?,?,?)""",
                runId, workspace, "fixture-$runId", owner, surface, UUID.randomUUID(), "sha256:" + "a".repeat(64), state,
                if (state == "FAILED") "EXECUTION_FAILED" else null, at(createdAt), at(createdAt), at(createdAt), client)
        }
        run(workspaceA, adminA1, "SUCCEEDED", null, null, "2026-08-03T00:00:00Z")
        run(workspaceA, adminA1, "FAILED", "WEB", null, "2026-09-13T23:59:59Z")
        run(workspaceA, adminA1, "SUCCEEDED", null, null, "2026-09-20T23:59:59Z")
        run(workspaceA, adminA1, "SUCCEEDED", "AGENT", claude, "2026-09-21T00:00:00Z")
        run(workspaceA, adminA1, "FAILED", "AGENT", claude, "2026-09-22T00:00:00Z")
        run(workspaceA, pageReader, "SUCCEEDED", "WEB", null, "2026-09-16T00:00:00Z")
        run(workspaceA, sharedUser, "SUCCEEDED", "WEB", null, "2026-10-06T09:00:00Z")
        run(workspaceB, sharedUser, "SUCCEEDED", "WEB", null, "2026-10-06T10:00:00Z")
        run(workspaceB, adminB, "FAILED", "WEB", null, "2026-10-07T01:00:00Z")
        run(workspaceX, adminX, "SUCCEEDED", "AGENT", claude, "2026-09-22T00:00:00Z")
    }

    private fun insertDashboardRecords() {
        val dashboard = UUID.randomUUID(); val deletedDashboard = UUID.randomUUID(); val internalDashboard = UUID.randomUUID()
        fixtures.update("INSERT INTO dashboard.dashboards(dashboard_id,workspace_id,definition,created_at,updated_at) VALUES(?,?,'{}'::jsonb,?,?)",
            dashboard, workspaceA, at("2026-09-23T00:00:00Z"), at("2026-09-23T00:00:00Z"))
        fixtures.update("INSERT INTO dashboard.dashboards(dashboard_id,workspace_id,definition,created_at,updated_at) VALUES(?,?,'{}'::jsonb,?,?)",
            internalDashboard, workspaceX, at("2026-09-22T00:00:00Z"), at("2026-09-22T00:00:00Z"))
        fun queryRun(workspace: UUID, dashboardId: UUID, actor: String?, state: String, surface: String?, client: String?, createdAt: String) {
            fixtures.update("""INSERT INTO dashboard.query_runs(query_run_id,workspace_id,dashboard_id,query_name,query_type,connector_id,resolved_query,output_column_names,
                actor_id,credential_surface,state,result_manifest,failure_code,failure_message,created_at,updated_at,agent_client_id)
                VALUES(?,?,?,'revenue','SQL',?,'{}'::jsonb,'[]'::jsonb,?,?,?,?::jsonb,?,?,?,?,?)""",
                UUID.randomUUID(), workspace, dashboardId, UUID.randomUUID(), actor, surface, state,
                if (state == "SUCCEEDED") "{}" else null, if (state == "FAILED") "EXECUTION_FAILED" else null,
                if (state == "FAILED") "private failure" else null, at(createdAt), at(createdAt), client)
        }
        queryRun(workspaceA, dashboard, "  ${adminA1.toString().uppercase()} ", "SUCCEEDED", "AGENT", codex, "2026-09-23T01:00:00Z")
        queryRun(workspaceA, dashboard, adminA1.toString(), "FAILED", "AGENT", codex, "2026-09-24T00:00:00Z")
        queryRun(workspaceA, dashboard, pageReader.toString(), "SUCCEEDED", "WEB", null, "2026-09-23T02:00:00Z")
        queryRun(workspaceA, dashboard, null, "SUCCEEDED", null, null, "2026-09-23T03:00:00Z")
        queryRun(workspaceA, dashboard, null, "SUCCEEDED", "WEB", null, "2026-09-23T04:00:00Z")
        queryRun(workspaceA, dashboard, "schedule:nightly", "SUCCEEDED", "WEB", null, "2026-09-23T05:00:00Z")
        queryRun(workspaceX, internalDashboard, adminX.toString(), "SUCCEEDED", "WEB", null, "2026-09-29T00:00:00Z")
        queryRun(workspaceA, dashboard, adminA1.toString(), "FAILED", "WEB", null, "2026-09-20T23:30:00Z")
        fun change(workspace: UUID, dashboardId: UUID, action: String, actor: UUID?, surface: String?, client: String?, occurredAt: String) {
            fixtures.update("""INSERT INTO dashboard.definition_changes(change_id,workspace_id,dashboard_id,title,action,definition,actor_id,credential_surface,agent_client_id,occurred_at)
                VALUES(?,?,?,'Revenue',?,'{}'::jsonb,?,?,?,?)""", UUID.randomUUID(), workspace, dashboardId, action, actor, surface, client, at(occurredAt))
        }
        change(workspaceA, dashboard, "CREATED", adminA1, "WEB", null, "2026-09-23T00:00:00Z")
        change(workspaceA, dashboard, "APPLIED", adminA1, "WEB", null, "2026-09-20T23:40:00Z")
        change(workspaceA, dashboard, "APPLIED", null, null, null, "2026-09-23T06:00:00Z")
        change(workspaceB, deletedDashboard, "CREATED", sharedUser, "AGENT", claude, "2026-10-06T08:00:00Z")
        change(workspaceB, deletedDashboard, "DELETED", sharedUser, "WEB", null, "2026-10-06T09:00:00Z")
    }

    private fun insertSemanticRecords() {
        fixtures.update("INSERT INTO semantic.metrics(metric_id,workspace_id,definition,created_at,updated_at) VALUES(?,?,'{\"name\":\"revenue\"}'::jsonb,?,?)",
            UUID.randomUUID(), workspaceA, at("2026-09-25T00:00:00Z"), at("2026-09-25T00:00:00Z"))
        fun change(kind: String, action: String, actor: UUID?, surface: String?, client: String?, occurredAt: String) {
            fixtures.update("""INSERT INTO semantic.definition_changes(change_id,workspace_id,definition_kind,definition_id,definition_name,action,definition,actor_id,credential_surface,agent_client_id,occurred_at)
                VALUES(?,?,?,?,'revenue',?,'{}'::jsonb,?,?,?,?)""", UUID.randomUUID(), workspaceA, kind, UUID.randomUUID(), action, actor, surface, client, at(occurredAt))
        }
        change("METRIC", "CREATED", adminA1, "AGENT", claude, "2026-09-24T00:00:00Z")
        change("SEMANTIC_MODEL", "UPDATED", adminA1, "WEB", null, "2026-09-20T23:50:00Z")
        change("SEMANTIC_MODEL", "CREATED", adminA2, null, null, "2026-09-28T00:00:00Z")
        change("METRIC", "UPDATED", pageReader, "WEB", null, "2026-09-29T00:00:00Z")
        change("METRIC", "UPDATED", null, null, null, "2026-09-30T00:00:00Z")
    }

    private fun insertBulkOrganizations(count: Int) = inTransaction {
        fixtures.update("""INSERT INTO identity_runtime.organizations(id,display_name,created_at)
            SELECT gen_random_uuid(),'Bulk',TIMESTAMPTZ '2026-09-01T00:00:00Z' FROM generate_series(1,?)""", count)
        fixtures.update("""INSERT INTO identity_runtime.organization_members(organization_id,user_id,role)
            SELECT id,?,'ADMIN' FROM identity_runtime.organizations WHERE display_name='Bulk'""", adminLegacy)
    }

    companion object {
        @JvmField @RegisterExtension val postgres = EphemeralPostgresAuthoritySupport
    }
}
