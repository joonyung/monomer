package cc.monomer.control

import java.net.URI
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("monomer.control.object-storage")
data class ControlObjectStorageProperties(
    val endpoint: URI,
    val region: String,
    val credentialMode: ControlArtifactCredentialMode,
    val accessKey: String?,
    val secretKey: String?,
    val pageContent: ControlObjectStoreBinding,
) {
    init {
        require(
            !endpoint.host.isNullOrBlank() &&
                endpoint.userInfo == null &&
                endpoint.query == null &&
                endpoint.fragment == null &&
                endpoint.path.orEmpty() in setOf("", "/"),
        ) {
            "object-store endpoint must be an exact origin"
        }
        require(
            endpoint.scheme == "https" ||
                (endpoint.scheme == "http" && endpoint.host in LOOPBACK_ENDPOINT_HOSTS),
        ) {
            "object-store endpoint must use HTTPS unless it addresses a loopback host"
        }
        require(region.isNotBlank()) { "object-store region must not be blank" }
        when (credentialMode) {
            ControlArtifactCredentialMode.WORKLOAD -> require(accessKey.isNullOrEmpty() && secretKey.isNullOrEmpty()) {
                "object-store WORKLOAD mode must not contain static credentials"
            }
            ControlArtifactCredentialMode.LOCAL_TEST_STATIC -> {
                require(endpoint.host in LOOPBACK_ENDPOINT_HOSTS) {
                    "object-store LOCAL_TEST_STATIC mode requires a loopback endpoint"
                }
                require(!accessKey.isNullOrBlank() && !secretKey.isNullOrBlank()) {
                    "object-store LOCAL_TEST_STATIC mode requires both access key and secret key"
                }
            }
        }
    }

    override fun toString(): String =
        "ControlObjectStorageProperties(" +
            "endpoint=$endpoint, region=$region, credentialMode=$credentialMode, accessKey=[REDACTED], secretKey=[REDACTED], " +
            "pageContent=$pageContent)"

    private companion object {
        // URI.getHost() keeps the brackets around an IPv6 literal, so the bracketed form is the
        // only one an endpoint can present.
        val LOOPBACK_ENDPOINT_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")
    }
}

data class ControlObjectStoreBinding(
    val bucket: String,
    val rootDirectory: String,
) {
    init {
        require(bucket.matches(BUCKET_NAME)) { "object-store bucket name is invalid: $bucket" }
        require(
            !rootDirectory.startsWith("/") &&
                "//" !in rootDirectory &&
                ".." !in rootDirectory &&
                rootDirectory.all(SAFE_ROOT_DIRECTORY_CHARACTERS::contains),
        ) {
            "object-store root directory is invalid: $rootDirectory"
        }
    }

    private companion object {
        val BUCKET_NAME = Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
        val SAFE_ROOT_DIRECTORY_CHARACTERS: Set<Char> =
            (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('/', '-', '_', '.')).toSet()
    }
}
