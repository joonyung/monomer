package cc.monomer.execution.driver.spi

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InlineResultBatchTest {
    @Test
    fun fromPayloadUsesTheSha256OfTheTransmittedArrowIpcBytes() {
        val payload = BinaryValue.copyOf(byteArrayOf(0, 1, 2, -1))

        val batch = InlineResultBatch.fromPayload(1u, payload)

        assertEquals(1u, batch.sequence)
        assertEquals(payload, batch.arrowIpc)
        assertEquals(
            "3d1f57c984978ef98a18378c8166c1cb8ede02c03eeb6aee7e2f121dfeee3e56",
            batch.sha256.value,
        )
    }

    @Test
    fun invalidSequencePayloadOrDigestIsRejected() {
        val payload = BinaryValue.copyOf(byteArrayOf(1, 2, 3))
        val digest = sha256(payload.copyBytes())

        assertFailsWith<IllegalArgumentException> {
            InlineResultBatch(0u, payload, digest)
        }
        assertFailsWith<IllegalArgumentException> {
            InlineResultBatch(1u, BinaryValue.copyOf(byteArrayOf()), digest)
        }
        assertFailsWith<IllegalArgumentException> {
            InlineResultBatch(1u, payload, Sha256Digest("0".repeat(64)))
        }
    }

    private fun sha256(bytes: ByteArray): Sha256Digest =
        Sha256Digest(
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) },
        )
}
