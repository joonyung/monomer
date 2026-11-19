package cc.monomer.execution.driver.databricks

import cc.monomer.execution.driver.spi.NullQueryParameterType
import cc.monomer.execution.driver.spi.QueryParameter
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import java.math.BigDecimal

internal fun databricksParameters(parameters: List<QueryParameter>): ArrayNode =
    JsonNodeFactory.instance.arrayNode().apply {
        parameters.forEach { parameter ->
            val type = when (parameter) {
                is QueryParameter.Text -> "STRING"
                is QueryParameter.Integer64 -> "BIGINT"
                is QueryParameter.Float64 -> "DOUBLE"
                is QueryParameter.BooleanValue -> "BOOLEAN"
                is QueryParameter.Decimal -> BigDecimal(parameter.value).let {
                    "DECIMAL(${maxOf(it.precision(), it.scale(), 1)},${maxOf(it.scale(), 0)})"
                }
                is QueryParameter.Date -> "DATE"
                is QueryParameter.Timestamp -> "TIMESTAMP"
                is QueryParameter.Bytes -> throw DatabricksFailure("QUERY_BIND_UNSUPPORTED", BYTES_UNSUPPORTED_MESSAGE)
                is QueryParameter.Null -> when (parameter.type) {
                    NullQueryParameterType.TEXT -> "STRING"
                    NullQueryParameterType.INTEGER64 -> "BIGINT"
                    NullQueryParameterType.FLOAT64 -> "DOUBLE"
                    NullQueryParameterType.BOOLEAN -> "BOOLEAN"
                    NullQueryParameterType.BYTES -> throw DatabricksFailure("QUERY_BIND_UNSUPPORTED", BYTES_UNSUPPORTED_MESSAGE)
                    NullQueryParameterType.DECIMAL -> "DECIMAL(38,18)"
                    NullQueryParameterType.DATE -> "DATE"
                    NullQueryParameterType.TIMESTAMP -> "TIMESTAMP"
                }
            }
            val value = when (parameter) {
                is QueryParameter.Text -> parameter.value
                is QueryParameter.Integer64 -> parameter.value.toString()
                is QueryParameter.Float64 -> parameter.value.toString()
                is QueryParameter.BooleanValue -> parameter.value.toString()
                is QueryParameter.Decimal -> parameter.value
                is QueryParameter.Date -> parameter.value.toString()
                is QueryParameter.Timestamp -> parameter.value.toString()
                is QueryParameter.Null -> null
                is QueryParameter.Bytes -> error("unsupported bind")
            }
            addObject().put("name", parameter.name).put("type", type).apply {
                if (value != null) put("value", value)
            }
        }
    }

internal const val BYTES_UNSUPPORTED_MESSAGE =
    "Databricks binary parameters, including typed nulls, are unsupported. Use a Semantic query."
