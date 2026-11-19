@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package cc.monomer.control.agent

import cc.monomer.agent.api.*
import cc.monomer.agent.application.*
import cc.monomer.semantic.adapter.outbound.postgres.*
import cc.monomer.control.*
import cc.monomer.control.mcp.*
import cc.monomer.control.publicapi.*
import cc.monomer.identity.api.*
import cc.monomer.identity.application.AdminAuthorization
import cc.monomer.identity.application.IdentityAdmissionService
import cc.monomer.identity.application.organization.*
import cc.monomer.identity.application.agent.*
import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.application.grant.*
import cc.monomer.identity.application.me.MeService
import cc.monomer.identity.application.workspace.*
import cc.monomer.identity.domain.model.DeveloperAccessPolicy
import cc.monomer.identity.domain.port.CurrentUserCredentialVerifier
import cc.monomer.identity.domain.port.InvitationDelivery
import cc.monomer.identity.outbound.postgres.*
import io.mockk.*
import java.net.URI
import java.sql.Timestamp
import kotlinx.coroutines.Dispatchers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.http.MediaType
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.transaction.support.TransactionTemplate

class AgentManagementPersistenceIntegrationTest {
    private val now = Instant.parse("2026-09-12T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val organization = Uuid.generateV7()
    private val workspace = Uuid.generateV7()
    private val owner = Uuid.generateV7()
    private val colleague = Uuid.generateV7()
    private val admin = Uuid.generateV7()
    private val audiences = mapOf(ExpectedOAuthResource.AGENT_REST to "http://localhost/v2/workspaces", ExpectedOAuthResource.AGENT_MCP to "http://localhost/mcp")
    private val jdbc get() = NamedParameterJdbcTemplate(database.dataSource)
    private lateinit var setMemberRole: SetWorkspaceMemberRoleUseCase
    private lateinit var rest: WebTestClient
    private lateinit var mcp: WebTestClient

    @Test
    fun `real OAuth and Identity owners share management facts across REST and MCP`() = runBlocking<Unit> {
        database.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                listOf("identity-runtime").forEach { schema ->
                    statement.execute(checkNotNull(javaClass.classLoader.getResource("db/$schema/schema.sql")).readText())
                }
            }
        }
        TransactionTemplate(DataSourceTransactionManager(database.dataSource)).executeWithoutResult { seed() }
        buildClients()
        restCall("/v2/agent/organizations/${organization}/read", "admin-rest", """{"path":"organization"}""").expectStatus().isOk.expectBody().jsonPath("$.body.id").isEqualTo(organization.toString())
        for (invalidJson in listOf("{}", "null", "true", "42", "[{}]", "[]")) {
            val cursor = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(invalidJson.toByteArray())
            restCall("/v2/agent/organizations/${organization}/list", "admin-rest", """{"path":"organization/members","limit":20,"cursor":"$cursor"}""").expectStatus().isBadRequest.expectBody().jsonPath("$.code").isEqualTo("AGENT_REQUEST_INVALID")
            restCall("/v2/workspaces/$workspace/agent/list", "owner-rest", """{"path":"workspace/members","limit":20,"cursor":"$cursor"}""").expectStatus().isBadRequest.expectBody().jsonPath("$.code").isEqualTo("AGENT_REQUEST_INVALID")
            for ((token, arguments) in listOf(
                "admin-mcp" to """{"organizationId":"$organization","path":"organization/members","limit":20,"cursor":"$cursor"}""",
                "owner-mcp" to """{"workspaceId":"$workspace","path":"workspace/members","limit":20,"cursor":"$cursor"}""",
            )) {
                val body = mcpCall(token, "monomer_list", arguments).expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(true).returnResult().responseBody!!
                val mapper = com.fasterxml.jackson.databind.ObjectMapper()
                val problem = mapper.readTree(mapper.readTree(body).at("/result/content/0/text").asText())
                assertEquals(400, problem.path("status").asInt())
                assertEquals("AGENT_REQUEST_INVALID", problem.path("code").asText())
            }
        }
        val workspaceInvoke = "/v2/workspaces/$workspace/agent/invoke"
        restCall(workspaceInvoke, "owner-rest", """{"path":"workspace/members","action":"add-member","input":{"kind":"WORKSPACE_MEMBER_ADD","userId":"$colleague","role":"EXPLORER"}}""").expectStatus().isOk
        assertEquals(2, count("workspace_members"))
        restCall(workspaceInvoke, "owner-rest", """{"path":"workspace/members","action":"add-member","input":{"kind":"WORKSPACE_MEMBER_ADD","userId":"$colleague","role":"EXPLORER"}}""").expectStatus().isOk
        assertEquals(1, jdbc.jdbcTemplate.queryForObject("SELECT count(*) FROM identity_runtime.audit_log WHERE action='workspace.member.add'", Int::class.java))

