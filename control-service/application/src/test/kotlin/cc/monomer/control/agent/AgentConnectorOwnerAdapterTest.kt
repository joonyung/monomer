@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package cc.monomer.control.agent

import cc.monomer.agent.api.*
import cc.monomer.agent.application.AgentWorkspaceContractDecoder
import cc.monomer.connector.api.*
import cc.monomer.control.*
import cc.monomer.identity.api.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import java.time.Instant
import kotlin.uuid.Uuid

class AgentConnectorOwnerAdapterTest {
    private val authority = mockk<ConnectorAuthorityUseCase>()
    private val read = mockk<ConnectorWorkspaceReadUseCase>()
    private val admission = mockk<ControlAdmissionFacade>()
    private val workspaceSamples = mockk<WorkspaceSampleQuery>()
    private val caller = mockk<AgentWorkspaceCaller>()
    private val authorization = mockk<AuthorizedMutationContext>()
    private val workspace = WorkspaceId(Uuid.parse("01942b04-6e00-7000-8000-000000000001"))
    private val id = ConnectorId(Uuid.parse("01942b04-6e00-7000-8000-000000000002"))
    private val path = AgentResourcePath.parse("workspace/connectors/$id")
    private val adapter = AgentConnectorOwnerAdapter(authority, read, admission, workspaceSamples)
    private val decoder = AgentWorkspaceContractDecoder()

    init {
        every { caller.workspaceId } returns workspace
        every { caller.presentedCredential } returns PresentedCredential("fixture-token")
        every { caller.expectedOAuthResource } returns ExpectedOAuthResource.AGENT_REST
        every { caller.requestId } returns AgentRequestId("server-request-id")
        coEvery { admission.authorizeMutation(any()) } returns ControlAdmissionDecision.Admitted(authorization)
        coEvery { workspaceSamples.readMarker(workspace) } returns null
    }

    @Test
    fun `create exposes the owner free limit as a nonretryable conflict`() = runTest {
        val create = decoder.create("""{"path":"workspace/connectors","input":{"kind":"CONNECTOR_CREATE","connectorType":"POSTGRESQL","displayName":"Warehouse","properties":{"host":"db.example.com","port":5432,"database":"warehouse"},"credential":{"kind":"USERNAME_PASSWORD","username":"reader","password":"fixture-only"}}}""").value
        coEvery { authority.create(any()) } returns ConnectorMutationResult.LimitExceeded

        val rejected = adapter.create(caller, create) as AgentActionOutcome.Rejected
        assertThat(rejected.problem.code).isEqualTo("CONNECTOR_LIMIT_EXCEEDED")
        assertThat(rejected.problem.status).isEqualTo(AgentWorkspaceProblemStatus.CONFLICT)
        assertThat(rejected.problem.retryable).isFalse()
        assertThat(rejected.problem.detail).contains("10 connectors")
        coVerify(exactly = 1) { authority.create(any()) }
    }

    @Test
    fun `settings edit forwards patch to owner without reading current settings or credential`() = runTest {
        val request = AgentEditRequest(path, null, listOf(AgentJsonPatchOperation(AgentJsonPatchOp.REPLACE, "/displayName", JsonPrimitive("Renamed"))), commandId = null, aggregateMetrics = null)
        val captured = slot<ReviseConnectorCommand>()
        coEvery { authority.revise(capture(captured)) } returns ConnectorMutationResult.Committed(view())
        val result = adapter.edit(caller, request) as AgentActionOutcome.Completed
        assertThat(result.action.result.path).isEqualTo(path)
        assertThat(captured.captured.authorization).isSameAs(authorization)
        assertThat(captured.captured.mutationIdempotencyKey).isEqualTo("server-request-id")
        assertThat(captured.captured.patch).containsExactly(ConnectorSettingsPatch("replace", "/displayName", JsonPrimitive("Renamed")))
        coVerify(exactly = 1) { admission.authorizeMutation(match { it.intent == MutationIntent.CONNECTOR_UPDATE && it.common.expectedOAuthResource == ExpectedOAuthResource.AGENT_REST }) }
        coVerify { read wasNot Called }
    }

