package cc.monomer.control

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource

class ControlObjectStoragePropertiesTest {
    @Test
    fun workloadAndExplicitLocalCredentialsAreAccepted() {
        properties(ControlArtifactCredentialMode.WORKLOAD, null, null)
        properties(ControlArtifactCredentialMode.LOCAL_TEST_STATIC, "fixture-access", "fixture-secret")
    }

    @Test
    fun mixedAndMissingCredentialsAreRejected() {
        for ((access, secret) in listOf("access" to null, null to "secret", "access" to "secret")) {
            assertFailsWith<IllegalArgumentException> {
                properties(ControlArtifactCredentialMode.WORKLOAD, access, secret)
            }
        }
        for ((access, secret) in listOf(null to null, "access" to null, null to "secret", "" to "")) {
            assertFailsWith<IllegalArgumentException> {
                properties(ControlArtifactCredentialMode.LOCAL_TEST_STATIC, access, secret)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            properties(ControlArtifactCredentialMode.LOCAL_TEST_STATIC, "access", "secret")
                .copy(endpoint = URI("https://s3.example.test"))
        }
    }

    @Test
    fun applicationYamlDefaultsToWorkloadWithoutStaticSecrets() {
        val environment = StandardEnvironment()
        environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
        YamlPropertySourceLoader().load("application", ClassPathResource("application.yaml"))
            .forEach(environment.propertySources::addLast)
        val bound = Binder.get(environment).bind(
            "monomer.control.object-storage", Bindable.of(ControlObjectStorageProperties::class.java),
        ).get()
        assertEquals(ControlArtifactCredentialMode.WORKLOAD, bound.credentialMode)
        assertEquals("", bound.accessKey)
        assertEquals("", bound.secretKey)
        val artifact = Binder.get(environment).bind(
            "monomer.control.execution-runtime.artifact-store", Bindable.of(ControlArtifactStoreProperties::class.java),
        ).get()
        assertEquals(ControlArtifactCredentialMode.WORKLOAD, artifact.credentialMode)
        assertEquals("", artifact.localTestAccessKey)
        assertEquals("", artifact.localTestSecretKey)
        assertFailsWith<IllegalArgumentException> { artifact.copy(localTestAccessKey = "mixed-access") }
        assertFailsWith<IllegalArgumentException> { artifact.copy(localTestSecretKey = "mixed-secret") }
    }

    private fun properties(mode: ControlArtifactCredentialMode, access: String?, secret: String?) =
        ControlObjectStorageProperties(
            URI("http://127.0.0.1:9000"), "us-east-1", mode, access, secret,
            ControlObjectStoreBinding("monomer-page-content", "content"),
        )
}
