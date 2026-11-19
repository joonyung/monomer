package cc.monomer.execution.driver.postgresql

import cc.monomer.execution.driver.jdbc.WarehouseJdbcConnectionFactory
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.sql.SQLException
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PostgresqlDestinationTest {
    @Test
    fun jdbcDriverCannotConnectToUnapprovedLoopback() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 100
            assertFailsWith<SQLException> {
                WarehouseJdbcConnectionFactory(emptySet()).open("jdbc:postgresql://localhost:${server.localPort}/fixture", Properties().apply {
                    setProperty("user", "fixture")
                    setProperty("password", "fixture")
                    setProperty("sslmode", "disable")
                })
            }
            assertFailsWith<SocketTimeoutException> { server.accept().close() }
        }
    }
}
