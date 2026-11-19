package cc.monomer.execution.driver.jdbc

import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.util.Properties
import javax.net.SocketFactory

class WarehouseSocketFactory(localEndpoints: Set<InetSocketAddress>) : SocketFactory() {
    private val localEndpoints = localEndpoints.toSet().also { endpoints ->
        require(endpoints.all { !it.isUnresolved && it.address.isLoopbackAddress && it.port in 1..65535 })
    }

    constructor(argument: String) : this(parseLocalEndpoints(argument))

    override fun createSocket(): Socket = object : Socket(Proxy.NO_PROXY) {
        override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)

        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val destination = endpoint as? InetSocketAddress
                ?: throw SocketException("Warehouse destination is not permitted")
            if (destination.isUnresolved ||
                (!isPublicAddress(destination.address) && destination !in localEndpoints)
            ) {
                throw SocketException("Warehouse destination is not permitted")
            }
            super.connect(destination, timeout)
        }
    }

    override fun createSocket(host: String, port: Int): Socket =
        connected(InetSocketAddress(host, port), null)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        connected(InetSocketAddress(host, port), null)

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        connected(InetSocketAddress(host, port), InetSocketAddress(localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
        connected(InetSocketAddress(host, port), InetSocketAddress(localHost, localPort))

    private fun connected(destination: InetSocketAddress, local: InetSocketAddress?): Socket {
        val socket = createSocket()
        try {
            if (local != null) socket.bind(local)
            socket.connect(destination)
            return socket
        } catch (failure: Exception) {
            socket.close()
            throw failure
        }
    }

    companion object {
        fun parseLocalEndpoints(value: String): Set<InetSocketAddress> {
            if (value.isEmpty()) return emptySet()
            return value.split(',').map { entry ->
                val ipv4 = Regex("(127\\.(?:0|[1-9][0-9]{0,2})\\.(?:0|[1-9][0-9]{0,2})\\.(?:0|[1-9][0-9]{0,2})):([1-9][0-9]{0,4})").matchEntire(entry)
                val ipv6 = Regex("\\[([0-9a-fA-F:]+)]:(?:([1-9][0-9]{0,4}))").matchEntire(entry)
                val match = ipv4 ?: ipv6
                require(match != null) { "Local warehouse endpoints must be exact loopback IP:port entries" }
                val host = match.groupValues[1]
                val port = match.groupValues[2].toInt()
                require((ipv4 == null || host.split('.').all { it.toInt() in 0..255 }) &&
                    (ipv6 == null || ':' in host)) {
                    "Local warehouse endpoints must be exact loopback IP:port entries"
                }
                val address = try { InetAddress.getByName(host) } catch (_: Exception) {
                    throw IllegalArgumentException("Local warehouse endpoints must be exact loopback IP:port entries")
                }
                require(address.isLoopbackAddress && port in 1..65535 &&
                    (ipv6 == null || address is Inet6Address)) {
                    "Local warehouse endpoints must be exact loopback IP:port entries"
                }
                InetSocketAddress(address, port)
            }.toSet()
        }

        fun serializeLocalEndpoints(endpoints: Set<InetSocketAddress>): String = endpoints.joinToString(",") {
            val host = it.address.hostAddress
            "${if (it.address is Inet6Address) "[$host]" else host}:${it.port}"
        }

        fun isLoopbackLiteral(value: String): Boolean = try {
            parseLocalEndpoints("${if (':' in value) "[$value]" else value}:1").single().address.isLoopbackAddress
        } catch (_: Exception) {
            false
        }

        internal fun isPublicAddress(address: InetAddress): Boolean {
            val bytes = address.address
            return if (bytes.size == 4) {
                IPV4_DENIED.none { (network, bits) -> matchesPrefix(bytes, network, bits) }
            } else {
                bytes.size == 16 && (bytes[0].toInt() and 0xe0) == 0x20 &&
                    IPV6_DENIED.none { (network, bits) -> matchesPrefix(bytes, network, bits) }
            }
        }

        private fun matchesPrefix(address: ByteArray, network: ByteArray, bits: Int): Boolean =
            (0 until bits).all { bit ->
                (address[bit / 8].toInt() and (0x80 shr (bit % 8))) ==
                    (network[bit / 8].toInt() and (0x80 shr (bit % 8)))
            }

        private val IPV4_DENIED = listOf(
            "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
            "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16",
            "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/3",
        ).map { prefix -> prefix.substringBefore('/').let(InetAddress::getByName).address to prefix.substringAfter('/').toInt() }

        private val IPV6_DENIED = listOf("2001::/23", "2001:db8::/32", "2002::/16", "3fff::/20")
            .map { prefix -> prefix.substringBefore('/').let(InetAddress::getByName).address to prefix.substringAfter('/').toInt() }
    }
}

class WarehouseJdbcConnectionFactory(localEndpoints: Set<InetSocketAddress>) : JdbcConnectionFactory {
    private val socketFactoryArgument = WarehouseSocketFactory.serializeLocalEndpoints(localEndpoints)

    init {
        WarehouseSocketFactory(localEndpoints)
    }

    override fun open(jdbcUrl: String, properties: Properties): java.sql.Connection {
        require('?' !in jdbcUrl && ';' !in jdbcUrl) { "Warehouse JDBC URL must not contain connection properties" }
        val propertyName = when {
            jdbcUrl.startsWith("jdbc:postgresql:") -> "socketFactory"
            jdbcUrl.startsWith("jdbc:redshift:") -> "socketfactory"
            else -> throw IllegalArgumentException("Unsupported warehouse JDBC driver")
        }
        val guardedProperties = Properties().apply {
            putAll(properties)
            setProperty(propertyName, WarehouseSocketFactory::class.java.name)
            setProperty(if (propertyName == "socketFactory") "socketFactoryArg" else "socketfactoryarg", socketFactoryArgument)
        }
        return try {
            DriverManagerJdbcConnectionFactory.open(jdbcUrl, guardedProperties)
        } finally {
            guardedProperties.clear()
        }
    }
}
