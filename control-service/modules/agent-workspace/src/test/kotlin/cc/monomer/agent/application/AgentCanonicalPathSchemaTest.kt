package cc.monomer.agent.application

import cc.monomer.agent.api.AgentPathPatterns
import cc.monomer.agent.api.AgentWorkspaceAdmissionIntent
import cc.monomer.agent.api.AgentResourcePath
import cc.monomer.agent.api.AgentWorkspaceRequestDecodingException
import cc.monomer.agent.api.isSupported
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentCanonicalPathSchemaTest {
    private val mapper = ObjectMapper()
    private val document = javaClass.classLoader.getResourceAsStream("contracts/agent-workspace.schema.json")!!.use(mapper::readTree)
    private val definitions = document.path("\$defs")
    private val decoder = AgentWorkspaceContractDecoder()
    private val compiled = mutableMapOf<String, Regex>()

    @Test
    fun `contract patterns never repeat a group`() {
        val repeatedGroups = patternNodes(document).map { it.second.path("pattern").asText() }
            .plus(patternNodes(document).mapNotNull { it.second.path("not").get("pattern")?.asText() })
            .filter { pattern ->
                pattern.replace(Regex("""\\."""), "e").replace(Regex("""\[[^\]]*]"""), "c").contains(Regex("""\)[*+{]"""))
            }
        assertEquals(emptyList(), repeatedGroups, "java.util.regex recurses once per repeated group iteration")
    }

    @Test
    fun `factored canonical path and JSON Pointer keep the previous languages`() {
        val canonical = previous("^(?:workspace|organization)(?:/[^/$CONTROL]+)*(?![\\s\\S])$", "~(?![01])")
        for (prefix in listOf("workspace", "organization", "")) {
            assertSameLanguage("CanonicalResourcePath", canonical, { accepts(definitions.path("CanonicalResourcePath"), it) }, words(prefix, ESCAPE_ALPHABET, 7))
        }
        val pointer = previous(PREVIOUS_POINTER)
        assertSameLanguage("JsonPointer", pointer, { accepts(definitions.path("JsonPointer"), it) }, words("", ESCAPE_ALPHABET, 7))
        assertSameLanguage("JsonPointer", pointer, { accepts(definitions.path("JsonPointer"), it) },
            words("", listOf('/', '~', '0', '1', '2', '\r', '\n', '\u2028'), 6))
    }

    @Test
    fun `edit path categories keep the previous pointer language with JsonPointer`() {
        val sites = patternNodes(document).filter { (_, node) ->
            node.path("pattern").asText().let { it.startsWith("^/(?:") && it.endsWith(EDIT_SUFFIX) }
        }
        assertEquals(4, sites.size, "Semantic Model, Metric, Dashboard Draft and Connector settings edit paths")
        for ((location, node) in sites) {
            val operation = document.at(location.substringBefore("/allOf/1/properties/path"))
            assertEquals("#/\$defs/AgentJsonPatchOperation", operation.path("allOf").path(0).path("\$ref").asText(), location)
            val pattern = node.path("pattern").asText()
            val previousCategory = previous(pattern.removeSuffix(EDIT_SUFFIX) + PREVIOUS_EDIT_SUFFIX)
            val previousPointer = previous(PREVIOUS_POINTER)
            val roots = pattern.removePrefix("^/(?:").removeSuffix(")$EDIT_SUFFIX").split('|')
            for (prefix in roots.map { "/$it" } + listOf("", "/", "/${roots.first()}x")) {
                assertSameLanguage(location, { previousPointer(it) && previousCategory(it) },
                    { accepts(definitions.path("JsonPointer"), it) && accepts(node, it) }, words(prefix, ESCAPE_ALPHABET, 5))
            }
        }
    }

    @Test
    fun `Catalog name tokens keep the previous escape language inside canonical paths`() {
        val sites = patternNodes(document).filter { (_, node) -> NAME_TOKEN in node.path("pattern").asText() }
        assertEquals(14, sites.size, "Catalog request paths and option listing response paths")
        val canonical = previous("^(?:workspace|organization)(?:/[^/$CONTROL]+)*(?![\\s\\S])$", "~(?![01])")
        val candidates = catalogCandidates()
        for ((location, node) in sites) {
            val enclosing = document.at(location.substringBeforeLast("/allOf/"))
            assertEquals("#/\$defs/CanonicalResourcePath", enclosing.path("allOf").path(0).path("\$ref").asText(), location)
            val previousPattern = previous(node.path("pattern").asText().replace(NAME_TOKEN, PREVIOUS_NAME_TOKEN))
            assertSameLanguage(location, { canonical(it) && previousPattern(it) }, { accepts(enclosing, it) }, candidates)
        }
    }

    @Test
    fun `semantic names keep the previous lower snake case language`() {
        val sites = patternNodes(document).filter { (_, node) -> SEMANTIC_TOKEN in node.path("pattern").asText() }
        assertEquals(11, sites.size, "Semantic definition paths, history paths and names")
        val candidates = listOf("", "workspace/semantic-models/", "workspace/metrics/").flatMap { prefix ->
            listOf("", "/history/$RUN_ID", "/history/$RUN_ID/$RUN_ID").flatMap { suffix ->
                words(prefix, SEMANTIC_ALPHABET, 5).map { it + suffix }
            }
        }
        for ((location, node) in sites) {
            assertTrue(node.path("not").isMissingNode, location)
            val previousPattern = previous(node.path("pattern").asText().replace(SEMANTIC_TOKEN, PREVIOUS_SEMANTIC_TOKEN))
            assertSameLanguage(location, previousPattern, { accepts(node, it) }, candidates)
        }
        val kotlinName = Regex("^${AgentPathPatterns.SEMANTIC_NAME}$")
        val previousName = Regex("^$PREVIOUS_SEMANTIC_TOKEN$")
        words("", SEMANTIC_ALPHABET, 7).forEach { name ->
            assertEquals(previousName.matches(name), kotlinName.matches(name), escaped(name))
        }
    }

    @Test
    fun `maximum escaped names decode without recursive regex stack growth`() {
        for (name in listOf("~1".repeat(1024), "~0".repeat(1024), "a".repeat(1024))) {
            val path = "workspace/connectors/$CONNECTOR_ID/catalog/catalogs/$name/selection/options/schemas/$name/relations"
            assertEquals(path, decoder.list("""{"path":"$path","limit":20}""").path.value)
            assertEquals("$path/$name", decoder.read("""{"path":"$path/$name"}""").path.value)
            assertEquals(path, decoder.invoke("""{"path":"$path","action":"refresh"}""").value.path.value)
        }
        for (name in listOf("~", "~2", "~1~", "~0~x")) {
            assertFailsWith<AgentWorkspaceRequestDecodingException> {
                decoder.list("""{"path":"workspace/connectors/$name","limit":20}""")
            }
        }
    }

    @Test
    fun `maximum length paths and pointers with the most segments stay schema decisions`() {
        val segmented = "workspace" + "/a".repeat((MAXIMUM_PATH_LENGTH - "workspace".length) / 2)
        assertEquals(MAXIMUM_PATH_LENGTH, segmented.length)
        assertEquals(segmented, decoder.list("""{"path":"$segmented","limit":20}""").path.value)
        val semanticPath = "workspace/semantic-models/$MAXIMUM_SEMANTIC_NAME"
        assertEquals(semanticPath, decoder.read("""{"path":"$semanticPath"}""").path.value)
        val pointer = "/entities" + "/a".repeat((MAXIMUM_POINTER_LENGTH - "/entities".length) / 2)
        val edit = decoder.edit("""{"path":"workspace/semantic-models/orders","aggregateMetrics":[],"patch":[{"op":"remove","path":"$pointer"}]}""")
        assertEquals(pointer, edit.value.patch.single().path)
    }

    @Test
    fun `megabyte paths pointers and names become contract violations instead of stack overflow`() {
        val filler = MAXIMUM_REQUEST_BYTES - 200
        val requests = listOf<Pair<String, (String) -> Unit>>(
            """{"path":"workspace${"/a".repeat(filler / 2)}","limit":20}""" to { body -> decoder.list(body) },
            """{"path":"workspace/connectors/$CONNECTOR_ID/catalog/catalogs/${"a".repeat(filler)}/selection","patch":[{"op":"replace","path":"","value":{"selectedSchemas":[]}}]}""" to { body -> decoder.edit(body) },
            """{"path":"workspace/connectors/$CONNECTOR_ID/catalog/catalogs/${"~0".repeat(filler / 2)}","action":"ingest"}""" to { body -> decoder.invoke(body) },
            """{"path":"workspace/semantic-models/a${"_a".repeat(filler / 2)}"}""" to { body -> decoder.read(body) },
            """{"path":"workspace/semantic-models/orders","aggregateMetrics":[],"patch":[{"op":"remove","path":"/entities${"/a".repeat(filler / 2)}"}]}""" to { body -> decoder.edit(body) },
            """{"path":"workspace/connectors/$CONNECTOR_ID","patch":[{"op":"replace","path":"/properties/${"a".repeat(filler)}","value":1}]}""" to { body -> decoder.edit(body) },
        )
        val invalidName = "a" + "_a".repeat(filler / 2 - 100) + "_"
        val ownerSchemaRequests = listOf<Pair<String, (String) -> Unit>>(
            """{"path":"workspace/semantic-models","input":{"kind":"SEMANTIC_MODEL_DEFINITION","aggregateMetrics":[],"definition":{"schemaVersion":3,"name":"$invalidName","sourceRelations":[],"entities":[],"dimensions":[],"measures":[]}}}""" to { body -> decoder.create(body) },
            """{"path":"workspace/metrics","input":{"kind":"METRIC_DEFINITION","definition":{"schemaVersion":3,"name":"$invalidName","type":"simple","semanticModelName":"orders","measure":"amount","timeGranularity":"day","filters":[]}}}""" to { body -> decoder.create(body) },
            """{"path":"workspace/explore/runs","action":"run","input":{"kind":"SEMANTIC_EXPLORE_RUN","connectorId":"$CONNECTOR_ID","query":{"schemaVersion":4,"metrics":["$invalidName"],"groupBys":[],"metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":10}}}""" to { body -> decoder.invoke(body) },
            """{"path":"workspace/connectors","input":{"kind":"CONNECTOR_CREATE","connectorType":"BIGQUERY","displayName":"Fixture","properties":{"projectId":"fixture-project","location":"${"a-".repeat(filler / 2 - 100)}"},"credential":{"kind":"SERVICE_ACCOUNT_JSON","serviceAccountJson":"{}"}}}""" to { body -> decoder.create(body) },
        )
        for ((body, decode) in requests + ownerSchemaRequests) {
            assertTrue(body.length <= MAXIMUM_REQUEST_BYTES)
            assertFailsWith<AgentWorkspaceRequestDecodingException>(body.take(80)) { decode(body) }
        }
    }

    @Test
    fun `Kotlin path regexes match megabyte inputs without recursion`() {
        val name = "a" + "_a".repeat(500_000)
        assertTrue(Regex("^${AgentPathPatterns.SEMANTIC_NAME}$").matches(name))
        for (path in listOf("workspace/semantic-models/$MAXIMUM_SEMANTIC_NAME", "workspace/metrics/$MAXIMUM_SEMANTIC_NAME")) {
            assertTrue(AgentWorkspaceAdmissionIntent.Read(AgentResourcePath.parse(path)).isSupported(), path)
        }
        val catalog = "workspace/connectors/$CONNECTOR_ID/catalog/catalogs/${"a".repeat(1_000_000)}"
        assertTrue(AgentPathPatterns.catalogInvokeDeclared(catalog, "ingest"))
        assertTrue(AgentPathPatterns.catalogInvokeDeclared("$catalog/selection/options/schemas/${"b".repeat(1_000_000)}/relations", "refresh"))
    }

    private fun accepts(schema: JsonNode, value: String): Boolean {
        schema.get("\$ref")?.let { reference ->
            assertTrue(reference.asText().startsWith("#/\$defs/"), reference.asText())
            return accepts(document.at(reference.asText().removePrefix("#")), value)
        }
        schema.get("not")?.let { forbidden ->
            assertEquals(setOf("pattern"), forbidden.fieldNames().asSequence().toSet())
            if (regex(forbidden.path("pattern").asText()).containsMatchIn(value)) return false
        }
        schema.get("pattern")?.let { if (!regex(it.asText()).containsMatchIn(value)) return false }
        return schema.path("allOf").all { accepts(it, value) }
    }

    private fun regex(pattern: String): Regex = compiled.getOrPut(pattern) { Regex(pattern) }

    private fun previous(pattern: String, vararg forbidden: String): (String) -> Boolean {
        val required = Regex(pattern)
        val forbiddenPatterns = forbidden.map(::Regex)
        return { value -> required.containsMatchIn(value) && forbiddenPatterns.none { it.containsMatchIn(value) } }
    }

    private fun assertSameLanguage(label: String, previous: (String) -> Boolean, current: (String) -> Boolean, values: List<String>) {
        values.forEach { value -> assertEquals(previous(value), current(value), "$label: ${escaped(value)}") }
    }

    private fun patternNodes(node: JsonNode, location: String = ""): List<Pair<String, JsonNode>> = when (node) {
        is ObjectNode -> (if (node.path("pattern").isTextual) listOf(location to node) else emptyList()) +
            node.properties().filter { it.key != "not" }.flatMap { (key, value) -> patternNodes(value, "$location/${key.replace("~", "~0").replace("/", "~1")}") }
        is ArrayNode -> node.flatMapIndexed { index, value -> patternNodes(value, "$location/$index") }
        else -> emptyList()
    }

    private fun words(prefix: String, alphabet: List<Char>, maximumLength: Int): List<String> {
        val values = mutableListOf<String>()
        var layer = listOf(prefix)
        repeat(maximumLength + 1) { length ->
            values += layer
            if (length < maximumLength) layer = layer.flatMap { word -> alphabet.map { word + it } }
        }
        return values
    }

    private fun catalogCandidates(): List<String> {
        val root = "workspace/connectors/$CONNECTOR_ID/catalog"
        val templates = listOf(
            "", "/catalogs", "/catalogs/{}", "/catalogs/{}/schemas", "/schemas", "/schemas/{}", "/catalogs/{}/schemas/{}",
            "/schemas/{}/relations", "/schemas/{}/relations/{}", "/catalogs/{}/schemas/{}/relations", "/catalogs/{}/schemas/{}/relations/{}",
            "/catalog-selection", "/selection", "/catalogs/{}/selection", "/catalogs/{}/selection/{}",
            "/catalog-selection/options/catalogs", "/catalog-selection/options/catalogs/{}", "/catalog-selection/options/catalogs/{}/{}",
            "/selection/options/schemas", "/selection/options/schemas/{}", "/selection/options/schemas/{}/relations",
            "/selection/options/schemas/{}/relations/{}", "/catalogs/{}/selection/options/schemas", "/catalogs/{}/selection/options/schemas/{}",
            "/catalogs/{}/selection/options/schemas/{}/relations", "/catalogs/{}/selection/options/schemas/{}/relations/{}",
            "/catalog-selection/option-refreshes/catalogs", "/catalog-selection/option-refreshes/catalogs/$RUN_ID",
            "/selection/option-refreshes/schemas/$RUN_ID", "/selection/option-refreshes/schemas/{}/relations",
            "/selection/option-refreshes/schemas/{}/relations/$RUN_ID", "/catalogs/{}/selection/option-refreshes/schemas",
            "/catalogs/{}/selection/option-refreshes/schemas/{}/relations/$RUN_ID", "/ingestions", "/ingestions/$RUN_ID",
            "/ingestions/$RUN_ID/failures", "/ingestions/$RUN_ID/failures/$RUN_ID", "/catalogs/{}/ingestions",
            "/catalogs/{}/ingestions/$RUN_ID", "/catalogs/{}/ingestions/$RUN_ID/failures", "/catalogs/{}/ingestions/$RUN_ID/failures/$RUN_ID",
        ).map { root + it }
        val fills = words("", NAME_ALPHABET, 3)
        val slotFills = templates.flatMap { template ->
            val slots = template.split("{}")
            fills.flatMap { fill ->
                listOf(slots.joinToString(fill)) + (0 until slots.size - 1).map { slot ->
                    slots.withIndex().joinToString("") { (index, part) -> part + if (index == slots.size - 1) "" else if (index == slot) fill else "a~0" }
                }
            }
        }
        val neighbours = templates.map { it.replace("{}", "a~1b") }.flatMap { path ->
            path.indices.flatMap { index ->
                listOf(path.removeRange(index, index + 1)) + NAME_ALPHABET.flatMap { character ->
                    listOf(path.substring(0, index) + character + path.substring(index), path.replaceRange(index, index + 1, character.toString()))
                }
            } + NAME_ALPHABET.map { path + it }
        }
        return (slotFills + neighbours).distinct()
    }

    private fun escaped(value: String): String = value.map { if (it.code < 0x20 || it.code in 0x7f..0x9f) "\\u%04X".format(it.code) else "$it" }.joinToString("")

    private companion object {
        const val CONNECTOR_ID = "01942b04-6e00-7000-8000-000000000010"
        const val RUN_ID = "01942b04-6e00-7000-8000-000000000011"
        const val MAXIMUM_PATH_LENGTH = 6_257
        const val MAXIMUM_POINTER_LENGTH = 2_048
        const val MAXIMUM_REQUEST_BYTES = 1_048_576
        val MAXIMUM_SEMANTIC_NAME = "a" + "_a".repeat(511)
        const val CONTROL = """\u0000-\u001F\u007F-\u009F"""
        const val NAME_TOKEN = """[^/\u0000-\u001F\u007F-\u009F]+"""
        const val PREVIOUS_NAME_TOKEN = """(?:[^~/\u0000-\u001F\u007F-\u009F]|~[01])+"""
        const val EDIT_SUFFIX = """(?:/[\s\S]*)?$"""
        const val PREVIOUS_EDIT_SUFFIX = """(?:/(?:[^~/]|~[01])*)*$"""
        const val PREVIOUS_POINTER = """^(?:/(?:[^~/]|~[01])*)*$"""
        const val SEMANTIC_TOKEN = AgentPathPatterns.SEMANTIC_NAME
        const val PREVIOUS_SEMANTIC_TOKEN = "[a-z][a-z0-9]*(?:_[a-z0-9]+)*"
        val ESCAPE_ALPHABET = listOf('/', '~', '0', '1', 'a', '\n', '\u0085')
        val NAME_ALPHABET = listOf('/', '~', '0', '1', '2', 'a', '\n', '\u0085')
        val SEMANTIC_ALPHABET = listOf('a', '0', '_', '/', '\n', '\u0085', 'A')
    }
}
