@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.connector.api.ConnectorExecutionAccessInOwnerTransactionPort
import cc.monomer.connector.api.ConnectorExecutionAccessResult
import cc.monomer.connector.api.ConnectorExecutionBinding
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ContentDigest
import cc.monomer.connector.api.CredentialEnvelopeReference
import cc.monomer.connector.api.CredentialVersionId
import cc.monomer.connector.api.ConnectorRuntimeBindingProjectionPort
import cc.monomer.dashboard.api.QueryRunId
import cc.monomer.dashboard.domain.DashboardBindValue
import cc.monomer.execution.api.QueryParameterValue
import cc.monomer.dashboard.application.DashboardExecutionAdmissionPort
import cc.monomer.execution.adapter.inbound.grpc.ProtoRawSqlRunSpecFactory
import cc.monomer.execution.api.ConnectorReference
import cc.monomer.execution.api.ConnectorRuntimeBindingSpec
import cc.monomer.execution.api.ConnectorRuntimePropertyValue
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.ExecutionRunAdmissionPort
import cc.monomer.execution.api.RawSqlRunSpecFactory
import cc.monomer.identity.api.WorkspaceId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import io.mockk.mockk
import io.mockk.every
import kotlin.uuid.Uuid

class DashboardExecutionAdmissionAdapterTest {
    @Test
    fun `admission carries dashboard artifact and cell budgets into execution binding`() {
        val workspaceId = WorkspaceId.parse("019e0000-0000-7000-8000-0000000000a1")
        val connectorId = ConnectorId(Uuid.parse("019e0000-0000-7000-8000-0000000000a2"))
        val queryRunId = QueryRunId(Uuid.parse("019e0000-0000-7000-8000-0000000000a3"))
        val command = DashboardExecutionAdmissionPort.AdmissionCommand(
            queryRunId = queryRunId,
            workspaceId = workspaceId,
            connectorId = connectorId,
            sql = "SELECT ? AS injected",
            idempotencyKey = "dashboard-budget",
            resultObjectKey = "artifacts/workspaces/$workspaceId/dashboard/query-runs/$queryRunId/result.arrow",
            rowLimit = 100,

            bindings = listOf(DashboardBindValue.Text("x' OR '1'='1")),
            validateRawSqlBeforeRun = true,
            expectedColumnNames = listOf("injected"),
            parameterNames = listOf(""),
        )
        val connector = connectorBinding(workspaceId, connectorId)
        val runtimeBinding = runtimeBinding(connectorId)
        val requests = mutableListOf<cc.monomer.execution.api.RawSqlRunSpecCommand>()
        val reservations = mutableListOf<ExecutionRunAdmissionPort.ReserveCommand>()
        val adapter = DashboardExecutionAdmissionAdapter(
            connectorAccess = object : ConnectorExecutionAccessInOwnerTransactionPort {
                override fun readCurrentForExecutionInOwnerTransaction(
                    workspaceId: WorkspaceId,
                    connectorId: ConnectorId,
                ): ConnectorExecutionAccessResult = ConnectorExecutionAccessResult.Granted(connector)
            },
            connectorProjection = ConnectorRuntimeBindingProjectionPort { runtimeBinding },
            rawSqlRunSpecFactory = RawSqlRunSpecFactory { specCommand ->
                requests += specCommand
                ProtoRawSqlRunSpecFactory().create(specCommand)
            },
            admissionPort = object : ExecutionRunAdmissionPort {
            override fun lockWorkspaceInOwnerTransaction(workspaceId: cc.monomer.identity.api.WorkspaceId) = Unit
            override fun hasUnreleasedOccupancyInOwnerTransaction(workspaceId: cc.monomer.identity.api.WorkspaceId, runIds: Set<cc.monomer.execution.api.ExecutionRunId>): Boolean = false
                override fun reserveInOwnerTransaction(command: ExecutionRunAdmissionPort.ReserveCommand): ExecutionRunAdmissionPort.ReserveOutcome {
                    reservations += command
                    return ExecutionRunAdmissionPort.ReserveOutcome.Backpressured
                }
            },
            clock = Clock.fixed(Instant.parse("2026-08-22T00:00:00Z"), ZoneOffset.UTC),
            runtimeProperties = cc.monomer.control.testControlRuntimeProperties(),
        )

        assertIs<DashboardExecutionAdmissionPort.AdmissionOutcome.Backpressured>(adapter.admit(command))
        assertEquals(1, requests.size)
        assertEquals(1, reservations.size)
        assertEquals("SELECT ? AS injected", requests.single().request.sql)
        assertEquals(listOf(QueryParameterValue.Text("x' OR '1'='1", name = "")), requests.single().request.parameters)
        assertEquals(true, requests.single().request.validateRawSqlBeforeRun)
        assertEquals(listOf("injected"), requests.single().request.expectedColumnNames)
        assertEquals(67_108_864uL, requests.single().request.maximumArtifactBytes)
        assertEquals(67_108_864uL, reservations.single().runtimeBindings.resultSink.maximumArtifactBytes)
        assertEquals(65_536, requests.single().request.maximumCellBytes)
        assertEquals(65_536, reservations.single().runtimeBindings.resultSink.maximumCellBytes)
    }

