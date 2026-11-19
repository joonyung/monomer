package cc.monomer.control

import cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogConnectorStateRepository
import cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogIngestRepository
import cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogSelectionRepository
import cc.monomer.catalog.api.CatalogIngestEntry
import cc.monomer.catalog.api.CatalogIngestJobId
import cc.monomer.catalog.api.CatalogRelationSelection
import cc.monomer.catalog.api.CatalogSelectedSchema
import cc.monomer.catalog.api.CatalogSelection
import cc.monomer.catalog.api.CatalogSelectionState
import cc.monomer.catalog.domain.CatalogIngestJob
import cc.monomer.connector.adapter.outbound.postgres.*
import cc.monomer.connector.api.ConnectorId
import cc.monomer.catalog.application.CatalogConnectorStateRemoval
import cc.monomer.identity.api.PrincipalId
import cc.monomer.control.connector.inbound.rest.ConnectorMutationController
import cc.monomer.control.connector.inbound.rest.ConnectorMutationProblemHandler
import cc.monomer.control.search.CatalogConnectorSearchProjection
import cc.monomer.identity.api.OrganizationId
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.api.WorkspaceSampleQuery
import cc.monomer.identity.application.IdentityAdmissionService
import cc.monomer.identity.application.agent.AgentOAuthTokenHasher
import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.DeveloperAccessPolicy
import cc.monomer.identity.outbound.jwt.*
import cc.monomer.identity.outbound.postgres.PostgresCanonicalIdentityAdmissionSnapshot
import cc.monomer.identity.outbound.postgres.SpringTransactionRunner
import cc.monomer.search.adapter.outbound.postgres.PostgresSearchIndex
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.transaction.support.TransactionTemplate

