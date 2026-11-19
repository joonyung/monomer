package cc.monomer.semantic

import cc.monomer.semantic.adapter.outbound.postgres.SemanticPostgresJsonCodec
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticPartitionCutoverTest {
    @Test
    fun `exported current and History models pass the strict owner decoder`() {
        val path = System.getenv("MONOMER_SEMANTIC_PARTITION_DEFINITIONS_FILE")
        assumeTrue(!path.isNullOrBlank(), "Set MONOMER_SEMANTIC_PARTITION_DEFINITIONS_FILE to validate cutover NDJSON")
        val mapper = ObjectMapper()
        val codec = SemanticPostgresJsonCodec(mapper)
        val definitions = Files.readAllLines(Path.of(path!!)).filter { it.isNotBlank() }
        assertTrue(definitions.isNotEmpty())
        definitions.forEachIndexed { index, definition ->
            val decoded = codec.parseModelDefinition(definition)
            assertEquals(mapper.readTree(definition), mapper.readTree(codec.modelDefinitionJson(decoded)), "model line ${index + 1}")
        }
    }
}
