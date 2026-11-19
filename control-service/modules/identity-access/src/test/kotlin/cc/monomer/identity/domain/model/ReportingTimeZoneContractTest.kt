package cc.monomer.identity.domain.model

import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReportingTimeZoneContractTest {
    private val ids = ReportingTimeZone.supportedIds

    @Test
    fun `the classpath list is the repository contract`() {
        val contract = Path.of(System.getProperty("control.service.root"), "../contracts/time-zones.json")
        val repositoryIds = Json.parseToJsonElement(Files.readString(contract)).jsonObject.getValue("timeZones").jsonArray
            .map { it.jsonPrimitive.content }

        assertThat(ids).isEqualTo(repositoryIds)
    }

    @Test
    fun `the list is unique, sorted by code point, and keeps UTC as its only non-region entry`() {
        assertThat(ids).hasSizeGreaterThan(400)
        assertThat(ids.zipWithNext()).allSatisfy { (previous, next) -> assertThat(previous < next).isTrue() }
        assertThat(ids).contains("UTC", "Asia/Seoul", "America/New_York", "Europe/London", "Asia/Kolkata", "Europe/Kyiv")
        assertThat(ids.filter { "/" !in it }).containsExactly("UTC")
        assertThat(ids).noneMatch { it.startsWith("Etc/") || it.startsWith("SystemV/") }
    }

    @Test
    fun `every entry is a JDK zone region with the same identifier`() {
        assertThat(ids).allSatisfy { id ->
            val zone = ZoneId.of(id)
            assertThat(zone).isNotInstanceOf(ZoneOffset::class.java)
            assertThat(zone.id).isEqualTo(id)
        }
    }

    @Test
    fun `current per-country city names are listed and backward-compatibility names are not`() {
        val links = jdkTzdbLinks()

        assertThat(ids).contains("Europe/Amsterdam", "Asia/Kuala_Lumpur", "Europe/Stockholm", "Asia/Kuwait", "Atlantic/Reykjavik")
        assertThat(links).containsKeys("Europe/Amsterdam", "Asia/Kuala_Lumpur")
        assertThat(ids).doesNotContain("US/Eastern", "Asia/Calcutta", "Europe/Kiev", "Asia/Saigon", "America/Buenos_Aires", "Etc/UTC")
        assertThat(ids).doesNotContain("America/Coyhaique")
        assertThat(links).containsKeys("US/Eastern", "Asia/Calcutta", "Europe/Kiev")
    }

    @Test
    fun `only listed identifiers become reporting time zones`() {
        assertThat(ReportingTimeZone.UTC.id).isEqualTo("UTC")
        assertThat(ReportingTimeZone.of("Asia/Seoul")?.zoneId).isEqualTo(ZoneId.of("Asia/Seoul"))
        assertThat(ReportingTimeZone.of("Europe/Amsterdam")?.id).isEqualTo("Europe/Amsterdam")
        for (unsupported in listOf("US/Eastern", "Asia/Calcutta", "Etc/UTC", "Etc/GMT+9", "GMT", "Z", "+09:00", "asia/seoul", " Asia/Seoul", "")) {
            assertThat(ReportingTimeZone.of(unsupported)).describedAs(unsupported).isNull()
        }
    }

    // The JDK's tzdb.dat stores every tzdb Link as an alias entry after the region rules.
    private fun jdkTzdbLinks(): Map<String, String> {
        val tzdb = Path.of(System.getProperty("java.home"), "lib", "tzdb.dat")
        return DataInputStream(Files.newInputStream(tzdb).buffered()).use { input ->
            check(input.readByte().toInt() == 1)
            check(input.readUTF() == "TZDB")
            val versionCount = input.readShort().toInt()
            repeat(versionCount) { input.readUTF() }
            val regions = List(input.readShort().toInt()) { input.readUTF() }
            repeat(input.readShort().toInt()) { input.skipNBytes(input.readShort().toLong()) }
            repeat(versionCount) {
                repeat(input.readShort().toInt()) {
                    input.readShort()
                    input.readShort()
                }
            }
            val links = List(input.readShort().toInt()) { regions[input.readShort().toInt()] to regions[input.readShort().toInt()] }.toMap()
            check(input.read() == -1)
            links
        }
    }
}
