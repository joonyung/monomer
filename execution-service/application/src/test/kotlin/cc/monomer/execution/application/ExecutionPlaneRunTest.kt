package cc.monomer.execution.application

import cc.monomer.execution.driver.databricks.DatabricksExecutionDriverProvider
import cc.monomer.execution.driver.spi.BinaryValue
import cc.monomer.execution.driver.spi.ConnectorRuntimeBinding
import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.RawSqlValidationException
import cc.monomer.execution.driver.spi.DriverCancellationResult
import cc.monomer.execution.driver.spi.DriverCapabilityDescriptor
import cc.monomer.execution.driver.spi.DriverPhysicalResult
import cc.monomer.execution.driver.spi.ExecutionDriverProvider
import cc.monomer.execution.driver.spi.InlineResultBatch
import cc.monomer.execution.driver.spi.MaterializedCredential
import cc.monomer.execution.driver.spi.QueryParameter
import cc.monomer.execution.driver.spi.ResultProducingRunDriverSession
import cc.monomer.execution.driver.spi.RunResultSink
import cc.monomer.execution.driver.spi.StartExecutionContext
import cc.monomer.execution.runtime.DirectArtifactUpload
import cc.monomer.execution.runtime.DirectArtifactUploader
import cc.monomer.execution.protocol.v1.ControlAssignment
import cc.monomer.execution.protocol.v1.ControlMessage
import cc.monomer.execution.protocol.v1.ExecutionEvent
import cc.monomer.execution.protocol.v1.ExecutionPlaneGrpcKt
import cc.monomer.execution.protocol.v1.PhysicalOutcomeKind
import cc.monomer.execution.protocol.v1.QueryRequest as ProtoQueryRequest
import cc.monomer.execution.protocol.v1.ValidateRawSqlRequest
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExecutionPlaneRunTest {
    private val clock = Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun namedResultFailuresCrossExistingWireFieldWithSafeDetails() = runBlocking {
        listOf(
            DriverPhysicalResult.Failed("RESULT_LIMIT_EXCEEDED", "Query result exceeds the configured 8 byte limit"),
            DriverPhysicalResult.Failed("UNSUPPORTED_RESULT_TYPE", "Column 'value' has unsupported result type 'refcursor'"),
        ).forEach { failure ->
            withServer(RecordingRunDriverProvider(physicalFailure = failure)) { stub ->
                val assignment = inlineAssignment().toBuilder().setDeadlineEpochMillis(System.currentTimeMillis() + 30_000).build()
                val outcome = stub.run(flowOf(ControlMessage.newBuilder().setAssignment(assignment).build()))
                    .toList().single { it.hasPhysicalOutcome() }.physicalOutcome
                assertEquals(PhysicalOutcomeKind.PHYSICAL_OUTCOME_KIND_FAILED, outcome.kind)
                assertEquals(failure.failureCode, outcome.failureCode)
                assertEquals(failure.safeMessage, outcome.safeMessage)
            }
        }
    }

    @Test
    fun timingSummaryCoversRunPrevalidationAndStandaloneValidationExactlyOnce() = runBlocking {
        val records = java.util.concurrent.CopyOnWriteArrayList<java.util.logging.LogRecord>()
        val logger = java.util.logging.Logger.getLogger(cc.monomer.execution.driver.spi.DriverOperationTiming::class.java.name)
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) { records += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        try {
            listOf(false, true).forEach { fail ->
                withServer(RecordingRunDriverProvider(resolutionFailure = fail)) { stub ->
                    val assignment = inlineAssignment().toBuilder()
                        .setDeadlineEpochMillis(System.currentTimeMillis() + 30_000)
                        .setRequest(inlineAssignment().request.toBuilder().setSql("SELECT secret_customer").setValidateRawSqlBeforeRun(true))
                        .build()
                    val before = records.size
                    val events = stub.run(flowOf(ControlMessage.newBuilder().setAssignment(assignment).build())).toList()
                    assertEquals(1, records.size - before)
                    val line = records.last().message
                    assertTrue(line.contains("run_id=$applicationRunId workspace_id=$applicationWorkspaceId driver=POSTGRESQL operation=QUERY"))
                    assertTrue(line.contains("outcome=${if (fail) "FAILED" else "SUCCEEDED"}"))
                    if (fail) assertTrue(line.contains("failure_code=${events.last().physicalOutcome.failureCode}"))
                    assertTrue(!line.contains("secret_customer") && !line.contains("dogfood") && !line.contains("unused"))
                    val response = stub.validateRawSql(ValidateRawSqlRequest.newBuilder()
                        .setRunId(applicationRunId.toString()).setWorkspaceId(applicationWorkspaceId.toString())
                        .setConnector(connector("POSTGRESQL")).setSql("SELECT secret_customer")
                        .setDeadlineEpochMillis(System.currentTimeMillis() + 30_000).build())
                    assertEquals(2, records.size - before)
                    val validationLine = records.last().message
                    assertTrue(validationLine.contains("operation=VALIDATE"))
                    assertTrue(validationLine.contains("outcome=${if (fail) "FAILED" else "SUCCEEDED"}"))
                    if (fail) assertTrue(validationLine.contains("failure_code=${response.failed.failureCode}"))
                    assertTrue(!validationLine.contains("secret_customer"))
                }
            }
            assertTrue(records.all { it.thrown == null })
        } finally { logger.removeHandler(handler) }
    }

    @Test
    fun invalidCredentialIsTimedBeforeTheDriverOpensWithoutLoggingItsBytes() = runBlocking {
        val lines = mutableListOf<String>()
        val logger = java.util.logging.Logger.getLogger(cc.monomer.execution.driver.spi.DriverOperationTiming::class.java.name)
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) { lines += record.message }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        try {
            val provider = RecordingRunDriverProvider()
            withServer(provider) { stub ->
                val assignment = inlineAssignment().toBuilder().setConnector(connector("POSTGRESQL").toBuilder()
                    .setCredentialDocument(ByteString.copyFromUtf8("private-credential-bytes"))).build()
                val events = stub.run(flowOf(ControlMessage.newBuilder().setAssignment(assignment).build())).toList()
                val line = lines.single()
                assertTrue(line.contains("failure_phase=CREDENTIAL"))
                assertTrue(line.contains("failure_code=${events.last().physicalOutcome.failureCode}"))
                assertTrue(!line.contains("private-credential-bytes"))
                assertEquals(emptyList(), provider.calls)
            }
        } finally { logger.removeHandler(handler) }
    }

    @Test
    fun successfulInlineRunStreamsResultAndTerminalOutcome() = runBlocking {
        val driver = RecordingRunDriverProvider()
        withServer(driver) { stub ->
            val events = stub.run(flowOf(ControlMessage.newBuilder().setAssignment(inlineAssignment()).build())).toList()
            val batch = events.single { it.hasInlineResultBatch() }.inlineResultBatch
            val outcome = events.single { it.hasPhysicalOutcome() }.physicalOutcome

            assertTrue(events.any(ExecutionEvent::hasProgress))
            assertEquals(1L, batch.sequence)
            assertEquals(3, batch.arrowIpc.size())
            assertEquals(PhysicalOutcomeKind.PHYSICAL_OUTCOME_KIND_SUCCEEDED, outcome.kind)
            assertTrue(!outcome.hasArrowResultManifest())
        }
    }

    @Test
    fun requestedValidationRunsBeforeExactlyOneExecution() = runBlocking {
        val driver = RecordingRunDriverProvider()
        withServer(driver) { stub ->
            val assignment = inlineAssignment().toBuilder()
                .setDeadlineEpochMillis(System.currentTimeMillis() + 30_000)
                .setRequest(inlineAssignment().request.toBuilder().setValidateRawSqlBeforeRun(true))
                .build()
            val events = stub.run(flowOf(ControlMessage.newBuilder().setAssignment(assignment).build())).toList()
            assertEquals(listOf("VALIDATE", "OPEN"), driver.calls)
            assertEquals(PhysicalOutcomeKind.PHYSICAL_OUTCOME_KIND_SUCCEEDED, events.last().physicalOutcome.kind)
            assertTrue(driver.entered.isCompleted)
        }
    }

    @Test
    fun cancellingDuringValidationCancelsTheCheckBeforeOpeningExecution() = runBlocking {
        val driver = RecordingRunDriverProvider(hangValidation = true)
        withServer(driver) { stub ->
            val assignment = inlineAssignment().toBuilder()
                .setDeadlineEpochMillis(System.currentTimeMillis() + 30_000)
                .setRequest(inlineAssignment().request.toBuilder().setValidateRawSqlBeforeRun(true))
                .build()
            val call = async {
                stub.run(flowOf(ControlMessage.newBuilder().setAssignment(assignment).build())).toList()
            }
            withTimeout(5_000) { driver.validationEntered.await() }
            assertEquals(assignment.deadlineEpochMillis, driver.validationDeadline)
            call.cancelAndJoin()
            withTimeout(5_000) { driver.validationCancelled.await() }
            assertEquals(listOf("VALIDATE"), driver.calls)
            assertTrue(!driver.entered.isCompleted)
        }
    }

    @Test
    fun validationFailureStopsBeforeOpeningExecution() = runBlocking {
        val driver = RecordingRunDriverProvider(resolutionFailure = true)
        withServer(driver) { stub ->
            val assignment = inlineAssignment().toBuilder()
                .setDeadlineEpochMillis(System.currentTimeMillis() + 30_000)
                .setRequest(inlineAssignment().request.toBuilder().setValidateRawSqlBeforeRun(true))
                .build()
            val outcome = stub.run(flowOf(ControlMessage.newBuilder().setAssignment(assignment).build()))
                .toList().single { it.hasPhysicalOutcome() }.physicalOutcome
            assertEquals(listOf("VALIDATE"), driver.calls)
            assertEquals("RAW_SQL_VALIDATION_UNSUPPORTED", outcome.failureCode)
            assertTrue(!driver.entered.isCompleted)
        }
    }

    @Test
    fun databricksNonNullBinaryBindReturnsExistingFailureBeforeHttp() = runBlocking {
        listOf(false, true).forEach { validateBeforeRun ->
            val driver = DatabricksExecutionDriverProvider(
                maximumQuerySeconds = 300,
                arrowBatchSize = 1_024,
                maximumCallDuration = java.time.Duration.ofSeconds(3),
                pollInterval = java.time.Duration.ofMillis(10),
                blockingDispatcher = Dispatchers.IO,
                clock = Clock.systemUTC(),
                httpClient = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build(),
            )
            withServer(driver) { stub ->
                val assignment = inlineAssignment().toBuilder()
                    .setConnector(connector("DATABRICKS").toBuilder()
                        .setCredentialDocument(ByteString.copyFrom(encodeCredentialDocument(listOf("access_token" to "unused-binding-test-token"))))
                        .addProperties(cc.monomer.execution.protocol.v1.ConnectorRuntimeProperty.newBuilder()
                            .setName("host").setStringValue("dbc-1234.cloud.databricks.com"))
                        .addProperties(cc.monomer.execution.protocol.v1.ConnectorRuntimeProperty.newBuilder()
                            .setName("warehouse_id").setStringValue("abcd1234")))
                    .setRequest(inlineAssignment().request.toBuilder().setSql("SELECT :payload")
                        .addParameters(cc.monomer.execution.protocol.v1.QueryParameter.newBuilder()
                            .setName("payload").setBytesValue(ByteString.copyFromUtf8("binary")))
                        .setValidateRawSqlBeforeRun(validateBeforeRun))
                    .setDeadlineEpochMillis(System.currentTimeMillis() + 30_000).build()
                val outcome = stub.run(flowOf(ControlMessage.newBuilder().setAssignment(assignment).build()))
                    .toList().single { it.hasPhysicalOutcome() }.physicalOutcome
                assertEquals(PhysicalOutcomeKind.PHYSICAL_OUTCOME_KIND_FAILED, outcome.kind)
                assertEquals("QUERY_BIND_UNSUPPORTED", outcome.failureCode)
                assertEquals("Databricks binary parameters, including typed nulls, are unsupported. Use a Semantic query.", outcome.safeMessage)
            }
        }
    }

    @Test
    fun parameterNamingViolationsKeepExistingRunAndValidationFailureEnvelopes() = runBlocking {
        val databricks = DatabricksExecutionDriverProvider(
            maximumQuerySeconds = 300,
            arrowBatchSize = 1_024,
            maximumCallDuration = java.time.Duration.ofSeconds(3),
            pollInterval = java.time.Duration.ofMillis(10),
            blockingDispatcher = Dispatchers.IO,
            clock = Clock.systemUTC(),
            httpClient = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build(),
        )
        val cases = listOf(
            "DATABRICKS" to listOf(""),
            "DATABRICKS" to listOf("valid", "VALID"),
            "DATABRICKS" to listOf("1invalid"),
            "POSTGRESQL" to listOf("named"),
        )
        cases.forEach { (type, names) ->
            val driver = if (type == "DATABRICKS") databricks else RecordingRunDriverProvider()
            withServer(driver) { stub ->
                val parameters = names.map { name ->
                    cc.monomer.execution.protocol.v1.QueryParameter.newBuilder().setName(name).setStringValue("synthetic-value").build()
                }
                val assignment = inlineAssignment().toBuilder()
                    .setConnector(connector(type))
                    .setRequest(inlineAssignment().request.toBuilder().setSql("SELECT :value").addAllParameters(parameters))
                    .setDeadlineEpochMillis(System.currentTimeMillis() + 30_000).build()
                val outcome = stub.run(flowOf(ControlMessage.newBuilder().setAssignment(assignment).build()))
                    .toList().single { it.hasPhysicalOutcome() }.physicalOutcome
                assertEquals("INVALID_ASSIGNMENT", outcome.failureCode)
                val validation = stub.validateRawSql(ValidateRawSqlRequest.newBuilder()
                    .setRunId(applicationRunId.toString()).setWorkspaceId(applicationWorkspaceId.toString())
                    .setConnector(connector(type)).setSql("SELECT :value").addAllParameters(parameters)
                    .setDeadlineEpochMillis(System.currentTimeMillis() + 30_000).build())
                assertTrue(validation.hasFailed())
                assertEquals("INVALID_RAW_SQL_VALIDATION_REQUEST", validation.failed.failureCode)
                if (driver is RecordingRunDriverProvider) assertTrue(driver.calls.isEmpty())
            }
        }
    }

    @Test
    fun validationUsesRunDeadlineWithoutSubmittingSqlAfterExpiry() = runBlocking {
        val driver = RecordingRunDriverProvider()
        withServer(driver) { stub ->
            val assignment = inlineAssignment().toBuilder()
                .setRequest(inlineAssignment().request.toBuilder().setValidateRawSqlBeforeRun(true))
                .build()
            val outcome = stub.run(flowOf(ControlMessage.newBuilder().setAssignment(assignment).build()))
                .toList().single { it.hasPhysicalOutcome() }.physicalOutcome
            assertEquals("RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", outcome.failureCode)
            assertTrue(driver.calls.isEmpty())
        }
    }

    @Test
    fun validateRawSqlIsUnaryAndReturnsAcceptance() = runBlocking {
        val driver = RecordingRunDriverProvider()
        withServer(driver) { stub ->
            val response =
                stub.validateRawSql(
                    ValidateRawSqlRequest.newBuilder()
                        .setRunId(applicationRunId.toString())
                        .setWorkspaceId(applicationWorkspaceId.toString())
                        .setConnector(connector("POSTGRESQL"))
                        .setSql("SELECT * FROM analytics.orders")
                        .setDeadlineEpochMillis(clock.millis() + 30_000)
                        .build(),
                )

            assertTrue(response.hasAccepted())
        }
    }

    @Test
    fun unsupportedRawSqlValidationIsReturnedAsFailure() = runBlocking {
        val driver = RecordingRunDriverProvider(resolutionFailure = true)
        withServer(driver) { stub ->
            val response =
                stub.validateRawSql(
                    ValidateRawSqlRequest.newBuilder()
                        .setRunId(applicationRunId.toString())
                        .setWorkspaceId(applicationWorkspaceId.toString())
                        .setConnector(connector("POSTGRESQL"))
                        .setSql("SELECT 1")
                        .setDeadlineEpochMillis(clock.millis() + 30_000)
                        .build(),
                )

            assertTrue(response.hasFailed())
            assertEquals("RAW_SQL_VALIDATION_UNSUPPORTED", response.failed.failureCode)
        }
    }

    @Test
    fun aSlowControlReaderHoldsBackFurtherResultFrames() = runBlocking<Unit> {
        val driver = RecordingRunDriverProvider(framesToEmit = 200)
        withServer(driver) { stub ->
            val firstEvent = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val call =
                async {
                    stub.run(flowOf(ControlMessage.newBuilder().setAssignment(inlineAssignment()).build())).collect {
                        firstEvent.complete(Unit)
                        release.await()
                    }
                }
            withTimeout(5_000) { firstEvent.await() }
            delay(500)
            assertTrue(driver.emittedFrames.get() < 8, "emitted ${driver.emittedFrames.get()} frames ahead of the reader")
            call.cancel()
        }
    }

    @Test
    fun cancellationCancelsTheAttachedRun() = runBlocking<Unit> {
        val driver = RecordingRunDriverProvider(hangUntilCancelled = true)
        withServer(driver) { stub ->
            val call =
                async {
                    stub.run(flowOf(ControlMessage.newBuilder().setAssignment(inlineAssignment()).build())).toList()
                }
            withTimeout(5_000) { driver.entered.await() }
            call.cancel()
            withTimeout(5_000) { driver.cancelled.await() }
        }
    }

    @Test
    fun runDeadlineWhileConnectingFailsAsDeadlineWhileCallCancellationStaysCancelled() = runBlocking {
        val records = java.util.concurrent.CopyOnWriteArrayList<java.util.logging.LogRecord>()
        val logger = java.util.logging.Logger.getLogger(cc.monomer.execution.driver.spi.DriverOperationTiming::class.java.name)
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) { records += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        try {
            listOf(false, true).forEach { cancelCall ->
                val connecting = java.util.concurrent.CountDownLatch(1)
                val release = java.util.concurrent.CountDownLatch(1)
                val driver = cc.monomer.execution.driver.postgresql.PostgresqlExecutionDriverProvider(
                    maximumQuerySeconds = 300,
                    arrowBatchSize = 1_024,
                    warehouseTrustCertificatePath = java.nio.file.Path.of("/unused/ca.pem"),
                    timeoutPolicy = cc.monomer.execution.driver.jdbc.JdbcRunTimeoutPolicy(30_000, 30_000, 1_000),
                    blockingDispatcher = Dispatchers.IO,
                    clock = Clock.systemUTC(),
                    connectionFactory = { _, _ ->
                        connecting.countDown()
                        release.await(10, TimeUnit.SECONDS)
                        throw java.sql.SQLException("connection released after the test", "08001")
                    },
                )
                try {
                    withServer(driver) { stub ->
                        val runId = if (cancelCall) "019f0000-0000-7000-8000-0000000000c1" else "019f0000-0000-7000-8000-0000000000d1"
                        val assignment = inlineAssignment(runId).toBuilder()
                            .setConnector(connector("POSTGRESQL").toBuilder()
                                .addProperties(cc.monomer.execution.protocol.v1.ConnectorRuntimeProperty.newBuilder()
                                    .setName("host").setStringValue("warehouse.invalid"))
                                .addProperties(cc.monomer.execution.protocol.v1.ConnectorRuntimeProperty.newBuilder()
                                    .setName("database").setStringValue("fixture")))
                            .setDeadlineEpochMillis(System.currentTimeMillis() + if (cancelCall) 30_000 else 1_500)
                            .build()
                        val runLines = { records.map { it.message }.filter { it.contains("run_id=$runId ") } }
                        if (cancelCall) {
                            val call = async { stub.run(flowOf(ControlMessage.newBuilder().setAssignment(assignment).build())).toList() }
                            assertTrue(kotlinx.coroutines.withContext(Dispatchers.IO) { connecting.await(5, TimeUnit.SECONDS) })
                            call.cancelAndJoin()
                            withTimeout(5_000) { while (runLines().isEmpty()) delay(10) }
                            val line = runLines().single()
                            assertTrue(line.contains("outcome=CANCELLED") && line.contains("failure_code=CANCELLED"), line)
                        } else {
                            val outcome = stub.run(flowOf(ControlMessage.newBuilder().setAssignment(assignment).build()))
                                .toList().single { it.hasPhysicalOutcome() }.physicalOutcome
                            assertEquals(PhysicalOutcomeKind.PHYSICAL_OUTCOME_KIND_FAILED, outcome.kind)
                            assertEquals("RUN_DEADLINE_EXCEEDED", outcome.failureCode)
                            val line = runLines().single()
                            assertTrue(line.contains("outcome=FAILED") && line.contains("failure_code=RUN_DEADLINE_EXCEEDED"), line)
                        }
                    }
                } finally {
                    release.countDown()
                }
            }
        } finally { logger.removeHandler(handler) }
    }

    private suspend fun <T> withServer(
        driver: ExecutionDriverProvider,
        block: suspend (ExecutionPlaneGrpcKt.ExecutionPlaneCoroutineStub) -> T,
    ): T {
        val serverName = InProcessServerBuilder.generateName()
        val service =
            ExecutionPlaneService(
                driverRegistry = cc.monomer.execution.driver.spi.ExecutionDriverRegistry(listOf(driver)),
                maximumCredentialDocumentBytes = 60 * 1_024,
                maximumCredentialFields = 32,
                maximumCredentialFieldCharacters = 32 * 1_024,
                artifactUploader = NoopArtifactUploader,
            )
        val server =
            InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(service.bindService())
                .build()
                .start()
        val channel: ManagedChannel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
        return try {
            block(ExecutionPlaneGrpcKt.ExecutionPlaneCoroutineStub(channel))
        } finally {
            channel.shutdownNow()
            channel.awaitTermination(5, TimeUnit.SECONDS)
            server.shutdownNow()
            server.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun inlineAssignment(
        runId: String = applicationRunId.toString(),
    ): ControlAssignment =
        ControlAssignment.newBuilder()
            .setRunId(runId)
            .setWorkspaceId(applicationWorkspaceId.toString())
            .setConnector(connector("POSTGRESQL"))
            .setRequest(
                ProtoQueryRequest.newBuilder()
                    .setSchemaVersion(1)
                    .setSql("SELECT 1")
                    .setInlineStream(
                        cc.monomer.execution.protocol.v1.InlineStreamTarget.newBuilder()
                            .setMaximumRecordBatchRows(128)
                            .setMaximumRecordBatchBytes(64 * 1024)
                            .setMaximumCellBytes(64 * 1024),
                    ),
            )
            .setDeadlineEpochMillis(clock.millis() + 30_000)
            .build()

    private fun connector(type: String) =
        cc.monomer.execution.protocol.v1.ConnectorRuntimeBinding.newBuilder()
            .setSchemaVersion(1)
            .setConnectorId(applicationConnectorId.toString())
            .setConnectorType(type)
            .setCredentialDocument(ByteString.copyFrom(credentialDocument()))
            .build()

    private fun credentialDocument(): ByteArray =
        encodeCredentialDocument(listOf("username" to "dogfood", "password" to "unused"))

    private fun encodeCredentialDocument(fields: List<Pair<String, String>>): ByteArray =
        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(0x4d435231)
                data.writeInt(fields.size)
                fields.forEach { (name, value) -> writeField(data, name, value) }
            }
            output.toByteArray()
        }

    private fun writeField(data: DataOutputStream, name: String, value: String) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        data.writeInt(nameBytes.size)
        data.write(nameBytes)
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        data.writeInt(valueBytes.size)
        data.write(valueBytes)
    }
}

