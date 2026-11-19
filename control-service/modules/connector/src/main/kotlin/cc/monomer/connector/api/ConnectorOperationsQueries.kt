package cc.monomer.connector.api

import cc.monomer.identity.api.OperationsAccess
import java.time.Instant

interface ConnectorOperationsQueries {
    suspend fun counts(access: OperationsAccess, workspaceIds: Set<String>): Map<String, List<OperationsConnectorCount>>
    suspend fun firstSampleConnectorCreatedAt(access: OperationsAccess, workspaceIds: Set<String>): Map<String, Instant>
    suspend fun firstConnectorCreatedAt(access: OperationsAccess, workspaceIds: Set<String>): Map<String, Instant>
}
data class OperationsConnectorCount(val kind: String, val count: Long)
