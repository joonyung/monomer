package cc.monomer.agent.application

import cc.monomer.agent.api.AgentWorkspaceRequestDecodingException
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentEditContractTest {
    private val decoder = AgentWorkspaceContractDecoder()
    private val mapper = ObjectMapper()

    @Test
    fun `edit schema examples decode through public request boundary`() {
        val document = javaClass.classLoader.getResourceAsStream("contracts/agent-workspace.schema.json")!!.use(mapper::readTree)
        val definitions = document.path("\$defs")
        val variants = definitions.path("AgentEditRequest").path("oneOf")
        assertEquals(8, variants.size())
        variants.forEach { branch ->
            val variant = definitions.path(branch.path("\$ref").asText().substringAfterLast('/'))
            assertTrue(variant.path("examples").size() > 0)
            variant.path("examples").forEach { decoder.edit(it.toString()) }
        }
    }

    @Test
    fun `resource path determines revision policy without an input kind`() {
        listOf(MODEL, METRIC, CATALOG_SELECTION, ENTRY_SELECTION, NESTED_SELECTION).forEach { path ->
            val patch = when (path) {
                MODEL, METRIC -> PARTIAL
                CATALOG_SELECTION -> root("""{"selectedCatalogNames":[]}""")
                else -> root("""{"selectedSchemas":[]}""")
            }
            assertEquals(null, decoder.edit(request(path, patch, null)).value.revision)
            listOf("\"0\"", "null").forEach { revision -> reject(request(path, patch, revision)) }
        }
        listOf(DRAFT).forEach { path ->
            val patch = if (path == DRAFT) """[{"op":"replace","path":"/definition/meta/title","value":"New"}]""" else VARIABLE_PATCH
            assertEquals(0, decoder.edit(request(path, patch, "\"0\"")).value.revision!!.value)
            listOf(null, "null", "0", "\"-1\"").forEach { reject(request(path, patch, it)) }
        }
        reject(request("workspace/dashboards/$ID", PARTIAL, "\"0\""))
        reject(request("workspace/metrics", PARTIAL, null))
    }

    @Test
    fun `semantic root replacements reuse full schemas while partial values remain sequential`() {
        decoder.edit(request(MODEL, root(MODEL_DEFINITION), null))
        decoder.edit(request(METRIC, root(METRIC_DEFINITION), null))
        reject(request(MODEL, root(METRIC_DEFINITION), null))
        reject(request(METRIC, root("""{"schemaVersion":3,"name":"revenue","type":"simple"}"""), null))
        decoder.edit(request(METRIC, """[
            {"op":"remove","path":"/measure"},
            {"op":"add","path":"/measure","value":"amount"},
            {"op":"add","path":"/filters","value":[]}
        ]""", null))
        decoder.edit(request(MODEL, """[
            {"op":"add","path":"/measures/-","value":{"name":"amount"}},
            {"op":"add","path":"/measures/0/expression","value":"amount"},
            {"op":"add","path":"/measures/0/aggregation","value":"SUM"}
        ]""", null))
        listOf(MODEL, METRIC, DRAFT).forEach { path ->
            val revision = if (path == DRAFT) "\"0\"" else null
            listOf("add", "remove").forEach { op ->
                reject(request(path, """[{"op":"$op","path":""${if (op == "add") ",\"value\":{}" else ""}}]""", revision))
            }
        }
    }

    @Test
    fun `metric display is an editable document field`() {
        decoder.edit(request(METRIC, """[{"op":"add","path":"/display","value":{"style":"PERCENT","decimals":2}}]""", null))
        decoder.edit(request(METRIC, """[{"op":"replace","path":"/display/decimals","value":4}]""", null))
        decoder.edit(request(METRIC, """[{"op":"remove","path":"/display"}]""", null))
    }

    @Test
    fun `read envelope and server facts are outside every editable document`() {
        listOf(MODEL, METRIC, DRAFT).forEach { path ->
            val revision = if (path == DRAFT) "\"0\"" else null
            listOf("/body", "/revision", "/kind", "/path", "/requestId", "/availableOperations", "/queryRuns", "/updatedAt").forEach { pointer ->
                reject(request(path, """[{"op":"add","path":"$pointer","value":{}}]""", revision))
            }
        }
        reject(request(DRAFT, root("""{"definition":$DASHBOARD_DEFINITION,"parameterBindings":{},"variables":{},"revision":"0"}"""), "\"0\""))
        reject(request(DRAFT, root(DASHBOARD_DEFINITION), "\"0\""))
        decoder.edit(request(DRAFT, root("""{"definition":$DASHBOARD_DEFINITION,"parameterBindings":{},"variables":{}}"""), "\"0\""))
    }

    @Test
    fun `session edit is rejected for every revision and patch form`() {
        listOf(null, "null", "0", "\"0\"").forEach { revision ->
            reject(request(SESSION, VARIABLE_PATCH, revision))
            reject(request(SESSION, root("{}"), revision))
        }
    }

    @Test
    fun `selections require exactly one complete root replacement and keep known empty sets`() {
        listOf(CATALOG_SELECTION, ENTRY_SELECTION, NESTED_SELECTION).forEach { path ->
            val value = if (path == CATALOG_SELECTION) """{"selectedCatalogNames":[]}""" else """{"selectedSchemas":[]}"""
            val operation = root(value)
            decoder.edit(request(path, operation, null))
            reject(request(path, "[]", null))
            reject(request(path, "[${operation.removeSurrounding("[", "]")},${operation.removeSurrounding("[", "]")}]", null))
            reject(request(path, operation.replace("\"path\":\"\"", "\"path\":\"/selectedSchemas\""), null))
            reject(request(path, root("{}"), null))
        }
        decoder.edit(request(ENTRY_SELECTION, root("""{"selectedSchemas":[{"schemaName":"analytics","relations":{"kind":"SELECTED_RELATIONS","relationNames":[]}}]}"""), null))
    }

    @Test
    fun `path selected diagnostics never include other edit branches or sensitive keys`() {
        val missingRevision = reject(request(DRAFT, VARIABLE_PATCH, null))
        assertEquals(listOf("revision"), missingRevision.violations.map { it.field })
        val missingMeasure = reject(request(METRIC, root("""{"schemaVersion":3,"name":"revenue","type":"simple","semanticModelName":"orders"}"""), null))
        assertEquals(listOf("patch[0].value.measure"), missingMeasure.violations.map { it.field })
        assertEquals(0, missingMeasure.violations.single().operationIndex)
        assertEquals("", missingMeasure.violations.single().path)
        val unknown = reject(request("workspace/secret-resource", """[{"op":"replace","path":"/secret-pointer","value":{"secret-key":"secret-value"}}]""", null))
        assertFalse(unknown.violations.toString().contains("secret-"))
        assertTrue(unknown.violations.none { it.field.contains("revision") || it.field.contains("selectedSchemas") || it.field.contains("measure") })
        val secret = reject(request(SESSION, """[{"op":"replace","path":"/variables/secret_name","value":{"secret-key":"secret-value"}}]""", "\"0\""))
        assertFalse(secret.violations.toString().contains("secret"))
        assertTrue(secret.violations.all { it.path == null })
    }

    private fun reject(body: String) = assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.edit(body) }

    private fun request(path: String, patch: String, revision: String?): String =
        """{"path":"$path","patch":$patch${if (path == MODEL) ",\"aggregateMetrics\":[]" else ""}${revision?.let { ",\"revision\":$it" }.orEmpty()}}"""

    private fun root(value: String) = """[{"op":"replace","path":"","value":$value}]"""

    private companion object {
        const val ID = "01942b04-6e00-7000-8000-000000000010"
        const val MODEL = "workspace/semantic-models/orders"
        const val METRIC = "workspace/metrics/revenue"
        const val DRAFT = "workspace/dashboards/$ID/draft"
        const val SESSION = "workspace/dashboards/$ID/sessions/$ID"
        const val CATALOG_SELECTION = "workspace/connectors/$ID/catalog/catalog-selection"
        const val ENTRY_SELECTION = "workspace/connectors/$ID/catalog/selection"
        const val NESTED_SELECTION = "workspace/connectors/$ID/catalog/catalogs/warehouse/selection"
        const val PARTIAL = """[{"op":"replace","path":"/label","value":"New"}]"""
        const val VARIABLE_PATCH = """[{"op":"replace","path":"/variables/region","value":"APAC"}]"""
        const val METRIC_DEFINITION = """{"schemaVersion":3,"name":"revenue","type":"simple","semanticModelName":"missing_model","measure":"amount"}"""
        const val MODEL_DEFINITION = """{"schemaVersion":3,"name":"orders","sourceRelations":[{"connectorId":"$ID","schema":"analytics","relation":"orders"}],"entities":[{"name":"order_id","type":"primary","expression":"order_id"}],"dimensions":[],"measures":[]}"""
        const val DASHBOARD_DEFINITION = """{"schemaVersion":1,"meta":{"title":"Orders"},"parameters":{},"variables":{},"queries":{},"widgets":{},"layout":{"sections":[]}}"""
    }
}