class ConnectorLimitHttpPostgresIntegrationTest {
    private val now = Instant.parse("2026-09-09T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val organizationId = OrganizationId.generate()
    private val workspaceId = WorkspaceId.generate()
    private val otherWorkspaceId = WorkspaceId.generate()
    private val userId = UserId.generate()
    private val adminId = UserId.generate()
    private val sessionId = SessionId.generate()
    private val jdbc get() = NamedParameterJdbcTemplate(database.dataSource)
    private lateinit var keyRing: ManagedCredentialKeyRing
    private lateinit var client: WebTestClient
    private lateinit var token: String
    private lateinit var catalogSelections: PostgresCatalogSelectionRepository
    private lateinit var catalogIngest: PostgresCatalogIngestRepository
    private lateinit var ownerTransactions: TransactionTemplate

    @BeforeEach
    fun setUp() {
        database.dataSource.connection.use { connection ->
            listOf("identity-runtime", "connector", "catalog", "search").forEach { owner ->
                connection.createStatement().use {
                    it.execute(checkNotNull(javaClass.classLoader.getResource("db/$owner/schema.sql")).readText())
                }
            }
        }
        val transactionManager = DataSourceTransactionManager(database.dataSource)
        val transactions = SpringTransactionRunner(
            TransactionTemplate(transactionManager),
            TransactionTemplate(transactionManager).apply {
                isReadOnly = true
                isolationLevel = java.sql.Connection.TRANSACTION_REPEATABLE_READ
            }, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val jwtKeys = JwtKeySource(keyPair.private as RSAPrivateKey, keyPair.public as RSAPublicKey, "test")
        val jwtProperties = JwtProperties("monomer-auth", "monomer-internal", 900, 604800, "", "")
        token = JwtIssuer(jwtKeys, jwtProperties, clock).issueBrowserSession(userId, sessionId).serialized
        val identity = IdentityAdmissionService(PostgresCanonicalIdentityAdmissionSnapshot(
            jdbc, transactions, JwtVerifier(jwtKeys, jwtProperties, clock), AgentOAuthTokenHasher(), clock,
            mapOf(ExpectedOAuthResource.AGENT_REST to "http://localhost/v2/workspaces", ExpectedOAuthResource.AGENT_MCP to "http://localhost/mcp"),
            DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.OPEN, emptySet()),
        ))
        val admission = ControlAdmissionFacade(identity, clock)
        keyRing = ManagedCredentialKeyRing("test/active", mapOf("test/active" to ByteArray(32) { 7 }))
        val credentials = PostgresManagedCredentialStore(
            database.dataSource, keyRing, SecureRandomCredentialVaultNonceSource(SecureRandom()), clock,
            Dispatchers.IO, 1_048_576, 16, 64, 262_144,
        )
        val composition = ControlProductAuthorityConfiguration()
        val searchProjection = CatalogConnectorSearchProjection(PostgresSearchIndex(database.dataSource))
        catalogSelections = PostgresCatalogSelectionRepository(database.dataSource, ObjectMapper(), searchProjection)
        catalogIngest = PostgresCatalogIngestRepository(database.dataSource, ObjectMapper(), searchProjection, catalogSelections)
        val authority = composition.connectorAuthorityService(
            PostgresConnectorRepository(database.dataSource, ObjectMapper().findAndRegisterModules()),
            SpringConnectorTransactionPort(database.dataSource, TransactionTemplate(transactionManager).apply { timeout = 10 },
                kotlinx.coroutines.Dispatchers.IO, ConnectorTransactionTimeouts(Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(2))),
            PostgresConnectorMutationKeyLockPort(database.dataSource, Duration.ofSeconds(2)),
            credentials, searchProjection, CatalogConnectorStateRemoval(PostgresCatalogConnectorStateRepository(database.dataSource, searchProjection)),
            WorkspaceSampleQuery { null }, clock,
        )
        ownerTransactions = TransactionTemplate(transactionManager)
        client = WebTestClient.bindToController(ConnectorMutationController(authority, admission))
            .controllerAdvice(ConnectorMutationProblemHandler()).build()
        TransactionTemplate(transactionManager).executeWithoutResult { seedIdentity() }
    }

    @AfterEach
    fun closeKeyRing() { keyRing.close() }

    @Test
    fun `public create persists ten connectors rejects eleventh without writes and replays at cap`() {
        val first = create(workspaceId, "create-0").expectStatus().isCreated.expectBody().returnResult().responseBody
        repeat(9) { create(workspaceId, "create-${it + 1}").expectStatus().isCreated }
        assertOwnerCounts(workspaceId, 10)

        val rejection = create(workspaceId, "over-limit").expectStatus().isEqualTo(409)
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectHeader().doesNotExist(HttpHeaders.RETRY_AFTER)
            .expectBody().jsonPath("$.code").isEqualTo("CONNECTOR_LIMIT_EXCEEDED")
            .jsonPath("$.retryable").isEqualTo(false)
            .jsonPath("$.detail").isEqualTo("Free workspaces can have up to 10 connectors. Use an existing connector or choose another workspace.")
            .returnResult().responseBody!!.decodeToString()
        assertFalse(rejection.contains("test-only-credential-sentinel"))
        assertOwnerCounts(workspaceId, 10)
        val replay = create(workspaceId, "create-0").expectStatus().isCreated.expectBody().returnResult().responseBody
        assertEquals(first!!.decodeToString(), replay!!.decodeToString())
        assertOwnerCounts(workspaceId, 10)
        create(workspaceId, "create-0", "Changed Warehouse").expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("CONFLICT")
        assertOwnerCounts(workspaceId, 10)

        create(otherWorkspaceId, "create-0").expectStatus().isCreated
        assertOwnerCounts(otherWorkspaceId, 1)
        assertOwnerCounts(workspaceId, 10)
        jdbc.update("UPDATE identity_runtime.organizations SET plan='pro' WHERE id=:organization", parameters())
        create(workspaceId, "paid-eleventh").expectStatus().isCreated
        assertOwnerCounts(workspaceId, 11)
    }

    @Test
    fun `public create requires current credential workspace membership and Owner role before persistence`() {
        client.post().uri("/v2/workspaces/$workspaceId/connectors")
            .header("Idempotency-Key", "missing-token").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}").exchange().expectStatus().isUnauthorized
        create(workspaceId, "invalid-token", bearer = "invalid").expectStatus().isUnauthorized
            .expectBody().jsonPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED")
        create(WorkspaceId.generate(), "unknown-workspace").expectStatus().isNotFound
        jdbc.update("UPDATE identity_runtime.workspace_members SET role='EDITOR' WHERE workspace_id=:workspace AND user_id=:user", parameters())
        create(workspaceId, "not-owner").expectStatus().isForbidden
            .expectBody().jsonPath("$.code").isEqualTo("ROLE_REQUIRED")
        grantOwnerRole(workspaceId)
        jdbc.update("DELETE FROM identity_runtime.workspace_members WHERE workspace_id=:workspace AND user_id=:user", parameters())
        create(workspaceId, "no-membership").expectStatus().isNotFound
        assertOwnerCounts(workspaceId, 0)
        create(otherWorkspaceId, "other-workspace").expectStatus().isCreated
        assertOwnerCounts(otherWorkspaceId, 1)
    }

