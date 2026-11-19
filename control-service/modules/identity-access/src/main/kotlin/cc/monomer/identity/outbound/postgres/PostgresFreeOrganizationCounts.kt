package cc.monomer.identity.outbound.postgres

import cc.monomer.identity.domain.id.OrganizationId
import java.sql.Timestamp
import java.time.Instant
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

// Free-limit checks and the Organization usage read share these counts so displayed usage equals enforced usage.

internal fun countOrganizationMembers(jdbc: NamedParameterJdbcTemplate, organizationId: OrganizationId): Int =
    jdbc.queryForObject(
        "SELECT count(*) FROM identity_runtime.organization_members WHERE organization_id = :organizationId ",
        mapOf("organizationId" to organizationId.value.toJavaUuid()), Int::class.java,
    )!!

internal fun countOrganizationWorkspaces(jdbc: NamedParameterJdbcTemplate, organizationId: OrganizationId): Int =
    jdbc.queryForObject(
        "SELECT count(*) FROM identity_runtime.workspaces WHERE organization_id = :organizationId",
        mapOf("organizationId" to organizationId.value.toJavaUuid()), Int::class.java,
    )!!

internal fun countPendingOrganizationInvitations(
    jdbc: NamedParameterJdbcTemplate,
    organizationId: OrganizationId,
    now: Instant,
    excludedInvitationId: Uuid?,
): Int = jdbc.queryForObject(
    """SELECT count(*) FROM identity_runtime.organization_membership_invitations
        WHERE organization_id = :organizationId AND expires_at > :now AND (CAST(:excludedInvitationId AS uuid) IS NULL OR id <> :excludedInvitationId)""",
    mapOf(
        "organizationId" to organizationId.value.toJavaUuid(),
        "now" to Timestamp.from(now),
        "excludedInvitationId" to excludedInvitationId?.toJavaUuid(),
    ),
    Int::class.java,
)!!
