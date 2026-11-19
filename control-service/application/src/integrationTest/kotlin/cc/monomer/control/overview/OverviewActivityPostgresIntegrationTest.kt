@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package cc.monomer.control.overview

import cc.monomer.control.EphemeralPostgresAuthoritySupport
import cc.monomer.pages.adapter.outbound.postgres.pages.PostgresPageActivityReader
import cc.monomer.pages.domain.common.TransactionRunner
import cc.monomer.dashboard.adapter.outbound.postgres.PostgresDashboardActivityReadPort
import cc.monomer.catalog.adapter.outbound.postgres.PostgresCatalogActivityReadPort
import cc.monomer.explore.adapter.outbound.postgres.PostgresExploreActivityReadPort
import cc.monomer.explore.adapter.outbound.postgres.SpringExploreTransactionPort
import cc.monomer.catalog.adapter.outbound.postgres.SpringCatalogTransactionPort
import cc.monomer.catalog.adapter.outbound.postgres.CatalogTransactionTimeouts
import kotlinx.coroutines.Dispatchers
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import cc.monomer.identity.api.WorkspaceId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*
import kotlin.uuid.Uuid

class OverviewActivityPostgresIntegrationTest {
    private val dataSource get() = postgres.dataSource

    @Test fun `page body save outranks directory timestamp and preserves fractional cursor boundary`() = runBlocking {
        val workspace = Uuid.generateV7()
        val page = Uuid.generateV7()
        val actor = Uuid.generateV7()
        val updated = 1788912000000L
        dataSource.connection.use { connection -> connection.createStatement().use { statement ->
            statement.execute("INSERT INTO pages_relocation.page_directories VALUES ('$workspace', '[]', 0, $updated, 'USER', '$actor')")
            statement.execute("INSERT INTO pages_relocation.pages (id,workspace_id,kind,title,created_at,created_by_kind,created_by_id,updated_at,updated_by_kind,updated_by_id) VALUES ('$page','$workspace','DOCUMENT','Revenue briefing',$updated,'USER','$actor',$updated,'USER','$actor')")
            statement.execute("INSERT INTO pages_relocation.page_versions VALUES ('$page',1,'MARKDOWN','fixture-content','${"a".repeat(64)}',1,'AGENT','$actor',${updated + 1000})")
        } }
        val reader = PostgresPageActivityReader(NamedParameterJdbcTemplate(dataSource), object : TransactionRunner {
            override suspend fun <T> run(block: () -> T): T = block()
            override suspend fun <T> readOnly(block: () -> T): T = block()
        })
        val item = reader.readRecent(workspace, null, null, null, null, null, 10).single()
        assertEquals(1, item.contentVersion)
        assertEquals("AGENT", item.actorKind)
        assertEquals(Instant.ofEpochMilli(updated + 1000), item.occurredAt)
        assertEquals(item, reader.readRecent(workspace, item.occurredAt.plusNanos(1000), Uuid.parse("00000000-0000-0000-0000-000000000000"), null, null, null, 10).single())
        assertTrue(reader.readRecent(workspace, item.occurredAt, page, null, null, null, 10).isEmpty())
        assertTrue(reader.readRecent(Uuid.generateV7(), null, null, null, null, null, 10).isEmpty())
        assertTrue(reader.readRecent(workspace, null, null, Uuid.generateV7().toString(), null, null, 10).isEmpty())
        assertEquals(item, reader.readRecent(workspace, null, null, null, "AGENT", null, 10).single())
        assertTrue(reader.readRecent(workspace, null, null, null, "USER", null, 10).isEmpty())
        val webPage = Uuid.generateV7()
        dataSource.connection.use { connection -> connection.createStatement().use { statement ->
            statement.execute("INSERT INTO pages_relocation.pages (id,workspace_id,kind,title,created_at,created_by_kind,created_by_id,updated_at,updated_by_kind,updated_by_id) VALUES ('$webPage','$workspace','DOCUMENT','Web notes',$updated,'USER','$actor',${updated + 500},'USER','$actor')")
            statement.execute("INSERT INTO pages_relocation.page_versions VALUES ('$webPage',1,'MARKDOWN','web-content','${"b".repeat(64)}',1,'USER','$actor',$updated)")
        } }
        assertEquals(listOf(webPage), reader.readRecent(workspace, null, null, null, "USER", null, 10).map { it.pageId })
        assertEquals(listOf(page), reader.readRecent(workspace, null, null, null, "AGENT", null, 10).map { it.pageId })
    }

