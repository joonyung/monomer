package cc.monomer.execution.arrow

import cc.monomer.execution.driver.spi.ResultLogicalType
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals

class JdbcResultColumnReaderTest {
    @Test
    fun `vendor-specific and array values are preserved as text`() {
        assertEquals(
            ResultLogicalType.TEXT,
            JdbcResultColumnReader.logicalTypeFor(Types.OTHER, "jsonb"),
        )
        assertEquals(
            ResultLogicalType.TEXT,
            JdbcResultColumnReader.logicalTypeFor(Types.ARRAY, "_text"),
        )
    }

    @Test
    fun `postgresql timestamptz is distinguished from timestamp despite shared JDBC type`() {
        assertEquals(
            true,
            JdbcResultColumnReader.isTimestampWithTimeZone(Types.TIMESTAMP, "timestamptz"),
        )
        assertEquals(
            true,
            JdbcResultColumnReader.isTimestampWithTimeZone(
                Types.TIMESTAMP,
                "timestamp with time zone",
            ),
        )
        assertEquals(
            false,
            JdbcResultColumnReader.isTimestampWithTimeZone(Types.TIMESTAMP, "timestamp"),
        )
    }
}
