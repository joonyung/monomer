package cc.monomer.connector.api

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectorBigQueryLocationPatternTest {
    @Test
    fun `BigQuery locations keep the previous hyphenated label language without repeated groups`() {
        val previous = Regex("^[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*$")
        words(listOf('a', 'Z', '0', '-', '_', ' ', '\n', '\u0085'), 6).forEach { location ->
            assertEquals(previous.matches(location), accepted(location), location)
        }
        val megabyteLocation = "a" + "-a".repeat(500_000)
        assertEquals(true, accepted(megabyteLocation))
        assertEquals(false, accepted("$megabyteLocation-"))
    }

    private fun accepted(location: String): Boolean =
        try {
            ConnectorSettingsCodec.decode(ConnectorProductType.BIGQUERY, buildJsonObject {
                put("projectId", "fixture-project")
                put("location", location)
            })
            true
        } catch (_: IllegalArgumentException) {
            false
        }

    private fun words(alphabet: List<Char>, maximumLength: Int): List<String> {
        val values = mutableListOf<String>()
        var layer = listOf("")
        repeat(maximumLength + 1) { length ->
            values += layer
            if (length < maximumLength) layer = layer.flatMap { word -> alphabet.map { word + it } }
        }
        return values
    }
}
