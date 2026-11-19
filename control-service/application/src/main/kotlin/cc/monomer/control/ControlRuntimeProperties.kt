package cc.monomer.control

import cc.monomer.execution.api.MAXIMUM_MCR1_DOCUMENT_BYTES
import cc.monomer.execution.api.ResultSinkBinding
import java.net.URI
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("monomer.control")
data class ControlRuntimeProperties(
    val grpc: ControlGrpcProperties,
    val admission: ControlAdmissionProperties,
    val executionRuntime: ControlExecutionRuntimeProperties,
    val security: ControlSecurityProperties,
) {
    init {
        require(grpc.maximumInboundMessageBytes > executionRuntime.limits.maximumRunFrameBytes) {
            "Control gRPC inbound messages must contain one maximum run frame"
        }
    }
}

data class ControlGrpcProperties(
    val address: String,
    val port: Int,
    val maximumInboundMessageBytes: Int,
    val shutdownGrace: Duration,
) {
    init {
        require(address.isNotBlank())
        require(port in 1..65_535)
        require(maximumInboundMessageBytes in 1_024..64 * 1_024 * 1_024)
        require(shutdownGrace in Duration.ofMillis(100)..Duration.ofSeconds(30))
    }
}

data class ControlAdmissionProperties(
    val mode: ControlAdmissionMode,
    val publicWrites: PublicWriteAdmission,
)

data class ControlSecurityProperties(
    val issuer: String,
    val agentResource: String,
    val loginNamespace: String,
    val loginSubjectUrnPrefix: String,
    val sessionCookieName: String,
    val sessionCookieHostOnly: Boolean,
) {
    init {
        require(issuer.isNotBlank())
        require(agentResource.isNotBlank())
        require(loginNamespace.isNotBlank())
        require(loginSubjectUrnPrefix.isNotBlank())
        require(sessionCookieName.isNotBlank())
    }
}

enum class ControlAdmissionMode {
    OPERATOR_BOOTSTRAP,
}

enum class PublicWriteAdmission {
    CLOSED,
}

data class ControlExecutionRuntimeProperties(
    val executionTarget: java.net.URI,
    val artifactStore: ControlArtifactStoreProperties,
    val credentialVault: ControlCredentialVaultProperties,
    val limits: ControlExecutionLimitsProperties,
) {
    init {
        require(executionTarget.scheme == "http") {
            "Execution Dial target must be plaintext http"
        }
        require(!executionTarget.host.isNullOrBlank())
        require(executionTarget.port in 1..65_535)
    }
}

data class ControlCredentialVaultProperties(
    val activeKeyVersion: String,
    val keysBase64: Map<String, String>,
    val maximumDocumentBytes: Int,
    val maximumFields: Int,
    val maximumFieldNameBytes: Int,
    val maximumFieldValueBytes: Int,
) {
    init {
        require(activeKeyVersion.matches(Regex("^[A-Za-z0-9._~:/-]{1,512}$")))
        require(keysBase64.isNotEmpty() && activeKeyVersion in keysBase64)
        require(maximumDocumentBytes == MAXIMUM_MCR1_DOCUMENT_BYTES)
        require(maximumFields in 1..64)
        require(maximumFieldNameBytes in 1..64)
        require(maximumFieldValueBytes in 1..MAXIMUM_MCR1_DOCUMENT_BYTES)
        keysBase64.forEach { (keyVersion, encodedKey) ->
            require(keyVersion.matches(Regex("^[A-Za-z0-9._~:/-]{1,512}$")))
            val decoded = java.util.Base64.getDecoder().decode(encodedKey)
            try {
                require(decoded.size == 32)
            } finally {
                decoded.fill(0)
            }
        }
    }

    override fun toString(): String =
        "ControlCredentialVaultProperties(" +
            "activeKeyVersion=[REDACTED], keysBase64=[REDACTED], " +
            "maximumDocumentBytes=$maximumDocumentBytes, " +
            "maximumFields=$maximumFields, " +
            "maximumFieldNameBytes=$maximumFieldNameBytes, " +
            "maximumFieldValueBytes=$maximumFieldValueBytes)"
}