private class RecordingRunDriverProvider(
    private val hangUntilCancelled: Boolean = false,
    private val resolutionFailure: Boolean = false,
    private val framesToEmit: Int = 1,
    private val hangValidation: Boolean = false,
    private val physicalFailure: DriverPhysicalResult.Failed? = null,
) : ExecutionDriverProvider {
    val cancelled = CompletableDeferred<Unit>()
    val entered = CompletableDeferred<Unit>()
    val emittedFrames = AtomicInteger()
    val calls = mutableListOf<String>()
    val validationEntered = CompletableDeferred<Unit>()
    val validationCancelled = CompletableDeferred<Unit>()
    var validationDeadline: Long? = null

    override val capability =
        DriverCapabilityDescriptor(connectorType = ConnectorType.POSTGRESQL)

    override fun openSession(
        connector: ConnectorRuntimeBinding,
        credential: MaterializedCredential?,
        resultSink: RunResultSink,
        timing: cc.monomer.execution.driver.spi.DriverOperationTiming,
    ): ResultProducingRunDriverSession {
        calls += "OPEN"
        return RecordingRunDriverSession(resultSink, hangUntilCancelled, cancelled, entered, framesToEmit, emittedFrames, physicalFailure)
    }

    override suspend fun validateRawSql(
        connector: ConnectorRuntimeBinding,
        credential: MaterializedCredential?,
        sql: String,
        parameters: List<QueryParameter>,
        deadlineEpochMillis: Long,
        timing: cc.monomer.execution.driver.spi.DriverOperationTiming,
    ): Unit {
        cc.monomer.execution.driver.spi.requireQueryParameterNames(connector.connectorType, parameters)
        calls += "VALIDATE"
        validationDeadline = deadlineEpochMillis
        validationEntered.complete(Unit)
        if (hangValidation) {
            try { awaitCancellation() } finally { validationCancelled.complete(Unit) }
        }
        if (resolutionFailure) {
            throw RawSqlValidationException(
                failureCode = "RAW_SQL_VALIDATION_UNSUPPORTED",
                safeMessage = "raw SQL validation is not supported by this driver",
                cause = null,
            )
        }

    }
}

