package cc.monomer.execution.application

import cc.monomer.execution.protocol.v1.ConnectorRuntimeBinding
import cc.monomer.execution.protocol.v1.ConnectorRuntimeProperty
import cc.monomer.execution.protocol.v1.ControlAssignment
import cc.monomer.execution.protocol.v1.ControlMessage
import cc.monomer.execution.protocol.v1.ExecutionPlaneGrpcKt
import cc.monomer.execution.protocol.v1.InlineStreamTarget
import cc.monomer.execution.protocol.v1.PhysicalOutcomeKind
import cc.monomer.execution.protocol.v1.QueryRequest
import com.google.protobuf.ByteString
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExecutionProcessRestartIntegrationTest {
    @Test
    fun sigtermInterruptsRpcAndRestartAcceptsFreshRun() = verifyInterruption(force = false)

    @Test
    fun forcedKillInterruptsRpcAndRestartAcceptsFreshRun() = verifyInterruption(force = true)

    private fun verifyInterruption(force: Boolean) = runBlocking {
        val scenario = if (force) "forced-kill" else "sigterm"
        val evidence = Path.of(System.getProperty("execution.process.evidence"), scenario)
        Files.createDirectories(evidence)
        val postgres = PostgreSQLContainer(
            DockerImageName.parse(
                "postgres:16.14-alpine3.24@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777",
            ).asCompatibleSubstituteFor("postgres"),
        ).withDatabaseName("execution_process_restart")
            .withUsername("execution_process_test")
            .withPassword("execution_process_test_only")
        try {
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { observer ->
                val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
                val channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build()
                val stub = ExecutionPlaneGrpcKt.ExecutionPlaneCoroutineStub(channel)
                var child = startChild(port, postgres.firstMappedPort, evidence.resolve("initial.log"))
                try {
                    awaitSuccessfulQuery(stub, postgres, child)
                    val marker = "execution_restart_${UUID.randomUUID().toString().replace("-", "")}"
                    val query = "SELECT 1 AS value FROM pg_sleep(25) /* $marker */"
                    val call = async {
                        runCatching { stub.run(flowOf(message(postgres, query))).toList() }
                    }
                    awaitCondition("warehouse query did not become active") { activeQuery(observer, marker) }
                    Files.writeString(evidence.resolve("observations.txt"), "active query observed; pid=${child.pid()}\n")
                    val interruptedAt = System.nanoTime()
                    if (force) child.destroyForcibly() else child.destroy()
                    assertTrue(child.waitFor(25, TimeUnit.SECONDS), "Execution did not exit within 25 seconds")
                    val result = withTimeout(5_000) { call.await() }
                    val failure = assertNotNull(result.exceptionOrNull(), "interrupted RPC unexpectedly completed normally")
                    assertEquals(Status.Code.UNAVAILABLE, Status.fromThrowable(failure).code)
                    Files.writeString(
                        evidence.resolve("observations.txt"),
                        "process exited=${child.exitValue()}; RPC=UNAVAILABLE; elapsedMillis=${(System.nanoTime() - interruptedAt) / 1_000_000}\n",
                        java.nio.file.StandardOpenOption.APPEND,
                    )
                    Files.writeString(
                        evidence.resolve("observations.txt"),
                        "queryActiveAfterExit=${activeQuery(observer, marker)}; queryDurationSeconds=25; disappearanceMayBeNaturalCompletion=true\n",
                        java.nio.file.StandardOpenOption.APPEND,
                    )
                    awaitCondition("warehouse query survived the bounded 25 second query duration", 35) { !activeQuery(observer, marker) }
                    Files.writeString(evidence.resolve("observations.txt"), "warehouse query absent; elapsedSinceInterruptionMillis=${(System.nanoTime() - interruptedAt) / 1_000_000}\n", java.nio.file.StandardOpenOption.APPEND)
                    child = startChild(port, postgres.firstMappedPort, evidence.resolve("restarted.log"))
                    awaitSuccessfulQuery(stub, postgres, child)
                    Files.writeString(evidence.resolve("observations.txt"), "fresh run succeeded after restart\n", java.nio.file.StandardOpenOption.APPEND)
                } finally {
                    child.destroyForcibly()
                    assertTrue(child.waitFor(5, TimeUnit.SECONDS), "child cleanup failed")
                    channel.shutdownNow()
                    assertTrue(channel.awaitTermination(5, TimeUnit.SECONDS), "channel cleanup failed")
                }
            }
        } finally {
            if (postgres.isRunning) Files.writeString(evidence.resolve("postgres.log"), postgres.logs)
            postgres.stop()
        }
        Unit
    }

    private suspend fun awaitSuccessfulQuery(
        stub: ExecutionPlaneGrpcKt.ExecutionPlaneCoroutineStub,
        postgres: PostgreSQLContainer,
        child: Process,
    ) {
        awaitCondition("Execution did not accept a fresh SELECT") {
            assertTrue(child.isAlive, "Execution exited during startup; inspect child log")
            val events = runCatching {
                stub.withDeadlineAfter(2, TimeUnit.SECONDS)
                    .run(flowOf(message(postgres, "SELECT 1 AS value"))).toList()
            }.getOrNull()
            if (events == null) false else {
                val outcome = events.single { it.hasPhysicalOutcome() }.physicalOutcome
                assertEquals(PhysicalOutcomeKind.PHYSICAL_OUTCOME_KIND_SUCCEEDED, outcome.kind, outcome.failureCode)
                assertTrue(events.any { it.hasInlineResultBatch() && !it.inlineResultBatch.arrowIpc.isEmpty })
                true
            }
        }
    }

    private suspend fun awaitCondition(description: String, seconds: Long = 20, condition: suspend () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            delay(100)
        }
        throw AssertionError(description)
    }

    private fun activeQuery(observer: Connection, marker: String): Boolean =
        observer.prepareStatement(
            "SELECT count(*) FROM pg_stat_activity WHERE application_name = 'monomer-execution' AND state = 'active' AND query LIKE ?",
        ).use { statement ->
            statement.setString(1, "%$marker%")
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1) > 0
            }
        }

    private fun startChild(port: Int, warehousePort: Int, log: Path): Process {
        val sampleRoot = log.parent.resolve("sample-data")
        listOf(1, 2, 3).forEach { version ->
            val database = sampleRoot.resolve("acme-cloud/$version/acme_cloud.duckdb")
            Files.createDirectories(database.parent)
            if (!Files.exists(database)) {
                DriverManager.getConnection("jdbc:duckdb:$database").use { connection ->
                    connection.createStatement().use { it.execute("CREATE TABLE fixture(value INTEGER)") }
                }
            }
        }
        return ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--add-opens=java.base/java.nio=ALL-UNNAMED",
            "-cp", System.getProperty("execution.process.classpath"),
            "cc.monomer.execution.application.ExecutionServiceMainKt",
        ).apply {
            environment().clear()
            environment().putAll(
                mapOf(
                    "MONOMER_EXECUTION_LISTEN_ADDRESS" to "127.0.0.1",
                    "MONOMER_EXECUTION_LISTEN_PORT" to port.toString(),
                    "MONOMER_EXECUTION_HEALTH_BIND_ADDRESS" to "127.0.0.1",
                    "MONOMER_EXECUTION_HEALTH_PORT" to "0",
                    "MONOMER_EXECUTION_LOCAL_DW_ENDPOINTS" to "127.0.0.1:$warehousePort",
                    "MONOMER_EXECUTION_SAMPLE_DATA_ROOT" to sampleRoot.toAbsolutePath().toString(),
                    "MONOMER_EXECUTION_ARTIFACT_STORE_ENDPOINT" to "http://127.0.0.1:1",
                    "MONOMER_EXECUTION_ARTIFACT_STORE_REGION" to "us-east-1",
                    "MONOMER_EXECUTION_ARTIFACT_STORE_BUCKET" to "monomer-execution-artifacts",
                    "MONOMER_EXECUTION_ARTIFACT_STORE_PREFIX" to "artifacts/",
                    "MONOMER_EXECUTION_ARTIFACT_STORE_CREDENTIAL_MODE" to "LOCAL_TEST_STATIC",
                    "MONOMER_EXECUTION_ARTIFACT_STORE_ACCESS_KEY" to "process-test-only",
                    "MONOMER_EXECUTION_ARTIFACT_STORE_SECRET_KEY" to "process-test-only",
                ),
            )
            redirectErrorStream(true)
            redirectOutput(log.toFile())
        }.start()
    }

    private fun message(postgres: PostgreSQLContainer, sql: String): ControlMessage =
        ControlMessage.newBuilder().setAssignment(
            ControlAssignment.newBuilder()
                .setRunId(UUID.randomUUID().toString().replaceRange(14, 15, "7"))
                .setWorkspaceId(UUID.randomUUID().toString())
                .setDeadlineEpochMillis(System.currentTimeMillis() + 180_000)
                .setConnector(
                    ConnectorRuntimeBinding.newBuilder()
                        .setSchemaVersion(1)
                        .setConnectorId(UUID.randomUUID().toString().replaceRange(14, 15, "7"))
                        .setConnectorType("POSTGRESQL")
                        .addProperties(ConnectorRuntimeProperty.newBuilder().setName("host").setStringValue(postgres.host))
                        .addProperties(ConnectorRuntimeProperty.newBuilder().setName("port").setIntegerValue(postgres.firstMappedPort.toLong()))
                        .addProperties(ConnectorRuntimeProperty.newBuilder().setName("database").setStringValue(postgres.databaseName))
                        .addProperties(ConnectorRuntimeProperty.newBuilder().setName("ssl_mode").setStringValue("disable"))
                        .setCredentialDocument(ByteString.copyFrom(credentialDocument(postgres))),
                )
                .setRequest(
                    QueryRequest.newBuilder().setSchemaVersion(1).setSql(sql)
                        .setInlineStream(
                            InlineStreamTarget.newBuilder().setMaximumRecordBatchRows(128)
                                .setMaximumRecordBatchBytes(65_536).setMaximumCellBytes(65_536),
                        ),
                ),
        ).build()

    private fun credentialDocument(postgres: PostgreSQLContainer): ByteArray =
        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(0x4d435231)
                data.writeInt(2)
                mapOf("username" to postgres.username, "password" to postgres.password).forEach { (name, value) ->
                    listOf(name, value).forEach { field ->
                        val bytes = field.toByteArray(Charsets.UTF_8)
                        data.writeInt(bytes.size)
                        data.write(bytes)
                    }
                }
            }
            output.toByteArray()
        }
}
