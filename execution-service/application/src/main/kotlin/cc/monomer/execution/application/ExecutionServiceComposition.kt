package cc.monomer.execution.application

import cc.monomer.execution.storage.s3.S3ArtifactStoreSettings
import cc.monomer.execution.storage.s3.S3ArtifactCredentialMode
import java.nio.file.Files
import java.net.URI
import java.net.InetSocketAddress
import cc.monomer.execution.driver.jdbc.WarehouseSocketFactory
import java.nio.file.Path

data class ExecutionServiceConfiguration(
    val listenAddress: String,
    val listenPort: Int,
    val healthBindAddress: String,
    val healthPort: Int,
    val warehouseTrustCertificatePath: Path,
    val sampleDataRoot: Path,
    val localDwEndpoints: Set<InetSocketAddress>,
    val artifactStore: S3ArtifactStoreSettings,
) {
    init {
        WarehouseSocketFactory(localDwEndpoints)
        require(localDwEndpoints.isEmpty() ||
            (WarehouseSocketFactory.isLoopbackLiteral(listenAddress) &&
                WarehouseSocketFactory.isLoopbackLiteral(healthBindAddress))) {
            "Local warehouse exceptions require loopback Execution and health listeners"
        }
        require(listenAddress.isNotBlank())
        require(listenPort in 0..65_535)
        require(healthBindAddress.isNotBlank())
        require(healthPort in 0..65_535)
        if (listenPort != 0 && healthPort != 0 && listenPort == healthPort) {
            throw IllegalArgumentException(
                "gRPC listen port and health port must not share the same TCP port",
            )
        }
    }

    companion object {
        fun fromEnvironment(environment: Map<String, String>): ExecutionServiceConfiguration =
            EnvironmentConfigurationParser(environment).parse()
    }
}

enum class ExecutionServiceStatus {
    READY,
}

data class LocalHealth(
    val status: ExecutionServiceStatus,
)

class ExecutionServiceComposition {
    fun start(): LocalHealth = LocalHealth(status = ExecutionServiceStatus.READY)
}

internal class EnvironmentConfigurationParser(
    private val environment: Map<String, String>,
) {
    fun parse(): ExecutionServiceConfiguration =
        ExecutionServiceConfiguration(
            listenAddress =
                environment["MONOMER_EXECUTION_LISTEN_ADDRESS"]?.trim()
                    ?.also { value -> require(value.isNotEmpty()) }
                    ?: DEFAULT_LISTEN_ADDRESS,
            listenPort =
                environment["MONOMER_EXECUTION_LISTEN_PORT"]
                    ?.let { value ->
                        parseInteger("MONOMER_EXECUTION_LISTEN_PORT", value, 0..65_535)
                    }
                    ?: DEFAULT_LISTEN_PORT,
            healthBindAddress =
                environment["MONOMER_EXECUTION_HEALTH_BIND_ADDRESS"]?.trim()
                    ?.also { value -> require(value.isNotEmpty()) }
                    ?: DEFAULT_HEALTH_BIND_ADDRESS,
            healthPort =
                environment["MONOMER_EXECUTION_HEALTH_PORT"]
                    ?.let { value ->
                        parseInteger("MONOMER_EXECUTION_HEALTH_PORT", value, 0..65_535)
                    }
                    ?: DEFAULT_HEALTH_PORT,
            warehouseTrustCertificatePath = warehouseTrustCertificatePath(),
            sampleDataRoot = Path.of(environment["MONOMER_EXECUTION_SAMPLE_DATA_ROOT"] ?: "/opt/monomer/sample-data").also {
                require(it.isAbsolute) { "MONOMER_EXECUTION_SAMPLE_DATA_ROOT must be absolute" }
            },
            localDwEndpoints = WarehouseSocketFactory.parseLocalEndpoints(
                environment["MONOMER_EXECUTION_LOCAL_DW_ENDPOINTS"] ?: "",
            ),
            artifactStore = artifactStore(),
        )

    private fun warehouseTrustCertificatePath(): Path {
        val trustDirectory = environment["MONOMER_EXECUTION_WAREHOUSE_TRUST_DIRECTORY"]?.trim()
        if (trustDirectory.isNullOrEmpty()) {
            return ABSENT_WAREHOUSE_TRUST_CERTIFICATE_PATH
        }
        val directory =
            Path.of(trustDirectory).normalize().also { path ->
                require(path.isAbsolute && Files.isDirectory(path) && Files.isReadable(path)) {
                    "MONOMER_EXECUTION_WAREHOUSE_TRUST_DIRECTORY must reference a readable absolute directory"
                }
            }
        return Path.of(directory.resolve("ca.pem").toString()).normalize().also { path ->
            require(path.isAbsolute && Files.isRegularFile(path) && Files.isReadable(path)) {
                "MONOMER_EXECUTION_WAREHOUSE_TRUST_DIRECTORY/ca.pem must be a readable absolute regular file"
            }
        }
    }

    private fun artifactStore(): S3ArtifactStoreSettings =
        S3ArtifactStoreSettings(
            endpoint =
                URI.create(
                    requireEnvironment("MONOMER_EXECUTION_ARTIFACT_STORE_ENDPOINT"),
                ),
            region = requireEnvironment("MONOMER_EXECUTION_ARTIFACT_STORE_REGION"),
            bucket = requireEnvironment("MONOMER_EXECUTION_ARTIFACT_STORE_BUCKET"),
            prefix = requireEnvironment("MONOMER_EXECUTION_ARTIFACT_STORE_PREFIX"),
            credentialMode = S3ArtifactCredentialMode.valueOf(
                environment["MONOMER_EXECUTION_ARTIFACT_STORE_CREDENTIAL_MODE"] ?: "WORKLOAD",
            ),
            accessKey = environment["MONOMER_EXECUTION_ARTIFACT_STORE_ACCESS_KEY"],
            secretKey = environment["MONOMER_EXECUTION_ARTIFACT_STORE_SECRET_KEY"],
        ).also { settings ->
            require(settings.prefix == CANONICAL_ARTIFACT_PREFIX)
        }

    private fun requireEnvironment(name: String): String =
        environment[name]?.trim()?.takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("$name must be nonblank")

    private fun parseInteger(
        name: String,
        value: String,
        range: IntRange,
    ): Int =
        value.toIntOrNull()
            ?.takeIf(range::contains)
            ?: throw IllegalArgumentException("$name must be in ${range.first}..${range.last}")

    private companion object {
        const val DEFAULT_LISTEN_ADDRESS = "127.0.0.1"
        const val DEFAULT_LISTEN_PORT = 11_099
        const val DEFAULT_HEALTH_BIND_ADDRESS = "127.0.0.1"
        const val DEFAULT_HEALTH_PORT = 11_100
        val ABSENT_WAREHOUSE_TRUST_CERTIFICATE_PATH: Path =
            Path.of("/var/empty/monomer-execution-warehouse-trust-absent")
        const val CANONICAL_ARTIFACT_PREFIX = "artifacts/"
    }
}
