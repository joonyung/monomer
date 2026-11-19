package cc.monomer.execution.api

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Canonicalizes an artifact-upload query using the generic QueryRequest contract. */
object RawSqlExecutionRequestCanonicalizer {
    fun canonicalDigest(
        request: RawSqlExecutionRequest,
        objectKey: String,
    ): Sha256Digest {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeUtf8(DOMAIN)
            data.writeInt(request.schemaVersion.toInt())
            data.writeBytes(request.sql.toByteArray(Charsets.UTF_8))
            data.writeInt(request.parameters.size)
            request.parameters.forEach { parameter -> data.writeParameter(parameter) }
            data.writeInt(ARTIFACT_UPLOAD_TAG)
            data.writeUtf8(objectKey)
            data.writeLong(request.maximumResultRows.toLong())
            data.writeLong(request.maximumArtifactBytes.toLong())
            data.writeInt(request.maximumRecordBatchRows)
            data.writeInt(request.maximumRecordBatchBytes)
            data.writeInt(request.maximumCellBytes)
            data.writeInt(request.maximumMultipartPartBytes)
            // ExecutableRequestAssignmentCodec.requireSnapshotBinding rechecks persisted pre-deploy digests.
            val hasNames = request.parameters.any { it.name.isNotEmpty() }
            if (request.validateRawSqlBeforeRun || request.expectedColumnNames.isNotEmpty() || hasNames) {
                data.writeBoolean(request.validateRawSqlBeforeRun)
                data.writeInt(request.expectedColumnNames.size)
                request.expectedColumnNames.forEach { data.writeUtf8(it) }
            }
            if (hasNames) {
                data.writeInt(request.parameters.size)
                request.parameters.forEach { data.writeUtf8(it.name) }
            }
        }
        return digest(output.toByteArray())
    }

    fun isCanonical(
        request: RawSqlExecutionRequest,
        objectKey: String,
    ): Boolean = request.canonicalRequestDigest == canonicalDigest(request, objectKey)

    private fun DataOutputStream.writeParameter(parameter: QueryParameterValue) {
        when (parameter) {
            is QueryParameterValue.Text -> {
                writeInt(TEXT_TAG)
                writeUtf8(parameter.value)
            }
            is QueryParameterValue.Integer64 -> {
                writeInt(INTEGER64_TAG)
                writeLong(parameter.value)
            }
            is QueryParameterValue.Float64 -> {
                writeInt(FLOAT64_TAG)
                writeLong(java.lang.Double.doubleToRawLongBits(parameter.value))
            }
            is QueryParameterValue.BooleanValue -> {
                writeInt(BOOLEAN_TAG)
                writeBoolean(parameter.value)
            }
            is QueryParameterValue.Bytes -> {
                writeInt(BYTES_TAG)
                writeBytes(parameter.value.copyBytes())
            }
            is QueryParameterValue.Decimal -> {
                writeInt(DECIMAL_TAG)
                writeUtf8(parameter.value)
            }
            is QueryParameterValue.Date -> {
                writeInt(DATE_TAG)
                writeUtf8(parameter.value.toString())
            }
            is QueryParameterValue.Timestamp -> {
                writeInt(TIMESTAMP_TAG)
                writeUtf8(parameter.value.toString())
            }
            is QueryParameterValue.Null -> {
                writeInt(NULL_TAG)
                writeInt(
                    when (parameter.type) {
                        NullQueryParameterType.TEXT -> TEXT_TAG
                        NullQueryParameterType.INTEGER64 -> INTEGER64_TAG
                        NullQueryParameterType.FLOAT64 -> FLOAT64_TAG
                        NullQueryParameterType.BOOLEAN -> BOOLEAN_TAG
                        NullQueryParameterType.BYTES -> BYTES_TAG
                        NullQueryParameterType.DECIMAL -> DECIMAL_TAG
                        NullQueryParameterType.DATE -> DATE_TAG
                        NullQueryParameterType.TIMESTAMP -> TIMESTAMP_TAG
                    },
                )
            }
        }
    }

    private fun DataOutputStream.writeUtf8(value: String) {
        writeBytes(value.toByteArray(Charsets.UTF_8))
    }

    private fun DataOutputStream.writeBytes(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

    private fun digest(bytes: ByteArray): Sha256Digest = Sha256Digest.of(bytes)

    private const val DOMAIN = "monomer.execution.query-request.v1"
    private const val TEXT_TAG = 1
    private const val INTEGER64_TAG = 2
    private const val FLOAT64_TAG = 3
    private const val BOOLEAN_TAG = 4
    private const val BYTES_TAG = 5
    private const val NULL_TAG = 6
    private const val DECIMAL_TAG = 7
    private const val DATE_TAG = 8
    private const val TIMESTAMP_TAG = 9
    private const val ARTIFACT_UPLOAD_TAG = 11
}