    @Test fun `all activity SQL executes against canonical owner schemas with scoped watch and cursor`() = runBlocking<Unit> {
        val workspace = WorkspaceId.generate()
        val before = Instant.parse("2026-09-09T00:00:00Z")
        val id = Uuid.generateV7()
        val catalog = catalogActivityReader()
        val explore = exploreActivityReader()
        val dashboard = dashboardActivityReader()
        for (live in listOf(true, false)) {
            assertTrue(explore.readActivity(workspace, live, before, id, null, null, null, 10, emptySet()).isEmpty())
            assertTrue(explore.readActivity(workspace, live, null, null, null, null, null, 10, setOf(id)).isEmpty())
            for (refresh in listOf(true, false)) {
                assertTrue(catalog.readActivity(workspace, refresh, live, before, id, null, null, null, 10, emptySet()).isEmpty())
                assertTrue(catalog.readActivity(workspace, refresh, live, null, null, null, null, null, 10, setOf(id)).isEmpty())
            }
            assertTrue(dashboard.readSessions(workspace, live, before, id, "someone", null, null, 10, emptySet()).isEmpty())
            assertTrue(dashboard.readSessions(workspace, live, null, null, null, null, null, 10, setOf(id)).isEmpty())
            assertTrue(dashboard.readDraftRuns(workspace, live, before, id, "someone", null, null, 10, emptySet()).isEmpty())
            assertTrue(dashboard.readDraftRuns(workspace, live, null, null, null, null, null, 10, setOf(id)).isEmpty())
        }
        assertTrue(dashboard.readChanges(workspace, before, id, null, null, null, 10, false).isEmpty())
        val agent = cc.monomer.identity.api.CredentialSurface.AGENT
        val actor = cc.monomer.identity.api.PrincipalId.generate()
        for (live in listOf(true, false)) {
            assertTrue(explore.readActivity(workspace, live, before, id, actor, agent, null, 10, emptySet()).isEmpty())
            for (refresh in listOf(true, false)) {
                assertTrue(catalog.readActivity(workspace, refresh, live, before, id, actor, agent, null, 10, emptySet()).isEmpty())
            }
            assertTrue(dashboard.readSessions(workspace, live, before, id, "someone", agent, null, 10, emptySet()).isEmpty())
            assertTrue(dashboard.readDraftRuns(workspace, live, before, id, "someone", agent, null, 10, emptySet()).isEmpty())
        }
        assertTrue(dashboard.readChanges(workspace, before, id, "someone", agent, null, 10, true).isEmpty())
    }