    @Test
    fun `create and rotate pass typed credentials and correct mutation intents`() = runTest {
        val create = decoder.create("""{"path":"workspace/connectors","input":{"kind":"CONNECTOR_CREATE","connectorType":"POSTGRESQL","displayName":"Warehouse","properties":{"host":"db.example.com","port":5432,"database":"warehouse"},"credential":{"kind":"USERNAME_PASSWORD","username":"reader","password":"fixture-only"}}}""").value
        coEvery { authority.create(any()) } returns ConnectorMutationResult.Committed(view())
        coEvery { authority.rotateCredential(any()) } returns ConnectorMutationResult.Committed(view())
        assertThat(adapter.create(caller, create)).isInstanceOf(AgentActionOutcome.Completed::class.java)
        val rotate = decoder.invoke("""{"path":"${path.value}","action":"rotate-credentials","input":{"kind":"CONNECTOR_CREDENTIAL_ROTATE","credential":{"kind":"USERNAME_PASSWORD","username":"reader","password":"fixture-only"}}}""").value
        assertThat(adapter.invoke(caller, rotate)).isInstanceOf(AgentActionOutcome.Completed::class.java)
        coVerify { admission.authorizeMutation(match { it.intent == MutationIntent.CONNECTOR_CREATE }) }
        coVerify { admission.authorizeMutation(match { it.intent == MutationIntent.CONNECTOR_UPDATE }) }
        coVerify { authority.create(match { it.authorization === authorization && it.mutationIdempotencyKey == "server-request-id" }) }
        coVerify { authority.rotateCredential(match { it.authorization === authorization && it.connectorId == id && it.mutationIdempotencyKey == "server-request-id" }) }
        coVerify { read wasNot Called }
    }

    @Test
    fun `revoked mutation admission prevents owner call and operations disappear`() = runTest {
        coEvery { admission.authorizeMutation(any()) } returns ControlAdmissionDecision.Denied(cc.monomer.control.ControlAdmissionStage.IDENTITY, cc.monomer.control.ControlAdmissionDenial.Identity(cc.monomer.identity.api.IdentityAdmissionDenial.PERMISSION_UNAVAILABLE))
        coEvery { read.findByWorkspaceAndId(workspace, id) } returns current()
        val request = AgentEditRequest(path, null, listOf(AgentJsonPatchOperation(AgentJsonPatchOp.REPLACE, "/displayName", JsonPrimitive("Renamed"))), commandId = null, aggregateMetrics = null)
        assertThat(adapter.edit(caller, request)).isEqualTo(AgentActionOutcome.Forbidden)
        val resource = (adapter.read(caller, AgentReadRequest(path, null)) as AgentResourceOutcome.Found).resource
        assertThat(resource.availableOperations.filter { it is AgentAvailableOperation.Edit || it is AgentAvailableOperation.Invoke }).isEmpty()
        coVerify { authority wasNot Called }
    }

