@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.catalog.domain

import cc.monomer.catalog.api.CatalogListRefreshJobId
import cc.monomer.catalog.api.isCanonicalCatalogIdentifier
import cc.monomer.connector.api.ConnectorId
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import java.time.Instant

enum class CatalogListRefreshJobState {
    RUNNING,
    CANCEL_REQUESTED,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    CANCELLED,
}

enum class CatalogListParentKind { CONNECTOR, CATALOG, SCHEMA }

enum class CatalogListChildKind { CATALOG, SCHEMA, RELATION }

sealed interface CatalogListRefreshTarget {
    val key: String
    val parentKind: CatalogListParentKind
    val childKind: CatalogListChildKind
    val catalogName: String?
    val schemaName: String?

    data class Connector(val children: CatalogListChildKind) : CatalogListRefreshTarget {
        init { require(children == CatalogListChildKind.CATALOG || children == CatalogListChildKind.SCHEMA) }

        override val key: String = "connector"
        override val parentKind: CatalogListParentKind = CatalogListParentKind.CONNECTOR
        override val childKind: CatalogListChildKind = children
        override val catalogName: String? = null
        override val schemaName: String? = null
    }

    data class Catalog(val name: String) : CatalogListRefreshTarget {
        init { requireCanonical(name, "catalog") }

        override val key: String = targetKey("catalog", name)
        override val parentKind: CatalogListParentKind = CatalogListParentKind.CATALOG
        override val childKind: CatalogListChildKind = CatalogListChildKind.SCHEMA
        override val catalogName: String = name
        override val schemaName: String? = null
    }

    data class Schema(val catalog: String?, val name: String) : CatalogListRefreshTarget {
        init {
            require(catalog == null || catalog.isCanonicalCatalogIdentifier())
            requireCanonical(name, "schema")
        }

        override val key: String = targetKey("schema", catalog, name)
        override val parentKind: CatalogListParentKind = CatalogListParentKind.SCHEMA
        override val childKind: CatalogListChildKind = CatalogListChildKind.RELATION
        override val catalogName: String? = catalog
        override val schemaName: String = name
    }
}

data class CatalogListRefreshJob(
    val id: CatalogListRefreshJobId,
    val workspaceId: WorkspaceId,
    val connectorId: ConnectorId,
    val target: CatalogListRefreshTarget,
    val requestedBy: PrincipalId,
    val credentialSurface: cc.monomer.identity.api.CredentialSurface?,
    val agentClientId: cc.monomer.identity.api.AgentClientId?,
    val requestedAt: Instant,
    val state: CatalogListRefreshJobState,
    val runnerInstanceId: String?,
    val heartbeatAt: Instant?,
    val activeRunId: ExecutionRunId?,
    val failureCode: String?,
    val safeMessage: String?,
    val terminalAt: Instant?,
) {
    init {
        cc.monomer.identity.api.requireRecordedAgentClient(credentialSurface, agentClientId)
        require((runnerInstanceId == null) == (heartbeatAt == null))
        require(runnerInstanceId != null || activeRunId == null)
        require(state != CatalogListRefreshJobState.CANCEL_REQUESTED || runnerInstanceId != null)
        when (state) {
            CatalogListRefreshJobState.RUNNING,
            CatalogListRefreshJobState.CANCEL_REQUESTED,
            -> require(terminalAt == null)

            CatalogListRefreshJobState.COMPLETED -> {
                require(terminalAt != null)
                require(failureCode == null && safeMessage == null)
                require(activeRunId == null)
            }

            CatalogListRefreshJobState.COMPLETED_WITH_ERRORS -> {
                require(terminalAt != null)
                require(failureCode?.isNotBlank() == true)
                require(safeMessage?.isNotBlank() == true)
                require(activeRunId == null)
            }

            CatalogListRefreshJobState.CANCELLED -> {
                require(terminalAt != null)
                require(failureCode == null && safeMessage == null)
                require(activeRunId == null)
            }
        }
        if (state == CatalogListRefreshJobState.RUNNING || state == CatalogListRefreshJobState.CANCEL_REQUESTED) {
            require(failureCode == null && safeMessage == null)
        }
    }

    fun claim(instanceId: String, at: Instant): CatalogListRefreshJob {
        check(state == CatalogListRefreshJobState.RUNNING)
        check(runnerInstanceId == null && heartbeatAt == null)
        require(instanceId.isNotBlank())
        return copy(runnerInstanceId = instanceId, heartbeatAt = at)
    }

    fun requestCancellation(): CatalogListRefreshJob {
        check(state == CatalogListRefreshJobState.RUNNING)
        check(runnerInstanceId != null)
        return copy(state = CatalogListRefreshJobState.CANCEL_REQUESTED)
    }

    fun complete(at: Instant): CatalogListRefreshJob {
        check(state == CatalogListRefreshJobState.RUNNING)
        return copy(
            state = CatalogListRefreshJobState.COMPLETED,
            activeRunId = null,
            terminalAt = at,
        )
    }

    fun completeWithErrors(code: String, message: String, at: Instant): CatalogListRefreshJob {
        check(state == CatalogListRefreshJobState.RUNNING || state == CatalogListRefreshJobState.CANCEL_REQUESTED)
        require(code.isNotBlank())
        require(message.isNotBlank())
        return copy(
            state = CatalogListRefreshJobState.COMPLETED_WITH_ERRORS,
            activeRunId = null,
            failureCode = code,
            safeMessage = message,
            terminalAt = at,
        )
    }

    fun cancel(at: Instant): CatalogListRefreshJob {
        check(state == CatalogListRefreshJobState.RUNNING || state == CatalogListRefreshJobState.CANCEL_REQUESTED)
        return copy(
            state = CatalogListRefreshJobState.CANCELLED,
            activeRunId = null,
            terminalAt = at,
        )
    }

    companion object {
        fun request(
            id: CatalogListRefreshJobId,
            workspaceId: WorkspaceId,
            connectorId: ConnectorId,
            target: CatalogListRefreshTarget,
            requestedBy: PrincipalId,
            credentialSurface: cc.monomer.identity.api.CredentialSurface?,
            agentClientId: cc.monomer.identity.api.AgentClientId?,
            requestedAt: Instant,
        ): CatalogListRefreshJob = CatalogListRefreshJob(
            id = id,
            workspaceId = workspaceId,
            connectorId = connectorId,
            target = target,
            requestedBy = requestedBy,
            credentialSurface = credentialSurface,
            agentClientId = agentClientId,
            requestedAt = requestedAt,
            state = CatalogListRefreshJobState.RUNNING,
            runnerInstanceId = null,
            heartbeatAt = null,
            activeRunId = null,
            failureCode = null,
            safeMessage = null,
            terminalAt = null,
        )
    }
}

data class CatalogListOption(
    val name: String,
    val relationKind: cc.monomer.catalog.api.RelationKind?,
    val available: Boolean,
) {
    init { require(name.isCanonicalCatalogIdentifier()) }
}

data class CatalogListOptionRead(
    val fetchedAt: Instant?,
    val option: CatalogListOption?,
) {
    init { require(fetchedAt != null || option == null) }
}

data class CatalogListOptionPage(
    val target: CatalogListRefreshTarget,
    val fetchedAt: Instant?,
    val options: List<CatalogListOption>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int,
    val latestJob: CatalogListRefreshJob?,
) {
    init {
        require(page >= 1)
        require(pageSize in 1..200)
        require(totalCount >= 0)
        require(options.size <= pageSize)
    }
}
