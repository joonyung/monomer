@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.semantic.inbound.rest

import cc.monomer.identity.api.AuthorizedMutationContext
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.MetricView
import cc.monomer.semantic.api.SemanticConstantProperty
import cc.monomer.semantic.api.SemanticConversionCalculation
import cc.monomer.semantic.api.SemanticDimensionDefinition
import cc.monomer.semantic.api.SemanticDimensionKind
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticEntityDefinition
import cc.monomer.semantic.api.SemanticEntityKind
import cc.monomer.semantic.api.SemanticMeasureAggregation
import cc.monomer.semantic.api.SemanticMeasureDefinition
import cc.monomer.semantic.api.SemanticMetricDefinition
import cc.monomer.semantic.api.SemanticMetricInput
import cc.monomer.semantic.api.SemanticMetricPeriodAggregation
import cc.monomer.semantic.api.SemanticMetricWindow
import cc.monomer.semantic.api.SemanticMetricWindowUnit
import cc.monomer.semantic.api.SemanticModelDefinition
import cc.monomer.semantic.api.SemanticModelId
import cc.monomer.semantic.api.SemanticModelView
import cc.monomer.semantic.api.SemanticMutationResult
import cc.monomer.semantic.api.SemanticSourceRelation
import cc.monomer.semantic.api.SemanticTimeGranularity
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.json.Jackson2JsonDecoder
import org.springframework.http.codec.json.Jackson2JsonEncoder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.ResponseStatusException

class SemanticCurrentControllerWireTest {
    private val authority = mockk<cc.monomer.semantic.api.SemanticAuthorityUseCase>()
    private val currentRead = mockk<cc.monomer.semantic.api.SemanticCurrentReadUseCase>()
    private val authorizer = mockk<SemanticWorkspaceRequestAuthorizer>()
    private val authorization = mockk<AuthorizedMutationContext>(relaxed = true) { io.mockk.every { agentClientId } returns null }
    private val displayNames = mockk<cc.monomer.identity.api.PrincipalDisplayNames>().also { names ->
        io.mockk.coEvery { names.read(any()) } answers { firstArg<Set<Uuid>>().associateWith { "Sam Kim" } }
    }
    private val modelClient = WebTestClient
        .bindToController(SemanticModelController(authority, currentRead, authorizer, displayNames, cc.monomer.control.agentapp.FixedAgentAppDisplays))
        .httpMessageCodecs { codecs ->
            codecs.defaultCodecs().jackson2JsonDecoder(Jackson2JsonDecoder(strictObjectMapper()))
            codecs.defaultCodecs().jackson2JsonEncoder(Jackson2JsonEncoder(strictObjectMapper()))
        }
        .controllerAdvice(SemanticDefinitionProblemHandler())
        .build()
    private val metricClient = WebTestClient
        .bindToController(MetricController(authority, currentRead, authorizer, displayNames, cc.monomer.control.agentapp.FixedAgentAppDisplays))
        .httpMessageCodecs { codecs ->
            codecs.defaultCodecs().jackson2JsonDecoder(Jackson2JsonDecoder(strictObjectMapper()))
            codecs.defaultCodecs().jackson2JsonEncoder(Jackson2JsonEncoder(strictObjectMapper()))
        }
        .controllerAdvice(SemanticDefinitionProblemHandler())
        .build()
    private val lenientMetricClient = WebTestClient
        .bindToController(MetricController(authority, currentRead, authorizer, displayNames, cc.monomer.control.agentapp.FixedAgentAppDisplays))
        .httpMessageCodecs { codecs ->
            codecs.defaultCodecs().jacksonJsonDecoder(
                org.springframework.http.codec.json.JacksonJsonDecoder(
                    tools.jackson.databind.json.JsonMapper.builder()
                        .findAndAddModules()
                        .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .build(),
                ),
            )
        }
        .controllerAdvice(SemanticDefinitionProblemHandler())
        .build()
    private val objectMapper = strictObjectMapper()
    private val checks = mockk<cc.monomer.semantic.api.SemanticDefinitionCheckUseCase>()
    private val productionCodecClient = WebTestClient.bindToController(
        SemanticModelController(authority, currentRead, authorizer, displayNames, cc.monomer.control.agentapp.FixedAgentAppDisplays),
        SemanticCheckController(
            checks,
            authorizer,
            object : cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner {
                override suspend fun <T> run(action: suspend () -> T): T = action()
            },
        ),
    ).controllerAdvice(SemanticDefinitionProblemHandler()).build()

    @Test
    fun `Jackson 3 model definition emits canonical snapshot partition booleans`() {
        val definition = modelDefinition().copy(
            dimensions = modelDefinition().dimensions.mapIndexed { index, dimension -> dimension.copy(isPartition = index == 0) },
        )
        coEvery { currentRead.findModel(WORKSPACE, MODEL_ID) } returns modelView().copy(definition = definition)
        coEvery { currentRead.readModelChange(WORKSPACE, MODEL_ID) } returns null

        val body = productionCodecClient.get().uri(modelItemPath()).exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val dimensions = objectMapper.readTree(body)["definition"]["dimensions"]
        assertEquals(listOf(true, false), dimensions.map { it["isPartition"]?.asBoolean() }, body)
        dimensions.forEach { assertFalse(it.has("partition"), body) }
    }

    @Test
    fun `Jackson 3 model GET definition projected by Web roundtrips through Check`() {
        val definition = modelDefinition().copy(
            dimensions = modelDefinition().dimensions.mapIndexed { index, dimension -> dimension.copy(isPartition = index == 0) },
        )
        val partition = cc.monomer.semantic.api.SemanticSourcePartitionInfo(
            definition.name, CONNECTOR_ID,
            cc.monomer.semantic.api.SemanticPartitionRelation("warehouse", "analytics", "orders"),
            true, listOf("ordered_at"), listOf(SemanticElementName("order_date")), true,
        )
        coEvery { currentRead.findModel(WORKSPACE, MODEL_ID) } returns modelView().copy(
            definition = definition, sourcePartitionInfo = listOf(partition),
        )
        coEvery { currentRead.readModelChange(WORKSPACE, MODEL_ID) } returns null
        coEvery { checks.checkFromInputs(any()) } returns cc.monomer.semantic.api.SemanticCheckResult.Checked(
            listOf(CONNECTOR_ID), null, emptyList(), listOf(cc.monomer.semantic.api.SemanticPartitionFilter(
                cc.monomer.semantic.api.SemanticPartitionFilterKind.SEMANTIC_MODEL,
                CONNECTOR_ID, partition.relation, partition.partitionKeys, definition.name, partition.dimensions, true,
            )),
        )

        val body = productionCodecClient.get().uri(modelItemPath()).exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val response = objectMapper.readTree(body)
        val source = response["sourcePartitionInfo"][0]
        assertEquals(CONNECTOR_ID.value.toString(), source["connectorId"].asText())
        assertEquals(objectMapper.readTree("""{"catalog":"warehouse","schema":"analytics","name":"orders"}"""), source["relation"])
        val projectedDefinition = response["definition"].deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        val projectedDimensions = objectMapper.createArrayNode()
        projectedDefinition["dimensions"].forEach { dimension ->
            val projected = objectMapper.createObjectNode()
            listOf("name", "type", "expression", "isPartition", "granularity", "label", "description").forEach { field ->
                dimension[field]?.takeUnless { it.isNull }?.let { projected.set<com.fasterxml.jackson.databind.JsonNode>(field, it) }
            }
            projectedDimensions.add(projected)
        }
        projectedDefinition.set<com.fasterxml.jackson.databind.JsonNode>("dimensions", projectedDimensions)
        val request = objectMapper.createObjectNode().put("kind", "SEMANTIC_MODEL").put("semanticModelId", MODEL_ID.toString())
        request.set<com.fasterxml.jackson.databind.JsonNode>("definition", projectedDefinition)

        productionCodecClient.post().uri("/v2/workspaces/$WORKSPACE/semantic/check")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(request.toString()).exchange().expectStatus().isOk
            .expectBody().jsonPath("$.ok").isEqualTo(true)
            .jsonPath("$.preview").isEmpty
            .jsonPath("$.runsOn[0]").isEqualTo(CONNECTOR_ID.value.toString())
            .jsonPath("$.partitionFilters[0].connectorId").isEqualTo(CONNECTOR_ID.value.toString())
            .jsonPath("$.partitionFilters[0].relation.catalog").isEqualTo("warehouse")
            .jsonPath("$.partitionFilters[0].relation.schema").isEqualTo("analytics")
            .jsonPath("$.partitionFilters[0].relation.name").isEqualTo("orders")
        coVerify(exactly = 1) { checks.checkFromInputs(match {
            val draft = it.request.draft as? cc.monomer.semantic.api.SemanticCheckDraft.Model
            it.request.workspaceId == WORKSPACE && draft?.semanticModelId == MODEL_ID && draft.definition == definition
        }) }
    }

