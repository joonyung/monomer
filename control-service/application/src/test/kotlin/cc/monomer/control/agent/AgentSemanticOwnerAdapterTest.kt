@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.agent

import cc.monomer.agent.api.AgentActionOutcome
import cc.monomer.agent.api.AgentActionStatus
import cc.monomer.agent.api.AgentCreateInput
import cc.monomer.agent.api.AgentCreateRequest
import cc.monomer.agent.api.AgentEditRequest
import cc.monomer.agent.api.AgentJsonPatchOp
import cc.monomer.agent.api.AgentJsonPatchOperation
import cc.monomer.agent.api.AgentListRequest
import cc.monomer.agent.api.AgentListingOutcome
import cc.monomer.agent.api.AgentReadRequest
import cc.monomer.agent.api.AgentRequestId
import cc.monomer.agent.api.AgentResourceOutcome
import cc.monomer.agent.api.AgentResourcePath
import cc.monomer.agent.api.AgentSemanticModelResourceBody
import cc.monomer.agent.api.AgentWorkspaceAdmissionAudience
import cc.monomer.agent.api.AgentWorkspaceAdmissionDecision
import cc.monomer.agent.api.AgentWorkspaceAdmissionPort
import cc.monomer.agent.api.AgentWorkspaceAdmissionRequest
import cc.monomer.agent.api.AgentWorkspaceCaller
import cc.monomer.agent.api.AgentWorkspaceCallerAuthenticationOutcome
import cc.monomer.agent.api.AgentWorkspaceRoleAdmissionRequest
import cc.monomer.agent.api.AgentWorkspaceRoleEvidence
import cc.monomer.agent.api.requiredRole
import cc.monomer.agent.application.AgentWorkspaceCallerAuthenticator
import cc.monomer.agent.application.AgentWorkspaceContractDecoder
import cc.monomer.identity.api.OrganizationId
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.api.AuthorizationRevision
import cc.monomer.identity.api.CanonicalPrincipal
import cc.monomer.identity.api.CredentialKind
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.PrincipalKind
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.SemanticDefinitionChangeAction
import cc.monomer.semantic.api.SemanticDefinitionHistory
import cc.monomer.semantic.api.SemanticHistoryBoundary
import cc.monomer.semantic.api.SemanticDefinitionVersion
import cc.monomer.semantic.api.SemanticDefinitionSnapshot
import cc.monomer.semantic.api.DeleteSemanticModelCommand
import cc.monomer.semantic.api.DeleteMetricCommand
import cc.monomer.semantic.api.RestoreSemanticModelCommand
import cc.monomer.semantic.api.RestoreMetricCommand
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.MetricView
import cc.monomer.semantic.api.PutMetricCommand
import cc.monomer.semantic.api.PutSemanticModelCommand
import cc.monomer.semantic.api.SemanticAuthorityUseCase
import cc.monomer.semantic.api.SemanticCurrentReadUseCase
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticInvalidReason
import cc.monomer.semantic.api.SemanticInvalidReasonCode
import cc.monomer.semantic.api.SemanticMetricDefinition
import cc.monomer.semantic.api.SemanticModelId
import cc.monomer.semantic.api.SemanticModelPage
import cc.monomer.semantic.api.SemanticModelPageCursor
import cc.monomer.semantic.api.SemanticModelView
import cc.monomer.semantic.api.SemanticMutationResult
import cc.monomer.semantic.api.MetricPage
import cc.monomer.semantic.api.MetricPageCursor
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class AgentSemanticOwnerAdapterTest {
    private val decoder = AgentWorkspaceContractDecoder()
    private val workspaceId = WorkspaceId(Uuid.parse("01942b04-6e00-7000-8000-000000000001"))
    private val actor = CanonicalPrincipal(
        PrincipalId(Uuid.parse("01942b04-6e00-7000-8000-000000000002")),
        PrincipalKind.USER,
    )
    private val credential = PresentedCredential("agent-token")
    private val instant = Instant.parse("2026-09-04T00:00:00Z")

    @Test
    fun `create delegates to Semantic owner with server request identity and returns canonical path`() = runTest {
        val owner = RecordingSemanticOwner()
        val adapter = adapter(owner)
        val request = modelCreate("orders")

        val outcome = adapter.create(capabilityCaller(request), request)

        val action = assertIs<AgentActionOutcome.Completed>(outcome).action
        assertEquals(AgentActionStatus.COMPLETED, action.status)
        assertEquals("workspace/semantic-models/orders", action.result.path.value)
        assertEquals("srv_create", owner.lastModelCommand?.idempotencyKey)
        assertNull(owner.lastModelCommand?.semanticModelId)
    }

    @Test
    fun `model create and edit carry aggregate metric fields outside definition and map indexed owner rejections`() = runTest {
        val aggregate = cc.monomer.agent.api.AgentSemanticAggregateMetric("amount", "total_amount", "Total amount", "Requested with model", cc.monomer.agent.api.AgentTimeGranularity.MONTH, emptyList())
        val originalCreate = modelCreate("orders")
        val create = originalCreate.copy(input = assertIs<AgentCreateInput.SemanticModel>(originalCreate.input).copy(aggregateMetrics = listOf(aggregate)))
        val recording = RecordingSemanticOwner()
        assertIs<AgentActionOutcome.Completed>(adapter(recording).create(capabilityCaller(create), create))
        val command = assertNotNull(recording.lastModelCommand)
        assertEquals("total_amount", command.aggregateMetrics.single().name.value)
        assertEquals(cc.monomer.semantic.api.SemanticTimeGranularity.MONTH, command.aggregateMetrics.single().timeGranularity)
        assertEquals("amount", command.aggregateMetrics.single().measure.value)
        for ((result, field, status) in listOf(
            Triple(SemanticMutationResult.AggregateMetricNameConflict("aggregateMetrics[1].name"), "aggregateMetrics[1].name", 409),
            Triple(SemanticMutationResult.Invalid(listOf(SemanticInvalidReason(SemanticInvalidReasonCode.AGGREGATE_MEASURE_NOT_FOUND, "aggregateMetrics[1].measure"))), "aggregateMetrics[1].measure", 400),
        )) {
            val owner = RecordingSemanticOwner(modelResult = result)
            owner.models += owner.modelView(originalCreate)
            val adapter = adapter(owner)
            val createProblem = assertIs<AgentActionOutcome.Rejected>(adapter.create(capabilityCaller(create), create)).problem
            assertEquals(status, createProblem.status.value)
            assertEquals("input.$field", createProblem.violations.single().field)
            val edit = decoder.edit("""{"path":"workspace/semantic-models/orders","aggregateMetrics":[{"measure":"amount","name":"total_amount"}],"patch":[{"op":"add","path":"/label","value":"Orders"}]}""").value
            val editProblem = assertIs<AgentActionOutcome.Rejected>(adapter.edit(capabilityCaller(edit), edit)).problem
            assertEquals(status, editProblem.status.value)
            assertEquals(field, editProblem.violations.single().field)
            assertEquals("total_amount", owner.lastModelCommand?.aggregateMetrics?.single()?.name?.value)
        }
    }

    @Test
    fun `create maps owner name conflict to public conflict`() = runTest {
        val owner = RecordingSemanticOwner(modelResult = SemanticMutationResult.Conflict)
        val adapter = adapter(owner)
        val request = modelCreate("orders")

        val outcome = adapter.create(capabilityCaller(request), request)

        val problem = assertIs<AgentActionOutcome.Rejected>(outcome).problem
        assertEquals(409, problem.status.value)
        assertEquals("SEMANTIC_NAME_CONFLICT", problem.code)
    }

    @Test
    fun `create preserves owner reason code and scopes known or unknown definition fields`() = runTest {
        listOf("name" to "input.definition.name", null to "input.definition").forEach { (field, expectedField) ->
            val owner = RecordingSemanticOwner(
                modelResult = SemanticMutationResult.Invalid(
                    listOf(SemanticInvalidReason(SemanticInvalidReasonCode.MODEL_NAME_IMMUTABLE, field)),
                ),
            )
            val request = modelCreate("orders")
            val problem = assertIs<AgentActionOutcome.Rejected>(
                adapter(owner).create(capabilityCaller(request), request),
            ).problem

            assertEquals("SEMANTIC_DEFINITION_INVALID", problem.code)
            val violation = problem.violations.single()
            assertEquals("MODEL_NAME_IMMUTABLE", violation.code)
            assertEquals(expectedField, violation.field)
            assertNull(violation.operationIndex)
            assertNull(violation.path)
        }
    }

    @Test
    fun `metric create uses the same Semantic owner and preserves unresolved current name references`() = runTest {
        val owner = RecordingSemanticOwner()
        val adapter = adapter(owner)
        val request = metricCreate("revenue")

        val action = assertIs<AgentActionOutcome.Completed>(
            adapter.create(capabilityCaller(request), request),
        ).action

        assertEquals("workspace/metrics/revenue", action.result.path.value)
        assertEquals("missing_model", (owner.lastMetricCommand?.definition as SemanticMetricDefinition.Simple).semanticModelName.value)
        assertNull(owner.lastMetricCommand?.metricId)
    }

    @Test
    fun `domain invalid model and ratio creates return repairable problems without owner writes`() = runTest {
        for ((collection, kind, definition, message) in invalidDefinitions()) {
            val owner = RecordingSemanticOwner()
            val request = decoder.create(
                """{"path":"$collection","input":{"kind":"$kind",${if (kind == "SEMANTIC_MODEL_DEFINITION") "\"aggregateMetrics\":[]," else ""}"definition":$definition}}""",
            ).value

            val problem = assertIs<AgentActionOutcome.Rejected>(
                adapter(owner).create(capabilityCaller(request), request),
            ).problem

            assertEquals(400, problem.status.value)
            assertEquals("SEMANTIC_DEFINITION_INVALID", problem.code)
            assertFalse(problem.retryable)
            assertEquals(message, problem.detail)
            val violation = problem.violations.single()
            assertEquals("input.definition", violation.field)
            assertEquals(problem.code, violation.code)
            assertEquals(message, violation.message)
            assertNull(violation.operationIndex)
            assertNull(violation.path)
            assertNull(owner.lastModelCommand)
            assertNull(owner.lastMetricCommand)
            assertTrue(owner.models.isEmpty())
            assertTrue(owner.metrics.isEmpty())
        }
    }

    @Test
    fun `domain invalid model and ratio edits preserve current definitions without owner writes`() = runTest {
        for ((collection, _, definition, message) in invalidDefinitions()) {
            val owner = RecordingSemanticOwner()
            val adapter = adapter(owner)
            owner.models += owner.modelView(modelCreate("orders"))
            val metric = metricCreate("revenue")
            assertIs<AgentActionOutcome.Completed>(adapter.create(capabilityCaller(metric), metric))
            owner.lastMetricCommand = null
            val beforeModels = owner.models.toList()
            val beforeMetrics = owner.metrics.toList()
            val name = if (collection.endsWith("metrics")) "revenue" else "orders"
            val request = decoder.edit(
                """{"path":"$collection/$name",${if (collection.endsWith("semantic-models")) "\"aggregateMetrics\":[]," else ""}"patch":[{"op":"replace","path":"","value":$definition}]}""",
            ).value

            val problem = assertIs<AgentActionOutcome.Rejected>(
                adapter.edit(capabilityCaller(request), request),
            ).problem

            assertEquals(400, problem.status.value)
            assertEquals("SEMANTIC_DEFINITION_INVALID", problem.code)
            assertFalse(problem.retryable)
            assertEquals(message, problem.detail)
            val violation = problem.violations.single()
            assertEquals("definition", violation.field)
            assertEquals(problem.code, violation.code)
            assertEquals(message, violation.message)
            assertNull(violation.operationIndex)
            assertNull(violation.path)
            assertNull(owner.lastModelCommand)
            assertNull(owner.lastMetricCommand)
            assertEquals(beforeModels, owner.models)
            assertEquals(beforeMetrics, owner.metrics)
        }
    }

    @Test
    fun `owner conflicts use the shared Agent conflict codes for create and edit`() = runTest {
        for ((result, code) in listOf(
            SemanticMutationResult.IdempotencyConflict to "IDEMPOTENCY_CONFLICT",
            SemanticMutationResult.Conflict to "SEMANTIC_NAME_CONFLICT",
        )) {
            val owner = RecordingSemanticOwner(result)
            val adapter = adapter(owner)
            val create = modelCreate("orders")
            owner.models += owner.modelView(create)
            val edit = decoder.edit(
                """{"path":"workspace/semantic-models/orders","aggregateMetrics":[],"patch":[{"op":"add","path":"/label","value":"Updated"}]}""",
            ).value

            for (outcome in listOf(
                adapter.create(capabilityCaller(create), create),
                adapter.edit(capabilityCaller(edit), edit),
            )) {
                val problem = assertIs<AgentActionOutcome.Rejected>(outcome).problem
                assertEquals(409, problem.status.value)
                assertEquals(code, problem.code)
                assertFalse(problem.retryable)
            }
        }
    }

    @Test
    fun `unconfirmed creates require readback while edits retain retryable owner failures`() = runTest {
        for (create in listOf(modelCreate("orders"), metricCreate("revenue"))) {
            val owner = RecordingSemanticOwner()
            val adapter = adapter(owner)
            assertIs<AgentActionOutcome.Completed>(adapter.create(capabilityCaller(create), create))
            val name = if (create.path.value.endsWith("metrics")) "revenue" else "orders"
            val edit = decoder.edit(
                """{"path":"${create.path.value}/$name",${if (create.path.value.endsWith("semantic-models")) "\"aggregateMetrics\":[]," else ""}"patch":[{"op":"add","path":"/label","value":"Updated"}]}""",
            ).value
            val beforeModels = owner.models.toList()
            val beforeMetrics = owner.metrics.toList()
            owner.failure = IllegalStateException("private infrastructure failure")

            val createdProblem = assertIs<AgentActionOutcome.Rejected>(adapter.create(capabilityCaller(create), create)).problem
            assertEquals(AgentOwnerProblems.semanticCreateUnconfirmed, createdProblem)
            assertFalse(createdProblem.retryable)
            val editedProblem = assertIs<AgentActionOutcome.Rejected>(adapter.edit(capabilityCaller(edit), edit)).problem
            assertEquals(AgentOwnerProblems.ownerUnavailable, editedProblem)
            assertTrue(editedProblem.retryable)
            owner.failure = IllegalArgumentException("private infrastructure failure")
            assertFailsWith<IllegalArgumentException> { adapter.create(capabilityCaller(create), create) }
            assertFailsWith<IllegalArgumentException> { adapter.edit(capabilityCaller(edit), edit) }
            assertEquals(beforeModels, owner.models)
            assertEquals(beforeMetrics, owner.metrics)
        }
    }

    @Test
    fun `list is name ordered cursor paged and advertises create only after current capability admission`() = runTest {
        val owner = RecordingSemanticOwner()
        owner.models += owner.modelView(modelCreate("zeta"))
        owner.models += owner.modelView(modelCreate("alpha"))
        val adapter = adapter(owner, canEdit = true)
        val firstRequest = AgentListRequest(path("workspace/semantic-models"), null, 1)

        val first = assertIs<AgentListingOutcome.Found>(adapter.list(membershipCaller(firstRequest), firstRequest)).listing
        val secondRequest = AgentListRequest(path("workspace/semantic-models"), first.nextCursor, 1)
        val second = assertIs<AgentListingOutcome.Found>(adapter.list(membershipCaller(secondRequest), secondRequest)).listing

        assertEquals(listOf("alpha"), first.items.map { assertIs<cc.monomer.agent.api.AgentResourceListingItem.SemanticModel>(it).name })
        assertEquals(listOf("zeta"), second.items.map { assertIs<cc.monomer.agent.api.AgentResourceListingItem.SemanticModel>(it).name })
        assertNull(second.nextCursor)
        assertEquals(listOf("CREATE", "INVOKE"), first.availableOperations.map { it.operation.name })
        assertEquals("SEMANTIC_MODEL_DEFINITION", assertIs<cc.monomer.agent.api.AgentAvailableOperation.Create>(first.availableOperations.first()).inputKind)
        val check = assertIs<cc.monomer.agent.api.AgentAvailableOperation.Invoke>(first.availableOperations.last())
        assertEquals("check" to "SEMANTIC_MODEL_CHECK", check.action to check.inputKind)
    }

    @Test
    fun `list cursor rejects another Semantic collection and non canonical base64url`() = runTest {
        val owner = RecordingSemanticOwner()
        owner.models += owner.modelView(modelCreate("alpha"))
        owner.models += owner.modelView(modelCreate("zeta"))
        val adapter = adapter(owner)
        val firstRequest = AgentListRequest(path("workspace/semantic-models"), null, 1)
        val cursor = assertIs<AgentListingOutcome.Found>(
            adapter.list(membershipCaller(firstRequest), firstRequest),
        ).listing.nextCursor!!

        val wrongCollection = AgentListRequest(path("workspace/metrics"), cursor, 1)
        val nonCanonical = AgentListRequest(path("workspace/semantic-models"), "$cursor=", 1)

        assertEquals(
            "CURSOR_INVALID",
            assertIs<AgentListingOutcome.Rejected>(
                adapter.list(membershipCaller(wrongCollection), wrongCollection),
            ).problem.code,
        )
        assertEquals(
            "CURSOR_INVALID",
            assertIs<AgentListingOutcome.Rejected>(
                adapter.list(membershipCaller(nonCanonical), nonCanonical),
            ).problem.code,
        )
    }

    @Test
    fun `read resolves canonical name and exposes a revisionless definition snapshot`() = runTest {
        val owner = RecordingSemanticOwner()
        owner.models += owner.modelView(modelCreate("orders"))
        val adapter = adapter(owner, canEdit = false)
        val request = AgentReadRequest(path("workspace/semantic-models/orders"), null)

        val resource = assertIs<AgentResourceOutcome.Found>(adapter.read(membershipCaller(request), request)).resource

        assertNull(resource.revision)
        assertEquals("orders", assertIs<AgentSemanticModelResourceBody>(resource.body).definition.name)
        val check = assertIs<cc.monomer.agent.api.AgentAvailableOperation.Invoke>(resource.availableOperations.filterIsInstance<cc.monomer.agent.api.AgentAvailableOperation.Invoke>().single())
        assertEquals("check" to "SEMANTIC_MODEL_CHECK", check.action to check.inputKind)
    }

    @Test
    fun `read carries the last save's time, person, surface, and agent app beside the definition`() = runTest {
        val mina = cc.monomer.identity.api.PrincipalId(FixedPrincipalDisplayNames.MINA)
        val claude = cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE
        data class Row(val actor: cc.monomer.identity.api.PrincipalId?, val surface: CredentialSurface?, val client: cc.monomer.identity.api.AgentClientId?)
        for (row in listOf(Row(mina, CredentialSurface.WEB, null), Row(mina, CredentialSurface.AGENT, claude), Row(mina, CredentialSurface.AGENT, null), null)) {
            val owner = RecordingSemanticOwner()
            val model = owner.modelView(modelCreate("orders"))
            owner.models += model
            if (row != null) owner.changes[model.semanticModelId.value] = cc.monomer.semantic.api.SemanticDefinitionChange(
                Uuid.generateV7(), SemanticDefinitionChangeAction.UPDATED, false, model.semanticModelId.value, "orders", null, model.createdAt, model.updatedAt, row.actor, row.surface, row.client,
            )
            val request = AgentReadRequest(path("workspace/semantic-models/orders"), null)
            val resource = assertIs<cc.monomer.agent.api.AgentResourceEnvelope.SemanticModel>(
                assertIs<AgentResourceOutcome.Found>(adapter(owner, canEdit = false).read(membershipCaller(request), request)).resource,
            )
            assertEquals(model.updatedAt, resource.updatedAt)
            assertEquals(row?.actor?.let { cc.monomer.agent.api.AgentActor(it.toString(), "Mina Park") }, resource.updatedBy, "$row")
            assertEquals(row?.surface, resource.credentialSurface, "$row")
            assertEquals(
                if (row?.client == claude) cc.monomer.agent.api.AgentApp(claude.value, "Claude", "claude.ai") else null,
                resource.agentApp,
                "$row",
            )
        }
    }

    @Test
    fun `edit applies ordered patch to current complete definition and keeps the stable owner id`() = runTest {
        val owner = RecordingSemanticOwner()
        val existing = owner.modelView(modelCreate("orders"))
        owner.models += existing
        val adapter = adapter(owner)
        val request = decoder.edit(
            """
            {
              "path": "workspace/semantic-models/orders", "aggregateMetrics": [],
              "patch": [
                {"op":"add","path":"/label","value":"Orders"},
                {"op":"replace","path":"/label","value":"Revenue"}
              ]
            }
            """.trimIndent(),
        ).value

        val outcome = adapter.edit(capabilityCaller(request), request)

        assertIs<AgentActionOutcome.Completed>(outcome)
        assertEquals(existing.semanticModelId, owner.lastModelCommand?.semanticModelId)
        assertEquals("Revenue", owner.lastModelCommand?.definition?.label)
        assertEquals("srv_edit", owner.lastModelCommand?.idempotencyKey)
    }

    @Test
    fun `semantic edit rejects revisions and root add before owner mutation`() = runTest {
        val owner = RecordingSemanticOwner()
        owner.models += owner.modelView(modelCreate("orders"))
        val adapter = adapter(owner)
        val requestWithRevision = AgentEditRequest(
            path("workspace/semantic-models/orders"),
            cc.monomer.agent.api.AgentRevision(1),
            listOf(AgentJsonPatchOperation(AgentJsonPatchOp.REPLACE, "/label", Json.parseToJsonElement("\"x\""))),
            commandId = null,
            aggregateMetrics = emptyList(),
        )
        val rootAdd = AgentEditRequest(
            path("workspace/semantic-models/orders"),
            null,
            listOf(AgentJsonPatchOperation(AgentJsonPatchOp.ADD, "", Json.parseToJsonElement("{}"))),
            commandId = null,
            aggregateMetrics = emptyList(),
        )

        assertEquals("REVISION_NOT_SUPPORTED", assertIs<AgentActionOutcome.Rejected>(adapter.edit(capabilityCaller(requestWithRevision), requestWithRevision)).problem.code)
        assertEquals("PATCH_INVALID", assertIs<AgentActionOutcome.Rejected>(adapter.edit(capabilityCaller(rootAdd), rootAdd)).problem.code)
        assertNull(owner.lastModelCommand)
    }

    @Test
    fun `semantic edit reports an out of bounds array add as the failing patch operation`() = runTest {
        val owner = RecordingSemanticOwner()
        owner.models += owner.modelView(modelCreate("orders"))
        val adapter = adapter(owner)
        val request = decoder.edit(
            """
            {
              "path": "workspace/semantic-models/orders", "aggregateMetrics": [],
              "patch": [
                {"op":"add","path":"/label","value":"Orders"},
                {"op":"add","path":"/entities/2","value":{"name":"customer","type":"foreign","expression":"customer_id"}}
              ]
            }
            """.trimIndent(),
        ).value

        val problem = assertIs<AgentActionOutcome.Rejected>(
            adapter.edit(capabilityCaller(request), request),
        ).problem

        assertEquals("PATCH_INVALID", problem.code)
        assertEquals("patch[1]", problem.violations.single().field)
        assertEquals("PATCH_OPERATION_INVALID", problem.violations.single().code)
        assertEquals(1, problem.violations.single().operationIndex)
        assertEquals("/entities/2", problem.violations.single().path)
        assertNull(owner.lastModelCommand)
    }

    @Test
    fun `semantic edit preserves final definition decoder diagnostics without guessing a patch operation`() = runTest {
        val owner = RecordingSemanticOwner()
        owner.models += owner.modelView(modelCreate("orders"))
        val adapter = adapter(owner)
        val request = decoder.edit(
            """
            {
              "path": "workspace/semantic-models/orders", "aggregateMetrics": [],
              "patch": [
                {"op":"remove","path":"/name"},
                {"op":"add","path":"/label","value":"Orders"}
              ]
            }
            """.trimIndent(),
        ).value

        val problem = assertIs<AgentActionOutcome.Rejected>(
            adapter.edit(capabilityCaller(request), request),
        ).problem

        assertEquals("SEMANTIC_DEFINITION_INVALID", problem.code)
        assertEquals("definition.name", problem.violations.single().field)
        assertEquals("REQUIRED_FIELD_MISSING", problem.violations.single().code)
        assertNull(problem.violations.single().operationIndex)
        assertNull(problem.violations.single().path)
        assertNull(owner.lastModelCommand)
    }

    @Test
    fun `semantic edit scopes immutable name to the final definition instead of the last patch operation`() = runTest {
        listOf("name" to "definition.name", null to "definition").forEach { (field, expectedField) ->
            val owner = RecordingSemanticOwner(
                modelResult = SemanticMutationResult.Invalid(
                    listOf(SemanticInvalidReason(SemanticInvalidReasonCode.MODEL_NAME_IMMUTABLE, field)),
                ),
            )
            owner.models += owner.modelView(modelCreate("orders"))
            val request = decoder.edit(
                """
                {
                  "path": "workspace/semantic-models/orders", "aggregateMetrics": [],
                  "patch": [
                    {"op":"replace","path":"/name","value":"renamed_orders"},
                    {"op":"add","path":"/label","value":"Orders"}
                  ]
                }
                """.trimIndent(),
            ).value
            val problem = assertIs<AgentActionOutcome.Rejected>(
                adapter(owner).edit(capabilityCaller(request), request),
            ).problem

            assertEquals("renamed_orders", owner.lastModelCommand?.definition?.name?.value)
            assertEquals("Orders", owner.lastModelCommand?.definition?.label)
            assertEquals("SEMANTIC_DEFINITION_INVALID", problem.code)
            val violation = problem.violations.single()
            assertEquals("MODEL_NAME_IMMUTABLE", violation.code)
            assertEquals(expectedField, violation.field)
            assertNull(violation.operationIndex)
            assertNull(violation.path)
        }
    }

    @Test
    fun `delete delegates original IDs and exposes completed collection for both semantic kinds`() = runTest {
        val owner = RecordingSemanticOwner()
        val adapter = adapter(owner)
        owner.models += owner.modelView(modelCreate("orders"))
        val metric = metricCreate("revenue")
        adapter.create(capabilityCaller(metric), metric)
        for (resource in listOf("workspace/semantic-models/orders", "workspace/metrics/revenue")) {
            val request = decoder.invoke("""{"path":"$resource","action":"delete"}""").value
            val caller = checkCaller(request)
            val action = assertIs<AgentActionOutcome.Completed>(adapter.invoke(caller, request)).action
            assertEquals(AgentActionStatus.COMPLETED, action.status)
            assertEquals(resource.substringBeforeLast('/'), action.result.path.value)
            assertTrue(cc.monomer.agent.api.AgentWorkspaceResponseRelations.matches(request, action))
            cc.monomer.control.publicapi.PublicControlContractValidator().agentAction(
                cc.monomer.control.publicapi.AgentWorkspaceJson.agentActionJson(AgentRequestId("srv_delete"), action, workspaceId, java.net.URI("http://localhost:3100")),
            )
            assertIs<AgentActionOutcome.NotFound>(adapter.invoke(caller, request))
            assertIs<AgentResourceOutcome.NotFound>(adapter.read(caller, AgentReadRequest(request.path, null)))
        }
        assertTrue(owner.models.isEmpty() && owner.metrics.isEmpty())
    }

    @Test
    fun `delete and restore recheck Editor role and unavailable operations are not advertised`() = runTest {
        val owner = RecordingSemanticOwner()
        val saved = owner.modelView(modelCreate("orders"))
        owner.models += saved
        val delete = decoder.invoke("""{"path":"workspace/semantic-models/orders","action":"delete"}""").value
        val caller = checkCaller(delete)
        currentRole = WorkspaceRole.EXPLORER
        assertIs<AgentActionOutcome.Forbidden>(adapter(owner).invoke(caller, delete))
        assertEquals(listOf(saved), owner.models)
        val read = AgentReadRequest(delete.path, null)
        val resource = assertIs<AgentResourceOutcome.Found>(adapter(owner, false).read(caller, read)).resource
        assertTrue(resource.availableOperations.none { it is cc.monomer.agent.api.AgentAvailableOperation.Invoke && it.action == "delete" })
        assertTrue(resource.availableOperations.any { it is cc.monomer.agent.api.AgentAvailableOperation.List && it.path.value.endsWith("/history/${saved.semanticModelId}") })
        val version = owner.record(saved)
        val restore = decoder.invoke("""{"path":"workspace/semantic-models/orders/history/${saved.semanticModelId}/${version.changeId}","action":"restore"}""").value
        assertIs<AgentActionOutcome.Forbidden>(adapter(owner).invoke(caller, restore))
        assertNull(owner.restoredId)
    }

    @Test
    fun `owner restorability controls Agent history operations for both definition kinds`() = runTest {
        val validator = cc.monomer.control.publicapi.PublicControlContractValidator()
        for (model in listOf(true, false)) {
            val owner = RecordingSemanticOwner()
            val ownerAdapter = adapter(owner)
            val create = if (model) modelCreate("orders") else metricCreate("revenue")
            assertIs<AgentActionOutcome.Completed>(ownerAdapter.create(capabilityCaller(create), create))
            val definitionId = if (model) owner.models.single().semanticModelId.value else owner.metrics.single().metricId.value
            val snapshot = if (model) SemanticDefinitionSnapshot.Model(owner.models.single().definition)
                else SemanticDefinitionSnapshot.Metric(owner.metrics.single().definition)
            val olderSnapshot = when (snapshot) {
                is SemanticDefinitionSnapshot.Model -> snapshot.copy(definition = snapshot.definition.copy(label = "Earlier"))
                is SemanticDefinitionSnapshot.Metric -> snapshot.copy(definition = (snapshot.definition as SemanticMetricDefinition.Simple).copy(label = "Earlier"))
            }
            val collection = if (model) "semantic-models" else "metrics"
            val name = if (model) "orders" else "revenue"
            val historyPath = "workspace/$collection/$name/history/$definitionId"
            val list = AgentListRequest(path(historyPath), null, 20)
            val caller = membershipCaller(list)
            for (deleted in listOf(false, true)) {
                if (deleted) { owner.models.clear(); owner.metrics.clear() }
                val latest = SemanticDefinitionVersion(
                    Uuid.generateV7(), if (deleted) SemanticDefinitionChangeAction.DELETED else SemanticDefinitionChangeAction.UPDATED,
                    snapshot, instant, actor.id, CredentialSurface.AGENT, cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE, deleted,
                )
                val same = latest.copy(changeId = Uuid.generateV7(), action = SemanticDefinitionChangeAction.UPDATED, restorable = false)
                val older = same.copy(changeId = Uuid.generateV7(), definition = olderSnapshot, restorable = true)
                owner.versions[definitionId] = listOf(latest, same, older)
                val listing = assertIs<AgentListingOutcome.Found>(ownerAdapter.list(caller, list)).listing
                assertEquals(listOf(deleted, false, true), listing.items.map { (it as cc.monomer.agent.api.AgentResourceListingItem.SemanticDefinitionVersion).restorable })
                validator.agentListing(cc.monomer.control.publicapi.AgentWorkspaceJson.agentListingJson(AgentRequestId("srv_restore_flags"), listing))
                for (version in owner.versions.getValue(definitionId)) {
                    val read = AgentReadRequest(path("$historyPath/${version.changeId}"), null)
                    val resource = assertIs<AgentResourceOutcome.Found>(ownerAdapter.read(caller, read)).resource
                    assertEquals(version.restorable, (resource.body as cc.monomer.agent.api.AgentSemanticDefinitionVersionBody).restorable)
                    assertEquals(version.restorable, resource.availableOperations.any { it is cc.monomer.agent.api.AgentAvailableOperation.Invoke && it.action == "restore" })
                    validator.agentResource(cc.monomer.control.publicapi.AgentWorkspaceJson.agentResourceJson(AgentRequestId("srv_restore_flags"), resource))
                    val explorerResource = assertIs<AgentResourceOutcome.Found>(adapter(owner, false).read(caller, read)).resource
                    assertEquals(version.restorable, (explorerResource.body as cc.monomer.agent.api.AgentSemanticDefinitionVersionBody).restorable)
                    assertTrue(explorerResource.availableOperations.isEmpty())
                }
            }
        }
    }

    @Test
    fun `deleted ID history remains readable across name reuse and restore retains original identity`() = runTest {
        val owner = RecordingSemanticOwner()
        val saved = owner.modelView(modelCreate("orders"))
        val version = owner.record(saved)
        val adapter = adapter(owner)
        val historyPath = "workspace/semantic-models/orders/history/${saved.semanticModelId}"
        val list = AgentListRequest(path(historyPath), null, 20)
        val caller = membershipCaller(list)
        val listing = assertIs<AgentListingOutcome.Found>(adapter.list(caller, list)).listing
        val item = assertIs<cc.monomer.agent.api.AgentResourceListingItem.SemanticDefinitionVersion>(listing.items.single())
        assertTrue(item.deleted)
        assertEquals(saved.semanticModelId.value, item.definitionId)
        cc.monomer.control.publicapi.PublicControlContractValidator().agentListing(
            cc.monomer.control.publicapi.AgentWorkspaceJson.agentListingJson(AgentRequestId("srv_history"), listing),
        )
        val read = AgentReadRequest(item.path, null)
        val resource = assertIs<AgentResourceOutcome.Found>(adapter.read(caller, read)).resource
        val body = assertIs<cc.monomer.agent.api.AgentSemanticDefinitionVersionBody>(resource.body)
        assertTrue(body.deleted)
        assertEquals("orders", body.name)
        assertTrue(resource.availableOperations.any { it is cc.monomer.agent.api.AgentAvailableOperation.Invoke && it.action == "restore" })
        cc.monomer.control.publicapi.PublicControlContractValidator().agentResource(
            cc.monomer.control.publicapi.AgentWorkspaceJson.agentResourceJson(AgentRequestId("srv_history"), resource),
        )
        val restore = decoder.invoke("""{"path":"${item.path}","action":"restore"}""").value
        owner.models += owner.modelView(modelCreate("orders"))
        val conflict = assertIs<AgentActionOutcome.Rejected>(adapter.invoke(caller, restore)).problem
        assertEquals("SEMANTIC_NAME_CONFLICT", conflict.code)
        assertEquals(409, conflict.status.value)
        owner.models.clear()
        val action = assertIs<AgentActionOutcome.Completed>(adapter.invoke(caller, restore)).action
        assertEquals(saved.semanticModelId.value, owner.restoredId)
        assertTrue(cc.monomer.agent.api.AgentWorkspaceResponseRelations.matches(restore, action))
        val mismatched = AgentReadRequest(path(historyPath.replace("/orders/", "/other/")+"/${version.changeId}"), null)
        assertIs<AgentResourceOutcome.NotFound>(adapter.read(caller, mismatched))
        assertIs<AgentListingOutcome.NotFound>(adapter.list(caller, list.copy(path=path(historyPath.replace("/orders/", "/other/")))))
    }

    private var currentRole = WorkspaceRole.EDITOR
    private var currentAdmissionDenial: cc.monomer.identity.api.IdentityAdmissionDenial? = null
    private val checkRequests = mutableListOf<cc.monomer.semantic.api.SemanticCheckRequest>()
    private var checkFailure: RuntimeException? = null
    private var checkResult: cc.monomer.semantic.api.SemanticCheckResult = cc.monomer.semantic.api.SemanticCheckResult.Checked(emptyList(), null, emptyList(),
        emptyList(),
    )
    private val checks = object : cc.monomer.semantic.api.SemanticDefinitionCheckUseCase {
        override suspend fun readInputs(request: cc.monomer.semantic.api.SemanticCheckRequest) =
            cc.monomer.semantic.api.SemanticCheckInputs(request, emptyList(), emptyList(), emptyMap(), emptyMap(), emptyList(), java.time.ZoneId.of("UTC"))
        override suspend fun checkFromInputs(inputs: cc.monomer.semantic.api.SemanticCheckInputs) = check(inputs.request)
        override suspend fun check(request: cc.monomer.semantic.api.SemanticCheckRequest): cc.monomer.semantic.api.SemanticCheckResult {
            checkRequests += request
            checkFailure?.let { throw it }
            return checkResult
        }
    }

    @Test
    fun `check on the collection treats the draft as new and returns problems as data`() = runTest {
        val owner = RecordingSemanticOwner()
        val connector = cc.monomer.connector.api.ConnectorId(Uuid.parse("01942b04-6e00-7000-8000-000000000010"))
        checkResult = cc.monomer.semantic.api.SemanticCheckResult.Checked(
            listOf(connector),
            cc.monomer.semantic.api.SemanticCheckPreview(
                connector,
                cc.monomer.semantic.api.SemanticSqlDialect.POSTGRESQL,
                "SELECT 1",
                listOf(
                    cc.monomer.semantic.api.SemanticGroupBy.MetricTime(cc.monomer.semantic.api.SemanticTimeGranularity.DAY),
                    cc.monomer.semantic.api.SemanticGroupBy.Entity(SemanticElementName("orders"), SemanticElementName("order_id")),
                    cc.monomer.semantic.api.SemanticGroupBy.Dimension(SemanticElementName("orders"), SemanticElementName("status"), null),
                ),
            ),
            listOf(cc.monomer.semantic.api.SemanticCheckProblem(cc.monomer.semantic.api.SemanticCheckProblemCode.TIME_SPINE_UNAVAILABLE, null, connector, null, null)),
            emptyList(),
        )
        val request = metricCheck("workspace/metrics", connector.value.toString())

        val action = assertIs<cc.monomer.agent.api.AgentActionResult.SemanticCheck>(
            assertIs<AgentActionOutcome.Completed>(adapter(owner, canEdit = false).invoke(checkCaller(request), request)).action,
        )

        assertEquals(cc.monomer.agent.api.AgentResourceKind.COLLECTION, action.result.kind)
        assertEquals(AgentActionStatus.COMPLETED, action.status)
        assertEquals(true, cc.monomer.agent.api.AgentWorkspaceResponseRelations.matches(request, action))
        cc.monomer.control.publicapi.PublicControlContractValidator().agentAction(
            cc.monomer.control.publicapi.AgentWorkspaceJson.agentActionJson(AgentRequestId("srv_check"), action, workspaceId, java.net.URI("http://localhost:3100")),
        )
        val check = action.check.toString()
        assertEquals(true, check.contains("\"ok\":false") && check.contains("TIME_SPINE_UNAVAILABLE") && check.contains("\"field\":null"), check)
        val draft = assertIs<cc.monomer.semantic.api.SemanticCheckDraft.Metric>(checkRequests.single().draft)
        assertNull(draft.metricId)
        assertEquals(connector, draft.connectorId)
        assertNull(owner.lastMetricCommand)
    }

    @Test
    fun `Check rejects MetricFlow caller input failures with existing Agent 400 codes`() = runTest {
        val owner = RecordingSemanticOwner()
        val adapter = adapter(owner)
        val requests = listOf(
            metricCheck("workspace/metrics", null),
            decoder.invoke("""{"path":"workspace/semantic-models","action":"check","input":{"kind":"SEMANTIC_MODEL_CHECK","definition":${modelDefinitionJson("orders")}}}""").value,
        )
        val diagnostic = "private MetricFlow diagnostic"
        val failures = listOf(
            cc.monomer.semantic.application.SemanticMetricFlowInvalidQueryException(
                SemanticInvalidReasonCode.QUERY_FILTER_UNRENDERABLE, IllegalStateException(diagnostic),
            ) to "SEMANTIC_QUERY_INVALID",
            cc.monomer.semantic.application.SemanticMetricFlowUnsupportedException(IllegalStateException(diagnostic)) to "SEMANTIC_QUERY_UNSUPPORTED",
        )
        for (request in requests) {
            for ((failure, code) in failures) {
                checkFailure = failure
                val problem = assertIs<AgentActionOutcome.Rejected>(adapter.invoke(checkCaller(request), request)).problem
                assertEquals(400, problem.status.value)
                assertEquals(code, problem.code)
                assertFalse(problem.retryable)
                assertNull(problem.retryAfterSeconds)
                assertFalse(problem.toString().contains(diagnostic))
            }
        }
        assertEquals(4, checkRequests.size)
        assertNull(owner.lastModelCommand)
        assertNull(owner.lastMetricCommand)
    }

    @Test
    fun `Check propagates MetricFlow engine faults for transport 500 handling`() = runTest {
        val owner = RecordingSemanticOwner()
        val adapter = adapter(owner)
        val requests = listOf(
            metricCheck("workspace/metrics", null),
            decoder.invoke("""{"path":"workspace/semantic-models","action":"check","input":{"kind":"SEMANTIC_MODEL_CHECK","definition":${modelDefinitionJson("orders")}}}""").value,
        )
        val fault = cc.monomer.semantic.application.SemanticMetricFlowEngineFaultException(IllegalStateException("private MetricFlow diagnostic"))
        checkFailure = fault
        for (request in requests) {
            kotlin.test.assertSame(fault, assertFailsWith<cc.monomer.semantic.application.SemanticMetricFlowEngineFaultException> {
                adapter.invoke(checkCaller(request), request)
            })
        }
        assertEquals(2, checkRequests.size)
        assertNull(owner.lastModelCommand)
        assertNull(owner.lastMetricCommand)
    }

    @Test
    fun `check on an exact path replaces that saved definition and unknown names are not found`() = runTest {
        val owner = RecordingSemanticOwner()
        val saved = owner.modelView(modelCreate("orders"))
        owner.models += saved
        val request = decoder.invoke(
            """{"path":"workspace/semantic-models/orders","action":"check","input":{"kind":"SEMANTIC_MODEL_CHECK","definition":${modelDefinitionJson("orders")}}}""",
        ).value

        val action = assertIs<cc.monomer.agent.api.AgentActionResult.SemanticCheck>(
            assertIs<AgentActionOutcome.Completed>(adapter(owner).invoke(checkCaller(request), request)).action,
        )
        assertEquals(cc.monomer.agent.api.AgentResourceKind.SEMANTIC_MODEL, action.result.kind)
        assertEquals(saved.semanticModelId, assertIs<cc.monomer.semantic.api.SemanticCheckDraft.Model>(checkRequests.single().draft).semanticModelId)

        val missing = decoder.invoke(
            """{"path":"workspace/semantic-models/missing","action":"check","input":{"kind":"SEMANTIC_MODEL_CHECK","definition":${modelDefinitionJson("missing")}}}""",
        ).value
        assertIs<AgentActionOutcome.NotFound>(adapter(owner).invoke(checkCaller(missing), missing))
    }

    @Test
    fun `check admission requires Explorer and decodes definitions strictly`() {
        val request = metricCheck("workspace/metrics", null)
        val intent = cc.monomer.agent.api.AgentWorkspaceAdmissionIntent.SemanticCheck(request.path, request.action)
        assertEquals(WorkspaceRole.EXPLORER, intent.requiredRole)
        val invalid = kotlin.runCatching {
            decoder.invoke("""{"path":"workspace/metrics","action":"check","input":{"kind":"METRIC_CHECK","definition":{"schemaVersion":3,"name":"Revenue","type":"simple"}}}""")
        }.exceptionOrNull()
        assertIs<cc.monomer.agent.api.AgentWorkspaceRequestDecodingException>(invalid)
    }

    private fun metricCheck(path: String, connectorId: String?): cc.monomer.agent.api.AgentInvokeRequest = decoder.invoke(
        """{"path":"$path","action":"check","input":{"kind":"METRIC_CHECK",${connectorId?.let { "\"connectorId\":\"$it\"," } ?: ""}"definition":{"schemaVersion":3,"name":"revenue","type":"simple","semanticModelName":"orders","measure":"amount"}}}""",
    ).value

    private fun modelDefinitionJson(name: String): String =
        """{"schemaVersion":3,"name":"$name","sourceRelations":[{"connectorId":"01942b04-6e00-7000-8000-000000000010","schema":"analytics","relation":"orders"}],"entities":[{"name":"order_id","type":"primary","expression":"order_id"}],"dimensions":[],"measures":[]}"""

    private data class InvalidDefinition(val collection: String, val kind: String, val definition: String, val message: String)

    private fun invalidDefinitions() = listOf(
        InvalidDefinition(
            "workspace/semantic-models", "SEMANTIC_MODEL_DEFINITION",
            modelDefinitionJson("orders").replace("\"expression\":\"order_id\"", "\"expression\":\" \""),
            "Semantic entity expression must not be blank",
        ),
        InvalidDefinition(
            "workspace/metrics", "METRIC_DEFINITION",
            """{"schemaVersion":3,"name":"revenue","type":"ratio","numerator":{"metricName":"missing_metric"},"denominator":{"metricName":"missing_metric"}}""",
            "Ratio metric numerator and denominator must not have the same effective input",
        ),
    )

    private suspend fun checkCaller(request: cc.monomer.agent.api.AgentInvokeRequest): AgentWorkspaceCaller =
        authenticatedCaller("srv_check") { authenticator, requestId ->
            authenticator.authenticate(workspaceId, credential, AgentWorkspaceAdmissionAudience.REST, request, requestId)
        }

    private fun adapter(owner: RecordingSemanticOwner, canEdit: Boolean = true): AgentSemanticOwnerAdapter {
        val identity = io.mockk.mockk<cc.monomer.identity.api.IdentityAdmissionPort>()
        io.mockk.coEvery { identity.decide(any<cc.monomer.identity.api.IdentityRoleAdmissionRequest>()) } coAnswers {
            currentAdmissionDenial?.let { return@coAnswers cc.monomer.identity.api.IdentityRoleAdmissionDecision.Denied(it) }
            val request = firstArg<cc.monomer.identity.api.IdentityRoleAdmissionRequest>()
            if (!currentRole.includes(request.requiredRole)) {
                return@coAnswers cc.monomer.identity.api.IdentityRoleAdmissionDecision.Denied(
                    cc.monomer.identity.api.IdentityAdmissionDenial.PERMISSION_UNAVAILABLE,
                )
            }
            cc.monomer.identity.api.IdentityRoleAdmissionDecision.Authorized(cc.monomer.identity.api.IdentityRoleEvidence(
                cc.monomer.identity.api.OrganizationPlan.FREE, actor, OrganizationId(Uuid.parse("01942b04-6e00-7000-8000-000000000003")),
                workspaceId, AuthorizationRevision(1), CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"), currentRole,
            ))
        }
        return AgentSemanticOwnerAdapter(owner, owner, checks, AgentSemanticEditAvailability { canEdit },
            cc.monomer.control.ControlAdmissionFacade(identity, java.time.Clock.fixed(instant, java.time.ZoneOffset.UTC)), fixedAgentProvenanceNames())
    }

    @Test
    fun `secondary admission preserves expired credential and lost membership outcomes`() = runTest {
        val owner = RecordingSemanticOwner()
        val request = modelCreate("orders")
        val caller = capabilityCaller(request)
        val adapter = adapter(owner)
        for (reason in listOf(cc.monomer.identity.api.IdentityAdmissionDenial.CREDENTIAL_EXPIRED, cc.monomer.identity.api.IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE)) {
            currentAdmissionDenial = reason
            val outcome = adapter.create(caller, request)
            if (reason == cc.monomer.identity.api.IdentityAdmissionDenial.CREDENTIAL_EXPIRED) {
                assertEquals(401, assertIs<AgentActionOutcome.Rejected>(outcome).problem.status.value)
            } else assertIs<AgentActionOutcome.NotFound>(outcome)
        }
        assertNull(owner.lastModelCommand)
    }

    @Test
    fun `mutation rechecks current role after caller admission and rejects a downgrade`() = runBlocking {
        val owner = RecordingSemanticOwner()
        val request = modelCreate("orders")
        val caller = capabilityCaller(request)
        currentRole = WorkspaceRole.EXPLORER
        assertIs<AgentActionOutcome.Forbidden>(adapter(owner).create(caller, request))
        assertNull(owner.lastModelCommand)
    }


    private suspend fun capabilityCaller(request: AgentCreateRequest): AgentWorkspaceCaller =
        authenticatedCaller("srv_create") { authenticator, requestId ->
            authenticator.authenticate(workspaceId, credential, AgentWorkspaceAdmissionAudience.REST, request, requestId)
        }

    private suspend fun capabilityCaller(request: AgentEditRequest): AgentWorkspaceCaller =
        authenticatedCaller("srv_edit") { authenticator, requestId ->
            authenticator.authenticate(workspaceId, credential, AgentWorkspaceAdmissionAudience.REST, request, requestId)
        }

    private suspend fun membershipCaller(request: AgentListRequest): AgentWorkspaceCaller =
        authenticatedCaller("srv_list") { authenticator, requestId ->
            authenticator.authenticate(workspaceId, credential, AgentWorkspaceAdmissionAudience.REST, request, requestId)
        }

    private suspend fun membershipCaller(request: AgentReadRequest): AgentWorkspaceCaller =
        authenticatedCaller("srv_read") { authenticator, requestId ->
            authenticator.authenticate(workspaceId, credential, AgentWorkspaceAdmissionAudience.REST, request, requestId)
        }

    private suspend fun authenticatedCaller(
        requestId: String,
        authenticate: suspend (AgentWorkspaceCallerAuthenticator, AgentRequestId) -> AgentWorkspaceCallerAuthenticationOutcome,
    ): AgentWorkspaceCaller {
        val authenticator = AgentWorkspaceCallerAuthenticator(
            object : AgentWorkspaceAdmissionPort {
                override suspend fun decide(request: AgentWorkspaceAdmissionRequest): AgentWorkspaceAdmissionDecision {
                    val evidence = when (request) {
                    is AgentWorkspaceRoleAdmissionRequest -> AgentWorkspaceRoleEvidence(
                        actor = actor,
                        organizationId = OrganizationId(Uuid.parse("01942b04-6e00-7000-8000-000000000003")),
                        organizationPlan = cc.monomer.identity.api.OrganizationPlan.FREE,
                        workspaceId = workspaceId,
                        authorizationRevision = AuthorizationRevision(1),
                        credentialKind = CredentialKind.AGENT_OAUTH,
                        credentialSurface = CredentialSurface.AGENT,
                        agentClientId = cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"),
                        role = WorkspaceRole.EDITOR,

                        intent = request.intent,
                    )

                    }
                    return AgentWorkspaceAdmissionDecision.Authorized(evidence)
                }
            },
        )
        return assertIs<AgentWorkspaceCallerAuthenticationOutcome.Authenticated>(
            authenticate(authenticator, AgentRequestId(requestId)),
        ).caller
    }

    private fun modelCreate(name: String): AgentCreateRequest = decoder.create(
        """
        {
          "path": "workspace/semantic-models",
          "input": {
            "kind": "SEMANTIC_MODEL_DEFINITION", "aggregateMetrics": [],
            "definition": {
              "schemaVersion": 3,
              "name": "$name",
              "sourceRelations": [{
                "connectorId": "01942b04-6e00-7000-8000-000000000010",
                "schema": "analytics",
                "relation": "orders"
              }],
              "entities": [{"name":"order_id","type":"primary","expression":"order_id"}],
              "dimensions": [],
              "measures": []
            }
          }
        }
        """.trimIndent(),
    ).value

    private fun metricCreate(name: String): AgentCreateRequest = decoder.create(
        """
        {
          "path": "workspace/metrics",
          "input": {
            "kind": "METRIC_DEFINITION",
            "definition": {
              "schemaVersion": 3,
              "name": "$name",
              "type": "simple",
              "semanticModelName": "missing_model",
              "measure": "amount"
            }
          }
        }
        """.trimIndent(),
    ).value

    private fun path(value: String) = AgentResourcePath.parse(value)

    private inner class RecordingSemanticOwner(
        private val modelResult: SemanticMutationResult<cc.monomer.semantic.api.SemanticModelWriteResult>? = null,
    ) : SemanticAuthorityUseCase, SemanticCurrentReadUseCase {
        override suspend fun readRecentModels(workspaceId: WorkspaceId, before: Instant?, beforeId: Uuid?, actorId: cc.monomer.identity.api.PrincipalId?, credentialSurface: cc.monomer.identity.api.CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?, limit: Int): List<cc.monomer.semantic.api.SemanticDefinitionChange> = error("Not used by this fixture")
        override suspend fun readRecentMetrics(workspaceId: WorkspaceId, before: Instant?, beforeId: Uuid?, actorId: cc.monomer.identity.api.PrincipalId?, credentialSurface: cc.monomer.identity.api.CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?, limit: Int): List<cc.monomer.semantic.api.SemanticDefinitionChange> = error("Not used by this fixture")
        override suspend fun readRecentAgentClients(workspaceId: WorkspaceId, scanLimit: Int): List<cc.monomer.identity.api.RecentAgentClient> = error("Not used by this fixture")
        val versions = mutableMapOf<Uuid, List<SemanticDefinitionVersion>>()
        var restoredId: Uuid? = null
        fun record(view: SemanticModelView): SemanticDefinitionVersion = SemanticDefinitionVersion(
            Uuid.generateV7(), if (models.any { it.semanticModelId == view.semanticModelId }) SemanticDefinitionChangeAction.CREATED else SemanticDefinitionChangeAction.DELETED, SemanticDefinitionSnapshot.Model(view.definition), instant,
            actor.id, CredentialSurface.AGENT, cc.monomer.control.agentapp.FixedAgentAppDisplays.CLAUDE,
            models.none { it.semanticModelId == view.semanticModelId },
        ).also { versions[view.semanticModelId.value] = listOf(it) }
        override suspend fun readModelVersion(workspaceId: WorkspaceId, semanticModelId: SemanticModelId, changeId: Uuid) =
            versions[semanticModelId.value]?.firstOrNull { it.changeId == changeId }
        override suspend fun readMetricVersion(workspaceId: WorkspaceId, metricId: MetricId, changeId: Uuid) =
            versions[metricId.value]?.firstOrNull { it.changeId == changeId }
        override suspend fun readModelHistory(workspaceId: WorkspaceId, semanticModelId: SemanticModelId, before: SemanticHistoryBoundary?, limit: Int): SemanticDefinitionHistory? =
            versions[semanticModelId.value]?.let { SemanticDefinitionHistory(semanticModelId.value, (it.first().definition as SemanticDefinitionSnapshot.Model).definition.name.value, models.none { model -> model.semanticModelId == semanticModelId }, it.take(limit), null) }
        override suspend fun readMetricHistory(workspaceId: WorkspaceId, metricId: MetricId, before: SemanticHistoryBoundary?, limit: Int): SemanticDefinitionHistory? =
            versions[metricId.value]?.let { SemanticDefinitionHistory(metricId.value, (it.first().definition as SemanticDefinitionSnapshot.Metric).definition.name.value, metrics.none { metric -> metric.metricId == metricId }, it.take(limit), null) }
        override suspend fun deleteModel(command: DeleteSemanticModelCommand): SemanticMutationResult<Unit> =
            if (models.removeAll { it.semanticModelId == command.semanticModelId }) SemanticMutationResult.Committed(Unit) else SemanticMutationResult.NotFoundOrOutsideWorkspace
        override suspend fun deleteMetric(command: DeleteMetricCommand): SemanticMutationResult<Unit> =
            if (metrics.removeAll { it.metricId == command.metricId }) SemanticMutationResult.Committed(Unit) else SemanticMutationResult.NotFoundOrOutsideWorkspace
        override suspend fun restoreModel(command: RestoreSemanticModelCommand): SemanticMutationResult<SemanticModelView> {
            val snapshot = versions[command.semanticModelId.value]?.firstOrNull { it.changeId == command.changeId }?.definition as? SemanticDefinitionSnapshot.Model
                ?: return SemanticMutationResult.NotFoundOrOutsideWorkspace
            if (models.any { it.definition.name == snapshot.definition.name && it.semanticModelId != command.semanticModelId }) return SemanticMutationResult.Conflict
            restoredId = command.semanticModelId.value
            return when (val saved = putModel(PutSemanticModelCommand(command.authorization, command.idempotencyKey, command.semanticModelId, snapshot.definition, emptyList()))) {
                is SemanticMutationResult.Committed -> SemanticMutationResult.Committed(saved.value.model)
                is SemanticMutationResult.Invalid -> saved
                is SemanticMutationResult.AggregateMetricNameConflict -> saved
                SemanticMutationResult.Conflict -> SemanticMutationResult.Conflict
                SemanticMutationResult.IdempotencyConflict -> SemanticMutationResult.IdempotencyConflict
                SemanticMutationResult.NotFoundOrOutsideWorkspace -> SemanticMutationResult.NotFoundOrOutsideWorkspace
                SemanticMutationResult.InvalidAuthorization -> SemanticMutationResult.InvalidAuthorization
            }
        }
        override suspend fun restoreMetric(command: RestoreMetricCommand): SemanticMutationResult<MetricView> = error("Not used by this fixture")
        val changes = mutableMapOf<Uuid, cc.monomer.semantic.api.SemanticDefinitionChange>()
        override suspend fun readModelChange(workspaceId: WorkspaceId, semanticModelId: cc.monomer.semantic.api.SemanticModelId): cc.monomer.semantic.api.SemanticDefinitionChange? = changes[semanticModelId.value]
        override suspend fun readMetricChange(workspaceId: WorkspaceId, metricId: cc.monomer.semantic.api.MetricId): cc.monomer.semantic.api.SemanticDefinitionChange? = changes[metricId.value]
        val models = mutableListOf<SemanticModelView>()
        val metrics = mutableListOf<MetricView>()
        val metricSourceConnectors = mutableMapOf<SemanticElementName, List<cc.monomer.semantic.api.MetricSourceConnector>>()
        var sourceConnectorNames: Set<SemanticElementName> = emptySet()
        override suspend fun findMetricSourceConnectors(
            workspaceId: WorkspaceId,
            metricNames: Set<SemanticElementName>,
        ): Map<SemanticElementName, List<cc.monomer.semantic.api.MetricSourceConnector>> {
            sourceConnectorNames = metricNames
            return metricNames.associateWith { metricSourceConnectors[it].orEmpty() }
        }
        override fun findModelsInCurrentTransaction(workspaceId: WorkspaceId): List<SemanticModelView> =
            models.filter { it.workspaceId == workspaceId }
        override fun findMetricsInCurrentTransaction(workspaceId: WorkspaceId): List<MetricView> =
            metrics.filter { it.workspaceId == workspaceId }
        var lastModelCommand: PutSemanticModelCommand? = null
        var lastMetricCommand: PutMetricCommand? = null
        var failure: RuntimeException? = null

        override suspend fun putModel(command: PutSemanticModelCommand): SemanticMutationResult<cc.monomer.semantic.api.SemanticModelWriteResult> {
            failure?.let { throw it }
            lastModelCommand = command
            modelResult?.let { return it }
            val view = SemanticModelView(
                command.semanticModelId ?: SemanticModelId(Uuid.parse("01942b04-6e00-7000-8000-000000000020")),
                workspaceId,
                command.definition,
                instant,
                instant,
                emptyList(),
            )
            models.removeAll { it.semanticModelId == view.semanticModelId }
            models += view
            return SemanticMutationResult.Committed(cc.monomer.semantic.api.SemanticModelWriteResult(view, emptyList()))
        }

        override suspend fun putMetric(command: PutMetricCommand): SemanticMutationResult<MetricView> {
            failure?.let { throw it }
            lastMetricCommand = command
            val view = MetricView(
                command.metricId ?: MetricId(Uuid.parse("01942b04-6e00-7000-8000-000000000021")),
                workspaceId,
                command.definition,
                instant,
                instant,
                emptyList(),
            )
            metrics.removeAll { it.metricId == view.metricId }
            metrics += view
            return SemanticMutationResult.Committed(view)
        }

        fun modelView(request: AgentCreateRequest): SemanticModelView {
            val input = request.input as cc.monomer.agent.api.AgentCreateInput.SemanticModel
            val definition = cc.monomer.control.semantic.inbound.rest.SemanticModelDefinitionDto(
                schemaVersion = input.definition.schemaVersion.toInt(),
                name = input.definition.name,
                label = input.definition.label,
                description = input.definition.description,
                sourceRelations = input.definition.sourceRelations.map { relation ->
                    cc.monomer.control.semantic.inbound.rest.SemanticSourceRelationDto(
                        relation.connectorId.toString(), relation.catalog, relation.schema, relation.relation,
                    )
                },
                aggregationTimeDimension = input.definition.aggregationTimeDimension,
                entities = input.definition.entities.map { entity ->
                    cc.monomer.control.semantic.inbound.rest.SemanticEntityDefinitionDto(
                        entity.name, entity.kind.name.lowercase(), entity.expression, entity.label, entity.description,
                    )
                },
                dimensions = input.definition.dimensions.map { dimension ->
                    cc.monomer.control.semantic.inbound.rest.SemanticDimensionDefinitionDto(
                        dimension.name, dimension.kind.name.lowercase(), dimension.expression,
                        dimension.granularity?.name?.lowercase(), dimension.label, dimension.description,
                        false, dimension.timeKind?.name?.lowercase(),
                    )
                },
                measures = emptyList(),
            ).toDomain()
            return SemanticModelView(
                SemanticModelId(Uuid.generateV7()), workspaceId, definition, instant, instant,
                emptyList(),
            )
        }

        override suspend fun findModel(workspaceId: WorkspaceId, semanticModelId: SemanticModelId) =
            models.firstOrNull { it.semanticModelId == semanticModelId }
        override suspend fun findModelByName(workspaceId: WorkspaceId, name: SemanticElementName) =
            models.firstOrNull { it.definition.name == name }
        override suspend fun findModels(workspaceId: WorkspaceId) = models.toList()
        override suspend fun listModels(
            workspaceId: WorkspaceId,
            after: cc.monomer.semantic.api.SemanticModelListBoundary?,
            limit: Int,
        ): cc.monomer.semantic.api.SemanticModelListPage {
            val remaining = models.sortedBy { it.definition.name.value }
                .dropWhile { after != null && it.definition.name.value <= after.name.value }
            val items = remaining.take(limit)
            return cc.monomer.semantic.api.SemanticModelListPage(
                items,
                if (remaining.size > limit) {
                    cc.monomer.semantic.api.SemanticModelListBoundary(items.last().definition.name)
                } else {
                    null
                },
            )
        }
        override suspend fun searchModels(workspaceId: WorkspaceId, query: String, cursor: SemanticModelPageCursor?, pageSize: Int) =
            SemanticModelPage(emptyList(), null)
        override suspend fun findLatestModel(workspaceId: WorkspaceId) = models.maxByOrNull { it.updatedAt }
        override suspend fun findMetric(workspaceId: WorkspaceId, metricId: MetricId) = metrics.firstOrNull { it.metricId == metricId }
        override suspend fun findMetricByName(workspaceId: WorkspaceId, name: SemanticElementName) =
            metrics.firstOrNull { it.definition.name == name }
        override suspend fun findMetrics(workspaceId: WorkspaceId) = metrics.toList()
        override suspend fun listMetrics(
            workspaceId: WorkspaceId,
            after: cc.monomer.semantic.api.MetricListBoundary?,
            limit: Int,
        ): cc.monomer.semantic.api.MetricListPage {
            val remaining = metrics.sortedBy { it.definition.name.value }
                .dropWhile { after != null && it.definition.name.value <= after.name.value }
            val items = remaining.take(limit)
            return cc.monomer.semantic.api.MetricListPage(
                items,
                if (remaining.size > limit) {
                    cc.monomer.semantic.api.MetricListBoundary(items.last().definition.name)
                } else {
                    null
                },
            )
        }
        override suspend fun searchMetrics(workspaceId: WorkspaceId, query: String, cursor: MetricPageCursor?, pageSize: Int) =
            MetricPage(emptyList(), null)
        override suspend fun findLatestMetric(workspaceId: WorkspaceId) = metrics.maxByOrNull { it.updatedAt }
        override suspend fun findTimeSpineConfigurations(workspaceId: WorkspaceId) = emptyList<cc.monomer.semantic.api.SemanticTimeSpineConfigurationView>()
    }
}
