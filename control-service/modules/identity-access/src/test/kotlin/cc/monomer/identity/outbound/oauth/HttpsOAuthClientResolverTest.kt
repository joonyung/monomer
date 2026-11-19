package cc.monomer.identity.outbound.oauth

import cc.monomer.identity.api.OAuthClient
import java.net.InetAddress
import io.mockk.*
import okhttp3.OkHttpClient
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class HttpsOAuthClientResolverTest {
    private val client = OAuthClient("monomer-native", "Monomer CLI", setOf("http://127.0.0.1/callback", "https://app.example/callback"))
    private val resolver = HttpsOAuthClientResolver(mapOf(client.clientId to client))

    @Test fun `native callback varies port alone`() {
        assertTrue(resolver.acceptsRedirect(client,"http://127.0.0.1:49152/callback"))
        listOf("http://localhost:49152/callback", "http://127.0.0.1:49152/other", "http://127.0.0.1:49152/callback?next=x", "https://evil.example/callback", "https://app.example:443/callback", "https://app.example/callback#fragment").forEach {
            assertFalse(resolver.acceptsRedirect(client,it),it)
        }
    }
    @Test fun `metadata URL rejects insecure local and malformed targets`() = runBlocking {
        listOf("http://127.0.0.1/metadata", "https://127.0.0.1/metadata", "https://user:secret@example.com/meta", "https://example.com/meta#fragment", "https://example.com:444/meta").forEach {
            assertNull(resolver.resolve(it))
        }
        assertEquals(client,resolver.resolve("monomer-native"))
    }
    @Test fun `metadata IP literals are rejected before any HTTP call is created`() = runBlocking<Unit> {
        mockkConstructor(OkHttpClient::class)
        try {
            every { anyConstructed<OkHttpClient>().newCall(any()) } throws AssertionError("HTTP transport reached")
            val guarded = HttpsOAuthClientResolver(emptyMap())
            listOf(
                "127.0.0.1", "127.0.0.1:443", "10.0.0.1", "169.254.169.254", "1.1.1.1",
                "2130706433", "127.1", "127.0.1", "0177.0.0.1", "0x7f000001", "0x7f.0.0.1",
                "0x7f.0x0.0x0.0x1", "127.0.0.1.", "%31%32%37.0.0.1", "localhost",
                "[::1]", "[::ffff:127.0.0.1]", "[::ffff:7f00:1]", "[fe80::1%25eth0]",
                "[fe80::1%eth0]", "[2606:4700:4700::1111]",
            ).forEach { host -> assertNull(guarded.resolve("https://$host/metadata.json"), host) }
            verify(exactly = 0) { anyConstructed<OkHttpClient>().newCall(any()) }
            assertFailsWith<AssertionError> { guarded.resolve("https://client.example/metadata.json") }
            verify(exactly = 1) { anyConstructed<OkHttpClient>().newCall(any()) }
        } finally { unmockkConstructor(OkHttpClient::class) }
    }
    @Test fun `CIMD protocol metadata requires bound public code client`() {
        val id="https://client.example/metadata.json"
        val valid="""{"client_id":"$id","client_name":"Client","token_endpoint_auth_method":"none","grant_types":["authorization_code","refresh_token"],"response_types":["code"],"redirect_uris":["https://client.example/callback"]}"""
        assertEquals("Client",resolver.parseMetadata(id,valid)?.clientName)
        assertNull(resolver.parseMetadata("https://other.example/metadata.json",valid))
        listOf(valid.replace("none","client_secret_basic"),valid.replace("authorization_code","client_credentials"),valid.replace("[\"code\"]","[\"token\"]"),valid.replace("https://client.example/callback","https://client.example/callback#fragment"),"[]","invalid").forEach {
            assertNull(resolver.parseMetadata(id,it))
        }
    }
    @Test fun `Claude published metadata supports code flow despite additional grant advertisement`() {
        val id = "https://claude.ai/oauth/mcp-oauth-client-metadata"
        val callback = "https://claude.ai/api/mcp/auth_callback"
        val metadata = """{
            "client_id":"$id",
            "client_name":"Claude",
            "redirect_uris":["$callback"],
            "grant_types":["authorization_code","refresh_token","urn:ietf:params:oauth:grant-type:jwt-bearer"],
            "response_types":["code"],
            "token_endpoint_auth_method":"none"
        }"""
        assertEquals(OAuthClient(id, "Claude", setOf(callback)), resolver.parseMetadata(id, metadata))
        assertNull(resolver.parseMetadata(id, metadata.replace("\"authorization_code\",", "")))
        assertNull(resolver.parseMetadata(id, metadata.replace("\"authorization_code\"", "42")))
        assertNull(resolver.parseMetadata(id, metadata.replace("\"authorization_code\"", "{}")))
    }
    @Test fun `DNS rejects nonpublic and transition ranges`() {
        listOf("127.0.0.1","10.0.0.1","172.16.1.1","192.168.1.1","169.254.169.254","100.64.0.1","198.18.0.1","224.0.0.1","::1","fc00::1","fe80::1","::ffff:127.0.0.1","2002:7f00:1::1","2001::1").forEach {
            assertFalse(HttpsOAuthClientResolver.publicAddress(InetAddress.getByName(it)),it)
        }
        assertTrue(HttpsOAuthClientResolver.publicAddress(InetAddress.getByName("1.1.1.1")))
        assertTrue(HttpsOAuthClientResolver.publicAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }
}
