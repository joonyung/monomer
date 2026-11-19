package cc.monomer.execution.api

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

sealed interface QueryParameterValue {
    val name: String

    data class Text(
        val value: String,
        override val name: String,
    ) : QueryParameterValue {
        init {
            requireCanonicalSqlText(value)
        }
    }

    data class Integer64(
        val value: Long,
        override val name: String,
    ) : QueryParameterValue

    data class Float64(
        val value: Double,
        override val name: String,
    ) : QueryParameterValue {
        init {
            require(value.isFinite()) { "query float parameter must be finite" }
        }
    }

    data class BooleanValue(
        val value: Boolean,
        override val name: String,
    ) : QueryParameterValue

    data class Bytes(
        val value: BinaryValue,
        override val name: String,
    ) : QueryParameterValue {
        init {
            require(value.size > 0) { "query byte parameter must not be empty" }
        }
    }

    data class Decimal(val value: String, override val name: String) : QueryParameterValue {
        init {
            val decimal = BigDecimal(value)
            require(decimal.stripTrailingZeros().toPlainString() == value) { "decimal parameter must be canonical" }
            require(decimal.precision() <= 38 && decimal.scale() in 0..18) { "decimal parameter is outside NUMBER bounds" }
        }
    }

    data class Date(val value: LocalDate, override val name: String) : QueryParameterValue

    data class Timestamp(val value: Instant, override val name: String) : QueryParameterValue {
        init {
            require(value.nano % 1_000 == 0) { "timestamp parameter exceeds microsecond precision" }
        }
    }

    data class Null(
        val type: NullQueryParameterType,
        override val name: String,
    ) : QueryParameterValue
}

enum class NullQueryParameterType {
    TEXT,
    INTEGER64,
    FLOAT64,
    BOOLEAN,
    BYTES,
    DECIMAL,
    DATE,
    TIMESTAMP,
}

data class RawSqlExecutionRequest(
    val schemaVersion: UInt,
    val canonicalRequestDigest: Sha256Digest,
    val sql: String,
    val parameters: List<QueryParameterValue>,
    val maximumResultRows: ULong,
    val maximumArtifactBytes: ULong,
    val maximumRecordBatchRows: Int,
    val maximumRecordBatchBytes: Int,
    val maximumCellBytes: Int,
    val maximumMultipartPartBytes: Int,
    val validateRawSqlBeforeRun: Boolean,
    val expectedColumnNames: List<String>,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "raw SQL execution request schema is unsupported" }
        require(sql.isNotBlank()) { "raw SQL execution request SQL must not be blank" }
        requireCanonicalSqlText(sql)
        require(maximumResultRows > 0uL) { "raw SQL execution request row limit must be positive" }
        require(maximumArtifactBytes in 1uL..MAXIMUM_ARTIFACT_BYTES) {
            "raw SQL execution request artifact byte limit is outside the supported range"
        }
        require(maximumRecordBatchRows > 0)
        require(maximumRecordBatchBytes > 0)
        require(maximumCellBytes > 0)
        require(maximumMultipartPartBytes >= maximumRecordBatchBytes)
    }

    companion object {
        const val SCHEMA_VERSION: UInt = 1u
        const val MAXIMUM_ARTIFACT_BYTES: ULong = 1_073_741_824uL
    }
}

internal fun requireCanonicalSqlText(value: String) {
    require(value.none { character -> character == '\u0000' }) {
        "raw SQL text must not contain NUL"
    }
    var index = 0
    while (index < value.length) {
        when {
            Character.isHighSurrogate(value[index]) -> {
                require(index + 1 < value.length && Character.isLowSurrogate(value[index + 1])) {
                    "raw SQL text must not contain unpaired surrogates"
                }
                index += 2
            }

            Character.isLowSurrogate(value[index]) ->
                require(false) { "raw SQL text must not contain unpaired surrogates" }

            else -> index += 1
        }
    }
}