    @Test fun `dashboard runs group by Session with latest query state, evidenced origins, and keyset`() = runBlocking<Unit> {
        val workspace = WorkspaceId.generate()
        val dashboardId = Uuid.generateV7()
        val connector = Uuid.generateV7()
        val member = Uuid.generateV7().toString()
        val opened = Uuid.generateV7()
        val scheduled = Uuid.generateV7()
        val publicReader = Uuid.generateV7()
        val unrecorded = Uuid.generateV7()
        fun at(seconds: Long) = "2026-10-01T00:00:${seconds.toString().padStart(2, '0')}Z"
        fun sql(value: String) = dataSource.connection.use { connection -> connection.createStatement().use { it.execute(value) } }
        sql("""INSERT INTO dashboard.dashboards VALUES ('$dashboardId','$workspace','{"meta":{"title":"Revenue overview"}}','$member','WEB','${at(0)}','${at(0)}')""")
        for (session in listOf(opened, scheduled, publicReader, unrecorded)) {
            sql("""INSERT INTO dashboard.sessions VALUES ('$session','$workspace','$dashboardId','{}','{}','${at(0)}','${at(0)}')""")
        }
        sql("""INSERT INTO dashboard.schedule_occurrences VALUES ('$workspace','$dashboardId','daily','${at(0)}','${at(0)}','ACCEPTED',NULL,'{}','{}','$scheduled','{}')""")
        sql("""INSERT INTO dashboard.command_receipts VALUES ('$workspace',NULL,'public-1','RESOLVE_PUBLIC_SESSION','$publicReader','{}','$dashboardId','$publicReader','[]','{}','${at(0)}')""")
        fun run(session: Uuid?, query: String, actor: String?, state: String, created: Long, updated: Long, failure: String?) {
            val manifest = if (state == "SUCCEEDED") "'{}'" else "NULL"
            sql("""INSERT INTO dashboard.query_runs (query_run_id, workspace_id, dashboard_id, viewer_session_id, query_name, query_type, connector_id, resolved_query,
                rendered_sql, output_column_names, actor_id, credential_surface, state, result_manifest, failure_code, failure_message, created_at, updated_at)
                VALUES ('${Uuid.generateV7()}','$workspace','$dashboardId',${session?.let { "'$it'" } ?: "NULL"},'$query','SEMANTIC','$connector','{}','SELECT 1','["value"]',
                ${actor?.let { "'$it'" } ?: "NULL"},${actor?.let { "'WEB'" } ?: "NULL"},'$state',$manifest,${failure?.let { "'$it'" } ?: "NULL"},NULL,'${at(created)}','${at(updated)}')""")
        }
        run(opened, "monthly", member, "FAILED", 1, 2, "EXECUTION_FAILED")
        run(opened, "regions", member, "RUNNING", 5, 5, null)
        run(opened, "monthly", member, "SUCCEEDED", 10, 11, null)
        run(opened, "regions", member, "FAILED", 10, 12, "AUTHENTICATION_FAILED")
        run(scheduled, "regions", null, "SUCCEEDED", 3, 4, null)
        run(scheduled, "monthly", null, "RUNNING", 13, 13, null)
        run(publicReader, "monthly", null, "SUCCEEDED", 14, 15, null)
        run(unrecorded, "monthly", null, "SUCCEEDED", 6, 7, null)
        val repository = dashboardActivityReader()
        val recent = repository.readSessions(workspace, false, null, null, null, null, null, 10, emptySet())
        assertEquals(listOf(publicReader, opened, unrecorded), recent.map { it.sessionId })
        val openedRow = recent[1]
        assertEquals(2, openedRow.queryCount)
        assertEquals(1, openedRow.failedCount)
        assertEquals("AUTHENTICATION_FAILED", openedRow.failureCode)
        assertEquals(0, openedRow.runningCount)
        assertEquals(Instant.parse(at(10)), openedRow.startedAt)
        assertEquals(Instant.parse(at(12)), openedRow.updatedAt)
        assertEquals(cc.monomer.dashboard.api.DashboardSessionOrigin.MEMBER, openedRow.origin)
        assertEquals("Revenue overview", openedRow.title)
        assertEquals(cc.monomer.dashboard.api.DashboardSessionOrigin.PUBLIC_READER, recent.first().origin)
        assertEquals(cc.monomer.dashboard.api.DashboardSessionOrigin.UNRECORDED, recent.last().origin)
        assertEquals(listOf(opened, unrecorded), repository.readSessions(workspace, false, Instant.parse(at(15)), publicReader, null, null, null, 10, emptySet()).map { it.sessionId })
        assertEquals(listOf(opened), repository.readSessions(workspace, false, null, null, member, null, null, 10, emptySet()).map { it.sessionId })
        assertEquals(listOf(publicReader), repository.readSessions(workspace, false, null, null, null, null, null, 1, emptySet()).map { it.sessionId })
        val live = repository.readSessions(workspace, true, null, null, null, null, null, 10, emptySet()).single()
        assertEquals(scheduled, live.sessionId)
        assertEquals(cc.monomer.dashboard.api.DashboardSessionOrigin.SCHEDULE, live.origin)
        assertEquals(1, live.runningCount)
        assertEquals(2, live.queryCount)
        assertEquals(Instant.parse(at(13)), live.startedAt)
        assertTrue(repository.readSessions(workspace, true, Instant.parse(at(13)), scheduled, null, null, null, 10, emptySet()).isEmpty())
        assertEquals(setOf(scheduled, opened), repository.readSessions(workspace, true, null, null, null, null, null, 10, setOf(scheduled, opened)).map { it.sessionId }.toSet())
        assertTrue(repository.readSessions(WorkspaceId.generate(), true, null, null, null, null, null, 10, setOf(scheduled)).isEmpty())
        run(null, "monthly", member, "FAILED", 16, 17, "EXECUTION_FAILED")
        assertTrue(repository.readDraftRuns(workspace, false, null, null, null, null, null, 10, emptySet()).isEmpty())
        sql("""INSERT INTO dashboard.drafts (workspace_id,dashboard_id,definition,parameter_bindings,variables,authoring_revision,target_query_runs,ready_results,created_at,updated_at)
            VALUES ('$workspace','$dashboardId','{"meta":{"title":"Draft title"}}','{}','{}',1,'{}','{}','${at(16)}','${at(16)}')""")
        val draftRun = repository.readDraftRuns(workspace, false, null, null, null, null, null, 10, emptySet()).single()
        assertEquals(draftRun, repository.readDraftRuns(workspace, false, null, null, member, cc.monomer.identity.api.CredentialSurface.WEB, null, 10, emptySet()).single())
        assertTrue(repository.readDraftRuns(workspace, false, null, null, null, cc.monomer.identity.api.CredentialSurface.AGENT, null, 10, emptySet()).isEmpty())
        assertEquals(listOf(opened), repository.readSessions(workspace, false, null, null, member, cc.monomer.identity.api.CredentialSurface.WEB, null, 10, emptySet()).map { it.sessionId })
        assertTrue(repository.readSessions(workspace, false, null, null, null, cc.monomer.identity.api.CredentialSurface.AGENT, null, 10, emptySet()).isEmpty())
        assertEquals("EXECUTION_FAILED", draftRun.failureCode)
        assertEquals("monthly", draftRun.queryName)
        assertEquals(listOf(publicReader, opened, unrecorded), repository.readSessions(workspace, false, null, null, null, null, null, 10, emptySet()).map { it.sessionId })
        val refresher = Uuid.generateV7().toString()
        run(opened, "monthly", refresher, "SUCCEEDED", 18, 19, null)
        assertEquals(listOf(opened), repository.readSessions(workspace, false, null, null, member, null, null, 10, emptySet()).map { it.sessionId })
        assertTrue(repository.readSessions(workspace, false, null, null, refresher, null, null, 10, emptySet()).isEmpty())
    }

