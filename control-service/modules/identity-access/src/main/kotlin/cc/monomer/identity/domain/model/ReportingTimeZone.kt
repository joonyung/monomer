package cc.monomer.identity.domain.model

import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@JvmInline
value class ReportingTimeZone private constructor(val zoneId: ZoneId) {
    val id: String get() = zoneId.id

    override fun toString(): String = id

    companion object {
        const val CONTRACT_RESOURCE = "contracts/time-zones.json"

        val supportedIds: List<String> = readSupportedIds()
        private val supportedIdSet: Set<String> = supportedIds.toSet()

        val UTC: ReportingTimeZone = checkNotNull(of("UTC")) { "$CONTRACT_RESOURCE must list UTC" }

        fun of(id: String): ReportingTimeZone? =
            if (id in supportedIdSet) ReportingTimeZone(ZoneId.of(id)) else null

        private fun readSupportedIds(): List<String> {
            val contract = checkNotNull(ReportingTimeZone::class.java.classLoader.getResource(CONTRACT_RESOURCE)) {
                "$CONTRACT_RESOURCE is missing from the classpath"
            }.readText()
            return Json.parseToJsonElement(contract).jsonObject.getValue("timeZones").jsonArray.map { it.jsonPrimitive.content }
        }
    }
}
