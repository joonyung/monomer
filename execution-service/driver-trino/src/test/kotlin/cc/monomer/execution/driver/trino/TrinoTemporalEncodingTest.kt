package cc.monomer.execution.driver.trino

import cc.monomer.execution.arrow.JdbcArrowEncoder
import cc.monomer.execution.arrow.JdbcDecimalPhysicalEncoding
import cc.monomer.execution.driver.spi.BinaryValue
import io.trino.jdbc.InMemoryTrinoResultSet
import io.trino.jdbc.`$internal`.client.ClientTypeSignature
import io.trino.jdbc.`$internal`.client.ClientTypeSignatureParameter
import io.trino.jdbc.`$internal`.client.Column
import java.sql.SQLDataException
import java.sql.SQLException
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.TimeMicroVector
import org.apache.arrow.vector.TimeStampMicroTZVector
import org.apache.arrow.vector.ipc.ArrowFileReader
import org.apache.arrow.vector.ipc.SeekableReadChannel
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel

class TrinoTemporalEncodingTest {
    @Test
    fun `pinned vendor rejects OffsetDateTime but encodes timestamp with time zone as UTC instant`() {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
        try {
            resultSet().use { resultSet ->
                assertTrue(resultSet.next())
                assertFailsWith<SQLException> { resultSet.getObject(1, OffsetDateTime::class.java) }
            }
            resultSet().use { resultSet ->
                val payloads = mutableListOf<BinaryValue>()
                runBlocking {
                    JdbcArrowEncoder(32, JdbcDecimalPhysicalEncoding.NATIVE_DECIMAL, TrinoJdbcResultValueReader)
                        .streamInline(resultSet, 32, 65536, 65536, payloads::add)
                }
                RootAllocator().use { allocator ->
                    ArrowFileReader(
                        SeekableReadChannel(ByteArrayReadableSeekableByteChannel(payloads.single().copyBytes())), allocator,
                    ).use { reader ->
                        assertTrue(reader.loadNextBatch())
                        val vector = assertIs<TimeStampMicroTZVector>(reader.vectorSchemaRoot.getVector("value"))
                        assertEquals("UTC", (vector.field.type as ArrowType.Timestamp).timezone)
                        assertEquals(-876544L, vector.get(0))
                        val instant = Instant.parse("2024-01-01T18:04:05.123456Z")
                        assertEquals(instant.epochSecond * 1_000_000L + instant.nano / 1_000L, vector.get(1))
                        assertTrue(vector.isNull(2))
                    }
                }
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `pinned vendor rejects LocalTime but encodes time text up to picoseconds as truncated microseconds`() {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
        try {
            timeResultSet().use { resultSet ->
                assertTrue(resultSet.next())
                assertFailsWith<SQLException> { resultSet.getObject(1, LocalTime::class.java) }
            }
            val inline = mutableListOf<BinaryValue>()
            timeResultSet().use { resultSet ->
                runBlocking { timeEncoder().streamInline(resultSet, 32, 65536, 65536, inline::add) }
            }
            assertTimeVectors(inline.single().copyBytes())
            val artifact = mutableListOf<BinaryValue>()
            timeResultSet().use { resultSet ->
                runBlocking { timeEncoder().stream(resultSet, 32uL, 32, 65536, 65536, artifact::add) }
            }
            assertTimeVectors(artifact.map(BinaryValue::copyBytes).reduce(ByteArray::plus))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `time text outside the exact server form fails explicitly`() {
        listOf("1:02:03", "01:02:03.", "01:02:03.1234567890123", "24:00:00", "01:02:03 +09:00").forEach { text ->
            InMemoryTrinoResultSet(listOf(timeColumn("value", 12)), listOf(listOf(text))).use { resultSet ->
                assertTrue(resultSet.next())
                val failure = assertFailsWith<SQLDataException> { TrinoJdbcResultValueReader.readTime(resultSet, 1) }
                assertEquals("22007", failure.sqlState)
            }
        }
    }

    private fun timeEncoder(): JdbcArrowEncoder =
        JdbcArrowEncoder(32, JdbcDecimalPhysicalEncoding.NATIVE_DECIMAL, TrinoJdbcResultValueReader)

    private fun assertTimeVectors(arrowFile: ByteArray) {
        RootAllocator().use { allocator ->
            ArrowFileReader(SeekableReadChannel(ByteArrayReadableSeekableByteChannel(arrowFile)), allocator).use { reader ->
                assertTrue(reader.loadNextBatch())
                val root = reader.vectorSchemaRoot
                assertEquals(2, root.rowCount)
                val picoseconds = assertIs<TimeMicroVector>(root.getVector("time12"))
                val milliseconds = assertIs<TimeMicroVector>(root.getVector("time3"))
                val seconds = assertIs<TimeMicroVector>(root.getVector("time0"))
                assertEquals(86_399_999_999L, picoseconds.get(0))
                assertEquals(45_296_789_000L, milliseconds.get(0))
                assertEquals(3_723_000_000L, seconds.get(0))
                assertTrue(picoseconds.isNull(1))
                assertTrue(milliseconds.isNull(1))
                assertTrue(seconds.isNull(1))
            }
        }
    }

    private fun timeResultSet(): InMemoryTrinoResultSet = InMemoryTrinoResultSet(
        listOf(timeColumn("time12", 12), timeColumn("time3", 3), timeColumn("time0", 0)),
        listOf(listOf("23:59:59.999999999999", "12:34:56.789", "01:02:03"), listOf(null, null, null)),
    )

    private fun timeColumn(name: String, precision: Long): Column =
        Column(name, "time($precision)", ClientTypeSignature("time", listOf(ClientTypeSignatureParameter.ofLong(precision))))

    private fun resultSet(): InMemoryTrinoResultSet = InMemoryTrinoResultSet(
        listOf(Column("value", "timestamp(6) with time zone", ClientTypeSignature(
            "timestamp with time zone", listOf(ClientTypeSignatureParameter.ofLong(6)),
        ))),
        listOf(listOf("1969-12-31 23:59:59.123456 UTC"), listOf("2024-01-02 03:04:05.123456 +09:00"), listOf(null)),
    )
}
