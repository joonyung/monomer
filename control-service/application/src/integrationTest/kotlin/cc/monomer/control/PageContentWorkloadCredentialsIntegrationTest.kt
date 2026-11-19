package cc.monomer.control

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import cc.monomer.pages.adapter.outbound.s3.pagecontent.S3PageContentObjectStorage

class PageContentWorkloadCredentialsIntegrationTest {

    @Test
    fun temporaryContainerCredentialsRefreshAtTheSignedRequestBoundary() = runBlocking {
        check(System.getenv("AWS_ACCESS_KEY_ID").isNullOrEmpty())
        check(System.getenv("AWS_WEB_IDENTITY_TOKEN_FILE").isNullOrEmpty())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val credentialReads = AtomicInteger()
        val signedTokens = CopyOnWriteArrayList<String>()
        server.createContext("/credentials") { exchange ->
            val generation = credentialReads.incrementAndGet()
            val expiration = Instant.now().plusSeconds(if (generation == 1) 30 else 3600)
            val response = """{"AccessKeyId":"fixture-access-$generation","SecretAccessKey":"fixture-secret-$generation","Token":"fixture-token-$generation","Expiration":"$expiration"}"""
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/") { exchange ->
            val token = exchange.requestHeaders.getFirst("X-Amz-Security-Token")
            val authorization = exchange.requestHeaders.getFirst("Authorization")
            check(token != null && authorization.contains("Credential=fixture-access-"))
            check(authorization.contains("x-amz-security-token"))
            signedTokens += token
            val response = "<InitiateMultipartUploadResult><Bucket>monomer-execution-artifacts</Bucket><Key>artifacts/test</Key><UploadId>fixture-upload</UploadId></InitiateMultipartUploadResult>".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        val properties = mapOf(
            "aws.containerCredentialsFullUri" to "http://127.0.0.1:${server.address.port}/credentials",
            "aws.profile" to "monomer-hermetic-missing-profile",
            "aws.sharedCredentialsFile" to "/var/empty/monomer-hermetic-missing-credentials",
            "aws.configFile" to "/var/empty/monomer-hermetic-missing-config",
            "aws.disableEc2Metadata" to "true",
        )
        val original = properties.keys.associateWith(System::getProperty)
        server.start()
        try {
            properties.forEach(System::setProperty)
            val settings = ControlObjectStorageProperties(
                URI("http://127.0.0.1:${server.address.port}"), "us-east-1",
                ControlArtifactCredentialMode.WORKLOAD, null, null,
                ControlObjectStoreBinding("monomer-page-content", "content"),
            )
            val composition = ControlObjectStorageComposition()
            (composition.pageContentCredentials(settings) as DefaultCredentialsProvider).use { credentials ->
                (composition.pageContentObjectStorage(settings, credentials) as S3PageContentObjectStorage).use { storage ->
                    repeat(2) { storage.createMultipartUpload("test", "text/plain") }
                    val signed = storage.presignUploadPart(
                        cc.monomer.pages.domain.content.PageContentObjectStorage.MultipartUpload("test", "fixture-upload"),
                        1, 1, java.time.Duration.ofMinutes(1),
                    )
                    assertTrue(signed.rawQuery.contains("X-Amz-Security-Token=fixture-token-"))
                }
            }
            assertTrue(credentialReads.get() >= 2)
            assertTrue(signedTokens.size >= 2)
            assertTrue(signedTokens.first() != signedTokens.last())
            assertEquals("fixture-token-${credentialReads.get()}", signedTokens.last())
        } finally {
            original.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
            server.stop(0)
        }
    }
}
