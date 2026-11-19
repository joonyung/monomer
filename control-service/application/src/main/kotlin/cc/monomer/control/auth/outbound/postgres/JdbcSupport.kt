package cc.monomer.control.auth.outbound.postgres

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import kotlin.uuid.Uuid
import kotlin.uuid.toKotlinUuid

fun ResultSet.getUuid(columnLabel: String): Uuid =
    getObject(columnLabel, java.util.UUID::class.java).toKotlinUuid()

fun ResultSet.getInstant(columnLabel: String): Instant =
    (getObject(columnLabel) as Timestamp).toInstant()

fun ResultSet.getInstantNullable(columnLabel: String): Instant? =
    (getObject(columnLabel) as Timestamp?)?.toInstant()
