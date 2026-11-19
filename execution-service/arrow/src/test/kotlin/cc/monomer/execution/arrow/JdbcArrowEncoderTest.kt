package cc.monomer.execution.arrow

import cc.monomer.execution.driver.spi.BinaryValue
import cc.monomer.execution.driver.spi.ResultLimitExceededException
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLFeatureNotSupportedException
import java.sql.Types
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.DecimalVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.ipc.ArrowFileReader
import org.apache.arrow.vector.ipc.SeekableReadChannel
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel

class JdbcArrowEncoderTest {
    @Test
    fun `canonicalizes exponent negative scale and special numeric tokens as tagged utf8`() {
        val payload =
            encodeSingleInlinePayload(
                encoder =
                    JdbcArrowEncoder(
                        batchSize = 32,
                        decimalPhysicalEncoding = JdbcDecimalPhysicalEncoding.UTF8_CANONICAL_DECIMAL,
                        valueReader = JavaTimeJdbcResultValueReader,
                    ),
                resultSet =
                    decimalResultSet(
                        values =
                            listOf(
                                "1.2300E+3",
                                "1E-3",
                                "123E+2",
                                "NaN",
                                "Infinity",
                                "-Infinity",
                                null,
                            ),
                        precision = 0,
                        scale = 0,
                    ),
            )
        try {
            val allocator = RootAllocator()
            try {
                ArrowFileReader(
                    SeekableReadChannel(ByteArrayReadableSeekableByteChannel(payload.copyBytes())),
                    allocator,
                ).use { reader ->
                    reader.loadRecordBatch(reader.recordBlocks.single())
                    val root = reader.vectorSchemaRoot
                    val vector = root.getVector("value") as VarCharVector
                    assertEquals(
                        listOf("1230.0", "0.001", "12300", "NaN", "Infinity", "-Infinity", null),
                        (0 until vector.valueCount).map { index ->
                            if (vector.isNull(index)) null else vector.text(index)
                        },
                    )
                    assertIs<ArrowType.Utf8>(vector.field.type)
                    assertEquals(
                        "DECIMAL",
                        vector.field.metadata[TabularArrowEncoder.LOGICAL_TYPE_METADATA_KEY],
                    )
                    assertEquals(
                        "UTF8_CANONICAL_DECIMAL",
                        vector.field.metadata[TabularArrowEncoder.PHYSICAL_ENCODING_METADATA_KEY],
                    )
                }
            } finally {
                allocator.close()
            }
        } finally {
            payload.copyBytes().fill(0)
        }
    }

    @Test
    fun `rejects unsupported special numeric tokens`() {
        assertFailsWith<NumberFormatException> {
            encodeSingleInlinePayload(
                encoder =
                    JdbcArrowEncoder(
                        batchSize = 1,
                        decimalPhysicalEncoding = JdbcDecimalPhysicalEncoding.UTF8_CANONICAL_DECIMAL,
                        valueReader = JavaTimeJdbcResultValueReader,
                    ),
                resultSet = decimalResultSet(listOf("+Infinity"), precision = 0, scale = 0),
            )
        }
    }

    @Test
    fun `applies maximum cell bytes after canonicalization`() {
        val encoder =
            JdbcArrowEncoder(
                batchSize = 1,
                decimalPhysicalEncoding = JdbcDecimalPhysicalEncoding.UTF8_CANONICAL_DECIMAL,
                valueReader = JavaTimeJdbcResultValueReader,
            )

        assertFailsWith<ResultLimitExceededException> {
            runSuspend {
                encoder.streamInline(
                    resultSet = decimalResultSet(listOf("1E+3"), precision = 0, scale = 0),
                    maximumRecordBatchRows = 1,
                    maximumRecordBatchBytes = 64 * 1024,
                    maximumCellBytes = 3,
                    emit = {},
                )
            }
        }
    }

    @Test
    fun `keeps native decimal encoding unchanged`() {
        val payload =
            encodeSingleInlinePayload(
                encoder =
                    JdbcArrowEncoder(
                        batchSize = 1,
                        decimalPhysicalEncoding = JdbcDecimalPhysicalEncoding.NATIVE_DECIMAL,
                        valueReader = JavaTimeJdbcResultValueReader,
                    ),
                resultSet = decimalResultSet(listOf(BigDecimal("12.34")), precision = 10, scale = 2),
            )
        try {
            val allocator = RootAllocator()
            try {
                ArrowFileReader(
                    SeekableReadChannel(ByteArrayReadableSeekableByteChannel(payload.copyBytes())),
                    allocator,
                ).use { reader ->
                    reader.loadRecordBatch(reader.recordBlocks.single())
                    val vector = reader.vectorSchemaRoot.getVector("value") as DecimalVector
                    assertEquals(BigDecimal("12.34"), vector.getObject(0))
                    assertIs<ArrowType.Decimal>(vector.field.type)
                    assertTrue(vector.field.metadata.isEmpty())
                }
            } finally {
                allocator.close()
            }
        } finally {
            payload.copyBytes().fill(0)
        }
    }