    @Test
    fun `model definitions carry a declared timeKind through GET and Check and report time kind problems`() {
        val definition = modelDefinition().copy(
            dimensions = modelDefinition().dimensions.map { dimension ->
                if (dimension.type == SemanticDimensionKind.TIME) dimension.copy(timeKind = cc.monomer.semantic.api.SemanticTimeKind.INSTANT) else dimension
            },
        )
        coEvery { currentRead.findModel(WORKSPACE, MODEL_ID) } returns modelView().copy(definition = definition)
        coEvery { currentRead.readModelChange(WORKSPACE, MODEL_ID) } returns null
        coEvery { checks.checkFromInputs(any()) } returns cc.monomer.semantic.api.SemanticCheckResult.Checked(
            listOf(CONNECTOR_ID), null,
            listOf(
                cc.monomer.semantic.api.SemanticCheckProblem(cc.monomer.semantic.api.SemanticCheckProblemCode.TIME_KIND_UNDECLARED, "dimensions[0]", CONNECTOR_ID, null, null),
                cc.monomer.semantic.api.SemanticCheckProblem(cc.monomer.semantic.api.SemanticCheckProblemCode.TIME_SPINE_COLUMN_TYPE_INVALID, null, CONNECTOR_ID, null, null),
            ),
            emptyList(),
        )

        val body = productionCodecClient.get().uri(modelItemPath()).exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val response = objectMapper.readTree(body)
        val dimensions = response["definition"]["dimensions"]
        assertEquals("instant", dimensions[0]["timeKind"].asText(), body)
        assertTrue(dimensions[1]["timeKind"]?.isNull ?: true, body)

        val request = objectMapper.createObjectNode().put("kind", "SEMANTIC_MODEL").put("semanticModelId", MODEL_ID.toString())
        request.set<com.fasterxml.jackson.databind.JsonNode>("definition", response["definition"])
        productionCodecClient.post().uri("/v2/workspaces/$WORKSPACE/semantic/check")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(request.toString()).exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.ok").isEqualTo(false)
            .jsonPath("$.problems[0].code").isEqualTo("TIME_KIND_UNDECLARED")
            .jsonPath("$.problems[0].field").isEqualTo("dimensions[0]")
            .jsonPath("$.problems[0].message").isEqualTo("Declare timeKind for this time dimension.")
            .jsonPath("$.problems[1].message").isEqualTo("A time spine column on this connector must be a date or a timestamp without a time zone.")
        coVerify(exactly = 1) { checks.checkFromInputs(match {
            (it.request.draft as? cc.monomer.semantic.api.SemanticCheckDraft.Model)?.definition == definition
        }) }
    }

    @BeforeEach
    fun authorizeCurrentWorkspace() {
        coEvery { checks.readInputs(any()) } answers {
            cc.monomer.semantic.api.SemanticCheckInputs(firstArg(), emptyList(), emptyList(), emptyMap(), emptyMap(), emptyList(), java.time.ZoneId.of("UTC"))
        }

        coEvery { authorizer.authorizeExplorer(any(), WORKSPACE) } returns Unit
        coEvery { authorizer.authorizeMutation(any(), WORKSPACE, any()) } returns authorization
    }

    @Test
    fun `model collection is a raw current array with workspace identity and source expressions`() {
        coEvery { currentRead.findModels(WORKSPACE) } returns listOf(modelView())

        val body = modelClient.get()
            .uri(modelCollectionPath())
            .header(HttpHeaders.AUTHORIZATION, BEARER)
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
            ?: error("model collection response body was empty")

        val response = objectMapper.readTree(body)
        assertTrue(response.isArray)
        assertEquals(1, response.size())
        assertEquals(MODEL_ID.toString(), response[0].path("semanticModelId").asText())
        assertEquals(WORKSPACE.toString(), response[0].path("workspaceId").asText())
        assertEquals(CREATED_AT.toString(), response[0].path("createdAt").asText())
        assertEquals(UPDATED_AT.toString(), response[0].path("updatedAt").asText())
        assertEquals(3, response[0].path("definition").path("schemaVersion").asInt())
        assertEquals(CONNECTOR_ID.toString(), response[0].path("definition").path("sourceRelations")[0].path("connectorId").asText())
        assertEquals("warehouse", response[0].path("definition").path("sourceRelations")[0].path("catalog").asText())
        assertEquals("analytics", response[0].path("definition").path("sourceRelations")[0].path("schema").asText())
        assertEquals("orders", response[0].path("definition").path("sourceRelations")[0].path("relation").asText())
        assertEquals("order_id", response[0].path("definition").path("entities")[0].path("expression").asText())
        assertEquals("ordered_at", response[0].path("definition").path("dimensions")[0].path("expression").asText())
        assertEquals("amount", response[0].path("definition").path("measures")[0].path("expression").asText())
        assertNoLifecycleIdentity(response[0])
        assertFalse(response[0].has("definitionDigest"))
        assertFalse(response[0].has("publication"))

        coVerify(exactly = 1) { authorizer.authorizeExplorer(any(), WORKSPACE) }
        coVerify(exactly = 1) { currentRead.findModels(WORKSPACE) }
    }

