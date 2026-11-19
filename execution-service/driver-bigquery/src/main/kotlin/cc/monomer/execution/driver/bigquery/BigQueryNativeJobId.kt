package cc.monomer.execution.driver.bigquery

import cc.monomer.execution.driver.spi.RunIdentity
import cc.monomer.execution.driver.spi.ConnectorId
import cc.monomer.execution.driver.spi.Sha256Digest
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

object BigQueryNativeJobId {
    private const val PREFIX = "monomer_"
    private const val IDENTITY_DOMAIN = "monomer.execution.bigquery.job.v2"

    fun from(
        run: RunIdentity,
        canonicalRequestDigest: Sha256Digest,
        connectorId: ConnectorId,
    ): String {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeUtf8(IDENTITY_DOMAIN)
            data.writeUtf8(run.runId.toString())
            data.writeUtf8(canonicalRequestDigest.value)
            data.writeUtf8(connectorId.toString())
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(output.toByteArray())
        return PREFIX + digest.toHex()
    }

    private fun DataOutputStream.writeUtf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