    @Test fun `dashboard definition change retains accountable actor and original surface`() = runBlocking<Unit> {
        val workspace = WorkspaceId.generate()
        val id = Uuid.generateV7()
        val actor = Uuid.generateV7()
        dataSource.connection.use { connection -> connection.createStatement().use { statement ->
            statement.execute("INSERT INTO dashboard.dashboards VALUES ('$id','$workspace','{\"meta\":{\"title\":\"Revenue\"}}','$actor','AGENT','2026-09-09T00:00:00Z','2026-09-09T00:00:01Z')")
        } }
        recordDefinitionChange(workspace, id, "APPLIED", "Revenue", actor.toString(), "AGENT", null, "2026-09-09T00:00:01Z")
        val item = dashboardActivityReader().readChanges(workspace, null, null, actor.toString(), null, null, 1, false).single()
        assertEquals("Revenue", item.title)
        assertEquals(actor.toString(), item.actorId)
        assertEquals(cc.monomer.identity.api.CredentialSurface.AGENT, item.credentialSurface)
    }

    @Test fun `authoring activity lists creation and apply records with the Draft edit after the last Apply and no draft leakage`() = runBlocking<Unit> {
        val workspace = WorkspaceId.generate()
        val id = Uuid.generateV7()
        val creator = Uuid.generateV7().toString()
        val editor = Uuid.generateV7().toString()
        val repository = dashboardActivityReader()
        fun sql(value: String) = dataSource.connection.use { connection -> connection.createStatement().use { it.execute(value) } }
        sql("""INSERT INTO dashboard.dashboards VALUES ('$id','$workspace','{"meta":{"title":"Created title"}}','$creator','AGENT','2026-09-14T00:00:00Z','2026-09-14T00:00:00Z')""")
        recordDefinitionChange(workspace, id, "CREATED", "Created title", creator, "AGENT", null, "2026-09-14T00:00:00Z")
        assertEquals(cc.monomer.dashboard.api.DashboardChangeKind.CREATED, repository.readChanges(workspace, null, null, null, null, null, 10, true).single().change)
        sql("""INSERT INTO dashboard.drafts (workspace_id,dashboard_id,definition,parameter_bindings,variables,authoring_revision,target_query_runs,ready_results,created_at,updated_at,last_edited_at,last_edited_by,last_edit_surface)
            VALUES ('$workspace','$id','{"meta":{"title":"Draft title"}}','{}','{}',1,'{}','{}','2026-09-14T00:00:01Z','2026-09-14T00:00:09Z','2026-09-14T00:00:02Z','$editor','WEB')""")
        val draft = repository.readChanges(workspace, null, null, editor, null, null, 10, true).single()
        assertEquals(cc.monomer.dashboard.api.DashboardChangeKind.DRAFT_UPDATED, draft.change)
        assertEquals("Draft title", draft.title)
        assertEquals(Instant.parse("2026-09-14T00:00:02Z"), draft.occurredAt)
        assertEquals(cc.monomer.identity.api.CredentialSurface.WEB, draft.credentialSurface)
        assertEquals(listOf(cc.monomer.dashboard.api.DashboardChangeKind.CREATED), repository.readChanges(workspace, null, null, creator, null, null, 10, true).map { it.change })
        assertEquals(listOf(cc.monomer.dashboard.api.DashboardChangeKind.CREATED), repository.readChanges(workspace, draft.occurredAt, id, null, null, null, 10, true).map { it.change })
        val viewer = repository.readChanges(workspace, null, null, null, null, null, 10, false).single()
        assertEquals("Created title", viewer.title)
        assertEquals(creator, viewer.actorId)
        assertEquals(cc.monomer.dashboard.api.DashboardChangeKind.CREATED, viewer.change)
        sql("UPDATE dashboard.dashboards SET updated_at='2026-09-14T00:00:03Z', updated_by='$editor',credential_surface='WEB' WHERE dashboard_id='$id'")
        recordDefinitionChange(workspace, id, "APPLIED", "Created title", editor, "WEB", null, "2026-09-14T00:00:03Z")
        assertEquals(
            listOf(cc.monomer.dashboard.api.DashboardChangeKind.APPLIED, cc.monomer.dashboard.api.DashboardChangeKind.CREATED),
            repository.readChanges(workspace, null, null, null, null, null, 10, true).map { it.change },
        )
        assertEquals(listOf(cc.monomer.dashboard.api.DashboardChangeKind.CREATED), repository.readChanges(workspace, null, null, null, cc.monomer.identity.api.CredentialSurface.AGENT, null, 10, true).map { it.change })
        sql("""UPDATE dashboard.drafts SET definition='{"meta":{"title":"Created title"}}', last_edited_at='2026-09-14T00:00:04Z', last_edited_by='$creator', last_edit_surface='AGENT' WHERE dashboard_id='$id'""")
        sql("""UPDATE dashboard.dashboards SET definition='{"meta":{"title":"Created title"}}' WHERE dashboard_id='$id'""")
        val agentChanges = repository.readChanges(workspace, null, null, null, cc.monomer.identity.api.CredentialSurface.AGENT, null, 10, true)
        assertEquals(
            listOf(cc.monomer.dashboard.api.DashboardChangeKind.DRAFT_DISCARDED, cc.monomer.dashboard.api.DashboardChangeKind.CREATED),
            agentChanges.map { it.change },
        )
        assertEquals(creator, agentChanges.first().actorId)
    }

