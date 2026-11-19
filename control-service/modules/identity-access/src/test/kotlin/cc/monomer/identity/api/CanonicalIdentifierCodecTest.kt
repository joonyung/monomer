@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.identity.api

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import kotlin.uuid.Uuid

class CanonicalIdentifierCodecTest {
    private val uuid = Uuid.parse("00112233-4455-7788-99aa-bbccddeeff00")

    @Test
    fun `encodes framed NFC text and raw network order UUID bytes`() {
        val encoded = CanonicalIdentifierCodec.encode(
            listOf(
                CanonicalIdentifierPart.Text("monomer:test"),
                CanonicalIdentifierPart.UuidPart(uuid),
            ),
        )

        assertThat(encoded.hex()).isEqualTo(
            "0000000c6d6f6e6f6d65723a74657374001122334455778899aabbccddeeff00",
        )
        assertThat(
            CanonicalIdentifierCodec.sha256UuidV8(
                listOf(
                    CanonicalIdentifierPart.Text("monomer:test"),
                    CanonicalIdentifierPart.UuidPart(uuid),
                ),
            ),
        ).isEqualTo(Uuid.parse("740ca41a-0f19-8dd5-b6e2-de24614bdc0e"))
    }

    @Test
    fun `derived UUID uses v8 and IETF variant bits`() {
        val derived = CanonicalIdentifierCodec.sha256UuidV8(
            listOf(
                CanonicalIdentifierPart.Text("monomer:test"),
                CanonicalIdentifierPart.UuidPart(uuid),
            ),
        ).toByteArray()

        assertThat(derived[6].toInt().and(0xf0)).isEqualTo(0x80)
        assertThat(derived[8].toInt().and(0xc0)).isEqualTo(0x80)
    }

    @Test
    fun `rejects non NFC and malformed surrogate text`() {
        assertThatIllegalArgumentException().isThrownBy {
            CanonicalIdentifierCodec.encode(
                listOf(CanonicalIdentifierPart.Text("e\u0301")),
            )
        }
        assertThatIllegalArgumentException().isThrownBy {
            CanonicalIdentifierCodec.encode(
                listOf(CanonicalIdentifierPart.Text("\uD800")),
            )
        }
    }

    @Test
    fun `preserves exact case and requires a text domain tag`() {
        val upper = CanonicalIdentifierCodec.sha256Digest(
            listOf(CanonicalIdentifierPart.Text("Monomer")),
        )
        val lower = CanonicalIdentifierCodec.sha256Digest(
            listOf(CanonicalIdentifierPart.Text("monomer")),
        )

        assertThat(upper).isNotEqualTo(lower)
        assertThatIllegalArgumentException().isThrownBy {
            CanonicalIdentifierCodec.encode(
                listOf(CanonicalIdentifierPart.UuidPart(uuid)),
            )
        }
        assertThatIllegalArgumentException().isThrownBy {
            CanonicalIdentifierCodec.encode(
                listOf(CanonicalIdentifierPart.Text("")),
            )
        }
    }
}

private fun ByteArray.hex(): String =
    joinToString("") { byte ->
        byte.toInt().and(0xff).toString(16).padStart(2, '0')
    }
