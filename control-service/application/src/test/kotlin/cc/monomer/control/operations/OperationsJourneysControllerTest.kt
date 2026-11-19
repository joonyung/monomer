package cc.monomer.control.operations

import cc.monomer.connector.api.ConnectorOperationsQueries
import cc.monomer.dashboard.api.*
import cc.monomer.explore.api.*
import cc.monomer.identity.api.*
import cc.monomer.semantic.api.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.web.server.ResponseStatusException
import kotlin.test.*

class OperationsJourneysControllerTest {
    private val auth = mockk<OperationsAccessPort>()
    private val identity = mockk<IdentityOperationsQueries>()
    private val connectors = mockk<ConnectorOperationsQueries>()
    private val explore = mockk<ExploreOperationsQueries>()
    private val dashboards = mockk<DashboardOperationsQueries>()
    private val semantic = mockk<SemanticOperationsQueries>()
    private val access = mockk<OperationsAccess>()
    private val agentApps = object : AgentAppDisplays {
        override suspend fun read(clientIds: Set<AgentClientId>) = clientIds.mapNotNull { id ->
            appNames[id]?.let { id to AgentAppDisplay(id, it, null) }
        }.toMap()
    }
    private val clock = Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC)
    private val controller = OperationsJourneysController(auth, identity, connectors, explore, dashboards, semantic, agentApps, clock)
    private val client = WebTestClient.bindToController(controller).controllerAdvice(OperationsQueriesProblemHandler()).build()
    private val mapper = ObjectMapper()

    private val from = "2026-09-28T00:00:00Z"
    private val to = "2026-10-12T00:00:00Z"
    private val week1 = LocalDate.parse("2026-09-28")
    private val week2 = LocalDate.parse("2026-10-05")
    private val organizationA = "00000000-0000-0000-0000-00000000000a"
    private val organizationB = "00000000-0000-0000-0000-00000000000b"
    private val workspaceA = "00000000-0000-0000-0000-0000000000a1"
    private val workspaceB = "00000000-0000-0000-0000-0000000000b1"
    private val userA = "00000000-0000-0000-0000-000000000a01"
    private val userB = "00000000-0000-0000-0000-000000000b01"
    private val sharedUser = "00000000-0000-0000-0000-000000000c01"
    private val pageReader = "00000000-0000-0000-0000-000000000d01"
    private val claude = AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata")
    private val codex = AgentClientId("codex-registered-client")
    private val appNames = mapOf(claude to "Claude", codex to "Codex")
    private fun at(value: String) = Instant.parse(value)

    private val population = OperationsJourneyPopulation(
        listOf(
            OperationsJourneyOrganization(organizationB, "Beta", at("2026-10-01T00:00:00Z"), setOf(userB, sharedUser), setOf(workspaceB),
                listOf(OperationsJourneyContact("Bea", "bea@beta.example"))),
            OperationsJourneyOrganization(organizationA, "Acme", at("2026-09-28T08:00:00Z"), setOf(userA, sharedUser), setOf(workspaceA),
                listOf(OperationsJourneyContact("Ada", "ada@acme.example"))),
        ),
        3,
    )
    private val exploreActivity = OperationsExploreJourneyActivity(
        listOf(
            OperationsExploreActorMilestone(workspaceA, userA, at("2026-09-29T00:00:00Z"), at("2026-09-29T01:00:00Z")),
            OperationsExploreActorMilestone(workspaceA, pageReader, at("2026-09-28T09:00:00Z"), at("2026-09-28T09:00:00Z")),
        ),
        listOf(
            OperationsExploreWeeklyRuns(workspaceA, week1, userA, CredentialSurface.AGENT, claude, false, 5, 4),
            OperationsExploreWeeklyRuns(workspaceA, week1, userA, null, null, false, 1, 1),
            OperationsExploreWeeklyRuns(workspaceA, week1, pageReader, CredentialSurface.WEB, null, false, 3, 3),
            OperationsExploreWeeklyRuns(workspaceB, week2, sharedUser, CredentialSurface.WEB, null, false, 2, 0),
            OperationsExploreWeeklyRuns(workspaceA, week2, sharedUser, CredentialSurface.AGENT, codex, false, 1, 1),
        ),
    )
    private val dashboardActivity = OperationsDashboardJourneyActivity(
        mapOf(workspaceB to at("2026-10-06T00:00:00Z")),
        listOf(OperationsDashboardActorMilestone(workspaceB, sharedUser, at("2026-10-05T10:00:00Z"), at("2026-10-05T09:00:00Z"), at("2026-10-05T10:00:00Z"), at("2026-10-05T10:00:00Z"))),
        listOf(OperationsDashboardWeeklyQueryRuns(workspaceB, week2, sharedUser, CredentialSurface.AGENT, claude, 2, 2)),
        listOf(OperationsDashboardWeeklyDefinitionChanges(workspaceA, week1, userA, CredentialSurface.AGENT, claude, 2)),
    )
    private val semanticActivity = OperationsSemanticJourneyActivity(
        mapOf(workspaceA to at("2026-10-01T00:00:00Z")),
        listOf(OperationsSemanticActorMilestone(workspaceA, userA, at("2026-09-30T00:00:00Z"), null)),
        listOf(
            OperationsSemanticWeeklyDefinitionChanges(workspaceA, week1, userA, CredentialSurface.WEB, null, 1),
            OperationsSemanticWeeklyDefinitionChanges(workspaceA, week1, pageReader, null, null, 4),
        ),
    )

    init {
        coEvery { auth.authenticate(any()) } returns access
        every { access.userId } returns userA
        coEvery { identity.journeyPopulation(access, at(to), any(), 1_001) } returns population
        coEvery { identity.firstAgentAuthorizations(access, setOf(organizationA, organizationB)) } returns mapOf(organizationA to at("2026-09-20T00:00:00Z"))
        coEvery { connectors.firstSampleConnectorCreatedAt(access, any()) } returns emptyMap()
        coEvery { connectors.firstConnectorCreatedAt(access, setOf(workspaceA, workspaceB)) } returns
            mapOf(workspaceA to at("2026-09-28T08:10:00Z"), workspaceB to at("2026-10-02T00:00:00Z"))
        coEvery { explore.journeyActivity(access, setOf(workspaceA, workspaceB), at(from), at(to)) } returns exploreActivity
        coEvery { dashboards.journeyActivity(access, setOf(workspaceA, workspaceB), at(from), at(to)) } returns dashboardActivity
        coEvery { semantic.journeyActivity(access, setOf(workspaceA, workspaceB), at(from), at(to)) } returns semanticActivity
    }

    private fun request(query: String) = MockServerWebExchange.from(
        MockServerHttpRequest.get("/ops/v1/journeys?$query").header("Authorization", "Bearer token").build(),
    )
    private fun journeys(query: String = "from=$from&to=$to"): JsonNode {
        val body = client.get().uri("/ops/v1/journeys?$query").header("Authorization", "Bearer token").exchange()
            .expectStatus().isOk.expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
            .expectBody(String::class.java).returnResult().responseBody!!
        assertFalse(listOf(userA, userB, sharedUser, pageReader, claude.value, codex.value, workspaceA, workspaceB, "private detail").any(body::contains))
        return mapper.readTree(body)
    }
    private fun JsonNode.item(organizationId: String) = path("items").single { it.path("organizationId").asText() == organizationId }
    private fun JsonNode.week(index: Int) = path("weekly")[index]

    @Test fun `journeys require operations authorization before validation or owner reads`() = runBlocking {
        coEvery { auth.authenticate(any()) } throws OperationsForbiddenException()
        assertFailsWith<OperationsForbiddenException> { controller.journeys(request("from=invalid")) }
        assertFailsWith<OperationsUnauthenticatedException> {
            controller.journeys(MockServerWebExchange.from(MockServerHttpRequest.get("/ops/v1/journeys?from=$from&to=$to").build()))
        }
        coVerify(exactly = 0) { identity.journeyPopulation(any(), any(), any(), any()) }
    }

    @Test fun `the period end tolerates five minutes of client clock skew before Monday`() = runBlocking {
        fun controllerAt(instant: String) =
            OperationsJourneysController(auth, identity, connectors, explore, dashboards, semantic, agentApps, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC))
        val periodEndingNextMonday = "from=$from&to=$to"
        controllerAt("2026-10-04T23:56:00Z").journeys(request(periodEndingNextMonday))
        val early = assertFailsWith<ResponseStatusException> { controllerAt("2026-10-04T23:54:00Z").journeys(request(periodEndingNextMonday)) }
        assertEquals(400, early.statusCode.value())
    }

    @Test fun `invalid periods exclusions and paging parameters are bad requests`() = runBlocking {
        val tooMany = (1..51).joinToString("&") { "excludeOrganizationId=00000000-0000-0000-0000-%012d".format(it) }
        for (query in listOf(
            "to=$to",
            "from=$from",
            "from=$from&from=$from&to=$to",
            "from=2026-09-29T00:00:00Z&to=$to",
            "from=2026-09-28T00:00:01Z&to=$to",
            "from=2026-09-28T00:00:00.001Z&to=$to",
            "from=$to&to=$to",
            "from=$to&to=$from",
            "from=2026-04-06T00:00:00Z&to=$to",
            "from=$from&to=2026-10-19T00:00:00Z",
            "from=invalid&to=$to",
            "from=$from&to=$to&$tooMany",
            "from=$from&to=$to&excludeOrganizationId=not-a-uuid",
            "from=$from&to=$to&excludeOrganizationId=00000000-0000-0000-0000-00000000000A",
            "from=$from&to=$to&excludeOrganizationId=",
            "from=$from&to=$to&cursor=abc",
            "from=$from&to=$to&limit=50",
        )) {
            val failure = assertFailsWith<ResponseStatusException>(query) { controller.journeys(request(query)) }
            assertEquals(400, failure.statusCode.value(), query)
            assertEquals("Invalid operations query.", failure.reason)
        }
        coVerify(exactly = 0) { identity.journeyPopulation(any(), any(), any(), any()) }
        client.get().uri("/ops/v1/journeys?from=$from&to=$to&limit=50").header("Authorization", "Bearer token").exchange()
            .expectStatus().isBadRequest.expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
            .expectBody().jsonPath("$.detail").isEqualTo("Invalid operations query.")
    }

    @Test fun `twenty six weeks up to the in-progress week and fifty duplicate exclusions are accepted`() = runBlocking {
        val excluded = (1..25).map { "00000000-0000-0000-0000-%012d".format(it) }
        val exclusions = (excluded + excluded).joinToString("&") { "excludeOrganizationId=$it" }
        coEvery { identity.journeyPopulation(access, at(to), excluded.toSet(), 1_001) } returns OperationsJourneyPopulation(emptyList(), 2)
        coEvery { identity.firstAgentAuthorizations(access, emptySet()) } returns emptyMap()
        coEvery { connectors.firstConnectorCreatedAt(access, emptySet()) } returns emptyMap()
        coEvery { explore.journeyActivity(access, emptySet(), at("2026-04-13T00:00:00Z"), at(to)) } returns OperationsExploreJourneyActivity(emptyList(), emptyList())
        coEvery { dashboards.journeyActivity(access, emptySet(), at("2026-04-13T00:00:00Z"), at(to)) } returns OperationsDashboardJourneyActivity(emptyMap(), emptyList(), emptyList(), emptyList())
        coEvery { semantic.journeyActivity(access, emptySet(), at("2026-04-13T00:00:00Z"), at(to)) } returns OperationsSemanticJourneyActivity(emptyMap(), emptyList(), emptyList())
        val response = controller.journeys(request("from=2026-04-13T00:00:00Z&to=$to&$exclusions"))
        assertEquals(26, response.weeks.size)
        assertEquals("2026-04-13", response.weeks.first())
        assertEquals("2026-10-05", response.weeks.last())
        assertEquals(2L, response.excludedOrganizations)
        assertEquals(26, response.weeklyTotals.size)
        assertTrue(response.weeklyTotals.all { it.activeOrganizations == 0L && it.activeUsers == 0L })
    }

    @Test fun `member activity is attributed per organization and users are counted once across organizations`() {
        val response = journeys()
        assertEquals(listOf("2026-09-28", "2026-10-05"), response.path("weeks").map { it.asText() })
        assertEquals(3, response.path("excludedOrganizations").asInt())
        assertEquals(0, response.path("unavailableSources").size())
        assertEquals(listOf(organizationB, organizationA), response.path("items").map { it.path("organizationId").asText() })
        assertEquals(listOf(1, 2), response.path("weeklyTotals").map { it.path("activeOrganizations").asInt() })
        assertEquals(listOf(1, 1), response.path("weeklyTotals").map { it.path("activeUsers").asInt() })

        val acme = response.item(organizationA)
        assertEquals(2, acme.path("memberCount").asInt())
        assertEquals(1, acme.path("workspaceCount").asInt())
        assertEquals("ada@acme.example", acme.path("contacts")[0].path("email").asText())
        assertEquals(listOf("Claude", "Codex"), acme.path("agentApps").map { it.asText() })
        val acmeFirstWeek = acme.week(0)
        assertTrue(acmeFirstWeek.path("active").asBoolean())
        assertEquals(1, acmeFirstWeek.path("activeUsers").asInt())
        assertEquals(1, acmeFirstWeek.path("agentClients").asInt())
        assertEquals(listOf(6, 5, 4, 1), listOf("total", "succeeded", "succeededByAgent", "succeededUnknownSurface").map { acmeFirstWeek.path("exploreRuns").path(it).asInt() })
        assertEquals(listOf(0, 0, 0), listOf("total", "succeeded", "succeededByAgent").map { acmeFirstWeek.path("dashboardRuns").path(it).asInt() })
        assertEquals(listOf(3, 2, 0), listOf("total", "byAgent", "unknownSurface").map { acmeFirstWeek.path("definitionChanges").path(it).asInt() })
        assertEquals(1, acme.week(1).path("activeUsers").asInt())

        val beta = response.item(organizationB)
        assertFalse(beta.week(0).path("active").asBoolean())
        assertTrue(beta.week(0).path("active").isBoolean)
        assertEquals(0, beta.week(0).path("activeUsers").asInt())
        assertTrue(beta.week(1).path("active").asBoolean())
        assertEquals(listOf(2, 0), listOf("total", "succeeded").map { beta.week(1).path("exploreRuns").path(it).asInt() })
        assertEquals(listOf(2, 2, 2), listOf("total", "succeeded", "succeededByAgent").map { beta.week(1).path("dashboardRuns").path(it).asInt() })
        assertEquals(listOf("Claude"), beta.path("agentApps").map { it.asText() })

        val acmeMilestones = acme.path("milestones")
        assertEquals("2026-09-28T08:10:00Z", acmeMilestones.path("firstConnectorAt").asText())
        assertEquals("2026-09-20T00:00:00Z", acmeMilestones.path("firstAgentAuthorizedAt").asText())
        assertEquals("2026-09-29T00:00:00Z", acmeMilestones.path("firstSucceededResultAt").asText())
        assertEquals("2026-09-30T00:00:00Z", acmeMilestones.path("firstMetricAt").asText())
        assertTrue(acmeMilestones.path("firstDashboardAt").isNull)
        assertEquals("2026-09-29T01:00:00Z", acmeMilestones.path("firstAgentActivityAt").asText())
        val betaMilestones = beta.path("milestones")
        assertTrue(betaMilestones.path("firstAgentAuthorizedAt").isNull)
        assertEquals("2026-10-05T10:00:00Z", betaMilestones.path("firstSucceededResultAt").asText())
        assertEquals("2026-10-05T09:00:00Z", betaMilestones.path("firstDashboardAt").asText())
        assertTrue(betaMilestones.path("firstMetricAt").isNull)
    }

    @Test fun `connector and agent grant failures null only their milestones`() {
        coEvery { connectors.firstConnectorCreatedAt(any(), any()) } throws DataAccessResourceFailureException("private detail")
        coEvery { identity.firstAgentAuthorizations(any(), any()) } throws CannotCreateTransactionException("private detail")
        val response = journeys()
        assertEquals(listOf("connector", "agentGrants"), response.path("unavailableSources").map { it.asText() })
        val acme = response.item(organizationA)
        assertTrue(acme.path("milestones").path("firstConnectorAt").isNull)
        assertTrue(acme.path("milestones").path("firstSampleConnectorAt").isNull)
        assertTrue(acme.path("milestones").path("firstSampleAgentRunAt").isNull)
        assertTrue(acme.path("milestones").path("firstAgentAuthorizedAt").isNull)
        assertEquals("2026-09-29T00:00:00Z", acme.path("milestones").path("firstSucceededResultAt").asText())
        assertEquals(listOf(1, 2), response.path("weeklyTotals").map { it.path("activeOrganizations").asInt() })
    }

    @Test fun `sample only connectors do not reach warehouse milestone and counts separate mixed runs`() {
        coEvery { connectors.firstConnectorCreatedAt(any(), any()) } returns emptyMap()
        coEvery { connectors.firstSampleConnectorCreatedAt(any(), any()) } returns mapOf(workspaceA to at("2026-09-28T08:10:00Z"))
        coEvery { explore.journeyActivity(any(), any(), any(), any()) } returns exploreActivity.copy(
            weeklyRuns = exploreActivity.weeklyRuns.map {
                it.copy(sampleDataset = it.credentialSurface == CredentialSurface.AGENT)
            },
        )
        val response = journeys()
        val acme = response.item(organizationA)
        assertTrue(acme.path("milestones").path("firstConnectorAt").isNull)
        assertEquals("2026-09-28T08:10:00Z", acme.path("milestones").path("firstSampleConnectorAt").asText())
        assertEquals("2026-09-29T01:00:00Z", acme.path("milestones").path("firstSampleAgentRunAt").asText())
        assertEquals(listOf(6, 5, 5, 4, 1, 1), listOf("total", "succeeded", "sampleTotal", "sampleSucceeded", "externalTotal", "externalSucceeded")
            .map { acme.week(0).path("exploreRuns").path(it).asInt() })
        assertEquals(0, response.item(organizationB).week(1).path("exploreRuns").path("sampleTotal").asInt())
    }

    @Test fun `sample agent milestone uses actual runs across history and current members only`() {
        coEvery { connectors.firstSampleConnectorCreatedAt(any(), any()) } returns mapOf(workspaceA to at("2026-08-01T00:00:00Z"))
        coEvery { explore.journeyActivity(any(), any(), any(), any()) } returns OperationsExploreJourneyActivity(
            listOf(OperationsExploreActorMilestone(workspaceA, pageReader, null, at("2026-08-01T01:00:00Z"))), emptyList(),
        )
        coEvery { dashboards.journeyActivity(any(), any(), any(), any()) } returns OperationsDashboardJourneyActivity(
            mapOf(workspaceA to at("2026-08-01T00:00:00Z")),
            listOf(OperationsDashboardActorMilestone(workspaceA, userA, null, at("2026-08-01T00:00:00Z"), at("2026-08-02T00:00:00Z"), null)),
            emptyList(), emptyList(),
        )
        assertTrue(journeys().item(organizationA).path("milestones").path("firstSampleAgentRunAt").isNull,
            "seeded WEB writes and AGENT definition changes are not Agent execution")
        coEvery { dashboards.journeyActivity(any(), any(), any(), any()) } returns OperationsDashboardJourneyActivity(
            emptyMap(), listOf(OperationsDashboardActorMilestone(workspaceA, userA, null, null, at("2026-08-02T00:00:00Z"), at("2026-08-03T12:34:56Z"))),
            emptyList(), emptyList(),
        )
        assertEquals("2026-08-03T12:34:56Z", journeys().item(organizationA).path("milestones").path("firstSampleAgentRunAt").asText(),
            "all-history timestamp is preserved even when no period weekly execution exists")
    }

    @Test fun `missing sample connector source is not measured while recorded engine counts remain known`() {
        coEvery { connectors.firstSampleConnectorCreatedAt(any(), any()) } throws DataAccessResourceFailureException("private detail")
        val response = journeys()
        assertEquals(listOf("connector"), response.path("unavailableSources").map { it.asText() })
        val acme = response.item(organizationA)
        assertTrue(acme.path("milestones").path("firstConnectorAt").isNull)
        assertTrue(acme.path("milestones").path("firstSampleConnectorAt").isNull)
        assertTrue(acme.path("milestones").path("firstSampleAgentRunAt").isNull)
        assertEquals(6, acme.week(0).path("exploreRuns").path("externalTotal").asInt())
        assertEquals(0, acme.week(0).path("exploreRuns").path("sampleTotal").asInt())
    }

    @Test fun `explore failure nulls its counts and every combined value`() {
        coEvery { explore.journeyActivity(any(), any(), any(), any()) } throws DataAccessResourceFailureException("private detail")
        val response = journeys()
        assertEquals(listOf("explore"), response.path("unavailableSources").map { it.asText() })
        assertTrue(response.path("weeklyTotals").all { it.path("activeOrganizations").isNull && it.path("activeUsers").isNull })
        val acme = response.item(organizationA)
        assertTrue(acme.week(0).path("active").asBoolean())
        assertTrue(acme.week(1).path("active").isNull)
        assertTrue(acme.week(0).path("exploreRuns").isNull)
        assertTrue(acme.week(0).path("activeUsers").isNull && acme.week(0).path("agentClients").isNull)
        assertEquals(3, acme.week(0).path("definitionChanges").path("total").asInt())
        assertTrue(acme.path("milestones").path("firstSucceededResultAt").isNull)
        assertTrue(acme.path("milestones").path("firstAgentActivityAt").isNull)
        assertEquals("2026-09-30T00:00:00Z", acme.path("milestones").path("firstMetricAt").asText())
        assertTrue(acme.path("agentApps").isNull)
        val beta = response.item(organizationB)
        assertTrue(beta.week(0).path("active").isNull)
        assertTrue(beta.week(1).path("active").asBoolean())
    }

    @Test fun `dashboard failure nulls dashboard runs definition changes and dashboard milestones`() {
        coEvery { dashboards.journeyActivity(any(), any(), any(), any()) } throws DataAccessResourceFailureException("private detail")
        val response = journeys()
        assertEquals(listOf("dashboard"), response.path("unavailableSources").map { it.asText() })
        val beta = response.item(organizationB)
        assertTrue(beta.week(1).path("active").isNull)
        assertTrue(beta.week(1).path("dashboardRuns").isNull && beta.week(1).path("definitionChanges").isNull)
        assertEquals(2, beta.week(1).path("exploreRuns").path("total").asInt())
        assertTrue(beta.path("milestones").path("firstDashboardAt").isNull)
        assertTrue(beta.path("milestones").path("firstSucceededResultAt").isNull)
        val acme = response.item(organizationA)
        assertTrue(acme.week(0).path("active").asBoolean())
        assertTrue(acme.path("milestones").path("firstAgentActivityAt").isNull)
        assertTrue(response.path("weeklyTotals").all { it.path("activeUsers").isNull })
    }

    @Test fun `semantic failure nulls definition changes and metric milestones`() {
        coEvery { semantic.journeyActivity(any(), any(), any(), any()) } throws DataAccessResourceFailureException("private detail")
        val response = journeys()
        assertEquals(listOf("semantic"), response.path("unavailableSources").map { it.asText() })
        val acme = response.item(organizationA)
        assertTrue(acme.week(0).path("definitionChanges").isNull)
        assertEquals(1, acme.week(0).path("exploreRuns").path("succeededUnknownSurface").asInt())
        assertTrue(acme.path("milestones").path("firstMetricAt").isNull)
        assertTrue(acme.path("milestones").path("firstAgentActivityAt").isNull)
        assertEquals("2026-09-29T00:00:00Z", acme.path("milestones").path("firstSucceededResultAt").asText())
        assertTrue(response.item(organizationB).week(0).path("active").isNull)
        assertTrue(response.path("weeklyTotals").all { it.path("activeOrganizations").isNull })
    }

    @Test fun `agent app name failure fails the whole request without a partial list`() {
        val failingNames = object : AgentAppDisplays {
            override suspend fun read(clientIds: Set<AgentClientId>): Map<AgentClientId, AgentAppDisplay> =
                throw CannotCreateTransactionException("private detail")
        }
        WebTestClient.bindToController(OperationsJourneysController(auth, identity, connectors, explore, dashboards, semantic, failingNames, clock))
            .controllerAdvice(OperationsQueriesProblemHandler()).build()
            .get().uri("/ops/v1/journeys?from=$from&to=$to").header("Authorization", "Bearer token").exchange()
            .expectStatus().isEqualTo(503).expectBody().jsonPath("$.detail").isEqualTo("Operations data is unavailable.")
    }

    @Test fun `identity population failure and population over the cap fail the whole request`() {
        coEvery { identity.journeyPopulation(any(), any(), any(), any()) } throws DataAccessResourceFailureException("private detail")
        client.get().uri("/ops/v1/journeys?from=$from&to=$to").header("Authorization", "Bearer token").exchange()
            .expectStatus().isEqualTo(503).expectBody().jsonPath("$.detail").isEqualTo("Operations data is unavailable.")
        coEvery { identity.journeyPopulation(any(), any(), any(), any()) } throws CannotCreateTransactionException("private detail")
        client.get().uri("/ops/v1/journeys?from=$from&to=$to").header("Authorization", "Bearer token").exchange()
            .expectStatus().isEqualTo(503).expectBody().jsonPath("$.detail").isEqualTo("Operations data is unavailable.")
        val organizations = (1..1_001).map {
            OperationsJourneyOrganization("00000000-0000-0000-0001-%012d".format(it), "Bulk", at("2026-09-01T00:00:00Z"), emptySet(), emptySet(), emptyList())
        }
        coEvery { identity.journeyPopulation(any(), any(), any(), any()) } returns OperationsJourneyPopulation(organizations, 0)
        client.get().uri("/ops/v1/journeys?from=$from&to=$to").header("Authorization", "Bearer token").exchange()
            .expectStatus().isEqualTo(422).expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
            .expectBody().jsonPath("$.detail").isEqualTo("Too many organizations for the journeys view.")
        coVerify(exactly = 0) { connectors.firstConnectorCreatedAt(any(), any()) }
        coVerify(exactly = 0) { explore.journeyActivity(any(), any(), any(), any()) }
    }
}
