package cc.monomer.execution.driver.spi

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

object QueryRequestCanonicalizer {
    fun canonicalDigest(request: QueryRequest): Sha256Digest {
        require(request.schemaVersion == SCHEMA_VERSION) {
            "query request schema is unsupported"
        }
        requireCanonicalQueryText(request.sql)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeUtf8(DOMAIN)
            data.writeInt(request.schemaVersion.toInt())
            data.writeBytes(request.sql.toByteArray(Charsets.UTF_8))
            data.writeInt(request.parameters.size)
            request.parameters.forEach { parameter -> data.writeParameter(parameter) }
            when (val target = request.resultTarget) {
                is QueryResultTarget.InlineStream -> {
                    data.writeInt(INLINE_STREAM_TAG)
                    data.writeInt(target.maximumRecordBatchRows)
                    data.writeInt(target.maximumRecordBatchBytes)
                    data.writeInt(target.maximumCellBytes)
                }

                is QueryResultTarget.ArtifactUpload -> {
                    data.writeInt(ARTIFACT_UPLOAD_TAG)
                    data.writeUtf8(target.objectKey)
                    data.writeLong(target.maximumArtifactRows.toLong())
                    data.writeLong(target.maximumArtifactBytes.toLong())
                    data.writeInt(target.maximumRecordBatchRows)
                    data.writeInt(target.maximumRecordBatchBytes)
                    data.writeInt(target.maximumCellBytes)
                    data.writeInt(target.maximumMultipartPartBytes)
                }
            }
            // Control ExecutableRequestAssignmentCodec rechecks persisted pre-deploy digests with this encoding.
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
        return Sha256Digest(
            MessageDigest.getInstance("SHA-256")
                .digest(output.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) },
        )
    }

    fun requireCanonical(request: QueryRequest) {
        require(request.canonicalRequestDigest == canonicalDigest(request)) {
            "query request canonical digest does not match its exact execution payload"
        }
    }

    private fun DataOutputStream.writeParameter(parameter: QueryParameter) {
        when (parameter) {
            is QueryParameter.Text -> {
                requireCanonicalQueryText(parameter.value)
                writeInt(TEXT_TAG)
                writeUtf8(parameter.value)
            }

            is QueryParameter.Integer64 -> {
                writeInt(INTEGER64_TAG)
                writeLong(parameter.value)
            }

            is QueryParameter.Float64 -> {
                writeInt(FLOAT64_TAG)
                writeLong(java.lang.Double.doubleToRawLongBits(parameter.value))
            }

            is QueryParameter.BooleanValue -> {
                writeInt(BOOLEAN_TAG)
                writeBoolean(parameter.value)
            }

            is QueryParameter.Bytes -> {
                writeInt(BYTES_TAG)
                writeBytes(parameter.value.copyBytes())
            }

            is QueryParameter.Decimal -> {
                writeInt(DECIMAL_TAG)
                writeUtf8(parameter.value)
            }
            is QueryParameter.Date -> {
                writeInt(DATE_TAG)
                writeUtf8(parameter.value.toString())
            }
            is QueryParameter.Timestamp -> {
                writeInt(TIMESTAMP_TAG)
                writeUtf8(parameter.value.toString())
            }
            is QueryParameter.Null -> {
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

    private const val DOMAIN = "monomer.execution.query-request.v1"
    private const val SCHEMA_VERSION: UInt = 1u
    private const val TEXT_TAG = 1
    private const val INTEGER64_TAG = 2
    private const val FLOAT64_TAG = 3
    private const val BOOLEAN_TAG = 4
    private const val BYTES_TAG = 5
    private const val NULL_TAG = 6
    private const val DECIMAL_TAG = 7
    private const val DATE_TAG = 8
    private const val TIMESTAMP_TAG = 9
    private const val INLINE_STREAM_TAG = 10
    private const val ARTIFACT_UPLOAD_TAG = 11
}

private fun requireCanonicalQueryText(value: String) {
    require(value.none { character -> character == '\u0000' }) {
        "query text must not contain NUL"
    }
    var index = 0
    while (index < value.length) {
        when {
            Character.isHighSurrogate(value[index]) -> {
                require(index + 1 < value.length && Character.isLowSurrogate(value[index + 1])) {
                    "query text must not contain unpaired surrogates"
                }
                index += 2
            }

            Character.isLowSurrogate(value[index]) ->
                require(false) { "query text must not contain unpaired surrogates" }

            else -> index += 1
        }
    }
}
