package cc.monomer.pages.adapter.outbound.postgres.pages

import cc.monomer.pages.api.PageActivity
import cc.monomer.pages.api.PageActivityReadPort
import cc.monomer.pages.api.PageAgentClientUse
import cc.monomer.pages.domain.common.TransactionRunner
import java.time.Instant
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class PostgresPageActivityReader(
    @param:Qualifier("pagesJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
    private val transactionRunner: TransactionRunner,
) : PageActivityReadPort {
    override suspend fun readRecent(
        workspaceId: Uuid,
        before: Instant?,
        beforePageId: Uuid?,
        actorId: String?,
        actorKind: String?,
        agentClientId: String?,
        limit: Int,
    ): List<PageActivity> = transactionRunner.readOnly {
        require(limit in 1..101)
        require(actorKind == null || actorKind in setOf("USER", "AGENT"))
        require((before == null) == (beforePageId == null))
        jdbc.query(
            """
            $RECENT_DOCUMENT_CHANGES
            SELECT * FROM recent
            WHERE (CAST(:actorId AS TEXT) IS NULL OR actor_id = :actorId)
              AND (CAST(:actorKind AS TEXT) IS NULL OR actor_kind = :actorKind)
              AND (CAST(:agentClientId AS TEXT) IS NULL OR agent_client_id = :agentClientId)
              AND (CAST(:before AS NUMERIC) IS NULL OR (occurred_at, id) < (:before, CAST(:beforeId AS UUID)))
            ORDER BY occurred_at DESC, id DESC LIMIT :limit
            """.trimIndent(),
            mapOf(
                "workspaceId" to workspaceId.toJavaUuid(), "actorId" to actorId, "actorKind" to actorKind, "agentClientId" to agentClientId,
                "before" to before?.let { java.math.BigDecimal.valueOf(it.epochSecond).multiply(java.math.BigDecimal(1000)).add(java.math.BigDecimal.valueOf(it.nano.toLong(), 6)) }, "beforeId" to beforePageId?.toJavaUuid(), "limit" to limit,
            ),
        ) { row, _ ->
            PageActivity(
                pageId = row.getObject("id", java.util.UUID::class.java).toKotlinUuid(),
                title = row.getString("title"),
                occurredAt = Instant.ofEpochMilli(row.getLong("occurred_at")),
                actorId = row.getString("actor_id"),
                actorKind = row.getString("actor_kind"),
                agentClientId = row.getString("agent_client_id"),
                contentVersion = row.getObject("content_version")?.let { (it as Number).toInt() },
            )
        }
    }

    override suspend fun readRecentAgentClients(workspaceId: Uuid, scanLimit: Int): List<PageAgentClientUse> = transactionRunner.readOnly {
        require(scanLimit in 1..500)
        jdbc.query(
            """
            $RECENT_DOCUMENT_CHANGES
            SELECT agent_client_id, max(occurred_at) AS last_seen_at
            FROM (SELECT agent_client_id, occurred_at FROM recent ORDER BY occurred_at DESC, id DESC LIMIT :scanLimit) latest
            WHERE agent_client_id IS NOT NULL
            GROUP BY agent_client_id
            """.trimIndent(),
            mapOf("workspaceId" to workspaceId.toJavaUuid(), "scanLimit" to scanLimit),
        ) { row, _ -> PageAgentClientUse(row.getString("agent_client_id"), Instant.ofEpochMilli(row.getLong("last_seen_at"))) }
    }
}

/** Each Document's latest change: its newest version when that is not older than its metadata change. */
private const val RECENT_DOCUMENT_CHANGES = """
            WITH recent AS (
                SELECT page.id, page.title,
                       GREATEST(page.updated_at, version.finalized_at) AS occurred_at,
                       CASE WHEN version.finalized_at >= page.updated_at
                            THEN version.finalized_by_id ELSE page.updated_by_id END AS actor_id,
                       CASE WHEN version.finalized_at >= page.updated_at
                            THEN version.finalized_by_kind ELSE page.updated_by_kind END AS actor_kind,
                       CASE WHEN version.finalized_at >= page.updated_at
                            THEN version.finalized_by_agent_client_id ELSE page.updated_by_agent_client_id END AS agent_client_id,
                       CASE WHEN version.finalized_at >= page.updated_at THEN version.version_no END AS content_version
                FROM pages_relocation.pages page
                LEFT JOIN LATERAL (
                    SELECT version_no, finalized_at, finalized_by_id, finalized_by_kind, finalized_by_agent_client_id
                    FROM pages_relocation.page_versions
                    WHERE page_id = page.id ORDER BY version_no DESC LIMIT 1
                ) version ON true
                WHERE page.workspace_id = :workspaceId AND page.kind = 'DOCUMENT'
            )"""
