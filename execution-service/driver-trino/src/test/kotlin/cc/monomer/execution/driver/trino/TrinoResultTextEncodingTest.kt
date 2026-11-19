package cc.monomer.execution.driver.trino

import cc.monomer.execution.arrow.JdbcArrowEncoder
import cc.monomer.execution.arrow.JdbcDecimalPhysicalEncoding
import cc.monomer.execution.driver.spi.BinaryValue
import cc.monomer.execution.driver.spi.UnsupportedResultTypeException
import com.fasterxml.jackson.databind.ObjectMapper
import io.trino.jdbc.InMemoryTrinoResultSet
import io.trino.jdbc.`$internal`.client.ClientTypeSignature
import io.trino.jdbc.`$internal`.client.ClientTypeSignatureParameter
import io.trino.jdbc.`$internal`.client.Column
import io.trino.jdbc.`$internal`.client.NamedClientTypeSignature
import io.trino.jdbc.`$internal`.client.Row
import io.trino.jdbc.`$internal`.client.RowFieldName
import java.lang.reflect.Proxy
import java.sql.ResultSet
import java.sql.Types
import java.util.Optional
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.ipc.ArrowFileReader
import org.apache.arrow.vector.ipc.SeekableReadChannel
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel

class TrinoResultTextEncodingTest {
    @Test
    fun `time with time zone preserves picoseconds and source offsets in both delivery modes`() {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kathmandu"))
        try {
            val signature = type("time with time zone", ClientTypeSignatureParameter.ofLong(12))
            val columns = listOf(column("value", signature))
            val values = listOf("12:34:56.123456789012 +09:00", "00:00:00.000001 -03:30", "23:59:59.999999 +00:00")
            listOf(false, true).forEach { inline ->
                InMemoryTrinoResultSet(columns, values.map { listOf(it) } + listOf(listOf(null))).use { resultSet ->
                    assertEquals(Types.TIME_WITH_TIMEZONE, resultSet.metaData.getColumnType(1))
                    assertText(encode(resultSet, inline), mapOf("value" to values.map { it.replace(" ", "") }))
                }
            }
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun `scalar extension types retain server spelling and unknown is nullable text`() {
        val values = linkedMapOf(
            "json" to "{\"name\":\"escaped \\\" text\",\"number\":12345678901234567890}",
            "uuid" to "123e4567-e89b-12d3-a456-426614174000",
            "ipaddress" to "2001:db8::1234",
            "interval day to second" to "-2 03:04:05.006",
            "interval year to month" to "-123-11",
        )
        val columns = values.keys.mapIndexed { index, name -> column("c$index", type(name)) } + column("unknown_value", type("unknown"))
        listOf(false, true).forEach { inline ->
            InMemoryTrinoResultSet(columns, listOf(values.values.toList() + null, List(columns.size) { null })).use { resultSet ->
                assertEquals(Types.NULL, resultSet.metaData.getColumnType(columns.size))
                assertTrue((1 until columns.size).all { resultSet.metaData.getColumnType(it) == Types.JAVA_OBJECT })
                val expected = values.values.mapIndexed { index, value -> "c$index" to listOf(value) }.toMap() +
                    ("unknown_value" to listOf<String?>(null))
                assertText(encode(resultSet, inline), expected)
            }
        }
    }

    @Test
    fun `complex text is JSON preserving map keys row field order escapes and nested raw time precision`() {
        val varchar = type("varchar", ClientTypeSignatureParameter.ofLong(100))
        val time = type("time", ClientTypeSignatureParameter.ofLong(12))
        val rowType = type("row", named("duplicate", varchar), named("duplicate", time), named(null, arrayType(varchar)))
        val row = Row.builder().addField("duplicate", "quote \" and slash \\ and newline\n")
            .addField("duplicate", "12:34:56.123456789012")
            .addUnnamedField(listOf("first", null, "last")).build()
        val mapType = mapType(type("integer"), rowType)
        val map = linkedMapOf(7 to row)
        val columns = listOf(column("map_value", mapType), column("row_value", rowType), column("array_value", arrayType(mapType)))
        val mapper = ObjectMapper()
        val expectedRow = listOf(
            linkedMapOf("name" to "duplicate", "value" to "quote \" and slash \\ and newline\n"),
            linkedMapOf("name" to "duplicate", "value" to "12:34:56.123456789012"),
            linkedMapOf("name" to null, "value" to listOf("first", null, "last")),
        )
        val expectedMap = listOf(listOf(7, expectedRow))
        val expected = mapOf(
            "map_value" to listOf(mapper.writeValueAsString(expectedMap)),
            "row_value" to listOf(mapper.writeValueAsString(expectedRow)),
            "array_value" to listOf(mapper.writeValueAsString(listOf(expectedMap))),
        )
        listOf(false, true).forEach { inline ->
            InMemoryTrinoResultSet(columns, listOf(listOf(map, row, listOf(map)), listOf(null, null, null))).use { resultSet ->
                assertText(encode(resultSet, inline), expected)
            }
        }
    }

    @Test
    fun `complex map key schemas fail before reading rows including nested and empty results`() {
        val varchar = type("varchar", ClientTypeSignatureParameter.ofLong(100))
        val integer = type("integer")
        val arrayKeyMap = mapType(arrayType(varchar), varchar)
        val rowKeyMap = mapType(type("row", named("key", integer)), varchar)
        val unsupportedTypes = listOf(
            arrayKeyMap,
            rowKeyMap,
            mapType(mapType(integer, varchar), varchar),
            arrayType(arrayKeyMap),
            type("row", named("nested", rowKeyMap)),
            mapType(integer, arrayType(arrayKeyMap)),
        )
        unsupportedTypes.forEach { signature ->
            listOf(false, true).forEach { inline ->
                listOf(emptyList(), listOf(listOf(null, null))).forEach { rows ->
                    val columns = listOf(column(" value ", integer), column("value", signature))
                    InMemoryTrinoResultSet(columns, rows).use { vendorResultSet ->
                        var nextCalls = 0
                        val resultSet = Proxy.newProxyInstance(
                            ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java),
                        ) { _, method, arguments ->
                            if (method.name == "next") {
                                nextCalls++
                                error("unsupported metadata must fail before reading a vendor row")
                            }
                            method.invoke(vendorResultSet, *arguments.orEmpty())
                        } as ResultSet
                        val failure = assertFailsWith<UnsupportedResultTypeException> { encode(resultSet, inline) }
                        assertEquals(
                            "Column 'value_2' has unsupported result type '${vendorResultSet.metaData.getColumnTypeName(2)}'",
                            failure.message,
                        )
                        assertEquals(0, nextCalls)
                    }
                }
            }
        }
    }

    private fun encode(resultSet: ResultSet, inline: Boolean): ByteArray {
        val payloads = mutableListOf<BinaryValue>()
        runBlocking {
            val encoder = JdbcArrowEncoder(32, JdbcDecimalPhysicalEncoding.NATIVE_DECIMAL, TrinoJdbcResultValueReader)
            if (inline) encoder.streamInline(resultSet, 32, 65536, 65536, payloads::add)
            else encoder.stream(resultSet, 32uL, 32, 65536, 65536, payloads::add)
        }
        return payloads.map(BinaryValue::copyBytes).reduce(ByteArray::plus)
    }

    private fun assertText(bytes: ByteArray, expected: Map<String, List<String?>>) {
        RootAllocator().use { allocator ->
            ArrowFileReader(SeekableReadChannel(ByteArrayReadableSeekableByteChannel(bytes)), allocator).use { reader ->
                assertTrue(reader.loadNextBatch())
                expected.forEach { (name, values) ->
                    val vector = assertIs<VarCharVector>(reader.vectorSchemaRoot.getVector(name))
                    assertEquals(ArrowType.Utf8(), vector.field.type)
                    assertTrue(vector.field.isNullable)
                    values.forEachIndexed { index, value ->
                        assertEquals(value, vector.get(index)?.toString(Charsets.UTF_8), "$name row $index")
                    }
                    assertTrue(vector.isNull(values.size))
                }
            }
        }
    }

    private fun column(name: String, signature: ClientTypeSignature): Column = Column(name, signature.toString(), signature)

    private fun type(name: String, vararg arguments: ClientTypeSignatureParameter): ClientTypeSignature =
        ClientTypeSignature(name, arguments.toList())

    private fun arrayType(element: ClientTypeSignature): ClientTypeSignature = type("array", ClientTypeSignatureParameter.ofType(element))

    private fun mapType(key: ClientTypeSignature, value: ClientTypeSignature): ClientTypeSignature =
        type("map", ClientTypeSignatureParameter.ofType(key), ClientTypeSignatureParameter.ofType(value))

    private fun named(name: String?, signature: ClientTypeSignature): ClientTypeSignatureParameter =
        ClientTypeSignatureParameter.ofNamedType(NamedClientTypeSignature(Optional.ofNullable(name?.let(::RowFieldName)), signature))
}
