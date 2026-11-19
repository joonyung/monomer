package cc.monomer.control.publicapi

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class AgentResponseDocumentationTest {
    @Test
    fun `implemented edit request examples satisfy the public decoder`() {
        val repository = Path.of(System.getProperty("control.service.root")).parent
        val decoder = cc.monomer.agent.application.AgentWorkspaceContractDecoder()
        val mapper = ObjectMapper()
        var checked = 0
        listOf(
            "2026-08-27-agent-public-rest-api-design.html",
            "2026-08-27-agent-public-rest-api-request-response-catalog.html",
        ).forEach { name ->
            val html = Files.readString(repository.resolve("docs/research/$name"))
            Regex("<pre><code class=\"language-json\">([\\s\\S]*?)</code></pre>").findAll(html).forEach { example ->
                val json = example.groupValues[1]
                val body = mapper.readTree(json)
                if (body.has("patch") && body.has("path")) {
                    val path = body.path("path").asText()
                    if (!Regex("^workspace/connectors/[^/]+$").matches(path)) {
                        decoder.edit(json)
                        checked += 1
                    }
                }
            }
        }
        assertTrue(checked >= 8, "Expected current edit examples, checked $checked")
    }

    @Test
    fun `implemented response examples satisfy the packaged public contract`() {
        val repository = Path.of(System.getProperty("control.service.root")).parent
        val validator = PublicControlContractValidator()
        val mapper = ObjectMapper()
        var checked = 0
        val failures = mutableListOf<String>()
        listOf(
            "2026-08-27-agent-public-rest-api-design.html",
            "2026-08-27-agent-public-rest-api-request-response-catalog.html",
        ).forEach { name ->
            val html = Files.readString(repository.resolve("docs/research/$name"))
            Regex("<pre><code class=\"language-json\">([\\s\\S]*?)</code></pre>").findAll(html).forEach exampleLoop@ { example ->
                val body = mapper.readValue(example.groupValues[1], object : TypeReference<Map<String, Any?>>() {})
                if (body["requestId"] == null) return@exampleLoop
                val path = body["path"] as? String
                val items = body["items"] as? List<*>
                val pendingConnector = body["kind"] == "CONNECTOR" || (body["result"] as? Map<*, *>)?.get("kind") == "CONNECTOR" ||
                    path == "workspace/connectors" ||
                    items.orEmpty().any { (it as? Map<*, *>)?.get("kind") == "CONNECTOR" }
                if (pendingConnector) return@exampleLoop
                try {
                    when {
                        body["kind"] != null -> validator.agentResource(body)
                        items != null -> validator.agentListing(body)
                        body["result"] != null -> validator.agentAction(body)
                        else -> return@exampleLoop
                    }
                    checked += 1
                } catch (_: IllegalStateException) {
                    val line = html.take(example.range.first).count { it == '\n' } + 1
                    failures += "$name:$line ${mapper.writeValueAsString(body)}"
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        assertTrue(checked >= 20, "Expected the implemented response examples to be exercised; checked $checked")
    }
}
