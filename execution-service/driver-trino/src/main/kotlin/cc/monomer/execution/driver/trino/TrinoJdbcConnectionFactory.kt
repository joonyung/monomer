package cc.monomer.execution.driver.trino

import cc.monomer.execution.driver.jdbc.JdbcConnectionFactory
import cc.monomer.execution.driver.jdbc.WarehouseSocketFactory
import io.trino.jdbc.NonRegisteringTrinoDriver
import io.trino.jdbc.`$internal`.okhttp3.Call
import io.trino.jdbc.`$internal`.okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.sql.Connection
import java.util.Properties

class TrinoJdbcConnectionFactory(localEndpoints: Set<InetSocketAddress>) : JdbcConnectionFactory {
    private val driver = object : NonRegisteringTrinoDriver() {
        private val warehouseSocketFactory = WarehouseSocketFactory(localEndpoints)

        override fun wrapClient(client: OkHttpClient): Call.Factory = client.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .socketFactory(warehouseSocketFactory)
            .build()
    }

    override fun open(jdbcUrl: String, properties: Properties): Connection =
        requireNotNull(driver.connect(jdbcUrl, properties))
}