    @Test
    fun `model item is a raw current object and not a versioned resource`() {
        coEvery { currentRead.findModel(WORKSPACE, MODEL_ID) } returns modelView()
        coEvery { currentRead.readModelChange(WORKSPACE, MODEL_ID) } returns cc.monomer.semantic.api.SemanticDefinitionChange(
            Uuid.generateV7(), cc.monomer.semantic.api.SemanticDefinitionChangeAction.UPDATED, false, MODEL_ID.value, "orders", null, CREATED_AT, UPDATED_AT, EDITOR, cc.monomer.identity.api.CredentialSurface.AGENT, cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE,
        )

        val body = modelClient.get()
            .uri(modelItemPath())
            .header(HttpHeaders.AUTHORIZATION, BEARER)
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
            ?: error("model item response body was empty")

        val response = objectMapper.readTree(body)
        assertTrue(response.isObject)
        assertEquals(MODEL_ID.toString(), response.path("semanticModelId").asText())
        assertEquals(WORKSPACE.toString(), response.path("workspaceId").asText())
        assertEquals("orders", response.path("definition").path("name").asText())
        assertEquals(CREATED_AT.toString(), response.path("createdAt").asText())
        assertEquals(UPDATED_AT.toString(), response.path("updatedAt").asText())
        assertEquals(EDITOR.toString(), response.path("updatedBy").path("id").asText())
        assertEquals("Sam Kim", response.path("updatedBy").path("displayName").asText())
        assertEquals("AGENT", response.path("credentialSurface").asText())
        assertEquals("Claude", response.path("agentApp").path("name").asText())
        assertEquals("claude.ai", response.path("agentApp").path("verifiedHost").asText())
        assertNoLifecycleIdentity(response)

        coVerify(exactly = 1) { currentRead.findModel(WORKSPACE, MODEL_ID) }
    }

    @Test
    fun `anonymous model read and create return 401 before current or semantic authority`() {
        coEvery { authorizer.authorizeExplorer(any(), WORKSPACE) } throws
            ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bearer credential is required.")
        coEvery { authorizer.authorizeMutation(any(), WORKSPACE, any()) } throws
            ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bearer credential is required.")

        modelClient.get()
            .uri(modelItemPath())
            .exchange()
            .expectStatus().isUnauthorized

        modelClient.post()
            .uri(modelCollectionPath())
            .header("Idempotency-Key", "anonymous-model-create")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(modelWriteJson())
            .exchange()
            .expectStatus().isUnauthorized

        coVerify(exactly = 1) { authorizer.authorizeExplorer(any(), WORKSPACE) }
        coVerify(exactly = 1) {
            authorizer.authorizeMutation(any(), WORKSPACE, any())
        }
        coVerify(exactly = 0) { currentRead.findModel(WORKSPACE, MODEL_ID) }
        coVerify(exactly = 0) { authority.putModel(any()) }
    }

    @Test
    fun `membership denial rejects model read before current definition lookup`() {
        coEvery { authorizer.authorizeExplorer(any(), WORKSPACE) } throws
            ResponseStatusException(HttpStatus.FORBIDDEN, "Workspace membership is required.")

        modelClient.get()
            .uri(modelItemPath())
            .header(HttpHeaders.AUTHORIZATION, BEARER)
            .exchange()
            .expectStatus().isForbidden

        coVerify(exactly = 1) { authorizer.authorizeExplorer(any(), WORKSPACE) }
        coVerify(exactly = 0) { currentRead.findModel(WORKSPACE, MODEL_ID) }
    }

    @Test
    fun `metric collection returns all five current definition shapes`() {
        coEvery { currentRead.findMetrics(WORKSPACE) } returns metricViews()

        val body = metricClient.get()
            .uri(metricCollectionPath())
            .header(HttpHeaders.AUTHORIZATION, BEARER)
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
            ?: error("metric collection response body was empty")

        val response = objectMapper.readTree(body)
        assertTrue(response.isArray)
        assertEquals(5, response.size())
        assertEquals(listOf("simple", "ratio", "derived", "cumulative", "conversion"), response.map { it.path("definition").path("type").asText() })
        assertEquals("orders_revenue", response[0].path("definition").path("name").asText())
        assertEquals("orders", response[0].path("definition").path("semanticModelName").asText())
        assertEquals("revenue", response[0].path("definition").path("measure").asText())
        assertEquals("orders_revenue", response[1].path("definition").path("numerator").path("metricName").asText())
        assertEquals("orders_count", response[1].path("definition").path("denominator").path("metricName").asText())
        assertEquals("revenue / orders", response[2].path("definition").path("expression").asText())
        assertEquals(2, response[2].path("definition").path("inputMetrics").size())
        assertEquals("7 days", response[3].path("definition").path("window").asText())
        assertEquals("last", response[3].path("definition").path("periodAggregation").asText())
        assertEquals("order_id", response[4].path("definition").path("entity").asText())
        assertEquals("conversion_rate", response[4].path("definition").path("calculation").asText())
        assertEquals("orders", response[4].path("definition").path("baseMetric").path("metricName").asText())
        assertEquals("orders_converted", response[4].path("definition").path("conversionMetric").path("metricName").asText())
        response.forEach { metric ->
            assertEquals(WORKSPACE.toString(), metric.path("workspaceId").asText())
            assertEquals(3, metric.path("definition").path("schemaVersion").asInt())
            assertEquals(CREATED_AT.toString(), metric.path("createdAt").asText())
            assertEquals(UPDATED_AT.toString(), metric.path("updatedAt").asText())
            assertNoLifecycleIdentity(metric)
        }

        coVerify(exactly = 1) { authorizer.authorizeExplorer(any(), WORKSPACE) }
        coVerify(exactly = 1) { currentRead.findMetrics(WORKSPACE) }
    }

    @Test
    fun `metric item is a raw current object`() {
        coEvery { currentRead.findMetric(WORKSPACE, SIMPLE_METRIC_ID) } returns metricViews().first()
        coEvery { currentRead.readMetricChange(WORKSPACE, SIMPLE_METRIC_ID) } returns cc.monomer.semantic.api.SemanticDefinitionChange(
            Uuid.generateV7(), cc.monomer.semantic.api.SemanticDefinitionChangeAction.UPDATED, false, SIMPLE_METRIC_ID.value, "simple", null, CREATED_AT, UPDATED_AT, null, null, null,
        )

        val body = metricClient.get()
            .uri(metricItemPath(SIMPLE_METRIC_ID))
            .header(HttpHeaders.AUTHORIZATION, BEARER)
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
            ?: error("metric item response body was empty")

        val response = objectMapper.readTree(body)
        assertTrue(response.isObject)
        assertEquals(SIMPLE_METRIC_ID.toString(), response.path("metricId").asText())
        assertEquals(WORKSPACE.toString(), response.path("workspaceId").asText())
        assertEquals("simple", response.path("definition").path("type").asText())
        assertEquals(CREATED_AT.toString(), response.path("createdAt").asText())
        assertEquals(UPDATED_AT.toString(), response.path("updatedAt").asText())
        assertTrue(response.path("updatedBy").isNull)
        assertTrue(response.path("credentialSurface").isNull)
        assertNoLifecycleIdentity(response)

        coVerify(exactly = 1) { currentRead.findMetric(WORKSPACE, SIMPLE_METRIC_ID) }
    }

    @Test
    fun `model write carries requested aggregate metric fields and returns ordered new metrics`() {
        val result = cc.monomer.semantic.api.SemanticModelWriteResult(modelView(), listOf(metricViews().first()))
        coEvery { authority.putModel(any()) } returns SemanticMutationResult.Committed(result)
        val body = modelWriteJson().replace("\"aggregateMetrics\": []", "\"aggregateMetrics\": [{\"measure\":\"revenue\",\"name\":\"orders_revenue\",\"label\":\"Revenue\",\"timeGranularity\":\"month\"}]")
        modelClient.post().uri(modelCollectionPath()).header(HttpHeaders.AUTHORIZATION, BEARER)
            .header("Idempotency-Key", "atomic-create").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
            .expectStatus().isCreated.expectBody()
            .jsonPath("$.model.definition.name").isEqualTo("orders")
            .jsonPath("$.aggregateMetrics[0].definition.name").isEqualTo("orders_revenue")
        coVerify { authority.putModel(match { it.aggregateMetrics.single().let { metric -> metric.name.value == "orders_revenue" && metric.measure.value == "revenue" && metric.timeGranularity == cc.monomer.semantic.api.SemanticTimeGranularity.MONTH } }) }
    }