    @Test
    fun `fails instead of wrapping dates outside the Arrow day range`() {
        listOf(LocalDate.MAX, LocalDate.MIN).forEach { infiniteDate ->
            assertFailsWith<ArithmeticException> {
                encodeSingleInlinePayload(
                    encoder =
                        JdbcArrowEncoder(
                            batchSize = 32,
                            decimalPhysicalEncoding = JdbcDecimalPhysicalEncoding.NATIVE_DECIMAL,
                            valueReader = JavaTimeJdbcResultValueReader,
                        ),
                    resultSet =
                        singleColumnResultSet(
                            jdbcType = Types.DATE,
                            jdbcTypeName = "date",
                            values = listOf(infiniteDate),
                        ) { methodName, arguments, value ->
                            if (methodName == "getObject" && arguments.toList() == listOf(1, LocalDate::class.java)) {
                                value
                            } else {
                                throw SQLFeatureNotSupportedException(methodName)
                            }
                        },
                )
            }
        }
    }

    private fun singleColumnResultSet(
        jdbcType: Int,
        jdbcTypeName: String,
        values: List<Any?>,
        readCell: (methodName: String, arguments: Array<out Any?>, value: Any?) -> Any?,
    ): ResultSet {
        val rowIndex = AtomicInteger(-1)
        var lastWasNull = false
        val metadata =
            Proxy.newProxyInstance(
                ResultSetMetaData::class.java.classLoader,
                arrayOf(ResultSetMetaData::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getColumnCount" -> 1
                    "getColumnLabel", "getColumnName" -> "value"
                    "getColumnType" -> jdbcType
                    "getColumnTypeName" -> jdbcTypeName
                    "isNullable" -> ResultSetMetaData.columnNullable
                    else -> defaultValue(method.returnType)
                }
            } as ResultSetMetaData
        return Proxy.newProxyInstance(
            ResultSet::class.java.classLoader,
            arrayOf(ResultSet::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "next" -> {
                    val next = rowIndex.get() + 1
                    if (next < values.size) {
                        rowIndex.set(next)
                        true
                    } else {
                        false
                    }
                }
                "getMetaData" -> metadata
                "wasNull" -> lastWasNull
                "close" -> null
                "isClosed" -> false
                "toString" -> "SingleColumnResultSet"
                else -> {
                    val value = values[rowIndex.get()]
                    lastWasNull = value == null
                    readCell(method.name, arguments.orEmpty(), value)
                }
            }
        } as ResultSet
    }

    private fun encodeSingleInlinePayload(
        encoder: JdbcArrowEncoder,
        resultSet: ResultSet,
    ): BinaryValue {
        val payloads = mutableListOf<BinaryValue>()
        runSuspend {
            encoder.streamInline(
                resultSet = resultSet,
                maximumRecordBatchRows = 32,
                maximumRecordBatchBytes = 1024 * 1024,
                maximumCellBytes = 1024 * 1024,
                emit = { payload -> payloads += payload },
            )
        }
        return payloads.single()
    }

    private fun decimalResultSet(
        values: List<Any?>,
        precision: Int,
        scale: Int,
    ): ResultSet {
        val rowIndex = AtomicInteger(-1)
        var lastWasNull = false
        val metadata =
            Proxy.newProxyInstance(
                ResultSetMetaData::class.java.classLoader,
                arrayOf(ResultSetMetaData::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getColumnCount" -> 1
                    "getColumnLabel", "getColumnName" -> "value"
                    "getColumnType" -> Types.NUMERIC
                    "getColumnTypeName" -> "numeric"
                    "getPrecision" -> precision
                    "getScale" -> scale
                    "isNullable" -> ResultSetMetaData.columnNullable
                    else -> defaultValue(method.returnType)
                }
            } as ResultSetMetaData
        return Proxy.newProxyInstance(
            ResultSet::class.java.classLoader,
            arrayOf(ResultSet::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "next" -> {
                    val next = rowIndex.get() + 1
                    if (next < values.size) {
                        rowIndex.set(next)
                        true
                    } else {
                        false
                    }
                }
                "getString" ->
                    values[rowIndex.get()].let { value ->
                        lastWasNull = value == null
                        value?.toString()
                    }
                "getBigDecimal" ->
                    values[rowIndex.get()].let { value ->
                        lastWasNull = value == null
                        when (value) {
                            null -> null
                            is BigDecimal -> value
                            else -> BigDecimal(value.toString())
                        }
                    }
                "getMetaData" -> metadata
                "wasNull" -> lastWasNull
                "close" -> null
                "isClosed" -> false
                "isWrapperFor" -> false
                "unwrap" -> null
                "toString" -> "DecimalResultSet"
                else -> defaultValue(method.returnType)
            }
        } as ResultSet
    }

    private fun defaultValue(type: Class<*>): Any? =
        when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Double.TYPE -> 0.0
            java.lang.Float.TYPE -> 0.0f
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }

    private fun VarCharVector.text(index: Int): String = String(get(index), Charsets.UTF_8)

    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            },
        )
        return checkNotNull(outcome).getOrThrow()
    }
}