        restCall(workspaceInvoke, "owner-rest", """{"path":"workspace/members","action":"add-member","input":{"kind":"WORKSPACE_MEMBER_ADD","userId":"$colleague","role":"VIEWER"}}""")
            .expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("WORKSPACE_MEMBER_ROLE_CONFLICT")
        restCall("/v2/workspaces/$workspace/agent/read", "colleague-rest", """{"path":"workspace"}""").expectStatus().isOk.expectBody()
            .jsonPath("$.body.role").isEqualTo("EXPLORER")
            .jsonPath("$.body.resources[?(@.path == 'workspace/lineage/metrics')]").isNotEmpty
            .jsonPath("$.body.resources[?(@.path == 'workspace/members')]").isEmpty
        restCall("/v2/workspaces/$workspace/agent/read", "colleague-rest", """{"path":"workspace/access"}""").expectStatus().isOk.expectBody()
            .jsonPath("$.body.role").isEqualTo("EXPLORER").jsonPath("$.body.granted").doesNotExist()
            .jsonPath("$.body.reportingTimeZone").isEqualTo("UTC")
        jdbc.jdbcTemplate.update("UPDATE identity_runtime.workspaces SET reporting_time_zone = 'Asia/Seoul' WHERE id = '$workspace'")
        restCall("/v2/workspaces/$workspace/agent/read", "colleague-rest", """{"path":"workspace/access"}""").expectStatus().isOk.expectBody()
            .jsonPath("$.body.reportingTimeZone").isEqualTo("Asia/Seoul")
        mcpCall("owner-mcp", "monomer_read", """{"workspaceId":"$workspace","path":"workspace/access"}""").expectStatus().isOk.expectBody()
            .jsonPath("$.result.isError").isEqualTo(false)
            .jsonPath("$.result.structuredContent.body.reportingTimeZone").isEqualTo("Asia/Seoul")
        restCall("/v2/workspaces/$workspace/agent/read", "admin-rest", """{"path":"workspace/access"}""").expectStatus().isNotFound
        restCall("/v2/workspaces/$workspace/agent/read", "owner-rest", """{"path":"workspace/data-access/metrics/$colleague"}""").expectStatus().isBadRequest

