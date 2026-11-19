package cc.monomer.execution.api

import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

object ResultSinkBindingCanonicalizer {
    fun digest(binding: ResultSinkBinding): Sha256Digest {
        val messageDigest = MessageDigest.getInstance("SHA-256")
        DataOutputStream(
            DigestOutputStream(OutputStream.nullOutputStream(), messageDigest),
        ).use { data ->
            data.writeUtf8(RESULT_SINK_DOMAIN)
            data.writeInt(binding.schemaVersion.toInt())
            data.writeInt(binding.kind.canonicalCode())
            data.writeNullableUtf8(binding.objectKey)
            data.writeNullableLong(binding.maximumArtifactRows?.toLong())
            data.writeNullableLong(binding.maximumArtifactBytes?.toLong())
            data.writeNullableLong(binding.expiresAt?.toEpochMilli())
            data.writeInt(binding.maximumRecordBatchRows)
            data.writeInt(binding.maximumFrameBytes)
            data.writeInt(binding.maximumCellBytes)
            data.writeNullableInt(binding.maximumMultipartPartBytes)
        }
        return Sha256Digest.fromDigestBytes(messageDigest.digest())
    }

    private fun ResultSinkKind.canonicalCode(): Int =
        when (this) {
            ResultSinkKind.ARTIFACT_UPLOAD -> 1
            ResultSinkKind.INLINE_STREAM -> 2
        }

    private fun DataOutputStream.writeUtf8(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataOutputStream.writeNullableUtf8(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUtf8(value)
    }

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataOutputStream.writeNullableInt(value: Int?) {
        writeBoolean(value != null)
        if (value != null) writeInt(value)
    }

    private const val RESULT_SINK_DOMAIN = "monomer.execution.result-sink-binding.v3"
}
