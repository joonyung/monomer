package cc.monomer.control.publicapi

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion

class PublicControlContractValidator {
    fun agentResource(body: Map<String, Any?>): Map<String, Any?> = validate(agentResourceSchema, body)

    fun agentListing(body: Map<String, Any?>): Map<String, Any?> = validate(agentListingSchema, body)

    fun agentSearch(body: Map<String, Any?>): Map<String, Any?> = validate(agentSearchSchema, body)

    fun agentAction(body: Map<String, Any?>): Map<String, Any?> = validate(agentActionSchema, body)

    fun problem(body: Map<String, Any?>): Map<String, Any?> = validate(problemSchema, body)

    private fun validate(
        schema: JsonSchema,
        body: Map<String, Any?>,
    ): Map<String, Any?> {
        val violations = schema.validate(objectMapper.valueToTree(body))
        check(violations.isEmpty()) { "Public contract serialization does not match its frozen schema." }
        return body
    }

    private companion object {
        val objectMapper = ObjectMapper()
        val documents =
            listOf(
                resource("contracts/agent-workspace.schema.json"),
                resource("contracts/connector/connector.schema.json"),
                resource("contracts/problem-details.schema.json"),
                resource("contracts/metric-display.schema.json"),
                resource("contracts/semantic/model.schema.json"),
                resource("contracts/semantic/metric.schema.json"),
                resource("contracts/semantic/query.schema.json"),
                resource("contracts/dashboard/dashboard.schema.json"),
            )
        val schemaFactory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012) { builder ->
                builder.schemaLoaders { loaders ->
                    loaders.schemas(documents.associate { document -> document.path("\$id").asText() to document.toString() })
                }
            }
        val problemSchema = schemaFactory.getSchema(documents.first { document -> document.path("\$id").asText() == PROBLEM_SCHEMA_ID })
        val agentSchemaDocument = documents.first { document -> document.path("\$id").asText() == AGENT_SCHEMA_ID }
        val agentResourceSchema = definitionSchema("AgentResourceEnvelope")
        val agentSearchSchema = definitionSchema("AgentSearchResponse")
        val agentListingSchema = definitionSchema("AgentResourceListingResponse")
        val agentActionSchema = definitionSchema("AgentActionResult")

        fun resource(path: String): JsonNode =
            PublicControlContractValidator::class.java.classLoader.getResourceAsStream(path)
                ?.use(objectMapper::readTree)
                ?: error("Missing packaged contract resource: $path")

        fun definitionSchema(definition: String): JsonSchema =
            schemaFactory.getSchema(
                objectMapper.createObjectNode().apply {
                    put("\$schema", agentSchemaDocument.path("\$schema").asText())
                    put("\$id", agentSchemaDocument.path("\$id").asText())
                    put("\$ref", "#/\$defs/$definition")
                    set<JsonNode>("\$defs", agentSchemaDocument.path("\$defs").deepCopy())
                },
            )

        const val AGENT_SCHEMA_ID = "https://monomer.cc/schemas/agent-workspace/v1"
        const val PROBLEM_SCHEMA_ID = "https://monomer.cc/schemas/problem-details/v1"
    }
}
