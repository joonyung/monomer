package cc.monomer.control.sample

import cc.monomer.connector.api.ConnectorId
import cc.monomer.control.semantic.inbound.rest.MetricDefinitionDto
import cc.monomer.control.semantic.inbound.rest.SemanticModelDefinitionDto
import cc.monomer.control.semantic.inbound.rest.SemanticTimeSpineDefinitionRequest
import cc.monomer.semantic.api.SemanticMetricDefinition
import cc.monomer.semantic.api.SemanticModelDefinition
import cc.monomer.semantic.api.SemanticTimeSpineDefinition
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import java.time.LocalDate
import kotlinx.serialization.json.*

class SampleDefinitionBundle {
    private val mapper = ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .findAndRegisterModules().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
    private val dtoMapper = mapper.copy().disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val contractDocuments = listOf("contracts/agent-workspace.schema.json", "contracts/connector/connector.schema.json",
        "contracts/semantic/model.schema.json", "contracts/semantic/metric.schema.json", "contracts/semantic/query.schema.json",
        "contracts/metric-display.schema.json", "contracts/dashboard/dashboard.schema.json").map { resource ->
        javaClass.classLoader.getResourceAsStream(resource)!!.use(mapper::readTree)
    }
    private val schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012) { builder ->
        builder.schemaLoaders { loaders -> loaders.schemas(contractDocuments.associate { it.path("\$id").asText() to it.toString() }) }
    }
    private val requestSchema = schemaFactory.getSchema(mapper.createObjectNode().apply {
        put("\$schema", "https://json-schema.org/draft/2020-12/schema")
        put("\$ref", "https://monomer.cc/schemas/agent-workspace/v1#/\$defs/AgentCreateRequest")
    })

    fun read(dataset: String, connectorId: ConnectorId, initialDate: LocalDate): SampleDefinitions {
        val resourceDirectory = when (dataset) {
            "acme-cloud@1" -> "sample-data/acme-cloud/1/definitions"
            "acme-cloud@2" -> "sample-data/acme-cloud/2/definitions"
            "acme-cloud@3" -> "sample-data/acme-cloud/3/definitions"
            else -> error("Sample dataset is unsupported")
        }
        fun boundFile(name: String): JsonElement = javaClass.classLoader.getResourceAsStream("$resourceDirectory/$name")
            ?.use { stream ->
                val strict = mapper.readTree(stream)
                bind(Json.parseToJsonElement(strict.toString()), connectorId, initialDate)
            } ?: error("Sample definition resource is unavailable")
        fun decodeInput(path: String, input: JsonElement): JsonNode {
            val request = mapper.readTree(buildJsonObject { put("path", path); put("input", input) }.toString())
            check(requestSchema.validate(request).isEmpty()) { "Sample input violates the owning Agent contract" }
            return request.get("input")
        }
        val models = boundFile("semantic-models.json").jsonArray.map { input ->
            val value = decodeInput("workspace/semantic-models", input)
            check(value.required("aggregateMetrics").isEmpty) { "Sample aggregate metric inputs must be explicit and empty" }
            dtoMapper.treeToValue(value.required("definition"), SemanticModelDefinitionDto::class.java).toDomain()
        }
        val metrics = boundFile("metrics.json").jsonArray.map { input ->
            dtoMapper.treeToValue(decodeInput("workspace/metrics", input).required("definition"), MetricDefinitionDto::class.java).toDomain()
        }
        val dashboardNames = if (dataset == "acme-cloud@3") listOf("board-metrics", "revenue-movements", "customer-segments", "product-engagement") else listOf("revenue-overview", "product-usage")
        val dashboards = dashboardNames.associateWith { name ->
            val input = decodeInput("workspace/dashboards", boundFile("$name.json"))
            Json.parseToJsonElement(input.required("definition").toString()).jsonObject
        }
        val spine = dtoMapper.treeToValue(mapper.readTree(boundFile("time-spine.json").toString()), SemanticTimeSpineDefinitionRequest::class.java).toDomain()
        return SampleDefinitions(models, metrics, spine, dashboards)
    }
}

data class SampleDefinitions(
    val models: List<SemanticModelDefinition>,
    val metrics: List<SemanticMetricDefinition>,
    val timeSpine: SemanticTimeSpineDefinition,
    val dashboards: Map<String, JsonObject>,
)

private fun bind(value: JsonElement, connectorId: ConnectorId, initialDate: LocalDate): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.mapValues { bind(it.value, connectorId, initialDate) })
    is JsonArray -> JsonArray(value.map { bind(it, connectorId, initialDate) })
    is JsonPrimitive -> when {
        value.isString && value.content == "\${SAMPLE_CONNECTOR_ID}" -> JsonPrimitive(connectorId.toString())
        value.isString && value.content == "\${SAMPLE_DATE_RANGE}" -> buildJsonObject {
            put("start", initialDate.minusDays(90).toString()); put("end", initialDate.minusDays(1).toString())
        }
        value.isString && value.content == "\${SAMPLE_YEAR_RANGE}" -> buildJsonObject {
            put("start", initialDate.minusMonths(12).toString()); put("end", initialDate.minusDays(1).toString())
        }
        else -> value
    }
}
