package cc.monomer.execution.driver.bigquery

import com.google.auth.oauth2.ServiceAccountCredentials
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.KeyPairGenerator
import java.time.Duration
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoogleBigQueryCredentialDestinationTest {
    private val factory = GoogleBigQueryGatewayFactory(1_000_000, Duration.ofSeconds(30))
    private val timeout = BigQueryRpcTimeout(100, 100, 100)

    @Test
    fun standardGoogleCredentialDestinationsArePreserved() {
        for (extraFields in listOf("", """, "token_uri":"https://oauth2.googleapis.com/token", "universe_domain":"googleapis.com"""")) {
            withCredential(extraFields) { characters ->
                val client = factory.createClient("test-project", "US", characters, timeout)
                val credentials = client.options.credentials as ServiceAccountCredentials
                assertEquals("https://oauth2.googleapis.com/token", credentials.tokenServerUri.toString())
                assertEquals("googleapis.com", credentials.universeDomain)
            }
        }
    }

    @Test
    fun unapprovedTokenDestinationsAreRejected() {
        for (tokenUri in listOf(
            "http://127.0.0.1/token",
            "https://external.invalid/token",
            "https://accounts.google.com/o/oauth2/token",
            "http://oauth2.googleapis.com/token",
            "https://oauth2.googleapis.com:443/token",
            "https://oauth2.googleapis.com/token?target=external",
            "https://oauth2.googleapis.com/token#fragment",
            "https://user@oauth2.googleapis.com/token",
            "https://oauth2.googleapis.com/token/",
            "https://oauth2.googleapis.com/other",
        )) {
            withCredential(""", "token_uri":"$tokenUri"""") { characters ->
                val failure = assertFailsWith<IllegalArgumentException> {
                    factory.createClient("test-project", "US", characters, timeout)
                }
                assertEquals("BigQuery service account token_uri must be https://oauth2.googleapis.com/token", failure.message)
            }
        }
    }

    @Test
    fun nonGoogleUniversesAreRejected() {
        for (universeDomain in listOf("external.invalid", "googleapis.com.external.invalid")) {
            withCredential(""", "universe_domain":"$universeDomain"""") { characters ->
                val failure = assertFailsWith<IllegalArgumentException> {
                    factory.createClient("test-project", "US", characters, timeout)
                }
                assertEquals("BigQuery service account universe_domain must be googleapis.com", failure.message)
            }
        }
    }

    @Test
    fun tokenDestinationRejectionHappensWithoutOpeningASocket() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 100
            withCredential(""", "token_uri":"http://localhost:${server.localPort}/token"""") { characters ->
                assertFailsWith<IllegalArgumentException> {
                    factory.createClient("test-project", "US", characters, timeout)
                }
            }
            assertFailsWith<SocketTimeoutException> { server.accept().close() }
        }
    }

    private fun withCredential(extraFields: String, operation: (CharArray) -> Unit) {
        val characters = """{"type":"service_account","client_id":"fixture-client","client_email":"fixture@test-project.iam.gserviceaccount.com","private_key_id":"fixture-key","private_key":"$privateKey"$extraFields}""".toCharArray()
        try {
            operation(characters)
        } finally {
            characters.fill('\u0000')
        }
    }

    private companion object {
        val privateKey: String = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            .generateKeyPair().private.encoded.let { encoded ->
                try {
                    "-----BEGIN PRIVATE KEY-----\\n${Base64.getEncoder().encodeToString(encoded)}\\n-----END PRIVATE KEY-----\\n" // gitleaks:allow
                } finally {
                    encoded.fill(0)
                }
            }
    }
}
