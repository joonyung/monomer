@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.dashboard.api.CreateDashboardCommand
import cc.monomer.dashboard.api.DashboardOwnerCommandMetadata
import cc.monomer.dashboard.api.DashboardOwnerOutcome
import cc.monomer.dashboard.application.DashboardDraftUseCase
import cc.monomer.dashboard.application.DashboardUseCase
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DashboardOwnerPortAdapterTest {
    @Test
    fun `ordinary reads use the blocking dispatcher and active transactions keep their thread`() = runTest {
        val useCase = mockk<DashboardUseCase>()
        var readThread: Thread? = null
        val workspace = WorkspaceId.generate()
        val dashboard = cc.monomer.dashboard.api.DashboardId.generate()
        coEvery { useCase.get(workspace, dashboard) } coAnswers {
            readThread = Thread.currentThread()
            cc.monomer.dashboard.application.DashboardResult.NotFound
        }
        java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val adapter = DashboardOwnerPortAdapter(useCase, mockk(), mockk(), dispatcher, lifecycle = mockk())
            val caller = Thread.currentThread()
            adapter.read(workspace, dashboard)
            kotlin.test.assertNotSame(caller, readThread)
            val wasActive = org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true)
            try {
                adapter.read(workspace, dashboard)
                kotlin.test.assertSame(caller, readThread)
            } finally {
                org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(wasActive)
            }
        }
    }

    @Test
    fun `Agent Session map reaches the canonical owner command with credential revision and idempotency`() = runTest {
        val sessionUseCase = mockk<cc.monomer.dashboard.application.DashboardSessionUseCase>()
        val captured = io.mockk.slot<cc.monomer.dashboard.application.DashboardSessionUseCase.ResolveCommand>()
        coEvery { sessionUseCase.resolve(capture(captured)) } returns cc.monomer.dashboard.application.SessionResolutionResult.Resolved(emptyMap(), emptyMap())
        val adapter = DashboardOwnerPortAdapter(mockk(), mockk(), sessionUseCase, kotlinx.coroutines.Dispatchers.Unconfined, lifecycle = mockk())
        val workspace = WorkspaceId.generate()
        val session = cc.monomer.dashboard.api.DashboardSessionId.generate()
        val values = mapOf("region" to kotlinx.serialization.json.JsonNull, "enabled" to kotlinx.serialization.json.JsonPrimitive(true))
        val metadata = DashboardOwnerCommandMetadata(
            actorId = "agent",
            credential = PresentedCredential("agent-token"),
            expectedOAuthResource = ExpectedOAuthResource.AGENT_REST,
            idempotencyKey = "atomic-request",
            requestedAt = Instant.parse("2026-08-24T00:00:00Z"),
            credentialSurface = null,
            agentClientId = null,
        )
        val outcome = adapter.resolveSession(cc.monomer.dashboard.api.ResolveDashboardSessionCommand(
            workspaceId = workspace,
            dashboardId = cc.monomer.dashboard.api.DashboardId.generate(),
            sessionId = session,
            variables = values,
            metadata = metadata,
        ))
        assertIs<DashboardOwnerOutcome.Success<*>>(outcome)
        assertEquals(workspace, captured.captured.workspaceId)
        assertEquals(session, captured.captured.sessionId)
        assertEquals(values, captured.captured.variables)
        assertEquals(metadata.credential, captured.captured.credential)
        assertEquals(metadata.expectedOAuthResource, captured.captured.expectedOAuthResource)
        assertEquals(metadata.actorId, captured.captured.actorId)
        assertEquals(metadata.idempotencyKey, captured.captured.idempotencyKey)
    }

    @Test
    fun `Agent owner open passes typed control outsiders to the shared Session use case`() = runTest {
        val sessionUseCase = mockk<cc.monomer.dashboard.application.DashboardSessionUseCase>()
        coEvery { sessionUseCase.open(any()) } returns cc.monomer.dashboard.application.SessionResult.Rejected("ADMISSION_TEST", null)
        val adapter = DashboardOwnerPortAdapter(mockk(), mockk(), sessionUseCase, kotlinx.coroutines.Dispatchers.Unconfined, lifecycle = mockk())
        val bindings = mapOf("country" to kotlinx.serialization.json.JsonPrimitive("CN"), "amount" to kotlinx.serialization.json.JsonPrimitive("150"))
        val workspace = WorkspaceId.generate()
        val dashboard = cc.monomer.dashboard.api.DashboardId.generate()
        val metadata = DashboardOwnerCommandMetadata(
            "agent", PresentedCredential("agent-token"), ExpectedOAuthResource.AGENT_REST,
            "ui-only-open", Instant.parse("2026-09-01T00:00:00Z"),
            credentialSurface = null,
            agentClientId = null,
        )
        val outcome = adapter.openSession(cc.monomer.dashboard.api.OpenDashboardSessionCommand(workspace, dashboard, bindings, metadata))
        assertEquals("ADMISSION_TEST", assertIs<DashboardOwnerOutcome.Rejected>(outcome).code)
        io.mockk.coVerify(exactly = 1) {
            sessionUseCase.open(match {
                it.parameterBindings == bindings && it.workspaceId == workspace && it.dashboardId == dashboard &&
                    it.expectedOAuthResource == ExpectedOAuthResource.AGENT_REST && it.credential == metadata.credential
            })
        }
    }

    @Test
    fun `create does not classify unrelated argument failures as authored definition errors`() = runTest {
        val dashboardUseCase = mockk<DashboardUseCase>()
        val failure = IllegalArgumentException("private persistence detail")
        coEvery { dashboardUseCase.create(any()) } throws failure
        val adapter = DashboardOwnerPortAdapter(dashboardUseCase, mockk(), mockk(), kotlinx.coroutines.Dispatchers.Unconfined, lifecycle = mockk())
        val thrown = kotlin.test.assertFailsWith<IllegalArgumentException> {
            adapter.create(CreateDashboardCommand(
                WorkspaceId.generate(), JsonObject(emptyMap()),
                DashboardOwnerCommandMetadata(
                    "agent", PresentedCredential("agent-token"), ExpectedOAuthResource.AGENT_REST,
                    "request-1", Instant.parse("2026-08-24T00:00:00Z"), null, null,
                ),
            ))
        }
        kotlin.test.assertSame(failure, thrown)
    }

    @Test
    fun `invalid authored definition is an owner rejection`() = runTest {
        val dashboardUseCase = mockk<DashboardUseCase>()
        coEvery { dashboardUseCase.create(any()) } answers {
            cc.monomer.dashboard.application.DashboardDefinitionDecoder.decode(JsonObject(emptyMap()))
            error("invalid definition must fail")
        }
        val adapter = DashboardOwnerPortAdapter(dashboardUseCase, mockk<DashboardDraftUseCase>(), mockk(), kotlinx.coroutines.Dispatchers.Unconfined, lifecycle = mockk())

        val outcome = adapter.create(
            CreateDashboardCommand(
                workspaceId = WorkspaceId.generate(),
                definition = JsonObject(emptyMap()),
                metadata = DashboardOwnerCommandMetadata(
                    actorId = "agent",
                    credential = PresentedCredential("agent-token"),
                    expectedOAuthResource = ExpectedOAuthResource.AGENT_REST,
                    idempotencyKey = "request-1",
                    requestedAt = Instant.parse("2026-08-24T00:00:00Z"),
                    credentialSurface = null,
                    agentClientId = null,
                ),
            ),
        )

        val rejected = assertIs<DashboardOwnerOutcome.Rejected>(outcome)
        assertEquals("INVALID_DASHBOARD_DEFINITION", rejected.code)
        assertEquals("dashboard definition is missing required fields", rejected.message)
    }
}
