package cc.monomer.pages.application.content.rendering

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class StrictUtf8Decoder {
    fun decode(bytes: ByteArray): Utf8DecodingResult {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val decoded = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            return Utf8DecodingResult.Rejected(Utf8Rejection.MALFORMED_OR_UNMAPPABLE)
        }
        return if ('\u0000' in decoded) {
            Utf8DecodingResult.Rejected(Utf8Rejection.NUL_CHARACTER)
        } else {
            Utf8DecodingResult.Decoded(decoded)
        }
    }
}

enum class Utf8Rejection {
    MALFORMED_OR_UNMAPPABLE,
    NUL_CHARACTER,
}

sealed interface Utf8DecodingResult {
    data class Decoded(val text: String) : Utf8DecodingResult
    data class Rejected(val reason: Utf8Rejection) : Utf8DecodingResult
}
