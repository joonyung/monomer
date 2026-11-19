package cc.monomer.control.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class PublicDocsExamplesTest {
    @Test
    fun `public guide examples match the advertised MCP inputs and exact decoder schemas`() {
        val root = sequenceOf(Path.of("../public-site/src/content/docs/en"), Path.of("../../public-site/src/content/docs/en"))
            .first { Files.isDirectory(it) }
        val mapper = ObjectMapper()
        val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        val catalog = McpToolCatalog(MonomerDocsReader("https://monomer.cc").agentApiReferenceUrl)
        val schemas = catalog.tools.associate { tool ->
            tool["name"] as String to factory.getSchema(mapper.valueToTree<JsonNode>(tool["inputSchema"]))
        }
        val codeBlock = Regex("```json(?:[ \t][^\\n]*)?\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
        val toolName = Regex("`(monomer_[a-z_]+)`")
        var examples = 0
        Files.walk(root).use { paths ->
            paths.filter { it.toString().endsWith(".md") }.sorted().forEach { path ->
                val markdown = Files.readString(path)
                for (block in codeBlock.findAll(markdown)) {
                    val example = mapper.readTree(block.groupValues[1])
                    if (block.value.lineSequence().first() == "```json mcp-config") {
                        assertTrue(example.isObject, "$path: MCP configuration must be an object")
                        continue
                    }
                    val tool = toolName.findAll(markdown.take(block.range.first)).lastOrNull()?.groupValues?.get(1)
                    val schema = schemas[tool] ?: error("$path: JSON example must name its MCP tool before the example")
                    val errors = schema.validate(example)
                    assertTrue(errors.isEmpty(), "$path ($tool): $errors")
                    if (tool !in setOf("monomer_describe", "monomer_docs", "monomer_show") && example.path("path").asText() !in setOf("organizations", "workspaces")) {
                        val body = example.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply { remove(listOf("workspaceId", "organizationId")) }
                        val operation = if (tool == "monomer_check") "invoke" else checkNotNull(tool).removePrefix("monomer_")
                        val exact = factory.getSchema(catalog.schemaForRequest(operation))
                        val exactErrors = exact.validate(body)
                        assertTrue(exactErrors.isEmpty(), "$path ($tool decoder): $exactErrors")
                        catalog.requireToolRoute(checkNotNull(tool), body)
                    } else if (tool == "monomer_describe") catalog.describe(example)
                    examples++
                }
            }
        }
        assertTrue(examples >= 8, "Expected task examples for model, metric, analysis, dashboard and agent guides")
    }
}
