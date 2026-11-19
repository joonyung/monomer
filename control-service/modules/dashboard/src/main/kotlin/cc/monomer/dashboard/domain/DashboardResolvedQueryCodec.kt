package cc.monomer.dashboard.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

object DashboardResolvedQueryCodec {
    fun encode(query: DashboardResolvedQuery): JsonObject = when (query) {
        is DashboardResolvedQuery.Semantic -> query.query
        is DashboardResolvedQuery.Sql -> buildJsonObject {
            put("bindings", JsonArray(query.bindings.map { bind -> buildJsonObject {
                put("type", bind.type.name)
                put("value", when (bind) {
                    is DashboardBindValue.Text -> JsonPrimitive(bind.value)
                    is DashboardBindValue.Number -> JsonPrimitive(bind.value)
                    is DashboardBindValue.Boolean -> JsonPrimitive(bind.value)
                    is DashboardBindValue.Date -> JsonPrimitive(bind.value.toString())
                    is DashboardBindValue.Timestamp -> JsonPrimitive(bind.value.toString())
                    is DashboardBindValue.Null -> JsonNull
                })
            } }))
            put("rowLimit", query.rowLimit)
        }
    }

    fun decode(type: DashboardQueryType, node: JsonObject): DashboardResolvedQuery = when (type) {
        DashboardQueryType.SEMANTIC -> DashboardResolvedQuery.Semantic(node)
        DashboardQueryType.SQL -> {
            DashboardJson.exact(node, setOf("bindings", "rowLimit"), "SQL resolved query")
            val binds = DashboardJson.arrayNode(node.getValue("bindings"), "SQL resolved query.bindings").map { raw ->
                val bind = raw.jsonObject
                DashboardJson.exact(bind, setOf("type", "value"), "SQL resolved binding")
                val type = DashboardValueType.valueOf(DashboardJson.text(bind.getValue("type"), "SQL resolved binding.type"))
                val value = bind.getValue("value")
                if (value is JsonNull) DashboardBindValue.Null(type) else when (type) {
                    DashboardValueType.TEXT -> {
                        require(value is JsonPrimitive && value.isString) { "SQL resolved TEXT must be a string" }
                        DashboardBindValue.Text(value.content)
                    }
                    DashboardValueType.NUMBER -> DashboardBindValue.Number(DashboardJson.text(value, "SQL resolved NUMBER"))
                    DashboardValueType.BOOLEAN -> DashboardBindValue.Boolean(DashboardJson.boolean(value, "SQL resolved BOOLEAN"))
                    DashboardValueType.DATE -> DashboardBindValue.Date(DashboardJson.date(value, "SQL resolved DATE"))
                    DashboardValueType.TIMESTAMP -> DashboardBindValue.Timestamp(DashboardJson.timestamp(value, "SQL resolved TIMESTAMP"))
                    else -> error("SQL resolved binding must be scalar")
                }
            }
            DashboardResolvedQuery.Sql(binds, DashboardJson.integer(node.getValue("rowLimit"), "SQL resolved rowLimit"))
        }
    }
}