private class RecordingRunDriverSession(
    private val resultSink: RunResultSink,
    private val hangUntilCancelled: Boolean,
    private val cancelled: CompletableDeferred<Unit>,
    private val entered: CompletableDeferred<Unit>,
    private val framesToEmit: Int,
    private val emittedFrames: AtomicInteger,
    private val physicalFailure: DriverPhysicalResult.Failed?,
) : ResultProducingRunDriverSession {
    private val result = CompletableDeferred<DriverPhysicalResult>()
    override val connectorType = ConnectorType.POSTGRESQL

    override suspend fun enter(context: StartExecutionContext) {
        entered.complete(Unit)
        physicalFailure?.let { result.complete(it); return }
        if (!hangUntilCancelled) {
            repeat(framesToEmit) { index ->
                resultSink.emitInlineResult(
                    InlineResultBatch.fromPayload(
                        sequence = (index + 1).toULong(),
                        arrowIpc = BinaryValue.copyOf(byteArrayOf(1, 2, 3)),
                    ),
                )
                emittedFrames.incrementAndGet()
            }
            result.complete(
                DriverPhysicalResult.Succeeded(
                    rowsProcessed = 1u,
                    bytesProcessed = 3u,
                    manifest = null,
                ),
            )
        }
    }

    override suspend fun cancel(): DriverCancellationResult {
        cancelled.complete(Unit)
        result.complete(DriverPhysicalResult.Cancelled)
        return DriverCancellationResult.CONFIRMED
    }

    override suspend fun awaitPhysicalResult(): DriverPhysicalResult = result.await()

    override suspend fun close() = Unit
}

private object NoopArtifactUploader : DirectArtifactUploader {
    override suspend fun open(
        objectKey: String,
        mediaType: String,
        deadlineEpochMillis: Long,
    ): DirectArtifactUpload =
        error("artifact upload is not expected in this test")
}
