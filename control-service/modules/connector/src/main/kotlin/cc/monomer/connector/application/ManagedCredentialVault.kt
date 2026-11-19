package cc.monomer.connector.application

import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.CredentialEnvelopeReference
import cc.monomer.identity.api.WorkspaceId
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Arrays

class CredentialMaterial private constructor(
    private var fields: Map<String, ByteArray>?,
) : AutoCloseable {
    fun <T> useFields(block: (Map<String, ByteArray>) -> T): T {
        val current = checkNotNull(fields) { "Credential material is already destroyed" }
        val copy = current.mapValues { (_, value) -> value.copyOf() }
        return try {
            block(copy)
        } finally {
            copy.values.forEach { value -> Arrays.fill(value, 0) }
        }
    }

    fun requireCompatible(connectorTypeName: String) {
        val current = checkNotNull(fields) { "Credential material is already destroyed" }
        requireCredentialFieldMapping(connectorTypeName, current.keys)
        if (connectorTypeName.equals("duckdb", ignoreCase = true)) {
            require(listOf("acme-cloud@1", "acme-cloud@2", "acme-cloud@3").any { current.getValue("dataset").contentEquals(it.toByteArray(Charsets.UTF_8)) }) { "Unsupported sample dataset" }
        }
    }

    fun normalizedForExecution(connectorTypeName: String): CredentialMaterial {
        requireCompatible(connectorTypeName)
        return useFields { current ->
            val fieldMapping = requireCredentialFieldMapping(connectorTypeName, current.keys)
            copyOf(
                current.entries.associate { (sourceName, value) ->
                    fieldMapping.getValue(sourceName) to value
                },
            )
        }

    }

    override fun close() {
        fields?.values?.forEach { value -> Arrays.fill(value, 0) }
        fields = null
    }

    override fun toString(): String = "[REDACTED]"

    companion object {
        fun copyOf(fields: Map<String, ByteArray>): CredentialMaterial {
            require(fields.isNotEmpty()) { "Credential fields must not be empty" }
            require(fields.keys.all(String::isCredentialFieldIdentifier)) {
                "Credential field names must be source or runtime identifiers"
            }
            require(fields.values.all { value -> value.isNotEmpty() }) {
                "Credential field values must not be empty"
            }
            return CredentialMaterial(
                fields.mapValues { (_, value) -> value.copyOf() }.toSortedMap(),
            )
        }
    }
}

private fun requireCredentialFieldMapping(
    connectorTypeName: String,
    actualFields: Set<String>,
): Map<String, String> {
    val acceptedMappings =
        when (connectorTypeName.lowercase()) {
            "duckdb" -> listOf(identityCredentialFieldMapping("dataset"))
            "postgresql", "trino" -> listOf(identityCredentialFieldMapping("username", "password"))
            "bigquery" -> listOf(identityCredentialFieldMapping("service_account_json"))
            "snowflake" ->
                listOf(
                    identityCredentialFieldMapping("username", "password"),
                    mapOf("username" to "username", "privateKey" to "private_key"),
                    mapOf(
                        "username" to "username",
                        "privateKey" to "private_key",
                        "privateKeyPassphrase" to "private_key_passphrase",
                    ),
                )
            "redshift" ->
                listOf(
                    identityCredentialFieldMapping("username", "password"),
                    mapOf(
                        "awsAccessKeyId" to "aws_access_key_id",
                        "awsSecretAccessKey" to "aws_secret_access_key",
                        "awsRegion" to "aws_region",
                        "clusterId" to "cluster_id",
                        "dbUser" to "db_user",
                    ),
                )
            "databricks" ->
                listOf(
                    mapOf("accessToken" to "access_token"),
                    mapOf("oauthClientId" to "oauth_client_id", "oauthClientSecret" to "oauth_client_secret"),
                )
            else -> throw IllegalArgumentException("Unsupported Connector type")
        }
    return requireNotNull(acceptedMappings.singleOrNull { mapping -> mapping.keys == actualFields }) {
        "$connectorTypeName credential fields do not match an accepted connector credential contract"
    }
}

