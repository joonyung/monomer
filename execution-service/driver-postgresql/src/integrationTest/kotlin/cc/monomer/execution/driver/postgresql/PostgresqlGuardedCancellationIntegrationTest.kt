package cc.monomer.execution.driver.postgresql

import java.sql.SQLException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.extension.RegisterExtension
import org.postgresql.PGConnection

class PostgresqlGuardedCancellationIntegrationTest {
    @Test
    fun queryAndCancelUseTheApprovedEphemeralWarehouseSocket() {
        EphemeralPostgresqlWarehouse.openGuardedConnection().use { connection ->
            val backendPid = connection.unwrap(PGConnection::class.java).backendPID
            connection.createStatement().use { statement ->
                Executors.newSingleThreadExecutor().use { executor ->
                    val query = executor.submit<String> {
                        try {
                            statement.executeQuery("SELECT pg_sleep(10)").close()
                            "COMPLETED"
                        } catch (failure: SQLException) {
                            failure.sqlState
                        }
                    }
                    EphemeralPostgresqlWarehouse.openAdminConnection().use { observer ->
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                        var running = false
                        while (!running && System.nanoTime() < deadline) {
                            observer.prepareStatement("SELECT wait_event = 'PgSleep' FROM pg_stat_activity WHERE pid = ?").use { check ->
                                check.setInt(1, backendPid)
                                check.executeQuery().use { rows -> running = rows.next() && rows.getBoolean(1) }
                            }
                            if (!running) Thread.sleep(10)
                        }
                        assertTrue(running, "query must reach the disposable warehouse before cancellation")
                    }
                    statement.cancel()
                    assertEquals("57014", query.get(3, TimeUnit.SECONDS))
                }
            }
        }
    }

    companion object {
        @JvmField
        @RegisterExtension
        val ephemeralWarehouse = EphemeralPostgresqlWarehouse
    }
}