        val assignment = """{"workspaceId":"$workspace","path":"workspace/members/$colleague","action":"set-role","input":{"kind":"WORKSPACE_MEMBER_ROLE","role":"EDITOR"}}"""
        mcpCall("owner-mcp", "monomer_invoke", assignment).expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(false)
        restCall("/v2/workspaces/$workspace/agent/read", "owner-rest", """{"path":"workspace/members/$colleague"}""").expectStatus().isOk.expectBody()
            .jsonPath("$.body.role").isEqualTo("EDITOR").jsonPath("$.body.effectiveRole").doesNotExist()
        val authorizationRevision = jdbc.jdbcTemplate.queryForObject("SELECT authorization_revision FROM identity_runtime.workspaces WHERE id='$workspace'", Long::class.java)!!.toString()
        restCall("/v2/workspaces/$workspace/agent/read", "owner-rest", """{"path":"workspace/authorization"}""").expectStatus().isOk.expectBody()
            .jsonPath("$.body.authorizationRevision").isEqualTo(authorizationRevision)
            .jsonPath("$.body.principals").doesNotExist()
            .jsonPath("$.body.members[?(@.userId == '$colleague')].role").isEqualTo(listOf("EDITOR"))
        mcpCall("owner-mcp", "monomer_read", """{"workspaceId":"$workspace","path":"workspace/authorization"}""").expectStatus().isOk.expectBody()
            .jsonPath("$.result.structuredContent.body.authorizationRevision").isEqualTo(authorizationRevision)
            .jsonPath("$.result.structuredContent.body.principals").doesNotExist()
            .jsonPath("$.result.structuredContent.body.members[?(@.userId == '$colleague')].role").isEqualTo(listOf("EDITOR"))
        restCall("/v2/workspaces/$workspace/agent/read", "colleague-rest", """{"path":"workspace/authorization"}""").expectStatus().isForbidden
        mcpCall("owner-mcp", "monomer_invoke", assignment.replace("EDITOR", "VIEWER")).expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(false)
        restCall("/v2/workspaces/$workspace/agent/read", "colleague-rest", """{"path":"workspace"}""").expectStatus().isOk.expectBody()
            .jsonPath("$.body.role").isEqualTo("VIEWER")
            .jsonPath("$.body.resources[?(@.path == 'workspace/lineage/metrics')]").isEmpty
        restCall("/v2/workspaces/$workspace/agent/read", "colleague-rest", """{"path":"workspace/access"}""").expectStatus().isOk.expectBody()
            .jsonPath("$.body.role").isEqualTo("VIEWER").jsonPath("$.availableOperations").doesNotExist()
        restCall("/v2/workspaces/$workspace/agent/list", "colleague-rest", """{"path":"workspace/lineage/metrics","limit":20}""").expectStatus().isForbidden
        mcpCall("owner-mcp", "monomer_invoke", assignment).expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(false)
        for (role in cc.monomer.identity.api.WorkspaceRole.entries) {
            mcpCall("owner-mcp", "monomer_invoke", assignment.replace("EDITOR", role.name))
                .expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(false)
            assertEquals(role.name, jdbc.jdbcTemplate.queryForObject(
                "SELECT role FROM identity_runtime.workspace_members WHERE workspace_id='$workspace' AND user_id='$colleague'", String::class.java))
            restCall("/v2/workspaces/$workspace/agent/read", "colleague-rest", """{"path":"workspace/access"}""")
                .expectStatus().isOk.expectBody().jsonPath("$.body.role").isEqualTo(role.name)
            restCall("/v2/workspaces/$workspace/agent/read", "owner-rest", """{"path":"workspace/members/$colleague"}""")
                .expectStatus().isOk.expectBody()
                .jsonPath("$.body.role").isEqualTo(role.name)
        }
        mcpCall("owner-mcp", "monomer_invoke", assignment).expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(false)
        restCall(workspaceInvoke, "owner-rest", """{"path":"workspace/authorization","action":"set-relation-default","input":{"kind":"WORKSPACE_RELATION_ACCESS_POLICY","allowed":true}}""").expectStatus().isBadRequest

        restCall(workspaceInvoke, "owner-rest", """{"path":"workspace/members/$owner","action":"remove-member"}""").expectStatus().isEqualTo(409)
        assertEquals(2, count("workspace_members"))
        assertEquals(1, jdbc.jdbcTemplate.queryForObject("SELECT count(*) FROM identity_runtime.workspace_members WHERE role='OWNER'", Int::class.java))
        mcpCall("owner-mcp", "monomer_invoke", assignment.replace("EDITOR", "OWNER")).expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(false)
        mcpCall("owner-mcp", "monomer_invoke", """{"workspaceId":"$workspace","path":"workspace/members/$owner","action":"remove-member"}""").expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(false)
        restCall("/v2/workspaces/$workspace/agent/list", "owner-rest", """{"path":"workspace/members","limit":20}""").expectStatus().isNotFound
        assertEquals(1, count("workspace_members"))
        assertEquals(1, jdbc.jdbcTemplate.queryForObject("SELECT count(*) FROM identity_runtime.workspace_members WHERE role='OWNER'", Int::class.java))
        val audit = jdbc.jdbcTemplate.queryForList("SELECT request_id, actor_kind, actor_ref, details->>'surface' AS surface FROM identity_runtime.audit_log WHERE action LIKE 'workspace.member.%' OR action LIKE 'authorization.%'")
        assertTrue(audit.isNotEmpty())
        assertTrue(audit.all { it["request_id"] != null && it["actor_kind"] == "AGENT" && it["actor_ref"] == "https://client.example" })
        assertTrue(audit.any { it["surface"] == "AGENT_REST" })
        assertTrue(audit.any { it["surface"] == "AGENT_MCP" })

        verifyOrganizationWorkspaceManagement()

