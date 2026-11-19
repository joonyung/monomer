@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.dashboard

import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardSessionId
import cc.monomer.dashboard.api.QueryRunId
import cc.monomer.dashboard.application.DashboardQueryRunPageCursor
import cc.monomer.dashboard.application.DashboardSessionPageCursor
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode

internal fun parseDashboardId(raw: String): DashboardId = try {
    DashboardId(parseUuid(raw, "dashboardId"))
} catch (error: IllegalArgumentException) {
    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "dashboardId must be a UUIDv7", error)
}

internal fun parseDashboardSessionId(raw: String): DashboardSessionId = try {
    DashboardSessionId(parseUuid(raw, "sessionId"))
} catch (error: IllegalArgumentException) {
    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId must be a UUIDv7", error)
}

internal fun parseQueryRunId(raw: String): QueryRunId = try {
    QueryRunId(parseUuid(raw, "runId"))
} catch (error: IllegalArgumentException) {
    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "runId must be a UUIDv7", error)
}

private fun parseUuid(raw: String, field: String): Uuid = runCatching { Uuid.parse(raw) }
    .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$field must be a UUID") }

private val STRICT_BODY_MAPPER = tools.jackson.databind.json.JsonMapper.builder()
    .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
    .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()

internal fun strictBody(value: String): JsonNode = try {
    STRICT_BODY_MAPPER.readTree(value)
} catch (_: RuntimeException) {
    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON request body")
}

internal fun parseObject(node: JsonNode): JsonObject = runCatching { parseJsonElement(node).jsonObject }
    .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "request body must be an object", it) }

internal fun requireKey(value: String): String = value.trim().takeIf(String::isNotEmpty)
    ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is required")

internal fun requiredObject(node: JsonNode, field: String): JsonObject {
    if (!node.isObject) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$field must be an object")
    val value = node.get(field) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$field is required")
    if (!value.isObject) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$field must be an object")
    return parseJsonElement(value).jsonObject
}

internal fun requiredBodyObject(node: JsonNode): JsonNode {
    if (!node.isObject) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "request body must be an object")
    return node
}

internal fun requiredStringArray(node: JsonNode, field: String): List<String> {
    if (!node.isObject) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "request body must be an object")
    val value = node.get(field) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$field is required")
    if (!value.isArray || value.isEmpty) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$field must be a non-empty array")
    return value.toList().map { item ->
        if (!item.isString) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$field must contain non-blank strings")
        }
        item.stringValue().takeIf(String::isNotBlank)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$field must contain non-blank strings")
    }
}

internal fun JsonNode.requiredNonNegativeLong(field: String): Long {
    val value = get(field)
    if (value == null || !value.isIntegralNumber || !value.canConvertToLong()) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$field must be a non-negative integer")
    }
    return value.longValue().takeIf { it >= 0 }
        ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$field must be a non-negative integer")
}

internal fun resolvePageSize(pageSize: Int?): Int = (pageSize ?: DEFAULT_PAGE_SIZE).takeIf { it in 1..MAX_PAGE_SIZE }
    ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "pageSize must be between 1 and $MAX_PAGE_SIZE")

private data class DecodedCursor(val createdAt: Instant, val id: Uuid)

internal fun decodeSessionCursor(raw: String, dashboardId: DashboardId): DashboardSessionPageCursor {
    return try {
        require(raw.isNotBlank() && raw.length <= MAX_CURSOR_LENGTH)
        val parts = String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8).split('|')
        require(parts.size == 3)
        val cursor = DashboardSessionPageCursor(DashboardId(Uuid.parse(parts[0])), Instant.parse(parts[1]), DashboardSessionId(Uuid.parse(parts[2])))
        require(cursor.dashboardId == dashboardId && encodeSessionCursor(cursor) == raw)
        cursor
    } catch (error: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "cursor is invalid for this Dashboard", error)
    } catch (error: DateTimeParseException) {
        throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "cursor is invalid for this Dashboard", error)
    }
}

internal fun encodeSessionCursor(cursor: DashboardSessionPageCursor): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString("${cursor.dashboardId}|${cursor.createdAt}|${cursor.sessionId}".toByteArray(StandardCharsets.UTF_8))

internal fun decodeQueryRunCursor(raw: String): DashboardQueryRunPageCursor {
    val decoded = decodeCursor(raw)
    return try {
        DashboardQueryRunPageCursor(decoded.createdAt, QueryRunId(decoded.id))
    } catch (error: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "cursor is invalid", error)
    }
}

private fun decodeCursor(raw: String): DecodedCursor {
    if (raw.isBlank() || raw.length > MAX_CURSOR_LENGTH) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "cursor is invalid")
    }
    val value = try {
        String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8)
    } catch (error: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "cursor is invalid", error)
    }
    val separator = value.indexOf('|')
    if (separator <= 0 || separator == value.lastIndex) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "cursor is invalid")
    }
    val createdAt = try {
        Instant.parse(value.substring(0, separator))
    } catch (error: DateTimeParseException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "cursor is invalid", error)
    }
    val id = try {
        Uuid.parse(value.substring(separator + 1))
    } catch (error: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "cursor is invalid", error)
    }
    return DecodedCursor(createdAt, id)
}

internal fun encodeCursor(createdAt: Instant, id: Uuid): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString("$createdAt|$id".toByteArray(StandardCharsets.UTF_8))

private const val DEFAULT_PAGE_SIZE = 20
private const val MAX_PAGE_SIZE = 100
private const val MAX_CURSOR_LENGTH = 512

internal fun parseJsonElement(node: JsonNode): JsonElement = Json.parseToJsonElement(node.toString())
