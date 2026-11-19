package cc.monomer.execution.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExecutionServiceCompositionTest {
    @Test
    fun artifactStoreEnvironmentIsRequired() {
        assertFailsWith<IllegalArgumentException> {
            ExecutionServiceConfiguration.fromEnvironment(emptyMap())
        }
    }

    @Test
    fun canonicalArtifactStoreEnvironmentIsValidatedAndMapped() {
        val configuration = ExecutionServiceConfiguration.fromEnvironment(environment())

        assertEquals("127.0.0.1", configuration.listenAddress)
        assertEquals(11_099, configuration.listenPort)
        assertEquals("http://127.0.0.1:9000", configuration.artifactStore.endpoint.toString())
        assertEquals("us-east-1", configuration.artifactStore.region)
        assertEquals("monomer-execution-artifacts", configuration.artifactStore.bucket)
        assertEquals("artifacts/", configuration.artifactStore.prefix)
    }

    @Test
    fun deploymentBucketIsAcceptedAndNonCanonicalPrefixIsRejected() {
        val bucket = "monomer-dev-execution-artifacts-111122223333-us-east-1"
        val configured = ExecutionServiceConfiguration.fromEnvironment(
            environment() + ("MONOMER_EXECUTION_ARTIFACT_STORE_BUCKET" to bucket),
        )
        assertEquals(bucket, configured.artifactStore.bucket)
        assertFailsWith<IllegalArgumentException> {
            ExecutionServiceConfiguration.fromEnvironment(
                environment() + ("MONOMER_EXECUTION_ARTIFACT_STORE_PREFIX" to "staging/"),
            )
        }
    }

    @Test
    fun listenAndHealthPortsMustNotCollideWhenBothAreBound() {
        assertFailsWith<IllegalArgumentException> {
            ExecutionServiceConfiguration.fromEnvironment(
                environment() +
                    mapOf(
                        "MONOMER_EXECUTION_LISTEN_PORT" to "11099",
                        "MONOMER_EXECUTION_HEALTH_PORT" to "11099",
                    ),
            )
        }
    }

    @Test
    fun workloadRejectsStaticCredentialsAndLocalModeRequiresBothKeys() {
        val workload = environment() - setOf(
            "MONOMER_EXECUTION_ARTIFACT_STORE_CREDENTIAL_MODE",
            "MONOMER_EXECUTION_ARTIFACT_STORE_ACCESS_KEY",
            "MONOMER_EXECUTION_ARTIFACT_STORE_SECRET_KEY",
        )
        assertEquals("WORKLOAD", ExecutionServiceConfiguration.fromEnvironment(workload).artifactStore.credentialMode.name)
        for (key in listOf("ACCESS_KEY", "SECRET_KEY")) {
            assertFailsWith<IllegalArgumentException> {
                ExecutionServiceConfiguration.fromEnvironment(workload + ("MONOMER_EXECUTION_ARTIFACT_STORE_$key" to "fixture"))
            }
            assertFailsWith<IllegalArgumentException> {
                ExecutionServiceConfiguration.fromEnvironment(environment() - "MONOMER_EXECUTION_ARTIFACT_STORE_$key")
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ExecutionServiceConfiguration.fromEnvironment(workload + ("MONOMER_EXECUTION_ARTIFACT_STORE_CREDENTIAL_MODE" to "UNKNOWN"))
        }
    }

    @Test
    fun localWarehouseExceptionsRequireBothListenersToBeLoopback() {
        val configured = environment() + ("MONOMER_EXECUTION_LOCAL_DW_ENDPOINTS" to "127.0.0.1:15432")
        assertEquals(1, ExecutionServiceConfiguration.fromEnvironment(configured).localDwEndpoints.size)
        assertEquals(emptySet(), ExecutionServiceConfiguration.fromEnvironment(environment()).localDwEndpoints)
        for (key in listOf("MONOMER_EXECUTION_LISTEN_ADDRESS", "MONOMER_EXECUTION_HEALTH_BIND_ADDRESS")) {
            assertFailsWith<IllegalArgumentException> {
                ExecutionServiceConfiguration.fromEnvironment(configured + (key to "0.0.0.0"))
            }
        }
    }

    @Test
    fun sampleRootDefaultsToImmutableImagePathAndLocalOverrideMustBeAbsolute() {
        assertEquals("/opt/monomer/sample-data", ExecutionServiceConfiguration.fromEnvironment(environment()).sampleDataRoot.toString())
        assertEquals("/private/tmp/sample-data", ExecutionServiceConfiguration.fromEnvironment(environment() + ("MONOMER_EXECUTION_SAMPLE_DATA_ROOT" to "/private/tmp/sample-data")).sampleDataRoot.toString())
        assertFailsWith<IllegalArgumentException> {
            ExecutionServiceConfiguration.fromEnvironment(environment() + ("MONOMER_EXECUTION_SAMPLE_DATA_ROOT" to "relative"))
        }
    }

    private fun environment(): Map<String, String> =
        mapOf(
            "MONOMER_EXECUTION_ARTIFACT_STORE_ENDPOINT" to "http://127.0.0.1:9000",
            "MONOMER_EXECUTION_ARTIFACT_STORE_REGION" to "us-east-1",
            "MONOMER_EXECUTION_ARTIFACT_STORE_BUCKET" to "monomer-execution-artifacts",
            "MONOMER_EXECUTION_ARTIFACT_STORE_PREFIX" to "artifacts/",
            "MONOMER_EXECUTION_ARTIFACT_STORE_CREDENTIAL_MODE" to "LOCAL_TEST_STATIC",
            "MONOMER_EXECUTION_ARTIFACT_STORE_ACCESS_KEY" to "access",
            "MONOMER_EXECUTION_ARTIFACT_STORE_SECRET_KEY" to "secret",
        )
}
