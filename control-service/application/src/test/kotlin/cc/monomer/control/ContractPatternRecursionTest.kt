package cc.monomer.control

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** java.util.regex recurses once per repeated group iteration, and schema validators evaluate pattern even after maxLength fails. */
class ContractPatternRecursionTest {
    private val mapper = jacksonObjectMapper()
    private val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("contracts/agent-workspace.schema.json")) }
    private val documents: Map<String, JsonNode> = (
        Files.walk(repository.resolve("contracts")).use { paths -> paths.filter { it.extension == "json" }.toList() } +
            Files.walk(repository.resolve("control-service")).use { paths ->
                paths.filter { it.extension == "json" && "/src/main/resources/" in it.invariantSeparatorsPathString }.toList()
            }
        ).associate { repository.relativize(it).invariantSeparatorsPathString to mapper.readTree(it.toFile()) }

    @Test
    fun `contract and owner schema patterns never repeat a group`() {
        val repeated = documents.flatMap { (file, document) ->
            patterns(document).filter { pattern ->
                pattern.replace(Regex("""\\."""), "e").replace(Regex("""\[[^\]]*]"""), "c").contains(Regex("""\)[*+{]"""))
            }.map { "$file: $it" }
        }
        assertEquals(emptyList(), repeated)
    }

    @Test
    fun `owner name patterns keep their previous languages`() {
        val rewritten = mapOf(
            SEMANTIC_NAME to ("^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$" to listOf('a', '0', '_', 'A', '-', '/', '\n', '\u0085')),
            BIGQUERY_LOCATION to ("^[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*$" to listOf('a', 'Z', '0', '-', '_', '/', '\n', '\u0085')),
        )
        val sites = documents.flatMap { (file, document) -> stringSchemas(document).filter { it.path("pattern").asText() in rewritten }.map { file to it } }
        assertEquals(mapOf(SEMANTIC_NAME to 13, BIGQUERY_LOCATION to 1), sites.groupingBy { it.second.path("pattern").asText() }.eachCount())
        for ((pattern, expectation) in rewritten) {
            val (previousPattern, alphabet) = expectation
            sites.filter { it.second.path("pattern").asText() == pattern }.forEach { (file, schema) -> assertTrue(schema.path("not").isMissingNode, file) }
            val current = Regex(pattern)
            val previous = Regex(previousPattern)
            words(alphabet, 7).forEach { value ->
                assertEquals(previous.containsMatchIn(value), current.containsMatchIn(value), "$pattern: $value")
            }
        }
    }

    private fun patterns(node: JsonNode): List<String> = when {
        node.isObject -> node.properties().flatMap { (key, value) ->
            when {
                key == "pattern" && value.isTextual -> listOf(value.asText())
                key == "patternProperties" && value.isObject -> value.fieldNames().asSequence().toList() + patterns(value)
                else -> patterns(value)
            }
        }
        node.isArray -> node.flatMap(::patterns)
        else -> emptyList()
    }

    private fun stringSchemas(node: JsonNode): List<JsonNode> = when {
        node.isObject -> (if (node.path("pattern").isTextual) listOf(node) else emptyList()) + node.properties().flatMap { stringSchemas(it.value) }
        node.isArray -> node.flatMap(::stringSchemas)
        else -> emptyList()
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

    private companion object {
        const val SEMANTIC_NAME = "^(?![a-z0-9_]*__)[a-z](?:[a-z0-9_]*[a-z0-9])?$"
        const val BIGQUERY_LOCATION = "^(?![A-Za-z0-9-]*--)[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?$"
    }
}