    @Test
    fun `aggregate metric collision and malformed descriptor identify the exact indexed field`() {
        coEvery { authority.putModel(any()) } returns SemanticMutationResult.AggregateMetricNameConflict("aggregateMetrics[1].name")
        val body = modelWriteJson().replace("\"aggregateMetrics\": []", "\"aggregateMetrics\": [{\"measure\":\"revenue\",\"name\":\"first\"},{\"measure\":\"revenue\",\"name\":\"second\"}]")
        modelClient.put().uri("${modelCollectionPath()}/$MODEL_ID").header(HttpHeaders.AUTHORIZATION, BEARER)
            .header("Idempotency-Key", "atomic-collision").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
            .expectStatus().isEqualTo(409).expectBody()
            .jsonPath("$.code").isEqualTo("SEMANTIC_NAME_CONFLICT")
            .jsonPath("$.violations[0].field").isEqualTo("aggregateMetrics[1].name")
        modelClient.post().uri(modelCollectionPath()).header(HttpHeaders.AUTHORIZATION, BEARER)
            .header("Idempotency-Key", "atomic-invalid").contentType(MediaType.APPLICATION_JSON).bodyValue(body.replace("\"name\":\"second\"", "\"name\":\"Bad Name\"")).exchange()
            .expectStatus().isBadRequest.expectBody()
            .jsonPath("$.code").isEqualTo("SEMANTIC_DEFINITION_INVALID")
            .jsonPath("$.violations[0].field").isEqualTo("aggregateMetrics[1].name")
        coVerify(exactly = 1) { authority.putModel(any()) }
    }

    @Test
    fun `model create uses definition envelope idempotency key and returns 201`() {
        coEvery { authority.putModel(any()) } returns SemanticMutationResult.Committed(cc.monomer.semantic.api.SemanticModelWriteResult(modelView(), emptyList()))

        val body = modelClient.post()
            .uri(modelCollectionPath())
            .header(HttpHeaders.AUTHORIZATION, BEARER)
            .header("Idempotency-Key", "model-create-1")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(modelWriteJson())
            .exchange()
            .expectStatus().isCreated
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
            ?: error("model create response body was empty")

        val response = objectMapper.readTree(body).path("model")
        assertEquals(MODEL_ID.toString(), response.path("semanticModelId").asText())
        assertEquals("orders", response.path("definition").path("name").asText())
        assertEquals(3, response.path("definition").path("schemaVersion").asInt())
        assertNoLifecycleIdentity(response)
        coVerify(exactly = 1) {
            authority.putModel(match { command ->
                command.idempotencyKey == "model-create-1" &&
                    command.semanticModelId == null &&
                    command.definition.name.value == "orders"
            })
        }
        coVerify(exactly = 1) {
            authorizer.authorizeMutation(any(), WORKSPACE, cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_CREATE)
        }
    }

    @Test
    fun `semantic edit capability denial rejects model and metric create before semantic authority`() {
        coEvery {
            authorizer.authorizeMutation(any(), WORKSPACE, any())
        } throws ResponseStatusException(HttpStatus.FORBIDDEN, "semantic capability is required")

        modelClient.post()
            .uri(modelCollectionPath())
            .header(HttpHeaders.AUTHORIZATION, BEARER)
            .header("Idempotency-Key", "denied-model-create")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(modelWriteJson())
            .exchange()
            .expectStatus().isForbidden

        metricClient.post()
            .uri(metricCollectionPath())
            .header(HttpHeaders.AUTHORIZATION, BEARER)
            .header("Idempotency-Key", "denied-metric-create")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(simpleMetricWriteJson())
            .exchange()
            .expectStatus().isForbidden

        coVerify(exactly = 2) {
            authorizer.authorizeMutation(any(), WORKSPACE, any())
        }
        coVerify(exactly = 0) { authority.putModel(any()) }
        coVerify(exactly = 0) { authority.putMetric(any()) }
    }

    @Test
    fun `model update uses path identity and returns 200`() {
        coEvery { authority.putModel(any()) } returns SemanticMutationResult.Committed(cc.monomer.semantic.api.SemanticModelWriteResult(modelView(), emptyList()))

        modelClient.put()
            .uri(modelItemPath())
            .header(HttpHeaders.AUTHORIZATION, BEARER)
            .header("Idempotency-Key", "model-update-1")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(modelWriteJson())
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.model.semanticModelId").isEqualTo(MODEL_ID.toString())
            .jsonPath("$.model.workspaceId").isEqualTo(WORKSPACE.toString())
            .jsonPath("$.model.definition.name").isEqualTo("orders")

        coVerify(exactly = 1) {
            authority.putModel(match { command ->
                command.idempotencyKey == "model-update-1" && command.semanticModelId == MODEL_ID
            })
        }
    }

    @Test
    fun `model stale editor save is denied before semantic authority`() {
        coEvery {
            authorizer.authorizeMutation(any(), WORKSPACE, cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_UPDATE)
        } throws ResponseStatusException(HttpStatus.FORBIDDEN, "semantic capability revoked")

        modelClient.put()
            .uri(modelItemPath())
            .header(HttpHeaders.AUTHORIZATION, BEARER)
            .header("Idempotency-Key", "model-stale-editor-save")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(modelWriteJson())
            .exchange()
            .expectStatus().isForbidden

        coVerify(exactly = 1) {
            authorizer.authorizeMutation(any(), WORKSPACE, cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_UPDATE)
        }
        coVerify(exactly = 0) { authority.putModel(any()) }
    }

    @Test
    fun `model wire preserves all entity kinds and valid names`() {
        val definition = fourEntityKindsDefinition()
        coEvery { authority.putModel(any()) } returns SemanticMutationResult.Committed(
            cc.monomer.semantic.api.SemanticModelWriteResult(modelView().copy(definition = definition), emptyList()),
        )

        val body = modelClient.post()
            .uri(modelCollectionPath())
            .header(HttpHeaders.AUTHORIZATION, BEARER)
            .header("Idempotency-Key", "model-entity-kinds")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(fourEntityKindsWriteJson())
            .exchange()
            .expectStatus().isCreated
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
            ?: error("model entity kinds response body was empty")

        val response = objectMapper.readTree(body).path("model").path("definition").path("entities")
        assertEquals(
            listOf("primary", "unique", "foreign", "natural"),
            response.map { it.path("type").asText() },
        )
        assertEquals(
            listOf("order_id", "customer_id", "order_ref", "natural_order"),
            response.map { it.path("name").asText() },
        )
        coVerify(exactly = 1) {
            authority.putModel(match { command ->
                command.definition.entities.map { it.type } == listOf(
                    SemanticEntityKind.PRIMARY,
                    SemanticEntityKind.UNIQUE,
                    SemanticEntityKind.FOREIGN,
                    SemanticEntityKind.NATURAL,
                ) && command.definition.entities.map { it.name.value } == listOf(
                    "order_id",
                    "customer_id",
                    "order_ref",
                    "natural_order",
                )
            })
        }
    }

