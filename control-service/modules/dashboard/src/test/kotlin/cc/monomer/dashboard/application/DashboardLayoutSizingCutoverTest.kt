package cc.monomer.dashboard.application

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DashboardLayoutSizingCutoverTest {
    @Test
    fun `exported normalized current and History definitions pass the restore owner decoder`() {
        val path = System.getenv("MONOMER_DASHBOARD_DEFINITIONS_FILE")
        assumeTrue(!path.isNullOrBlank(), "Set MONOMER_DASHBOARD_DEFINITIONS_FILE to validate exported NDJSON definitions")
        val definitions = Files.readAllLines(Path.of(path!!)).filter { it.isNotBlank() }
        assertTrue(definitions.isNotEmpty())
        definitions.forEachIndexed { index, definition ->
            val decoded = try {
                DashboardDefinitionDecoder.decode(definition)
            } catch (error: InvalidDashboardDefinitionException) {
                throw AssertionError("definition line ${index + 1}: ${error.message}", error)
            }
            assertEquals(Json.parseToJsonElement(definition), decoded.document, "definition line ${index + 1}")
        }
    }
}
