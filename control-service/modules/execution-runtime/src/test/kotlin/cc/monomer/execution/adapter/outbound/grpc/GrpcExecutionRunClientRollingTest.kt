package cc.monomer.execution.adapter.outbound.grpc

import cc.monomer.execution.api.BinaryValue
import cc.monomer.execution.api.ConnectorReference
import cc.monomer.execution.api.ConnectorRuntimePropertyValue
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.ControlRunAssignment
import cc.monomer.execution.api.ExecutionRunEvent
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.RawSqlValidationRequest
import cc.monomer.execution.api.RawSqlValidationResult
import cc.monomer.execution.protocol.v1.ValidateRawSqlRequest
import cc.monomer.execution.protocol.v1.ValidateRawSqlResponse
import cc.monomer.execution.protocol.v1.ControlMessage
import cc.monomer.execution.protocol.v1.ExecutionEvent
import cc.monomer.execution.protocol.v1.ExecutionPlaneGrpcKt
import cc.monomer.execution.protocol.v1.InlineResultBatch
import cc.monomer.execution.protocol.v1.InlineStreamTarget
import cc.monomer.execution.protocol.v1.PhysicalOutcome
import cc.monomer.execution.protocol.v1.PhysicalOutcomeKind
import cc.monomer.execution.protocol.v1.QueryRequest
import cc.monomer.execution.protocol.v1.RawSqlAccepted
import cc.monomer.execution.protocol.v1.RunProgress
import cc.monomer.identity.api.WorkspaceId
import com.google.protobuf.ByteString
import io.grpc.ConnectivityState
import io.grpc.EquivalentAddressGroup
import io.grpc.ManagedChannel
import io.grpc.NameResolver
import io.grpc.NameResolverProvider
import io.grpc.NameResolverRegistry
import io.grpc.Status
import io.grpc.StatusOr
import io.grpc.StatusException
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class GrpcExecutionRunClientRollingTest {
    @Test
    fun `retained channel waits for replacement endpoint without resending started SQL`() = replacementScenario(discoveryGap = true)

    @Test
    fun `graceful shutdown directs new RPCs to replacement while old RPC remains in flight`() = replacementScenario(discoveryGap = false)

    private fun replacementScenario(discoveryGap: Boolean) = runBlocking {
        val endpointA = RecordingExecutionEndpoint(holdRun = true)
        val endpointB = RecordingExecutionEndpoint(holdRun = false)
        val serverA = NettyServerBuilder.forAddress(InetSocketAddress("127.0.0.1", 0)).addService(endpointA).build().start()
        val serverB = NettyServerBuilder.forAddress(InetSocketAddress("127.0.0.1", 0)).addService(endpointB).build().start()
        val resolver = RotatingResolverProvider(serverA.port)
        NameResolverRegistry.getDefaultRegistry().register(resolver)
        val (channel, client) = GrpcExecutionRunClient.plaintext(URI("http://${resolver.hostname}:11099"), 65_536)
        try {
            val oldAssignment = assignment(Instant.now().plusSeconds(20))
            val oldRun = async { runCatching { client.run(oldAssignment).toList() } }
            withTimeout(5_000) { endpointA.runStarted.await() }
            val refreshesBeforeReplacement = resolver.refreshes.get()
            resolver.port = if (discoveryGap) null else serverB.port
            serverA.shutdown()
            if (discoveryGap) {
                serverA.shutdownNow()
                assertTrue(serverA.awaitTermination(5, TimeUnit.SECONDS))
                awaitTransientFailure(channel)
            }

            val newAssignment = assignment(Instant.now().plusSeconds(20))
            val newRun = async { runCatching { client.run(newAssignment).toList() } }
            val newAnalysis = async { runCatching { client.validateRawSql(analysis(newAssignment)) } }
            if (discoveryGap) {
                delay(200)
                assertFalse(newRun.isCompleted, "New Run must wait while name resolution is unavailable")
                assertFalse(newAnalysis.isCompleted, "New ValidateRawSql must wait while name resolution is unavailable")
                assertTrue(endpointB.runIds.isEmpty())
                assertTrue(endpointB.analysisRunIds.isEmpty())
                resolver.port = serverB.port
            }
            val events = withTimeout(10_000) { newRun.await().getOrThrow() }
            assertTrue(events.single() is ExecutionRunEvent.Progress)
            assertEquals(RawSqlValidationResult.Accepted, withTimeout(10_000) { newAnalysis.await().getOrThrow() })
            assertTrue(resolver.refreshes.get() > refreshesBeforeReplacement, "The existing channel must re-resolve its target")
            if (!discoveryGap) {
                assertFalse(oldRun.isCompleted, "Graceful shutdown must leave the existing RPC in flight")
                serverA.shutdownNow()
            }
            val oldFailure = withTimeout(5_000) { oldRun.await() }.exceptionOrNull()
            assertTrue(Status.fromThrowable(requireNotNull(oldFailure)).code in setOf(Status.Code.UNAVAILABLE, Status.Code.CANCELLED))
            assertEquals(listOf(oldAssignment.runId.toString()), endpointA.runIds)
            assertEquals(listOf(newAssignment.runId.toString()), endpointB.runIds)
            assertEquals(listOf(newAssignment.runId.toString()), endpointB.analysisRunIds)
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            serverA.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            serverB.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            NameResolverRegistry.getDefaultRegistry().deregister(resolver)
        }
    }

    @Test
    fun `connection wait remains bounded by each RPC deadline`() = runBlocking {
        val resolver = RotatingResolverProvider(null)
        NameResolverRegistry.getDefaultRegistry().register(resolver)
        val (channel, client) = GrpcExecutionRunClient.plaintext(URI("http://${resolver.hostname}:11099"), 65_536)
        try {
            awaitTransientFailure(channel)
            val assignment = assignment(Instant.now().plusMillis(300))
            val run = async { runCatching { client.run(assignment).toList() } }
            val analysis = async { runCatching { client.validateRawSql(analysis(assignment)) } }
            withTimeout(5_000) {
                assertEquals(Status.Code.DEADLINE_EXCEEDED, Status.fromThrowable(run.await().exceptionOrNull()!!).code)
                assertEquals(
                    RawSqlValidationResult.Failed("RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", "raw SQL validation is temporarily unavailable"),
                    analysis.await().getOrThrow(),
                )
            }
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            NameResolverRegistry.getDefaultRegistry().deregister(resolver)
        }
    }

    @Test
    fun `analysis transport failure uses safe existing result while cancellation propagates`() = runBlocking {
        val endpoint = RecordingExecutionEndpoint(holdRun = false)
        val server = NettyServerBuilder.forAddress(InetSocketAddress("127.0.0.1", 0)).addService(endpoint).build().start()
        val (channel, client) = GrpcExecutionRunClient.plaintext(URI("http://127.0.0.1:${server.port}"), 65_536)
        try {
            endpoint.analysisFailure = Status.UNAVAILABLE.withDescription("private warehouse connection details")
            val request = analysis(assignment(Instant.now().plusSeconds(5)))
            assertEquals(
                RawSqlValidationResult.Failed("EXECUTION_UNAVAILABLE", "raw SQL validation is temporarily unavailable"),
                client.validateRawSql(request),
            )
            endpoint.analysisFailure = Status.CANCELLED
            val cancellation = assertFailsWith<StatusException> { client.validateRawSql(request) }
            assertEquals(Status.Code.CANCELLED, cancellation.status.code)
            assertEquals(listOf(request.runId.toString(), request.runId.toString()), endpoint.analysisRunIds)
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `warehouse validation failure codes remain distinct from RPC unavailability`() = runBlocking {
        val endpoint = RecordingExecutionEndpoint(holdRun = false)
        val server = NettyServerBuilder.forAddress(InetSocketAddress("127.0.0.1", 0)).addService(endpoint).build().start()
        val (channel, client) = GrpcExecutionRunClient.plaintext(URI("http://127.0.0.1:${server.port}"), 65_536)
        try {
            for (code in listOf("CONNECTION_FAILED", "AUTHENTICATION_FAILED", "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED", "RAW_SQL_VALIDATION_FAILED")) {
                endpoint.validationFailureCode = code
                assertEquals(
                    RawSqlValidationResult.Failed(code, "safe warehouse diagnostic"),
                    client.validateRawSql(analysis(assignment(Instant.now().plusSeconds(5)))),
                )
            }
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `verified inline batches reach the caller before the terminal`() = runBlocking {
        val payload = byteArrayOf(1, 2, 3)
        val events = runAgainstSingleBatch(payload, sha256Hex(payload))

        val batch = events.filterIsInstance<ExecutionRunEvent.InlineResultBatch>().single()
        assertEquals(1uL, batch.sequence)
        assertEquals(sha256Hex(payload), batch.sha256.value)
        assertTrue(events.last() is ExecutionRunEvent.Terminal)
    }

    @Test
    fun `a batch whose digest does not match fails the stream instead of being skipped`() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking { runAgainstSingleBatch(byteArrayOf(1, 2, 3), "0".repeat(64)) }
        }
    }

    private suspend fun runAgainstSingleBatch(payload: ByteArray, declaredSha256: String): List<ExecutionRunEvent> {
        val server = NettyServerBuilder.forAddress(InetSocketAddress("127.0.0.1", 0))
            .addService(SingleBatchEndpoint(payload, declaredSha256))
            .build()
            .start()
        val (channel, client) = GrpcExecutionRunClient.plaintext(URI("http://127.0.0.1:${server.port}"), 65_536)
        try {
            return client.run(assignment(Instant.now().plusSeconds(10))).toList()
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private suspend fun awaitTransientFailure(channel: ManagedChannel) {
        withTimeout(5_000) {
            while (channel.getState(true) != ConnectivityState.TRANSIENT_FAILURE) delay(10)
        }
    }

    private fun assignment(deadline: Instant) = ControlRunAssignment(
        runId = ExecutionRunId.generate(),
        workspaceId = WorkspaceId(Uuid.generateV7()),
        connectorId = ConnectorReference(Uuid.generateV7()),
        connectorType = ConnectorType.POSTGRESQL,
        properties = mapOf("host" to ConnectorRuntimePropertyValue.Text("warehouse.invalid")),
        credentialDocument = byteArrayOf(1),
        queryRequest = BinaryValue.copyOf(
            QueryRequest.newBuilder().setSchemaVersion(1).setSql("select 1")
                .setInlineStream(InlineStreamTarget.getDefaultInstance()).build().toByteArray(),
        ),
        deadline = deadline,
    )

    private fun analysis(assignment: ControlRunAssignment) = RawSqlValidationRequest(
        runId = assignment.runId,
        workspaceId = assignment.workspaceId,
        connectorId = assignment.connectorId,
        connectorType = assignment.connectorType,
        properties = assignment.properties,
        credentialDocument = assignment.credentialDocument,
        sql = "select 1",
        parameters = emptyList(),
        deadline = assignment.deadline,
    )

    private class RecordingExecutionEndpoint(private val holdRun: Boolean) : ExecutionPlaneGrpcKt.ExecutionPlaneCoroutineImplBase() {
        val runIds = CopyOnWriteArrayList<String>()
        val analysisRunIds = CopyOnWriteArrayList<String>()
        val runStarted = CompletableDeferred<Unit>()
        @Volatile var analysisFailure: Status? = null
        @Volatile var validationFailureCode: String? = null

        override fun run(requests: Flow<ControlMessage>): Flow<ExecutionEvent> = flow {
            runIds += requests.first().assignment.runId
            emit(ExecutionEvent.newBuilder().setProgress(RunProgress.newBuilder().setPhase("started")).build())
            runStarted.complete(Unit)
            if (holdRun) awaitCancellation()
        }

        override suspend fun validateRawSql(request: ValidateRawSqlRequest): ValidateRawSqlResponse {
            analysisRunIds += request.runId
            analysisFailure?.let { throw it.asException() }
            validationFailureCode?.let { code ->
                return ValidateRawSqlResponse.newBuilder().setFailed(
                    cc.monomer.execution.protocol.v1.RawSqlValidationFailed.newBuilder()
                        .setFailureCode(code).setSafeMessage("safe warehouse diagnostic"),
                ).build()
            }
            return ValidateRawSqlResponse.newBuilder().setAccepted(RawSqlAccepted.getDefaultInstance()).build()
        }
    }

    private class SingleBatchEndpoint(
        private val payload: ByteArray,
        private val declaredSha256: String,
    ) : ExecutionPlaneGrpcKt.ExecutionPlaneCoroutineImplBase() {
        override fun run(requests: Flow<ControlMessage>): Flow<ExecutionEvent> = flow {
            requests.first()
            emit(
                ExecutionEvent.newBuilder()
                    .setInlineResultBatch(
                        InlineResultBatch.newBuilder()
                            .setSequence(1)
                            .setArrowIpc(ByteString.copyFrom(payload))
                            .setSha256(declaredSha256),
                    )
                    .build(),
            )
            emit(
                ExecutionEvent.newBuilder()
                    .setPhysicalOutcome(PhysicalOutcome.newBuilder().setKind(PhysicalOutcomeKind.PHYSICAL_OUTCOME_KIND_SUCCEEDED))
                    .build(),
            )
        }
    }

    // Real loopback Netty transports use the production channel factory. Only DNS answers and
    // failures are controlled: this does not simulate JVM DNS caching or Cloud Map propagation.
    private class RotatingResolverProvider(@Volatile var port: Int?) : NameResolverProvider() {
        val hostname = "rolling-${Uuid.random()}.invalid"
        val refreshes = AtomicInteger()

        override fun isAvailable() = true
        override fun priority() = 10
        override fun getDefaultScheme() = "dns"
        override fun newNameResolver(targetUri: URI, args: NameResolver.Args): NameResolver? {
            if (targetUri.scheme != "dns" || !targetUri.path.startsWith("/$hostname:")) return null
            return object : NameResolver() {
                private lateinit var listener: Listener2
                override fun getServiceAuthority() = hostname
                override fun start(listener: Listener2) {
                    this.listener = listener
                    resolve()
                }
                override fun refresh() {
                    refreshes.incrementAndGet()
                    resolve()
                }
                private fun resolve() {
                    val currentPort = port
                    if (currentPort == null) {
                        listener.onError(Status.UNAVAILABLE.withDescription("Replacement endpoint is not yet discoverable"))
                    } else {
                        listener.onResult(
                            ResolutionResult.newBuilder()
                                .setAddressesOrError(StatusOr.fromValue(listOf(EquivalentAddressGroup(InetSocketAddress("127.0.0.1", currentPort)))))
                                .build(),
                        )
                    }
                }
                override fun shutdown() = Unit
            }
        }
    }
}