    @Test
    fun `model wire rejects closed entity validation cases with bad request`() {
        val invalidBodies = buildList {
            add(modelWriteJson().replace("\"type\": \"primary\"", "\"type\": \"unknown\""))
            add(modelWriteJson().replace("\"type\": \"primary\"", "\"type\": \"unique\""))
            add(
                modelWriteJson().replace(
                    "\"entities\": [{\"name\": \"order_id\", \"type\": \"primary\", \"expression\": \"order_id\"}]",
                    "\"entities\": [{\"name\": \"order_id\", \"type\": \"primary\", \"expression\": \"order_id\"}, {\"name\": \"customer_id\", \"type\": \"primary\", \"expression\": \"customer_id\"}]",
                ),
            )

            val invalidNames = listOf("Orders", "has space", "has-hyphen", "", "\\u0000")
            listOf(
                "\"name\": \"order_id\"",
                "\"name\": \"order_date\"",
                "\"name\": \"revenue\"",
            ).forEach { nameField ->
                invalidNames.forEach { invalidName ->
                    add(modelWriteJson().replace(nameField, "\"name\": \"$invalidName\""))
                }
            }

            val invalidExpressions = listOf("", " ", "\\u0000")
            listOf(
                "\"expression\": \"order_id\"",
                "\"expression\": \"ordered_at\"",
                "\"expression\": \"amount\"",
            ).forEach { expressionField ->
                invalidExpressions.forEach { invalidExpression ->
                    add(modelWriteJson().replace(expressionField, "\"expression\": \"$invalidExpression\""))
                }
            }

            add(modelWriteJson().replace("\"name\": \"order_id\"", "\"name\": \"order_date\""))
            add(modelWriteJson().replace("\"name\": \"order_id\"", "\"name\": \"revenue\""))
            add(modelWriteJson().replace("\"name\": \"order_date\"", "\"name\": \"revenue\""))
        }

        invalidBodies.forEachIndexed { index, body ->
            modelClient.post()
                .uri(modelCollectionPath())
                .header(HttpHeaders.AUTHORIZATION, BEARER)
                .header("Idempotency-Key", "model-invalid-$index")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest
        }
        coVerify(exactly = 0) { authority.putModel(any()) }
    }

    @Test
    fun `metric create uses definition envelope idempotency key and returns 201`() {
        coEvery { authority.putMetric(any()) } returns SemanticMutationResult.Committed(metricViews().first())

        metricClient.post()
            .uri(metricCollectionPath())
            .header(HttpHeaders.AUTHORIZATION, BEARER)
            .header("Idempotency-Key", "metric-create-1")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(simpleMetricWriteJson())
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.metricId").isEqualTo(SIMPLE_METRIC_ID.toString())
            .jsonPath("$.workspaceId").isEqualTo(WORKSPACE.toString())
            .jsonPath("$.definition.type").isEqualTo("simple")
            .jsonPath("$.definition.semanticModelName").isEqualTo("orders")
            .jsonPath("$.definition.measure").isEqualTo("revenue")
            .jsonPath("$.createdAt").isEqualTo(CREATED_AT.toString())
            .jsonPath("$.updatedAt").isEqualTo(UPDATED_AT.toString())
            .jsonPath("$.version").doesNotExist()
            .jsonPath("$.definition.version").doesNotExist()
            .jsonPath("$.definition.publication").doesNotExist()
            .jsonPath("$.definition.definitionDigest").doesNotExist()

        coVerify(exactly = 1) {
            authority.putMetric(match { command ->
                command.idempotencyKey == "metric-create-1" &&
                    command.metricId == null &&
                    command.definition.name.value == "orders_revenue"
            })
        }
        coVerify(exactly = 1) {
            authorizer.authorizeMutation(any(), WORKSPACE, cc.monomer.identity.api.MutationIntent.METRIC_CREATE)
        }
    }

    @Test
    fun `metric update uses path identity and returns 200`() {
        coEvery { authority.putMetric(any()) } returns SemanticMutationResult.Committed(metricViews().first())

        metricClient.put()
            .uri(metricItemPath(SIMPLE_METRIC_ID))
            .header(HttpHeaders.AUTHORIZATION, BEARER)
            .header("Idempotency-Key", "metric-update-1")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(simpleMetricWriteJson())
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.metricId").isEqualTo(SIMPLE_METRIC_ID.toString())
            .jsonPath("$.workspaceId").isEqualTo(WORKSPACE.toString())
            .jsonPath("$.definition.type").isEqualTo("simple")

        coVerify(exactly = 1) {
            authority.putMetric(match { command ->
                command.idempotencyKey == "metric-update-1" && command.metricId == SIMPLE_METRIC_ID
            })
        }
    }

    @Test
    fun `metric stale editor save is denied before semantic authority`() {
        coEvery {
            authorizer.authorizeMutation(any(), WORKSPACE, cc.monomer.identity.api.MutationIntent.METRIC_UPDATE)
        } throws ResponseStatusException(HttpStatus.FORBIDDEN, "semantic capability revoked")

        metricClient.put()
            .uri(metricItemPath(SIMPLE_METRIC_ID))
            .header(HttpHeaders.AUTHORIZATION, BEARER)
            .header("Idempotency-Key", "metric-stale-editor-save")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(simpleMetricWriteJson())
            .exchange()
            .expectStatus().isForbidden

        coVerify(exactly = 1) {
            authorizer.authorizeMutation(any(), WORKSPACE, cc.monomer.identity.api.MutationIntent.METRIC_UPDATE)
        }
        coVerify(exactly = 0) { authority.putMetric(any()) }
    }

    @Test
    fun `metric wire rejects irrelevant type fields and unknown lifecycle fields`() {
        val simpleWithRatioFields = """
            {
              "definition": {
                "schemaVersion": 3,
                "name": "orders_revenue",
                "type": "simple",
                "semanticModelName": "orders",
                "measure": "revenue",
                "numerator": {"metricName": "orders_revenue"}
              }
            }
        """.trimIndent()
        metricClient.post()
            .uri(metricCollectionPath())
            .header(HttpHeaders.AUTHORIZATION, BEARER)
            .header("Idempotency-Key", "metric-invalid-shape")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(simpleWithRatioFields)
            .exchange()
            .expectStatus().isBadRequest

        val withUnknownField = simpleMetricWriteJson().replace(
            "\"measure\": \"revenue\"",
            "\"measure\": \"revenue\", \"publication\": \"active\"",
        )
        metricClient.post()
            .uri(metricCollectionPath())
            .header(HttpHeaders.AUTHORIZATION, BEARER)
            .header("Idempotency-Key", "metric-unknown-field")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(withUnknownField)
            .exchange()
            .expectStatus().isBadRequest

        coVerify(exactly = 0) { authority.putMetric(any()) }
    }

