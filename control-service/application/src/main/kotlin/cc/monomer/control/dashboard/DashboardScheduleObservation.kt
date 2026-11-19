@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.dashboard.api.*
import cc.monomer.identity.api.WorkspaceId
import kotlinx.serialization.json.*
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.Base64

@Component
class DashboardScheduleObservation(
    private val schedules: DashboardScheduleOwnerPort,
    private val dashboards: DashboardOwnerPort,
    private val clock: Clock,
) {
    suspend fun schedules(workspaceId: WorkspaceId, dashboardId: DashboardId, draft: Boolean): JsonObject? {
        val document = if (draft) {
            (dashboards.readDraft(workspaceId, dashboardId) as? DashboardOwnerOutcome.Success)?.value?.definition
        } else {
            (dashboards.read(workspaceId, dashboardId) as? DashboardOwnerOutcome.Success)?.value?.definition
        } ?: return null
        var previewError: String? = null
        val preview = try {
            schedules.preview(document, clock.instant())
        } catch (_: IllegalArgumentException) {
            previewError = "SCHEDULE_PREVIEW_UNAVAILABLE"
            emptyList()
        } catch (_: java.time.DateTimeException) {
            previewError = "SCHEDULE_PREVIEW_UNAVAILABLE"
            emptyList()
        } catch (_: ArithmeticException) {
            previewError = "SCHEDULE_PREVIEW_UNAVAILABLE"
            emptyList()
        }
        return buildJsonObject {
            put("schedules", JsonArray(if (draft) emptyList() else schedules.listSchedules(workspaceId, dashboardId).map {
                buildJsonObject { put("scheduleKey", it.scheduleKey); put("nextScheduledAt", it.nextScheduledAt.toString()) }
            }))
            put("preview", JsonArray(preview.map {
                buildJsonObject {
                    put("scheduleKey", it.scheduleKey); put("scheduledAt", it.scheduledAt.toString())
                    put("localTime", it.localTime); put("parameters", JsonObject(it.parameters))
                }
            }))
            put("previewError", previewError?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    suspend fun list(workspaceId: WorkspaceId, dashboardId: DashboardId, cursor: String?, limit: Int): JsonObject? {
        require(limit in 1..100)
        if (dashboards.read(workspaceId, dashboardId) !is DashboardOwnerOutcome.Success) return null
        val boundary = cursor?.let { decodeCursor(workspaceId, dashboardId, it) }
        val page = schedules.listOccurrences(workspaceId, dashboardId, boundary, limit)
        val items = page.items.map { occurrence(workspaceId, dashboardId, it) }
        return buildJsonObject {
            put("items", JsonArray(items))
            put("nextCursor", page.nextCursor?.let { JsonPrimitive(encodeCursor(workspaceId, dashboardId, it)) } ?: JsonNull)
        }
    }

    suspend fun read(workspaceId: WorkspaceId, dashboardId: DashboardId, scheduleKey: String, scheduledAt: Instant): JsonObject? {
        require(scheduleKey.matches(Regex("^[A-Za-z_][A-Za-z0-9_]{0,127}$")) && scheduleKey != "__proto__")
        if (dashboards.read(workspaceId, dashboardId) !is DashboardOwnerOutcome.Success) return null
        return schedules.readOccurrence(workspaceId, dashboardId, scheduleKey, scheduledAt)?.let { occurrence(workspaceId, dashboardId, it) }
    }

    private suspend fun occurrence(workspaceId: WorkspaceId, dashboardId: DashboardId, occurrence: DashboardScheduleOccurrenceView): JsonObject {
        val queryRuns = occurrence.initialQueryRuns.mapValues { (_, runId) ->
            occurrence.sessionId?.let { sessionId ->
                (dashboards.readSessionQueryRun(ReadDashboardSessionQueryRunCommand(workspaceId, dashboardId, sessionId, runId))
                    as? DashboardOwnerOutcome.Success)?.value
            }
        }
        val resultState = when {
            occurrence.outcome != "ACCEPTED" -> null
            queryRuns.values.any { it == null } -> "UNAVAILABLE"
            queryRuns.values.any { it?.state == "RUNNING" } -> "RUNNING"
            queryRuns.values.all { it?.state == "SUCCEEDED" && it.resultAvailable } -> "READY"
            queryRuns.values.any { it?.state == "SUCCEEDED" && it.resultAvailable } -> "PARTIAL_FAILURE"
            queryRuns.values.any { it?.state == "FAILED" || it?.state == "CANCELLED" } -> "FAILED"
            else -> "UNAVAILABLE"
        }
        return buildJsonObject {
            put("scheduleKey", occurrence.scheduleKey); put("scheduledAt", occurrence.scheduledAt.toString())
            put("decidedAt", occurrence.decidedAt.toString()); put("outcome", occurrence.outcome)
            put("reason", occurrence.reason?.let(::JsonPrimitive) ?: JsonNull)
            put("definition", occurrence.definition)
            put("parameters", occurrence.parameters?.let(::JsonObject) ?: JsonNull)
            put("sessionId", occurrence.sessionId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            put("initialQueryRuns", JsonObject(occurrence.initialQueryRuns.mapValues { JsonPrimitive(it.value.toString()) }))
            put("queryRuns", JsonObject(queryRuns.mapNotNull { (name, run) -> run?.let {
                name to buildJsonObject {
                    put("queryRunId", it.queryRunId.toString()); put("state", it.state)
                    put("failureCode", it.failureCode?.let(::JsonPrimitive) ?: JsonNull)
                    put("failureMessage", dashboardFailureMessage(it.failureCode, it.failureMessage)?.let(::JsonPrimitive) ?: JsonNull)
                    put("updatedAt", it.updatedAt.toString()); put("resultAvailable", it.resultAvailable)
                }
            } }.toMap()))
            put("resultState", resultState?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    private fun encodeCursor(workspaceId: WorkspaceId, dashboardId: DashboardId, cursor: DashboardScheduleOccurrenceCursor): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("1|$workspaceId|$dashboardId|${cursor.scheduledAt}|${cursor.scheduleKey}".toByteArray(Charsets.UTF_8))

    private fun decodeCursor(workspaceId: WorkspaceId, dashboardId: DashboardId, cursor: String): DashboardScheduleOccurrenceCursor = try {
        require(cursor.length <= 1024)
        val parts = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8).split('|')
        require(parts.size == 5 && parts[0] == "1" && parts[1] == workspaceId.toString() && parts[2] == dashboardId.toString())
        require(parts[4].matches(Regex("^[A-Za-z_][A-Za-z0-9_]{0,127}$")) && parts[4] != "__proto__")
        val scheduledAt = try { Instant.parse(parts[3]) } catch (_: java.time.DateTimeException) { throw IllegalArgumentException("Invalid occurrence cursor") }
        val boundary = DashboardScheduleOccurrenceCursor(scheduledAt, parts[4])
        require(encodeCursor(workspaceId, dashboardId, boundary) == cursor)
        boundary
    } catch (_: IllegalArgumentException) {
        throw DashboardOccurrenceCursorInvalidException()
    }
}

internal class DashboardOccurrenceCursorInvalidException : IllegalArgumentException("Invalid occurrence cursor")
