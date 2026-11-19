package cc.monomer.execution.driver.postgresql

import cc.monomer.execution.driver.jdbc.JdbcConnectionFactory
import cc.monomer.execution.driver.jdbc.JdbcRunTimeoutPolicy
import cc.monomer.execution.driver.jdbc.JdbcTimingFixture
import cc.monomer.execution.driver.spi.ArtifactUploadSink
import cc.monomer.execution.driver.spi.ConnectorId
import cc.monomer.execution.driver.spi.ConnectorPropertyValue
import cc.monomer.execution.driver.spi.ConnectorRuntimeBinding
import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.DriverOperation
import cc.monomer.execution.driver.spi.DriverPhysicalResult
import cc.monomer.execution.driver.spi.InlineResultBatch
import cc.monomer.execution.driver.spi.MaterializedCredential
import cc.monomer.execution.driver.spi.RunResultSink
import cc.monomer.execution.driver.spi.SecretCharacters
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Types
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.DateDayVector
import org.apache.arrow.vector.TimeMicroVector
import org.apache.arrow.vector.TimeStampMicroTZVector
import org.apache.arrow.vector.TimeStampMicroVector
import org.apache.arrow.vector.ipc.ArrowFileReader
import org.apache.arrow.vector.ipc.SeekableReadChannel
import org.apache.arrow.vector.types.DateUnit
import org.apache.arrow.vector.types.TimeUnit
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel

class PostgresqlTemporalReadRegressionTest {
    @Test
    fun `preserves typed temporal reads and Arrow meanings in a non UTC JVM`() = runBlocking {
        val previousTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kathmandu"))
        try {
            assertEquals("Asia/Kathmandu", TimeZone.getDefault().id)
            val typedReads = mutableListOf<Pair<Int, Class<*>>>()
            val batches = mutableListOf<InlineResultBatch>()
            val sink = object : RunResultSink {
                override suspend fun emitInlineResult(batch: InlineResultBatch) { batches += batch }
                override suspend fun openObjectUpload(objectKey: String, mediaType: String): ArtifactUploadSink =
                    error("inline query must not open an artifact upload")
            }
            MaterializedCredential(mapOf(
                "username" to SecretCharacters.copyOf("fixture-reader".toCharArray()),
                "password" to SecretCharacters.copyOf("fixture-password".toCharArray()),
            )).use { credential ->
                val result =
                    JdbcTimingFixture.query(
                        provider = PostgresqlExecutionDriverProvider(
                            maximumQuerySeconds = 30,
                            arrowBatchSize = 2,
                            warehouseTrustCertificatePath = Path.of("unused.pem"),
                            timeoutPolicy = JdbcRunTimeoutPolicy(2_000, 20_000, 1_000),
                            blockingDispatcher = Dispatchers.IO,
                            clock = Clock.systemUTC(),
                            connectionFactory = JdbcConnectionFactory { _, _ -> connection(typedReads) },
                        ),
                        connector = connector,
                        credential = credential,
                        timing = JdbcTimingFixture.timing(ConnectorType.POSTGRESQL, DriverOperation.QUERY),
                        target = JdbcTimingFixture.inlineTarget,
                        sink = sink,
                    )
                assertEquals(2uL, assertIs<DriverPhysicalResult.Succeeded>(result).rowsProcessed)
            }
            assertEquals(
                List(2) { columns.mapIndexed { index, column -> (index + 1) to column.javaType } }.flatten(),
                typedReads,
            )
            val payload = batches.single().arrowIpc.copyBytes()
            try {
                RootAllocator().use { allocator ->
                    ArrowFileReader(SeekableReadChannel(ByteArrayReadableSeekableByteChannel(payload)), allocator).use { reader ->
                        reader.loadRecordBatch(reader.recordBlocks.single())
                        val root = reader.vectorSchemaRoot
                        assertEquals(2, root.rowCount)
                        assertEquals(listOf(
                            ArrowType.Date(DateUnit.DAY),
                            ArrowType.Time(TimeUnit.MICROSECOND, 64),
                            ArrowType.Timestamp(TimeUnit.MICROSECOND, null),
                            ArrowType.Timestamp(TimeUnit.MICROSECOND, "UTC"),
                            ArrowType.Timestamp(TimeUnit.MICROSECOND, "UTC"),
                        ), root.schema.fields.map { it.type })
                        assertEquals(-1, assertIs<DateDayVector>(root.getVector("date_value")).get(0))
                        assertEquals(86_399_123_456L, assertIs<TimeMicroVector>(root.getVector("time_value")).get(0))
                        assertEquals(-876_544L, assertIs<TimeStampMicroVector>(root.getVector("wallclock_value")).get(0))
                        assertEquals(-876_544L, assertIs<TimeStampMicroTZVector>(root.getVector("instant_explicit")).get(0))
                        assertEquals(-876_544L, assertIs<TimeStampMicroTZVector>(root.getVector("instant_named")).get(0))
                        root.fieldVectors.forEach { vector -> assertTrue(vector.isNull(1), vector.name) }
                    }
                }
            } finally {
                payload.fill(0)
            }
        } finally {
            TimeZone.setDefault(previousTimeZone)
        }
    }

