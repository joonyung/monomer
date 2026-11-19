package cc.monomer.execution.driver.jdbc

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WarehouseSocketFactoryTest {
    @Test
    fun privateAndSpecialAddressesAreRejectedFromTheirBytes() {
        for (literal in listOf(
            "0.0.0.0", "10.0.0.1", "100.64.0.1", "127.0.0.1", "169.254.169.254",
            "172.16.0.1", "192.0.0.1", "192.168.0.1", "192.0.2.1", "192.88.99.1",
            "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255",
            "::", "::1", "fc00::1", "fe80::1", "ff02::1", "64:ff9b::a00:1",
            "2001::1", "2001:2::1", "2001:db8::1", "2002:a00:1::1", "3fff::1",
            "::ffff:127.0.0.1", "::ffff:169.254.169.254", "::ffff:10.0.0.1",
        )) {
            val address = InetAddress.getByAddress("public-name.example", InetAddress.getByName(literal).address)
            assertFalse(WarehouseSocketFactory.isPublicAddress(address), literal)
            WarehouseSocketFactory(emptySet()).createSocket().use { socket ->
                assertEquals("Warehouse destination is not permitted", assertFailsWith<SocketException> {
                    socket.connect(InetSocketAddress(address, 443), 50)
                }.message)
            }
        }
        for (literal in listOf("8.8.8.8", "1.1.1.1", "2001:4860:4860::8888", "2606:4700:4700::1111")) {
            assertTrue(WarehouseSocketFactory.isPublicAddress(InetAddress.getByName(literal)))
        }
    }

    @Test
    fun localExceptionConnectsOnlyTheExactResolvedEndpointWithoutResolvingTheHostnameAgain() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 100
            val destination = InetSocketAddress(
                InetAddress.getByAddress("does-not-resolve.invalid", byteArrayOf(127, 0, 0, 1)), server.localPort,
            )
            WarehouseSocketFactory(emptySet()).createSocket().use { socket ->
                assertFailsWith<SocketException> { socket.connect(destination, 100) }
            }
            assertFailsWith<SocketTimeoutException> { server.accept().close() }
            val factory = WarehouseSocketFactory(setOf(InetSocketAddress("127.0.0.1", server.localPort)))
            factory.createSocket().use { socket ->
                socket.connect(destination, 100)
                server.accept().use { assertTrue(socket.isConnected) }
            }
            factory.createSocket().use { socket ->
                assertFailsWith<SocketException> { socket.connect(InetSocketAddress("127.0.0.2", server.localPort), 100) }
            }
        }
    }

    @Test
    fun everyResolvedAddressIsCheckedEvenWhenTheHostnameWasPreviouslyPublic() {
        val publicAnswer = InetAddress.getByAddress("same.example", byteArrayOf(8, 8, 8, 8))
        val privateAnswer = InetAddress.getByAddress("same.example", byteArrayOf(127, 0, 0, 1))
        assertTrue(WarehouseSocketFactory.isPublicAddress(publicAnswer))
        WarehouseSocketFactory(emptySet()).createSocket().use { socket ->
            assertFailsWith<SocketException> { socket.connect(InetSocketAddress(privateAnswer, 443), 100) }
        }
        WarehouseSocketFactory(emptySet()).createSocket().use { socket ->
            assertFailsWith<SocketException> { socket.connect(InetSocketAddress.createUnresolved("same.example", 443), 100) }
        }
    }

    @Test
    fun localExceptionGrammarCannotAdmitNamesRangesOrMetadata() {
        for (value in listOf("localhost:1234", "127.0.0.1:*", "127.0.0.0/8:1234", "0.0.0.0:1234",
            "169.254.169.254:80", "10.0.0.1:1234", "127.1:1234", "127.0.0.1:0", "127.0.0.1:65536",
            "[deadbeef]:1234", "127.999.0.1:1234", "[:::]:1234", "[::ffff:127.0.0.1]:1234", "[fe80::1]:1234", "127.0.0.1:1234,")) {
            assertFailsWith<IllegalArgumentException> { WarehouseSocketFactory.parseLocalEndpoints(value) }
        }
        val endpoints = WarehouseSocketFactory.parseLocalEndpoints("127.0.0.1:15432,[::1]:18443")
        assertEquals(endpoints, WarehouseSocketFactory.parseLocalEndpoints(WarehouseSocketFactory.serializeLocalEndpoints(endpoints)))
    }
}
