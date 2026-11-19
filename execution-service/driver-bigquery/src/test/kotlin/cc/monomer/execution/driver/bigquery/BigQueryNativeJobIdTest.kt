package cc.monomer.execution.driver.bigquery

import cc.monomer.execution.driver.spi.RunIdentity
import cc.monomer.execution.driver.spi.Sha256Digest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BigQueryNativeJobIdTest {
    @Test
    fun sameRunRequestAndConnectorAlwaysProduceTheSameValidJobId() {
        val run = RunIdentity(bigQueryRunId)

        val first =
            BigQueryNativeJobId.from(run, requestDigest, bigQueryConnectorId)
        val second =
            BigQueryNativeJobId.from(run, requestDigest, bigQueryConnectorId)

        assertEquals(first, second)
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals(72, first.length)
    }

    @Test
    fun requestOrConnectorTamperingProducesADifferentNativeIdentity() {
        val run = RunIdentity(bigQueryRunId)

        assertNotEquals(
            BigQueryNativeJobId.from(run, requestDigest, bigQueryConnectorId),
            BigQueryNativeJobId.from(
                run,
                Sha256Digest("b".repeat(64)),
                bigQueryConnectorId,
            ),
        )
        assertNotEquals(
            BigQueryNativeJobId.from(run, requestDigest, bigQueryConnectorId),
            BigQueryNativeJobId.from(
                run,
                requestDigest,
                alternateBigQueryConnectorId,
            ),
        )
    }

    private companion object {
        val requestDigest = Sha256Digest("a".repeat(64))
    }
}
