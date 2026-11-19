package cc.monomer.execution.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResultPreviewTypesTest {
    @Test
    fun `datetime requires local calendar fields and exactly microsecond precision`() {
        val column = ResultColumn(1, "local_time", ResultLogicalType.DATETIME, true)
        for (value in listOf("1969-12-31T23:59:59.999999", "1970-01-01T00:00:00.000000", "2024-02-29T12:34:56.123456", "2024-12-31T23:59:59.999999", "2025-01-01T00:00:00.000001")) {
            val cell = ResultPreviewCell.LocalDateTimeValue(value)
            ResultPreviewCanonicalizer.requireCellMatchesColumn(column, cell)
            assertEquals(value, ResultPreviewCanonicalizer.canonicalText(cell))
            assertFailsWith<IllegalArgumentException> {
                ResultPreviewCanonicalizer.requireCellMatchesColumn(column.copy(logicalType = ResultLogicalType.TIMESTAMP), cell)
            }
        }
        ResultPreviewCanonicalizer.requireCellMatchesColumn(column, ResultPreviewCell.Null)
        for (value in listOf("2024-02-30T00:00:00.000000", "2024-02-29T24:00:00.000000", "2024-02-29T12:34:56.123456Z", "2024-02-29T12:34:56.123456+09:00", "2024-02-29T12:34:56", "2024-02-29T12:34:56.1234567")) {
            assertFailsWith<IllegalArgumentException> { ResultPreviewCell.LocalDateTimeValue(value) }
        }
    }
}