private fun identityCredentialFieldMapping(vararg fieldNames: String): Map<String, String> =
    fieldNames.associateWith { fieldName -> fieldName }

private val camelCaseCredentialFieldIdentifier = Regex("^[a-z][a-z0-9]*(?:[A-Z][a-z0-9]*)*$")
private val snakeCaseCredentialFieldIdentifier = Regex("^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$")

private fun String.isCredentialFieldIdentifier(): Boolean =
    length in 1..64 &&
        (camelCaseCredentialFieldIdentifier.matches(this) || snakeCaseCredentialFieldIdentifier.matches(this))

data class ManagedCredentialWriteRequest(
    val workspaceId: WorkspaceId,
    val connectorId: ConnectorId,
    val connectorType: String,
    val credentialVersionId: cc.monomer.connector.api.CredentialVersionId,
    val createdAt: Instant,
)

data class ManagedCredentialReadRequest(
    val workspaceId: WorkspaceId,
    val connectorId: ConnectorId,
    val connectorType: String,
    val reference: CredentialEnvelopeReference,
    val serverDeadline: Instant,
)

sealed interface ManagedCredentialReadOutcome {
    class Found(
        val credentialMaterial: CredentialMaterial,
    ) : ManagedCredentialReadOutcome, AutoCloseable {
        override fun close() {
            credentialMaterial.close()
        }

        override fun toString(): String = "Found(credentialMaterial=[REDACTED])"
    }

    data object Rejected : ManagedCredentialReadOutcome
    data object Unavailable : ManagedCredentialReadOutcome
}

interface ManagedCredentialReadPort {
    suspend fun readExact(request: ManagedCredentialReadRequest): ManagedCredentialReadOutcome
}

interface ManagedCredentialWritePort {
    fun writeInOwnerTransaction(
        request: ManagedCredentialWriteRequest,
        credentialMaterial: CredentialMaterial,
    ): CredentialEnvelopeReference

    fun matchesInOwnerTransaction(
        request: ManagedCredentialWriteRequest,
        credentialMaterial: CredentialMaterial,
    ): Boolean

    fun destroyInOwnerTransaction(workspaceId: WorkspaceId, connectorId: ConnectorId): Int
}

/** Codec for the bounded encrypted document; it never carries connector lifecycle state. */
object ManagedCredentialDocumentCodec {
    private const val MAGIC = 0x4d435231

    fun encode(
        credentialMaterial: CredentialMaterial,
        maximumDocumentBytes: Int,
        maximumFields: Int,
        maximumFieldNameBytes: Int,
        maximumFieldValueBytes: Int,
    ): ByteArray {
        require(maximumDocumentBytes > 0)
        require(maximumFields > 0)
        require(maximumFieldNameBytes > 0)
        require(maximumFieldValueBytes > 0)
        return connectorInput { credentialMaterial.useFields { fields ->
            require(fields.size in 1..maximumFields) {
                "Credential field count exceeds the managed credential bound"
            }
            val encodedFields = mutableListOf<EncodedField>()
            try {
                fields.toSortedMap().forEach { (name, value) ->
                    value.requireValidUtf8()
                    val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
                    try {
                        require(nameBytes.size <= maximumFieldNameBytes) {
                            "Credential field name exceeds the managed credential bound"
                        }
                        require(value.size <= maximumFieldValueBytes) {
                            "Credential field value exceeds the managed credential bound"
                        }
                        encodedFields.add(EncodedField(nameBytes, value))
                    } catch (failure: Throwable) {
                        Arrays.fill(nameBytes, 0)
                        throw failure
                    }
                }
                val documentSize = encodedFields.fold(8L) { size, field ->
                    size + 4 + field.nameBytes.size + 4 + field.value.size
                }
                require(documentSize <= Int.MAX_VALUE) { "Credential document exceeds the JVM array bound" }
                require(documentSize <= maximumDocumentBytes) { "Credential document exceeds the managed credential bound" }
                ByteBuffer.allocate(documentSize.toInt()).apply {
                    putInt(MAGIC)
                    putInt(encodedFields.size)
                    encodedFields.forEach { field ->
                        putInt(field.nameBytes.size)
                        put(field.nameBytes)
                        putInt(field.value.size)
                        put(field.value)
                    }
                    check(!hasRemaining())
                }.array()
            } finally {
                encodedFields.forEach { field -> Arrays.fill(field.nameBytes, 0) }
            }
        } }
    }

