package cc.monomer.control

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DashboardSchemaContractTest {
    private val objectMapper = ObjectMapper()
    private val controlRoot = Path.of(System.getProperty("control.service.root"))
    private val dashboardSchemaPath = controlRoot.resolve("modules/dashboard/src/main/resources/schema/dashboard.schema.json")
    private val dashboardSchema = objectMapper.readTree(Files.readString(dashboardSchemaPath))
    private val semanticSchema = objectMapper.readTree(
        Files.readString(controlRoot.resolve("modules/semantic/src/main/resources/schema/semantic/query.schema.json")),
    )
    private val schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012) { builder ->
        builder.schemaLoaders { loaders ->
            loaders.schemas(listOf(dashboardSchema, semanticSchema).associate { it.path("\$id").asText() to it.toString() })
        }
    }

    @Test
    fun `section height schema closes modes and bounds while owner validates the root tree`() {
        val schema = definitionSchema("section")
        val widget = """{"type":"WIDGET","id":"item","widgetId":"text"}"""
        fun section(height: String, root: String = widget) = """{"id":"main","height":$height,"root":$root}"""
        assertAccepts(schema, section("""{"mode":"CONTENT"}"""))
        for (scale in listOf("0.3", "1", "1.50", "10.0")) {
            assertAccepts(schema, section("""{"mode":"SCALED","scale":$scale}"""))
        }
        for (height in listOf(
            "null", "1", "{}", """{"mode":"AUTO"}""", """{"mode":null}""",
            """{"mode":"CONTENT","scale":1}""", """{"mode":"CONTENT","extra":true}""",
            """{"mode":"SCALED"}""", """{"mode":"SCALED","scale":1,"extra":true}""",
        ) + listOf("null", "true", "\"1\"", "0.2", "10.1", "0.35").map { """{"mode":"SCALED","scale":$it}""" }) {
            assertRejects(schema, section(height))
        }
        assertRejects(schema, """{"id":"main","root":$widget}""")
        assertRejects(schema, """{"id":"main","heightScale":1,"root":$widget}""")
        assertRejects(schema, """{"id":"main","heightScale":1,"height":{"mode":"CONTENT"},"root":$widget}""")
        val stack = """{"type":"STACK","id":"stack","direction":"ROW","children":[{"weight":1,"node":$widget},{"weight":1,"node":{"type":"SPACER","id":"space"}}]}"""
        assertAccepts(schema, section("""{"mode":"SCALED","scale":1}""", stack))
        assertAccepts(schema, section("""{"mode":"CONTENT"}""", stack))
        assertAccepts(schema, section("""{"mode":"CONTENT"}""", """{"type":"SPACER","id":"space"}"""))
    }

    @Test
    fun `stack child weight is optional but remains a closed positive integer field`() {
        val schema = definitionSchema("layoutNode")
        val widget = """{"type":"WIDGET","id":"item","widgetId":"text"}"""
        fun stack(direction: String, wrapper: String) = """{"type":"STACK","id":"stack","direction":"$direction","children":[$wrapper,{"node":$widget}]}"""
        for (direction in listOf("ROW", "COLUMN")) {
            assertAccepts(schema, stack(direction, """{"node":$widget}"""))
            assertAccepts(schema, stack(direction, """{"weight":1,"node":$widget}"""))
            for (weight in listOf("0", "-1", "1.5", "null", "true", "\"1\"")) {
                assertRejects(schema, stack(direction, """{"weight":$weight,"node":$widget}"""))
            }
            assertRejects(schema, stack(direction, """{"weight":1}"""))
            assertRejects(schema, stack(direction, """{"node":$widget,"extra":true}"""))
        }
    }

    @Test
    fun `public dashboard schema publication maps to its canonical owner`() {
        val ownerPath = "control-service/modules/dashboard/src/main/resources/schema/dashboard.schema.json"
        val publicPath = "public-site/public/schemas/dashboard/dashboard.schema.json"
        val generator = Files.readString(controlRoot.resolve("../public-site/scripts/sync-contracts.mjs"))
        val publications = Regex("""source:\s*"([^"]+)",\s*target:\s*"([^"]+)"""")
            .findAll(generator)
            .map { it.groupValues[1] to it.groupValues[2] }
            .filter { it.first == ownerPath || it.second == publicPath }
            .toList()

        assertThat(publications).containsExactly(ownerPath to publicPath)
        assertThat(dashboardSchema.path("\$id").asText()).isEqualTo("monomer://schema/dashboard/1")
    }

    @Test
    fun `scalar filters and metric time endpoints admit closed endpoint references`() {
        val filterSchema = definitionSchema("filter")
        val metricEndpointSchema = definitionSchema("metricTimeEndpoint")
        for (referenceKind in listOf("PARAMETER", "VARIABLE")) {
            for (endpoint in listOf("start", "end")) {
                val reference = reference(referenceKind, endpoint)
                assertAccepts(metricEndpointSchema, reference)
                for (filterKind in listOf("DATE", "TIMESTAMP")) {
                    assertAccepts(filterSchema, filter(filterKind, "GREATER_THAN_OR_EQUAL", reference))
                }
            }
        }
        for (invalidEndpoint in listOf("START", "middle", "", "endExclusive")) {
            assertRejects(metricEndpointSchema, reference("VARIABLE", invalidEndpoint))
        }
        assertRejects(metricEndpointSchema, """{"kind":"VARIABLE","name":"period","endpoint":null}""")
        assertRejects(metricEndpointSchema, """{"kind":"VARIABLE","name":"period.start"}""")
        for (extraField in listOf("field", "path", "expression")) {
            assertRejects(metricEndpointSchema, """{"kind":"VARIABLE","name":"period","endpoint":"start","$extraField":"start"}""")
        }
    }

    @Test
    fun `whole metric ranges set filters and variable initial remain whole references`() {
        val metricRangeSchema = schemaFactory.getSchema(
            objectMapper.createObjectNode().apply {
                put("\$ref", "${dashboardSchema.path("\$id").asText()}#/\$defs/semanticQuery/properties/metricTimeRange")
            },
        )
        val filterSchema = definitionSchema("filter")
        for (referenceKind in listOf("PARAMETER", "VARIABLE")) {
            assertAccepts(metricRangeSchema, reference(referenceKind, null))
            for (endpoint in listOf("start", "end")) {
                assertRejects(metricRangeSchema, reference(referenceKind, endpoint))
                assertAccepts(
                    metricRangeSchema,
                    """{"start":${reference(referenceKind, endpoint)},"end":${reference(referenceKind, endpoint)}}""",
                )
                for (filterKind in listOf("TEXT", "NUMBER", "DATE", "TIMESTAMP")) {
                    for (operator in listOf("IN", "NOT_IN")) {
                        assertAccepts(filterSchema, filter(filterKind, operator, reference(referenceKind, null)))
                        assertRejects(filterSchema, filter(filterKind, operator, reference(referenceKind, endpoint)))
                    }
                }
            }
        }
        val variableSchema = definitionSchema("variableDefinition")
        assertAccepts(variableSchema, """{"type":"DATE_RANGE","nullable":false,"initial":${reference("PARAMETER", null)}}""")
        for (endpoint in listOf("start", "end")) {
            assertRejects(variableSchema, """{"type":"DATE","nullable":false,"initial":${reference("PARAMETER", endpoint)}}""")
        }
    }

    @Test
    fun `chart and table titles are optional single-line display labels`() {
        val widgetSchema = definitionSchema("widget")
        val chart = """{"type":"CHART","queryNames":["orders"],"module":{"inputs":{"parameters":[],"variables":[]},"source":"export default defineChart({ render() { return {}; } });"}"""
        val table = """{"type":"TABLE","queryName":"orders","columns":[{"field":"metric.orders"}]"""
        for (widget in listOf(chart, table)) {
            assertAccepts(widgetSchema, "$widget}")
            assertAccepts(widgetSchema, """$widget,"title":"Revenue by region"}""")
            assertAccepts(widgetSchema, """$widget,"title":"${"x".repeat(120)}"}""")
            for (title in listOf("\"\"", "\" Revenue\"", "\"Revenue \"", "\"Revenue\\nby region\"", "\"${"x".repeat(121)}\"", "null", "1")) {
                assertRejects(widgetSchema, """$widget,"title":$title}""")
            }
        }
        assertRejects(widgetSchema, """{"type":"TEXT","content":"Notes","title":"Notes"}""")
        assertRejects(widgetSchema, """{"type":"INPUT","variableName":"region","title":"Region"}""")
    }

    @Test
    fun `emptyLabel is limited to choice controls and nullable Parameters`() {
        val parameterSchema = definitionSchema("valueDefinition")
        val controlSchema = definitionSchema("control")
        val choice = """{"options":[{"value":"north"}],"emptyLabel":"All regions"}"""
        assertAccepts(parameterSchema, """{"type":"TEXT","nullable":true,"control":$choice}""")
        assertAccepts(parameterSchema, """{"type":"BOOLEAN","nullable":true,"control":{"options":[{"value":true}],"emptyLabel":"Any"}}""")
        assertRejects(parameterSchema, """{"type":"TEXT","nullable":false,"control":$choice}""")
        assertRejects(parameterSchema, """{"type":"TEXT","nullable":true,"control":{"placeholder":"Region","emptyLabel":"All regions"}}""")
        assertRejects(parameterSchema, """{"type":"TEXT","nullable":true,"control":{"options":[{"value":"north"}],"emptyLabel":""}}""")
        assertAccepts(controlSchema, choice)
        assertRejects(controlSchema, """{"placeholder":"Region","emptyLabel":"All regions"}""")
        assertRejects(controlSchema, """{"emptyLabel":"All regions"}""")
        assertRejects(controlSchema, """{"options":[{"value":"north"}],"emptyLabel":" All"}""")
    }

    private fun reference(kind: String, endpoint: String?): String =
        if (endpoint == null) """{"kind":"$kind","name":"period"}"""
        else """{"kind":"$kind","name":"period","endpoint":"$endpoint"}"""

    private fun filter(kind: String, operator: String, reference: String): String =
        """{"kind":"$kind","dimension":{"semanticModelName":"orders","name":"created_at"},"operator":"$operator","value":$reference}"""

    private fun definitionSchema(definition: String): JsonSchema = schemaFactory.getSchema(
        objectMapper.createObjectNode().apply {
            put("\$ref", "${dashboardSchema.path("\$id").asText()}#/\$defs/$definition")
        },
    )

    private fun assertAccepts(schema: JsonSchema, value: String) {
        assertThat(schema.validate(objectMapper.readTree(value))).describedAs(value).isEmpty()
    }

    private fun assertRejects(schema: JsonSchema, value: String) {
        assertThat(schema.validate(objectMapper.readTree(value))).describedAs(value).isNotEmpty()
    }
}
