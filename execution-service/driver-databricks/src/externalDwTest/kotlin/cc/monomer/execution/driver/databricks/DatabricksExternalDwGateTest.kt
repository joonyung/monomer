package cc.monomer.execution.driver.databricks

import cc.monomer.execution.driver.spi.ArrowResultManifest
import cc.monomer.execution.driver.spi.ArtifactUploadSink
import cc.monomer.execution.driver.spi.BinaryValue
import cc.monomer.execution.driver.spi.ConnectorId
import cc.monomer.execution.driver.spi.ConnectorPropertyValue
import cc.monomer.execution.driver.spi.ConnectorRuntimeBinding
import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.DriverOperation
import cc.monomer.execution.driver.spi.DriverOperationTiming
import cc.monomer.execution.driver.spi.DriverPhysicalResult
import cc.monomer.execution.driver.spi.InlineResultBatch
import cc.monomer.execution.driver.spi.MaterializedCredential
import cc.monomer.execution.driver.spi.NullQueryParameterType
import cc.monomer.execution.driver.spi.QueryParameter
import cc.monomer.execution.driver.spi.QueryRequest
import cc.monomer.execution.driver.spi.QueryRequestCanonicalizer
import cc.monomer.execution.driver.spi.QueryResultTarget
import cc.monomer.execution.driver.spi.RawSqlValidationException
import cc.monomer.execution.driver.spi.RunAssignment
import cc.monomer.execution.driver.spi.RunId
import cc.monomer.execution.driver.spi.RunIdentity
import cc.monomer.execution.driver.spi.RunResultSink
import cc.monomer.execution.driver.spi.SecretCharacters
import cc.monomer.execution.driver.spi.Sha256Digest
import cc.monomer.execution.driver.spi.StartExecutionContext
import cc.monomer.execution.driver.spi.WorkspaceId
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.BitVector
import org.apache.arrow.vector.DateDayVector
import org.apache.arrow.vector.DecimalVector
import org.apache.arrow.vector.Float8Vector
import org.apache.arrow.vector.IntVector
import org.apache.arrow.vector.TimeStampMicroTZVector
import org.apache.arrow.vector.VarBinaryVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowFileReader
import org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel

class DatabricksExternalDwGateTest {
    @Test
    fun `attended tiny Statement API gate uses the documented workspace and fixture`() {
        var failureCode = GateCode.CONFIGURATION_FAILED
        try {
            requireSafeHttpLogging()
            printPlan()
            println("DATABRICKS_GATE_OAUTH_M2M_NOT_PROVISIONED")
            failureCode = GateCode.CREDENTIAL_PREFLIGHT_FAILED
            readCredential().use { credential ->
                HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build().use { httpClient ->
                        val provider = DatabricksExecutionDriverProvider(
                            maximumQuerySeconds = 60,
                            arrowBatchSize = 2,
                            maximumCallDuration = Duration.ofSeconds(15),
                            pollInterval = Duration.ofMillis(250),
                            blockingDispatcher = Dispatchers.IO,
                            clock = Clock.systemUTC(),
                            httpClient = httpClient,
                        )
                        val cases = gateCases(provider, credential)
                        expect(cases.map { it.phase } == GatePhase.entries, GateCode.CONFIGURATION_FAILED)
                        val summary = runBlocking { runIndependentGateCases(cases, ::println) }
                        requireAllGateCasesPassed(summary)
                    }
            }
        } catch (failure: GateFailure) {
            throw failure
        } catch (failure: GateAggregateFailure) {
            throw failure
        } catch (_: Throwable) {
            throw GateFailure(failureCode)
        }
    }

