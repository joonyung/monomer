@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.dashboard.api.*
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class DashboardScheduleObservationTest {
    private val workspace = WorkspaceId(Uuid.parse("01942b04-6e00-7000-8000-000000000001"))
    private val dashboard = DashboardId(Uuid.parse("01942b04-6e00-7000-8000-000000000010"))
    private val session = DashboardSessionId(Uuid.parse("01942b04-6e00-7000-8000-000000000011"))
    private val original = QueryRunId(Uuid.parse("01942b04-6e00-7000-8000-000000000012"))
    private val now = Instant.parse("2026-09-20T00:00:00Z")
    private val definition = JsonObject(emptyMap())
    private val owner = mockk<DashboardOwnerPort>()
    private val schedules = mockk<DashboardScheduleOwnerPort>()
    private val observation = DashboardScheduleObservation(schedules, owner, Clock.fixed(now, ZoneOffset.UTC))

    init {
        coEvery { owner.read(workspace, dashboard) } returns DashboardOwnerOutcome.Success(DashboardOwnerDashboard(dashboard, workspace, definition, now, now, null, null, null))
    }

    @Test
    fun `occurrence observation reads only original runs and sanitizes terminal failure`() = runTest {
        val occurrence = DashboardScheduleOccurrenceView("daily", now, now, "ACCEPTED", null, definition, emptyMap(), session, mapOf("revenue" to original))
        coEvery { schedules.readOccurrence(workspace, dashboard, "daily", now) } returns occurrence
        coEvery { owner.readSessionQueryRun(ReadDashboardSessionQueryRunCommand(workspace, dashboard, session, original)) } returns DashboardOwnerOutcome.Success(
            DashboardOwnerQueryRun(original, dashboard, session, "revenue", "SEMANTIC", ConnectorId(Uuid.parse("01942b04-6e00-7000-8000-000000000013")), "FAILED", false, "EXECUTION_UNAVAILABLE", "secret warehouse error", emptyList(), now, now.plusSeconds(30), null, null, null, outputColumnDisplays = null, reportingTimeZone = null),
        )
        repeat(2) {
            val body = observation.read(workspace, dashboard, "daily", now)!!
            assertEquals(original.toString(), body.getValue("initialQueryRuns").jsonObject.getValue("revenue").jsonPrimitive.content)
            assertEquals("FAILED", body.getValue("resultState").jsonPrimitive.content)
            val run = body.getValue("queryRuns").jsonObject.getValue("revenue").jsonObject
            assertEquals(now.plusSeconds(30).toString(), run.getValue("updatedAt").jsonPrimitive.content)
            assertEquals("The query could not start before its dispatch deadline.", run.getValue("failureMessage").jsonPrimitive.content)
            assertFalse(body.toString().contains("secret"))
        }
        coVerify(exactly = 2) { owner.read(workspace, dashboard) }
        coVerify(exactly = 2) { owner.readSessionQueryRun(ReadDashboardSessionQueryRunCommand(workspace, dashboard, session, original)) }
        confirmVerified(owner)
    }

    @Test
    fun `preview failure preserves actual backlog and never hides current document`() = runTest {
        coEvery { schedules.preview(definition, now) } throws IllegalArgumentException("private rejected value")
        coEvery { schedules.listSchedules(workspace, dashboard) } returns listOf(DashboardScheduleStatus("daily", now.minusSeconds(600)))
        val body = observation.schedules(workspace, dashboard, false)!!
        assertEquals("SCHEDULE_PREVIEW_UNAVAILABLE", body.getValue("previewError").jsonPrimitive.content)
        assertEquals(now.minusSeconds(600).toString(), body.getValue("schedules").jsonArray.single().jsonObject.getValue("nextScheduledAt").jsonPrimitive.content)
        assertEquals(emptyList(), body.getValue("preview").jsonArray.toList())
        assertFalse(body.toString().contains("private"))
    }

    @Test
    fun `cursor cannot be replayed for a different dashboard`() = runTest {
        coEvery { schedules.listOccurrences(workspace, dashboard, null, 1) } returns DashboardScheduleOccurrencePage(emptyList(), DashboardScheduleOccurrenceCursor(now, "daily"))
        val cursor = observation.list(workspace, dashboard, null, 1)!!.getValue("nextCursor").jsonPrimitive.content
        val other = DashboardId(Uuid.parse("01942b04-6e00-7000-8000-000000000099"))
        coEvery { owner.read(workspace, other) } returns DashboardOwnerOutcome.Success(DashboardOwnerDashboard(other, workspace, definition, now, now, null, null, null))
        assertFailsWith<IllegalArgumentException> { observation.list(workspace, other, cursor, 1) }
        coVerify(exactly = 1) { schedules.listOccurrences(workspace, dashboard, null, 1) }
    }
}
