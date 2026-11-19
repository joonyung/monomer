package cc.monomer.execution.driver.postgresql

import cc.monomer.execution.arrow.JdbcArrowEncoder
import cc.monomer.execution.arrow.JdbcDecimalPhysicalEncoding
import cc.monomer.execution.driver.spi.BinaryValue
import cc.monomer.execution.driver.spi.UnsupportedResultTypeException
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.LocalTime
import java.time.OffsetTime
import java.time.DateTimeException
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.ipc.ArrowFileReader
import org.apache.arrow.vector.ipc.SeekableReadChannel
import org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel
import org.postgresql.core.BaseConnection
import org.postgresql.core.BaseStatement
import org.postgresql.core.Encoding
import org.postgresql.core.Field
import org.postgresql.core.Oid
import org.postgresql.core.QueryExecutor
import org.postgresql.core.Tuple
import org.postgresql.jdbc.PgResultSet
import org.postgresql.jdbc.TypeInfoCache

class PostgresqlTimeWithTimezoneEncodingTest {
    @Test
    fun `pinned vendor reports timetz as TIME and rejects LocalTime with datatype mismatch`() = withNonUtcTimezone {
        withResultSet(false, true) { resultSet ->
            assertEquals(Types.TIME, resultSet.metaData.getColumnType(1))
            assertEquals("timetz", resultSet.metaData.getColumnTypeName(1))
            assertTrue(resultSet.next())
            val failure = assertFailsWith<SQLException> { resultSet.getObject(1, LocalTime::class.java) }
            assertEquals("42821", failure.sqlState)
            assertEquals("UNSUPPORTED_RESULT_TYPE", postgresqlFailureCode(failure, validating = false))
            assertEquals(values.first(), resultSet.getString(1))
        }
        withResultSet(true, true) { resultSet ->
            assertTrue(resultSet.next())
            assertNotEquals(values.first(), resultSet.getString(1))
            assertEquals(OffsetTime.parse(expected.first()), resultSet.getObject(1, OffsetTime::class.java))
            repeat(values.size - 1) { resultSet.next() }
            assertFailsWith<DateTimeException> { resultSet.getObject(1, OffsetTime::class.java) }
        }
    }

    @Test
    fun `text and binary timetz preserve original wall time microseconds and offsets in both delivery modes`() = withNonUtcTimezone {
        listOf(false, true).forEach { binary ->
            listOf(false, true).forEach { inline ->
                withResultSet(binary, true) { resultSet ->
                    val payloads = mutableListOf<BinaryValue>()
                    runBlocking {
                        val encoder = JdbcArrowEncoder(32, JdbcDecimalPhysicalEncoding.NATIVE_DECIMAL, PostgresqlJdbcResultValueReader)
                        if (inline) encoder.streamInline(resultSet, 32, 65536, 65536, payloads::add)
                        else encoder.stream(resultSet, 32uL, 32, 65536, 65536, payloads::add)
                    }
                    val bytes = payloads.map(BinaryValue::copyBytes).reduce(ByteArray::plus)
                    RootAllocator().use { allocator ->
                        ArrowFileReader(SeekableReadChannel(ByteArrayReadableSeekableByteChannel(bytes)), allocator).use { reader ->
                            assertTrue(reader.loadNextBatch())
                            val vector = assertIs<VarCharVector>(reader.vectorSchemaRoot.getVector("value"))
                            assertTrue(vector.field.isNullable)
                            expected.forEachIndexed { index, value -> assertEquals(value, vector.get(index).toString(Charsets.UTF_8)) }
                            assertTrue(vector.isNull(expected.size))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `floating binary timetz fails with safe column and type diagnostics while null is preserved`() = withNonUtcTimezone {
        withResultSet(true, false) { resultSet ->
            assertTrue(resultSet.next())
            val failure = assertFailsWith<UnsupportedResultTypeException> { PostgresqlJdbcResultValueReader.readText(resultSet, 1) }
            assertEquals("Column 'value' has unsupported result type 'timetz'", failure.message)
            repeat(values.size) { resultSet.next() }
            assertEquals(null, PostgresqlJdbcResultValueReader.readText(resultSet, 1))
        }
    }

    private fun withResultSet(binary: Boolean, integerDateTimes: Boolean, verify: (PgResultSet) -> Unit) {
        val executor = proxy<QueryExecutor> { method, _ ->
            when (method) {
                "getIntegerDateTimes" -> integerDateTimes
                "getTimeZone" -> TimeZone.getTimeZone("Asia/Kathmandu")
                else -> error("unexpected query executor operation: $method")
            }
        }
        lateinit var typeInfo: TypeInfoCache
        lateinit var connection: BaseConnection
        connection = proxy { method, _ ->
            when (method) {
                "getTypeInfo" -> typeInfo
                "getEncoding" -> Encoding.getJVMEncoding("UTF-8")
                "getLogger" -> Logger.getLogger("postgresql-result-fixture")
                "getQueryExecutor" -> executor
                "getFieldMetadataCache" -> null
                "unwrap" -> connection
                else -> error("unexpected connection operation: $method")
            }
        }
        typeInfo = TypeInfoCache(connection, 0)
        val statement = proxy<BaseStatement> { method, _ ->
            when (method) {
                "getConnection" -> connection
                else -> error("unexpected statement operation: $method")
            }
        }
        val field = Field("value", Oid.TIMETZ).apply { if (binary) format = Field.BINARY_FORMAT }
        val tuples = values.mapIndexed { index, value ->
            val bytes = if (!binary) value.toByteArray() else binaryTime(expected[index], integerDateTimes)
            Tuple(arrayOf(bytes))
        } + Tuple(arrayOfNulls<ByteArray>(1))
        val constructor = PgResultSet::class.java.declaredConstructors.single().apply { isAccessible = true }
        val resultSet = constructor.newInstance(
            null, statement, arrayOf(field), tuples, null, 0, 0, ResultSet.TYPE_FORWARD_ONLY,
            ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT, false,
        ) as PgResultSet
        try {
            verify(resultSet)
        } finally {
            PgResultSet::class.java.getDeclaredMethod("closeInternally").apply { isAccessible = true }.invoke(resultSet)
        }
    }

    private fun binaryTime(text: String, integerDateTimes: Boolean): ByteArray {
        val microseconds: Long
        val offset: ZoneOffset
        if (text.startsWith("24:00:00")) {
            microseconds = 86_400_000_000L
            offset = ZoneOffset.of(text.substring(8))
        } else {
            val value = OffsetTime.parse(text)
            microseconds = value.toLocalTime().toNanoOfDay() / 1_000
            offset = value.offset
        }
        val bytes = ByteBuffer.allocate(12)
        if (integerDateTimes) bytes.putLong(microseconds) else bytes.putDouble(microseconds / 1_000_000.0)
        return bytes.putInt(-offset.totalSeconds).array()
    }

    private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, arguments ->
            invoke(method.name, arguments.orEmpty())
        } as T

    private fun withNonUtcTimezone(verify: () -> Unit) {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        try { verify() } finally { TimeZone.setDefault(previous) }
    }

    private val values = listOf("12:34:56.123456+09", "00:00:01.000001-03:30", "23:59:59.999999+05:30:45", "24:00:00+00")
    private val expected = listOf("12:34:56.123456+09:00", "00:00:01.000001-03:30", "23:59:59.999999+05:30:45", "24:00:00+00:00")
}
