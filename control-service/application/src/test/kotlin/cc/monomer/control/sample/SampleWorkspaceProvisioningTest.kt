package cc.monomer.control.sample

import cc.monomer.identity.api.*
import cc.monomer.control.ControlAdmissionFacade
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.ZoneOffset
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class SampleWorkspaceProvisioningTest {
    private fun lock(organization: OrganizationId): PostgresSampleWorkspaceLock = mockk<PostgresSampleWorkspaceLock>().also { lock ->
        coEvery { lock.withLock<Unit>(organization, any()) } coAnswers { secondArg<suspend () -> Unit>().invoke() }
    }

    @Test
    fun `start returns provisioning immediately suppresses duplicate local jobs and seeds twenty metrics and four dashboards`() = runTest {
        val workspaces = MemorySampleWorkspaces()
        val fixture = SampleProvisioningFixture(workspaces, workspaces.workspaceId)
        fixture.ingestionRunning = true
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val coordinator = fixture.coordinator(lock(workspaces.organization), scope)
            val first = coordinator.start(workspaces.actor, workspaces.organization)
            assertEquals("acme-cloud@3", first.sample.dataset)
            assertEquals(WorkspaceSampleState.PROVISIONING, first.sample.state)
            assertEquals(0, fixture.startedIngests.get())
            runCurrent()
            val second = coordinator.start(workspaces.actor, workspaces.organization)
            assertEquals(first.workspaceId, second.workspaceId)
            assertEquals(1, fixture.startedIngests.get())
            fixture.ingestionRunning = false
            advanceTimeBy(250); runCurrent()
            assertEquals(WorkspaceSampleState.READY, workspaces.value!!.sample.state)
            assertEquals(listOf("acme-cloud@3"), fixture.connectorDatasets)
            assertEquals(20, fixture.metrics.size)
            assertEquals(4, fixture.createdDashboards.size)
            assertEquals(1, workspaces.creations)
            assertTrue(fixture.actors.all { it == workspaces.actor })
        } finally { scope.cancel() }
    }

    @Test
    fun `seed definitions use the unattributed seed owners with initiator creation authority and sample receipt keys`() = runTest {
        val workspaces = MemorySampleWorkspaces()
        val fixture = SampleProvisioningFixture(workspaces, workspaces.workspaceId)
        val sample = workspaces.create(workspaces.actor, workspaces.organization, "acme-cloud@3")
        fixture.coordinator(lock(workspaces.organization), backgroundScope).complete(sample)
        val seed = SampleDefinitionBundle().read("acme-cloud@3", fixture.connectorId, java.time.LocalDate.parse("2026-10-12"))
        coVerify(exactly = seed.models.size) { fixture.semantic.seedModel(match {
            it.authorization.actor.id == workspaces.actor && it.authorization.intent == MutationIntent.SEMANTIC_MODEL_CREATE &&
                it.semanticModelId == null && it.idempotencyKey == "sample:acme-cloud@3:model:${it.definition.name.value}"
        }) }
        coVerify(exactly = seed.metrics.size) { fixture.semantic.seedMetric(match {
            it.authorization.actor.id == workspaces.actor && it.authorization.intent == MutationIntent.METRIC_CREATE &&
                it.metricId == null && it.idempotencyKey == "sample:acme-cloud@3:metric:${it.definition.name.value}"
        }) }
        coVerify(exactly = seed.dashboards.size) { fixture.dashboards.seed(match {
            it.workspaceId == sample.workspaceId && it.receiptActorId == workspaces.actor.toString() &&
                it.idempotencyKey.removePrefix("sample:acme-cloud@3:") in seed.dashboards.keys && it.createdAt == sample.createdAt
        }) }
        assertEquals(WorkspaceSampleState.READY, workspaces.value!!.sample.state)
    }

    @Test
    fun `failure right after workspace creation preserves marker and explicit retry resumes`() = runTest {
        val workspaces = MemorySampleWorkspaces()
        val fixture = SampleProvisioningFixture(workspaces, workspaces.workspaceId)
        fixture.failBeforeConnector = true
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val coordinator = fixture.coordinator(lock(workspaces.organization), scope)
            val first = coordinator.start(workspaces.actor, workspaces.organization)
            advanceUntilIdle()
            assertEquals(WorkspaceSampleState.PROVISIONING, workspaces.value!!.sample.state)
            val second = coordinator.start(workspaces.actor, workspaces.organization)
            advanceUntilIdle()
            assertEquals(first.workspaceId, second.workspaceId)
            assertEquals(WorkspaceSampleState.READY, workspaces.value!!.sample.state)
            assertEquals(1, workspaces.creations)
        } finally { scope.cancel() }
    }

    @Test
    fun `catalog failure and lost semantic response resume without duplicate definitions`() = runTest {
        val workspaces = MemorySampleWorkspaces()
        val fixture = SampleProvisioningFixture(workspaces, workspaces.workspaceId)
        val coordinator = fixture.coordinator(lock(workspaces.organization), backgroundScope)
        val sample = workspaces.create(workspaces.actor, workspaces.organization, "acme-cloud@1")
        fixture.failedIngests = 1
        assertFailsWith<IllegalStateException> { coordinator.complete(sample) }
        assertTrue(fixture.models.isEmpty())
        fixture.failAfterModel = true
        assertFailsWith<IllegalStateException> { coordinator.complete(sample) }
        assertEquals(1, fixture.models.size)
        coordinator.complete(sample)
        assertEquals(14, fixture.metrics.size)
        assertEquals(2, fixture.createdDashboards.size)
        assertEquals(WorkspaceSampleState.READY, workspaces.value!!.sample.state)
    }

    @Test
    fun `lost dashboard response replay is stable across days and another admin keeps original actor`() = runTest {
        val workspaces = MemorySampleWorkspaces()
        val fixture = SampleProvisioningFixture(workspaces, workspaces.workspaceId)
        val sample = workspaces.create(workspaces.actor, workspaces.organization, "acme-cloud@1")
        fixture.failAfterDashboard = true
        assertFailsWith<IllegalStateException> { fixture.coordinator(lock(workspaces.organization), backgroundScope).complete(sample) }
        val other = PrincipalId.generate()
        workspaces.admins.add(other)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val laterClock = Clock.fixed(fixture.now.plusSeconds(30 * 86400), ZoneOffset.UTC)
            val coordinator = fixture.coordinator(lock(workspaces.organization), scope, laterClock)
            coordinator.start(other, workspaces.organization)
            advanceUntilIdle()
            assertEquals("acme-cloud@1", workspaces.value!!.sample.dataset)
            assertTrue(fixture.connectorDatasets.all { it == "acme-cloud@1" })
            assertTrue(fixture.createdDashboards.keys.all { it.startsWith("sample:acme-cloud@1:") })
            assertEquals(setOf(workspaces.actor, other), workspaces.owners)
            assertEquals(2, fixture.createdDashboards.size)
            assertTrue(fixture.actors.all { it == workspaces.actor })
            val initial = fixture.originalDashboardInputs.values.first().getValue("variables").jsonObject.getValue("date_range").jsonObject.getValue("initial").jsonObject
            assertEquals("2026-07-14", initial.getValue("start").jsonPrimitive.content)
            assertEquals("2026-10-11", initial.getValue("end").jsonPrimitive.content)
        } finally { scope.cancel() }
    }

    @Test
    fun `ready repeat does not recreate renamed dashboards or overwritten metric definitions`() = runTest {
        val workspaces = MemorySampleWorkspaces()
        val fixture = SampleProvisioningFixture(workspaces, workspaces.workspaceId)
        val sample = workspaces.create(workspaces.actor, workspaces.organization, "acme-cloud@1")
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val coordinator = fixture.coordinator(lock(workspaces.organization), scope)
            coordinator.complete(sample)
            val key = fixture.createdDashboards.keys.first()
            val original = fixture.createdDashboards.getValue(key)
            val renamed = JsonObject(original.definition + ("meta" to JsonObject(original.definition.getValue("meta").jsonObject + ("title" to JsonPrimitive("My edited dashboard")))))
            fixture.createdDashboards[key] = original.copy(definition = renamed)
            val count = fixture.startedIngests.get()
            val opened = coordinator.start(workspaces.actor, workspaces.organization)
            advanceUntilIdle()
            assertEquals("acme-cloud@1", opened.sample.dataset)
            assertEquals(sample.workspaceId, opened.workspaceId)
            assertEquals(WorkspaceSampleState.READY, opened.sample.state)
            assertEquals(count, fixture.startedIngests.get())
            assertEquals(renamed, fixture.createdDashboards.getValue(key).definition)
            assertEquals(2, fixture.createdDashboards.size)
        } finally { scope.cancel() }
    }

    @Test
    fun `lost initiator membership fails closed without provisioning writes`() = runTest {
        val workspaces = MemorySampleWorkspaces()
        val fixture = SampleProvisioningFixture(workspaces, workspaces.workspaceId)
        val sample = workspaces.create(workspaces.actor, workspaces.organization, "acme-cloud@1")
        workspaces.evidenceAvailable = false
        assertFailsWith<IllegalStateException> { fixture.coordinator(lock(workspaces.organization), backgroundScope).complete(sample) }
        assertEquals(0, fixture.startedIngests.get())
        assertTrue(fixture.models.isEmpty())
        assertEquals(WorkspaceSampleState.PROVISIONING, workspaces.value!!.sample.state)
    }

    @Test
    fun `definition bundle preserves v1 and v2 and supports v3 while refusing unknown versions`() {
        val connector = cc.monomer.connector.api.ConnectorId.generate()
        val date = java.time.LocalDate.parse("2026-10-12")
        val bundle = SampleDefinitionBundle()
        assertEquals(bundle.read("acme-cloud@1", connector, date), bundle.read("acme-cloud@2", connector, date))
        assertEquals(20, bundle.read("acme-cloud@3", connector, date).metrics.size)
        assertEquals(4, bundle.read("acme-cloud@3", connector, date).dashboards.size)
        assertFailsWith<IllegalStateException> { bundle.read("acme-cloud@4", connector, date) }
        assertFailsWith<IllegalArgumentException> { WorkspaceSampleMarker("acme-cloud@4", WorkspaceSampleState.PROVISIONING, PrincipalId.generate()) }
    }

    @Test
    fun `v3 paid activity cohorts use a twelve month date range while product inputs keep ninety days`() {
        val definitions = SampleDefinitionBundle().read(
            "acme-cloud@3", cc.monomer.connector.api.ConnectorId.generate(), java.time.LocalDate.parse("2026-10-12"),
        )
        val product = definitions.dashboards.getValue("product-engagement")
        val variables = product.getValue("variables").jsonObject
        val productDates = variables.getValue("date_range").jsonObject.getValue("initial").jsonObject
        val cohortDates = variables.getValue("cohort_date_range").jsonObject.getValue("initial").jsonObject
        assertEquals("2026-07-14", productDates.getValue("start").jsonPrimitive.content)
        assertEquals("2026-10-11", productDates.getValue("end").jsonPrimitive.content)
        assertEquals("2025-10-12", cohortDates.getValue("start").jsonPrimitive.content)
        assertEquals("2026-10-11", cohortDates.getValue("end").jsonPrimitive.content)
        val bindings = product.getValue("queries").jsonObject.getValue("cohort_activity").jsonObject.getValue("bindings").jsonObject
        for (endpoint in listOf("start", "end")) {
            val binding = bindings.getValue(endpoint).jsonObject
            assertEquals("VARIABLE", binding.getValue("kind").jsonPrimitive.content)
            assertEquals("cohort_date_range", binding.getValue("name").jsonPrimitive.content)
            assertEquals(endpoint, binding.getValue("endpoint").jsonPrimitive.content)
        }
        val query = product.getValue("queries").jsonObject.getValue("cohort_activity").jsonObject
        assertEquals(listOf("cohort_month", "months_since_conversion", "cohort_accounts", "active_accounts", "activity_retention", "observed_through"), query.getValue("columns").jsonArray.map { it.jsonPrimitive.content })
        val widget = product.getValue("widgets").jsonObject.getValue("cohort_heatmap").jsonObject
        assertEquals("Paid-account activity retention by conversion month", widget.getValue("title").jsonPrimitive.content)
        val source = widget.getValue("module").jsonObject.getValue("source").jsonPrimitive.content
        assertTrue(source.contains("Denominator: accounts that converted to paid in that month within this date range."))
        assertTrue(source.contains("on or after conversion in the month"))
        assertTrue(source.contains("Date range only; all regions and plans."))
        assertFalse(source.contains("signup"))
    }

    @Test
    fun `sample admission issuer binds current initiator owner evidence and only seed intents`() = runTest {
        val workspaces = MemorySampleWorkspaces()
        val sample = workspaces.create(workspaces.actor, workspaces.organization, "acme-cloud@1")
        val evidence = workspaces.readInitiatorEvidence(sample.workspaceId, sample.sample.dataset)!!
        val mutation = ControlAdmissionFacade.sampleWorkspaceProvisioningMutation(sample, evidence, MutationIntent.METRIC_CREATE, sample.createdAt)
        assertEquals(evidence.actor, mutation.actor)
        assertEquals(evidence.organizationPlan, mutation.organizationPlan)
        assertEquals(evidence.authorizationRevision, mutation.authorizationRevision)
        assertEquals(CredentialKind.SESSION, mutation.credentialKind)
        assertEquals(CredentialSurface.WEB, mutation.credentialSurface)
        assertNull(mutation.agentClientId)
        val execution = ControlAdmissionFacade.sampleWorkspaceCatalogIngest(sample, evidence, sample.createdAt)
        assertEquals(ExecutionIntent.CATALOG_INGEST, execution.intent)
        assertEquals(evidence.workspaceId, execution.workspaceId)
        assertEquals(evidence.actor, execution.actor)
        for (invalid in listOf(evidence.copy(workspaceId = WorkspaceId.generate()),
            evidence.copy(organizationId = OrganizationId.generate()),
            evidence.copy(actor = CanonicalPrincipal(PrincipalId.generate(), PrincipalKind.USER)),
            evidence.copy(role = WorkspaceRole.EDITOR))) {
            assertFailsWith<IllegalArgumentException> { ControlAdmissionFacade.sampleWorkspaceProvisioningMutation(sample, invalid, MutationIntent.METRIC_CREATE, sample.createdAt) }
            assertFailsWith<IllegalArgumentException> { ControlAdmissionFacade.sampleWorkspaceCatalogIngest(sample, invalid, sample.createdAt) }
        }
        assertFailsWith<IllegalArgumentException> {
            ControlAdmissionFacade.sampleWorkspaceProvisioningMutation(sample, evidence, MutationIntent.CONNECTOR_DELETE, sample.createdAt)
        }
        assertFailsWith<IllegalArgumentException> {
            ControlAdmissionFacade.sampleWorkspaceCatalogIngest(sample.copy(sample = sample.sample.copy(state = WorkspaceSampleState.READY)), evidence, sample.createdAt)
        }
    }
}
