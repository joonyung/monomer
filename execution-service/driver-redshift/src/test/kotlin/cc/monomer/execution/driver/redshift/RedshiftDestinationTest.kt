package cc.monomer.execution.driver.redshift

import cc.monomer.execution.driver.jdbc.WarehouseJdbcConnectionFactory
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.sql.SQLException
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertFailsWith

class RedshiftDestinationTest {
    @Test
    fun jdbcDriverCannotConnectToUnapprovedLoopback() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 100
            assertFailsWith<SQLException> {
                WarehouseJdbcConnectionFactory(emptySet()).open("jdbc:redshift://localhost:${server.localPort}/fixture", Properties().apply {
                    setProperty("user", "fixture")
                    setProperty("password", "fixture")
                    setProperty("ssl", "false")
                })
            }
            assertFailsWith<SocketTimeoutException> { server.accept().close() }
        }
    }
}
