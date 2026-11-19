@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.identity.api

import java.io.ByteArrayOutputStream
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import kotlin.uuid.Uuid

sealed interface CanonicalIdentifierPart {
    data class Text(
        val value: String,
    ) : CanonicalIdentifierPart

    data class UuidPart(
        val value: Uuid,
    ) : CanonicalIdentifierPart
}

object CanonicalIdentifierCodec {
    fun encode(parts: List<CanonicalIdentifierPart>): ByteArray {
        require(parts.isNotEmpty()) { "canonical identifier requires a domain tag" }
        require(parts.first() is CanonicalIdentifierPart.Text) {
            "canonical identifier domain tag must be framed text"
        }
        require((parts.first() as CanonicalIdentifierPart.Text).value.isNotBlank()) {
            "canonical identifier domain tag must not be blank"
        }
        val output = ByteArrayOutputStream()
        parts.forEach { part ->
            when (part) {
                is CanonicalIdentifierPart.Text -> output.writeFramedText(part.value)
                is CanonicalIdentifierPart.UuidPart -> output.write(part.value.toByteArray())
            }
        }
        return output.toByteArray()
    }

    fun sha256UuidV8(parts: List<CanonicalIdentifierPart>): Uuid {
        val digest = MessageDigest.getInstance("SHA-256").digest(encode(parts))
        val uuidBytes = digest.copyOfRange(0, 16)
        uuidBytes[6] = (uuidBytes[6].toInt().and(0x0f).or(0x80)).toByte()
        uuidBytes[8] = (uuidBytes[8].toInt().and(0x3f).or(0x80)).toByte()
        return Uuid.fromByteArray(uuidBytes)
    }

    fun sha256Digest(parts: List<CanonicalIdentifierPart>): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(encode(parts)).hex()
}

private fun ByteArrayOutputStream.writeFramedText(value: String) {
    val encoded = value.strictUtf8Bytes()
    require(encoded.size.toLong() <= UINT32_MAXIMUM) {
        "canonical identifier text exceeds the unsigned 32-bit frame"
    }
    write(
        byteArrayOf(
            (encoded.size ushr 24).toByte(),
            (encoded.size ushr 16).toByte(),
            (encoded.size ushr 8).toByte(),
            encoded.size.toByte(),
        ),
    )
    write(encoded)
}

private fun String.strictUtf8Bytes(): ByteArray {
    require(Normalizer.isNormalized(this, Normalizer.Form.NFC)) {
        "canonical identifier text must already use Unicode NFC"
    }
    val encoder = StandardCharsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val encoded = try {
        encoder.encode(CharBuffer.wrap(this))
    } catch (failure: CharacterCodingException) {
        throw IllegalArgumentException("canonical identifier text must be valid UTF-8", failure)
    }
    return ByteArray(encoded.remaining()).also(encoded::get)
}

private fun ByteArray.hex(): String =
    joinToString("") { byte ->
        byte.toInt().and(0xff).toString(16).padStart(2, '0')
    }

private const val UINT32_MAXIMUM = 4_294_967_295L
