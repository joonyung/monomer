package cc.monomer.execution.adapter.outbound.postgres

import cc.monomer.execution.api.ConnectorRuntimePropertyValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets

private const val CONNECTOR_PROPERTIES_ENCODING_VERSION = 1

internal fun encodeConnectorProperties(
    properties: Map<String, ConnectorRuntimePropertyValue>,
): ByteArray {
    val output = ByteArrayOutputStream()
    DataOutputStream(output).use { data ->
        data.writeInt(CONNECTOR_PROPERTIES_ENCODING_VERSION)
        data.writeInt(properties.size)
        properties.toSortedMap().forEach { (name, value) ->
            val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
            data.writeInt(nameBytes.size)
            data.write(nameBytes)
            when (value) {
                is ConnectorRuntimePropertyValue.Text -> {
                    data.writeByte(1)
                    val valueBytes = value.value.toByteArray(StandardCharsets.UTF_8)
                    data.writeInt(valueBytes.size)
                    data.write(valueBytes)
                }
                is ConnectorRuntimePropertyValue.Integer -> {
                    data.writeByte(2)
                    data.writeLong(value.value)
                }
            }
        }
    }
    return output.toByteArray()
}

internal fun decodeConnectorProperties(bytes: ByteArray): Map<String, ConnectorRuntimePropertyValue> = try {
    readConnectorProperties(bytes)
} catch (truncated: EOFException) {
    throw IllegalArgumentException("connector runtime properties are truncated", truncated)
}

private fun readConnectorProperties(bytes: ByteArray): Map<String, ConnectorRuntimePropertyValue> {
    DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == CONNECTOR_PROPERTIES_ENCODING_VERSION)
        val count = input.readInt()
        require(count in 1..128)
        val properties = linkedMapOf<String, ConnectorRuntimePropertyValue>()
        repeat(count) {
            val nameSize = input.readInt()
            require(nameSize in 1..256)
            val name = ByteArray(nameSize).also(input::readFully).toString(StandardCharsets.UTF_8)
            require(name.isNotBlank() && properties.put(name, readConnectorProperty(input)) == null)
        }
        require(input.available() == 0)
        return properties
    }
}

private fun readConnectorProperty(input: DataInputStream): ConnectorRuntimePropertyValue =
    when (input.readUnsignedByte()) {
        1 -> {
            val size = input.readInt()
            require(size in 1..1_048_576)
            ConnectorRuntimePropertyValue.Text(
                ByteArray(size).also(input::readFully).toString(StandardCharsets.UTF_8),
            )
        }
        2 -> ConnectorRuntimePropertyValue.Integer(input.readLong())
        else -> throw IllegalArgumentException("unsupported connector runtime property value")
    }
