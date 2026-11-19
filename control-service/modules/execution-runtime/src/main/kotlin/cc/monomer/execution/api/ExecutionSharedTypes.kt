package cc.monomer.execution.api

import java.security.MessageDigest
import java.util.HexFormat
import kotlin.uuid.Uuid

@JvmInline
value class Sha256Digest(val value: String) {
    init {
        require(value.matches(Regex("[0-9a-f]{64}")))
    }

    override fun toString(): String = value

    companion object {
        fun of(bytes: ByteArray): Sha256Digest = fromDigestBytes(MessageDigest.getInstance("SHA-256").digest(bytes))

        fun fromDigestBytes(digest: ByteArray): Sha256Digest = Sha256Digest(HexFormat.of().formatHex(digest))
    }
}

@JvmInline
value class ConnectorReference(val value: Uuid) {
    init {
        value.requireUuidV7("connector reference")
    }

    companion object {
        fun parse(value: String): ConnectorReference =
            ConnectorReference(Uuid.parse(value))
    }

    override fun toString(): String = value.toString()
}

@JvmInline
value class CredentialVersionReference(val value: Uuid) {
    init {
        value.requireUuidV7("credential version reference")
    }

    companion object {
        fun parse(value: String): CredentialVersionReference =
            CredentialVersionReference(Uuid.parse(value))
    }

    override fun toString(): String = value.toString()
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

enum class ExecutableRequestKind {
    QUERY,
}

enum class ResultSinkKind {
    ARTIFACT_UPLOAD,
    INLINE_STREAM,
}

class BinaryValue private constructor(bytes: ByteArray) {
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

private fun Uuid.requireUuidV7(label: String) {
    require(this != Uuid.NIL) { "$label must not be nil" }
    val bytes = toByteArray()
    require(bytes[8].toInt().and(0xc0) == 0x80) {
        "$label must use the IETF UUID variant"
    }
    require(bytes[6].toInt().and(0xf0) == 0x70) {
        "$label must be UUIDv7"
    }
}