    @Test
    fun `owner read offers delete and delete returns the connector collection or a typed conflict`() = runTest {
        coEvery { read.findByWorkspaceAndId(workspace, id) } returns current()
        val resource = (adapter.read(caller, AgentReadRequest(path, null)) as AgentResourceOutcome.Found).resource
        assertThat(resource.availableOperations).containsExactly(
            AgentAvailableOperation.Read(AgentResourcePath.parse("${path.value}/catalog")), AgentAvailableOperation.Edit(path),
            AgentAvailableOperation.Invoke(path, "rotate-credentials", "CONNECTOR_CREDENTIAL_ROTATE"), AgentAvailableOperation.Invoke(path, "delete", null),
        )

        val delete = decoder.invoke("""{"path":"${path.value}","action":"delete"}""").value
        assertThat(delete.input).isEqualTo(AgentInvokeInput.None)
        coEvery { authority.delete(any()) } returnsMany listOf(
            ConnectorDeletionResult.Deleted,
            ConnectorDeletionResult.RefreshActive,
            ConnectorDeletionResult.NotFoundOrOutsideWorkspace,
        )
        val completed = adapter.invoke(caller, delete) as AgentActionOutcome.Completed
        assertThat(completed.action.result).isEqualTo(AgentActionResultReference(AgentResourceKind.COLLECTION, AgentResourcePath.parse("workspace/connectors")))
        assertThat(AgentWorkspaceResponseRelations.matches(delete, completed.action)).isTrue()
        val conflict = adapter.invoke(caller, delete) as AgentActionOutcome.Rejected
        assertThat(conflict.problem.code).isEqualTo("CONNECTOR_REFRESH_ACTIVE")
        assertThat(conflict.problem.status).isEqualTo(AgentWorkspaceProblemStatus.CONFLICT)
        assertThat(adapter.invoke(caller, delete)).isEqualTo(AgentActionOutcome.NotFound)
        coVerify(exactly = 3) { authority.delete(match { it.authorization === authorization && it.connectorId == id }) }
        coVerify { admission.authorizeMutation(match { it.intent == MutationIntent.CONNECTOR_DELETE }) }
    }

    @Test
    fun `listing paginates canonical paths and rejects foreign cursor`() = runTest {
        val later = current().copy(connectorId = ConnectorId(Uuid.parse("01942b04-6e00-7000-8000-000000000003")))
        coEvery { read.listByWorkspace(workspace) } returns listOf(later, current())
        val collection = AgentResourcePath.parse("workspace/connectors")
        val first = (adapter.list(caller, AgentListRequest(collection, null, 1)) as AgentListingOutcome.Found).listing
        assertThat(first.items.single().path).isEqualTo(path)
        assertThat((first.items.single() as AgentResourceListingItem.Connector).displayName).isEqualTo("Warehouse")
        val second = (adapter.list(caller, AgentListRequest(collection, first.nextCursor, 1)) as AgentListingOutcome.Found).listing
        assertThat(second.items.single().path.value).endsWith(later.connectorId.toString())
        assertThat(second.nextCursor).isNull()
        assertThat(adapter.list(caller, AgentListRequest(collection, "invalid", 1))).isInstanceOf(AgentListingOutcome.Rejected::class.java)
    }

    @Test
    fun `sample Workspace empty collection never offers create in provisioning or ready`() = runTest {
        coEvery { read.listByWorkspace(workspace) } returns emptyList()
        val collection = AgentResourcePath.parse("workspace/connectors")
        val ordinary = (adapter.list(caller, AgentListRequest(collection, null, 5)) as AgentListingOutcome.Found).listing
        assertThat(ordinary.availableOperations).containsExactly(AgentAvailableOperation.Create(collection, "CONNECTOR_CREATE"))
        for (state in WorkspaceSampleState.entries) {
            coEvery { workspaceSamples.readMarker(workspace) } returns WorkspaceSampleMarker("acme-cloud@1", state, PrincipalId.generate())
            val sample = (adapter.list(caller, AgentListRequest(collection, null, 5)) as AgentListingOutcome.Found).listing
            assertThat(sample.items).isEmpty()
            assertThat(sample.availableOperations).isEmpty()
        }
        coVerify(exactly = 1) { admission.authorizeMutation(any()) }
        coVerify { authority wasNot Called }
    }

    @Test
    fun `sample connector exact read keeps catalog but never offers edits rotation or deletion to Owner`() = runTest {
        val sample = CurrentConnectorRead(id, workspace, ConnectorProductType.DUCKDB, "Acme Cloud (sample)",
            ConnectorSettingsProperties.Duckdb("acme-cloud@1"), Instant.EPOCH, Instant.EPOCH)
        coEvery { read.findByWorkspaceAndId(workspace, id) } returns sample
        val resource = (adapter.read(caller, AgentReadRequest(path, null)) as AgentResourceOutcome.Found).resource
        assertThat(resource.availableOperations).containsExactly(AgentAvailableOperation.Read(AgentResourcePath.parse("${path.value}/catalog")))
        assertThat((resource as AgentResourceEnvelope.Connector).body.sample).isEqualTo(ConnectorSample("acme-cloud@1", "Acme Cloud"))
        coVerify(exactly = 0) { admission.authorizeMutation(any()) }
        coVerify { authority wasNot Called }
    }