    fun decode(
        document: ByteArray,
        maximumFields: Int,
        maximumFieldNameBytes: Int,
        maximumFieldValueBytes: Int,
    ): CredentialMaterial {
        require(maximumFields > 0)
        require(maximumFieldNameBytes > 0)
        require(maximumFieldValueBytes > 0)
        val fields = linkedMapOf<String, ByteArray>()
        try {
            DataInputStream(ByteArrayInputStream(document)).use { input ->
                require(input.readInt() == MAGIC) { "Credential document has an invalid format" }
                val fieldCount = input.readInt()
                require(fieldCount in 1..maximumFields) { "Credential document has an invalid field count" }
                var previousName: String? = null
                repeat(fieldCount) {
                    val nameBytes = input.readBoundedBytes(maximumFieldNameBytes)
                    val name = try {
                        nameBytes.toString(StandardCharsets.UTF_8)
                    } finally {
                        Arrays.fill(nameBytes, 0)
                    }
                    require(name.isCredentialFieldIdentifier()) { "Credential document has an invalid field name" }
                    require(previousName == null || previousName < name) { "Credential document fields are not canonical" }
                    previousName = name
                    val value = input.readBoundedBytes(maximumFieldValueBytes)
                    try {
                        value.requireValidUtf8()
                        fields[name] = value
                    } catch (failure: Throwable) {
                        Arrays.fill(value, 0)
                        throw failure
                    }
                }
                require(input.available() == 0) { "Credential document contains trailing bytes" }
            }
            return CredentialMaterial.copyOf(fields)
        } catch (failure: java.io.IOException) {
            throw IllegalArgumentException("Credential document has an invalid format", failure)
        } finally {
            fields.values.forEach { value -> Arrays.fill(value, 0) }
        }
    }

    private fun DataInputStream.readBoundedBytes(maximumBytes: Int): ByteArray {
        val size = readInt()
        require(size in 1..maximumBytes) { "Credential document field exceeds its bound" }
        val bytes = ByteArray(size)
        return try {
            readFully(bytes)
            bytes
        } catch (failure: Throwable) {
            Arrays.fill(bytes, 0)
            throw failure
        }
    }

    private data class EncodedField(val nameBytes: ByteArray, val value: ByteArray)
}

private fun ByteArray.requireValidUtf8() {
    var index = 0
    while (index < size) {
        val first = this[index].toInt().and(0xff)
        when (first) {
            in 0x00..0x7f -> index += 1
            in 0xc2..0xdf -> { requireContinuation(index + 1); index += 2 }
            0xe0 -> { requireByte(index + 1, 0xa0, 0xbf); requireContinuation(index + 2); index += 3 }
            in 0xe1..0xec, in 0xee..0xef -> { requireContinuation(index + 1); requireContinuation(index + 2); index += 3 }
            0xed -> { requireByte(index + 1, 0x80, 0x9f); requireContinuation(index + 2); index += 3 }
            0xf0 -> { requireByte(index + 1, 0x90, 0xbf); requireContinuation(index + 2); requireContinuation(index + 3); index += 4 }
            in 0xf1..0xf3 -> { requireContinuation(index + 1); requireContinuation(index + 2); requireContinuation(index + 3); index += 4 }
            0xf4 -> { requireByte(index + 1, 0x80, 0x8f); requireContinuation(index + 2); requireContinuation(index + 3); index += 4 }
            else -> throw IllegalArgumentException("Credential field value is not canonical UTF-8")
        }
    }
}

private fun ByteArray.requireContinuation(index: Int) = requireByte(index, 0x80, 0xbf)

private fun ByteArray.requireByte(index: Int, minimum: Int, maximum: Int) {
    require(index < size && this[index].toInt().and(0xff) in minimum..maximum) {
        "Credential field value is not canonical UTF-8"
    }
}
