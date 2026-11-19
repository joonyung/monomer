@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.semantic.inbound.rest

import cc.monomer.connector.api.ConnectorId
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticModelId
import cc.monomer.semantic.api.isCanonicalSemanticAddressPart
import kotlin.uuid.Uuid
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

internal fun String.requiredIdempotencyKey(): String =
    takeIf { matches(Regex("^[A-Za-z0-9._:-]{1,200}$")) }
        ?: throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Idempotency-Key must be 1..200 characters matching [A-Za-z0-9._:-]+",
        )

internal fun String.parseResourceId(): Uuid =
    runCatching { Uuid.parse(this) }
        .getOrElse {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "resource id must be a UUID")
        }

internal fun <T> String.parseResourceId(identifier: (Uuid) -> T): T {
    val uuid = parseResourceId()
    return try {
        identifier(uuid)
    } catch (_: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "resource id is invalid")
    }
}

internal fun parseDefinitionConnectorId(value: String): ConnectorId =
    decodeSemanticTransport { ConnectorId(value.parseResourceId()) }

internal fun parseSemanticModelId(value: String): SemanticModelId =
    decodeSemanticTransport { SemanticModelId(value.parseResourceId()) }

internal fun parseMetricId(value: String): MetricId =
    decodeSemanticTransport { MetricId(value.parseResourceId()) }

internal fun Int.toSemanticSchemaVersion(): UInt =
    takeIf { it == 3 }?.toUInt()
        ?: invalidSemanticTransport("semantic definition schemaVersion must be 3")

internal fun Int.toSemanticTimeSpineSchemaVersion(): UInt =
    takeIf { it == 1 }?.toUInt()
        ?: invalidSemanticTransport("semantic time spine schemaVersion must be 1")

internal inline fun <reified T : Enum<T>> String.toSemanticEnum(label: String): T =
    enumValues<T>().firstOrNull { it.name.equals(this, ignoreCase = true) }
        ?: invalidSemanticTransport("unsupported $label: $this")

internal fun String.toSemanticElementName(label: String): SemanticElementName =
    runCatching { SemanticElementName(this) }
        .getOrElse { invalidSemanticTransport("invalid $label") }

internal fun <T> requireField(value: T?, label: String): T =
    value ?: invalidSemanticTransport("$label is required for this metric type")

internal inline fun <T> decodeSemanticTransport(block: () -> T): T =
    try {
        block()
    } catch (exception: SemanticDefinitionProblem) {
        throw exception
    } catch (exception: ResponseStatusException) {
        throw exception
    } catch (exception: IllegalArgumentException) {
        invalidSemanticTransport(exception.message ?: "The definition is invalid.")
    }

internal fun String.requireCanonicalTimeSpineAddress(field: String, label: String): String =
    takeIf { it.isCanonicalSemanticAddressPart() }
        ?: invalidSemanticTransport(field, "semantic time spine $label must be a canonical identifier")

internal fun invalidSemanticTransport(message: String): Nothing = invalidSemanticTransport("definition", message)

internal fun invalidSemanticTransport(field: String, message: String): Nothing =
    throw SemanticDefinitionProblem(
        HttpStatus.BAD_REQUEST,
        "SEMANTIC_DEFINITION_INVALID",
        message.replaceFirstChar(Char::uppercaseChar),
        listOf(SemanticProblemViolation(field, "SEMANTIC_DEFINITION_INVALID", message.replaceFirstChar(Char::uppercaseChar))),
    )