    @Test
    fun `admission preserves names for every typed value and rejects styles inconsistent with Connector type`() {
        val workspaceId = WorkspaceId.generate()
        val connectorId = ConnectorId.generate()
        val queryRunId = QueryRunId.generate()
        val values = listOf(
            DashboardBindValue.Text("text"), DashboardBindValue.Number("123.45"), DashboardBindValue.Boolean(true),
            DashboardBindValue.Date(java.time.LocalDate.parse("2026-10-07")), DashboardBindValue.Timestamp(Instant.parse("2026-10-07T00:00:00Z")),
            DashboardBindValue.Null(cc.monomer.dashboard.domain.DashboardValueType.TEXT),
        )
        val names = values.indices.map { "monomer_p${it + 1}" }
        val command = DashboardExecutionAdmissionPort.AdmissionCommand(
            queryRunId, workspaceId, connectorId, "SELECT :monomer_p1", "names", "result.arrow", 100, values,
            true, listOf("value"), names,
        )
        val connectorAccess = mockk<ConnectorExecutionAccessInOwnerTransactionPort>()
        val requests = mutableListOf<cc.monomer.execution.api.RawSqlRunSpecCommand>()
        val projection = mockk<ConnectorRuntimeBindingProjectionPort>()
        every { projection.project(any()) } returns runtimeBinding(connectorId).copy(connectorType = ConnectorType.DATABRICKS)
        val admissions = mockk<ExecutionRunAdmissionPort>()
        every { admissions.reserveInOwnerTransaction(any()) } returns ExecutionRunAdmissionPort.ReserveOutcome.Backpressured
        val adapter = DashboardExecutionAdmissionAdapter(
            connectorAccess, projection,
            RawSqlRunSpecFactory { requests += it; ProtoRawSqlRunSpecFactory().create(it) }, admissions,
            Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC), cc.monomer.control.testControlRuntimeProperties(),
        )
        every { connectorAccess.readCurrentForExecutionInOwnerTransaction(workspaceId, connectorId) } returns
            ConnectorExecutionAccessResult.Granted(connectorBinding(workspaceId, connectorId).copy(connectorType = "DATABRICKS"))
        assertIs<DashboardExecutionAdmissionPort.AdmissionOutcome.Backpressured>(adapter.admit(command))
        assertEquals(names, requests.single().request.parameters.map { it.name })
        assertEquals(values.zip(names).map { (value, name) -> value.toExecutionParameter(name) }, requests.single().request.parameters)
        listOf(List(values.size) { "" }, names.toMutableList().apply { this[1] = names[0].uppercase() }, names.toMutableList().apply { this[0] = "invalid-name" })
            .forEach { invalidNames -> assertFailsWith<IllegalStateException> { adapter.admit(command.copy(parameterNames = invalidNames)) } }
        every { connectorAccess.readCurrentForExecutionInOwnerTransaction(workspaceId, connectorId) } returns
            ConnectorExecutionAccessResult.Granted(connectorBinding(workspaceId, connectorId))
        assertFailsWith<IllegalStateException> { adapter.admit(command) }
        assertEquals(1, requests.size)
        every { connectorAccess.readCurrentForExecutionInOwnerTransaction(workspaceId, connectorId) } returns ConnectorExecutionAccessResult.NotFoundOrOutsideWorkspace
        assertEquals(DashboardExecutionAdmissionPort.AdmissionOutcome.Rejected("CONNECTOR_NOT_FOUND"), adapter.admit(command))
    }

    private fun connectorBinding(workspaceId: WorkspaceId, connectorId: ConnectorId) = ConnectorExecutionBinding(
        connectorId = connectorId,
        workspaceId = workspaceId,
        connectorType = "POSTGRESQL",
        displayName = "warehouse",
        properties = mapOf("host" to "localhost"),
        credentialReference = CredentialEnvelopeReference(
            credentialVersionId = CredentialVersionId(Uuid.parse("019e0000-0000-7000-8000-0000000000a4")),
            keyVersion = "test-key",
            envelopeDigest = ContentDigest("sha256:${"a".repeat(64)}"),
        ),
        updatedAt = Instant.parse("2026-08-22T00:00:00Z"),
    )

    private fun runtimeBinding(connectorId: ConnectorId) = ConnectorRuntimeBindingSpec(
        connectorId = ConnectorReference(connectorId.value),
        connectorType = ConnectorType.POSTGRESQL,
        properties = mapOf("host" to ConnectorRuntimePropertyValue.Text("localhost")),
        managedCredential = cc.monomer.execution.api.ManagedCredentialDispatchReference(
            credentialVersion = cc.monomer.execution.api.CredentialVersionReference(
                Uuid.parse("019e0000-0000-7000-8000-0000000000a4"),
            ),
            keyVersion = "test-key",
            envelopeDigest = cc.monomer.execution.api.Sha256Digest("a".repeat(64)),
        ),
    )
}
