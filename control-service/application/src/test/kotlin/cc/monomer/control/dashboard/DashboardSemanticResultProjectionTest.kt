package cc.monomer.control.dashboard

import cc.monomer.dashboard.application.DashboardResultReadPort
import cc.monomer.semantic.api.MetricDisplay
import cc.monomer.semantic.api.MetricDisplayCurrency
import cc.monomer.semantic.api.SemanticOutputColumnDisplay
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DashboardSemanticResultProjectionTest {
    private val keys = listOf("dimension.orders.status", "dimension.shipments.status", "metric.status")
    private val payload = DashboardResultReadPort.DashboardResultPayload(
        columns = listOf(
            DashboardResultReadPort.Column("__monomer_result_0", "TEXT", true, display = null),
            DashboardResultReadPort.Column("__monomer_result_1", "TEXT", true, display = null),
            DashboardResultReadPort.Column("__monomer_result_2", "DECIMAL", true, display = null),
        ),
        rows = listOf(listOf(JsonPrimitive("open"), JsonNull, JsonPrimitive("12345678901234567890.12345678901234567"))),
    )

    @Test
    fun `canonical projection preserves order type precision and null`() {
        val result = payload.withSemanticColumns(keys, outputColumnDisplays = null)
        assertEquals(keys, result.columns.map { it.name })
        assertEquals(payload.columns.map { it.logicalType to it.nullable }, result.columns.map { it.logicalType to it.nullable })
        assertEquals(payload.rows, result.rows)
    }

    @Test
    fun `wrong reordered missing and old artifact columns fail closed`() {
        val invalidColumns = listOf(
            payload.columns.reversed(),
            payload.columns.map { it.copy(name = "old_${it.name}") },
            payload.columns.dropLast(1),
        )
        invalidColumns.forEach { columns ->
            val invalid = DashboardResultReadPort.DashboardResultPayload(columns, emptyList())
            assertFailsWith<DashboardResultUnavailableException> { invalid.withSemanticColumns(keys, outputColumnDisplays = null) }
        }
        assertFailsWith<DashboardResultUnavailableException> {
            payload.withSemanticColumns(listOf("status", "status", "status"), outputColumnDisplays = null)
        }
    }

    @Test
    fun `stored display joins only a complete ordered canonical key match`() {
        val display = MetricDisplay.Currency(2, MetricDisplayCurrency.USD)
        val stored = keys.mapIndexed { index, key -> SemanticOutputColumnDisplay(cc.monomer.semantic.api.SemanticResultColumnKey(key), if (index == 2) display else null) }
        val result = payload.withSemanticColumns(keys, stored)
        assertEquals(listOf(null, null, display), result.columns.map { it.display })
        assertEquals(payload.rows, result.rows)
        listOf(stored.reversed(), stored.dropLast(1), stored.map { it.copy(key = cc.monomer.semantic.api.SemanticResultColumnKey("metric.other")) }).forEach { mismatched ->
            assertEquals(listOf(null, null, null), payload.withSemanticColumns(keys, mismatched).columns.map { it.display })
        }
        val sql = payload.copy(columns = payload.columns.mapIndexed { index, column -> column.copy(name = keys[index], display = display) })
        assertEquals(listOf(null, null, null), sql.withQueryColumns("SQL", keys, stored).columns.map { it.display })
    }
    @Test
    fun `recorded SQL type preserves declared names while mismatches fail closed`() {
        val sqlPayload = payload.copy(columns = payload.columns.mapIndexed { index, column -> column.copy(name = "field_$index") })
        val names = sqlPayload.columns.map { it.name }
        assertEquals(sqlPayload, sqlPayload.withQueryColumns("SQL", names, outputColumnDisplays = null))
        assertFailsWith<DashboardResultUnavailableException> { sqlPayload.withQueryColumns("SQL", names.reversed(), outputColumnDisplays = null) }
        assertFailsWith<DashboardResultUnavailableException> { sqlPayload.withQueryColumns("SEMANTIC", names, outputColumnDisplays = null) }
        assertFailsWith<DashboardResultUnavailableException> { sqlPayload.withQueryColumns("UNKNOWN", names, outputColumnDisplays = null) }
        assertEquals(keys, payload.withQueryColumns("SEMANTIC", keys, outputColumnDisplays = null).columns.map { it.name })
    }

}
