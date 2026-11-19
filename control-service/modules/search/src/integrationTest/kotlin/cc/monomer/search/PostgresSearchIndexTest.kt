package cc.monomer.search

import cc.monomer.search.adapter.outbound.postgres.PostgresSearchIndex
import cc.monomer.search.api.SearchDocument
import cc.monomer.search.api.SearchMatch
import cc.monomer.search.api.SearchResourceKind
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

class PostgresSearchIndexTest {
    @Test fun `ranking isolation updates and rollback share the source transaction`() {
        PostgreSQLContainer(DockerImageName.parse("postgres:16.14-alpine3.24@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777").asCompatibleSubstituteFor("postgres")).use { postgres ->
            postgres.start()
            val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            dataSource.connection.use { connection -> connection.createStatement().use { statement ->
                statement.execute(checkNotNull(javaClass.getResource("/db/search/schema.sql")).readText())
                statement.execute("CREATE TABLE source_record (name text)")
            } }
            val index = PostgresSearchIndex(dataSource)
            val transaction = TransactionTemplate(DataSourceTransactionManager(dataSource))
            val workspace = UUID.randomUUID()
            val otherWorkspace = UUID.randomUUID()
            fun doc(path: String, name: String, description: String, structural: String) = SearchDocument(
                workspace, path, SearchResourceKind.METRIC, listOf(name), listOf(description), listOf(structural),
            )
            val exact = doc("workspace/metrics/z", "customer_revenue", "", "")
            assertFailsWith<IllegalStateException> { index.upsert(exact) }
            transaction.executeWithoutResult {
                index.upsert(exact)
                index.upsert(doc("workspace/metrics/a", "monthly customer revenue", "", ""))
                index.upsert(doc("workspace/metrics/b", "other", "customer revenue", ""))
                index.upsert(doc("workspace/metrics/c", "한글", "", "customerRevenue id in or 매출을"))
                index.upsert(exact.copy(workspaceId = otherWorkspace, path = "workspace/metrics/foreign"))
                assertEquals("workspace/metrics/z", index.candidates(workspace, "workspace", setOf(SearchResourceKind.METRIC), "customer_revenue", SearchMatch.WHOLE_TOKENS, 1, 0).single())
                assertEquals(listOf("workspace/metrics/c"), index.candidates(workspace, "workspace", setOf(SearchResourceKind.METRIC), "id in or 매출을", SearchMatch.WHOLE_TOKENS, 20, 0))
                assertEquals(emptyList(), index.candidates(workspace, "workspace/metrics/z-other", setOf(SearchResourceKind.METRIC), "customer", SearchMatch.WHOLE_TOKENS, 20, 0))
                assertEquals(emptyList(), index.candidates(workspace, "workspace", setOf(SearchResourceKind.DASHBOARD), "customer", SearchMatch.WHOLE_TOKENS, 20, 0))
                assertEquals(emptyList(), index.candidates(workspace, "workspace", setOf(SearchResourceKind.METRIC), "custo reven", SearchMatch.WHOLE_TOKENS, 20, 0))
                assertEquals(4, index.candidates(workspace, "workspace", setOf(SearchResourceKind.METRIC), "custo reven", SearchMatch.TOKEN_PREFIX, 20, 0).size)
            }
            assertFailsWith<IllegalStateException> {
                transaction.executeWithoutResult {
                    org.springframework.jdbc.core.JdbcTemplate(dataSource).update("INSERT INTO source_record VALUES ('rollback')")
                    index.deleteSubtree(workspace, "workspace/metrics")
                    error("source and derived mutation both fail")
                }
            }
            transaction.executeWithoutResult {
                assertEquals(0, org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForObject("SELECT count(*) FROM source_record", Int::class.java))
                assertEquals(4, index.candidates(workspace, "workspace", setOf(SearchResourceKind.METRIC), "customer revenue", SearchMatch.WHOLE_TOKENS, 20, 0).size)
                index.upsert(exact.copy(primaryNames = listOf("new_name")))
                assertEquals(listOf("workspace/metrics/z"), index.candidates(workspace, "workspace", setOf(SearchResourceKind.METRIC), "newName", SearchMatch.WHOLE_TOKENS, 20, 0))
                index.deleteSubtree(workspace, "workspace/metrics/z")
                assertEquals(emptyList(), index.candidates(workspace, "workspace", setOf(SearchResourceKind.METRIC), "newName", SearchMatch.WHOLE_TOKENS, 20, 0))
            }
        }
    }
}
