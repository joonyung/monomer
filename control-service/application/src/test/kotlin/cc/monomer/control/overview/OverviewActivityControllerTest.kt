@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package cc.monomer.control.overview

import cc.monomer.catalog.api.CatalogActivityReadPort
import cc.monomer.connector.api.*
import cc.monomer.control.*
import cc.monomer.control.auth.inbound.oauth.JwtAuthentication
import cc.monomer.dashboard.api.DashboardActivityReadPort
import cc.monomer.explore.api.*
import cc.monomer.identity.api.*
import cc.monomer.semantic.api.*
import cc.monomer.pages.api.PageActivityReadPort
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import java.time.Instant
import kotlin.test.*
import kotlin.uuid.Uuid

class OverviewActivityControllerTest {
    private val workspace = WorkspaceId.generate()
    private val actor = PrincipalId.generate()
    private val teammate = PrincipalId.generate()
    private val connector = ConnectorId(Uuid.generateV7())
    private val time = Instant.parse("2026-09-09T00:00:00.123456Z")
    private val admission = mockk<ControlAdmissionFacade>()
    private val explore = mockk<ExploreActivityReadPort>()
    private val catalog = mockk<CatalogActivityReadPort>()
    private val pages = mockk<PageActivityReadPort>()
    private val dashboards = mockk<DashboardActivityReadPort>()
    private val semantic = mockk<SemanticCurrentReadUseCase>()
    private val names = mockk<PrincipalDisplayNames>()
    private val connectors = mockk<ConnectorWorkspaceReadUseCase>()
    private val executionRuns = mockk<cc.monomer.execution.api.ExecutionRunQueryPort>()
    private val denied = ControlAdmissionDecision.Denied(ControlAdmissionStage.IDENTITY, ControlAdmissionDenial.Identity(IdentityAdmissionDenial.PERMISSION_UNAVAILABLE))
    private val controller = OverviewActivityController(admission, explore, catalog, pages, dashboards, names, cc.monomer.control.agentapp.FixedAgentAppDisplays, semantic, connectors, executionRuns)
    private val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/").build()).mutate().principal(Mono.just(mockk<JwtAuthentication> {
        every { presentedCredential } returns PresentedCredential("fixture")
    })).build()

    init {
        coEvery { semantic.readRecentModels(workspace, any(), any(), null, any(), any(), any()) } returns emptyList()
        coEvery { semantic.readRecentMetrics(workspace, any(), any(), null, any(), any(), any()) } returns emptyList()
        coEvery { semantic.readRecentModels(workspace, any(), any(), actor, any(), any(), any()) } returns emptyList()
        coEvery { semantic.readRecentMetrics(workspace, any(), any(), actor, any(), any(), any()) } returns emptyList()
        coEvery { admission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Admitted(mockk {
            every { actor } returns CanonicalPrincipal(this@OverviewActivityControllerTest.actor, PrincipalKind.USER)
        })
        coEvery { explore.readActivity(workspace, any(), any(), any(), null, any(), any(), any(), any()) } returns emptyList()
        coEvery { explore.readActivity(workspace, any(), any(), any(), actor, any(), any(), any(), any()) } returns emptyList()
        coEvery { catalog.readActivity(workspace, any(), any(), any(), any(), null, any(), any(), any(), any()) } returns emptyList()
        coEvery { catalog.readActivity(workspace, any(), any(), any(), any(), actor, any(), any(), any(), any()) } returns emptyList()
        coEvery { dashboards.readSessions(workspace, any(), any(), any(), any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { dashboards.readDraftRuns(workspace, any(), any(), any(), any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { executionRuns.readSqlBatch(any()) } answers {
            cc.monomer.execution.api.ExecutionRunQueryPort.ReadSqlBatchOutcome(emptyList(), firstArg<cc.monomer.execution.api.ExecutionRunQueryPort.ReadSqlBatchCommand>().runs.map { it.runId })
        }
        coEvery { dashboards.readChanges(workspace, any(), any(), any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { pages.readRecent(any(), any(), any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { names.read(any()) } answers { firstArg<Set<Uuid>>().associateWith { "Team member" } }
        coEvery { connectors.findByWorkspaceAndIds(workspace, any()) } returns emptyList()
    }

    @Test fun `dashboard changes open the surface described by the activity for Explorer`() = runBlocking {
        val dashboardId = Uuid.generateV7()
        for (editor in listOf(true)) {
            for (change in cc.monomer.dashboard.api.DashboardChangeKind.entries) {
                coEvery { dashboards.readChanges(workspace, any(), any(), any(), any(), any(), any(), editor) } returns listOf(
                    cc.monomer.dashboard.api.DashboardChange(dashboardId, dashboardId, change == cc.monomer.dashboard.api.DashboardChangeKind.DELETED, "Revenue", time, actor.toString(), CredentialSurface.AGENT, null, change),
                )
                val item = controller.read(workspace.toString(), "recent", null, null, null, null, 10, null, exchange).items.single()
                val expectedDraft = editor && change in setOf(
                    cc.monomer.dashboard.api.DashboardChangeKind.CREATED, cc.monomer.dashboard.api.DashboardChangeKind.DRAFT_UPDATED,
                )
                assertEquals("/workspaces/$workspace/dashboards/$dashboardId" + if (change == cc.monomer.dashboard.api.DashboardChangeKind.DELETED) "/history" else if (expectedDraft) "/builder" else "", item.href)
                assertEquals(when (change) {
                    cc.monomer.dashboard.api.DashboardChangeKind.CREATED -> "Dashboard created"
                    cc.monomer.dashboard.api.DashboardChangeKind.DRAFT_UPDATED -> "Draft updated"
                    cc.monomer.dashboard.api.DashboardChangeKind.DRAFT_DISCARDED -> "Draft changes discarded"
                    cc.monomer.dashboard.api.DashboardChangeKind.APPLIED -> "Dashboard edited"
                    cc.monomer.dashboard.api.DashboardChangeKind.DELETED -> "Dashboard deleted"
                }, item.description)
            }
        }
    }

    @Test fun `semantic recent rows preserve current label name actor and stable links without lineage checks`() = runBlocking {
        val model = SemanticDefinitionChange(id = Uuid.generateV7(), name = "orders", label = "Orders model", createdAt = time, updatedAt = time, actorId = actor, credentialSurface = CredentialSurface.AGENT, agentClientId = null, changeId = Uuid.generateV7(), action = SemanticDefinitionChangeAction.UPDATED, currentlyDeleted = false)
        val metric = SemanticDefinitionChange(id = Uuid.generateV7(), name = "revenue", label = null, createdAt = time.minusSeconds(1), updatedAt = time, actorId = actor, credentialSurface = CredentialSurface.WEB, agentClientId = null, changeId = Uuid.generateV7(), action = SemanticDefinitionChangeAction.UPDATED, currentlyDeleted = false)
        coEvery { semantic.readRecentModels(workspace, null, null, actor, any(), any(), 3) } returns listOf(model)
        coEvery { semantic.readRecentMetrics(workspace, null, null, actor, any(), any(), 3) } returns listOf(metric)
        val response = controller.read(workspace.toString(), "recent", null, "me", null, null, 2, null, exchange)
        assertEquals(listOf(OverviewActivityKind.SEMANTIC_MODEL, OverviewActivityKind.METRIC), response.items.map { it.kind })
        assertEquals("Orders model", response.items[0].title)
        assertEquals("Semantic Model edited · orders", response.items[0].description)
        assertEquals("Metric edited · revenue", response.items[1].description)
        assertEquals("revenue", response.items[1].title)
        assertEquals("/workspaces/$workspace/semantic-models/${model.id}", response.items[0].href)
        assertEquals("/workspaces/$workspace/metrics/${metric.id}", response.items[1].href)
        assertEquals("AGENT", response.items[0].credentialSurface)
        assertEquals(actor.toString(), response.items[0].actorId)
    }

    @Test fun `every semantic mutation is a distinct activity with attribution and history links`() = runBlocking {
        val original = Uuid.generateV7()
        val created = SemanticDefinitionChange(id = original, name = "revenue", label = "Revenue at creation", createdAt = time.minusSeconds(3), updatedAt = time.minusSeconds(3), actorId = actor, credentialSurface = CredentialSurface.WEB, agentClientId = null, changeId = Uuid.generateV7(), action = SemanticDefinitionChangeAction.CREATED, currentlyDeleted = true)
        val edited = created.copy(label="Revenue edited", updatedAt=time.minusSeconds(2), changeId=Uuid.generateV7(), action=SemanticDefinitionChangeAction.UPDATED, credentialSurface=CredentialSurface.AGENT, agentClientId=cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE)
        val deleted = edited.copy(updatedAt=time.minusSeconds(1), changeId=Uuid.generateV7(), action=SemanticDefinitionChangeAction.DELETED, currentlyDeleted=false)
        val replacement = created.copy(id=Uuid.generateV7(), label="New Revenue", updatedAt=time, changeId=Uuid.generateV7(), currentlyDeleted=false)
        coEvery { semantic.readRecentMetrics(workspace, null, null, null, any(), any(), 11) } returns listOf(replacement,deleted,edited,created)
        val items = controller.read(workspace.toString(), "recent", null, null, null, null, 10, null, exchange).items
        assertEquals(4, items.size)
        assertEquals(4, items.map { it.id }.distinct().size)
        assertEquals(listOf("CREATED","DELETED","UPDATED","CREATED"), items.map { it.state })
        assertEquals(listOf("Metric created · revenue","Metric deleted · revenue","Metric edited · revenue","Metric created · revenue"), items.map { it.description })
        assertEquals("/workspaces/$workspace/metrics/${replacement.id}", items[0].href)
        assertTrue(items.drop(1).all { it.href == "/workspaces/$workspace/metrics/$original/history" })
        assertEquals("Revenue at creation", items.last().title)
        assertEquals("AGENT", items[1].credentialSurface)
        assertEquals(cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE.value, items[1].agentApp?.clientId)
        assertNotNull(items[1].agentApp)
        assertEquals(actor.toString(), items[1].actorId)
    }

    @Test fun `catalog list refresh links name the refreshed level apart from the browse catalog`() = runBlocking {
        val connectorLevel = cc.monomer.catalog.api.CatalogActivity(Uuid.generateV7(), connector, null, null, "COMPLETED", time, time, actor, CredentialSurface.WEB, null, null)
        val schemaLevel = cc.monomer.catalog.api.CatalogActivity(Uuid.generateV7(), connector, "SALES", "sales ops", "COMPLETED", time.minusSeconds(1), time.minusSeconds(1), actor, CredentialSurface.WEB, null, null)
        val ingest = cc.monomer.catalog.api.CatalogActivity(Uuid.generateV7(), connector, "SALES", null, "COMPLETED", time.minusSeconds(2), time.minusSeconds(2), actor, CredentialSurface.WEB, null, null)
        coEvery { catalog.readActivity(workspace, true, false, any(), any(), null, any(), any(), any(), any()) } returns listOf(connectorLevel, schemaLevel)
        coEvery { catalog.readActivity(workspace, false, false, any(), any(), null, any(), any(), any(), any()) } returns listOf(ingest)
        val hrefs = controller.read(workspace.toString(), "recent", null, null, null, null, 10, null, exchange).items.associate { it.id to it.href }
        val base = "/workspaces/$workspace/catalog?connector=$connector"
        assertEquals("$base&refresh=${connectorLevel.id}", hrefs[connectorLevel.id.toString()])
        assertEquals("$base&catalog=SALES&refreshCatalog=SALES&refreshSchema=sales+ops&refresh=${schemaLevel.id}", hrefs[schemaLevel.id.toString()])
        assertEquals("$base&catalog=SALES&ingest=${ingest.id}", hrefs[ingest.id.toString()])
    }

    @Test fun `catalog activity of a deleted connector keeps its history under a deleted name`() = runBlocking {
        val connectorLevel = cc.monomer.catalog.api.CatalogActivity(Uuid.generateV7(), connector, null, null, "COMPLETED", time, time, actor, CredentialSurface.WEB, null, null)
        val schemaLevel = cc.monomer.catalog.api.CatalogActivity(Uuid.generateV7(), connector, "SALES", "sales ops", "COMPLETED", time.minusSeconds(1), time.minusSeconds(1), actor, CredentialSurface.WEB, null, null)
        coEvery { catalog.readActivity(workspace, true, false, any(), any(), null, any(), any(), any(), any()) } returns listOf(connectorLevel, schemaLevel)
        coEvery { catalog.readActivity(workspace, false, false, any(), any(), null, any(), any(), any(), any()) } returns emptyList()
        val titles = controller.read(workspace.toString(), "recent", null, null, null, null, 10, null, exchange).items.associate { it.id to it.title }
        assertEquals("Deleted connector", titles[connectorLevel.id.toString()])
        assertEquals("Deleted connector / SALES / sales ops", titles[schemaLevel.id.toString()])
    }

    @Test fun `semantic sources are recent only and membership gates reads`() = runBlocking {
        controller.read(workspace.toString(), "live", null, null, null, null, 2, null, exchange)
        coVerify(exactly = 0) { semantic.readRecentModels(workspace, any(), any(), null, any(), any(), any()) }
        coVerify(exactly = 0) { semantic.readRecentMetrics(workspace, any(), any(), null, any(), any(), any()) }
        for (kind in listOf("SEMANTIC_MODEL", "METRIC")) {
            assertEquals(400, assertFailsWith<ResponseStatusException> {
                controller.read(workspace.toString(), "live", null, null, null, null, 2, "$kind:${Uuid.generateV7()}", exchange)
            }.statusCode.value())
        }
        coEvery { admission.authorizeWorkspaceRole(any()) } returns denied
        assertEquals(403, assertFailsWith<ResponseStatusException> {
            controller.read(workspace.toString(), "recent", null, null, null, null, 2, null, exchange)
        }.statusCode.value())
        coVerify(exactly = 0) { semantic.readRecentModels(workspace, any(), any(), null, any(), any(), any()) }
    }

    @Test fun `semantic source failure pauses cursor and total failure counts all recent sources`() = runBlocking {
        coEvery { explore.readActivity(workspace, any(), any(), any(), null, any(), any(), any(), any()) } throws IllegalStateException("offline")
        coEvery { catalog.readActivity(workspace, any(), any(), any(), any(), null, any(), any(), any(), any()) } throws IllegalStateException("offline")
        coEvery { dashboards.readSessions(workspace, any(), any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("offline")
        coEvery { dashboards.readDraftRuns(workspace, any(), any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("offline")
        coEvery { dashboards.readChanges(workspace, any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("offline")
        coEvery { pages.readRecent(any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("offline")
        val partial = controller.read(workspace.toString(), "recent", null, null, null, null, 2, null, exchange)
        assertEquals(7, partial.unavailableSources.size)
        assertNull(partial.nextCursor)
        coEvery { semantic.readRecentModels(workspace, any(), any(), null, any(), any(), any()) } throws IllegalStateException("offline")
        coEvery { semantic.readRecentMetrics(workspace, any(), any(), null, any(), any(), any()) } throws IllegalStateException("offline")
        assertEquals(503, assertFailsWith<ResponseStatusException> {
            controller.read(workspace.toString(), "recent", null, null, null, null, 2, null, exchange)
        }.statusCode.value())
    }

    @Test fun `equal time semantic cursor advances between kinds and retains actor scope`() = runBlocking {
        val model = SemanticDefinitionChange(id = Uuid.generateV7(), name = "orders", label = null, createdAt = time, updatedAt = time, actorId = actor, credentialSurface = CredentialSurface.WEB, agentClientId = null, changeId = Uuid.generateV7(), action = SemanticDefinitionChangeAction.UPDATED, currentlyDeleted = false)
        val metric = model.copy(id = Uuid.generateV7(), name = "revenue")
        coEvery { semantic.readRecentModels(workspace, null, null, actor, any(), any(), 2) } returns listOf(model)
        coEvery { semantic.readRecentMetrics(workspace, null, null, actor, any(), any(), 2) } returns listOf(metric)
        val first = controller.read(workspace.toString(), "recent", null, "me", null, null, 1, null, exchange)
        assertEquals(model.changeId.toString(), first.items.single().id)
        controller.read(workspace.toString(), "recent", first.nextCursor, "me", null, null, 1, null, exchange)
        coVerify { semantic.readRecentModels(workspace, time, model.changeId, actor, any(), any(), 2) }
        coVerify { semantic.readRecentMetrics(workspace, time, Uuid.parse("ffffffff-ffff-ffff-ffff-ffffffffffff"), actor, any(), any(), 2) }
        assertEquals(400, assertFailsWith<ResponseStatusException> {
            controller.read(workspace.toString(), "recent", first.nextCursor, "all", null, null, 1, null, exchange)
        }.statusCode.value())
    }

    @Test fun `the Agents filter reaches every source and keeps its own cursor scope`() = runBlocking {
        val model = SemanticDefinitionChange(id = Uuid.generateV7(), name = "orders", label = null, createdAt = time, updatedAt = time, actorId = actor, credentialSurface = CredentialSurface.AGENT, agentClientId = null, changeId = Uuid.generateV7(), action = SemanticDefinitionChangeAction.UPDATED, currentlyDeleted = false)
        coEvery { semantic.readRecentModels(workspace, any(), any(), null, CredentialSurface.AGENT, any(), any()) } returns listOf(model, model.copy(changeId = Uuid.generateV7()))
        val first = controller.read(workspace.toString(), "recent", null, null, "AGENT", null, 1, null, exchange)
        assertEquals("AGENT", first.items.single().credentialSurface)
        coVerify { explore.readActivity(workspace, false, null, null, null, CredentialSurface.AGENT, any(), 2, emptySet()) }
        coVerify { catalog.readActivity(workspace, true, false, null, null, null, CredentialSurface.AGENT, any(), 2, emptySet()) }
        coVerify { dashboards.readChanges(workspace, null, null, null, CredentialSurface.AGENT, any(), 2, true) }
        coVerify { dashboards.readSessions(workspace, false, null, null, null, CredentialSurface.AGENT, any(), 2, emptySet()) }
        coVerify { dashboards.readDraftRuns(workspace, false, null, null, null, CredentialSurface.AGENT, any(), 2, emptySet()) }
        coVerify { semantic.readRecentMetrics(workspace, null, null, null, CredentialSurface.AGENT, any(), 2) }
        coVerify { pages.readRecent(workspace.value, null, null, null, "AGENT", any(), 2) }
        controller.read(workspace.toString(), "recent", null, null, "WEB", null, 1, null, exchange)
        coVerify { pages.readRecent(workspace.value, null, null, null, "USER", any(), 2) }
        assertEquals(400, assertFailsWith<ResponseStatusException> {
            controller.read(workspace.toString(), "recent", first.nextCursor, null, null, null, 1, null, exchange)
        }.statusCode.value())
        assertEquals(400, assertFailsWith<ResponseStatusException> {
            controller.read(workspace.toString(), "recent", null, null, "agent", null, 1, null, exchange)
        }.statusCode.value())
    }

    @Test fun `the app filter reaches every source, names the app, and binds the cursor`() = runBlocking {
        val claude = cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE
        val model = SemanticDefinitionChange(id = Uuid.generateV7(), name = "orders", label = null, createdAt = time, updatedAt = time, actorId = actor, credentialSurface = CredentialSurface.AGENT, agentClientId = claude, changeId = Uuid.generateV7(), action = SemanticDefinitionChangeAction.UPDATED, currentlyDeleted = false)
        coEvery { semantic.readRecentModels(workspace, any(), any(), null, CredentialSurface.AGENT, claude, any()) } returns listOf(model, model.copy(changeId = Uuid.generateV7()))
        val first = controller.read(workspace.toString(), "recent", null, null, "AGENT", claude.value, 1, null, exchange)
        assertEquals(cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE_APP, first.items.single().agentApp)
        coVerify { explore.readActivity(workspace, false, null, null, null, CredentialSurface.AGENT, claude, 2, emptySet()) }
        coVerify { catalog.readActivity(workspace, false, false, null, null, null, CredentialSurface.AGENT, claude, 2, emptySet()) }
        coVerify { dashboards.readChanges(workspace, null, null, null, CredentialSurface.AGENT, claude, 2, true) }
        coVerify { dashboards.readSessions(workspace, false, null, null, null, CredentialSurface.AGENT, claude, 2, emptySet()) }
        coVerify { dashboards.readDraftRuns(workspace, false, null, null, null, CredentialSurface.AGENT, claude, 2, emptySet()) }
        coVerify { semantic.readRecentMetrics(workspace, null, null, null, CredentialSurface.AGENT, claude, 2) }
        coVerify { pages.readRecent(workspace.value, null, null, null, "AGENT", claude.value, 2) }
        assertEquals(400, assertFailsWith<ResponseStatusException> {
            controller.read(workspace.toString(), "recent", first.nextCursor, null, "AGENT", null, 1, null, exchange)
        }.statusCode.value())
        assertEquals(400, assertFailsWith<ResponseStatusException> {
            controller.read(workspace.toString(), "recent", null, null, "WEB", claude.value, 1, null, exchange)
        }.statusCode.value())
        assertEquals(400, assertFailsWith<ResponseStatusException> {
            controller.read(workspace.toString(), "recent", null, null, null, claude.value, 1, null, exchange)
        }.statusCode.value())
    }

    @Test fun `a person filter reaches every source, combines with the Agents filter, and binds the cursor`() = runBlocking {
        val model = SemanticDefinitionChange(id = Uuid.generateV7(), name = "orders", label = null, createdAt = time, updatedAt = time, actorId = teammate, credentialSurface = CredentialSurface.AGENT, agentClientId = null, changeId = Uuid.generateV7(), action = SemanticDefinitionChangeAction.UPDATED, currentlyDeleted = false)
        coEvery { explore.readActivity(workspace, any(), any(), any(), teammate, any(), any(), any(), any()) } returns emptyList()
        coEvery { catalog.readActivity(workspace, any(), any(), any(), any(), teammate, any(), any(), any(), any()) } returns emptyList()
        coEvery { semantic.readRecentModels(workspace, any(), any(), teammate, any(), any(), any()) } returns listOf(model, model.copy(changeId = Uuid.generateV7()))
        coEvery { semantic.readRecentMetrics(workspace, any(), any(), teammate, any(), any(), any()) } returns emptyList()
        val first = controller.read(workspace.toString(), "recent", null, teammate.toString(), "AGENT", null, 1, null, exchange)
        assertEquals(teammate.toString(), first.items.single().actorId)
        coVerify { explore.readActivity(workspace, false, null, null, teammate, CredentialSurface.AGENT, null, 2, emptySet()) }
        coVerify { catalog.readActivity(workspace, true, false, null, null, teammate, CredentialSurface.AGENT, null, 2, emptySet()) }
        coVerify { dashboards.readChanges(workspace, null, null, teammate.toString(), CredentialSurface.AGENT, null, 2, true) }
        coVerify { dashboards.readSessions(workspace, false, null, null, teammate.toString(), CredentialSurface.AGENT, null, 2, emptySet()) }
        coVerify { dashboards.readDraftRuns(workspace, false, null, null, teammate.toString(), CredentialSurface.AGENT, null, 2, emptySet()) }
        coVerify { semantic.readRecentMetrics(workspace, null, null, teammate, CredentialSurface.AGENT, null, 2) }
        coVerify { pages.readRecent(workspace.value, null, null, teammate.toString(), "AGENT", null, 2) }
        controller.read(workspace.toString(), "recent", first.nextCursor, teammate.toString(), "AGENT", null, 1, null, exchange)
        coVerify { semantic.readRecentModels(workspace, time, Uuid.parse(first.items.single().id), teammate, CredentialSurface.AGENT, null, 2) }
        assertEquals(400, assertFailsWith<ResponseStatusException> {
            controller.read(workspace.toString(), "recent", first.nextCursor, "all", "AGENT", null, 1, null, exchange)
        }.statusCode.value())
        for (malformed in listOf("someone", Uuid.random().toString())) {
            assertEquals(400, assertFailsWith<ResponseStatusException> {
                controller.read(workspace.toString(), "recent", null, malformed, null, null, 1, null, exchange)
            }.statusCode.value())
        }
    }

    @Test fun `agent work from an unknown app reads via agent and Web work names no app`() = runBlocking {
        val unknown = AgentClientId("https://agent.example/metadata.json")
        val agentModel = SemanticDefinitionChange(id = Uuid.generateV7(), name = "orders", label = null, createdAt = time, updatedAt = time, actorId = actor, credentialSurface = CredentialSurface.AGENT, agentClientId = unknown, changeId = Uuid.generateV7(), action = SemanticDefinitionChangeAction.UPDATED, currentlyDeleted = false)
        val webModel = SemanticDefinitionChange(id = Uuid.generateV7(), name = "returns", label = null, createdAt = time.minusSeconds(1), updatedAt = time.minusSeconds(1), actorId = actor, credentialSurface = CredentialSurface.WEB, agentClientId = null, changeId = Uuid.generateV7(), action = SemanticDefinitionChangeAction.UPDATED, currentlyDeleted = false)
        coEvery { semantic.readRecentModels(workspace, null, null, null, null, null, any()) } returns listOf(agentModel, webModel)
        val items = controller.read(workspace.toString(), "recent", null, null, null, null, 10, null, exchange).items
        assertEquals(listOf(null, null), items.map { it.agentApp })
        assertEquals(listOf("AGENT", "WEB"), items.map { it.credentialSurface })
    }

    @Test fun `recent agent apps merge every owner by latest use and skip unknown apps`() = runBlocking {
        val claude = cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE
        val unknown = AgentClientId("https://agent.example/metadata.json")
        coEvery { explore.readRecentAgentClients(workspace, 500) } returns listOf(RecentAgentClient(unknown, time.plusSeconds(5)))
        coEvery { catalog.readRecentAgentClients(workspace, any(), 500) } returns emptyList()
        coEvery { dashboards.readRecentAgentClients(workspace, 500) } returns listOf(RecentAgentClient(claude, time))
        coEvery { semantic.readRecentAgentClients(workspace, 500) } returns listOf(RecentAgentClient(claude, time.plusSeconds(1)))
        coEvery { pages.readRecentAgentClients(workspace.value, 500) } returns listOf(cc.monomer.pages.api.PageAgentClientUse(claude.value, time.minusSeconds(1)))
        val apps = controller.readAgentApps(workspace.toString(), exchange)
        assertEquals(listOf(cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE_APP), apps.items)
        coEvery { admission.authorizeWorkspaceRole(any()) } returns denied
        assertEquals(403, assertFailsWith<ResponseStatusException> { controller.readAgentApps(workspace.toString(), exchange) }.statusCode.value())
    }

    @Test fun `Explorer sees team results and pagination advances`() = runBlocking {
        val rows = listOf(run(time), run(time.minusSeconds(1)))
        coEvery { explore.readActivity(workspace, any(), any(), any(), null, any(), any(), any(), any()) } answers {
            if (arg<Set<Uuid>>(8).isEmpty()) rows else listOf(rows[0])
        }
        val response = controller.read(workspace.toString(), "live", null, null, null, null, 1, "EXPLORE:${rows[0].id}", exchange)
        assertEquals(rows[0].id.toString(), response.items.single().id)
        assertEquals(rows[0].id.toString(), response.watched.single().id)
        assertNotNull(response.nextCursor)
        val wire = java.util.Base64.getUrlDecoder().decode(response.nextCursor).toString(Charsets.UTF_8)
        assertFalse(wire.contains(rows[0].id.toString()))
        assertFalse(wire.contains(time.toString()))
        controller.read(workspace.toString(), "live", response.nextCursor, null, null, null, 1, null, exchange)
        coVerify { explore.readActivity(workspace, true, rows[0].startedAt, rows[0].id, null, any(), any(), 2, emptySet()) }
        coVerify { names.read(setOf(teammate.value)) }
    }

    @Test fun `partial source failure pauses pagination without declaring watched runs removed`() = runBlocking {
        coEvery { explore.readActivity(workspace, any(), any(), any(), null, any(), any(), any(), any()) } throws
            IllegalStateException("user@example.test raw-secret-token", java.sql.SQLException("private SQL statement", "40001"))
        val logger = LoggerFactory.getLogger(OverviewActivityController::class.java) as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        try {
            val response = controller.read(workspace.toString(), "live", null, null, null, null, 1, null, exchange)
            assertEquals(listOf(OverviewActivityKind.EXPLORE), response.unavailableSources)
            assertNull(response.nextCursor)
            val event = logs.list.single()
            val fields = event.keyValuePairs.associate { it.key to it.value }
            assertEquals("overview_activity_source_failed", event.formattedMessage)
            val encoded = cc.monomer.control.observability.RequestLogTestSupport.encoded(event)
            assertTrue(encoded.contains("stack_trace"))
            assertTrue(encoded.contains("OverviewActivityControllerTest.kt:"))
            assertTrue(encoded.contains("Caused by: java.sql.SQLException"))
            assertEquals("EXPLORE", fields["source"])
            assertEquals(workspace.toString(), fields["workspace_id"])
            assertEquals("40001", fields["sql_states"])
            assertEquals(listOf("java.lang.IllegalStateException", "java.sql.SQLException"),
                (fields["failure_types"] as String).split(" -> ").distinct())
            assertFalse(encoded.contains("raw-secret-token"))
            assertFalse(encoded.contains("user@example.test"))
            assertFalse(encoded.contains("private SQL statement"))
        } finally {
            logger.detachAppender(logs)
        }
    }

    @Test fun `own terminal observation preserves responsibility and completion time`() = runBlocking {
        val terminal = run(time).copy(actorId = actor, state = ExploreRunState.SUCCEEDED, terminalAt = time.plusSeconds(5))
        coEvery { explore.readActivity(workspace, any(), any(), any(), null, any(), any(), any(), any()) } answers {
            if (arg<Set<Uuid>>(8).isEmpty()) emptyList() else listOf(terminal)
        }
        val response = controller.read(workspace.toString(), "live", null, null, null, null, 1, "EXPLORE:${terminal.id}", exchange)
        assertEquals(time.plusSeconds(5), response.watched.single().occurredAt)
        assertEquals("AGENT", response.watched.single().credentialSurface)
        assertEquals(actor.toString(), response.watched.single().actorId)
        assertEquals("Team member", response.watched.single().actorName)
    }

    @Test fun `malformed or foreign cursor and recent watch are bad requests`() = runBlocking {
        val foreign = ActivityCursor(time, OverviewActivityKind.EXPLORE, Uuid.generateV7()).encode("another-workspace:LIVE:all", javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        for (cursor in listOf("!invalid", foreign)) {
            assertEquals(400, assertFailsWith<ResponseStatusException> { controller.read(workspace.toString(), "live", cursor, null, null, null, 1, null, exchange) }.statusCode.value())
        }
        assertEquals(400, assertFailsWith<ResponseStatusException> { controller.read(workspace.toString(), "recent", null, null, null, null, 1, "EXPLORE:${Uuid.generateV7()}", exchange) }.statusCode.value())
    }

    @Test fun `Viewer denial performs no owner reads`() = runBlocking {
        coEvery { admission.authorizeWorkspaceRole(any()) } returns denied
        assertEquals(403, assertFailsWith<ResponseStatusException> { controller.read(workspace.toString(), "live", null, null, null, null, 1, null, exchange) }.statusCode.value())
        coVerify(exactly = 0) { explore.readActivity(workspace, any(), any(), any(), null, any(), any(), any(), any()) }
    }

    @Test fun `missing workspace or membership remains hidden`() = runBlocking {
        for (reason in listOf(IdentityAdmissionDenial.WORKSPACE_UNAVAILABLE, IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE)) {
            coEvery { admission.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Denied(
                ControlAdmissionStage.IDENTITY, ControlAdmissionDenial.Identity(reason))
            assertEquals(404, assertFailsWith<ResponseStatusException> {
                controller.read(workspace.toString(), "live", null, null, null, null, 1, null, exchange)
            }.statusCode.value())
        }
        coVerify(exactly = 0) { explore.readActivity(workspace, any(), any(), any(), null, any(), any(), any(), any()) }
    }

    @Test fun `cursor encryption binds scope rejects tampering and expires with process key`() {
        fun key() = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val firstKey = key()
        val cursor = ActivityCursor(time, OverviewActivityKind.EXPLORE, Uuid.generateV7())
        val encoded = cursor.encode("workspace:principal:LIVE:all", firstKey)
        assertEquals(cursor, ActivityCursor.decode(encoded, "workspace:principal:LIVE:all", firstKey))
        assertNotEquals(encoded, cursor.encode("workspace:principal:LIVE:all", firstKey))
        assertFailsWith<IllegalArgumentException> { ActivityCursor.decode(encoded, "workspace:other:LIVE:all", firstKey) }
        assertFailsWith<IllegalArgumentException> { ActivityCursor.decode(encoded, "workspace:principal:LIVE:all", key()) }
        val bytes = java.util.Base64.getUrlDecoder().decode(encoded)
        bytes[12] = (bytes[12].toInt() xor 1).toByte()
        assertFailsWith<IllegalArgumentException> { ActivityCursor.decode(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), "workspace:principal:LIVE:all", firstKey) }
    }

    @Test fun `Explore rows carry distinct titles from recorded SQL and current semantic labels`() = runBlocking {
        val sqlRun = run(time).copy(state = ExploreRunState.SUCCEEDED, terminalAt = time)
        val unavailableSql = run(time.minusSeconds(1)).copy(state = ExploreRunState.SUCCEEDED, terminalAt = time.minusSeconds(1))
        val query = SemanticQuerySpec(
            metrics = listOf(SemanticElementName("revenue"), SemanticElementName("invoice_count")),
            groupBys = listOf(
                SemanticGroupBy.MetricTime(SemanticTimeGranularity.MONTH),
                SemanticGroupBy.Dimension(SemanticElementName("billing"), SemanticElementName("region"), null),
                SemanticGroupBy.Entity(SemanticElementName("billing"), SemanticElementName("customer")),
            ),
            metricTimeRange = null, filters = emptyList(), orderBy = emptyList(), rowLimit = 100,
        )
        val semanticRun = run(time.minusSeconds(2)).copy(kind = ExploreRunKind.SEMANTIC, state = ExploreRunState.FAILED,
            terminalAt = time.minusSeconds(2), query = query, failureCode = ExploreRunFailureCode.PERMISSION_DENIED)
        coEvery { explore.readActivity(workspace, false, any(), any(), null, any(), any(), any(), emptySet()) } returns listOf(sqlRun, unavailableSql, semanticRun)
        coEvery { executionRuns.readSqlBatch(any()) } returns cc.monomer.execution.api.ExecutionRunQueryPort.ReadSqlBatchOutcome(
            listOf(cc.monomer.execution.api.ExecutionRunQueryPort.RecordedRunSql(cc.monomer.execution.api.ExecutionRunId(sqlRun.id),
                "-- weekly\nSELECT status, count(*) FROM billing.\"invoices\" GROUP BY status")),
            listOf(cc.monomer.execution.api.ExecutionRunId(unavailableSql.id)),
        )
        coEvery { semantic.findMetricByName(workspace, SemanticElementName("revenue")) } returns mockk { every { definition.label } returns "Revenue" }
        coEvery { semantic.findMetricByName(workspace, SemanticElementName("invoice_count")) } returns null
        coEvery { semantic.findModelByName(workspace, SemanticElementName("billing")) } returns mockk {
            every { definition.dimensions } returns listOf(SemanticDimensionDefinition(SemanticElementName("region"), SemanticDimensionKind.CATEGORICAL, "region", null, "Sales region", null,
                false,
                null,
            ))
            every { definition.entities } returns emptyList()
        }
        val items = controller.read(workspace.toString(), "recent", null, null, null, null, 10, null, exchange).items.associateBy { it.id }
        assertEquals("invoices · SELECT status, count(*)…", items.getValue(sqlRun.id.toString()).title)
        assertEquals("SQL query", items.getValue(unavailableSql.id.toString()).title)
        val semanticItem = items.getValue(semanticRun.id.toString())
        assertEquals("Revenue, invoice_count by Month, Sales region, customer", semanticItem.title)
        assertEquals("Access denied", semanticItem.failureReason)
        assertNull(items.getValue(sqlRun.id.toString()).failureReason)
        assertEquals(OverviewActorKind.USER, semanticItem.actorKind)
        coVerify(exactly = 1) { executionRuns.readSqlBatch(match { command -> command.workspaceId == workspace && command.runs.size == 2 }) }
    }

    @Test fun `Explore connection and sign-in failures name their cause`() = runBlocking {
        val unreachable = run(time).copy(state = ExploreRunState.FAILED, terminalAt = time, failureCode = ExploreRunFailureCode.CONNECTION_FAILED)
        val rejected = run(time.minusSeconds(1)).copy(state = ExploreRunState.FAILED, terminalAt = time.minusSeconds(1),
            failureCode = ExploreRunFailureCode.AUTHENTICATION_FAILED)
        coEvery { explore.readActivity(workspace, false, any(), any(), null, any(), any(), any(), emptySet()) } returns listOf(unreachable, rejected)
        coEvery { executionRuns.readSqlBatch(any()) } returns cc.monomer.execution.api.ExecutionRunQueryPort.ReadSqlBatchOutcome(
            emptyList(), listOf(cc.monomer.execution.api.ExecutionRunId(unreachable.id), cc.monomer.execution.api.ExecutionRunId(rejected.id)),
        )
        val items = controller.read(workspace.toString(), "recent", null, null, null, null, 10, null, exchange).items.associateBy { it.id }
        assertEquals("Warehouse unreachable", items.getValue(unreachable.id.toString()).failureReason)
        assertEquals("Sign-in rejected", items.getValue(rejected.id.toString()).failureReason)
    }

    @Test fun `Explore titles fall back when recorded SQL cannot be read`() = runBlocking {
        val sqlRun = run(time).copy(state = ExploreRunState.SUCCEEDED, terminalAt = time)
        coEvery { explore.readActivity(workspace, false, any(), any(), null, any(), any(), any(), emptySet()) } returns listOf(sqlRun)
        coEvery { executionRuns.readSqlBatch(any()) } throws IllegalStateException("offline")
        val response = controller.read(workspace.toString(), "recent", null, null, null, null, 10, null, exchange)
        assertEquals("SQL query", response.items.single().title)
        assertTrue(response.unavailableSources.isEmpty())
    }

    @Test fun `a Dashboard Session is one row with the worst query state and its opener`() = runBlocking {
        val dashboardId = Uuid.generateV7()
        fun session(origin: cc.monomer.dashboard.api.DashboardSessionOrigin, actorId: String?, running: Int, failed: Int, cancelled: Int, failureCode: String?) =
            cc.monomer.dashboard.api.DashboardSessionActivity(Uuid.generateV7(), dashboardId, "Revenue overview", origin, 8, running, failed, cancelled,
                time.minusSeconds(30), time, actorId, if (actorId == null) null else CredentialSurface.WEB, null, failureCode)
        val opened = session(cc.monomer.dashboard.api.DashboardSessionOrigin.MEMBER, teammate.toString(), 0, 0, 0, null)
        val scheduled = session(cc.monomer.dashboard.api.DashboardSessionOrigin.SCHEDULE, null, 0, 2, 1, "AUTHENTICATION_FAILED")
        val publicReader = session(cc.monomer.dashboard.api.DashboardSessionOrigin.PUBLIC_READER, null, 0, 0, 1, null)
        val unrecorded = session(cc.monomer.dashboard.api.DashboardSessionOrigin.UNRECORDED, null, 0, 0, 0, null)
        coEvery { dashboards.readSessions(workspace, false, any(), any(), null, any(), any(), any(), emptySet()) } returns listOf(opened, scheduled, publicReader, unrecorded)
        val items = controller.read(workspace.toString(), "recent", null, null, null, null, 10, null, exchange).items.associateBy { it.id }
        val openedItem = items.getValue(opened.sessionId.toString())
        assertEquals(OverviewActivityKind.DASHBOARD_SESSION, openedItem.kind)
        assertEquals("Revenue overview", openedItem.title)
        assertEquals("Opened · 8 queries refreshed", openedItem.description)
        assertEquals("SUCCEEDED", openedItem.state)
        assertEquals(OverviewActorKind.USER, openedItem.actorKind)
        assertEquals("Team member", openedItem.actorName)
        assertEquals("/workspaces/$workspace/dashboards/$dashboardId?session=${opened.sessionId}", openedItem.href)
        assertEquals(time, openedItem.occurredAt)
        val scheduledItem = items.getValue(scheduled.sessionId.toString())
        assertEquals("FAILED", scheduledItem.state)
        assertEquals("Scheduled refresh · 2 of 8 queries failed", scheduledItem.description)
        assertEquals("Sign-in rejected", scheduledItem.failureReason)
        assertEquals(OverviewActorKind.SCHEDULE, scheduledItem.actorKind)
        assertNull(scheduledItem.actorId)
        val publicItem = items.getValue(publicReader.sessionId.toString())
        assertEquals("CANCELLED", publicItem.state)
        assertEquals("Opened · 1 of 8 queries cancelled", publicItem.description)
        assertEquals(OverviewActorKind.PUBLIC_READER, publicItem.actorKind)
        assertNull(publicItem.failureReason)
        assertEquals(OverviewActorKind.UNRECORDED, items.getValue(unrecorded.sessionId.toString()).actorKind)
    }

    @Test fun `live Sessions are watched by Session id and draft runs stay single rows`() = runBlocking {
        val dashboardId = Uuid.generateV7()
        val live = cc.monomer.dashboard.api.DashboardSessionActivity(Uuid.generateV7(), dashboardId, "Revenue overview",
            cc.monomer.dashboard.api.DashboardSessionOrigin.MEMBER, 8, 3, 0, 0, time, time.plusSeconds(4), actor.toString(), CredentialSurface.AGENT, cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE, null)
        val draft = cc.monomer.dashboard.api.DashboardRunActivity(Uuid.generateV7(), dashboardId, "Revenue overview", "monthly", "FAILED",
            time.minusSeconds(5), time.minusSeconds(1), actor.toString(), CredentialSurface.WEB, null, connector, "EXECUTION_FAILED")
        coEvery { dashboards.readSessions(workspace, true, any(), any(), null, any(), any(), any(), emptySet()) } returns listOf(live)
        coEvery { dashboards.readSessions(workspace, true, any(), any(), null, any(), any(), any(), setOf(live.sessionId)) } returns listOf(live)
        coEvery { dashboards.readDraftRuns(workspace, false, any(), any(), null, any(), any(), any(), emptySet()) } returns listOf(draft)
        val response = controller.read(workspace.toString(), "live", null, null, null, null, 10, "DASHBOARD_SESSION:${live.sessionId}", exchange)
        val item = response.items.single()
        assertEquals("RUNNING", item.state)
        assertEquals("Opened · 3 of 8 queries running", item.description)
        assertEquals(time, item.occurredAt)
        assertEquals("AGENT", item.credentialSurface)
        assertEquals(live.sessionId.toString(), response.watched.single().id)
        val draftItem = controller.read(workspace.toString(), "recent", null, null, null, null, 10, null, exchange).items.single { it.kind == OverviewActivityKind.DASHBOARD_RUN }
        assertEquals("Draft run · monthly", draftItem.description)
        assertEquals("Warehouse error", draftItem.failureReason)
        assertEquals("/workspaces/$workspace/dashboards/$dashboardId/builder?run=${draft.id}", draftItem.href)
    }

    @Test fun `catalog jobs completed with errors name a short cause and unrecorded actors stay unrecorded`() = runBlocking {
        val failed = cc.monomer.catalog.api.CatalogActivity(Uuid.generateV7(), connector, null, null, "COMPLETED_WITH_ERRORS", time, time, actor, CredentialSurface.WEB, null, "CONNECTION_FAILED")
        val partial = failed.copy(id = Uuid.generateV7(), terminalAt = time.minusSeconds(1), startedAt = time.minusSeconds(1), failureCode = "UNKNOWN_DRIVER_FAILURE")
        coEvery { catalog.readActivity(workspace, false, false, any(), any(), null, any(), any(), any(), any()) } returns listOf(failed, partial)
        coEvery { dashboards.readChanges(workspace, any(), any(), any(), any(), any(), any(), any()) } returns listOf(
            cc.monomer.dashboard.api.DashboardChange(Uuid.generateV7(), Uuid.generateV7(), false, "Legacy", time.minusSeconds(2), null, null, null, cc.monomer.dashboard.api.DashboardChangeKind.APPLIED),
        )
        val items = controller.read(workspace.toString(), "recent", null, null, null, null, 10, null, exchange).items
        assertEquals("Warehouse unreachable", items.single { it.id == failed.id.toString() }.failureReason)
        assertEquals("Some objects couldn't be read", items.single { it.id == partial.id.toString() }.failureReason)
        assertEquals(OverviewActorKind.UNRECORDED, items.single { it.kind == OverviewActivityKind.DASHBOARD }.actorKind)
    }

    @Test fun `sql run titles follow the Explore run-title rules`() {
        assertEquals("orders · select * from orders", sqlRunTitle("select * from orders"))
        assertEquals("SQL query", sqlRunTitle("  -- only a comment\n  "))
        assertEquals("SELECT 1", sqlRunTitle("SELECT 1"))
        assertEquals("events · SELECT id FROM `proj.ds`…", sqlRunTitle("SELECT id FROM `proj.ds`.`events` WHERE id > 1"))
    }

    private fun run(at: Instant): ExploreActivity {
        return ExploreActivity(Uuid.generateV7(), ExploreRunKind.SQL, ExploreRunState.RUNNING, at, null, teammate, CredentialSurface.AGENT, cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE, connector, null, null)
    }
}
