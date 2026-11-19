package cc.monomer.execution.driver.spi

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

sealed interface QueryResultTarget {
    val maximumRecordBatchRows: Int
    val maximumRecordBatchBytes: Int
    val maximumCellBytes: Int

    data class InlineStream(
        override val maximumRecordBatchRows: Int,
        override val maximumRecordBatchBytes: Int,
        override val maximumCellBytes: Int,
    ) : QueryResultTarget {
        init {
            require(maximumRecordBatchRows > 0)
            require(maximumRecordBatchBytes > 0)
            require(maximumCellBytes > 0)
        }
    }

    data class ArtifactUpload(
        val objectKey: String,
        val maximumArtifactRows: ULong,
        val maximumArtifactBytes: ULong,
        override val maximumRecordBatchRows: Int,
        override val maximumRecordBatchBytes: Int,
        override val maximumCellBytes: Int,
        val maximumMultipartPartBytes: Int,
    ) : QueryResultTarget {
        init {
            require(objectKey.isNotBlank())
            require(maximumArtifactRows > 0uL)
            require(maximumArtifactBytes > 0uL)
            require(maximumRecordBatchRows > 0)
            require(maximumRecordBatchBytes > 0)
            require(maximumCellBytes > 0)
            require(maximumMultipartPartBytes >= maximumRecordBatchBytes)
        }
    }
}

data class QueryRequest(
    val schemaVersion: UInt,
    val canonicalRequestDigest: Sha256Digest,
    val sql: String,
    val parameters: List<QueryParameter>,
    val resultTarget: QueryResultTarget,
    val validateRawSqlBeforeRun: Boolean,
    val expectedColumnNames: List<String>,
) {
    init {
        require(schemaVersion > 0u)
        require(sql.isNotBlank())
    }

    val isArtifactUpload: Boolean
        get() = resultTarget is QueryResultTarget.ArtifactUpload
}

sealed interface QueryParameter {
    val name: String

    data class Text(val value: String, override val name: String) : QueryParameter

    data class Integer64(val value: Long, override val name: String) : QueryParameter

    data class Float64(val value: Double, override val name: String) : QueryParameter {
        init {
            require(value.isFinite())
        }
    }

    data class BooleanValue(val value: Boolean, override val name: String) : QueryParameter

    data class Bytes(val value: BinaryValue, override val name: String) : QueryParameter {
        init {
            require(value.size > 0)
        }
    }

    data class Decimal(val value: String, override val name: String) : QueryParameter {
        init {
            val decimal = BigDecimal(value)
            require(decimal.stripTrailingZeros().toPlainString() == value) { "decimal parameter must be canonical" }
            require(decimal.precision() <= 38 && decimal.scale() in 0..18) { "decimal parameter is outside NUMBER bounds" }
        }
    }

    data class Date(val value: LocalDate, override val name: String) : QueryParameter

    data class Timestamp(val value: Instant, override val name: String) : QueryParameter {
        init {
            require(value.nano % 1_000 == 0) { "timestamp parameter exceeds microsecond precision" }
        }
    }

    data class Null(val type: NullQueryParameterType, override val name: String) : QueryParameter
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

data class RunAssignment(
    val workspaceId: WorkspaceId,
    val connector: ConnectorRuntimeBinding,
    val request: QueryRequest,
    val deadlineEpochMillis: Long,
) {
    init {
        require(deadlineEpochMillis > 0)
    }
}

fun requireQueryParameterNames(connectorType: ConnectorType, parameters: List<QueryParameter>) {
    if (connectorType == ConnectorType.DATABRICKS) {
        require(parameters.all { PARAMETER_NAME.matches(it.name) }) { "Databricks parameters require valid names" }
        require(parameters.map { it.name.lowercase(java.util.Locale.ROOT) }.distinct().size == parameters.size) {
            "Databricks parameter names must be case-insensitively unique"
        }
    } else {
        require(parameters.all { it.name.isEmpty() }) { "positional query parameters cannot have names" }
    }
}

private val PARAMETER_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