    @Test
    fun `owner errors are safe and unavailable resources stay hidden`() = runTest {
        val request = AgentEditRequest(path, null, listOf(AgentJsonPatchOperation(AgentJsonPatchOp.REPLACE, "/displayName", JsonPrimitive("Renamed"))), commandId = null, aggregateMetrics = null)
        coEvery { authority.revise(any()) } returns ConnectorMutationResult.InvalidInput("Connector port must be between 1 and 65535")
        val bad = adapter.edit(caller, request) as AgentActionOutcome.Rejected
        assertThat(bad.problem.status).isEqualTo(AgentWorkspaceProblemStatus.BAD_REQUEST)
        assertThat(bad.problem.toString()).doesNotContain("fixture-sensitive-value")
        assertThat(bad.problem.violations.single().field).isEqualTo("patch")
        assertThat(bad.problem.detail).isEqualTo("Connector port must be between 1 and 65535")
        coEvery { authority.revise(any()) } throws IllegalArgumentException("fixture-sensitive-value")
        kotlin.test.assertFailsWith<IllegalArgumentException> { adapter.edit(caller, request) }
        coEvery { authority.revise(any()) } throws IllegalStateException("unknown commit outcome")
        val uncertain = adapter.edit(caller, request) as AgentActionOutcome.Rejected
        assertThat(uncertain.problem.status).isEqualTo(AgentWorkspaceProblemStatus.SERVICE_UNAVAILABLE)
        assertThat(uncertain.problem.retryable).isFalse()
        assertThat(uncertain.problem.detail).contains("Read the Connector before sending the change again.")
        assertThat(uncertain.problem.toString()).doesNotContain("unknown commit outcome")
        coEvery { authority.revise(any()) } returns ConnectorMutationResult.NotFoundOrOutsideWorkspace
        assertThat(adapter.edit(caller, request)).isEqualTo(AgentActionOutcome.NotFound)
        coEvery { authority.revise(any()) } returns ConnectorMutationResult.Conflict
        assertThat((adapter.edit(caller, request) as AgentActionOutcome.Rejected).problem.status).isEqualTo(AgentWorkspaceProblemStatus.CONFLICT)
    }

    @Test
    fun `read failures are retryable owner unavailability`() = runTest {
        coEvery { read.listByWorkspace(workspace) } throws IllegalStateException("private read failure")
        coEvery { read.findByWorkspaceAndId(workspace, id) } throws IllegalStateException("private read failure")
        val listed = adapter.list(caller, AgentListRequest(AgentResourcePath.parse("workspace/connectors"), null, 1)) as AgentListingOutcome.Rejected
        val readOutcome = adapter.read(caller, AgentReadRequest(path, null)) as AgentResourceOutcome.Rejected
        for (problem in listOf(listed.problem, readOutcome.problem)) {
            assertThat(problem).isEqualTo(AgentOwnerProblems.ownerUnavailable)
            assertThat(problem.retryable).isTrue()
        }
    }

    private fun current() = CurrentConnectorRead(id, workspace, ConnectorProductType.POSTGRESQL, "Warehouse",
        ConnectorSettingsCodec.decode(ConnectorProductType.POSTGRESQL, kotlinx.serialization.json.Json.parseToJsonElement("""{"host":"db.example.com","port":5432,"database":"warehouse"}""") as kotlinx.serialization.json.JsonObject), Instant.EPOCH, Instant.EPOCH)
    private fun view() = ConnectorMutationView(id, workspace, "POSTGRESQL", "Warehouse", current().properties,
        CredentialVersionId(Uuid.parse("01942b04-6e00-7000-8000-000000000004")), Instant.EPOCH, Instant.EPOCH)
}
