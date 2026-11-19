package cc.monomer.execution.application

import cc.monomer.execution.driver.bigquery.BigQueryCallTimeoutPolicy
import cc.monomer.execution.driver.bigquery.BigQueryExecutionDriverProvider
import cc.monomer.execution.driver.bigquery.GoogleBigQueryGatewayFactory
import cc.monomer.execution.driver.jdbc.JdbcRunTimeoutPolicy
import cc.monomer.execution.driver.jdbc.DriverManagerJdbcConnectionFactory
import cc.monomer.execution.driver.postgresql.PostgresqlExecutionDriverProvider
import cc.monomer.execution.driver.jdbc.WarehouseJdbcConnectionFactory
import cc.monomer.execution.driver.trino.TrinoJdbcConnectionFactory
import cc.monomer.execution.driver.redshift.DefaultRedshiftIamCredentialsClient
import cc.monomer.execution.driver.redshift.RedshiftExecutionDriverProvider
import cc.monomer.execution.driver.trino.TrinoExecutionDriverProvider
import cc.monomer.execution.driver.snowflake.SnowflakeExecutionDriverProvider
import cc.monomer.execution.driver.databricks.DatabricksExecutionDriverProvider
import cc.monomer.execution.driver.duckdb.DuckDbExecutionDriverProvider
import cc.monomer.execution.driver.spi.ExecutionDriverRegistry
import cc.monomer.execution.storage.s3.S3DirectArtifactUploader
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.grpc.InsecureServerCredentials
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

interface LocalHealthEndpoint : AutoCloseable

fun interface LocalHealthEndpointFactory {
    fun start(
        bindAddress: String,
        port: Int,
        health: LocalHealth,
    ): LocalHealthEndpoint
}

interface ExecutionPlaneRuntime : AutoCloseable {
    fun initiateShutdown()

    fun abortBeforeStart()

    suspend fun awaitShutdown()

    suspend fun awaitUnexpectedTermination(): Throwable
}

fun interface ExecutionPlaneRuntimeFactory {
    fun create(configuration: ExecutionServiceConfiguration): ExecutionPlaneRuntime
}

fun interface BlockingExecutorFactory {
    fun create(): ExecutorService
}

fun interface ExecutionDriverRegistryFactory {
    fun create(
        configuration: ExecutionServiceConfiguration,
        blockingDispatcher: CoroutineDispatcher,
    ): ExecutionDriverRegistry
}

class ExecutionServiceLauncher(
    private val healthEndpointFactory: LocalHealthEndpointFactory,
    private val planeRuntimeFactory: ExecutionPlaneRuntimeFactory,
) {
    fun start(configuration: ExecutionServiceConfiguration): RunningExecutionService {
        val health = ExecutionServiceComposition().start()
        val planeRuntime = planeRuntimeFactory.create(configuration)
        val healthEndpoint =
            try {
                healthEndpointFactory.start(
                    bindAddress = configuration.healthBindAddress,
                    port = configuration.healthPort,
                    health = health,
                )
            } catch (failure: Throwable) {
                planeRuntime.abortBeforeStart()
                throw failure
            }
        return RunningExecutionService(
            healthEndpoint = healthEndpoint,
            planeRuntime = planeRuntime,
        )
    }
}

class RunningExecutionService internal constructor(
    private val healthEndpoint: LocalHealthEndpoint,
    private val planeRuntime: ExecutionPlaneRuntime,
) : AutoCloseable {
    private val shutdownStarted = AtomicBoolean(false)

    suspend fun awaitServerFailure(): Throwable = planeRuntime.awaitUnexpectedTermination()

    override fun close() {
        if (shutdownStarted.compareAndSet(false, true)) {
            planeRuntime.initiateShutdown()
            healthEndpoint.close()
        }
    }

    suspend fun closeGracefully() {
        close()
        planeRuntime.awaitShutdown()
    }
}

class JdkLocalHealthEndpointFactory : LocalHealthEndpointFactory {
    override fun start(
        bindAddress: String,
        port: Int,
        health: LocalHealth,
    ): LocalHealthEndpoint {
        require(port in 0..65_535)
        val executor =
            Executors.newSingleThreadExecutor(
                Thread.ofPlatform()
                    .daemon(true)
                    .name("execution-health")
                    .factory(),
            )
        val server = HttpServer.create(InetSocketAddress(bindAddress, port), 0)
        server.executor = executor
        server.createContext("/healthz") { exchange ->
            handleHealth(exchange, health)
        }
        server.start()
        return JdkLocalHealthEndpoint(server, executor)
    }

    private fun handleHealth(
        exchange: HttpExchange,
        health: LocalHealth,
    ) {
        if (exchange.requestURI.path != "/healthz") {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
            return
        }
        if (exchange.requestMethod != "GET") {
            exchange.sendResponseHeaders(405, -1)
            exchange.close()
            return
        }
        val response =
            "{\"status\":\"${health.status}\"}".toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendResponseHeaders(200, response.size.toLong())
        exchange.responseBody.use { body -> body.write(response) }
        exchange.close()
    }
}

