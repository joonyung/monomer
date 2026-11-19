@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.connector.api.ConnectorId
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.dashboard.application.DashboardQueryPlanningPort
import cc.monomer.dashboard.application.DashboardQueryResolution
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.semantic.api.SemanticRenderResult
import cc.monomer.semantic.api.SemanticResultColumnKey
import cc.monomer.semantic.api.SemanticRenderedOutputColumn
import cc.monomer.semantic.api.SemanticRenderedOutputColumnSource
import cc.monomer.semantic.api.SemanticSqlDialect
import cc.monomer.semantic.api.SemanticSqlRender
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.application.SemanticQueryUseCase
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import tools.jackson.databind.ObjectMapper

class DashboardQueryPlanningAdapterTest {
    @Test
    fun `batch retains invalid input positions and calls semantic owner once outside outer snapshot`() = runTest {
        val workspace = WorkspaceId.generate()
        val connector = ConnectorId.generate()
        val deadline = java.time.Instant.now().plusSeconds(10)
        val requests = slot<List<cc.monomer.semantic.api.SemanticRenderRequest>>()
        val owner = mockk<SemanticQueryUseCase>()
        coEvery { owner.renderBatch(capture(requests), deadline) } returns listOf(
            SemanticRenderResult.NotFoundOrOutsideWorkspace,
            SemanticRenderResult.Unsupported(setOf(cc.monomer.semantic.api.SemanticUnsupportedCapability.UNSUPPORTED_DIALECT)),
        )
        val adapter = DashboardQueryPlanningAdapter(mockk(), ObjectMapper(), owner, mockk(), mockk(), mockk(), kotlinx.coroutines.Dispatchers.Unconfined, pageAccess = mockk(), pages = mockk(), connectorRead = mockk())
        val query = Json.parseToJsonElement(
            """{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":1000}""",
        ).jsonObject
        val command = semanticPlanningCommand(workspace, connector, query, null, null, deadline, cc.monomer.dashboard.api.DashboardId.generate(), null)
        val results = adapter.resolveBatch(listOf(command, command.copy(resolvedQuery = cc.monomer.dashboard.domain.DashboardResolvedQuery.Semantic(Json.parseToJsonElement("{}").jsonObject)), command))
        assertEquals(listOf("SEMANTIC_QUERY_NOT_FOUND", "INVALID_SEMANTIC_QUERY", "UNSUPPORTED_SEMANTIC_QUERY"), results.map { (it as DashboardQueryResolution.Rejected).code })
        assertEquals(2, requests.captured.size)
        io.mockk.coVerify(exactly = 1) { owner.renderBatch(any(), deadline) }
    }