    private suspend fun query(
        provider: DatabricksExecutionDriverProvider,
        credential: MaterializedCredential,
        sql: String,
        parameters: List<QueryParameter>,
        columns: List<String>,
        expectedRows: ULong,
        verify: (VectorSchemaRoot) -> Unit,
    ) {
        val sink = InMemoryArtifactSink()
        val unsigned = QueryRequest(1u, Sha256Digest("0".repeat(64)), sql, parameters, artifactTarget(), false, columns)
        val request = unsigned.copy(canonicalRequestDigest = QueryRequestCanonicalizer.canonicalDigest(unsigned))
        val ownedCredential = credential.useSecret("access_token") { token ->
            MaterializedCredential(mapOf("access_token" to SecretCharacters.copyOf(token)))
        }
        val context = StartExecutionContext(
            RunIdentity(RunId.parse("019f0000-0000-7000-8000-000000000001")),
            RunAssignment(WorkspaceId.parse("019f0000-0000-7000-8000-000000000002"), connector(), request, deadline()),
        )
        val timing = DriverOperationTiming(
            ConnectorType.DATABRICKS, DriverOperation.QUERY, context.run.runId.toString(), context.assignment.workspaceId.toString(),
        )
        var physicalResult: DriverPhysicalResult = DriverPhysicalResult.Failed("DATABRICKS_EXECUTION_FAILED", "Databricks gate driver entry failed")
        val session = try {
            provider.openSession(connector(), ownedCredential, sink, timing)
        } catch (failure: Throwable) {
            ownedCredential.close()
            timing.log(physicalResult)
            throw failure
        }
        try {
            try {
                session.enter(context)
                physicalResult = session.awaitPhysicalResult()
            } finally {
                try { session.close() } finally { timing.log(physicalResult) }
            }
            val result = requireGateQuerySucceeded(physicalResult)
            expect(result.rowsProcessed == expectedRows && result.manifest?.rowCount == expectedRows, GateCode.ROW_COUNT_MISMATCH)
            expect(sink.completed && !sink.aborted, GateCode.ARTIFACT_INCOMPLETE)
            val bytes = sink.bytes()
            try {
                RootAllocator(8L * 1_024 * 1_024).use { allocator ->
                    ArrowFileReader(ByteArrayReadableSeekableByteChannel(bytes), allocator).use { reader ->
                        val root = reader.vectorSchemaRoot
                        expect(root.schema.fields.map { it.name } == columns, GateCode.COLUMN_MISMATCH)
                        if (expectedRows == 0uL) verify(root)
                        var rows = 0uL
                        while (reader.loadNextBatch()) {
                            expect(root.rowCount <= 1, GateCode.ROW_COUNT_MISMATCH)
                            rows += root.rowCount.toULong()
                            if (root.rowCount == 1) verify(root)
                        }
                        expect(rows == expectedRows, GateCode.ROW_COUNT_MISMATCH)
                    }
                }
            } finally {
                bytes.fill(0)
            }
        } finally {
            session.close()
        }
    }

    private suspend fun validate(provider: DatabricksExecutionDriverProvider, credential: MaterializedCredential, sql: String) {
        val timing = DriverOperationTiming(ConnectorType.DATABRICKS, DriverOperation.VALIDATE, null, "019f0000-0000-7000-8000-000000000002")
        var physicalResult: DriverPhysicalResult = DriverPhysicalResult.Failed("RAW_SQL_VALIDATION_FAILED", "Databricks Raw SQL validation failed")
        try {
            provider.validateRawSql(connector(), credential, sql, emptyList(), deadline(), timing)
            physicalResult = DriverPhysicalResult.Succeeded(0uL, 0uL, null)
        } catch (failure: RawSqlValidationException) {
            physicalResult = DriverPhysicalResult.Failed(failure.failureCode, failure.safeMessage)
            throw failure
        } catch (failure: kotlinx.coroutines.CancellationException) {
            physicalResult = DriverPhysicalResult.Cancelled
            throw failure
        } finally { timing.log(physicalResult) }
    }