private class JdkLocalHealthEndpoint(
    private val server: HttpServer,
    private val executor: ExecutorService,
) : LocalHealthEndpoint {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}

class ProductionExecutionPlaneRuntimeFactory(
    private val clock: Clock,
    private val blockingExecutorFactory: BlockingExecutorFactory,
    private val driverRegistryFactory: ExecutionDriverRegistryFactory,
) : ExecutionPlaneRuntimeFactory {
    override fun create(configuration: ExecutionServiceConfiguration): ExecutionPlaneRuntime {
        val blockingExecutor = blockingExecutorFactory.create()
        val blockingDispatcher = blockingExecutor.asCoroutineDispatcher()
        var server: Server? = null
        var artifactUploader: S3DirectArtifactUploader? = null
        var driverRegistry: ExecutionDriverRegistry? = null
        try {
            driverRegistry =
                driverRegistryFactory.create(
                    configuration = configuration,
                    blockingDispatcher = blockingDispatcher,
                )
            artifactUploader =
                S3DirectArtifactUploader.create(
                    settings = configuration.artifactStore,
                    blockingDispatcher = blockingDispatcher,
                    clock = clock,
                )
            val service =
                ExecutionPlaneService(
                    driverRegistry = requireNotNull(driverRegistry),
                    maximumCredentialDocumentBytes = MAXIMUM_CREDENTIAL_DOCUMENT_BYTES,
                    maximumCredentialFields = MAXIMUM_CREDENTIAL_FIELDS,
                    maximumCredentialFieldCharacters = MAXIMUM_CREDENTIAL_FIELD_CHARACTERS,
                    artifactUploader = requireNotNull(artifactUploader),
                )
            val listenAddress = InetSocketAddress(configuration.listenAddress, configuration.listenPort)
            val startedServer =
                NettyServerBuilder.forAddress(
                    listenAddress,
                    InsecureServerCredentials.create(),
                )
                    .addService(service.bindService())
                    .maxInboundMessageSize(MAXIMUM_FRAME_BYTES)
                    .build()
                    .start()
                server = startedServer
            return NettyExecutionPlaneRuntime(
                server = startedServer,
                blockingDispatcher = blockingDispatcher,
                blockingExecutor = blockingExecutor,
                artifactUploader = requireNotNull(artifactUploader),
                driverRegistry = requireNotNull(driverRegistry),
            )
        } catch (failure: Throwable) {
            server?.shutdownNow()
            artifactUploader?.close()
            driverRegistry?.close()
            closeBlockingResources(blockingDispatcher, blockingExecutor)
            throw failure
        }
    }

    private companion object {
        const val MAXIMUM_CREDENTIAL_DOCUMENT_BYTES = 60 * 1_024
        const val MAXIMUM_CREDENTIAL_FIELDS = 32
        const val MAXIMUM_CREDENTIAL_FIELD_CHARACTERS = 32 * 1_024
        const val MAXIMUM_FRAME_BYTES = 8 * 1_024 * 1_024
    }
}