    @Test
    fun `metric wire rejects unknown conversion constant property fields with a lenient decoder`() {
        val conversionWithUnknownConstantProperty = """
            {
              "definition": {
                "schemaVersion": 3,
                "name": "order_conversion_rate",
                "type": "conversion",
                "entity": "order_id",
                "calculation": "conversion_rate",
                "baseMetric": {"metricName": "orders"},
                "conversionMetric": {"metricName": "orders_converted"},
                "constantProperties": [{
                  "baseProperty": "channel",
                  "conversionProperty": "channel",
                  "operator": null,
                  "values": ["web"]
                }]
              }
            }
        """.trimIndent()

        listOf(
            conversionWithUnknownConstantProperty,
            conversionWithUnknownConstantProperty.replace("\"operator\": null", "\"operator\": \"eq\""),
        ).forEach { body ->
            lenientMetricClient.post()
                .uri(metricCollectionPath())
                .header(HttpHeaders.AUTHORIZATION, BEARER)
                .header("Idempotency-Key", "metric-unknown-constant-property-field")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest
        }

        coVerify(exactly = 0) { authorizer.authorizeMutation(any(), WORKSPACE, any()) }
        coVerify(exactly = 0) { authority.putMetric(any()) }
    }

    @Test
    fun `removed publication digest and dependency routes are not exposed`() {
        modelClient.get()
            .uri("/v2/workspaces/$WORKSPACE_ID/semantic/semantic-models/$MODEL_ID/dependencies")
            .exchange()
            .expectStatus().isNotFound
        metricClient.get()
            .uri("/v2/workspaces/$WORKSPACE_ID/semantic/metrics/$SIMPLE_METRIC_ID/dependencies")
            .exchange()
            .expectStatus().isNotFound
        modelClient.get()
            .uri("/v2/workspaces/$WORKSPACE_ID/semantic/publication-candidates/$MODEL_ID")
            .exchange()
            .expectStatus().isNotFound
        metricClient.get()
            .uri("/v2/workspaces/$WORKSPACE_ID/semantic/metrics/$SIMPLE_METRIC_ID/digest")
            .exchange()
            .expectStatus().isNotFound

        coVerify(exactly = 0) { authorizer.authorizeExplorer(any(), WORKSPACE) }
        coVerify(exactly = 0) { currentRead.findModel(WORKSPACE, MODEL_ID) }
        coVerify(exactly = 0) { currentRead.findMetric(WORKSPACE, SIMPLE_METRIC_ID) }
    }

    @Test
    fun `delete routes use scoped Editor mutation and return empty 204 or owner 404`() {
        coEvery { authority.deleteModel(any()) } returns SemanticMutationResult.Committed(Unit)
        coEvery { authority.deleteMetric(any()) } returns SemanticMutationResult.Committed(Unit)
        for ((client, uri) in listOf(modelClient to modelItemPath(), metricClient to metricItemPath(SIMPLE_METRIC_ID))) {
            client.delete().uri(uri).header(HttpHeaders.AUTHORIZATION, BEARER).exchange()
                .expectStatus().isNoContent.expectBody().isEmpty
        }
        coVerify { authorizer.authorizeMutation(any(), WORKSPACE, cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_DELETE) }
        coVerify { authorizer.authorizeMutation(any(), WORKSPACE, cc.monomer.identity.api.MutationIntent.METRIC_DELETE) }
        coVerify { authority.deleteModel(match { it.semanticModelId == MODEL_ID && it.authorization === authorization }) }
        coVerify { authority.deleteMetric(match { it.metricId == SIMPLE_METRIC_ID && it.authorization === authorization }) }
        coEvery { authority.deleteModel(any()) } returns SemanticMutationResult.NotFoundOrOutsideWorkspace
        coEvery { authority.deleteMetric(any()) } returns SemanticMutationResult.NotFoundOrOutsideWorkspace
        modelClient.delete().uri(modelItemPath()).exchange().expectStatus().isNotFound
        metricClient.delete().uri(metricItemPath(SIMPLE_METRIC_ID)).exchange().expectStatus().isNotFound
    }

    @Test
    fun `Explorer delete denial never reaches owner`() {
        coEvery { authorizer.authorizeMutation(any(), WORKSPACE, cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_DELETE) } throws ResponseStatusException(HttpStatus.FORBIDDEN)
        coEvery { authorizer.authorizeMutation(any(), WORKSPACE, cc.monomer.identity.api.MutationIntent.METRIC_DELETE) } throws ResponseStatusException(HttpStatus.FORBIDDEN)
        modelClient.delete().uri(modelItemPath()).exchange().expectStatus().isForbidden
        metricClient.delete().uri(metricItemPath(SIMPLE_METRIC_ID)).exchange().expectStatus().isForbidden
        coVerify(exactly=0) { authority.deleteModel(any()) }
        coVerify(exactly=0) { authority.deleteMetric(any()) }
    }

    @Test
    fun `history reads original deleted IDs with complete definition and nullable attribution`() {
        val version = cc.monomer.semantic.api.SemanticDefinitionVersion(
            Uuid.generateV7(), cc.monomer.semantic.api.SemanticDefinitionChangeAction.DELETED,
            cc.monomer.semantic.api.SemanticDefinitionSnapshot.Model(modelDefinition()), UPDATED_AT, null, null, null,
            true,
        )
        val boundary = cc.monomer.semantic.api.SemanticHistoryBoundary(UPDATED_AT, version.changeId)
        coEvery { currentRead.readModelHistory(WORKSPACE, MODEL_ID, null, 20) } returns
            cc.monomer.semantic.api.SemanticDefinitionHistory(MODEL_ID.value, "orders", true, listOf(version), boundary)
        val body = modelClient.get().uri("${modelItemPath()}/history").exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val response = objectMapper.readTree(body)
        assertEquals(MODEL_ID.toString(), response["definitionId"].asText())
        assertTrue(response["deleted"].asBoolean())
        assertEquals("DELETED", response["changes"][0]["action"].asText())
        assertTrue(response["changes"][0]["restorable"].asBoolean())
        assertEquals("orders", response["changes"][0]["definition"]["name"].asText())
        assertTrue(response["changes"][0]["actor"].isNull)
        assertTrue(response["changes"][0]["credentialSurface"].isNull)
        assertTrue(response["changes"][0]["agentApp"].isNull)
        val cursor = response["nextCursor"].asText()
        coEvery { currentRead.readModelHistory(WORKSPACE, MODEL_ID, boundary, 20) } returns
            cc.monomer.semantic.api.SemanticDefinitionHistory(MODEL_ID.value, "orders", true, emptyList(), null)
        modelClient.get().uri("${modelItemPath()}/history?cursor=$cursor").exchange().expectStatus().isOk
        metricClient.get().uri("${metricItemPath(SIMPLE_METRIC_ID)}/history?cursor=$cursor").exchange().expectStatus().isBadRequest
        modelClient.get().uri("${modelItemPath()}/history?limit=0").exchange().expectStatus().isBadRequest
        modelClient.get().uri("${modelItemPath()}/history?cursor=broken").exchange().expectStatus().isBadRequest
        coVerify(exactly=0) { currentRead.findModel(WORKSPACE, MODEL_ID) }
        coVerify(exactly=0) { currentRead.readMetricHistory(WORKSPACE, SIMPLE_METRIC_ID, any(), any()) }
    }

