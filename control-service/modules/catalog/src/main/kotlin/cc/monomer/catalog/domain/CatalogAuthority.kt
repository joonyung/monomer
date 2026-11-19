@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.domain

import cc.monomer.catalog.api.CatalogDigest
import cc.monomer.catalog.api.CatalogIngestEntry
import cc.monomer.catalog.api.CatalogIngestJobId
import cc.monomer.catalog.api.CatalogIngestTaskId
import cc.monomer.catalog.api.CatalogRelationshipKind
import cc.monomer.catalog.api.CatalogSelection
import cc.monomer.catalog.api.RelationKind
import cc.monomer.catalog.api.isCanonicalCatalogIdentifier
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.execution.api.ExecutionRunId
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Instant

enum class CatalogIngestJobState {
    RUNNING,
    CANCEL_REQUESTED,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    CANCELLED,
}

data class CatalogIngestJob(
    val id: CatalogIngestJobId,
    val workspaceId: WorkspaceId,
    val connectorId: ConnectorId,
    val selection: CatalogSelection,
    val entry: CatalogIngestEntry,
    val requestedBy: PrincipalId,
    val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
    val agentClientId: cc.monomer.identity.api.AgentClientId?,
    val requestedAt: Instant,
    val state: CatalogIngestJobState,
    val runnerInstanceId: String?,
    val heartbeatAt: Instant?,
    val terminalAt: Instant?,
) {
    init {
        cc.monomer.identity.api.requireRecordedAgentClient(credentialSurface, agentClientId)
        require(entry.connectorId == connectorId)
        require((runnerInstanceId == null) == (heartbeatAt == null))
        when (state) {
            CatalogIngestJobState.RUNNING,
            CatalogIngestJobState.CANCEL_REQUESTED,
            -> {
                require(terminalAt == null)
            }

            CatalogIngestJobState.COMPLETED,
            CatalogIngestJobState.COMPLETED_WITH_ERRORS,
            CatalogIngestJobState.CANCELLED,
            -> {
                require(terminalAt != null)
            }
        }
    }

    fun claim(instanceId: String, at: Instant): CatalogIngestJob {
        check(state == CatalogIngestJobState.RUNNING)
        check(runnerInstanceId == null && heartbeatAt == null)
        require(instanceId.isNotBlank())
        return copy(
            state = CatalogIngestJobState.RUNNING,
            runnerInstanceId = instanceId,
            heartbeatAt = at,
        )
    }

    fun requestCancellation(): CatalogIngestJob {
        check(state == CatalogIngestJobState.RUNNING)
        return copy(state = CatalogIngestJobState.CANCEL_REQUESTED)
    }

    fun complete(at: Instant): CatalogIngestJob {
        check(state == CatalogIngestJobState.RUNNING || state == CatalogIngestJobState.CANCEL_REQUESTED)
        return copy(state = CatalogIngestJobState.COMPLETED, terminalAt = at)
    }

    fun completeWithErrors(at: Instant): CatalogIngestJob {
        check(state == CatalogIngestJobState.RUNNING || state == CatalogIngestJobState.CANCEL_REQUESTED)
        return copy(state = CatalogIngestJobState.COMPLETED_WITH_ERRORS, terminalAt = at)
    }

    fun cancel(at: Instant): CatalogIngestJob {
        check(
            state == CatalogIngestJobState.RUNNING ||
                state == CatalogIngestJobState.CANCEL_REQUESTED,
        )
        return copy(
            state = CatalogIngestJobState.CANCELLED,
            terminalAt = at,
        )
    }

    companion object {
        fun request(
            id: CatalogIngestJobId,
            workspaceId: WorkspaceId,
            connectorId: ConnectorId,
            selection: CatalogSelection,
            entry: CatalogIngestEntry,
            requestedBy: PrincipalId,
            credentialSurface: cc.monomer.identity.api.CredentialSurface?,
            agentClientId: cc.monomer.identity.api.AgentClientId?,
            requestedAt: Instant,
        ): CatalogIngestJob = CatalogIngestJob(
            id = id,
            workspaceId = workspaceId,
            connectorId = connectorId,
            selection = selection,
            entry = entry,
            requestedBy = requestedBy,
            credentialSurface = credentialSurface,
            agentClientId = agentClientId,
            requestedAt = requestedAt,
            state = CatalogIngestJobState.RUNNING,
            runnerInstanceId = null,
            heartbeatAt = null,
            terminalAt = null,
        )
    }
}

data class CatalogIngestJobSummary(
    val job: CatalogIngestJob,
    val refreshedRelationCount: Int,
    val runningTaskCount: Int,
    val errorCount: Int,
) {
    init {
        require(refreshedRelationCount >= 0)
        require(runningTaskCount >= 0)
        require(errorCount >= 0)
    }
}

