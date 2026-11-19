@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorProductType
import cc.monomer.connector.api.ConnectorWorkspaceReadUseCase
import cc.monomer.connector.api.CurrentConnectorRead
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.application.DashboardQueryPlanningPort
import cc.monomer.dashboard.application.DashboardQueryResolution
import cc.monomer.dashboard.domain.DashboardBindValue
import cc.monomer.dashboard.domain.DashboardNamedQuery
import cc.monomer.dashboard.domain.DashboardResolvedQuery
import cc.monomer.dashboard.domain.DashboardSqlTemplate
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import tools.jackson.databind.ObjectMapper

class DashboardSqlQueryPlanningAdapterTest {
    @Test
    fun `single and batch choose identical engine SQL and names using workspace scoped Connector reads`() = runTest {
        val workspaceId = WorkspaceId.generate()
        val deadline = Instant.now().plusSeconds(10)
        val connectorRead = mockk<ConnectorWorkspaceReadUseCase>()
        val connectors = ConnectorProductType.entries.map { type ->
            CurrentConnectorRead(ConnectorId.generate(), workspaceId, type, "fixture", mockk(), Instant.EPOCH, Instant.EPOCH)
        }
        connectors.forEach { connector ->
            coEvery { connectorRead.findByWorkspaceAndId(workspaceId, connector.connectorId) } returns connector
        }
        val missingConnectorId = ConnectorId.generate()
        coEvery { connectorRead.findByWorkspaceAndId(workspaceId, missingConnectorId) } returns null
        coEvery { connectorRead.findByWorkspaceAndIds(workspaceId, any()) } coAnswers {
            val requested = secondArg<Set<ConnectorId>>()
            connectors.filter { it.connectorId in requested }
        }
        val adapter = adapter(connectorRead)
        val commands = connectors.map { connector ->
            command(workspaceId, connector.connectorId, "SELECT {{a}}tail, '{{a}}', '?' -- MONOMER_P", null)
        } + command(workspaceId, missingConnectorId, "SELECT {{a}}", null)
        val single = commands.map { adapter.resolve(it) }
        val batch = adapter.resolveBatch(commands.map { it.copy(deadline = deadline) })
        assertEquals(single, batch)
        single.dropLast(1).zip(connectors).forEach { (result, connector) ->
            val resolved = assertIs<DashboardQueryResolution.Resolved>(result)
            if (connector.connectorType == ConnectorProductType.DATABRICKS) {
                assertEquals("SELECT  :monomer_p_1 tail, ' :monomer_p_2 ', '?' -- MONOMER_P", resolved.sql)
                assertEquals(listOf("monomer_p_1", "monomer_p_2"), resolved.parameterNames)
            } else {
                assertEquals("SELECT ?tail, '?', '?' -- MONOMER_P", resolved.sql)
                assertEquals(listOf("", ""), resolved.parameterNames)
            }
        }
        assertEquals("CONNECTOR_NOT_FOUND", assertIs<DashboardQueryResolution.Rejected>(single.last()).code)
        commands.forEach { command -> coVerify(exactly = 1) { connectorRead.findByWorkspaceAndId(workspaceId, command.query.connectorId) } }
        coVerify(exactly = 1) { connectorRead.findByWorkspaceAndIds(workspaceId, commands.map { it.query.connectorId }.toSet()) }
    }

    @Test
    fun `expired batch deadline prevents Connector reads`() = runTest {
        val connectorRead = mockk<ConnectorWorkspaceReadUseCase>()
        val command = command(WorkspaceId.generate(), ConnectorId.generate(), "SELECT {{a}}", Instant.now().minusSeconds(1))
        assertFailsWith<java.util.concurrent.TimeoutException> { adapter(connectorRead).resolveBatch(listOf(command)) }
        coVerify(exactly = 0) { connectorRead.findByWorkspaceAndIds(command.workspaceId, any()) }
    }

    @Test
    fun `named SQL amplification is rejected before allocation at the existing execution size limit`() = runTest {
        val workspace = WorkspaceId.generate()
        val fixtureConnectorId = ConnectorId.generate()
        val connector = CurrentConnectorRead(fixtureConnectorId, workspace, ConnectorProductType.DATABRICKS, "fixture", mockk(), Instant.EPOCH, Instant.EPOCH)
        val connectorRead = mockk<ConnectorWorkspaceReadUseCase>()
        coEvery { connectorRead.findByWorkspaceAndId(workspace, fixtureConnectorId) } returns connector
        coEvery { connectorRead.findByWorkspaceAndIds(workspace, setOf(fixtureConnectorId)) } returns listOf(connector)
        val source = "SELECT monomer_p" + "_".repeat(100_000) + "," + "{{a}},".repeat(20_000)
        val command = command(workspace, fixtureConnectorId, source, null)
        val template = (command.query as DashboardNamedQuery.Sql).template
        assertEquals(2_000_468_911L, template.namedSqlUtf8ByteLength())
        val expected = DashboardQueryResolution.Rejected("RAW_SQL_VALIDATION_REJECTED", "Databricks Raw SQL exceeds the maximum UTF-8 byte length")
        val adapter = adapter(connectorRead)
        assertEquals(expected, adapter.resolve(command))
        assertEquals(listOf(expected), adapter.resolveBatch(listOf(command.copy(deadline = Instant.now().plusSeconds(10)))))
        coEvery { connectorRead.findByWorkspaceAndId(workspace, fixtureConnectorId) } returns connector.copy(connectorType = ConnectorProductType.POSTGRESQL)
        val positional = assertIs<DashboardQueryResolution.Resolved>(adapter.resolve(command))
        assertEquals(template.executableSql, positional.sql)
        assertEquals(List(20_000) { "" }, positional.parameterNames)
    }

    private fun adapter(connectorRead: ConnectorWorkspaceReadUseCase): DashboardQueryPlanningAdapter {
        val planningConnection = mockk<java.sql.Connection>(relaxed = true)
        return DashboardQueryPlanningAdapter(
            dataSource = mockk { every { connection } returns planningConnection },
            objectMapper = ObjectMapper(),
            semanticQueryUseCase = mockk(),
            controlAdmissionFacade = mockk(),
            consistentSnapshotTransactionRunner = object : ConsistentSnapshotTransactionRunner {
                override suspend fun <T> run(action: suspend () -> T): T = action()
            },
            planningTransactionOperations = mockk(),
            blockingDispatcher = Dispatchers.Unconfined,
            pageAccess = mockk(),
            pages = mockk(),
            connectorRead = connectorRead,
        )
    }

    private fun command(workspaceId: WorkspaceId, connectorId: ConnectorId, sql: String, deadline: Instant?): DashboardQueryPlanningPort.ResolveQueryCommand {
        val template = DashboardSqlTemplate.compile(sql, setOf("a"), "sql")
        return DashboardQueryPlanningPort.ResolveQueryCommand(
            workspaceId = workspaceId,
            query = DashboardNamedQuery.Sql("query", connectorId, template, emptyMap(), listOf("value"), 100),
            resolvedQuery = DashboardResolvedQuery.Sql(List(template.aliasesInAppearanceOrder.size) { DashboardBindValue.Text("value") }, 100),
            credential = null,
            expectedOAuthResource = null,
            deadline = deadline,
            dashboardId = DashboardId.generate(),
            readerEvidence = null,
        )
    }
}
