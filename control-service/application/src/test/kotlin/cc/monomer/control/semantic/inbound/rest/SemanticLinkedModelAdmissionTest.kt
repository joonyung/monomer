@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.semantic.inbound.rest

import cc.monomer.connector.api.ConnectorId
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.identity.api.*
import cc.monomer.identity.application.*
import cc.monomer.identity.domain.model.WorkspaceActorAuthorizationFacts
import cc.monomer.identity.domain.model.OrganizationRole
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpHeaders
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ResponseStatusException
import org.springframework.test.web.reactive.server.WebTestClient
import io.mockk.coVerify
import io.mockk.mockk

class SemanticLinkedModelAdmissionTest {
    private val workspace = WorkspaceId.generate()
    private val connector = ConnectorId.generate()
    private val actorId = cc.monomer.identity.domain.id.UserId(Uuid.generateV7())
    private val now = Instant.parse("2026-09-07T00:00:00Z")
    private val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/").header(HttpHeaders.AUTHORIZATION, "Bearer session").build())

    @Test
    fun `Explorer reads queries independently of resource definitions while Viewer cannot`(): Unit = runBlocking {
        authorizer(snapshot(WorkspaceRole.EXPLORER)).authorizeExplorer(exchange, workspace)
        authorizer(snapshot(WorkspaceRole.EDITOR)).authorizeExplorer(exchange, workspace)
        assertFailsWith<ResponseStatusException> { authorizer(snapshot(WorkspaceRole.VIEWER)).authorizeExplorer(exchange, workspace) }
    }

    @Test
    fun `Explorer receives REST 403 for Model and Metric delete and restore`() {
        val authority = mockk<cc.monomer.semantic.api.SemanticAuthorityUseCase>()
        val read = mockk<cc.monomer.semantic.api.SemanticCurrentReadUseCase>()
        val names = mockk<PrincipalDisplayNames>()
        val explorer = authorizer(snapshot(WorkspaceRole.EXPLORER))
        val apps = cc.monomer.control.agentapp.FixedAgentAppDisplays
        val client = WebTestClient.bindToController(
            SemanticModelController(authority, read, explorer, names, apps),
            MetricController(authority, read, explorer, names, apps),
        ).build()
        val definitionId = Uuid.generateV7()
        val changeId = Uuid.generateV7()
        for (kind in listOf("semantic-models", "metrics")) {
            val uri = "/v2/workspaces/$workspace/semantic/$kind/$definitionId"
            client.delete().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer session")
                .exchange().expectStatus().isForbidden
            client.post().uri("$uri/history/$changeId/restore")
                .header(HttpHeaders.AUTHORIZATION, "Bearer session").header("Idempotency-Key", "explorer-restore-$kind")
                .exchange().expectStatus().isForbidden
        }
        coVerify(exactly = 0) { authority.deleteModel(any()) }
        coVerify(exactly = 0) { authority.deleteMetric(any()) }
        coVerify(exactly = 0) { authority.restoreModel(any()) }
        coVerify(exactly = 0) { authority.restoreMetric(any()) }
    }

    @Test
    fun `credential expiry and missing direct membership still deny queries`(): Unit = runBlocking {
        val active = snapshot(WorkspaceRole.EXPLORER)
        for (snapshot in listOf(active.copy(credential = CanonicalCredentialState.Expired), active.copy(workspaceAuthorization = null))) {
            assertFailsWith<ResponseStatusException> { authorizer(snapshot).authorizeExplorer(exchange, workspace) }
        }
    }

    private fun authorizer(snapshot: CanonicalIdentityAdmissionSnapshot): SemanticWorkspaceRequestAuthorizer =
        SemanticWorkspaceRequestAuthorizer(ControlAdmissionFacade(IdentityAdmissionService(
            object : CanonicalIdentityAdmissionSnapshotPort {
                override suspend fun read(credential: PresentedCredential, workspaceId: WorkspaceId, expectedOAuthResource: ExpectedOAuthResource?): CanonicalIdentityAdmissionSnapshot = snapshot
            },
        ), Clock.fixed(now, ZoneOffset.UTC)))

    private fun snapshot(role: WorkspaceRole) = CanonicalIdentityAdmissionSnapshot(
        organizationPlan = cc.monomer.identity.api.OrganizationPlan.FREE,
        credential = CanonicalCredentialState.Active(CanonicalPrincipal(PrincipalId(actorId.value), PrincipalKind.USER), CredentialKind.SESSION, CredentialSurface.WEB, null, now.plusSeconds(60), emptySet()),
        workspaceAuthorization = WorkspaceActorAuthorizationFacts(
            actorUserId = actorId,
            workspaceId = cc.monomer.identity.domain.id.WorkspaceId(workspace.value),
            organizationId = cc.monomer.identity.domain.id.OrganizationId(Uuid.generateV7()),
            authorizationRevision = cc.monomer.identity.domain.model.AuthorizationRevision(1),
            organizationRole = OrganizationRole.MEMBER,
            role = role,
        ),
    )
}
