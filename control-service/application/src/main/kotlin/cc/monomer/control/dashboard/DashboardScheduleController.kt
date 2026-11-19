package cc.monomer.control.dashboard

import kotlinx.serialization.json.JsonObject
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.format.DateTimeParseException

@RestController
@RequestMapping("/v2/workspaces/{workspaceId}")
class DashboardScheduleController(
    private val authorizer: DashboardRequestAuthorizer,
    private val objectMapper: ObjectMapper,
    private val scheduleObservation: DashboardScheduleObservation,
) {

    @GetMapping("/dashboards/{dashboardId}/schedules", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun schedules(@PathVariable workspaceId: String, @PathVariable dashboardId: String, exchange: ServerWebExchange): JsonNode {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        return observationResponse(scheduleObservation.schedules(access.workspaceId, parseDashboardId(dashboardId), false))
    }

    @GetMapping("/dashboards/{dashboardId}/draft/schedules", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun draftSchedules(@PathVariable workspaceId: String, @PathVariable dashboardId: String, exchange: ServerWebExchange): JsonNode {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        return observationResponse(scheduleObservation.schedules(access.workspaceId, parseDashboardId(dashboardId), true))
    }

    @GetMapping("/dashboards/{dashboardId}/occurrences", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun occurrences(
        @PathVariable workspaceId: String, @PathVariable dashboardId: String,
        @RequestParam(required = false) cursor: String?, @RequestParam(defaultValue = "20") limit: Int,
        exchange: ServerWebExchange,
    ): JsonNode {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        if (limit !in 1..100) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid occurrence limit")
        return try {
            observationResponse(scheduleObservation.list(access.workspaceId, parseDashboardId(dashboardId), cursor, limit))
        } catch (_: DashboardOccurrenceCursorInvalidException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid occurrence cursor or limit")
        }
    }

    @GetMapping("/dashboards/{dashboardId}/occurrences/{scheduleKey}/{scheduledAt}", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun occurrence(
        @PathVariable workspaceId: String, @PathVariable dashboardId: String,
        @PathVariable scheduleKey: String, @PathVariable scheduledAt: String, exchange: ServerWebExchange,
    ): JsonNode {
        val access = authorizer.requireWorkspace(exchange, workspaceId)
        if (!scheduleKey.matches(Regex("^[A-Za-z_][A-Za-z0-9_]{0,127}$")) || scheduleKey == "__proto__") {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid occurrence identity")
        }
        val time = try { Instant.parse(scheduledAt) } catch (_: DateTimeParseException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid occurrence time")
        }
        return observationResponse(scheduleObservation.read(access.workspaceId, parseDashboardId(dashboardId), scheduleKey, time))
    }

    private fun observationResponse(value: JsonObject?): JsonNode =
        objectMapper.readTree((value ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)).toString())
}