    @Test
    fun `owner delete frees the limit slot destroys credentials and catalog state and repeats as not found`() {
        repeat(10) { create(workspaceId, "delete-seed-$it", "Warehouse $it").expectStatus().isCreated }
        val connectorId = jdbc.queryForObject("SELECT connector_id FROM connector.connectors WHERE workspace_id=:workspace AND display_name='Warehouse 0'",
            parameters(), java.util.UUID::class.java)!!
        val connector = ConnectorId(connectorId.toKotlinUuid())
        ownerTransactions.executeWithoutResult {
            catalogSelections.saveSelection(CatalogSelectionState(workspaceId, connector, CatalogIngestEntry.Connector(connector),
                CatalogSelection(setOf(CatalogSelectedSchema("analytics", CatalogRelationSelection.AllRelations))),
                PrincipalId(userId.value), now))
        }
        create(workspaceId, "delete-over-limit").expectStatus().isEqualTo(409)

        jdbc.update("UPDATE identity_runtime.workspace_members SET role='EDITOR' WHERE workspace_id=:workspace AND user_id=:user", parameters())
        delete(workspaceId, connector).expectStatus().isForbidden
        grantOwnerRole(workspaceId)

        val job = CatalogIngestJob.request(
            id = CatalogIngestJobId.generate(),
            workspaceId = workspaceId,
            connectorId = connector,
            selection = CatalogSelection(setOf(CatalogSelectedSchema("analytics", CatalogRelationSelection.AllRelations))),
            entry = CatalogIngestEntry.Connector(connector),
            requestedBy = PrincipalId(userId.value),
            requestedAt = now,
            credentialSurface = null,
            agentClientId = null,
        )
        ownerTransactions.executeWithoutResult { catalogIngest.insertIngestJob(job) }
        delete(workspaceId, connector).expectStatus().isEqualTo(409)
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody().jsonPath("$.code").isEqualTo("CONNECTOR_REFRESH_ACTIVE")
        assertOwnerCounts(workspaceId, 10)
        ownerTransactions.executeWithoutResult { check(catalogIngest.updateIngestJob(job.complete(now))) }

        delete(workspaceId, connector).expectStatus().isNoContent
        assertEquals(9, jdbc.queryForObject("SELECT count(*) FROM connector.connectors WHERE workspace_id=:workspace AND deleted_at IS NULL", parameters(), Int::class.java))
        assertEquals(9, jdbc.queryForObject("SELECT count(*) FROM connector.credential_versions WHERE workspace_id=:workspace", parameters(), Int::class.java))
        assertEquals(9, jdbc.queryForObject("SELECT count(*) FROM search.documents WHERE workspace_id=:workspace", parameters(), Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM catalog.selections WHERE workspace_id=:workspace", parameters(), Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM catalog.ingest_jobs WHERE workspace_id=:workspace", parameters(), Int::class.java))
        delete(workspaceId, connector).expectStatus().isNotFound
        create(workspaceId, "delete-reuse", "Warehouse 0").expectStatus().isCreated
    }

    private fun delete(workspace: WorkspaceId, connector: ConnectorId) =
        client.delete().uri("/v2/workspaces/$workspace/connectors/$connector").headers { it.setBearerAuth(token) }.exchange()