    @Test fun `dashboard and page activity keep the agent app, filter by it, and list recent apps`() = runBlocking {
        val workspace = WorkspaceId.generate()
        val dashboardId = Uuid.generateV7()
        val connector = Uuid.generateV7()
        val member = Uuid.generateV7().toString()
        val claude = "https://claude.ai/oauth/mcp-oauth-client-metadata"
        val codex = "https://chatgpt.com/codex/oauth-client.json"
        val claudeId = cc.monomer.identity.api.AgentClientId(claude)
        val codexId = cc.monomer.identity.api.AgentClientId(codex)
        val agent = cc.monomer.identity.api.CredentialSurface.AGENT
        fun sql(value: String) = dataSource.connection.use { connection -> connection.createStatement().use { it.execute(value) } }
        sql("""INSERT INTO dashboard.dashboards (dashboard_id, workspace_id, definition, updated_by, credential_surface, created_at, updated_at, agent_client_id)
            VALUES ('$dashboardId','$workspace','{"meta":{"title":"Revenue"}}','$member','AGENT','2026-10-01T00:00:00Z','2026-10-01T00:00:01Z','$claude')""")
        recordDefinitionChange(workspace, dashboardId, "APPLIED", "Revenue", member, "AGENT", claude, "2026-10-01T00:00:01Z")
        val repository = dashboardActivityReader()
        val current = repository.readChanges(workspace, null, null, null, agent, claudeId, 10, true).single()
        assertEquals(claudeId, current.agentClientId)
        assertTrue(repository.readChanges(workspace, null, null, null, agent, codexId, 10, true).isEmpty())
        sql("""INSERT INTO dashboard.drafts (workspace_id,dashboard_id,definition,parameter_bindings,variables,authoring_revision,target_query_runs,ready_results,created_at,updated_at,last_edited_at,last_edited_by,last_edit_surface,last_edit_agent_client_id)
            VALUES ('$workspace','$dashboardId','{"meta":{"title":"Draft"}}','{}','{}',1,'{}','{}','2026-10-01T00:00:02Z','2026-10-01T00:00:03Z','2026-10-01T00:00:03Z','$member','AGENT','$codex')""")
        assertEquals(codexId, repository.readChanges(workspace, null, null, null, agent, codexId, 10, true).single().agentClientId)
        assertEquals(listOf(cc.monomer.dashboard.api.DashboardChangeKind.APPLIED), repository.readChanges(workspace, null, null, null, agent, claudeId, 10, true).map { it.change })
        assertEquals(claudeId, repository.readChanges(workspace, null, null, null, agent, claudeId, 10, false).single().agentClientId)
        sql("""INSERT INTO dashboard.query_runs (query_run_id, workspace_id, dashboard_id, viewer_session_id, query_name, query_type, connector_id, resolved_query,
            rendered_sql, output_column_names, actor_id, credential_surface, state, result_manifest, failure_code, failure_message, created_at, updated_at, agent_client_id)
            VALUES ('${Uuid.generateV7()}','$workspace','$dashboardId',NULL,'monthly','SEMANTIC','$connector','{}','SELECT 1','["value"]','$member','AGENT','FAILED',NULL,'EXECUTION_FAILED',NULL,
            '2026-10-01T00:00:04Z','2026-10-01T00:00:05Z','$claude')""")
        assertEquals(claudeId, repository.readDraftRuns(workspace, false, null, null, null, agent, claudeId, 10, emptySet()).single().agentClientId)
        assertTrue(repository.readDraftRuns(workspace, false, null, null, null, agent, codexId, 10, emptySet()).isEmpty())
        val session = Uuid.generateV7()
        sql("""INSERT INTO dashboard.sessions VALUES ('$session','$workspace','$dashboardId','{}','{}','2026-10-01T00:00:06Z','2026-10-01T00:00:06Z')""")
        fun sessionRun(client: String, created: Int) = sql("""INSERT INTO dashboard.query_runs (query_run_id, workspace_id, dashboard_id, viewer_session_id, query_name, query_type, connector_id, resolved_query,
            rendered_sql, output_column_names, actor_id, credential_surface, state, result_manifest, failure_code, failure_message, created_at, updated_at, agent_client_id)
            VALUES ('${Uuid.generateV7()}','$workspace','$dashboardId','$session','q$created','SEMANTIC','$connector','{}','SELECT 1','["value"]','$member','AGENT','SUCCEEDED','{}',NULL,NULL,
            '2026-10-01T00:00:${created}Z','2026-10-01T00:00:${created + 1}Z','$client')""")
        sessionRun(claude, 10)
        sessionRun(codex, 20)
        val opened = repository.readSessions(workspace, false, null, null, null, agent, claudeId, 10, emptySet()).single()
        assertEquals(session, opened.sessionId)
        assertEquals(claudeId, opened.agentClientId, "the label and the filter both read the run that opened the Session")
        assertTrue(repository.readSessions(workspace, false, null, null, null, agent, codexId, 10, emptySet()).isEmpty())
        assertEquals(claudeId, repository.readSessions(workspace, false, null, null, null, null, null, 10, emptySet()).single().agentClientId)
        assertEquals(setOf(claudeId, codexId), repository.readRecentAgentClients(workspace, 500).map { it.clientId }.toSet())
        assertTrue(repository.readRecentAgentClients(WorkspaceId.generate(), 500).isEmpty())
        val invalid = runCatching { sql("UPDATE dashboard.dashboards SET credential_surface = 'WEB' WHERE dashboard_id = '$dashboardId'") }
        assertTrue(invalid.isFailure, "a WEB change cannot name an agent app")

        val page = Uuid.generateV7()
        val updated = 1788912000000L
        sql("INSERT INTO pages_relocation.page_directories VALUES ('${workspace.value}', '[]', 0, $updated, 'AGENT', '$member')")
        sql("INSERT INTO pages_relocation.pages (id,workspace_id,kind,title,created_at,created_by_kind,created_by_id,updated_at,updated_by_kind,updated_by_id,created_by_agent_client_id,updated_by_agent_client_id) VALUES ('$page','${workspace.value}','DOCUMENT','Notes',$updated,'AGENT','$member',$updated,'AGENT','$member','$codex','$codex')")
        sql("INSERT INTO pages_relocation.page_versions (page_id,version_no,format,source_object_ref,content_hash,content_size_bytes,finalized_by_kind,finalized_by_id,finalized_at,finalized_by_agent_client_id) VALUES ('$page',1,'MARKDOWN','notes','${"c".repeat(64)}',1,'AGENT','$member',${updated + 1000},'$claude')")
        val reader = PostgresPageActivityReader(NamedParameterJdbcTemplate(dataSource), object : TransactionRunner {
            override suspend fun <T> run(block: () -> T): T = block()
            override suspend fun <T> readOnly(block: () -> T): T = block()
        })
        assertEquals(claude, reader.readRecent(workspace.value, null, null, null, "AGENT", claude, 10).single().agentClientId)
        assertTrue(reader.readRecent(workspace.value, null, null, null, "AGENT", codex, 10).isEmpty(), "the newer version names the app")
        assertEquals(listOf(claude), reader.readRecentAgentClients(workspace.value, 500).map { it.agentClientId })
        val webVersion = runCatching { sql("UPDATE pages_relocation.page_versions SET finalized_by_kind = 'USER' WHERE page_id = '$page'") }
        assertTrue(webVersion.isFailure, "a USER version cannot name an agent app")
    }