enum class ControlArtifactCredentialMode {
    WORKLOAD,
    LOCAL_TEST_STATIC,
}

data class ControlArtifactStoreProperties(
    val endpoint: URI,
    val region: String,
    val bucket: String,
    val objectPrefix: String,
    val pathStyleAccessEnabled: Boolean,
    val credentialMode: ControlArtifactCredentialMode,
    val localTestAccessKey: String?,
    val localTestSecretKey: String?,
    val allowLocalTestStaticCredentials: Boolean,
) {
    init {
        require(
            endpoint.scheme == "https" ||
                (endpoint.scheme == "http" && endpoint.host in LOOPBACK_ENDPOINT_HOSTS),
        ) {
            "artifact object-store endpoint must use HTTPS unless it addresses a loopback host"
        }
        require(region.isNotBlank())
        require(bucket.isNotBlank())
        require(objectPrefix.isNotBlank() && objectPrefix.endsWith('/'))
        require(!objectPrefix.startsWith('/') && ".." !in objectPrefix)
        when (credentialMode) {
            ControlArtifactCredentialMode.WORKLOAD ->
                require(
                    localTestAccessKey.isNullOrEmpty() &&
                        localTestSecretKey.isNullOrEmpty() &&
                        !allowLocalTestStaticCredentials,
                )
            ControlArtifactCredentialMode.LOCAL_TEST_STATIC -> {
                require(allowLocalTestStaticCredentials)
                require(endpoint.host in LOOPBACK_ENDPOINT_HOSTS)
                require(!localTestAccessKey.isNullOrBlank())
                require(!localTestSecretKey.isNullOrBlank())
            }
        }
    }

    override fun toString(): String =
        "ControlArtifactStoreProperties(" +
            "endpoint=$endpoint, region=$region, bucket=$bucket, " +
            "objectPrefix=$objectPrefix, " +
            "pathStyleAccessEnabled=$pathStyleAccessEnabled, " +
            "credentialMode=$credentialMode, localTestAccessKey=[REDACTED], " +
            "localTestSecretKey=[REDACTED], " +
            "allowLocalTestStaticCredentials=$allowLocalTestStaticCredentials)"

    private companion object {
        val LOOPBACK_ENDPOINT_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")
    }
}

data class ControlExecutionLimitsProperties(
    val maximumRunFrameBytes: Int,
    val maximumNonTerminalRunsPerWorkspace: Int,
    val exploreResultRetention: Duration,
    val recoveryInterval: Duration,
    val orphanTimeout: Duration,
    val maximumOrphanRunsPerCycle: Int,
    val maximumOwnerSettlementRunsPerCycle: Int,
    val maximumExploreResultReadTransactionAttempts: Int,
    val dispatchReservationDuration: Duration,
    val executionDeadlineDuration: Duration,
) {
    init {
        require(maximumRunFrameBytes in 1..ResultSinkBinding.MAXIMUM_FRAME_BYTES)
        require(maximumNonTerminalRunsPerWorkspace in 1..1_000_000)
        require(!exploreResultRetention.isNegative && !exploreResultRetention.isZero)
        require(recoveryInterval in Duration.ofSeconds(1)..Duration.ofMinutes(5))
        require(orphanTimeout >= recoveryInterval)
        require(orphanTimeout in Duration.ofSeconds(1)..Duration.ofHours(1))
        require(maximumOrphanRunsPerCycle in 1..1_000)
        require(maximumOwnerSettlementRunsPerCycle in 1..1_000)
        require(maximumExploreResultReadTransactionAttempts in 1..10)
        require(dispatchReservationDuration in Duration.ofSeconds(1)..Duration.ofHours(1))
        require(executionDeadlineDuration in Duration.ofSeconds(1)..Duration.ofHours(1))
        require(dispatchReservationDuration <= executionDeadlineDuration) {
            "Execution duration must preserve the admitted dispatch deadline bound"
        }
    }
}
