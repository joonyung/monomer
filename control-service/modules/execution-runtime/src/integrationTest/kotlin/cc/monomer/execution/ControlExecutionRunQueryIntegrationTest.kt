package cc.monomer.execution

import cc.monomer.execution.adapter.outbound.grpc.GrpcExecutionRunClient
import cc.monomer.execution.adapter.inbound.grpc.ProtoInlineQueryRunSpecFactory
import cc.monomer.execution.api.ConnectorRuntimeBindingSpec
import cc.monomer.execution.api.CredentialVersionReference
import cc.monomer.execution.api.InlineQueryRunSpecCommand
import cc.monomer.execution.api.ManagedCredentialDispatchReference
import cc.monomer.execution.api.Sha256Digest
import cc.monomer.execution.api.ConnectorReference
import cc.monomer.execution.api.ConnectorRuntimePropertyValue
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.ControlRunAssignment
import cc.monomer.execution.api.ExecutionRunEvent
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.PhysicalOutcomeKind
import cc.monomer.identity.api.WorkspaceId
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.ipc.ArrowFileReader
import org.apache.arrow.vector.ipc.SeekableReadChannel
import org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ControlExecutionRunQueryIntegrationTest {
    @Test
    fun controlClientReceivesDataAndCatalogMetadataFromIndependentExecutionJvm() = runBlocking {
        val evidence = Path.of(System.getProperty("execution.process.evidence"))
        Files.createDirectories(evidence)
        val postgres = PostgreSQLContainer(
            DockerImageName.parse(
                "postgres:16.14-alpine3.24@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777",
            ).asCompatibleSubstituteFor("postgres"),
        ).withDatabaseName("control_execution_fixture")
            .withUsername("control_execution_test")
            .withPassword("control_execution_test_only")
        try {
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE SCHEMA integration_fixture")
                    statement.execute("CREATE TABLE integration_fixture.orders (id integer PRIMARY KEY, label text NOT NULL)")
                    statement.execute("INSERT INTO integration_fixture.orders VALUES (1, 'first'), (2, '둘째')")
                }
            }
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val child = startChild(port, postgres.firstMappedPort, evidence.resolve("execution.log"))
            try {
                val (channel, client) = GrpcExecutionRunClient.plaintext(URI("http://127.0.0.1:$port"), 1_048_576)
                try {
                    withTimeout(30_000) {
                        while (true) {
                            assertTrue(child.isAlive, "Execution exited; inspect execution.log")
                            val result = runCatching { client.run(assignment(postgres, "SELECT 1 AS ready")).toList() }
                            if (result.isSuccess) {
                                assertSuccessfulRows(result.getOrThrow(), listOf("ready"), listOf(listOf("1")))
                                break
                            }
                            delay(100)
                        }
                    }
                    val dataEvents = withTimeout(15_000) {
                        client.run(assignment(postgres, "SELECT id, label FROM integration_fixture.orders ORDER BY id")).toList()
                    }
                    assertSuccessfulRows(dataEvents, listOf("id", "label"), listOf(listOf("1", "first"), listOf("2", "둘째")))
                    val metadataEvents = withTimeout(15_000) {
                        client.run(
                            assignment(
                                postgres,
                                """
                                SELECT table_schema, table_name, column_name, data_type
                                FROM information_schema.columns
                                WHERE table_schema = 'integration_fixture' AND table_name = 'orders'
                                ORDER BY ordinal_position
                                """.trimIndent(),
                            ),
                        ).toList()
                    }
                    assertSuccessfulRows(
                        metadataEvents,
                        listOf("table_schema", "table_name", "column_name", "data_type"),
                        listOf(
                            listOf("integration_fixture", "orders", "id", "integer"),
                            listOf("integration_fixture", "orders", "label", "text"),
                        ),
                    )
                    Files.writeString(
                        evidence.resolve("observations.txt"),
                        "Control GrpcExecutionRunClient -> independent Execution JVM pid=${child.pid()} -> disposable PostgreSQL\n" +
                            "data query: SUCCEEDED; exact Arrow column names and 2 rows verified\n" +
                            "catalog metadata query: SUCCEEDED; exact Arrow column names and 2 columns verified\n",
                    )
                } finally {
                    channel.shutdownNow()
                    assertTrue(channel.awaitTermination(5, TimeUnit.SECONDS), "channel cleanup failed")
                }
            } finally {
                child.destroyForcibly()
                assertTrue(child.waitFor(5, TimeUnit.SECONDS), "child cleanup failed")
            }
        } finally {
            postgres.stop()
        }
        Unit
    }

    private fun assertSuccessfulRows(
        events: List<ExecutionRunEvent>,
        expectedColumns: List<String>,
        expectedRows: List<List<String>>,
    ) {
        val terminal = events.filterIsInstance<ExecutionRunEvent.Terminal>().single()
        assertEquals(PhysicalOutcomeKind.SUCCEEDED, terminal.outcome.kind, terminal.outcome.failureCode)
        assertEquals(terminal, events.last())
        val batches = events.filterIsInstance<ExecutionRunEvent.InlineResultBatch>()
        assertTrue(batches.isNotEmpty())
        assertEquals(batches.indices.map { (it + 1).toULong() }, batches.map { it.sequence })
        val rows = mutableListOf<List<String>>()
        RootAllocator(8 * 1024 * 1024).use { allocator ->
            batches.forEach { batch ->
                ArrowFileReader(SeekableReadChannel(ByteArrayReadableSeekableByteChannel(batch.arrowIpc.copyBytes())), allocator).use { reader ->
                    assertEquals(expectedColumns, reader.vectorSchemaRoot.schema.fields.map { it.name })
                    while (reader.loadNextBatch()) {
                        val root = reader.vectorSchemaRoot
                        repeat(root.rowCount) { row ->
                            rows += root.fieldVectors.map { it.getObject(row).toString() }
                        }
                    }
                }
            }
        }
        assertEquals(expectedRows, rows)
    }

    private fun startChild(port: Int, warehousePort: Int, log: Path): Process {
        val libraries = Path.of(System.getProperty("execution.distribution"), "lib")
        check(Files.isDirectory(libraries)) { "Build independent Execution :application:installDist first" }
        return ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--add-opens=java.base/java.nio=ALL-UNNAMED",
            "-cp", libraries.resolve("*").toString(),
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
                    "MONOMER_EXECUTION_ARTIFACT_STORE_ENDPOINT" to "http://127.0.0.1:1",
                    "MONOMER_EXECUTION_ARTIFACT_STORE_REGION" to "us-east-1",
                    "MONOMER_EXECUTION_ARTIFACT_STORE_BUCKET" to "monomer-execution-artifacts",
                    "MONOMER_EXECUTION_ARTIFACT_STORE_PREFIX" to "artifacts/",
                    "MONOMER_EXECUTION_ARTIFACT_STORE_CREDENTIAL_MODE" to "LOCAL_TEST_STATIC",
                    "MONOMER_EXECUTION_ARTIFACT_STORE_ACCESS_KEY" to "integration-test-only",
                    "MONOMER_EXECUTION_ARTIFACT_STORE_SECRET_KEY" to "integration-test-only",
                ),
            )
            redirectErrorStream(true)
            redirectOutput(log.toFile())
        }.start()
    }

    private fun assignment(postgres: PostgreSQLContainer, sql: String): ControlRunAssignment {
        val runId = ExecutionRunId.generate()
        val workspaceId = WorkspaceId(Uuid.random())
        val connector = ConnectorRuntimeBindingSpec(
            connectorId = ConnectorReference(Uuid.generateV7()),
            connectorType = ConnectorType.POSTGRESQL,
            properties = mapOf(
                "host" to ConnectorRuntimePropertyValue.Text(postgres.host),
                "port" to ConnectorRuntimePropertyValue.Integer(postgres.firstMappedPort.toLong()),
                "database" to ConnectorRuntimePropertyValue.Text(postgres.databaseName),
                "ssl_mode" to ConnectorRuntimePropertyValue.Text("disable"),
            ),
            managedCredential = ManagedCredentialDispatchReference(
                credentialVersion = CredentialVersionReference(Uuid.generateV7()),
                keyVersion = "integration-test-only",
                envelopeDigest = Sha256Digest("0".repeat(64)),
            ),
        )
        val deadline = Instant.now().plusSeconds(5)
        val spec = ProtoInlineQueryRunSpecFactory().create(
            InlineQueryRunSpecCommand(
                runId = runId,
                workspaceId = workspaceId,
                connector = connector,
                sql = sql,
                parameters = emptyList(),
                maximumRecordBatchRows = 1,
                maximumRecordBatchBytes = 65_536,
                maximumCellBytes = 65_536,
                deadline = deadline,
            ),
        )
        return ControlRunAssignment(
            runId = runId,
            workspaceId = workspaceId,
            connectorId = connector.connectorId,
            connectorType = connector.connectorType,
            properties = connector.properties,
            credentialDocument = credentialDocument(postgres),
            queryRequest = spec.assignmentTemplate,
            deadline = deadline,
        )
    }

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
