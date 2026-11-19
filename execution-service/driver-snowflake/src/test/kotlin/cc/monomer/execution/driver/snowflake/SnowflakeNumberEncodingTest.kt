package cc.monomer.execution.driver.snowflake

import cc.monomer.execution.arrow.JdbcArrowEncoder
import cc.monomer.execution.arrow.JdbcDecimalPhysicalEncoding
import cc.monomer.execution.driver.spi.BinaryValue
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.snowflake.client.core.SFResultSet
import net.snowflake.client.core.SFSession
import net.snowflake.client.core.SFStatement
import net.snowflake.client.jdbc.SnowflakeResultSetSerializableV1
import net.snowflake.client.jdbc.SnowflakeResultSetV1
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.JsonNode
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.ObjectMapper
import net.snowflake.client.jdbc.telemetry.Telemetry
import net.snowflake.client.jdbc.telemetry.TelemetryData
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.BigIntVector
import org.apache.arrow.vector.DecimalVector
import org.apache.arrow.vector.ipc.ArrowFileReader
import org.apache.arrow.vector.ipc.SeekableReadChannel
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel

class SnowflakeNumberEncodingTest {
    @Test
    fun `pinned vendor NUMBER scale zero metadata is BIGINT and getLong fails beyond int64`() {
        vendorResultSet().use { resultSet ->
            assertEquals(listOf(19, 38, 18), (1..3).map(resultSet.metaData::getPrecision))
            assertEquals(List(3) { Types.BIGINT }, (1..3).map(resultSet.metaData::getColumnType))
            assertEquals(List(3) { 0 }, (1..3).map(resultSet.metaData::getScale))
            assertTrue(resultSet.next())
            assertFailsWith<SQLException> { resultSet.getLong(1) }
            assertFailsWith<SQLException> { resultSet.getLong(2) }
            assertEquals(BigDecimal(rows[0][0]), resultSet.getBigDecimal(1))
            assertEquals(BigDecimal(rows[0][1]), resultSet.getBigDecimal(2))
            assertEquals(rows[0][2]!!.toLong(), resultSet.getLong(3))
        }
    }

    @Test
    fun `NUMBER precision above eighteen retains positive and negative integers as decimal in both delivery modes`() {
        listOf(false, true).forEach { inline ->
            vendorResultSet().use { resultSet ->
                val payloads = mutableListOf<BinaryValue>()
                runBlocking {
                    val encoder = JdbcArrowEncoder(32, JdbcDecimalPhysicalEncoding.NATIVE_DECIMAL, SnowflakeJdbcResultValueReader)
                    if (inline) encoder.streamInline(resultSet, 32, 65536, 65536, payloads::add)
                    else encoder.stream(resultSet, 32uL, 32, 65536, 65536, payloads::add)
                }
                val bytes = payloads.map(BinaryValue::copyBytes).reduce(ByteArray::plus)
                RootAllocator().use { allocator ->
                    ArrowFileReader(SeekableReadChannel(ByteArrayReadableSeekableByteChannel(bytes)), allocator).use { reader ->
                        assertTrue(reader.loadNextBatch())
                        val root = reader.vectorSchemaRoot
                        val number19 = assertIs<DecimalVector>(root.getVector("number19"))
                        val number38 = assertIs<DecimalVector>(root.getVector("number38"))
                        listOf(number19, number38).forEach { assertEquals(ArrowType.Decimal(38, 0, 128), it.field.type) }
                        val number18 = assertIs<BigIntVector>(root.getVector("number18"))
                        for (index in 0..1) {
                            assertEquals(BigDecimal(rows[index][0]), number19.getObject(index))
                            assertEquals(BigDecimal(rows[index][1]), number38.getObject(index))
                            assertEquals(rows[index][2]!!.toLong(), number18.get(index))
                        }
                        root.fieldVectors.forEach { assertTrue(it.isNull(2)) }
                    }
                }
            }
        }
    }

    private fun vendorResultSet(): ResultSet {
        val mapper = ObjectMapper()
        val response = mapper.createObjectNode().put("success", true)
        val data = response.putObject("data")
        data.put("queryId", "number-unit-test").put("statementTypeId", 4096).put("version", 1)
        val rowtype = data.putArray("rowtype")
        listOf(19, 38, 18).forEach { precision ->
            rowtype.addObject().put("name", "number$precision").put("type", "fixed")
                .put("precision", precision).put("scale", 0).put("nullable", true)
        }
        data.set<JsonNode>("rowset", mapper.valueToTree(rows))
        data.putArray("parameters").addObject().put("name", "JDBC_TREAT_DECIMAL_AS_INT").put("value", true)
        val session = SFSession().apply {
            addProperty("disableQueryContextCache", true)
            setJdbcTreatDecimalAsInt(true)
        }
        val serializable = SnowflakeResultSetSerializableV1.createWithChunksPrefetchDisabled(response, session, SFStatement(session))
        val telemetry = object : Telemetry {
            override fun addLogToBatch(log: TelemetryData) = Unit
            override fun close() = Unit
            override fun sendBatchAsync(): Future<Boolean> = CompletableFuture.completedFuture(true)
            override fun postProcess(queryId: String, sqlState: String, vendorCode: Int, ex: Throwable) = Unit
        }
        return SnowflakeResultSetV1(SFResultSet(serializable, session, telemetry, false), serializable)
    }

    private val rows = listOf(
        listOf("9223372036854775808", "99999999999999999999999999999999999999", "999999999999999999"),
        listOf("-9223372036854775809", "-99999999999999999999999999999999999999", "-999999999999999999"),
        listOf(null, null, null),
    )
}