class ProductionExecutionDriverRegistryFactory(
    private val clock: Clock,
) : ExecutionDriverRegistryFactory {
    override fun create(
        configuration: ExecutionServiceConfiguration,
        blockingDispatcher: CoroutineDispatcher,
    ): ExecutionDriverRegistry {
        val jdbcTimeoutPolicy =
            JdbcRunTimeoutPolicy(
                maximumConnectMillis = 30_000,
                maximumSocketMillis = 3_600_000,
                maximumLockMillis = 5_000,
            )
        return ExecutionDriverRegistry(
            listOf(
                PostgresqlExecutionDriverProvider(
                    maximumQuerySeconds = 3_600,
                    arrowBatchSize = 1_024,
                    warehouseTrustCertificatePath =
                        configuration.warehouseTrustCertificatePath,
                    timeoutPolicy = jdbcTimeoutPolicy,
                    blockingDispatcher = blockingDispatcher,
                    clock = clock,
                    connectionFactory = WarehouseJdbcConnectionFactory(configuration.localDwEndpoints),
                ),
                BigQueryExecutionDriverProvider(
                    gatewayFactory =
                        GoogleBigQueryGatewayFactory(
                            maximumBytesBilled = 1_000_000_000_000,
                            jobTimeout = Duration.ofHours(1),
                        ),
                    pollInterval = Duration.ofSeconds(1),
                    blockingDispatcher = blockingDispatcher,
                    clock = clock,
                    callTimeoutPolicy =
                        BigQueryCallTimeoutPolicy(Duration.ofSeconds(30)),
                ),
                RedshiftExecutionDriverProvider(
                    maximumQuerySeconds = 3_600,
                    arrowBatchSize = 1_024,
                    timeoutPolicy = jdbcTimeoutPolicy,
                    blockingDispatcher = blockingDispatcher,
                    clock = clock,
                    connectionFactory = WarehouseJdbcConnectionFactory(configuration.localDwEndpoints),
                    iamCredentialsClient = DefaultRedshiftIamCredentialsClient,
                ),
                TrinoExecutionDriverProvider(
                    maximumQuerySeconds = 3_600,
                    arrowBatchSize = 1_024,
                    warehouseTrustCertificatePath =
                        configuration.warehouseTrustCertificatePath,
                    timeoutPolicy = jdbcTimeoutPolicy,
                    blockingDispatcher = blockingDispatcher,
                    clock = clock,
                    connectionFactory = TrinoJdbcConnectionFactory(configuration.localDwEndpoints),
                ),
                SnowflakeExecutionDriverProvider(
                    maximumQuerySeconds = 3_600,
                    arrowBatchSize = 1_024,
                    timeoutPolicy = jdbcTimeoutPolicy,
                    blockingDispatcher = blockingDispatcher,
                    clock = clock,
                    connectionFactory = DriverManagerJdbcConnectionFactory,
                ),
                DatabricksExecutionDriverProvider(
                    maximumQuerySeconds = 3_600,
                    arrowBatchSize = 1_024,
                    maximumCallDuration = Duration.ofSeconds(30),
                    pollInterval = Duration.ofSeconds(1),
                    blockingDispatcher = blockingDispatcher,
                    clock = clock,
                    httpClient = java.net.http.HttpClient.newBuilder()
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(30))
                        .build(),
                ),
                DuckDbExecutionDriverProvider(
                    arrowBatchSize = 1_024,
                    allowedDatabaseRoot = configuration.sampleDataRoot,
                    blockingDispatcher = blockingDispatcher,
                    clock = clock,
                ),
            ),
        )
    }
}

private class NettyExecutionPlaneRuntime(
    private val server: Server,
    private val blockingDispatcher: ExecutorCoroutineDispatcher,
    private val blockingExecutor: ExecutorService,
    private val artifactUploader: AutoCloseable,
    private val driverRegistry: ExecutionDriverRegistry,
) : ExecutionPlaneRuntime {
    private val shutdownStarted = AtomicBoolean(false)
    private val resourcesClosed = AtomicBoolean(false)
    private val unexpectedTermination = CompletableDeferred<Throwable>()

    init {
        Thread.ofPlatform()
            .daemon(true)
            .name("execution-grpc-watch")
            .start {
                server.awaitTermination()
                if (!shutdownStarted.get()) {
                    unexpectedTermination.complete(
                        IllegalStateException("gRPC server terminated unexpectedly"),
                    )
                }
            }
    }

    override suspend fun awaitUnexpectedTermination(): Throwable = unexpectedTermination.await()

    override fun initiateShutdown() {
        if (shutdownStarted.compareAndSet(false, true)) {
            server.shutdown()
        }
    }

    override fun abortBeforeStart() {
        initiateShutdown()
        server.shutdownNow()
        if (resourcesClosed.compareAndSet(false, true)) {
            artifactUploader.close()
            driverRegistry.close()
            closeBlockingResources(blockingDispatcher, blockingExecutor)
        }
    }

    override suspend fun awaitShutdown() {
        try {
            server.awaitTermination(10, TimeUnit.SECONDS)
        } finally {
            if (resourcesClosed.compareAndSet(false, true)) {
                artifactUploader.close()
                driverRegistry.close()
                closeBlockingResources(blockingDispatcher, blockingExecutor)
            }
        }
    }

    override fun close() {
        initiateShutdown()
        if (resourcesClosed.compareAndSet(false, true)) {
            artifactUploader.close()
            driverRegistry.close()
            closeBlockingResources(blockingDispatcher, blockingExecutor)
        }
    }
}

private fun closeBlockingResources(
    blockingDispatcher: ExecutorCoroutineDispatcher,
    blockingExecutor: ExecutorService,
) {
    blockingDispatcher.close()
    if (!blockingExecutor.isShutdown) {
        blockingExecutor.shutdownNow()
    }
}

fun productionExecutionServiceLauncher(clock: Clock): ExecutionServiceLauncher =
    ExecutionServiceLauncher(
        healthEndpointFactory = JdkLocalHealthEndpointFactory(),
        planeRuntimeFactory =
            ProductionExecutionPlaneRuntimeFactory(
                clock = clock,
                blockingExecutorFactory =
                    BlockingExecutorFactory {
                        Executors.newVirtualThreadPerTaskExecutor()
                    },
                driverRegistryFactory = ProductionExecutionDriverRegistryFactory(clock),
            ),
    )
