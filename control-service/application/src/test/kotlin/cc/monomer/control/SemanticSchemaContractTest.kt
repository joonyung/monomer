package cc.monomer.control

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SemanticSchemaContractTest {
    private val objectMapper = ObjectMapper()
    private val controlRoot = Path.of(System.getProperty("control.service.root"))

    @Test
    fun `application packages only the current Semantic schemas from the owner module`() {
        val expected = mapOf(
            "model.schema.json" to Pair("monomer://schema/semantic/model/3", listOf("schemaVersion", "name", "sourceRelations", "entities", "dimensions", "measures")),
            "metric.schema.json" to Pair("monomer://schema/semantic/metric/3", emptyList()),
            "query.schema.json" to Pair("monomer://schema/semantic/query/4", listOf("schemaVersion", "metrics", "groupBys", "metricTimeRange", "filters", "orderBy", "rowLimit")),
            "time-spine.schema.json" to Pair("monomer://schema/semantic/time-spine/1", listOf("schemaVersion", "timeSpines")),
        )

        expected.forEach { (fileName, contract) ->
            assertThat(sourceCopies(fileName)).containsExactly(
                controlRoot.resolve("modules/semantic/src/main/resources/schema/semantic/$fileName"),
            )
            val schema = readSchema(fileName)
            assertThat(schema.path("\$schema").asText())
                .isEqualTo("https://json-schema.org/draft/2020-12/schema")
            assertThat(schema.path("\$id").asText()).isEqualTo(contract.first)
            assertThat(schema.path("additionalProperties").asBoolean()).isFalse()
            if (contract.second.isNotEmpty()) {
                assertThat(schema.path("required").map(JsonNode::asText))
                    .containsExactlyElementsOf(contract.second)
            }
            assertThat(schema.toString()).doesNotContain("sourceRelationId", "scalarKind", "definitionDigest", "publication")
        }
    }

    @Test
    fun `model schema accepts each timeKind only on time dimensions`() {
        val schema = draft202012Schema(readSchema("model.schema.json"))
        fun model(dimension: String) = objectMapper.readTree("""{
            "schemaVersion":3,"name":"orders",
            "sourceRelations":[{"connectorId":"0197d83f-2c9e-7a3b-8c1d-000000000001","schema":"analytics","relation":"orders"}],
            "entities":[{"name":"order_id","type":"primary","expression":"order_id"}],
            "dimensions":[$dimension],"measures":[]
        }""")
        listOf("date", "local", "instant").forEach { kind ->
            assertThat(schema.validate(model("""{"name":"ordered_at","type":"time","granularity":"day","expression":"ordered_at","isPartition":false,"timeKind":"$kind"}""")))
                .describedAs(kind).isEmpty()
        }
        assertThat(schema.validate(model("""{"name":"ordered_at","type":"time","granularity":"day","expression":"ordered_at","isPartition":false}"""))).isEmpty()
        assertThat(schema.validate(model("""{"name":"ordered_at","type":"time","granularity":"day","expression":"ordered_at","isPartition":false,"timeKind":"zoned"}"""))).isNotEmpty()
        assertThat(schema.validate(model("""{"name":"status","type":"categorical","expression":"status","isPartition":false,"timeKind":"local"}"""))).isNotEmpty()
    }

    @Test
    fun `metric filter schema enforces operator arity`() {
        val schema = draft202012Schema(readSchema("metric.schema.json"))

        assertMetricFilterAccepts(schema, "is_null", listOf())
        assertMetricFilterAccepts(schema, "between", listOf("a", "b"))
        assertMetricFilterAccepts(schema, "in", listOf("a"))
        assertMetricFilterAccepts(schema, "eq", listOf("a"))

        assertMetricFilterRejects(schema, "is_null", listOf("a"))
        assertMetricFilterRejects(schema, "between", listOf("a"))
        assertMetricFilterRejects(schema, "between", listOf("a", "b", "c"))
        assertMetricFilterRejects(schema, "in", emptyList())
        assertMetricFilterRejects(schema, "eq", listOf("a", "b"))
    }

    @Test
    fun `query schema limits only timestamp filter fractional digits for every operator`() {
        val schema = draft202012Schema(readSchema("query.schema.json"))
        val operators = listOf("EQUALS", "NOT_EQUALS", "IN", "NOT_IN", "GREATER_THAN", "GREATER_THAN_OR_EQUAL", "LESS_THAN", "LESS_THAN_OR_EQUAL")
        for (operator in operators) {
            for (fraction in listOf("", ".1", ".12", ".123", ".1234", ".12345", ".123456", ".000000")) {
                assertThat(schema.validate(queryWithTimestampFilter(operator, "2026-10-01T00:00:00$fraction+09:00")))
                    .describedAs("$operator accepts $fraction").isEmpty()
            }
            for (fraction in listOf("1234567", "12345678", "123456789", "1234567890", "1234560", "0000000")) {
                assertThat(schema.validate(queryWithTimestampFilter(operator, "2026-10-01T00:00:00.$fraction+09:00")))
                    .describedAs("$operator rejects $fraction").isNotEmpty()
            }
        }
    }

    private fun queryWithTimestampFilter(operator: String, value: String): JsonNode =
        objectMapper.readTree("""{
            "schemaVersion":4,"metrics":["revenue"],"groupBys":[],
            "metricTimeRange":{"start":{"kind":"TIMESTAMP","value":"2026-09-01T00:00:00.123456789Z"},
                "end":{"kind":"TIMESTAMP","value":"2026-10-01T00:00:00Z"}},
            "filters":[{"kind":"TIMESTAMP","dimension":{"semanticModelName":"orders","name":"ordered_at"},
                "operator":"$operator","values":["$value"]}],"orderBy":[],"rowLimit":100
        }""")

    private fun assertMetricFilterAccepts(schema: JsonSchema, operator: String, values: List<String>) {
        assertThat(schema.validate(metricWithFilter(operator, values)))
            .describedAs("$operator with $values must satisfy the metric schema")
            .isEmpty()
    }

    private fun assertMetricFilterRejects(schema: JsonSchema, operator: String, values: List<String>) {
        assertThat(schema.validate(metricWithFilter(operator, values)))
            .describedAs("$operator with $values must violate the metric schema")
            .isNotEmpty()
    }

    private fun metricWithFilter(operator: String, values: List<String>): JsonNode =
        objectMapper.createObjectNode().apply {
            put("schemaVersion", 3)
            put("name", "filtered_ratio")
            put("type", "ratio")
            putObject("numerator").put("metricName", "orders")
            putObject("denominator").put("metricName", "users")
            putArray("filters").addObject().apply {
                putObject("dimension")
                    .put("semanticModelName", "orders")
                    .put("name", "status")
                put("operator", operator)
                putArray("values").addAll(values.map(objectMapper.nodeFactory::textNode))
            }
        }

    private fun readSchema(fileName: String): JsonNode =
        requireNotNull(javaClass.classLoader.getResource("schema/semantic/$fileName"))
            .openStream()
            .use(objectMapper::readTree)

    private fun draft202012Schema(document: JsonNode): JsonSchema =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012) { builder ->
            builder.schemaLoaders { loaders ->
                val display = objectMapper.readTree(controlRoot.resolve("../contracts/metric-display.schema.json").toFile())
                loaders.schemas(mapOf(display.path("\$id").asText() to display.toString()))
            }
        }.getSchema(document)

    private fun sourceCopies(fileName: String): List<Path> =
        Files.walk(controlRoot).use { paths ->
            paths.filter { path ->
                path.fileName.toString() == fileName && path.toString().contains("/src/")
            }.toList()
        }
}