    @Test
    fun `agent OAuth resource and authenticated actor are preserved by credential validation`() = runTest {
        val workspaceId = WorkspaceId.parse("019e0000-0000-7000-8000-0000000000a1")
        val connectorId = ConnectorId(Uuid.parse("019e0000-0000-7000-8000-0000000000b1"))
        var snapshotActive = false
        val semanticQuery = mockk<SemanticQueryUseCase>()
        coEvery { semanticQuery.render(any()) } coAnswers {
            assertTrue(snapshotActive)
            SemanticRenderResult.Rendered(
            SemanticSqlRender(
                workspaceId = workspaceId,
                dialect = SemanticSqlDialect.TRINO,
                sql = "SELECT SUM(order_total) FROM hive.analytics.orders",
                reportingTimeZone = java.time.ZoneId.of("UTC"),
                outputColumns = listOf(
                    SemanticRenderedOutputColumn(
                        source = SemanticRenderedOutputColumnSource.Metric(
                            MetricId(Uuid.parse("019e0000-0000-7000-8000-0000000000c1")),
                        ),
                        name = SemanticResultColumnKey("metric.order_revenue"),
                        sqlName = "__monomer_result_0",
                        display = null,
                    ),
                ),
                partitionCheck = cc.monomer.semantic.api.SemanticPartitionCheck(false, emptyList()),
            ),
        )
        }
        val admissionRequest = slot<cc.monomer.control.WorkspaceRoleAdmissionRequest>()
        val admission = mockk<ControlAdmissionFacade>()
        coEvery { admission.authorizeWorkspaceMembership(any()) } returns ControlAdmissionDecision.Admitted(mockk())
        coEvery { admission.authorizeWorkspaceRole(capture(admissionRequest)) } coAnswers {
            assertTrue(snapshotActive)
            ControlAdmissionDecision.Admitted(mockk {
                io.mockk.every { actor } returns cc.monomer.identity.api.CanonicalPrincipal(cc.monomer.identity.api.PrincipalId.generate(), cc.monomer.identity.api.PrincipalKind.USER)
                io.mockk.every { credentialKind } returns cc.monomer.identity.api.CredentialKind.AGENT_OAUTH
                io.mockk.every { credentialSurface } returns cc.monomer.identity.api.CredentialSurface.AGENT
                io.mockk.every { agentClientId } returns cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE
            })
        }
        val planningConnection = mockk<java.sql.Connection>(relaxed = true)
        val planningDataSource = mockk<javax.sql.DataSource> {
            io.mockk.every { connection } returns planningConnection
        }
        val adapter = DashboardQueryPlanningAdapter(
            dataSource = planningDataSource,
            objectMapper = ObjectMapper(),
            semanticQueryUseCase = semanticQuery,
            controlAdmissionFacade = admission,
            planningTransactionOperations = io.mockk.mockk(),
            consistentSnapshotTransactionRunner = object : ConsistentSnapshotTransactionRunner {
                override suspend fun <T> run(action: suspend () -> T): T {
                    snapshotActive = true
                    return try { action() } finally { snapshotActive = false }
                }
            }, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined, pageAccess = mockk(), pages = mockk(), connectorRead = mockk())
        val query = Json.parseToJsonElement(
            """{"schemaVersion":4,"metrics":["order_revenue"],"groupBys":[],"metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":1000}""",
        ).jsonObject

        val result = adapter.resolve(
            semanticPlanningCommand(
                workspaceId = workspaceId,
                connectorId = connectorId,
                query = query,
                credential = PresentedCredential("agent-token"),
                expectedOAuthResource = ExpectedOAuthResource.AGENT_REST,
                deadline = null,
                dashboardId = cc.monomer.dashboard.api.DashboardId.generate(),
                readerEvidence = null,
            ),
        )

        assertIs<DashboardQueryResolution.Resolved>(result)
        assertEquals(listOf("metric.order_revenue"), result.outputColumns)
        assertEquals(ExpectedOAuthResource.AGENT_REST, admissionRequest.captured.expectedOAuthResource)

        val scheduled = adapter.resolve(semanticPlanningCommand(
            workspaceId, connectorId, query, null, null, null, cc.monomer.dashboard.api.DashboardId.generate(), null,
        ))
        assertIs<DashboardQueryResolution.Resolved>(scheduled)
        io.mockk.verify(exactly = 1) { planningConnection.prepareStatement("SELECT set_config('statement_timeout', ?, true), set_config('lock_timeout', ?, true)") }
        assertEquals(null, scheduled.actorId)
        assertEquals(null, scheduled.credentialSurface)
        io.mockk.coVerify(exactly = 1) { admission.authorizeWorkspaceRole(any()) }
        coEvery { semanticQuery.render(any()) } returns SemanticRenderResult.Rendered(mockk {
            io.mockk.every { this@mockk.workspaceId } returns WorkspaceId.generate()
        })
        val outsideWorkspace = adapter.resolve(semanticPlanningCommand(
            workspaceId, connectorId, query, null, null, null, cc.monomer.dashboard.api.DashboardId.generate(), null,
        ))
        assertEquals("DASHBOARD_QUERY_UNAUTHORIZED", assertIs<DashboardQueryResolution.Rejected>(outsideWorkspace).code)

    }

}