        mcpCall("admin-mcp", "monomer_read", """{"organizationId":"$organization","path":"organization"}""").expectStatus().isOk.expectBody().jsonPath("$.result.structuredContent.body.id").isEqualTo(organization.toString())
        mcpCall("admin-mcp", "monomer_list", """{"path":"workspaces"}""").expectStatus().isOk.expectBody().jsonPath("$.result.structuredContent.workspaces").isEmpty
        mcpCall("admin-mcp", "monomer_create", """{"organizationId":"$organization","path":"organization/invitations","input":{"kind":"ORGANIZATION_INVITATION_CREATE","email":"invitee@example.test"}}""").expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(true)
        assertEquals(1, jdbc.jdbcTemplate.queryForObject("SELECT count(*) FROM identity_runtime.organization_membership_invitations", Int::class.java))
        mcpCall("admin-mcp", "monomer_list", """{"organizationId":"$organization","path":"organization/invitations","limit":20}""").expectStatus().isOk.expectBody().jsonPath("$.result.structuredContent.items[0].email").isEqualTo("invitee@example.test")
        restCall("/v2/workspaces/$workspace/agent/read", "admin-rest", """{"path":"workspace/authorization"}""").expectStatus().isNotFound
        restCall("/v2/workspaces/$workspace/agent/list", "colleague-mcp", """{"path":"workspace/members","limit":20}""").expectStatus().isUnauthorized
        val secondOrganization = Uuid.generateV7()
        TransactionTemplate(DataSourceTransactionManager(database.dataSource)).executeWithoutResult {
            jdbc.update("INSERT INTO identity_runtime.organizations(id,display_name) VALUES(:id,'Second team')", mapOf("id" to secondOrganization.toJavaUuid()))
            jdbc.update("INSERT INTO identity_runtime.organization_members(organization_id,user_id,role) VALUES(:organization,:admin,'ADMIN')", mapOf("organization" to secondOrganization.toJavaUuid(), "admin" to admin.toJavaUuid()))
        }
        restCall("/v2/agent/organizations/$secondOrganization/read", "admin-rest", """{"path":"organization"}""").expectStatus().isOk.expectBody().jsonPath("$.body.id").isEqualTo(secondOrganization.toString())
        restCall("/v2/agent/organizations/$secondOrganization/read", "owner-rest", """{"path":"organization"}""").expectStatus().isNotFound
        restCall("/v2/agent/organizations/${Uuid.generateV7()}/read", "admin-rest", """{"path":"organization"}""").expectStatus().isNotFound
        restCall("/v2/agent/organizations/$secondOrganization/create", "admin-rest", """{"path":"organization/invitations","input":{"kind":"ORGANIZATION_INVITATION_CREATE","email":"second@example.test"}}""").expectStatus().isEqualTo(503)
        val secondInvitation = jdbc.queryForObject("SELECT id FROM identity_runtime.organization_membership_invitations WHERE organization_id=:organization", mapOf("organization" to secondOrganization.toJavaUuid()), java.util.UUID::class.java)!!
        restCall("/v2/agent/organizations/$organization/invoke", "admin-rest", """{"path":"organization/invitations/$secondInvitation","action":"cancel"}""").expectStatus().isNotFound
        restCall("/v2/agent/organizations/$secondOrganization/invoke", "admin-rest", """{"path":"organization/invitations/$secondInvitation","action":"cancel"}""").expectStatus().isOk
        val auditOrganizations = jdbc.jdbcTemplate.queryForList("SELECT DISTINCT organization_id FROM identity_runtime.audit_log WHERE resource_id='$secondInvitation'", java.util.UUID::class.java)
        assertEquals(listOf(secondOrganization.toJavaUuid()), auditOrganizations)
    }

    private suspend fun verifyOrganizationWorkspaceManagement() {
        val organizationRoute = "/v2/agent/organizations/$organization"
        val collection = "organization/workspaces/$workspace/members"
        fun command(user: Uuid, role: String) = """{"path":"$collection/$user","action":"set-role","input":{"kind":"WORKSPACE_MEMBER_ROLE","role":"$role"}}"""
        fun revision() = jdbc.jdbcTemplate.queryForObject("SELECT authorization_revision FROM identity_runtime.workspaces WHERE id='$workspace'", Long::class.java)!!
        fun roleAuditCount() = jdbc.jdbcTemplate.queryForObject("SELECT count(*) FROM identity_runtime.audit_log WHERE action='workspace.member.role.set' AND workspace_id='$workspace'", Int::class.java)!!
        restCall("$organizationRoute/list", "admin-rest", """{"path":"organization/workspaces","limit":1}""").expectStatus().isOk.expectBody()
            .jsonPath("$.items[0].path").isEqualTo(collection)
        restCall("$organizationRoute/list", "admin-rest", """{"path":"$collection","limit":20}""").expectStatus().isOk.expectBody()
            .jsonPath("$.items[0].role").isEqualTo("OWNER")
            .jsonPath("$.items[0].effectiveRole").doesNotExist()
        for (token in listOf("owner-rest", "colleague-rest")) {
            restCall("$organizationRoute/list", token, """{"path":"organization/workspaces","limit":20}""").expectStatus().isForbidden
            restCall("$organizationRoute/list", token, """{"path":"$collection","limit":20}""").expectStatus().isForbidden
            restCall("$organizationRoute/invoke", token, command(colleague, "EDITOR")).expectStatus().isForbidden
        }
        val initialRevision = revision()
        restCall("$organizationRoute/invoke", "admin-rest", command(colleague, "EDITOR")).expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("LAST_WORKSPACE_OWNER")
        assertEquals(initialRevision, revision())
        // An Admin admits an Organization member with any explicit Workspace role through the same set-role use case.
        val beforeAdd = roleAuditCount()
        restCall("$organizationRoute/invoke", "admin-rest", command(owner, "VIEWER")).expectStatus().isOk
        restCall("$organizationRoute/read", "admin-rest", """{"path":"$collection/$owner"}""").expectStatus().isOk.expectBody()
            .jsonPath("$.body.role").isEqualTo("VIEWER").jsonPath("$.availableOperations[0].action").isEqualTo("set-role")
        assertEquals(beforeAdd, roleAuditCount())
        assertEquals(1, jdbc.jdbcTemplate.queryForObject("SELECT count(*) FROM identity_runtime.audit_log WHERE action='workspace.member.add' AND workspace_id='$workspace' AND resource_id='$owner' AND details->>'role'='VIEWER' AND actor_kind='AGENT'", Int::class.java))
        val beforeAgent = revision()
        val beforeAudit = roleAuditCount()
        restCall("$organizationRoute/invoke", "admin-rest", command(owner, "EDITOR")).expectStatus().isOk
        assertEquals(beforeAgent + 1, revision())
        assertEquals(beforeAudit + 1, roleAuditCount())
        restCall("$organizationRoute/invoke", "admin-rest", command(owner, "EDITOR")).expectStatus().isOk
        assertEquals(beforeAgent + 1, revision())
        assertEquals(beforeAudit + 1, roleAuditCount())
        val webResolver = cc.monomer.control.auth.inbound.graphql.GrantMutationsResolver(
            mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), setMemberRole, mockk(),
        )
        val webAuthentication = org.springframework.security.authentication.UsernamePasswordAuthenticationToken(admin.toString(), null, emptyList())
        assertTrue(mono { webResolver.setWorkspaceMemberRole(workspace.toString(), owner.toString(), "VIEWER") }
            .contextWrite(org.springframework.security.core.context.ReactiveSecurityContextHolder.withAuthentication(webAuthentication)).awaitSingle())
        assertEquals(beforeAgent + 2, revision())
        assertEquals(beforeAudit + 2, roleAuditCount())
        val events = jdbc.jdbcTemplate.queryForList("SELECT actor_kind, details->>'role' AS role FROM identity_runtime.audit_log WHERE action='workspace.member.role.set' AND resource_id='$owner'")
        assertTrue(events.any { it["actor_kind"] == "AGENT" && it["role"] == "EDITOR" })
        assertTrue(events.any { it["actor_kind"] == null && it["role"] == "VIEWER" })
        mcpCall("admin-mcp", "monomer_invoke", command(owner, "EXPLORER").replace("{\"path\":", "{\"organizationId\":\"$organization\",\"path\":")).expectStatus().isOk.expectBody().jsonPath("$.result.isError").isEqualTo(false)
        mcpCall("admin-mcp", "monomer_read", """{"organizationId":"$organization","path":"$collection/$owner"}""").expectStatus().isOk.expectBody()
            .jsonPath("$.result.structuredContent.body.role").isEqualTo("EXPLORER")
        assertEquals(0, jdbc.jdbcTemplate.queryForObject("SELECT count(*) FROM identity_runtime.workspace_members WHERE user_id='$admin'", Int::class.java))
        val foreignOrganization = Uuid.generateV7()
        val foreignWorkspace = Uuid.generateV7()
        val foreignUser = Uuid.generateV7()
        TransactionTemplate(DataSourceTransactionManager(database.dataSource)).executeWithoutResult {
            jdbc.jdbcTemplate.update("INSERT INTO identity_runtime.organizations(id,display_name) VALUES('$foreignOrganization','Foreign')")
            jdbc.jdbcTemplate.update("INSERT INTO identity_runtime.principals(id,kind,display_name) VALUES('$foreignUser','user','Foreign user')")
            jdbc.jdbcTemplate.update("INSERT INTO identity_runtime.users(id,primary_email) VALUES('$foreignUser','foreign@example.test')")
            jdbc.jdbcTemplate.update("INSERT INTO identity_runtime.organization_members(organization_id,user_id,role) VALUES('$foreignOrganization','$foreignUser','ADMIN'),('$foreignOrganization','$admin','ADMIN')")
            jdbc.jdbcTemplate.update("INSERT INTO identity_runtime.workspaces(id,organization_id,display_name) VALUES('$foreignWorkspace','$foreignOrganization','Foreign')")
            jdbc.jdbcTemplate.update("INSERT INTO identity_runtime.workspace_members(workspace_id,user_id,role) VALUES('$foreignWorkspace','$foreignUser','OWNER')")
        }
        // The actor administers both Organizations: the explicitly selected Organization still bounds the target.
        restCall("$organizationRoute/invoke", "admin-rest", command(foreignUser, "OWNER")).expectStatus().isEqualTo(409)
        restCall("$organizationRoute/invoke", "admin-rest", command(foreignUser, "VIEWER")).expectStatus().isEqualTo(409)
        restCall("$organizationRoute/invoke", "admin-rest", command(foreignUser, "EDITOR").replace(workspace.toString(), foreignWorkspace.toString())).expectStatus().isNotFound
        restCall("$organizationRoute/list", "admin-rest", """{"path":"organization/workspaces/$foreignWorkspace/members","limit":20}""").expectStatus().isNotFound
        restCall("$organizationRoute/read", "admin-rest", """{"path":"organization/workspaces/$foreignWorkspace/members/$foreignUser"}""").expectStatus().isNotFound
        assertEquals("OWNER", jdbc.jdbcTemplate.queryForObject("SELECT role FROM identity_runtime.workspace_members WHERE workspace_id='$foreignWorkspace'", String::class.java))
        // An Admin removes a direct member through the same Admin-or-Owner use case as Web removeWorkspaceMember.
        fun remove(user: Uuid) = """{"path":"$collection/$user","action":"remove-member"}"""
        for (token in listOf("owner-rest", "colleague-rest")) {
            restCall("$organizationRoute/invoke", token, remove(owner)).expectStatus().isForbidden
        }
        restCall("/v2/agent/organizations/$foreignOrganization/invoke", "admin-rest", remove(owner)).expectStatus().isNotFound
        restCall("$organizationRoute/invoke", "admin-rest", remove(foreignUser).replace(workspace.toString(), foreignWorkspace.toString())).expectStatus().isNotFound
        assertEquals(1, jdbc.jdbcTemplate.queryForObject("SELECT count(*) FROM identity_runtime.workspace_members WHERE workspace_id='$foreignWorkspace'", Int::class.java))
        val owners = jdbc.jdbcTemplate.queryForList("SELECT user_id FROM identity_runtime.workspace_members WHERE workspace_id='$workspace' AND role='OWNER'", java.util.UUID::class.java)
        assertEquals(1, owners.size)
        restCall("$organizationRoute/invoke", "admin-rest", remove(checkNotNull(owners.single()).toKotlinUuid())).expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("LAST_WORKSPACE_OWNER")
        restCall("$organizationRoute/read", "admin-rest", """{"path":"$collection/$owner"}""").expectStatus().isOk.expectBody()
            .jsonPath("$.availableOperations[1].action").isEqualTo("remove-member")
        restCall("$organizationRoute/invoke", "admin-rest", remove(owner)).expectStatus().isOk.expectBody()
            .jsonPath("$.result.kind").isEqualTo("COLLECTION").jsonPath("$.result.path").isEqualTo(collection)
        assertEquals(0, jdbc.jdbcTemplate.queryForObject("SELECT count(*) FROM identity_runtime.workspace_members WHERE workspace_id='$workspace' AND user_id='$owner'", Int::class.java))
        assertEquals(1, jdbc.jdbcTemplate.queryForObject("SELECT count(*) FROM identity_runtime.audit_log WHERE action='workspace.member.remove' AND workspace_id='$workspace' AND resource_id='$owner' AND actor_id='$admin' AND actor_kind='AGENT'", Int::class.java))
        assertEquals(0, jdbc.jdbcTemplate.queryForObject("SELECT count(*) FROM identity_runtime.workspace_members WHERE user_id='$admin'", Int::class.java))
    }

    private fun buildClients() {
        val manager = DataSourceTransactionManager(database.dataSource)
        val transactions = SpringTransactionRunner(TransactionTemplate(manager), TransactionTemplate(manager).apply { isReadOnly = true; isolationLevel = java.sql.Connection.TRANSACTION_REPEATABLE_READ }, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        val snapshot = SpringConsistentSnapshotTransactionRunner(TransactionTemplate(manager).apply { isolationLevel = java.sql.Connection.TRANSACTION_REPEATABLE_READ }, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        val policy = DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.OPEN, emptySet())
        val verifier = mockk<CurrentUserCredentialVerifier>()
        every { verifier.verifyCurrentCredential(any()) } returns null
        val oauth = AgentOAuthService(PostgresAgentOAuthRepository(jdbc, verifier, policy), transactions, mockk(), clock, audiences)
        val identity = IdentityAdmissionService(PostgresCanonicalIdentityAdmissionSnapshot(jdbc, transactions, verifier, AgentOAuthTokenHasher(), clock, audiences, policy))
        val admission = ControlAdmissionFacade(identity, clock)
        val organizationQuery = PostgresOrganizationQuery(jdbc, transactions)
        val organizations = PostgresOrganizationRepository(jdbc)
        val organizationGovernance = PostgresOrganizationGovernanceOperations(jdbc, transactions)
        val workspaceGovernance = PostgresWorkspaceAuthorizationOperations(jdbc, transactions)
        val assignments = PostgresAuthorizationAssignmentRepository(jdbc)
        val members = PostgresWorkspaceMemberRepository(jdbc)
        val audits = PostgresAuditLogRepository(jdbc)
        val factory = AuditEventFactory(clock)
        val runner = OwnerWorkspaceMutationRunner(transactions, assignments)
        val delivery = mockk<InvitationDelivery>()
        coEvery { delivery.send(any(), any(), any(), any(), any()) } returns false
        setMemberRole = SetWorkspaceMemberRoleService(runner, assignments, audits, factory, members, clock)
        val management = AgentManagementOwnerAdapter(oauth, admission, MeService(PostgresUserQuery(jdbc, transactions), organizationQuery), ManagedWorkspacesService(organizationGovernance), OrganizationMembersService(organizationGovernance),
            OrganizationInvitationService(transactions, PostgresOrganizationInvitationRepository(jdbc), organizations, members, AdminAuthorization(organizations), audits, factory, delivery, clock),
            WorkspaceMembersService(workspaceGovernance), WorkspaceMemberCandidatesService(workspaceGovernance), WorkspaceAuthorizationAssignmentsService(workspaceGovernance),
            AddWorkspaceMemberService(runner, assignments, members, audits, factory, clock), RemoveWorkspaceMemberService(runner, members, audits, factory),
            setMemberRole, PostgresWorkspaceReportingTimeZoneQuery(jdbc, transactions), mockk(), clock)
        val registry = AgentResourceRouteRegistry(mockk(), mockk(), mockk(), mockk(), mockk(), null, emptyList(), mockk(), management)
        val ports = AgentWorkspaceHttpPorts(AgentWorkspaceCallerAuthenticator(AgentWorkspaceApplicationComposition().agentWorkspaceAdmissionPort(admission, mockk(), mockk(), mockk())), registry, AgentWorkspaceContractDecoder())
        val restRouter = AgentWorkspacePublicApiRouter(ports, "http://localhost/.well-known/oauth-protected-resource/v2/workspaces", ControlPublicApiProperties(URI("http://localhost")),
            object : AgentWorkspaceTransportCredentialResolver {
                override suspend fun resolve(request: org.springframework.web.reactive.function.server.ServerRequest) = request.headers().firstHeader("Authorization")?.removePrefix("Bearer ")?.let { AgentWorkspaceTransportCredentialResolution.Resolved(PresentedCredential(it)) } ?: AgentWorkspaceTransportCredentialResolution.Missing
            }, PublicControlContractValidator(), oauth)
        rest = WebTestClient.bindToRouterFunction(restRouter.routes().and(restRouter.organizationRoutes(oauth, management))).build()
        mcp = WebTestClient.bindToRouterFunction(McpRouter(ports, oauth, McpTransportProperties("http://localhost/.well-known/oauth-protected-resource/mcp", emptySet()), MonomerDocsReader("http://localhost"), management, cc.monomer.control.publicapi.ControlPublicApiProperties(java.net.URI("http://127.0.0.1:3100")), dashboardViewHtml = "<!doctype html><title>Fixture view</title>", clock = java.time.Clock.systemUTC()).routes()).build()
    }

    @Test
    fun `identity mutation joins validation connection and rolls back with outer snapshot`() = runBlocking<Unit> {
        val manager = DataSourceTransactionManager(database.dataSource)
        val snapshot = SpringConsistentSnapshotTransactionRunner(TransactionTemplate(manager).apply { isolationLevel = java.sql.Connection.TRANSACTION_REPEATABLE_READ }, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        val mutations = SpringTransactionRunner(TransactionTemplate(manager), TransactionTemplate(manager).apply { isReadOnly = true }, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        jdbc.jdbcTemplate.execute("CREATE TABLE transaction_boundary_probe(id uuid PRIMARY KEY)")
        val probe = Uuid.generateV7().toJavaUuid()
        val abort = IllegalStateException("rollback probe")
        assertEquals(abort.message, assertFailsWith<IllegalStateException> {
            snapshot.run {
                val validationConnection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(database.dataSource)
                mutations.run {
                    assertSame(validationConnection, org.springframework.jdbc.datasource.DataSourceUtils.getConnection(database.dataSource))
                    assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ, validationConnection.transactionIsolation)
                    assertFalse(validationConnection.autoCommit)
                    jdbc.update("INSERT INTO transaction_boundary_probe(id) VALUES(:id)", mapOf("id" to probe))
                }
                assertEquals(1, jdbc.jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_boundary_probe", Int::class.java))
                throw abort
            }
        }.message)
        assertEquals(0, jdbc.jdbcTemplate.queryForObject("SELECT count(*) FROM transaction_boundary_probe", Int::class.java))
    }

    private fun seed() {
        jdbc.update("INSERT INTO identity_runtime.organizations(id,display_name) VALUES(:id,'Management test')", mapOf("id" to organization.toJavaUuid()))
        listOf(owner, colleague, admin).forEach { user ->
            val parameters = mapOf("id" to user.toJavaUuid(), "organization" to organization.toJavaUuid(), "email" to "$user@example.test", "role" to if (user == admin) "ADMIN" else "MEMBER")
            jdbc.update("INSERT INTO identity_runtime.principals(id,kind,display_name) VALUES(:id,'user','Fixture user')", parameters)
            jdbc.update("INSERT INTO identity_runtime.users(id,primary_email) VALUES(:id,:email)", parameters)
            jdbc.update("INSERT INTO identity_runtime.organization_members(organization_id,user_id,role) VALUES(:organization,:id,:role)", parameters)
        }
        jdbc.update("INSERT INTO identity_runtime.workspaces(id,organization_id,display_name) VALUES(:workspace,:organization,'Management workspace')", mapOf("workspace" to workspace.toJavaUuid(), "organization" to organization.toJavaUuid()))
        jdbc.update("INSERT INTO identity_runtime.workspace_members(workspace_id,user_id,role) VALUES(:workspace,:user,'OWNER')", mapOf("workspace" to workspace.toJavaUuid(), "user" to owner.toJavaUuid()))
        listOf("owner" to owner, "colleague" to colleague, "admin" to admin).forEach { (label, user) -> audiences.forEach { (surface, audience) ->
            val token = "$label-${if (surface == ExpectedOAuthResource.AGENT_REST) "rest" else "mcp"}"
            val params = mapOf("grant" to Uuid.generateV7().toJavaUuid(), "user" to user.toJavaUuid(), "organization" to organization.toJavaUuid(), "audience" to audience,
                "tokenId" to Uuid.generateV7().toJavaUuid(), "hash" to AgentOAuthTokenHasher().hash(token), "now" to Timestamp.from(now), "expiry" to Timestamp.from(now.plusSeconds(3600)))
            jdbc.update("INSERT INTO identity_runtime.agent_oauth_grants(id,owner_user_id,oauth_client_id,resource_audience,scopes,issued_at) VALUES(:grant,:user,'https://client.example',:audience,ARRAY['agent.workspace'],:now)", params)
            jdbc.update("INSERT INTO identity_runtime.agent_oauth_access_tokens(id,grant_id,token_hash,issued_at,expires_at) VALUES(:tokenId,:grant,:hash,:now,:expiry)", params)
        } }
    }
    private fun restCall(uri: String, token: String, body: String) = rest.post().uri(uri).headers { it.setBearerAuth(token) }.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
    private fun mcpCall(token: String, tool: String, arguments: String) = mcp.post().uri("/mcp").headers { it.setBearerAuth(token) }.contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
        .bodyValue("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""").exchange()
    private fun count(table: String) = jdbc.jdbcTemplate.queryForObject("SELECT count(*) FROM identity_runtime.$table", Int::class.java)
    companion object { @JvmField @RegisterExtension val database = EphemeralPostgresAuthoritySupport }
}