    private fun activityReadTransactions(): TransactionTemplate =
        TransactionTemplate(DataSourceTransactionManager(dataSource)).apply {
            isReadOnly = true
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            timeout = 5
        }

    private fun catalogActivityReader(): PostgresCatalogActivityReadPort {
        val manager = DataSourceTransactionManager(dataSource)
        val write = TransactionTemplate(manager).apply {
            isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
            timeout = 5
        }
        val read = TransactionTemplate(manager).apply {
            isReadOnly = true
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            timeout = 5
        }
        return PostgresCatalogActivityReadPort(
            dataSource,
            SpringCatalogTransactionPort(
                dataSource, write, read, Dispatchers.IO,
                CatalogTransactionTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(1)),
            ),
        )
    }

    private fun dashboardActivityReader(): PostgresDashboardActivityReadPort =
        PostgresDashboardActivityReadPort(dataSource, activityReadTransactions(), Dispatchers.IO)

    private fun exploreActivityReader(): PostgresExploreActivityReadPort =
        PostgresExploreActivityReadPort(
            dataSource, SpringExploreTransactionPort(activityReadTransactions(), Dispatchers.IO),
            Duration.ofSeconds(5), Duration.ofSeconds(1),
        )

    private fun recordDefinitionChange(
        workspace: WorkspaceId, dashboardId: Uuid, action: String, title: String,
        actorId: String, credentialSurface: String, agentClientId: String?, occurredAt: String,
    ) = dataSource.connection.use { connection -> connection.createStatement().use { statement ->
        statement.execute("""INSERT INTO dashboard.definition_changes (change_id, workspace_id, dashboard_id, title, action, definition, actor_id, credential_surface, agent_client_id, occurred_at)
            VALUES ('${Uuid.generateV7()}','$workspace','$dashboardId','$title','$action','{"meta":{"title":"$title"}}','$actorId','$credentialSurface',${agentClientId?.let { "'$it'" } ?: "NULL"},'$occurredAt')""")
    } }

    companion object {
        @JvmField @RegisterExtension val postgres = EphemeralPostgresAuthoritySupport
        @JvmStatic @BeforeAll fun schemas() {
            postgres.verifyEphemeralIdentity()
            postgres.applyAuthoritySchemasTwice()
            val source = Path.of(System.getProperty("control.service.root"), "modules/pages/src/main/resources/schema-postgres.sql").toFile().readText()
            postgres.dataSource.connection.use { connection -> connection.createStatement().use { it.execute(source) } }
        }
    }
}