data class CatalogIngestJobPage(
    val jobs: List<CatalogIngestJobSummary>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int,
) {
    init {
        require(totalCount >= 0)
        require(page >= 1)
        require(pageSize in 1..200)
    }
}

data class CatalogIngestErrorPage(
    val errors: List<CatalogIngestTask>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int,
) {
    init {
        require(errors.all { it.state == CatalogIngestTaskState.FAILED })
        require(totalCount >= 0)
        require(page >= 1)
        require(pageSize in 1..200)
    }
}

sealed interface CatalogIngestTaskTarget {
    val key: String
    val catalog: String?
    val schema: String?
    val relation: String?

    data object Connector : CatalogIngestTaskTarget {
        override val key: String = "connector"
        override val catalog: String? = null
        override val schema: String? = null
        override val relation: String? = null
    }

    data class Catalog(val name: String) : CatalogIngestTaskTarget {
        init { requireCanonical(name, "catalog") }

        override val key: String = targetKey("catalog", name)
        override val catalog: String = name
        override val schema: String? = null
        override val relation: String? = null
    }

    data class Schema(val catalogName: String?, val name: String) : CatalogIngestTaskTarget {
        init {
            require(catalogName == null || catalogName.isCanonicalCatalogIdentifier())
            requireCanonical(name, "schema")
        }

        override val key: String = targetKey("schema", catalogName, name)
        override val catalog: String? = catalogName
        override val schema: String = name
        override val relation: String? = null
    }

    data class Relation(
        val catalogName: String?,
        val schemaName: String,
        val name: String,
        val kind: RelationKind,
    ) : CatalogIngestTaskTarget {
        init {
            require(catalogName == null || catalogName.isCanonicalCatalogIdentifier())
            requireCanonical(schemaName, "schema")
            requireCanonical(name, "relation")
        }

        override val key: String = targetKey("relation", catalogName, schemaName, name, kind.name)
        override val catalog: String? = catalogName
        override val schema: String = schemaName
        override val relation: String = name
    }
}

internal fun targetKey(kind: String, vararg components: String?): String =
    buildString {
        append(kind)
        components.forEach { component ->
            append(':')
            if (component == null) {
                append('-')
            } else {
                append(component.length)
                append(':')
                append(component)
            }
        }
    }

enum class CatalogIngestTaskState {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

data class CatalogIngestTask(
    val id: CatalogIngestTaskId,
    val jobId: CatalogIngestJobId,
    val parentTaskId: CatalogIngestTaskId?,
    val workspaceId: WorkspaceId,
    val target: CatalogIngestTaskTarget,
    val state: CatalogIngestTaskState,
    val activeRunId: ExecutionRunId?,
    val failureCode: String?,
    val safeMessage: String?,
    val failureRunId: ExecutionRunId?,
    val createdAt: Instant,
    val startedAt: Instant,
    val terminalAt: Instant?,
) {
    init {
        when (state) {
            CatalogIngestTaskState.RUNNING -> {
                require(terminalAt == null)
                require(failureCode == null && safeMessage == null && failureRunId == null)
            }

            CatalogIngestTaskState.SUCCEEDED -> {
                require(terminalAt != null && activeRunId == null)
                require(failureCode == null && safeMessage == null && failureRunId == null)
            }

            CatalogIngestTaskState.FAILED -> {
                require(terminalAt != null && activeRunId == null)
                require(failureCode?.isNotBlank() == true)
                require(safeMessage?.isNotBlank() == true)
            }

            CatalogIngestTaskState.CANCELLED -> {
                require(terminalAt != null && activeRunId == null)
                require(failureCode == null && failureRunId == null)
            }
        }
    }

    fun succeed(at: Instant): CatalogIngestTask {
        check(state == CatalogIngestTaskState.RUNNING)
        return copy(state = CatalogIngestTaskState.SUCCEEDED, activeRunId = null, terminalAt = at)
    }

    fun fail(
        code: String,
        message: String,
        at: Instant,
        runId: ExecutionRunId?,
    ): CatalogIngestTask {
        check(state == CatalogIngestTaskState.RUNNING)
        require(code.isNotBlank())
        require(message.isNotBlank())
        return copy(
            state = CatalogIngestTaskState.FAILED,
            activeRunId = null,
            failureCode = code,
            safeMessage = message,
            failureRunId = runId,
            terminalAt = at,
        )
    }

    fun cancel(message: String?, at: Instant): CatalogIngestTask {
        check(state == CatalogIngestTaskState.RUNNING)
        return copy(
            state = CatalogIngestTaskState.CANCELLED,
            activeRunId = null,
            safeMessage = message,
            terminalAt = at,
        )
    }

    companion object {
        /** A task row is created only when its branch starts work. */
        fun start(
            job: CatalogIngestJob,
            parentTaskId: CatalogIngestTaskId?,
            target: CatalogIngestTaskTarget,
            at: Instant,
        ): CatalogIngestTask {
            require(job.state == CatalogIngestJobState.RUNNING)
            require(job.runnerInstanceId != null && job.heartbeatAt != null)
            return CatalogIngestTask(
                id = CatalogIngestTaskId.generate(),
                jobId = job.id,
                parentTaskId = parentTaskId,
                workspaceId = job.workspaceId,
                target = target,
                state = CatalogIngestTaskState.RUNNING,
                activeRunId = null,
                failureCode = null,
                safeMessage = null,
                failureRunId = null,
                createdAt = at,
                startedAt = at,
                terminalAt = null,
            )
        }
    }
}

sealed interface CatalogMetadataObservation {
    val catalog: String?
    val schema: String

