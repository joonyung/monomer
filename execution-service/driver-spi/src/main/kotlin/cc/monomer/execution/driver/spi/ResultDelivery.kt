package cc.monomer.execution.driver.spi

import java.security.MessageDigest

/**
 * One complete Arrow IPC file. Consumers parse each value independently; the
 * payloads are not fragments of one file and must not be concatenated.
 */
data class InlineResultBatch(
    val sequence: ULong,
    val arrowIpc: BinaryValue,
    val sha256: Sha256Digest,
) {
    init {
        require(sequence > 0uL)
        require(arrowIpc.size > 0)
        require(sha256 == digest(arrowIpc))
    }

    companion object {
        fun fromPayload(
            sequence: ULong,
            arrowIpc: BinaryValue,
        ): InlineResultBatch =
            InlineResultBatch(
                sequence = sequence,
                arrowIpc = arrowIpc,
                sha256 = digest(arrowIpc),
            )

        private fun digest(payload: BinaryValue): Sha256Digest =
            Sha256Digest(
                MessageDigest.getInstance("SHA-256")
                    .digest(payload.copyBytes())
                    .joinToString("") { byte ->
                        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                    },
            )
    }
}
