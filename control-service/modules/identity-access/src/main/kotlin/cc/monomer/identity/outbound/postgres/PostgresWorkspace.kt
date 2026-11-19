package cc.monomer.identity.outbound.postgres

import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.domain.model.AccessibleWorkspace
import cc.monomer.identity.domain.model.FreeOrganizationLimits
import cc.monomer.identity.domain.model.ReportingTimeZone
import cc.monomer.identity.domain.model.Workspace
import cc.monomer.identity.domain.port.FreeWorkspaceLimitException
import cc.monomer.identity.domain.port.TransactionRunner
import cc.monomer.identity.domain.port.WorkspaceQuery
import cc.monomer.identity.domain.port.WorkspaceRepository
import cc.monomer.identity.domain.port.SampleWorkspaceRepository
import cc.monomer.identity.api.WorkspaceSampleMarker
import cc.monomer.identity.api.WorkspaceSampleState
import cc.monomer.identity.api.PrincipalId
import java.sql.ResultSet
import java.sql.Timestamp
import kotlin.uuid.toJavaUuid
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository

@Repository
class PostgresWorkspaceRepository(
    @param:Qualifier("postgresJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) : WorkspaceRepository, SampleWorkspaceRepository {

    override fun insert(workspace: Workspace) {
        val organizationParameters = mapOf("organizationId" to workspace.organizationId.value.toJavaUuid())
        val plan = jdbc.query("SELECT plan FROM identity_runtime.organizations WHERE id = :organizationId FOR UPDATE",
            organizationParameters) { rs, _ -> rs.getString("plan") }.single()
        if (plan == "free") {
            if (countOrganizationWorkspaces(jdbc, workspace.organizationId) >= FreeOrganizationLimits.WORKSPACES) {
                throw FreeWorkspaceLimitException()
            }
        }
        jdbc.update(
            """
            INSERT INTO identity_runtime.workspaces (id, organization_id, display_name, created_at, public_pages, reporting_time_zone, sample_dataset, sample_state, sample_initiator)
            VALUES (:id, :organizationId, :displayName, :createdAt, :publicPages, :reportingTimeZone, :sampleDataset, :sampleState, :sampleInitiator)
            """.trimIndent(),
            mapOf(
                "id" to workspace.id.value.toJavaUuid(),
                "organizationId" to workspace.organizationId.value.toJavaUuid(),
                "displayName" to workspace.displayName,
                "createdAt" to Timestamp.from(workspace.createdAt),
                "publicPages" to workspace.publicPages,
                "reportingTimeZone" to workspace.reportingTimeZone.id,
                "sampleDataset" to workspace.sample?.dataset,
                "sampleState" to workspace.sample?.state?.name,
                "sampleInitiator" to workspace.sample?.initiator?.value?.toJavaUuid(),
            ),
        )
    }

    override fun findSampleByOrganization(organizationId: OrganizationId): Workspace? = jdbc.query(
        "SELECT * FROM identity_runtime.workspaces WHERE organization_id = :organizationId AND sample_dataset IS NOT NULL",
        mapOf("organizationId" to organizationId.value.toJavaUuid()),
    ) { rs, _ -> mapWorkspace(rs) }.singleOrNull()

    override fun findSampleById(workspaceId: WorkspaceId): Workspace? = jdbc.query(
        "SELECT * FROM identity_runtime.workspaces WHERE id = :workspaceId AND sample_dataset IS NOT NULL",
        mapOf("workspaceId" to workspaceId.value.toJavaUuid()),
    ) { rs, _ -> mapWorkspace(rs) }.singleOrNull()

    override fun readSampleInitiatorEvidence(workspaceId: WorkspaceId, dataset: String): cc.monomer.identity.api.SampleWorkspaceInitiatorEvidence? = jdbc.query(
        """SELECT workspace.id, workspace.organization_id, workspace.authorization_revision, workspace.sample_initiator, organization.plan, member.role
           FROM identity_runtime.workspaces workspace
           JOIN identity_runtime.organizations organization ON organization.id = workspace.organization_id
           JOIN identity_runtime.organization_members organization_member ON organization_member.organization_id = workspace.organization_id AND organization_member.user_id = workspace.sample_initiator
           JOIN identity_runtime.workspace_members member ON member.workspace_id = workspace.id AND member.user_id = workspace.sample_initiator
           WHERE workspace.id = :workspaceId AND workspace.sample_dataset = :dataset AND workspace.sample_state = 'PROVISIONING' AND member.role = 'OWNER'""",
        mapOf("workspaceId" to workspaceId.value.toJavaUuid(), "dataset" to dataset),
    ) { rs, _ -> cc.monomer.identity.api.SampleWorkspaceInitiatorEvidence(
        cc.monomer.identity.api.OrganizationPlan.valueOf(rs.getString("plan").uppercase()),
        cc.monomer.identity.api.CanonicalPrincipal(PrincipalId(rs.getUuid("sample_initiator")), cc.monomer.identity.api.PrincipalKind.USER),
        cc.monomer.identity.api.OrganizationId(rs.getUuid("organization_id")), cc.monomer.identity.api.WorkspaceId(rs.getUuid("id")),
        cc.monomer.identity.api.AuthorizationRevision(rs.getLong("authorization_revision")), WorkspaceRole.valueOf(rs.getString("role")),
    ) }.singleOrNull()

    override fun markSampleReady(workspaceId: WorkspaceId, dataset: String) {
        check(jdbc.update("UPDATE identity_runtime.workspaces SET sample_state = 'READY' WHERE id = :workspaceId AND sample_dataset = :dataset",
            mapOf("workspaceId" to workspaceId.value.toJavaUuid(), "dataset" to dataset)) == 1) { "Sample workspace is unavailable" }
    }

    override fun updatePublicPages(workspaceId: WorkspaceId, publicPages: Boolean): Boolean = jdbc.update(
        """UPDATE identity_runtime.workspaces
           SET public_pages = :publicPages, authorization_revision = authorization_revision + 1
           WHERE id = :workspaceId AND public_pages IS DISTINCT FROM :publicPages""",
        mapOf("workspaceId" to workspaceId.value.toJavaUuid(), "publicPages" to publicPages),
    ) == 1

    override fun updateReportingTimeZone(workspaceId: WorkspaceId, reportingTimeZone: ReportingTimeZone): Boolean = jdbc.update(
        """UPDATE identity_runtime.workspaces
           SET reporting_time_zone = :reportingTimeZone
           WHERE id = :workspaceId AND reporting_time_zone IS DISTINCT FROM :reportingTimeZone""",
        mapOf("workspaceId" to workspaceId.value.toJavaUuid(), "reportingTimeZone" to reportingTimeZone.id),
    ) == 1

    override fun updateDisplayName(workspaceId: WorkspaceId, displayName: String) {
        jdbc.update(
            "UPDATE identity_runtime.workspaces SET display_name = :displayName WHERE id = :workspaceId",
            mapOf(
                "workspaceId" to workspaceId.value.toJavaUuid(),
                "displayName" to displayName,
            ),
        )
    }

}

@Component
class PostgresWorkspaceQuery(
    @param:Qualifier("postgresJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
    private val transactionRunner: TransactionRunner,
) : WorkspaceQuery {

    override suspend fun countMembers(workspaceId: WorkspaceId): Int = transactionRunner.readOnly {
        jdbc.queryForObject(
            """
            SELECT count(*) FROM identity_runtime.workspace_members member
            JOIN identity_runtime.workspaces workspace ON workspace.id = member.workspace_id
            JOIN identity_runtime.organization_members organization_member ON organization_member.organization_id = workspace.organization_id
                 AND organization_member.user_id = member.user_id
            WHERE member.workspace_id = :workspaceId
            """.trimIndent(), mapOf("workspaceId" to workspaceId.value.toJavaUuid()), Int::class.java,
        )!!
    }

    override suspend fun find(id: WorkspaceId): Workspace? = transactionRunner.readOnly {
        jdbc.query(
            "SELECT * FROM identity_runtime.workspaces WHERE id = :id",
            mapOf("id" to id.value.toJavaUuid()),
        ) { rs, _ -> mapWorkspace(rs) }.firstOrNull()
    }

    override suspend fun listAccessibleByUser(userId: UserId): List<AccessibleWorkspace> = transactionRunner.readOnly {
        jdbc.query(
            """
            SELECT w.*, wm.role AS member_role
              FROM identity_runtime.workspaces w
              JOIN identity_runtime.organization_members am
                ON am.organization_id = w.organization_id
               AND am.user_id = :userId
              JOIN identity_runtime.workspace_members wm
                ON wm.workspace_id = w.id
               AND wm.user_id = :userId
             ORDER BY w.created_at
            """.trimIndent(),
            mapOf("userId" to userId.value.toJavaUuid()),
        ) { rs, _ -> AccessibleWorkspace(mapWorkspace(rs), WorkspaceRole.valueOf(rs.getString("member_role"))) }
    }
}

private fun mapWorkspace(rs: ResultSet): Workspace = Workspace(
    id = WorkspaceId(rs.getUuid("id")),
    organizationId = OrganizationId(rs.getUuid("organization_id")),
    displayName = rs.getString("display_name"),
    createdAt = rs.getInstant("created_at"),
    publicPages = rs.getBoolean("public_pages"),
    reportingTimeZone = storedReportingTimeZone(rs.getString("reporting_time_zone")),
    sample = mapWorkspaceSample(rs),
)

internal fun mapWorkspaceSample(rs: ResultSet): WorkspaceSampleMarker? = rs.getString("sample_dataset")?.let { dataset ->
    WorkspaceSampleMarker(dataset, WorkspaceSampleState.valueOf(rs.getString("sample_state")), PrincipalId(rs.getUuid("sample_initiator")))
}
