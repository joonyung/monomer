@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.semantic.application

import cc.monomer.semantic.api.*
import cc.monomer.identity.api.AuthorizedMutationContext
import cc.monomer.identity.api.MutationIntent
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.MetricListBoundary
import cc.monomer.semantic.api.MetricView
import cc.monomer.semantic.api.PutMetricCommand
import cc.monomer.semantic.api.PutSemanticModelCommand
import cc.monomer.semantic.api.SemanticAuthorityUseCase
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticInvalidReason
import cc.monomer.semantic.api.SemanticInvalidReasonCode
import cc.monomer.semantic.api.SemanticModelView
import cc.monomer.semantic.api.SemanticMutationResult
import cc.monomer.semantic.api.SemanticModelId
import cc.monomer.semantic.api.SemanticModelListBoundary
import cc.monomer.semantic.api.SemanticModelPageCursor
import cc.monomer.semantic.api.MetricPageCursor
import cc.monomer.semantic.domain.CurrentMetric
import cc.monomer.semantic.domain.CurrentSemanticModel
import cc.monomer.semantic.domain.SemanticMutationKind
import cc.monomer.semantic.domain.SemanticMutationReceipt
import cc.monomer.semantic.api.SemanticSearchProjection
import cc.monomer.semantic.api.toSearchFields
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.semantic.api.SemanticDefinitionChange
import kotlin.uuid.Uuid
import java.time.Clock
import java.time.Instant
import kotlinx.serialization.json.*

interface SemanticTransactionPort {
    suspend fun <T> inSnapshotTransaction(deadline: Instant, block: suspend () -> T): T
    suspend fun <T> inSnapshotTransaction(block: () -> T): T
    suspend fun <T> inMutationTransaction(block: () -> T): T
}

interface SemanticRepository {
    fun appendDefinitionChange(workspaceId: WorkspaceId, definitionId: Uuid, version: SemanticDefinitionVersion)
    fun findDefinitionVersion(workspaceId: WorkspaceId, kind: SemanticDefinitionKind, definitionId: Uuid, changeId: Uuid): SemanticDefinitionVersion?
    fun readDefinitionVersions(workspaceId: WorkspaceId, kind: SemanticDefinitionKind, definitionId: Uuid, before: SemanticHistoryBoundary?, limit: Int): List<SemanticDefinitionVersion>
    fun deleteModel(workspaceId: WorkspaceId, semanticModelId: SemanticModelId)
    fun deleteMetric(workspaceId: WorkspaceId, metricId: MetricId)
    fun readRecentModels(
        workspaceId: WorkspaceId, before: Instant?, beforeId: Uuid?, actorId: PrincipalId?, credentialSurface: CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?, limit: Int,
    ): List<SemanticDefinitionChange>
    fun readRecentMetrics(
        workspaceId: WorkspaceId, before: Instant?, beforeId: Uuid?, actorId: PrincipalId?, credentialSurface: CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?, limit: Int,
    ): List<SemanticDefinitionChange>
    fun readRecentAgentClients(workspaceId: WorkspaceId, scanLimit: Int): List<cc.monomer.identity.api.RecentAgentClient>
    fun readModelChange(workspaceId: WorkspaceId, semanticModelId: SemanticModelId): SemanticDefinitionChange?
    fun readMetricChange(workspaceId: WorkspaceId, metricId: MetricId): SemanticDefinitionChange?

    fun findModel(id: SemanticModelId): CurrentSemanticModel?
    fun findModelByName(workspaceId: WorkspaceId, name: SemanticElementName): CurrentSemanticModel?
    fun findModels(workspaceId: WorkspaceId): List<CurrentSemanticModel>
    fun listModels(
        workspaceId: WorkspaceId,
        after: SemanticModelListBoundary?,
        limit: Int,
    ): List<CurrentSemanticModel>
    fun searchModels(
        workspaceId: WorkspaceId,
        query: String,
        cursor: SemanticModelPageCursor?,
        limit: Int,
    ): List<SemanticModelSearchRow>
    fun findLatestModel(workspaceId: WorkspaceId): CurrentSemanticModel?
    fun findMetric(id: MetricId): CurrentMetric?
    fun findMetricByName(workspaceId: WorkspaceId, name: SemanticElementName): CurrentMetric?
    fun findMetrics(workspaceId: WorkspaceId): List<CurrentMetric>
    fun listMetrics(
        workspaceId: WorkspaceId,
        after: MetricListBoundary?,
        limit: Int,
    ): List<CurrentMetric>
    fun searchMetrics(
        workspaceId: WorkspaceId,
        query: String,
        cursor: MetricPageCursor?,
        limit: Int,
    ): List<MetricSearchRow>
    fun findLatestMetric(workspaceId: WorkspaceId): CurrentMetric?