    private fun gateCases(provider: DatabricksExecutionDriverProvider, credential: MaterializedCredential): List<GateCase> {
        fun scalar(phase: GatePhase, parameter: QueryParameter) = GateCase(phase) {
            query(provider, credential, phase.sql, listOf(parameter), listOf(parameter.name), 1uL) { root -> verifyScalar(root, phase) }
            GateOutcome(true, "NONE", "NONE", "NONE")
        }
        return listOf(
            scalar(GatePhase.TEXT, QueryParameter.Text("monomer_named_scalar", "text_value")),
            scalar(GatePhase.BIGINT, QueryParameter.Integer64(9_007_199_254_740_993L, "integer_value")),
            scalar(GatePhase.DOUBLE, QueryParameter.Float64(1.25, "float_value")),
            scalar(GatePhase.BOOLEAN, QueryParameter.BooleanValue(true, "boolean_value")),
            scalar(GatePhase.DECIMAL, QueryParameter.Decimal("0.0001", "decimal_value")),
            scalar(GatePhase.DATE, QueryParameter.Date(SCALAR_DATE, "date_value")),
            scalar(GatePhase.TIMESTAMP, QueryParameter.Timestamp(SCALAR_TIMESTAMP, "timestamp_value")),
            GateCase(GatePhase.BINARY_NULL) {
                try {
                    query(provider, credential, GatePhase.BINARY_NULL.sql,
                        listOf(QueryParameter.Null(NullQueryParameterType.BYTES, "binary_null")), listOf("binary_null"), 1uL) { root ->
                        verifyScalar(root, GatePhase.BINARY_NULL)
                    }
                    GateOutcome(false, "NONE", "NONE", GateCode.DRIVER_QUERY_FAILED.name)
                } catch (failure: GateDriverFailure) {
                    GateOutcome(failure.driverCode == "QUERY_BIND_UNSUPPORTED" && failure.safeMessage == BYTES_UNSUPPORTED_MESSAGE,
                        failure.driverCode, failure.safeMessage, "NONE")
                }
            },
            GateCase(GatePhase.EMPTY_RESULT) {
                query(provider, credential, GatePhase.EMPTY_RESULT.sql, emptyList(), listOf("x"), 0uL) { root ->
                    expect(root.fieldVectors.single() is IntVector, GateCode.EMPTY_RESULT_FAILED)
                }
                GateOutcome(true, "NONE", "NONE", "NONE")
            },
            GateCase(GatePhase.CATALOG_CATALOG) {
                query(provider, credential, GatePhase.CATALOG_CATALOG.sql,
                    listOf(QueryParameter.Text(CATALOG, "catalog")), listOf("catalog_name"), 1uL) { root ->
                    expect(textValue(root, 0) == CATALOG, GateCode.CATALOG_BINDINGS_FAILED)
                }
                GateOutcome(true, "NONE", "NONE", "NONE")
            },
            GateCase(GatePhase.CATALOG_SCHEMA) {
                query(provider, credential, GatePhase.CATALOG_SCHEMA.sql,
                    listOf(QueryParameter.Text(SCHEMA, "schema"), QueryParameter.Text(CATALOG, "catalog")),
                    listOf("catalog_name", "schema_name"), 1uL) { root ->
                    expect(textValue(root, 0) == CATALOG && textValue(root, 1) == SCHEMA, GateCode.CATALOG_BINDINGS_FAILED)
                }
                GateOutcome(true, "NONE", "NONE", "NONE")
            },
            GateCase(GatePhase.CATALOG_RELATION) {
                query(provider, credential, GatePhase.CATALOG_RELATION.sql,
                    listOf(QueryParameter.Text(RELATION, "relation"), QueryParameter.Text(CATALOG, "catalog"), QueryParameter.Text(SCHEMA, "schema")),
                    listOf("catalog_name", "schema_name", "table_name", "table_type"), 1uL) { root ->
                    expect(textValue(root, 0) == CATALOG && textValue(root, 1) == SCHEMA && textValue(root, 2) == RELATION,
                        GateCode.CATALOG_BINDINGS_FAILED)
                    expect(textValue(root, 3).isNotBlank(), GateCode.CATALOG_BINDINGS_FAILED)
                }
                GateOutcome(true, "NONE", "NONE", "NONE")
            },
            GateCase(GatePhase.DESCRIBE_SUCCESS) {
                validate(provider, credential, "SELECT 1 AS monomer_probe")
                GateOutcome(true, "NONE", "NONE", "NONE")
            },
            GateCase(GatePhase.DESCRIBE_REJECTION) {
                try {
                    validate(provider, credential, "SELECT monomer_unresolved_column AS monomer_probe")
                    GateOutcome(false, "NONE", "NONE", GateCode.NATIVE_REJECTION_FAILED.name)
                } catch (failure: RawSqlValidationException) {
                    GateOutcome(failure.failureCode == "RAW_SQL_VALIDATION_REJECTED", failure.failureCode, failure.message.orEmpty(), "NONE")
                }
            },
        )
    }