    private fun connection(typedReads: MutableList<Pair<Int, Class<*>>>): Connection {
        val setupConnection = JdbcTimingFixture.connection()
        return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, arguments ->
            if (method.name == "prepareStatement" && arguments[0] == JdbcTimingFixture.SQL) {
                val rows = temporalRows(typedReads)
                JdbcTimingFixture.proxy<PreparedStatement> { operation, _ ->
                    when (operation) {
                        "execute" -> true
                        "getResultSet" -> rows
                        "setQueryTimeout", "setString", "close" -> null
                        else -> error("unexpected query statement operation: $operation")
                    }
                }
            } else {
                method.invoke(setupConnection, *arguments.orEmpty())
            }
        } as Connection
    }

    private fun temporalRows(typedReads: MutableList<Pair<Int, Class<*>>>): ResultSet {
        var row = -1
        var lastWasNull = false
        val metadata = JdbcTimingFixture.proxy<ResultSetMetaData> { method, arguments ->
            if (method == "getColumnCount") columns.size else {
                val column = columns[(arguments[0] as Int) - 1]
                when (method) {
                    "getColumnLabel", "getColumnName" -> column.name
                    "getColumnType" -> column.jdbcType
                    "getColumnTypeName" -> column.jdbcTypeName
                    "isNullable" -> ResultSetMetaData.columnNullable
                    else -> error("unexpected result metadata operation: $method")
                }
            }
        }
        return JdbcTimingFixture.proxy<ResultSet> { method, arguments ->
            when (method) {
                "next" -> (++row < 2)
                "getMetaData" -> metadata
                "wasNull" -> lastWasNull
                "close" -> null
                "getObject" -> {
                    assertEquals(2, arguments.size, "temporal reads must use the typed JDBC overload")
                    val columnIndex = arguments[0] as Int
                    val column = columns[columnIndex - 1]
                    assertEquals(column.javaType, arguments[1], column.name)
                    typedReads += columnIndex to (arguments[1] as Class<*>)
                    lastWasNull = row == 1
                    column.value.takeUnless { lastWasNull }
                }
                else -> error("unexpected temporal result getter: $method")
            }
        }
    }

    private data class TemporalColumn(
        val name: String,
        val jdbcType: Int,
        val jdbcTypeName: String,
        val javaType: Class<*>,
        val value: Any,
    )

    private val columns = listOf(
        TemporalColumn("date_value", Types.DATE, "date", LocalDate::class.java, LocalDate.parse("1969-12-31")),
        TemporalColumn("time_value", Types.TIME, "time", LocalTime::class.java, LocalTime.parse("23:59:59.123456")),
        TemporalColumn("wallclock_value", Types.TIMESTAMP, "timestamp", LocalDateTime::class.java,
            LocalDateTime.parse("1969-12-31T23:59:59.123456")),
        TemporalColumn("instant_explicit", Types.TIMESTAMP_WITH_TIMEZONE, "timestamp with time zone", OffsetDateTime::class.java,
            OffsetDateTime.parse("1970-01-01T05:29:59.123456+05:30")),
        TemporalColumn("instant_named", Types.TIMESTAMP, "timestamptz", OffsetDateTime::class.java,
            OffsetDateTime.parse("1969-12-31T15:59:59.123456-08:00")),
    )

    private val connector = ConnectorRuntimeBinding(
        schemaVersion = 1u,
        connectorId = ConnectorId.parse("019f0000-0000-7000-8000-000000000003"),
        connectorType = ConnectorType.POSTGRESQL,
        properties = mapOf(
            "host" to ConnectorPropertyValue.Text("warehouse.invalid"),
            "database" to ConnectorPropertyValue.Text("fixture"),
            "ssl_mode" to ConnectorPropertyValue.Text("disable"),
        ),
    )
}
