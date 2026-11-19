package cc.monomer.execution.driver.jdbc

import cc.monomer.execution.driver.spi.*
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.ParameterMetaData
import java.sql.Types
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

object JdbcTimingFixture {
    const val RUN_ID = "019f0000-0000-7000-8000-000000000001"
    const val WORKSPACE_ID = "019f0000-0000-7000-8000-000000000002"
    const val SQL = "SELECT ? AS timing_private_sql_marker"
    const val BINDING = "timing-private-binding"
    const val RESULT = "timing-private-result"
    const val PASSWORD = "timing-private-password"
    const val EXCEPTION = "timing-private-exception"

    fun timing(driver: ConnectorType, operation: DriverOperation) =
        DriverOperationTiming(driver, operation, RUN_ID, WORKSPACE_ID)

    suspend fun query(
        provider: ExecutionDriverProvider,
        connector: ConnectorRuntimeBinding,
        credential: MaterializedCredential?,
        timing: DriverOperationTiming,
        target: QueryResultTarget,
        sink: RunResultSink,
        sql: String = SQL,
    ): DriverPhysicalResult {
        val unsigned = QueryRequest(1u, Sha256Digest("0".repeat(64)), sql,
            listOf(QueryParameter.Text(BINDING, name = "")), target, false, emptyList())
        val request = unsigned.copy(canonicalRequestDigest = QueryRequestCanonicalizer.canonicalDigest(unsigned))
        val session = provider.openSession(connector, credential, sink, timing)
        return try {
            session.enter(StartExecutionContext(RunIdentity(RunId.parse(RUN_ID)),
                RunAssignment(WorkspaceId.parse(WORKSPACE_ID), connector, request,
                    System.currentTimeMillis() + 30_000)))
            session.awaitPhysicalResult()
        } finally {
            session.close()
        }
    }

    val inlineTarget = QueryResultTarget.InlineStream(2, 65536, 65536)
    val inlineSink = object : RunResultSink {
        override suspend fun emitInlineResult(batch: InlineResultBatch) = Unit
        override suspend fun openObjectUpload(objectKey: String, mediaType: String): ArtifactUploadSink = error("unused")
    }

    suspend fun summary(
        driver: ConnectorType,
        operation: DriverOperation,
        expectedFailureCode: String?,
        expectedFailurePhase: DriverPhase?,
        measuredPhases: Set<DriverPhase>,
        additionalSecrets: List<String> = emptyList(),
        operationTiming: DriverOperationTiming = timing(driver, operation),
        block: suspend (DriverOperationTiming) -> DriverPhysicalResult,
    ): String {
        val records = CopyOnWriteArrayList<LogRecord>()
        val logger = Logger.getLogger("")
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { records += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        try {
            val timing = operationTiming
            val outcome = block(timing)
            timing.log(outcome)
            timing.log(outcome)
            val summaries = records.filter { it.message.startsWith("driver_operation_timing ") }
            assertEquals(1, summaries.size)
            val record = summaries.single()
            val message = record.message
            assertEquals(java.util.logging.Level.INFO, record.level)
            assertEquals(null, record.thrown)
            assertTrue(message.contains("run_id=$RUN_ID workspace_id=$WORKSPACE_ID driver=$driver operation=$operation"), message)
            assertTrue(message.contains("outcome=${if (expectedFailureCode == null) "SUCCEEDED" else "FAILED"}"), message)
            assertTrue(message.contains("failure_code=${expectedFailureCode ?: "none"}"), message)
            assertTrue(message.contains("failure_phase=${expectedFailurePhase?.name ?: "none"}"), message)
            measuredPhases.forEach { phase ->
                assertTrue(Regex("(?:^| )${phase.name.lowercase()}_ms=[0-9]+(?: |$)").containsMatchIn(message), message)
            }
            (listOf(SQL, BINDING, RESULT, PASSWORD, EXCEPTION, "timing-private-user", "timing-private-object-key", "jdbc:") + additionalSecrets).forEach { secret ->
                records.forEach { captured -> assertFalse(captured.message.contains(secret), captured.message) }
            }
            return message
        } finally {
            logger.removeHandler(handler)
        }
    }

    fun connection(native: Any? = null, failure: Throwable? = null, readFailure: Throwable? = null): Connection {
        val metadata = proxy<ResultSetMetaData> { method, _ -> when (method) {
            "getColumnCount" -> 1
            "getColumnLabel", "getColumnName" -> "timing_private_sql_marker"
            "getColumnType" -> Types.VARCHAR
            "getColumnTypeName" -> "VARCHAR"
            "isNullable" -> ResultSetMetaData.columnNoNulls
            else -> null
        } }
        fun statement(queryFailure: Throwable?): PreparedStatement {
        var next = true
        val result = proxy<ResultSet> { method, _ -> when (method) {
            "next" -> readFailure?.let { throw it } ?: next.also { next = false }
            "getString" -> RESULT
            "getMetaData" -> metadata
            "wasNull" -> false
            else -> null
        } }
        return proxy<PreparedStatement> { method, _ -> when (method) {
            "execute" -> queryFailure?.let { throw it } ?: true
            "executeQuery", "getResultSet" -> result
            "getMetaData" -> metadata
            "getParameterMetaData" -> proxy<ParameterMetaData> { call, _ -> if (call == "getParameterCount") 1 else null }
            else -> null
        } }
        }
        return proxy<Connection> { method, args -> when (method) {
            "prepareStatement" -> statement(failure.takeIf { args[0] == SQL })
            "createStatement" -> statement(null)
            "unwrap" -> native
            "getAutoCommit" -> false
            else -> null
        } }
    }

    inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args.orEmpty()) ?: when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Double.TYPE -> 0.0
                java.lang.Float.TYPE -> 0.0f
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Byte.TYPE -> 0.toByte()
                else -> null
            }
        } as T
}
