package cc.monomer.execution.driver.snowflake

import cc.monomer.execution.arrow.JdbcArrowEncoder
import cc.monomer.execution.arrow.JdbcDecimalPhysicalEncoding
import cc.monomer.execution.driver.spi.BinaryValue
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.snowflake.client.core.SFResultSet
import net.snowflake.client.core.SFSession
import net.snowflake.client.core.SFStatement
import net.snowflake.client.jdbc.SnowflakeResultSetSerializableV1
import net.snowflake.client.jdbc.SnowflakeResultSetV1
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.ObjectMapper
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.TimeMicroVector
import org.apache.arrow.vector.DateDayVector
import org.apache.arrow.vector.TimeStampMicroTZVector
import org.apache.arrow.vector.TimeStampMicroVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowFileReader
import org.apache.arrow.vector.ipc.SeekableReadChannel
import org.apache.arrow.vector.types.TimeUnit
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel

class SnowflakeTemporalEncodingTest {
    @Test
    fun `encodes vendor date millisecond time NTZ LTZ and TZ with non UTC timezones`() = withNonUtcTimezone {
        val wallclocks = listOf("1969-12-31T23:59:59.123456", "2024-03-10T02:30:00.654321")
        val instants = listOf("1969-12-31T23:59:59.123456Z", "2024-01-02T03:04:05.654321Z")
        val times = listOf("86399.123000000", "11045.654321000")
        val days = listOf(LocalDate.parse("1969-12-31"), LocalDate.parse("2024-01-02"))
        val rows = wallclocks.indices.map { index ->
            listOf(
                days[index].toEpochDay().toString(),
                epochText(LocalDateTime.parse(wallclocks[index]).toInstant(ZoneOffset.UTC)),
                epochText(Instant.parse(instants[index])),
                epochText(Instant.parse(instants[index])) + " 1980",
                times[index],
            )
        } + listOf(List(5) { null })
        listOf(false, true).forEach { sessionTimezone ->
            listOf(false, true).forEach { inline ->
                vendorResultSet(listOf("date", "timestamp_ntz", "timestamp_ltz", "timestamp_tz", "time"), rows, sessionTimezone).use { resultSet ->
                    val payloads = mutableListOf<BinaryValue>()
                    runBlocking {
                        val encoder = JdbcArrowEncoder(32, JdbcDecimalPhysicalEncoding.NATIVE_DECIMAL, SnowflakeJdbcResultValueReader)
                        if (inline) {
                            encoder.streamInline(resultSet, 32, 65536, 65536, payloads::add)
                        } else {
                            encoder.stream(resultSet, 32uL, 32, 65536, 65536, payloads::add)
                        }
                    }
                    val bytes = payloads.flatMap { it.copyBytes().toList() }.toByteArray()
                    readArrow(bytes) { root ->
                        val time = assertIs<TimeMicroVector>(root.getVector("c4"))
                        assertEquals(listOf(86_399_123_000L, 11_045_654_000L), (0..1).map(time::get))
                        val date = assertIs<DateDayVector>(root.getVector("c0"))
                        val ntz = assertIs<TimeStampMicroVector>(root.getVector("c1"))
                        assertNull((ntz.field.type as ArrowType.Timestamp).timezone)
                        assertEquals(TimeUnit.MICROSECOND, (ntz.field.type as ArrowType.Timestamp).unit)
                        val ltz = assertIs<TimeStampMicroTZVector>(root.getVector("c2"))
                        val tz = assertIs<TimeStampMicroTZVector>(root.getVector("c3"))
                        listOf(ltz, tz).forEach { assertEquals("UTC", (it.field.type as ArrowType.Timestamp).timezone) }
                        for (index in wallclocks.indices) {
                            assertEquals(days[index].toEpochDay().toInt(), date.get(index))
                            assertEquals(micros(LocalDateTime.parse(wallclocks[index]).toInstant(ZoneOffset.UTC)), ntz.get(index))
                            assertEquals(micros(Instant.parse(instants[index])), ltz.get(index))
                            assertEquals(micros(Instant.parse(instants[index])), tz.get(index))
                        }
                        root.fieldVectors.forEach { assertTrue(it.isNull(2)) }
                    }
                }
            }
        }
    }

