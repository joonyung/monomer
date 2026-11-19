package cc.monomer.control

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ControlArtifactStoreLoopbackTest {
    @Test
    fun `local static credentials accept every loopback endpoint form and nothing else`() {
        listOf("http://localhost:9000", "http://127.0.0.1:9000", "http://[::1]:9000").forEach { endpoint ->
            localStaticArtifactStore(URI(endpoint))
        }
        assertFailsWith<IllegalArgumentException> { localStaticArtifactStore(URI("http://minio.internal:9000")) }
    }

    private fun localStaticArtifactStore(endpoint: URI) = ControlArtifactStoreProperties(
        endpoint = endpoint,
        region = "us-east-1",
        bucket = "monomer-test",
        objectPrefix = "artifacts/",
        pathStyleAccessEnabled = true,
        credentialMode = ControlArtifactCredentialMode.LOCAL_TEST_STATIC,
        localTestAccessKey = "local-access",
        localTestSecretKey = "local-secret",
        allowLocalTestStaticCredentials = true,
    )
}