    fun lockWorkspaceMutation(workspaceId: WorkspaceId)
    fun findMutationReceipt(workspaceId: WorkspaceId, idempotencyKey: String): SemanticMutationReceipt?
    fun insertMutationReceipt(receipt: SemanticMutationReceipt)

    fun insertModel(model: CurrentSemanticModel, actorId: PrincipalId?, credentialSurface: CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?)
    fun updateModel(model: CurrentSemanticModel, actorId: PrincipalId?, credentialSurface: CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?)
    fun insertMetric(metric: CurrentMetric, actorId: PrincipalId?, credentialSurface: CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?)
    fun updateMetric(metric: CurrentMetric, actorId: PrincipalId?, credentialSurface: CredentialSurface?, agentClientId: cc.monomer.identity.api.AgentClientId?)
}

data class SemanticModelSearchRow(
    val model: CurrentSemanticModel,
    val normalizedLabel: String,
    val normalizedName: String,
)

data class MetricSearchRow(
    val metric: CurrentMetric,
    val normalizedLabel: String,
    val normalizedName: String,
)

/** Current-row authority. Dependency resolution is deliberately request-local in query rendering. */
class SemanticAuthorityService(
    private val repository: SemanticRepository,
    private val transactionPort: SemanticTransactionPort,
    private val clock: Clock,
    private val searchProjection: SemanticSearchProjection,
) : SemanticAuthorityUseCase, SemanticSeedDefinitionUseCase {
    override suspend fun putModel(command: PutSemanticModelCommand): SemanticMutationResult<SemanticModelWriteResult> {
        val intent = if (command.semanticModelId == null) MutationIntent.SEMANTIC_MODEL_CREATE else MutationIntent.SEMANTIC_MODEL_UPDATE
        if (!command.authorization.admits(intent)) return SemanticMutationResult.InvalidAuthorization
        return transactionPort.inMutationTransaction {
            repository.lockWorkspaceMutation(command.authorization.workspaceId)
            saveModel(command, null, command.authorization.recordedAttribution())
        }
    }

    override suspend fun putMetric(command: PutMetricCommand): SemanticMutationResult<MetricView> {
        val intent = if (command.metricId == null) MutationIntent.METRIC_CREATE else MutationIntent.METRIC_UPDATE
        if (!command.authorization.admits(intent)) return SemanticMutationResult.InvalidAuthorization
        return transactionPort.inMutationTransaction {
            repository.lockWorkspaceMutation(command.authorization.workspaceId)
            saveMetric(command, null, command.authorization.recordedAttribution())
        }
    }

    override suspend fun seedModel(command: PutSemanticModelCommand): SemanticMutationResult<SemanticModelWriteResult> {
        if (command.semanticModelId != null || !command.authorization.admits(MutationIntent.SEMANTIC_MODEL_CREATE)) {
            return SemanticMutationResult.InvalidAuthorization
        }
        return transactionPort.inMutationTransaction {
            repository.lockWorkspaceMutation(command.authorization.workspaceId)
            saveModel(command, null, RecordedAttribution.UNRECORDED)
        }
    }

    override suspend fun seedMetric(command: PutMetricCommand): SemanticMutationResult<MetricView> {
        if (command.metricId != null || !command.authorization.admits(MutationIntent.METRIC_CREATE)) {
            return SemanticMutationResult.InvalidAuthorization
        }
        return transactionPort.inMutationTransaction {
            repository.lockWorkspaceMutation(command.authorization.workspaceId)
            saveMetric(command, null, RecordedAttribution.UNRECORDED)
        }
    }

    override suspend fun restoreModel(command: RestoreSemanticModelCommand): SemanticMutationResult<SemanticModelView> {
        if (!command.authorization.admits(MutationIntent.SEMANTIC_MODEL_UPDATE)) return SemanticMutationResult.InvalidAuthorization
        return transactionPort.inMutationTransaction {
            val workspaceId = command.authorization.workspaceId
            repository.lockWorkspaceMutation(workspaceId)
            val version = repository.findDefinitionVersion(workspaceId, SemanticDefinitionKind.SEMANTIC_MODEL, command.semanticModelId.value, command.changeId)
                ?: return@inMutationTransaction SemanticMutationResult.NotFoundOrOutsideWorkspace
            val definition = (version.definition as SemanticDefinitionSnapshot.Model).definition
            when (val result = saveModel(PutSemanticModelCommand(command.authorization, command.idempotencyKey, command.semanticModelId, definition, emptyList()),
                command.changeId, command.authorization.recordedAttribution())) {
                is SemanticMutationResult.Committed -> SemanticMutationResult.Committed(result.value.model)
                is SemanticMutationResult.Invalid -> result
                is SemanticMutationResult.AggregateMetricNameConflict -> result
                SemanticMutationResult.Conflict -> SemanticMutationResult.Conflict
                SemanticMutationResult.IdempotencyConflict -> SemanticMutationResult.IdempotencyConflict
                SemanticMutationResult.NotFoundOrOutsideWorkspace -> SemanticMutationResult.NotFoundOrOutsideWorkspace
                SemanticMutationResult.InvalidAuthorization -> SemanticMutationResult.InvalidAuthorization
            }
        }
    }

    override suspend fun restoreMetric(command: RestoreMetricCommand): SemanticMutationResult<MetricView> {
        if (!command.authorization.admits(MutationIntent.METRIC_UPDATE)) return SemanticMutationResult.InvalidAuthorization
        return transactionPort.inMutationTransaction {
            val workspaceId = command.authorization.workspaceId
            repository.lockWorkspaceMutation(workspaceId)
            val version = repository.findDefinitionVersion(workspaceId, SemanticDefinitionKind.METRIC, command.metricId.value, command.changeId)
                ?: return@inMutationTransaction SemanticMutationResult.NotFoundOrOutsideWorkspace
            val definition = (version.definition as SemanticDefinitionSnapshot.Metric).definition
            saveMetric(PutMetricCommand(command.authorization, command.idempotencyKey, command.metricId, definition), command.changeId,
                command.authorization.recordedAttribution())
        }
    }

    override suspend fun deleteModel(command: DeleteSemanticModelCommand): SemanticMutationResult<Unit> {
        if (!command.authorization.admits(MutationIntent.SEMANTIC_MODEL_DELETE)) return SemanticMutationResult.InvalidAuthorization
        return transactionPort.inMutationTransaction {
            val workspaceId = command.authorization.workspaceId
            repository.lockWorkspaceMutation(workspaceId)
            val existing = repository.findModel(command.semanticModelId)?.takeIf { it.workspaceId == workspaceId }
                ?: return@inMutationTransaction SemanticMutationResult.NotFoundOrOutsideWorkspace
            repository.deleteModel(workspaceId, existing.id)
            searchProjection.deleteModel(workspaceId, existing.definition.name.value)
            record(workspaceId, command.authorization.recordedAttribution(), existing.id.value, SemanticDefinitionSnapshot.Model(existing.definition),
                SemanticDefinitionChangeAction.DELETED, clock.instant())
            SemanticMutationResult.Committed(Unit)
        }
    }

    override suspend fun deleteMetric(command: DeleteMetricCommand): SemanticMutationResult<Unit> {
        if (!command.authorization.admits(MutationIntent.METRIC_DELETE)) return SemanticMutationResult.InvalidAuthorization
        return transactionPort.inMutationTransaction {
            val workspaceId = command.authorization.workspaceId
            repository.lockWorkspaceMutation(workspaceId)
            val existing = repository.findMetric(command.metricId)?.takeIf { it.workspaceId == workspaceId }
                ?: return@inMutationTransaction SemanticMutationResult.NotFoundOrOutsideWorkspace
            repository.deleteMetric(workspaceId, existing.id)
            searchProjection.deleteMetric(workspaceId, existing.definition.name.value)
            record(workspaceId, command.authorization.recordedAttribution(), existing.id.value, SemanticDefinitionSnapshot.Metric(existing.definition),
                SemanticDefinitionChangeAction.DELETED, clock.instant())
            SemanticMutationResult.Committed(Unit)
        }
    }

    private fun saveModel(
        command: PutSemanticModelCommand,
        restoringChangeId: Uuid?,
        attribution: RecordedAttribution,
    ): SemanticMutationResult<SemanticModelWriteResult> {
        val workspaceId = command.authorization.workspaceId
        val creating = command.semanticModelId == null
        val subject = command.semanticModelId?.let { "SEMANTIC_MODEL:$it" } ?: "SEMANTIC_MODEL:${command.definition.name.value}"
        val binding = MutationBinding(workspaceId, command.idempotencyKey, command.authorization.actor.id,
            if (creating) SemanticMutationKind.SEMANTIC_MODEL_CREATE else SemanticMutationKind.SEMANTIC_MODEL_UPDATE,
            if (restoringChangeId == null) subject else "$subject:restore:$restoringChangeId")
        repository.findMutationReceipt(workspaceId, command.idempotencyKey)?.let { return replayModel(it, binding, command.aggregateMetrics) }
        val existing = command.semanticModelId?.let(repository::findModel)
        if ((existing != null && existing.workspaceId != workspaceId) ||
            (command.semanticModelId != null && existing == null && restoringChangeId == null)) {
            return SemanticMutationResult.NotFoundOrOutsideWorkspace
        }
        if (existing == null && repository.findModelByName(workspaceId, command.definition.name) != null) return SemanticMutationResult.Conflict
        if (existing != null && existing.definition.name != command.definition.name) return invalid(SemanticInvalidReasonCode.MODEL_NAME_IMMUTABLE, "name")
        val requestedNames = mutableSetOf<SemanticElementName>()
        val measureNames = command.definition.measures.map { it.name }.toSet()
        for ((index, metric) in command.aggregateMetrics.withIndex()) {
            for ((field, value) in listOf("label" to metric.label, "description" to metric.description)) {
                if (value != null && (value.isBlank() || '\u0000' in value)) {
                    return invalid(SemanticInvalidReasonCode.AGGREGATE_METRIC_INVALID, "aggregateMetrics[$index].$field")
                }
            }
            if (metric.filters.size > 32) return invalid(SemanticInvalidReasonCode.AGGREGATE_METRIC_INVALID, "aggregateMetrics[$index].filters")
            if (metric.measure !in measureNames) {
                return invalid(SemanticInvalidReasonCode.AGGREGATE_MEASURE_NOT_FOUND, "aggregateMetrics[$index].measure")
            }
            if (!requestedNames.add(metric.name) || repository.findMetricByName(workspaceId, metric.name) != null) {
                return SemanticMutationResult.AggregateMetricNameConflict("aggregateMetrics[$index].name")
            }
        }
        val metricDefinitions = command.aggregateMetrics.map { metric ->
            SemanticMetricDefinition.Simple(3u, metric.name, metric.label, metric.description,
                metric.timeGranularity, metric.filters, command.definition.name, metric.measure, display = null)
        }
        val now = clock.instant()
        val current = CurrentSemanticModel(existing?.id ?: command.semanticModelId ?: SemanticModelId.generate(), workspaceId,
            command.definition, existing?.createdAt ?: now, now)
        if (existing == null) repository.insertModel(current, attribution.actorId, attribution.credentialSurface, attribution.agentClientId)
        else repository.updateModel(current, attribution.actorId, attribution.credentialSurface, attribution.agentClientId)
        val view = current.toView()
        searchProjection.upsertModel(view.toSearchFields())
        record(workspaceId, attribution, current.id.value, SemanticDefinitionSnapshot.Model(current.definition),
            if (existing == null) SemanticDefinitionChangeAction.CREATED else SemanticDefinitionChangeAction.UPDATED, now)
        val aggregateMetrics = metricDefinitions.map { definition ->
            val metric = CurrentMetric(MetricId.generate(), workspaceId, definition, now, now)
            repository.insertMetric(metric, attribution.actorId, attribution.credentialSurface, attribution.agentClientId)
            val metricView = metric.toView()
            searchProjection.upsertMetric(metricView.toSearchFields())
            record(workspaceId, attribution, metric.id.value, SemanticDefinitionSnapshot.Metric(definition), SemanticDefinitionChangeAction.CREATED, now)
            metricView
        }
        val receipt = binding.receipt(current.id.value, now)
        repository.insertMutationReceipt(if (aggregateMetrics.isEmpty()) receipt else receipt.copy(subject = buildJsonObject {
            put("subject", binding.subject)
            put("aggregateMetrics", aggregateMetricBinding(command.aggregateMetrics))
            put("metricIds", JsonArray(aggregateMetrics.map { JsonPrimitive(it.metricId.value.toString()) }))
        }.toString()))
        return SemanticMutationResult.Committed(SemanticModelWriteResult(view, aggregateMetrics))
    }

    private fun saveMetric(
        command: PutMetricCommand,
        restoringChangeId: Uuid?,
        attribution: RecordedAttribution,
    ): SemanticMutationResult<MetricView> {
        val workspaceId = command.authorization.workspaceId
        val creating = command.metricId == null
        val subject = command.metricId?.let { "METRIC:$it" } ?: "METRIC:${command.definition.name.value}"
        val binding = MutationBinding(workspaceId, command.idempotencyKey, command.authorization.actor.id,
            if (creating) SemanticMutationKind.METRIC_CREATE else SemanticMutationKind.METRIC_UPDATE,
            if (restoringChangeId == null) subject else "$subject:restore:$restoringChangeId")
        repository.findMutationReceipt(workspaceId, command.idempotencyKey)?.let { return replayMetric(it, binding) }
        val existing = command.metricId?.let(repository::findMetric)
        if ((existing != null && existing.workspaceId != workspaceId) ||
            (command.metricId != null && existing == null && restoringChangeId == null)) {
            return SemanticMutationResult.NotFoundOrOutsideWorkspace
        }
        if (existing == null && repository.findMetricByName(workspaceId, command.definition.name) != null) return SemanticMutationResult.Conflict
        if (existing != null && existing.definition.name != command.definition.name) return invalid(SemanticInvalidReasonCode.METRIC_NAME_IMMUTABLE, "name")
        val now = clock.instant()
        val current = CurrentMetric(existing?.id ?: command.metricId ?: MetricId.generate(), workspaceId,
            command.definition, existing?.createdAt ?: now, now)
        if (existing == null) repository.insertMetric(current, attribution.actorId, attribution.credentialSurface, attribution.agentClientId)
        else repository.updateMetric(current, attribution.actorId, attribution.credentialSurface, attribution.agentClientId)
        val view = current.toView()
        searchProjection.upsertMetric(view.toSearchFields())
        record(workspaceId, attribution, current.id.value, SemanticDefinitionSnapshot.Metric(current.definition),
            if (existing == null) SemanticDefinitionChangeAction.CREATED else SemanticDefinitionChangeAction.UPDATED, now)
        repository.insertMutationReceipt(binding.receipt(current.id.value, now))
        return SemanticMutationResult.Committed(view)
    }

    private fun record(workspaceId: WorkspaceId, attribution: RecordedAttribution, definitionId: Uuid, definition: SemanticDefinitionSnapshot,
        action: SemanticDefinitionChangeAction, occurredAt: Instant) {
        repository.appendDefinitionChange(workspaceId, definitionId,
            SemanticDefinitionVersion(Uuid.generateV7(), action, definition, occurredAt, attribution.actorId,
                attribution.credentialSurface, attribution.agentClientId, false))
    }

    private fun AuthorizedMutationContext.admits(intent: MutationIntent) = role >= WorkspaceRole.EDITOR && this.intent == intent

    private fun AuthorizedMutationContext.recordedAttribution() = RecordedAttribution(actor.id, credentialSurface, agentClientId)

    /** Who current rows and definition changes name; the receipt always keys on the authorization actor. */
    private data class RecordedAttribution(
        val actorId: PrincipalId?,
        val credentialSurface: CredentialSurface?,
        val agentClientId: cc.monomer.identity.api.AgentClientId?,
    ) {
        companion object {
            val UNRECORDED = RecordedAttribution(null, null, null)
        }
    }

    private fun replayModel(
        receipt: SemanticMutationReceipt,
        binding: MutationBinding,
        aggregateMetrics: List<SemanticAggregateMetric>,
    ): SemanticMutationResult<SemanticModelWriteResult> {
        val metricIds = if (aggregateMetrics.isEmpty()) {
            if (!receipt.matches(binding)) return SemanticMutationResult.IdempotencyConflict
            emptyList()
        } else {
            val subject = runCatching { Json.parseToJsonElement(receipt.subject).jsonObject }.getOrNull()
                ?: return SemanticMutationResult.IdempotencyConflict
            if (subject.keys != setOf("subject", "aggregateMetrics", "metricIds") ||
                subject["aggregateMetrics"] != aggregateMetricBinding(aggregateMetrics) ||
                !receipt.copy(subject = subject.getValue("subject").jsonPrimitive.content).matches(binding)) {
                return SemanticMutationResult.IdempotencyConflict
            }
            subject.getValue("metricIds").jsonArray.map { MetricId(Uuid.parse(it.jsonPrimitive.content)) }
                .also { check(it.size == aggregateMetrics.size) }
        }
        val current = repository.findModel(SemanticModelId(receipt.responseIdentity))
            ?.takeIf { it.workspaceId == binding.workspaceId }
            ?: return SemanticMutationResult.NotFoundOrOutsideWorkspace
        val metrics = metricIds.map { id ->
            repository.findMetric(id)?.takeIf { it.workspaceId == binding.workspaceId }?.toView()
                ?: return SemanticMutationResult.NotFoundOrOutsideWorkspace
        }
        return SemanticMutationResult.Committed(SemanticModelWriteResult(current.toView(), metrics))
    }

    private fun aggregateMetricBinding(metrics: List<SemanticAggregateMetric>) = JsonArray(metrics.map { metric ->
        buildJsonObject {
            put("measure", metric.measure.value)
            put("name", metric.name.value)
            put("label", metric.label?.let(::JsonPrimitive) ?: JsonNull)
            put("description", metric.description?.let(::JsonPrimitive) ?: JsonNull)
            put("timeGranularity", metric.timeGranularity?.name?.let(::JsonPrimitive) ?: JsonNull)
            put("filters", JsonArray(metric.filters.map(::metricFilterBinding)))
        }
    })

    private fun metricFilterBinding(filter: SemanticMetricFilter): JsonObject = buildJsonObject {
        when (filter) {
            is SemanticMetricFilter.Predicate -> {
                put("dimension", buildJsonObject {
                    put("semanticModelName", filter.dimension.semanticModelName.value)
                    put("name", filter.dimension.name.value)
                    put("granularity", filter.dimension.granularity?.name?.let(::JsonPrimitive) ?: JsonNull)
                })
                put("operator", filter.operator.name)
                put("values", JsonArray(filter.values.map(::JsonPrimitive)))
            }
            is SemanticMetricFilter.And -> put("and", JsonArray(filter.filters.map(::metricFilterBinding)))
            is SemanticMetricFilter.Or -> put("or", JsonArray(filter.filters.map(::metricFilterBinding)))
            is SemanticMetricFilter.Not -> put("not", metricFilterBinding(filter.filter))
        }
    }

    private fun replayMetric(
        receipt: SemanticMutationReceipt,
        binding: MutationBinding,
    ): SemanticMutationResult<MetricView> {
        if (!receipt.matches(binding)) return SemanticMutationResult.IdempotencyConflict
        val current = repository.findMetric(MetricId(receipt.responseIdentity))
            ?: return SemanticMutationResult.NotFoundOrOutsideWorkspace
        return SemanticMutationResult.Committed(current.toView())
    }

    private fun invalid(code: SemanticInvalidReasonCode, field: String?): SemanticMutationResult.Invalid =
        SemanticMutationResult.Invalid(listOf(SemanticInvalidReason(code, field)))

    private data class MutationBinding(
        val workspaceId: WorkspaceId,
        val idempotencyKey: String,
        val actorId: cc.monomer.identity.api.PrincipalId,
        val mutationKind: SemanticMutationKind,
        val subject: String,
    ) {
        fun receipt(responseIdentity: kotlin.uuid.Uuid, committedAt: Instant) =
            SemanticMutationReceipt(
                workspaceId = workspaceId,
                idempotencyKey = idempotencyKey,
                actorId = actorId,
                mutationKind = mutationKind,
                subject = subject,
                responseIdentity = responseIdentity,
                committedAt = committedAt,
            )
    }

    private fun SemanticMutationReceipt.matches(binding: MutationBinding): Boolean =
        workspaceId == binding.workspaceId &&
            idempotencyKey == binding.idempotencyKey &&
            actorId == binding.actorId &&
            mutationKind == binding.mutationKind &&
            subject == binding.subject
}

internal fun CurrentSemanticModel.toView() =
    SemanticModelView(id, workspaceId, definition, createdAt, updatedAt, emptyList())

internal fun CurrentMetric.toView() =
    MetricView(id, workspaceId, definition, createdAt, updatedAt, emptyList())
