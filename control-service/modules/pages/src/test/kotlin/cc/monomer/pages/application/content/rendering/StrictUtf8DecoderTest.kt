package cc.monomer.pages.application.content.rendering

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrictUtf8DecoderTest {
    private val decoder = StrictUtf8Decoder()

    @Test
    fun `decodes exact UTF-8 without replacing input`() {
        val result = decoder.decode("문서 café".toByteArray(Charsets.UTF_8))

        assertThat(result).isEqualTo(Utf8DecodingResult.Decoded("문서 café"))
    }

    @Test
    fun `rejects malformed input instead of inserting a replacement character`() {
        val result = decoder.decode(byteArrayOf(0x43, 0xC3.toByte(), 0x28))

        assertThat(result).isEqualTo(
            Utf8DecodingResult.Rejected(Utf8Rejection.MALFORMED_OR_UNMAPPABLE),
        )
    }

    @Test
    fun `rejects NUL in otherwise valid UTF-8`() {
        val result = decoder.decode("before\u0000after".toByteArray(Charsets.UTF_8))

        assertThat(result).isEqualTo(Utf8DecodingResult.Rejected(Utf8Rejection.NUL_CHARACTER))
    }
}