    @Test
    fun `history exposes owner restore availability for Model and Metric live and deleted versions`() {
        for (model in listOf(true, false)) {
            val id = if (model) MODEL_ID.value else SIMPLE_METRIC_ID.value
            val snapshot = if (model) cc.monomer.semantic.api.SemanticDefinitionSnapshot.Model(modelDefinition())
                else cc.monomer.semantic.api.SemanticDefinitionSnapshot.Metric(simpleMetric())
            val olderSnapshot = when (snapshot) {
                is cc.monomer.semantic.api.SemanticDefinitionSnapshot.Model -> snapshot.copy(definition = snapshot.definition.copy(description = "Before"))
                is cc.monomer.semantic.api.SemanticDefinitionSnapshot.Metric -> snapshot.copy(definition = (snapshot.definition as SemanticMetricDefinition.Simple).copy(description = "Before"))
            }
            for (deleted in listOf(false, true)) {
                val latest = cc.monomer.semantic.api.SemanticDefinitionVersion(
                    Uuid.generateV7(), if (deleted) cc.monomer.semantic.api.SemanticDefinitionChangeAction.DELETED else cc.monomer.semantic.api.SemanticDefinitionChangeAction.UPDATED,
                    snapshot, UPDATED_AT, null, null, null, deleted,
                )
                val same = latest.copy(changeId = Uuid.generateV7(), action = cc.monomer.semantic.api.SemanticDefinitionChangeAction.UPDATED, restorable = false)
                val older = same.copy(changeId = Uuid.generateV7(), definition = olderSnapshot, restorable = true)
                val history = cc.monomer.semantic.api.SemanticDefinitionHistory(id, if (model) "orders" else "revenue", deleted, listOf(latest, same, older), null)
                if (model) coEvery { currentRead.readModelHistory(WORKSPACE, MODEL_ID, null, 20) } returns history
                else coEvery { currentRead.readMetricHistory(WORKSPACE, SIMPLE_METRIC_ID, null, 20) } returns history
                val client = if (model) modelClient else metricClient
                val uri = if (model) modelItemPath() else metricItemPath(SIMPLE_METRIC_ID)
                val body = client.get().uri("$uri/history").exchange().expectStatus().isOk
                    .expectBody(String::class.java).returnResult().responseBody!!
                val changes = objectMapper.readTree(body)["changes"]
                assertEquals(listOf(deleted, false, true), changes.map { it["restorable"].asBoolean() })
            }
        }
    }

    @Test
    fun `restore is bodyless idempotent save with original IDs and name conflict`() {
        val change = Uuid.generateV7()
        coEvery { authority.restoreModel(any()) } returns SemanticMutationResult.Committed(modelView())
        coEvery { authority.restoreMetric(any()) } returns SemanticMutationResult.Committed(metricViews().first())
        modelClient.post().uri("${modelItemPath()}/history/$change/restore").header("Idempotency-Key", "restore-model")
            .exchange().expectStatus().isOk.expectBody().jsonPath("$.semanticModelId").isEqualTo(MODEL_ID.toString())
        metricClient.post().uri("${metricItemPath(SIMPLE_METRIC_ID)}/history/$change/restore").header("Idempotency-Key", "restore-metric")
            .exchange().expectStatus().isOk.expectBody().jsonPath("$.metricId").isEqualTo(SIMPLE_METRIC_ID.toString())
        coVerify { authority.restoreModel(match { it.semanticModelId == MODEL_ID && it.changeId == change && it.idempotencyKey == "restore-model" }) }
        coVerify { authority.restoreMetric(match { it.metricId == SIMPLE_METRIC_ID && it.changeId == change && it.idempotencyKey == "restore-metric" }) }
        coEvery { authority.restoreModel(any()) } returns SemanticMutationResult.Conflict
        modelClient.post().uri("${modelItemPath()}/history/$change/restore").header("Idempotency-Key", "restore-conflict")
            .exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("SEMANTIC_NAME_CONFLICT")
        modelClient.post().uri("${modelItemPath()}/history/$change/restore").exchange().expectStatus().isBadRequest
    }

    private fun modelView(): SemanticModelView =
        SemanticModelView(
            semanticModelId = MODEL_ID,
            workspaceId = WORKSPACE,
            definition = modelDefinition(),
            createdAt = CREATED_AT,
            updatedAt = UPDATED_AT,
            sourcePartitionInfo = emptyList(),
        )

    private fun metricViews(): List<MetricView> =
        listOf(
            MetricView(SIMPLE_METRIC_ID, WORKSPACE, simpleMetric(), CREATED_AT, UPDATED_AT,
                emptyList(),
            ),
            MetricView(RATIO_METRIC_ID, WORKSPACE, ratioMetric(), CREATED_AT, UPDATED_AT,
                emptyList(),
            ),
            MetricView(DERIVED_METRIC_ID, WORKSPACE, derivedMetric(), CREATED_AT, UPDATED_AT,
                emptyList(),
            ),
            MetricView(CUMULATIVE_METRIC_ID, WORKSPACE, cumulativeMetric(), CREATED_AT, UPDATED_AT,
                emptyList(),
            ),
            MetricView(CONVERSION_METRIC_ID, WORKSPACE, conversionMetric(), CREATED_AT, UPDATED_AT,
                emptyList(),
            ),
        )

    private fun modelDefinition(): SemanticModelDefinition =
        SemanticModelDefinition(
            schemaVersion = 3u,
            name = SemanticElementName("orders"),
            label = "Orders",
            description = "Orders semantic model",
            sourceRelations = listOf(
                SemanticSourceRelation(CONNECTOR_ID, "warehouse", "analytics", "orders"),
            ),
            aggregationTimeDimension = SemanticElementName("order_date"),
            entities = listOf(
                SemanticEntityDefinition(
                    name = SemanticElementName("order_id"),
                    type = SemanticEntityKind.PRIMARY,
                    expression = "order_id",
                    label = "Order ID",
                    description = "Stable order identifier",
                ),
            ),
            dimensions = listOf(
                SemanticDimensionDefinition(
                    name = SemanticElementName("order_date"),
                    type = SemanticDimensionKind.TIME,
                    expression = "ordered_at",
                    granularity = SemanticTimeGranularity.DAY,
                    label = "Order date",
                    description = "Order creation date",
                    isPartition = false,
                    timeKind = null,
                ),
                SemanticDimensionDefinition(
                    name = SemanticElementName("status"),
                    type = SemanticDimensionKind.CATEGORICAL,
                    expression = "status",
                    granularity = null,
                    label = "Status",
                    description = "Current order status",
                    isPartition = false,
                    timeKind = null,
                ),
            ),
            measures = listOf(
                SemanticMeasureDefinition(
                    name = SemanticElementName("revenue"),
                    aggregation = SemanticMeasureAggregation.SUM,
                    expression = "amount",
                    aggregationTimeDimension = SemanticElementName("order_date"),
                    nonAdditiveDimension = null,
                    label = "Revenue",
                    description = "Order amount",
                ),
            ),
        )

    private fun fourEntityKindsDefinition(): SemanticModelDefinition {
        val base = modelDefinition()
        return base.copy(
            entities = listOf(
                base.entities.single(),
                SemanticEntityDefinition(
                    name = SemanticElementName("customer_id"),
                    type = SemanticEntityKind.UNIQUE,
                    expression = "customer_id",
                    label = null,
                    description = null,
                ),
                SemanticEntityDefinition(
                    name = SemanticElementName("order_ref"),
                    type = SemanticEntityKind.FOREIGN,
                    expression = "order_ref",
                    label = null,
                    description = null,
                ),
                SemanticEntityDefinition(
                    name = SemanticElementName("natural_order"),
                    type = SemanticEntityKind.NATURAL,
                    expression = "natural_order",
                    label = null,
                    description = null,
                ),
            ),
        )
    }

