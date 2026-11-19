package cc.monomer.execution.driver.spi

data class RunIdentity(
    val runId: RunId,
)

@JvmInline
value class Sha256Digest(val value: String) {
    init {
        require(value.matches(Regex("[0-9a-f]{64}")))
    }
}

enum class ConnectorType {
    POSTGRESQL,
    BIGQUERY,
    SNOWFLAKE,
    TRINO,
    REDSHIFT,
    DATABRICKS,
    DUCKDB,
}

sealed interface ConnectorPropertyValue {
    data class Text(val value: String) : ConnectorPropertyValue

    data class Integer(val value: Long) : ConnectorPropertyValue

    data class BooleanValue(val value: Boolean) : ConnectorPropertyValue

    data class TextList(val values: List<String>) : ConnectorPropertyValue {
        init {
            require(values.none(String::isBlank))
        }
    }
}

class BinaryValue private constructor(
    bytes: ByteArray,
) {
    private val content = bytes.copyOf()

    val size: Int
        get() = content.size

    fun copyBytes(): ByteArray = content.copyOf()

    override fun equals(other: Any?): Boolean =
        other is BinaryValue && content.contentEquals(other.content)

    override fun hashCode(): Int = content.contentHashCode()

    override fun toString(): String = "BinaryValue(size=$size)"

    companion object {
        fun copyOf(bytes: ByteArray): BinaryValue = BinaryValue(bytes)
    }
}

data class ConnectorRuntimeBinding(
    val schemaVersion: UInt,
    val connectorId: ConnectorId,
    val connectorType: ConnectorType,
    val properties: Map<String, ConnectorPropertyValue>,
) {
    init {
        require(schemaVersion == 1u)
        require(properties.keys.none(String::isBlank))
    }

    override fun toString(): String =
        "ConnectorRuntimeBinding(" +
            "schemaVersion=$schemaVersion, connectorId=$connectorId, " +
            "connectorType=$connectorType, properties=[REDACTED])"
}
