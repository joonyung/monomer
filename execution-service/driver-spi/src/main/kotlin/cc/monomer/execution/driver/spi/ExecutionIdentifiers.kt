@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.execution.driver.spi

import kotlin.uuid.Uuid

@JvmInline
value class RunId(val value: Uuid) {
    init {
        requireUuidV7(value, "runId")
    }

    override fun toString(): String = value.toString()

    companion object {
        fun parse(wireValue: String): RunId =
            RunId(parseUuid(wireValue, "runId"))
    }
}

@JvmInline
value class WorkspaceId(val value: Uuid) {
    init {
        requireUuid(value, "workspaceId")
    }

    override fun toString(): String = value.toString()

    companion object {
        fun parse(wireValue: String): WorkspaceId =
            WorkspaceId(parseUuid(wireValue, "workspaceId"))
    }
}

@JvmInline
value class ConnectorId(val value: Uuid) {
    init {
        requireUuidV7(value, "connectorId")
    }

    override fun toString(): String = value.toString()

    companion object {
        fun parse(wireValue: String): ConnectorId =
            ConnectorId(parseUuid(wireValue, "connectorId"))
    }
}

private fun parseUuid(
    wireValue: String,
    fieldName: String,
): Uuid {
    require(wireValue.length in 32..36) { "$fieldName must be a UUID" }
    return runCatching { Uuid.parse(wireValue) }
        .getOrElse { throw IllegalArgumentException("$fieldName must be a UUID", it) }
}

private fun requireUuidV7(
    value: Uuid,
    fieldName: String,
) {
    requireUuid(value, fieldName)
    val version =
        value.toLongs { mostSignificantBits, _ ->
            ((mostSignificantBits ushr 12) and 0x0f).toInt()
        }
    require(version == 7) { "$fieldName must be UUID v7" }
}

private fun requireUuid(
    value: Uuid,
    fieldName: String,
) {
    require(value != Uuid.NIL) { "$fieldName must not be the nil UUID" }
    val ietfVariant =
        value.toULongs { _, leastSignificantBits ->
            leastSignificantBits shr 62 == 2uL
        }
    require(ietfVariant) { "$fieldName must use the IETF UUID variant" }
}
