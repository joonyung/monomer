@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.adapter.outbound.postgres

import cc.monomer.dashboard.application.DashboardReadDeadlineExceeded
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

internal class PostgresDashboardJdbc(private val dataSource: DataSource) {
    fun requireOwnerTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "Dashboard owner transaction is required" }
        check(TransactionSynchronizationManager.hasResource(dataSource)) { "Dashboard transaction must use the owning DataSource" }
    }

    fun <T> queryOne(
        deadline: Instant?,
        sql: String,
        bind: (PreparedStatement) -> Unit,
        map: (ResultSet) -> T,
    ): T? = if (deadline == null) queryOne(sql, bind, map)
        else queryWithin(deadline, sql, bind) { rows -> if (rows.next()) map(rows) else null }

    fun <T> queryManyWithin(
        deadline: Instant,
        sql: String,
        bind: (PreparedStatement) -> Unit,
        map: (ResultSet) -> T,
    ): List<T> = queryWithin(deadline, sql, bind) { rows -> buildList { while (rows.next()) add(map(rows)) } }

    private fun <T> queryWithin(
        deadline: Instant,
        sql: String,
        bind: (PreparedStatement) -> Unit,
        read: (ResultSet) -> T,
    ): T {
        if (!Instant.now().isBefore(deadline)) throw DashboardReadDeadlineExceeded()
        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.isReadOnly = true
            try {
                val remainingMillis = java.time.Duration.between(Instant.now(), deadline).toMillis()
                if (remainingMillis <= 0) throw DashboardReadDeadlineExceeded()
                connection.prepareStatement("SELECT set_config('statement_timeout', ?, true)").use { statement ->
                    statement.setString(1, "${remainingMillis}ms")
                    statement.executeQuery().close()
                }
                connection.prepareStatement(sql).use { statement ->
                    bind(statement)
                    statement.executeQuery().use(read)
                }
            } catch (error: java.sql.SQLException) {
                if (error.sqlState == "57014") throw DashboardReadDeadlineExceeded()
                throw error
            } finally {
                connection.rollback()
            }
        }
    }

    fun <T> queryOne(
        sql: String,
        bind: (PreparedStatement) -> Unit,
        map: (ResultSet) -> T,
    ): T? = withConnection { connection ->
        connection.prepareStatement(sql).use { statement ->
            bind(statement)
            statement.executeQuery().use { resultSet -> if (resultSet.next()) map(resultSet) else null }
        }
    }

    fun <T> queryMany(
        sql: String,
        bind: (PreparedStatement) -> Unit,
        map: (ResultSet) -> T,
    ): List<T> = withConnection { connection ->
        connection.prepareStatement(sql).use { statement ->
            bind(statement)
            statement.executeQuery().use { resultSet -> buildList { while (resultSet.next()) add(map(resultSet)) } }
        }
    }

    fun update(sql: String, bind: (PreparedStatement) -> Unit): Int = withConnection { connection ->
        connection.prepareStatement(sql).use { statement -> bind(statement); statement.executeUpdate() }
    }

    fun <T> withConnection(block: (Connection) -> T): T {
        val connection = DataSourceUtils.getConnection(dataSource)
        return try {
            block(connection)
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
    }
}

internal fun PreparedStatement.setUuid(index: Int, value: Uuid) = setObject(index, value.toJavaUuid())
internal fun PreparedStatement.setNullableUuid(index: Int, value: Uuid?) = if (value == null) setObject(index, null) else setUuid(index, value)
internal fun PreparedStatement.setInstant(index: Int, value: Instant) = setTimestamp(index, Timestamp.from(value))
internal fun ResultSet.getUuid(column: String): Uuid = getObject(column, java.util.UUID::class.java).toKotlinUuid()
internal fun ResultSet.getNullableUuid(column: String): Uuid? = getObject(column, java.util.UUID::class.java)?.toKotlinUuid()
internal fun ResultSet.getInstant(column: String): Instant = getTimestamp(column).toInstant()
