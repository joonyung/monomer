package cc.monomer.semantic

import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticGroupBy
import cc.monomer.semantic.api.SemanticResultColumnKey
import cc.monomer.semantic.api.SemanticResultColumns
import cc.monomer.semantic.api.SemanticTimeGranularity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SemanticResultColumnsTest {
    @Test
    fun `semantic identity preserves kind model and explicit grain independently of projection order`() {
        val groupBys = listOf(
            dimension("orders", "status", null),
            dimension("shipments", "status", null),
            dimension("orders", "created_at", SemanticTimeGranularity.DAY),
            dimension("orders", "created_at", SemanticTimeGranularity.MONTH),
            SemanticGroupBy.Entity(name("orders"), name("customer")),
            SemanticGroupBy.MetricTime(SemanticTimeGranularity.DAY),
        )
        val metrics = listOf(name("order_count"), name("status"), name("metric_time"))
        val projection = SemanticResultColumns.projection(metrics, groupBys)
        assertEquals(
            listOf(
                "dimension.orders.status", "dimension.shipments.status", "dimension.orders.created_at.day",
                "dimension.orders.created_at.month", "entity.orders.customer", "metric_time.day",
                "metric.order_count", "metric.status", "metric.metric_time",
            ),
            projection.map { it.key.value },
        )
        val reordered = SemanticResultColumns.projection(metrics.reversed(), groupBys.reversed())
        assertEquals(projection.map { it.key }.toSet(), reordered.map { it.key }.toSet())
        assertEquals(projection.map { it.sqlName }, reordered.map { it.sqlName })
        assertEquals(projection, SemanticResultColumns.fromKeys(projection.map { it.key }))
    }

    @Test
    fun `JSON selector projection keeps omitted and null grain identical without guessing a default`() {
        val metrics = Json.parseToJsonElement("[\"order_count\"]").jsonArray
        val omitted = Json.parseToJsonElement(
            """[{"kind":"DIMENSION","semanticModelName":"orders","name":"status"}]""",
        ).jsonArray
        val explicitNull = Json.parseToJsonElement(
            """[{"kind":"DIMENSION","semanticModelName":"orders","name":"status","granularity":null}]""",
        ).jsonArray
        assertEquals(SemanticResultColumns.keys(metrics, omitted), SemanticResultColumns.keys(metrics, explicitNull))
        assertEquals(listOf("dimension.orders.status", "metric.order_count"),
            SemanticResultColumns.keys(metrics, omitted).map { it.value })
        assertFailsWith<IllegalArgumentException> {
            SemanticResultColumns.keys(metrics, Json.parseToJsonElement("[${omitted.single()},${explicitNull.single()}]").jsonArray)
        }
    }

    @Test
    fun `duplicate selectors and malformed stored result keys are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SemanticResultColumns.projection(listOf(name("order_count"), name("order_count")), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            SemanticResultColumns.projection(emptyList(), List(2) { dimension("orders", "status", null) })
        }
        listOf("order_count", "metric.order_count.day", "dimension.orders.status.weekly", "metric_time").forEach {
            assertFailsWith<IllegalArgumentException> { SemanticResultColumnKey(it) }
        }
    }

    @Test
    fun `long logical keys keep bounded physical aliases`() {
        val projection = SemanticResultColumns.projection(
            listOf(name("m".repeat(300))), listOf(dimension("o".repeat(300), "s".repeat(300), null)),
        )
        assertEquals(611, projection.first().key.value.length)
        assertEquals(listOf("__monomer_result_0", "__monomer_result_1"), projection.map { it.sqlName })
    }

    private fun name(value: String) = SemanticElementName(value)
    private fun dimension(model: String, element: String, grain: SemanticTimeGranularity?) =
        SemanticGroupBy.Dimension(name(model), name(element), grain)
}
