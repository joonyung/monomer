@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.domain

import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.QueryRunId
import cc.monomer.identity.api.WorkspaceId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant

/** The single workspace-shared working state for one Dashboard. */
data class DashboardDraft(
    val workspaceId: WorkspaceId,
    val dashboardId: DashboardId,
    val definition: DashboardDefinition,
    val parameterBindings: Map<String, JsonElement>,
    val variables: Map<String, JsonElement>,
    val authoringRevision: Long,
    val targetQueryRuns: Map<String, QueryRunId>,
    val readyResults: Map<String, DashboardDraftReadyResult>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastEditedAt: Instant?,
    val lastEditedBy: String?,
    val lastEditSurface: cc.monomer.identity.api.CredentialSurface?,
    val lastEditAgentClientId: cc.monomer.identity.api.AgentClientId?,
) {
    init {
        require(authoringRevision >= 0)
        cc.monomer.identity.api.requireRecordedAgentClient(lastEditSurface, lastEditAgentClientId)
        require(parameterBindings.keys == definition.parameters.keys) {
            "draft parameter bindings must be complete"
        }
        require(variables.keys == definition.variables.keys) {
            "draft variables must be complete"
        }
        require(targetQueryRuns.keys.all { it in definition.queries })
        require(readyResults.keys.all { it in definition.widgets })
        require(!updatedAt.isBefore(createdAt))
        definition.parameters.forEach { (name, declaration) ->
            DashboardValueValidator.requireValid(
                parameterBindings.getValue(name),
                declaration.type,
                declaration.nullable,
                "parameter $name",
            )
        }
        definition.variables.forEach { (name, declaration) ->
            DashboardValueValidator.requireValid(
                variables.getValue(name),
                declaration.type,
                declaration.nullable,
                "variable $name",
            )
        }
        readyResults.values.forEach { ready ->
            require(ready.authoringRevision <= authoringRevision)
            require(ready.parameterBindings.keys == definition.parameters.keys)
            require(ready.variables.keys == definition.variables.keys)
            require(ready.queryRuns.isNotEmpty())
        }
    }

    fun replaceDefinition(
        expectedRevision: Long,
        nextDefinition: DashboardDefinition,
        nextParameterBindings: Map<String, JsonElement>,
        at: Instant,
    ): DraftMutationResult {
        require(expectedRevision >= 0)
        if (expectedRevision != authoringRevision) return DraftMutationResult.Conflict(this)
        require(nextParameterBindings.keys == nextDefinition.parameters.keys) {
            "draft parameter bindings must contain every declared parameter exactly once"
        }
        nextDefinition.parameters.forEach { (name, declaration) ->
            DashboardValueValidator.requireValid(
                nextParameterBindings.getValue(name),
                declaration.type,
                declaration.nullable,
                "parameter $name",
            )
        }
        val nextVariables = nextDefinition.variables.mapValues { (name, declaration) ->
            val previousDeclaration = definition.variables[name]
            val previousValue = variables[name]
            if (previousDeclaration != null && previousValue != null &&
                previousDeclaration.type == declaration.type &&
                previousDeclaration.nullable == declaration.nullable
            ) {
                previousValue
            } else {
                resolveDashboardInitialValue(declaration.initial, nextParameterBindings, nextDefinition.parameters)
            }
        }
        if (nextDefinition == definition && nextParameterBindings == parameterBindings) {
            return DraftMutationResult.Applied(this)
        }
        val compatibleReadyResults = readyResults.filter { (widgetId, ready) ->
            widgetId in nextDefinition.widgets &&
                ready.parameterBindings.keys == nextDefinition.parameters.keys &&
                ready.variables.keys == nextDefinition.variables.keys
        }
        return DraftMutationResult.Applied(
            copy(
                definition = nextDefinition,
                parameterBindings = nextParameterBindings.toMap(),
                variables = nextVariables,
                authoringRevision = authoringRevision + 1,
                updatedAt = at,
                targetQueryRuns = targetQueryRuns.filterKeys { it in nextDefinition.queries },
                readyResults = compatibleReadyResults,
            ),
        )
    }

    /**
     * Resets the working definition to the applied one. An input keeps its Draft value when its declaration is
     * unchanged; a changed nullable input becomes null, and a changed required input has no value to restore.
     */
    fun discard(
        expectedRevision: Long,
        appliedDefinition: DashboardDefinition,
        at: Instant,
    ): DraftMutationResult {
        require(expectedRevision >= 0)
        if (expectedRevision != authoringRevision) return DraftMutationResult.Conflict(this)
        val restoredBindings = appliedDefinition.parameters.mapValues { (name, declaration) ->
            val draftDeclaration = definition.parameters[name]
            val draftValue = parameterBindings[name]
            when {
                draftDeclaration != null && draftValue != null &&
                    draftDeclaration.type == declaration.type && draftDeclaration.nullable == declaration.nullable -> draftValue
                declaration.nullable -> JsonNull
                else -> throw IllegalArgumentException(
                    "The applied input $name has no value in the Draft. Restore input $name in the Draft, then discard again.",
                )
            }
        }
        return replaceDefinition(expectedRevision, appliedDefinition, restoredBindings, at)
    }

    fun replaceDocument(
        expectedRevision: Long,
        nextDefinition: DashboardDefinition,
        nextParameterBindings: Map<String, JsonElement>,
        nextVariables: Map<String, JsonElement>,
        at: Instant,
    ): DraftMutationResult {
        require(expectedRevision >= 0)
        if (expectedRevision != authoringRevision) return DraftMutationResult.Conflict(this)
        require(nextParameterBindings.keys == nextDefinition.parameters.keys) {
            "draft parameter bindings must contain every declared parameter exactly once"
        }
        require(nextVariables.keys == nextDefinition.variables.keys) {
            "draft variables must contain every declared variable exactly once"
        }
        nextParameterBindings.forEach { (name, value) ->
            val declaration = nextDefinition.parameters.getValue(name)
            DashboardValueValidator.requireValid(value, declaration.type, declaration.nullable, "parameter $name")
        }
        nextDefinition.variables.forEach { (name, declaration) ->
            DashboardValueValidator.requireValid(nextVariables.getValue(name), declaration.type, declaration.nullable, "variable $name")
        }
        if (
            nextDefinition == definition &&
            nextParameterBindings == parameterBindings &&
            nextVariables == variables
        ) {
            return DraftMutationResult.Applied(this)
        }
        val compatibleReadyResults = readyResults.filter { (widgetId, ready) ->
            widgetId in nextDefinition.widgets &&
                ready.parameterBindings.keys == nextDefinition.parameters.keys &&
                ready.variables.keys == nextDefinition.variables.keys
        }
        return DraftMutationResult.Applied(
            copy(
                definition = nextDefinition,
                parameterBindings = nextParameterBindings.toMap(),
                variables = nextVariables.toMap(),
                authoringRevision = authoringRevision + 1,
                updatedAt = at,
                targetQueryRuns = targetQueryRuns.filterKeys { it in nextDefinition.queries },
                readyResults = compatibleReadyResults,
            ),
        )
    }

    fun setParameter(
        name: String,
        value: JsonElement,
        expectedRevision: Long,
        at: Instant,
    ): DraftMutationResult {
        require(expectedRevision >= 0)
        if (expectedRevision != authoringRevision) return DraftMutationResult.Conflict(this)
        val declaration = definition.parameters[name] ?: error("unknown parameter: $name")
        DashboardValueValidator.requireValid(value, declaration.type, declaration.nullable, "parameter $name")
        if (parameterBindings[name] == value) return DraftMutationResult.Applied(this)
        return DraftMutationResult.Applied(
            copy(
                parameterBindings = parameterBindings + (name to value),
                authoringRevision = authoringRevision + 1,
                updatedAt = at,
            ),
        )
    }

    fun withTargets(targets: Map<String, QueryRunId>, at: Instant): DashboardDraft =
        copy(targetQueryRuns = targets.toMap(), updatedAt = at)

    fun withReadyResults(
        readyResults: Map<String, DashboardDraftReadyResult>,
        at: Instant,
    ): DashboardDraft = copy(readyResults = readyResults.toMap(), updatedAt = at)

    fun currentVariablesObject(): JsonObject = buildJsonObject {
        variables.forEach { (name, value) -> put(name, value) }
    }

    companion object {
        fun start(
            workspaceId: WorkspaceId,
            dashboardId: DashboardId,
            definition: DashboardDefinition,
            parameterBindings: Map<String, JsonElement>,
            startedAt: Instant,
        ): DashboardDraft {
            require(parameterBindings.keys == definition.parameters.keys) {
                "draft parameter bindings must contain every declared parameter exactly once"
            }
            definition.parameters.forEach { (name, declaration) ->
                DashboardValueValidator.requireValid(
                    parameterBindings.getValue(name),
                    declaration.type,
                    declaration.nullable,
                    "parameter $name",
                )
            }
            val variables = definition.variables.mapValues { (_, declaration) ->
                resolveDashboardInitialValue(declaration.initial, parameterBindings, definition.parameters)
            }
            return DashboardDraft(
                workspaceId = workspaceId,
                dashboardId = dashboardId,
                definition = definition,
                parameterBindings = parameterBindings.toMap(),
                variables = variables,
                authoringRevision = 0,
                targetQueryRuns = emptyMap(),
                readyResults = emptyMap(),
                lastEditedAt = null,
                lastEditedBy = null,
                lastEditSurface = null,
                lastEditAgentClientId = null,
                createdAt = startedAt,
                updatedAt = startedAt,
            )
        }
    }
}

data class DashboardDraftReadyResult(
    val authoringRevision: Long,
    val parameterBindings: Map<String, JsonElement>,
    val variables: Map<String, JsonElement>,
    val queryRuns: Map<String, QueryRunId>,
) {
    init {
        require(authoringRevision >= 0)
        require(queryRuns.isNotEmpty())
    }
}

sealed interface DraftMutationResult {
    data class Applied(val draft: DashboardDraft) : DraftMutationResult
    data class Conflict(val current: DashboardDraft) : DraftMutationResult
}

internal fun resolveDashboardInitialValue(
    value: JsonElement,
    parameters: Map<String, JsonElement>,
    declarations: Map<String, DashboardParameterDefinition>,
): JsonElement =
    if (value is JsonObject && value["kind"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } == "PARAMETER") {
        val name = value.getValue("name").let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        require(name in parameters && name in declarations) { "unknown initial parameter: $name" }
        parameters.getValue(name)
    } else value
