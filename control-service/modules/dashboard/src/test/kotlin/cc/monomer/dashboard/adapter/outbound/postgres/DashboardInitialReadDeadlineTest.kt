@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.adapter.outbound.postgres

import cc.monomer.dashboard.domain.DashboardResolvedQuery

import cc.monomer.connector.api.ConnectorId
import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.api.DashboardSessionId
import cc.monomer.dashboard.application.DashboardReadDeadlineExceeded
import cc.monomer.dashboard.application.EquivalentQueryRunLookup
import cc.monomer.dashboard.domain.QueryRunState
import cc.monomer.identity.api.WorkspaceId
import kotlinx.serialization.json.JsonObject
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class DashboardInitialReadDeadlineTest {
    @Test
    fun `initial read applies remaining milliseconds and releases cancelled database work`() {
        val events = mutableListOf<String>()
        var timeoutMillis = 0L
        val emptyRows = proxy<ResultSet> { method, _ ->
            when (method) {
                "next" -> false
                "close" -> null
                else -> error(method)
            }
        }
        val connection = proxy<Connection> { method, arguments ->
            when (method) {
                "setAutoCommit", "setReadOnly" -> { events += method; null }
                "rollback", "close" -> { events += method; null }
                "prepareStatement" -> {
                    val sql = arguments[0] as String
                    proxy<PreparedStatement> { statementMethod, values ->
                        when (statementMethod) {
                            "setString" -> { timeoutMillis = (values[1] as String).removeSuffix("ms").toLong(); null }
                            "setObject", "close" -> null
                            "executeQuery" -> {
                                if (sql.contains("set_config")) {
                                    events += "timeout"
                                    emptyRows
                                } else {
                                    events += "query"
                                    throw SQLException("cancelled", "57014")
                                }
                            }
                            else -> error(statementMethod)
                        }
                    }
                }
                else -> error(method)
            }
        }
        val dataSource = proxy<DataSource> { method, _ ->
            if (method == "getConnection") connection else error(method)
        }
        assertFailsWith<DashboardReadDeadlineExceeded> {
            PostgresDashboardSessionRepository(dataSource).findInitial(
                WorkspaceId(Uuid.random()), DashboardId.generate(), DashboardSessionId.generate(), Instant.now().plusMillis(1500),
            )
        }
        assertTrue(timeoutMillis in 1..1500)
        assertEquals(listOf("setAutoCommit", "setReadOnly", "timeout", "query", "rollback", "close"), events)
    }

    @Test
    fun `expired initial deadline never acquires database connection`() {
        val dataSource = proxy<DataSource> { method, _ -> error("Unexpected $method") }
        assertFailsWith<DashboardReadDeadlineExceeded> {
            PostgresDashboardSessionRepository(dataSource).findInitial(
                WorkspaceId(Uuid.random()), DashboardId.generate(), DashboardSessionId.generate(), Instant.EPOCH,
            )
        }
    }

    @Test
    fun `expired equivalent lookup deadline never acquires database connection`() {
        val dataSource = proxy<DataSource> { method, _ -> error("Unexpected $method") }
        val lookup = EquivalentQueryRunLookup("query", ConnectorId.generate(), DashboardResolvedQuery.Semantic(JsonObject(emptyMap())), "SELECT 1", listOf("value"))
        val repository = PostgresQueryRunRepository(dataSource)
        assertFailsWith<DashboardReadDeadlineExceeded> {
            repository.findEquivalentRuns(
                WorkspaceId(Uuid.random()), DashboardId.generate(), DashboardSessionId.generate(), listOf(lookup),
                setOf(QueryRunState.SUCCEEDED), Instant.EPOCH,
            )
        }
        assertEquals(emptyList(), repository.findEquivalentRuns(
            WorkspaceId(Uuid.random()), DashboardId.generate(), DashboardSessionId.generate(), emptyList(),
            setOf(QueryRunState.SUCCEEDED), Instant.EPOCH,
        ))
    }

    private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, arguments ->
            call(method.name, arguments ?: emptyArray())
        } as T
}