    private fun create(workspace: WorkspaceId, key: String, name: String = "Warehouse", bearer: String = token) =
        client.post().uri("/v2/workspaces/$workspace/connectors").headers { it.setBearerAuth(bearer) }
            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"connectorType":"POSTGRESQL","displayName":"$name","properties":{"host":"warehouse.internal","port":5432,"database":"warehouse"},"credential":{"kind":"USERNAME_PASSWORD","username":"reader","password":"test-only-credential-sentinel"}}""")
            .exchange()

    private fun assertOwnerCounts(workspace: WorkspaceId, expected: Int) {
        listOf("connector.connectors", "connector.credential_versions", "connector.connector_mutation_receipts", "search.documents").forEach { table ->
            assertEquals(expected, jdbc.queryForObject("SELECT count(*) FROM $table WHERE workspace_id=:workspace",
                mapOf("workspace" to workspace.value.toJavaUuid()), Int::class.java), table)
        }
    }

    private fun seedIdentity() {
        val params = parameters() + mapOf(
            "login" to Uuid.generateV7().toJavaUuid(), "session" to sessionId.value.toJavaUuid(),
            "email" to "$userId@example.test", "adminEmail" to "$adminId@example.test", "now" to Timestamp.from(now), "expiry" to Timestamp.from(now.plusSeconds(3600)),
        )
        jdbc.update("INSERT INTO identity_runtime.organizations(id,display_name,plan) VALUES(:organization,'Connector HTTP','free')", params)
        jdbc.update("INSERT INTO identity_runtime.principals(id,kind,display_name) VALUES(:user,'user','Connector Manager'),(:admin,'user','Administrator')", params)
        jdbc.update("INSERT INTO identity_runtime.users(id,primary_email) VALUES(:user,:email),(:admin,:adminEmail)", params)
        jdbc.update("INSERT INTO identity_runtime.organization_members(organization_id,user_id,role) VALUES(:organization,:user,'MEMBER'),(:organization,:admin,'ADMIN')", params)
        listOf(workspaceId, otherWorkspaceId).forEach { workspace ->
            val workspaceParams = params + mapOf("workspace" to workspace.value.toJavaUuid())
            jdbc.update("INSERT INTO identity_runtime.workspaces(id,organization_id,display_name) VALUES(:workspace,:organization,'Connector Workspace')", workspaceParams)
            jdbc.update("INSERT INTO identity_runtime.workspace_members(workspace_id,user_id,role) VALUES(:workspace,:user,'EXPLORER'),(:workspace,:admin,'OWNER')", workspaceParams)
            grantOwnerRole(workspace)
        }
        jdbc.update("""INSERT INTO identity_runtime.login_identities(id,user_id,protocol,namespace,subject,linked_via,linked_at)
            VALUES(:login,:user,'oidc','https://accounts.google.com',:email,'signup',:now)""", params)
        jdbc.update("""INSERT INTO identity_runtime.sessions(id,user_id,login_identity_id,protocol,authentication_source_ref,authentication_issuer,asserted_email,asserted_email_verified,email_evidence,created_at,last_refreshed_at,absolute_expires_at)
            VALUES(:session,:user,:login,'oidc','builtin/google','https://accounts.google.com',:email,true,'authoritative',:now,:now,:expiry)""", params)
    }

    private fun grantOwnerRole(workspace: WorkspaceId) {
        jdbc.update("UPDATE identity_runtime.workspace_members SET role='OWNER' WHERE workspace_id=:workspace AND user_id=:user",
            parameters() + mapOf("workspace" to workspace.value.toJavaUuid()))
    }

    private fun parameters() = mapOf("organization" to organizationId.value.toJavaUuid(), "workspace" to workspaceId.value.toJavaUuid(), "user" to userId.value.toJavaUuid(), "admin" to adminId.value.toJavaUuid())

    companion object {
        @JvmField @RegisterExtension val database = cc.monomer.control.auth.OAuthHttpPostgres()
    }
}
