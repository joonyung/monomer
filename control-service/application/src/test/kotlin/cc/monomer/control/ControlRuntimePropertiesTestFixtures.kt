package cc.monomer.control

import cc.monomer.execution.api.MAXIMUM_MCR1_DOCUMENT_BYTES
import java.net.URI
import java.time.Duration
import java.util.Base64

internal const val TEST_FRAME_BYTES: Int = 1_024 * 1_024

internal fun testExecutionLimits(
    maximumNonTerminalRunsPerWorkspace: Int = 256,
    maximumRunFrameBytes: Int = 2 * TEST_FRAME_BYTES,
): ControlExecutionLimitsProperties =
    ControlExecutionLimitsProperties(
        maximumRunFrameBytes = maximumRunFrameBytes,
        maximumNonTerminalRunsPerWorkspace = maximumNonTerminalRunsPerWorkspace,
        exploreResultRetention = Duration.ofDays(3),
        recoveryInterval = Duration.ofMinutes(1),
        orphanTimeout = Duration.ofSeconds(90),
        maximumOrphanRunsPerCycle = 100,
        maximumOwnerSettlementRunsPerCycle = 100,
        maximumExploreResultReadTransactionAttempts = 3,
        dispatchReservationDuration = Duration.ofSeconds(10),
        executionDeadlineDuration = Duration.ofMinutes(5),
    )

internal fun testExecutionRuntimeProperties(
    executionTarget: URI = URI("http://127.0.0.1:11099"),
    limits: ControlExecutionLimitsProperties = testExecutionLimits(),
): ControlExecutionRuntimeProperties =
    ControlExecutionRuntimeProperties(
        executionTarget = executionTarget,
        artifactStore =
            ControlArtifactStoreProperties(
                endpoint = URI("https://localhost:9443"),
                region = "us-east-1",
                bucket = "monomer-test",
                objectPrefix = "artifacts/",
                pathStyleAccessEnabled = true,
                credentialMode = ControlArtifactCredentialMode.WORKLOAD,
                localTestAccessKey = null,
                localTestSecretKey = null,
                allowLocalTestStaticCredentials = false,
            ),
        credentialVault =
            ControlCredentialVaultProperties(
                activeKeyVersion = "test-key",
                keysBase64 =
                    mapOf(
                        "test-key" to
                            Base64.getEncoder().encodeToString(ByteArray(32) { 1 }),
                    ),
                maximumDocumentBytes = MAXIMUM_MCR1_DOCUMENT_BYTES,
                maximumFields = 64,
                maximumFieldNameBytes = 64,
                maximumFieldValueBytes = MAXIMUM_MCR1_DOCUMENT_BYTES,
            ),
        limits = limits,
    )

fun testControlRuntimeProperties(
    executionTarget: URI = URI("http://127.0.0.1:11099"),
    limits: ControlExecutionLimitsProperties = testExecutionLimits(),
): ControlRuntimeProperties =
    ControlRuntimeProperties(
        grpc =
            ControlGrpcProperties(
                address = "127.0.0.1",
                port = 12078,
                maximumInboundMessageBytes = 4 * 1_024 * 1_024,
                shutdownGrace = Duration.ofSeconds(5),
            ),
        admission =
            ControlAdmissionProperties(
                mode = ControlAdmissionMode.OPERATOR_BOOTSTRAP,
                publicWrites = PublicWriteAdmission.CLOSED,
            ),
        executionRuntime =
            testExecutionRuntimeProperties(
                executionTarget = executionTarget,
                limits = limits,
            ),
        security =
            ControlSecurityProperties(
                issuer = "urn:monomer:test",
                agentResource = "http://127.0.0.1:12077/v2/workspaces",
                loginNamespace = "monomer-test",
                loginSubjectUrnPrefix = "urn:monomer:auth:test",
                sessionCookieName = "monomer_session",
                sessionCookieHostOnly = true,
            ),
    )
