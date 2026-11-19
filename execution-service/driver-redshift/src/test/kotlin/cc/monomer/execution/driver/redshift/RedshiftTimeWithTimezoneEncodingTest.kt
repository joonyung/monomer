package cc.monomer.execution.driver.redshift

import cc.monomer.execution.arrow.JdbcArrowEncoder
import cc.monomer.execution.arrow.JdbcDecimalPhysicalEncoding
import cc.monomer.execution.driver.spi.BinaryValue
import cc.monomer.execution.driver.spi.UnsupportedResultTypeException
import com.amazon.redshift.RedshiftResultSetMetaData
import com.amazon.redshift.core.BaseConnection
import com.amazon.redshift.core.BaseStatement
import com.amazon.redshift.core.Encoding
import com.amazon.redshift.core.Field
import com.amazon.redshift.core.Oid
import com.amazon.redshift.core.QueryExecutor
import com.amazon.redshift.core.Tuple
import com.amazon.redshift.jdbc.RedshiftResultSet
import com.amazon.redshift.jdbc.TypeInfoCache
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.sql.ResultSet
import java.sql.Types
import java.time.OffsetTime
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.ipc.ArrowFileReader
import org.apache.arrow.vector.ipc.SeekableReadChannel
import org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel

class RedshiftTimeWithTimezoneEncodingTest {
    @Test
    fun `pinned vendor reports timetz as TIME_WITH_TIMEZONE and exposes original text and raw binary`() = withNonUtcTimezone {
        withResultSet(false, true) { resultSet ->
            assertEquals(Types.TIME_WITH_TIMEZONE, resultSet.metaData.getColumnType(1))
            assertTrue(resultSet.next())
            assertEquals(values.first(), resultSet.getString(1))
        }
        withResultSet(true, true) { resultSet ->
            assertEquals(Field.BINARY_FORMAT, resultSet.metaData.unwrap(RedshiftResultSetMetaData::class.java).getFormat(1))
            assertTrue(resultSet.statement.connection.unwrap(BaseConnection::class.java).queryExecutor.integerDateTimes)
            assertTrue(resultSet.next())
            assertContentEquals(binaryTime(values.first()), resultSet.getBytes(1))
        }
    }

    @Test
    fun `text and binary timetz retain wall time microseconds and original offsets in both delivery modes`() = withNonUtcTimezone {
        listOf(false, true).forEach { binary ->
            listOf(false, true).forEach { inline ->
                withResultSet(binary, true) { resultSet ->
                    val payloads = mutableListOf<BinaryValue>()
                    runBlocking {
                        val encoder = JdbcArrowEncoder(32, JdbcDecimalPhysicalEncoding.NATIVE_DECIMAL, RedshiftJdbcResultValueReader)
                        if (inline) encoder.streamInline(resultSet, 32, 65536, 65536, payloads::add)
                        else encoder.stream(resultSet, 32uL, 32, 65536, 65536, payloads::add)
                    }
                    val bytes = payloads.map(BinaryValue::copyBytes).reduce(ByteArray::plus)
                    RootAllocator().use { allocator ->
                        ArrowFileReader(SeekableReadChannel(ByteArrayReadableSeekableByteChannel(bytes)), allocator).use { reader ->
                            assertTrue(reader.loadNextBatch())
                            val vector = assertIs<VarCharVector>(reader.vectorSchemaRoot.getVector("value"))
                            assertTrue(vector.field.isNullable)
                            values.forEachIndexed { index, value -> assertEquals(value, vector.get(index).toString(Charsets.UTF_8)) }
                            assertTrue(vector.isNull(values.size))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `floating binary timetz fails with safe column and type diagnostics`() = withNonUtcTimezone {
        withResultSet(true, false) { resultSet ->
            assertTrue(resultSet.next())
            val failure = assertFailsWith<UnsupportedResultTypeException> { RedshiftJdbcResultValueReader.readText(resultSet, 1) }
            assertEquals("Column 'value' has unsupported result type 'timetz'", failure.message)
            repeat(values.size) { resultSet.next() }
            assertEquals(null, RedshiftJdbcResultValueReader.readText(resultSet, 1))
        }
    }

    private fun withResultSet(binary: Boolean, integerDateTimes: Boolean, verify: (RedshiftResultSet) -> Unit) {
        val executor = proxy<QueryExecutor> { method, _ ->
            when (method) {
                "getIntegerDateTimes" -> integerDateTimes
                "getServerProtocolVersion" -> 0
                "closeRingBufferThread" -> null
                else -> error("unexpected query executor operation: $method")
            }
        }
        lateinit var typeInfo: TypeInfoCache
        lateinit var connection: BaseConnection
        connection = proxy { method, _ ->
            when (method) {
                "getTypeInfo" -> typeInfo
                "getEncoding" -> Encoding.defaultEncoding()
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
        val tuples = values.map { value ->
            val bytes = if (!binary) value.toByteArray() else if (integerDateTimes) binaryTime(value) else binaryFloatingTime(value)
            Tuple(arrayOf(bytes))
        } + Tuple(arrayOfNulls<ByteArray>(1))
        val constructor = RedshiftResultSet::class.java.declaredConstructors.single().apply { isAccessible = true }
        val resultSet = constructor.newInstance(
            null, statement, arrayOf(field), tuples, null, 0, 0, ResultSet.TYPE_FORWARD_ONLY,
            ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT, null, null, null,
        ) as RedshiftResultSet
        try {
            verify(resultSet)
        } finally {
            RedshiftResultSet::class.java.getDeclaredMethod("closeInternally").apply { isAccessible = true }.invoke(resultSet)
        }
    }

    private fun binaryTime(text: String): ByteArray {
        val value = OffsetTime.parse(text)
        return ByteBuffer.allocate(12).putLong(value.toLocalTime().toNanoOfDay() / 1_000)
            .putInt(-value.offset.totalSeconds).array()
    }

    private fun binaryFloatingTime(text: String): ByteArray {
        val value = OffsetTime.parse(text)
        return ByteBuffer.allocate(12).putDouble(value.toLocalTime().toNanoOfDay() / 1_000_000_000.0)
            .putInt(-value.offset.totalSeconds).array()
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

    private val values = listOf("12:34:56.123456+09:00", "00:00:01.000001-03:30", "23:59:59.999999+05:30:45")
}
