package cc.monomer.execution

import cc.monomer.execution.api.BinaryValue
import cc.monomer.execution.api.ExecutionRunEvent
import cc.monomer.execution.api.Sha256Digest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExecutionRunPortContractTest {
    @Test
    fun `inline result batch requires the exact payload digest`() {
        val payload = BinaryValue.copyOf(byteArrayOf(0, 1, 2, 255.toByte()))
        val batch = ExecutionRunEvent.InlineResultBatch.fromPayload(1u, payload)

        assertEquals(
            "3d1f57c984978ef98a18378c8166c1cb8ede02c03eeb6aee7e2f121dfeee3e56",
            batch.sha256.value,
        )
        assertFailsWith<IllegalArgumentException> {
            ExecutionRunEvent.InlineResultBatch(
                sequence = 1u,
                arrowIpc = payload,
                sha256 = Sha256Digest("0".repeat(64)),
            )
        }
    }
}
