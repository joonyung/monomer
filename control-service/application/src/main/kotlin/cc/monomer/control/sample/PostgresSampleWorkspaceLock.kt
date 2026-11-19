package cc.monomer.control.sample

import cc.monomer.identity.api.OrganizationId
import javax.sql.DataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class PostgresSampleWorkspaceLock(
    private val dataSource: DataSource,
    private val jdbcDispatcher: CoroutineDispatcher,
) {
    suspend fun <T> withLock(organizationId: OrganizationId, action: suspend () -> T): T = withContext(jdbcDispatcher) {
        dataSource.connection.use { connection ->
            var locked = false
            try {
                while (!locked) {
                    currentCoroutineContext().ensureActive()
                    locked = connection.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 0))").use { statement ->
                        statement.queryTimeout = 5
                        statement.setString(1, organizationId.toString())
                        statement.executeQuery().use { rows -> check(rows.next()); rows.getBoolean(1) }
                    }
                    if (!locked) delay(100)
                }
                action()
            } finally {
                if (locked) withContext(NonCancellable) {
                    try {
                        connection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))").use { statement ->
                            statement.queryTimeout = 5
                            statement.setString(1, organizationId.toString())
                            statement.executeQuery().use { rows -> check(rows.next() && rows.getBoolean(1)) }
                        }
                    } catch (failure: Throwable) {
                        runCatching { connection.abort { it.run() } }
                        throw failure
                    }
                }
            }
        }
    }
}
