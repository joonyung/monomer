package cc.monomer.execution.runtime

import cc.monomer.execution.driver.spi.MaterializedCredential
import cc.monomer.execution.driver.spi.SecretCharacters
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object CredentialDocumentCodec {
    private const val MAGIC = 0x4d435231

    fun decode(
        document: ByteArray,
        maximumFields: Int,
        maximumFieldCharacters: Int,
    ): MaterializedCredential {
        val decoded = linkedMapOf<String, SecretCharacters>()
        try {
            DataInputStream(ByteArrayInputStream(document)).use { input ->
                require(input.readInt() == MAGIC) { "credential document has an invalid format" }
                val fieldCount = input.readInt()
                require(fieldCount in 1..maximumFields) {
                    "credential document has an invalid field count"
                }
                repeat(fieldCount) {
                    val nameBytes = readBytes(input, maximumFieldCharacters * 4)
                    val name =
                        try {
                            val characters = decodeSecretCharacters(nameBytes)
                            try {
                                String(characters)
                            } finally {
                                characters.fill('\u0000')
                            }
                        } finally {
                            nameBytes.fill(0)
                        }
                    require(name.isNotBlank())
                    require(name !in decoded)
                    val valueBytes = readBytes(input, maximumFieldCharacters * 4)
                    val characters = decodeSecretCharacters(valueBytes)
                    try {
                        require(characters.size <= maximumFieldCharacters)
                        decoded[name] = SecretCharacters.copyOf(characters)
                    } finally {
                        characters.fill('\u0000')
                    }
                }
                require(input.available() == 0) {
                    "credential document contains trailing bytes"
                }
            }
            return MaterializedCredential(decoded)
        } catch (failure: Throwable) {
            decoded.values.forEach(SecretCharacters::close)
            throw failure
        }
    }

    private fun readBytes(
        input: DataInputStream,
        maximumBytes: Int,
    ): ByteArray {
        val size = input.readInt()
        require(size in 1..maximumBytes)
        val bytes = ByteArray(size)
        return try {
            input.readFully(bytes)
            bytes
        } catch (failure: Throwable) {
            bytes.fill(0)
            throw failure
        }
    }

    private fun decodeSecretCharacters(valueBytes: ByteArray): CharArray {
        val decoder =
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        var decoded: CharBuffer? = null
        return try {
            decoded =
                try {
                    decoder.decode(ByteBuffer.wrap(valueBytes))
                } catch (failure: CharacterCodingException) {
                    throw IllegalArgumentException(
                        "credential document contains invalid UTF-8",
                        failure,
                    )
                }
            CharArray(decoded.remaining()).also(decoded::get)
        } finally {
            valueBytes.fill(0)
            decoded?.let { buffer ->
                if (buffer.hasArray()) {
                    buffer.array().fill('\u0000')
                } else {
                    buffer.clear()
                    while (buffer.hasRemaining()) {
                        buffer.put('\u0000')
                    }
                }
            }
        }
    }
}