    private fun verifyScalar(root: VectorSchemaRoot, phase: GatePhase) {
        val vector = root.fieldVectors.single()
        if (phase != GatePhase.BINARY_NULL) expect(!vector.isNull(0), GateCode.SCALAR_READBACK_FAILED)
        val matches = when (phase) {
            GatePhase.TEXT -> textValue(root, 0) == "monomer_named_scalar"
            GatePhase.BIGINT -> (vector as? BigIntVector)?.get(0) == 9_007_199_254_740_993L
            GatePhase.DOUBLE -> (vector as? Float8Vector)?.get(0) == 1.25
            GatePhase.BOOLEAN -> (vector as? BitVector)?.get(0) == 1
            GatePhase.DECIMAL -> (vector as? DecimalVector)?.getObject(0) == BigDecimal("0.000100")
            GatePhase.DATE -> (vector as? DateDayVector)?.get(0) == SCALAR_DATE.toEpochDay().toInt()
            GatePhase.TIMESTAMP -> (vector as? TimeStampMicroTZVector)?.get(0) == SCALAR_TIMESTAMP.epochSecond * 1_000_000 + SCALAR_TIMESTAMP.nano / 1_000
            GatePhase.BINARY_NULL -> vector is VarBinaryVector && vector.isNull(0)
            else -> false
        }
        expect(matches, GateCode.SCALAR_READBACK_FAILED)
    }

    private fun readCredential(): MaterializedCredential {
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        val path = home.resolve(".monomer/credentials/databricks-example-ext-test.token")
        val owner = home.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
        expect(Files.isDirectory(path.parent, NOFOLLOW_LINKS) && Files.isRegularFile(path, NOFOLLOW_LINKS), GateCode.CREDENTIAL_PREFLIGHT_FAILED)
        expect(Files.getOwner(path.parent, NOFOLLOW_LINKS) == owner && Files.getOwner(path, NOFOLLOW_LINKS) == owner, GateCode.CREDENTIAL_PREFLIGHT_FAILED)
        expect(Files.getPosixFilePermissions(path.parent, NOFOLLOW_LINKS) == DIRECTORY_PERMISSIONS, GateCode.CREDENTIAL_PREFLIGHT_FAILED)
        expect(Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) == FILE_PERMISSIONS, GateCode.CREDENTIAL_PREFLIGHT_FAILED)
        expect(Instant.now().isBefore(Instant.parse("2099-01-01T00:00:00Z")), GateCode.CREDENTIAL_EXPIRED)
        expect(Files.size(path) in 1..4_096, GateCode.CREDENTIAL_PREFLIGHT_FAILED)
        val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(4_097) }
        val characters = bytes.map { (it.toInt() and 0xff).toChar() }.toCharArray()
        try {
            expect(bytes.size in 1..4_096, GateCode.CREDENTIAL_PREFLIGHT_FAILED)
            val last = characters.indexOfLast { it != '\n' && it != '\r' } + 1
            expect(last > 0 && characters.take(last).all { it.code in 33..126 }, GateCode.CREDENTIAL_PREFLIGHT_FAILED)
            val token = characters.copyOf(last)
            return try {
                MaterializedCredential(mapOf("access_token" to SecretCharacters.copyOf(token)))
            } finally {
                token.fill('\u0000')
            }
        } finally {
            bytes.fill(0)
            characters.fill('\u0000')
        }
    }

    private fun requireSafeHttpLogging() {
        expect(System.getProperty("jdk.httpclient.HttpClient.log").isNullOrBlank(), GateCode.HTTP_DEBUG_LOGGING_ENABLED)
        expect(System.getProperty("javax.net.debug").isNullOrBlank(), GateCode.HTTP_DEBUG_LOGGING_ENABLED)
    }

    private fun printPlan() {
        GatePhase.entries.forEach { phase -> println("DATABRICKS_GATE_PLAN phase=${phase.name} sql=${phase.sql}") }
    }

    private fun connector() = ConnectorRuntimeBinding(
        1u, ConnectorId.parse("019f0000-0000-7000-8000-000000000003"), ConnectorType.DATABRICKS,
        mapOf(
            "host" to ConnectorPropertyValue.Text("dbc-example.cloud.databricks.com"),
            "warehouse_id" to ConnectorPropertyValue.Text("0000000000000000"),
            "catalog" to ConnectorPropertyValue.Text(CATALOG),
            "schema" to ConnectorPropertyValue.Text(SCHEMA),
        ),
    )

    private fun artifactTarget() = QueryResultTarget.ArtifactUpload(
        "artifacts/databricks-live-gate.arrow", 1uL, MAXIMUM_ARTIFACT_BYTES.toULong(),
        2, 32 * 1_024, 8 * 1_024, 32 * 1_024,
    )

    private fun deadline() = System.currentTimeMillis() + 60_000

    private fun textValue(root: VectorSchemaRoot, index: Int): String {
        val vector = root.fieldVectors[index] as? VarCharVector ?: throw GateFailure(GateCode.RESULT_TYPE_MISMATCH)
        expect(!vector.isNull(0), GateCode.RESULT_TYPE_MISMATCH)
        return String(vector.get(0), Charsets.UTF_8)
    }

    private companion object {
        const val CATALOG = "workspace"
        const val SCHEMA = "example_ext_test"
        const val RELATION = "orders"
        const val MAXIMUM_ARTIFACT_BYTES = 64 * 1_024
        val SCALAR_DATE: LocalDate = LocalDate.parse("2024-02-29")
        val SCALAR_TIMESTAMP: Instant = Instant.parse("2024-02-29T12:34:56.123456Z")
        val FILE_PERMISSIONS = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        val DIRECTORY_PERMISSIONS = FILE_PERMISSIONS + PosixFilePermission.OWNER_EXECUTE
    }
}

