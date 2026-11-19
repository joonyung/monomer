@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.execution.api

import kotlin.uuid.Uuid

enum class ExecutionOwnerType {
    EXPLORE,
    CATALOG,
    DASHBOARD,
}

@JvmInline
value class ExecutionOwnerId(val value: Uuid) {
    init {
        value.requireExecutionOwnerUuidV7()
    }

    override fun toString(): String = value.toString()
}

data class ExecutionOwnerReference(
    val type: ExecutionOwnerType,
    val id: ExecutionOwnerId,
)

private fun Uuid.requireExecutionOwnerUuidV7() {
    require(this != Uuid.NIL) { "execution owner id must not be nil" }
    val bytes = toByteArray()
    require(bytes[8].toInt().and(0xc0) == 0x80) {
        "execution owner id must use the IETF UUID variant"
    }
    require(bytes[6].toInt().and(0xf0) == 0x70) {
        "execution owner id must be UUIDv7"
    }
}
