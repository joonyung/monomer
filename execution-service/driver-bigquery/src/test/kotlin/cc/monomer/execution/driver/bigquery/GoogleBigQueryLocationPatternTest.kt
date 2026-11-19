package cc.monomer.execution.driver.bigquery

import cc.monomer.execution.driver.spi.ConnectorId
import cc.monomer.execution.driver.spi.ConnectorPropertyValue
import cc.monomer.execution.driver.spi.ConnectorRuntimeBinding
import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.MaterializedCredential
import cc.monomer.execution.driver.spi.SecretCharacters
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

class GoogleBigQueryLocationPatternTest {
    private val factory = GoogleBigQueryGatewayFactory(1_000_000, Duration.ofSeconds(30))

    @Test
    fun locationsKeepThePreviousHyphenatedLabelLanguageWithoutRepeatedGroups() {
        val previous = Regex("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*")
        words(listOf('a', 'Z', '0', '-', '_', ' ', '\n', '\u0085'), 6).forEach { location ->
            assertEquals(previous.matches(location), accepted(location), location)
        }
        val megabyteLocation = "a" + "-a".repeat(500_000)
        assertEquals(true, accepted(megabyteLocation))
        assertEquals(false, accepted("$megabyteLocation-"))
    }

    private fun accepted(location: String): Boolean {
        val connector = ConnectorRuntimeBinding(
            1u, ConnectorId.parse("019f0000-0000-7000-8000-000000000003"), ConnectorType.BIGQUERY,
            mapOf("project_id" to ConnectorPropertyValue.Text("fixture-project"), "location" to ConnectorPropertyValue.Text(location)),
        )
        val credential = MaterializedCredential(mapOf("service_account_json" to SecretCharacters.copyOf("{}".toCharArray())))
        return try {
            factory.create(connector, credential, Dispatchers.Unconfined, Clock.systemUTC(), BigQueryCallTimeoutPolicy(Duration.ofSeconds(5)), testTiming())
            true
        } catch (_: IllegalArgumentException) {
            false
        } finally {
            credential.close()
        }
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