    @Test
    fun `pinned JDBC rejects java time getters and cannot expose TIME microseconds at defaults`() = withNonUtcTimezone {
        vendorResultSet(listOf("date", "timestamp_ntz", "time"), listOf(listOf("-1", "-0.876544000", "86399.123456000"))).use { resultSet ->
            assertTrue(resultSet.next())
            assertFailsWith<SQLException> { resultSet.getObject(1, LocalDate::class.java) }
            assertFailsWith<SQLException> { resultSet.getObject(2, LocalDateTime::class.java) }
            assertFailsWith<SQLException> { resultSet.getObject(3, LocalTime::class.java) }
            assertEquals(86_399_123L, resultSet.getTime(3).time)
            assertEquals(86_399_123L, (resultSet.getObject(3) as java.sql.Time).time)
            assertEquals(86_399_123L, resultSet.getTimestamp(3).time)
            assertEquals(0, resultSet.getTimestamp(3).nanos % 1_000_000)
            assertEquals("23:59:59", resultSet.getString(3))
        }

    }

    private fun vendorResultSet(types: List<String>, rows: List<List<String?>>, useSessionTimezone: Boolean = false): ResultSet {
        val mapper = ObjectMapper()
        val response = mapper.createObjectNode().put("success", true)
        val data = response.putObject("data")
        data.put("queryId", "temporal-unit-test").put("statementTypeId", 4096).put("version", 1)
        val rowtype = data.putArray("rowtype")
        types.forEachIndexed { index, type ->
            rowtype.addObject().put("name", "c$index").put("type", type).put("scale", 9).put("nullable", true)
        }
        data.set<net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.JsonNode>("rowset", mapper.valueToTree(rows))
        data.putArray("parameters").addObject().put("name", "TIMEZONE").put("value", "America/Los_Angeles")
        val session = SFSession().apply { addProperty("disableQueryContextCache", true); setUseSessionTimezone(useSessionTimezone) }
        val serializable = SnowflakeResultSetSerializableV1.createWithChunksPrefetchDisabled(response, session, SFStatement(session))
        assertEquals(useSessionTimezone, serializable.useSessionTimezone)
        assertEquals(false, serializable.treatNTZAsUTC)
        val telemetry = object : net.snowflake.client.jdbc.telemetry.Telemetry {
            override fun addLogToBatch(log: net.snowflake.client.jdbc.telemetry.TelemetryData) = Unit
            override fun close() = Unit
            override fun sendBatchAsync(): java.util.concurrent.Future<Boolean> = java.util.concurrent.CompletableFuture.completedFuture(true)
            override fun postProcess(queryId: String, sqlState: String, vendorCode: Int, ex: Throwable) = Unit
        }
        val core = SFResultSet(serializable, session, telemetry, false)
        return SnowflakeResultSetV1(core, serializable)
    }

    private fun readArrow(bytes: ByteArray, verify: (VectorSchemaRoot) -> Unit) {
        RootAllocator().use { allocator ->
            ArrowFileReader(SeekableReadChannel(ByteArrayReadableSeekableByteChannel(bytes)), allocator).use { reader ->
                assertTrue(reader.loadNextBatch())
                verify(reader.vectorSchemaRoot)
            }
        }
    }

    private fun epochText(instant: Instant): String =
        BigDecimal.valueOf(instant.epochSecond).add(BigDecimal.valueOf(instant.nano.toLong(), 9)).toPlainString()

    private fun micros(instant: Instant): Long = instant.epochSecond * 1_000_000L + instant.nano / 1_000L

    private fun withNonUtcTimezone(block: () -> Unit) {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        try {
            block()
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
