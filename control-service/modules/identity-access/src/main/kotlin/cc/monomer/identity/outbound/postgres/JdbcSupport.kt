package cc.monomer.identity.outbound.postgres

import cc.monomer.identity.domain.model.ReportingTimeZone
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

internal fun storedReportingTimeZone(id: String): ReportingTimeZone =
    checkNotNull(ReportingTimeZone.of(id)) { "stored reporting time zone $id is not in ${ReportingTimeZone.CONTRACT_RESOURCE}" }