    data class Catalog(val name: String) : CatalogMetadataObservation {
        override val catalog: String = name
        override val schema: String = ""
    }

    data class Schema(override val catalog: String?, override val schema: String) : CatalogMetadataObservation

    data class Relation(
        override val catalog: String?,
        override val schema: String,
        val name: String,
        val relationKind: RelationKind,
        val definition: String?,
        val partitionKeys: List<String>?,
        val clusteringKeys: List<String>?,
        val requiresPartitionFilter: Boolean?,
    ) : CatalogMetadataObservation

    data class Column(
        override val catalog: String?,
        override val schema: String,
        val relation: String,
        val name: String,
        val ordinal: Int,
        val nativeType: String,
        val nullable: Boolean,
        val defaultExpression: String?,
    ) : CatalogMetadataObservation

    data class RelationshipMember(
        override val catalog: String?,
        override val schema: String,
        val sourceRelation: String,
        val constraintName: String,
        val relationshipKind: CatalogRelationshipKind,
        val memberOrdinal: Int,
        val sourceColumn: String,
        val referencedCatalog: String?,
        val referencedSchema: String?,
        val referencedRelation: String?,
        val referencedColumn: String?,
    ) : CatalogMetadataObservation
}

fun digestObservation(observation: CatalogMetadataObservation): CatalogDigest =
    canonicalDigest("monomer:catalog-observation:v2") {
        writeOptionalText(observation.catalog)
        writeText(observation.schema)
        when (observation) {
            is CatalogMetadataObservation.Catalog -> {
                writeInt(0)
                writeText(observation.name)
            }

            is CatalogMetadataObservation.Schema -> writeInt(1)
            is CatalogMetadataObservation.Relation -> {
                writeInt(2)
                writeText(observation.name)
                writeInt(observation.relationKind.ordinal)
                writeOptionalText(observation.definition)
                writeOptionalTextList(observation.partitionKeys)
                writeOptionalTextList(observation.clusteringKeys)
                writeBoolean(observation.requiresPartitionFilter != null)
                observation.requiresPartitionFilter?.let(::writeBoolean)
            }

            is CatalogMetadataObservation.Column -> {
                writeInt(3)
                writeText(observation.relation)
                writeText(observation.name)
                writeInt(observation.ordinal)
                writeText(observation.nativeType)
                writeBoolean(observation.nullable)
                writeOptionalText(observation.defaultExpression)
            }

            is CatalogMetadataObservation.RelationshipMember -> {
                writeInt(4)
                writeText(observation.sourceRelation)
                writeText(observation.constraintName)
                writeInt(observation.relationshipKind.ordinal)
                writeInt(observation.memberOrdinal)
                writeText(observation.sourceColumn)
                writeOptionalText(observation.referencedCatalog)
                writeOptionalText(observation.referencedSchema)
                writeOptionalText(observation.referencedRelation)
                writeOptionalText(observation.referencedColumn)
            }
        }
    }

internal fun requireCanonical(value: String, label: String) {
    require(value.isCanonicalCatalogIdentifier()) { "$label must be canonical" }
}

private fun canonicalDigest(domain: String, block: DataOutputStream.() -> Unit): CatalogDigest {
    val payload = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeText(domain)
            output.block()
        }
        buffer.toByteArray()
    }
    val value = MessageDigest.getInstance("SHA-256").digest(payload)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return CatalogDigest("sha256:$value")
}

private fun DataOutputStream.writeText(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataOutputStream.writeOptionalText(value: String?) {
    writeBoolean(value != null)
    if (value != null) writeText(value)
}

private fun DataOutputStream.writeOptionalTextList(values: List<String>?) {
    writeBoolean(values != null)
    if (values != null) {
        writeInt(values.size)
        values.forEach(::writeText)
    }
}