private class InMemoryArtifactSink : RunResultSink, ArtifactUploadSink {
    private val output = ByteArrayOutputStream()
    var completed = false
        private set
    var aborted = false
        private set
    private var objectKey: String? = null

    override suspend fun emitInlineResult(batch: InlineResultBatch) = throw GateFailure(GateCode.UNEXPECTED_INLINE_RESULT)

    override suspend fun openObjectUpload(objectKey: String, mediaType: String): ArtifactUploadSink {
        expect(this.objectKey == null && mediaType == ArrowResultManifest.ARROW_FILE_MEDIA_TYPE, GateCode.ARTIFACT_INCOMPLETE)
        this.objectKey = objectKey
        return this
    }

    override suspend fun append(payload: BinaryValue) {
        expect(!completed && !aborted && output.size() + payload.size <= 64 * 1_024, GateCode.ARTIFACT_BYTE_LIMIT)
        val bytes = payload.copyBytes()
        try {
            output.write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    override suspend fun complete(rowCount: ULong, columnCount: Int): ArrowResultManifest {
        expect(!completed && !aborted && rowCount <= 1uL && output.size() > 0, GateCode.ARTIFACT_INCOMPLETE)
        completed = true
        return ArrowResultManifest(requireNotNull(objectKey), output.size().toULong(), "in-memory-gate", rowCount, columnCount, ArrowResultManifest.ARROW_FILE_MEDIA_TYPE)
    }

    override suspend fun abort() {
        aborted = true
        output.reset()
    }

    fun bytes(): ByteArray = output.toByteArray()
}

internal enum class GateCode {
    CONFIGURATION_FAILED, HTTP_DEBUG_LOGGING_ENABLED, CREDENTIAL_PREFLIGHT_FAILED, CREDENTIAL_EXPIRED,
    SCALAR_READBACK_FAILED, EMPTY_RESULT_FAILED, CATALOG_BINDINGS_FAILED,
    NATIVE_VALIDATION_FAILED, NATIVE_REJECTION_FAILED, DRIVER_QUERY_FAILED,
    ROW_COUNT_MISMATCH, COLUMN_MISMATCH, RESULT_TYPE_MISMATCH, ARTIFACT_INCOMPLETE,
    UNEXPECTED_INLINE_RESULT, ARTIFACT_BYTE_LIMIT,
}

internal class GateFailure(val code: GateCode) : RuntimeException("DATABRICKS_GATE_${code.name}", null, false, false)

internal fun requireGateQuerySucceeded(result: DriverPhysicalResult): DriverPhysicalResult.Succeeded = when (result) {
    is DriverPhysicalResult.Succeeded -> result
    is DriverPhysicalResult.Failed -> throw GateDriverFailure(result.failureCode, result.safeMessage)
    DriverPhysicalResult.Cancelled -> throw GateDriverFailure("CANCELLED", "Databricks statement was cancelled")
}

private fun expect(condition: Boolean, code: GateCode) {
    if (!condition) throw GateFailure(code)
}
