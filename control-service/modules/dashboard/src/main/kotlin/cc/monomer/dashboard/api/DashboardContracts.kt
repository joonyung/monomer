@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.api

import kotlin.uuid.Uuid

@JvmInline
value class DashboardId(val value: Uuid) {
    init { value.requireUuidV7("dashboard id") }

    companion object {
        fun generate(): DashboardId = DashboardId(Uuid.generateV7())
    }

    override fun toString(): String = value.toString()
}

@JvmInline
value class DashboardSessionId(val value: Uuid) {
    init { value.requireUuidV7("dashboard session id") }

    companion object {
        fun generate(): DashboardSessionId = DashboardSessionId(Uuid.generateV7())
    }

    override fun toString(): String = value.toString()
}

/** The dashboard-owned identity of one admitted top-level query execution. */
@JvmInline
value class QueryRunId(val value: Uuid) {
    init { value.requireUuidV7("query run id") }

    companion object {
        fun generate(): QueryRunId = QueryRunId(Uuid.generateV7())
    }

    override fun toString(): String = value.toString()
}

private fun Uuid.requireUuidV7(label: String) {
    require(this != Uuid.NIL) { "$label must not be nil" }
    val bytes = toByteArray()
    require(bytes[8].toInt().and(0xc0) == 0x80) {
        "$label must use the IETF UUID variant"
    }
    require(bytes[6].toInt().and(0xf0) == 0x70) {
        "$label must be UUIDv7"
    }
}
