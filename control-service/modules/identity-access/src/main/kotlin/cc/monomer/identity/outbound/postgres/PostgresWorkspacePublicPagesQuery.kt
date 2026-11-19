package cc.monomer.identity.outbound.postgres

import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.api.WorkspacePublicPagesQuery
import cc.monomer.identity.domain.port.TransactionRunner
import kotlin.uuid.toJavaUuid
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component

@Component
class PostgresWorkspacePublicPagesQuery(
    @param:Qualifier("postgresJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
    private val transactionRunner: TransactionRunner,
) : WorkspacePublicPagesQuery {
    override suspend fun isPublic(workspaceId: WorkspaceId): Boolean = transactionRunner.readOnly {
        jdbc.query(
            """SELECT workspace.public_pages FROM identity_runtime.workspaces workspace
               JOIN identity_runtime.organizations organization ON organization.id = workspace.organization_id
               WHERE workspace.id = :workspaceId""",
            mapOf("workspaceId" to workspaceId.value.toJavaUuid()),
        ) { row, _ -> row.getBoolean("public_pages") }.singleOrNull() == true
    }
    override suspend fun pageReaderName(workspaceId: WorkspaceId): String? = transactionRunner.readOnly {
        jdbc.query(
            """SELECT workspace.display_name FROM identity_runtime.workspaces workspace
               JOIN identity_runtime.organizations organization ON organization.id = workspace.organization_id
               WHERE workspace.id = :workspaceId""",
            mapOf("workspaceId" to workspaceId.value.toJavaUuid()),
        ) { row, _ -> row.getString("display_name") }.singleOrNull()
    }
}