    private fun simpleMetric(): SemanticMetricDefinition.Simple =
        SemanticMetricDefinition.Simple(
            schemaVersion = 3u,
            name = SemanticElementName("orders_revenue"),
            label = "Revenue",
            description = "Order revenue",
            timeGranularity = SemanticTimeGranularity.DAY,
            filters = emptyList(),
            semanticModelName = SemanticElementName("orders"),
            measure = SemanticElementName("revenue"),
            display = null,
        )

    private fun ratioMetric(): SemanticMetricDefinition.Ratio =
        SemanticMetricDefinition.Ratio(
            schemaVersion = 3u,
            name = SemanticElementName("revenue_per_order"),
            label = "Revenue per order",
            description = null,
            timeGranularity = SemanticTimeGranularity.MONTH,
            filters = emptyList(),
            numerator = metricInput("orders_revenue"),
            denominator = metricInput("orders_count"),
            display = null,
        )

    private fun derivedMetric(): SemanticMetricDefinition.Derived =
        SemanticMetricDefinition.Derived(
            schemaVersion = 3u,
            name = SemanticElementName("revenue_share"),
            label = null,
            description = "Revenue divided by order count",
            timeGranularity = null,
            filters = emptyList(),
            expression = "revenue / orders",
            inputMetrics = listOf(
                metricInput("orders_revenue", alias = "revenue"),
                metricInput("orders_count", alias = "orders"),
            ),
            display = null,
        )

    private fun cumulativeMetric(): SemanticMetricDefinition.Cumulative =
        SemanticMetricDefinition.Cumulative(
            schemaVersion = 3u,
            name = SemanticElementName("revenue_rolling"),
            label = null,
            description = null,
            timeGranularity = SemanticTimeGranularity.DAY,
            filters = emptyList(),
            inputMetric = metricInput("orders_revenue"),
            window = SemanticMetricWindow(7, SemanticMetricWindowUnit.DAY),
            grainToDate = null,
            periodAggregation = SemanticMetricPeriodAggregation.LAST,
            display = null,
        )

    private fun conversionMetric(): SemanticMetricDefinition.Conversion =
        SemanticMetricDefinition.Conversion(
            schemaVersion = 3u,
            name = SemanticElementName("order_conversion_rate"),
            label = "Order conversion rate",
            description = null,
            timeGranularity = null,
            filters = emptyList(),
            entity = SemanticElementName("order_id"),
            calculation = SemanticConversionCalculation.CONVERSION_RATE,
            baseMetric = metricInput("orders"),
            conversionMetric = metricInput("orders_converted"),
            window = SemanticMetricWindow(30, SemanticMetricWindowUnit.DAY),
            constantProperties = listOf(
                SemanticConstantProperty(
                    baseProperty = SemanticElementName("channel"),
                    conversionProperty = SemanticElementName("channel"),
                ),
            ),
            display = null,
        )

    private fun metricInput(metricName: String, alias: String? = null): SemanticMetricInput =
        SemanticMetricInput(
            metricName = SemanticElementName(metricName),
            filter = null,
            alias = alias?.let(::SemanticElementName),
            offsetWindow = null,
            offsetToGrain = null,
        )

    private fun assertNoLifecycleIdentity(response: com.fasterxml.jackson.databind.JsonNode) {
        listOf("version", "revision", "publication", "digest", "definitionDigest", "candidateVersionId")
            .forEach { field -> assertFalse(response.has(field), "unexpected lifecycle field: $field") }
    }

    private fun modelCollectionPath(): String = "/v2/workspaces/$WORKSPACE_ID/semantic/semantic-models"

    private fun modelItemPath(): String = "${modelCollectionPath()}/$MODEL_ID"

    private fun metricCollectionPath(): String = "/v2/workspaces/$WORKSPACE_ID/semantic/metrics"

    private fun metricItemPath(metricId: MetricId): String = "${metricCollectionPath()}/$metricId"

    private fun modelWriteJson(): String =
        """
        {

          "aggregateMetrics": [],
          "definition": {
            "schemaVersion": 3,
            "name": "orders",
            "sourceRelations": [{"connectorId": "$CONNECTOR_ID", "catalog": "warehouse", "schema": "analytics", "relation": "orders"}],
            "entities": [{"name": "order_id", "type": "primary", "expression": "order_id"}],
            "dimensions": [{"isPartition":false,"name": "order_date", "type": "time", "granularity": "day", "expression": "ordered_at"}],
            "measures": [{"name": "revenue", "aggregation": "sum", "expression": "amount"}]
          }
        }
        """.trimIndent()

    private fun fourEntityKindsWriteJson(): String = modelWriteJson().replace(
        "\"entities\": [{\"name\": \"order_id\", \"type\": \"primary\", \"expression\": \"order_id\"}]",
        "\"entities\": [{\"name\": \"order_id\", \"type\": \"primary\", \"expression\": \"order_id\"}, {\"name\": \"customer_id\", \"type\": \"unique\", \"expression\": \"customer_id\"}, {\"name\": \"order_ref\", \"type\": \"foreign\", \"expression\": \"order_ref\"}, {\"name\": \"natural_order\", \"type\": \"natural\", \"expression\": \"natural_order\"}]",
    )

    private fun simpleMetricWriteJson(): String =
        """
        {
          "definition": {
            "schemaVersion": 3,
            "name": "orders_revenue",
            "type": "simple",
            "semanticModelName": "orders",
            "measure": "revenue"
          }
        }
        """.trimIndent()

    private companion object {
        val WORKSPACE_ID = "019f4a78-afd2-7172-b806-0d9a98a59b94"
        val WORKSPACE = WorkspaceId(Uuid.parse(WORKSPACE_ID))
        val CONNECTOR_ID = cc.monomer.connector.api.ConnectorId(Uuid.parse("019f4a78-afd2-7172-b806-0d9a98a59b95"))
        val MODEL_ID = SemanticModelId(Uuid.parse("019f4a78-afd2-7172-b806-0d9a98a59b96"))
        val SIMPLE_METRIC_ID = MetricId(Uuid.parse("019f4a78-afd2-7172-b806-0d9a98a59b97"))
        val RATIO_METRIC_ID = MetricId(Uuid.parse("019f4a78-afd2-7172-b806-0d9a98a59b98"))
        val DERIVED_METRIC_ID = MetricId(Uuid.parse("019f4a78-afd2-7172-b806-0d9a98a59b99"))
        val CUMULATIVE_METRIC_ID = MetricId(Uuid.parse("019f4a78-afd2-7172-b806-0d9a98a59b9a"))
        val CONVERSION_METRIC_ID = MetricId(Uuid.parse("019f4a78-afd2-7172-b806-0d9a98a59b9b"))
        val CREATED_AT = Instant.parse("2026-08-19T00:00:00Z")
        val UPDATED_AT = Instant.parse("2026-08-19T01:00:00Z")
        val EDITOR = cc.monomer.identity.api.PrincipalId(Uuid.parse("019f4a78-afd2-7172-b806-0d9a98a59ba0"))
        const val BEARER = "Bearer semantic-test-token"

        fun strictObjectMapper(): ObjectMapper =
            ObjectMapper()
                .registerKotlinModule()
                .findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)

    }
}
