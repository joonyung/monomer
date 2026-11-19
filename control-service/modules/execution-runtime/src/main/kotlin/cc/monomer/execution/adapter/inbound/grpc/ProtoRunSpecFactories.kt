package cc.monomer.execution.adapter.inbound.grpc

import cc.monomer.execution.protocol.v1.ArtifactUploadTarget
import cc.monomer.execution.protocol.v1.InlineStreamTarget
import cc.monomer.execution.protocol.v1.NullQueryParameter
import cc.monomer.execution.protocol.v1.QueryParameter
import cc.monomer.execution.protocol.v1.QueryParameterType
import cc.monomer.execution.protocol.v1.QueryRequest
import cc.monomer.execution.api.BinaryValue
import cc.monomer.execution.api.ConnectorRuntimeBindingSpec
import cc.monomer.execution.api.InlineQueryRunSpec
import cc.monomer.execution.api.InlineQueryRunSpecCommand
import cc.monomer.execution.api.InlineQueryRunSpecFactory
import cc.monomer.execution.api.QueryParameterValue
import cc.monomer.execution.api.NullQueryParameterType
import cc.monomer.execution.api.RawSqlRunSpec
import cc.monomer.execution.api.RawSqlRunSpecCommand
import cc.monomer.execution.api.RawSqlRunSpecFactory
import cc.monomer.execution.api.ResultSinkBinding
import cc.monomer.execution.api.ResultSinkBindingCanonicalizer
import cc.monomer.execution.api.ResultSinkKind
import cc.monomer.execution.api.RunRuntimeBindings
import cc.monomer.execution.api.Sha256Digest
import com.google.protobuf.ByteString
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

class ProtoRawSqlRunSpecFactory : RawSqlRunSpecFactory {
    override fun create(command: RawSqlRunSpecCommand): RawSqlRunSpec {
        val objectKey = command.objectKey
        val queryRequest =
            QueryRequest.newBuilder()
                .setSchemaVersion(1)
                .setSql(command.request.sql)
                .setValidateRawSqlBeforeRun(command.request.validateRawSqlBeforeRun)
                .addAllExpectedColumnNames(command.request.expectedColumnNames)
                .addAllParameters(command.request.parameters.map(QueryParameterValue::toProto))
                .setArtifactUpload(
                    ArtifactUploadTarget.newBuilder()
                        .setObjectKey(objectKey)
                        .setMaximumArtifactRows(command.request.maximumResultRows.toLong())
                        .setMaximumArtifactBytes(command.request.maximumArtifactBytes.toLong())
                        .setMaximumRecordBatchRows(command.request.maximumRecordBatchRows)
                        .setMaximumRecordBatchBytes(command.request.maximumRecordBatchBytes.toLong())
                        .setMaximumCellBytes(command.request.maximumCellBytes.toLong())
                        .setMaximumMultipartPartBytes(
                            command.request.maximumMultipartPartBytes.toLong(),
                        )
                        .build(),
                )
                .build()
        val canonicalDigest = ProtoQueryRequestCanonicalizer.canonicalDigest(queryRequest)
        require(command.request.canonicalRequestDigest == canonicalDigest)
        return RawSqlRunSpec(
            canonicalRequestDigest = canonicalDigest,
            runtimeBindings =
                command.connector.toRuntimeBindings(
                    ResultSinkBinding(
                        schemaVersion = 2u,
                        kind = ResultSinkKind.ARTIFACT_UPLOAD,
                        objectKey = objectKey,
                        maximumArtifactRows = command.request.maximumResultRows,
                        maximumArtifactBytes = command.request.maximumArtifactBytes,
                        expiresAt = command.deadline,
                        maximumRecordBatchRows = command.request.maximumRecordBatchRows,
                        maximumFrameBytes = command.request.maximumRecordBatchBytes,
                        maximumCellBytes = command.request.maximumCellBytes,
                        maximumMultipartPartBytes = command.request.maximumMultipartPartBytes,
                        bindingDigest = Sha256Digest("0".repeat(64)),
                    ).let { binding ->
                        binding.copy(bindingDigest = ResultSinkBindingCanonicalizer.digest(binding))
                    },
                ),
            assignmentTemplate = BinaryValue.copyOf(queryRequest.toByteArray()),
        )
    }
}

class ProtoInlineQueryRunSpecFactory : InlineQueryRunSpecFactory {
    override fun create(command: InlineQueryRunSpecCommand): InlineQueryRunSpec {
        val queryRequest =
            QueryRequest.newBuilder()
                .setSchemaVersion(1)
                .setSql(command.sql)
                .addAllParameters(command.parameters.map(QueryParameterValue::toProto))
                .setInlineStream(
                    InlineStreamTarget.newBuilder()
                        .setMaximumRecordBatchRows(command.maximumRecordBatchRows)
                        .setMaximumRecordBatchBytes(command.maximumRecordBatchBytes.toLong())
                        .setMaximumCellBytes(command.maximumCellBytes.toLong())
                        .build(),
                )
                .build()
        val canonicalDigest = ProtoQueryRequestCanonicalizer.canonicalDigest(queryRequest)
        val unboundBinding =
            ResultSinkBinding(
                schemaVersion = 2u,
                kind = ResultSinkKind.INLINE_STREAM,
                objectKey = null,
                maximumArtifactRows = null,
                maximumArtifactBytes = null,
                expiresAt = null,
                maximumRecordBatchRows = command.maximumRecordBatchRows,
                maximumFrameBytes = command.maximumRecordBatchBytes,
                maximumCellBytes = command.maximumCellBytes,
                maximumMultipartPartBytes = null,
                bindingDigest = Sha256Digest("0".repeat(64)),
            )
        val binding =
            unboundBinding.copy(bindingDigest = ResultSinkBindingCanonicalizer.digest(unboundBinding))
        return InlineQueryRunSpec(
            canonicalRequestDigest = canonicalDigest,
            runtimeBindings = command.connector.toRuntimeBindings(binding),
            assignmentTemplate = BinaryValue.copyOf(queryRequest.toByteArray()),
        )
    }
}

private fun ConnectorRuntimeBindingSpec.toRuntimeBindings(
    resultSink: ResultSinkBinding,
): RunRuntimeBindings =
    RunRuntimeBindings(
        connectorBindingDigest = connectorBindingDigest(),
        credentialVersion = managedCredential.credentialVersion,
        credentialKeyVersion = managedCredential.keyVersion,
        credentialBindingDigest = managedCredential.envelopeDigest,
        resultSink = resultSink,
    )

private fun ConnectorRuntimeBindingSpec.connectorBindingDigest(): Sha256Digest {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(connectorId.toString().toByteArray())
    digest.update(connectorType.name.lowercase().toByteArray())
    properties.entries.sortedBy { entry -> entry.key }.forEach { (name, value) ->
        digest.update(name.toByteArray())
        digest.update(value.toString().toByteArray())
    }
    digest.update(managedCredential.envelopeDigest.value.toByteArray())
    return Sha256Digest.fromDigestBytes(digest.digest())
}

private fun QueryParameterValue.toProto(): QueryParameter {
    val builder = QueryParameter.newBuilder().setName(name)
    when (this) {
        is QueryParameterValue.Text -> builder.stringValue = value
        is QueryParameterValue.Integer64 -> builder.int64Value = value
        is QueryParameterValue.Float64 -> builder.float64Value = value
        is QueryParameterValue.BooleanValue -> builder.booleanValue = value
        is QueryParameterValue.Decimal -> builder.decimalValue = value
        is QueryParameterValue.Date -> builder.dateValue = value.toString()
        is QueryParameterValue.Timestamp -> builder.timestampValue = value.toString()
        is QueryParameterValue.Bytes -> builder.bytesValue = ByteString.copyFrom(value.copyBytes())
        is QueryParameterValue.Null ->
            builder.nullValue = NullQueryParameter.newBuilder().setType(type.toProto()).build()
    }
    return builder.build()
}

private fun NullQueryParameterType.toProto(): QueryParameterType =
    when (this) {
        NullQueryParameterType.TEXT -> QueryParameterType.QUERY_PARAMETER_TYPE_TEXT
        NullQueryParameterType.INTEGER64 -> QueryParameterType.QUERY_PARAMETER_TYPE_INTEGER64
        NullQueryParameterType.FLOAT64 -> QueryParameterType.QUERY_PARAMETER_TYPE_FLOAT64
        NullQueryParameterType.BOOLEAN -> QueryParameterType.QUERY_PARAMETER_TYPE_BOOLEAN
        NullQueryParameterType.BYTES -> QueryParameterType.QUERY_PARAMETER_TYPE_BYTES
        NullQueryParameterType.DECIMAL -> QueryParameterType.QUERY_PARAMETER_TYPE_DECIMAL
        NullQueryParameterType.DATE -> QueryParameterType.QUERY_PARAMETER_TYPE_DATE
        NullQueryParameterType.TIMESTAMP -> QueryParameterType.QUERY_PARAMETER_TYPE_TIMESTAMP
    }

internal object ProtoQueryRequestCanonicalizer {
    fun canonicalDigest(request: QueryRequest): Sha256Digest {
        require(request.schemaVersion == 1)
        require(request.sql.isNotBlank())
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeUtf8("monomer.execution.query-request.v1")
            data.writeInt(request.schemaVersion)
            data.writeBytes(request.sql.toByteArray(Charsets.UTF_8))
            data.writeInt(request.parametersCount)
            request.parametersList.forEach { parameter -> data.writeParameter(parameter) }
            when (request.deliveryCase) {
                QueryRequest.DeliveryCase.INLINE_STREAM -> {
                    data.writeInt(INLINE_STREAM_TAG)
                    data.writeInt(request.inlineStream.maximumRecordBatchRows)
                    data.writeInt(request.inlineStream.maximumRecordBatchBytes.toIntParameter())
                    data.writeInt(request.inlineStream.maximumCellBytes.toIntParameter())
                }
                QueryRequest.DeliveryCase.ARTIFACT_UPLOAD -> {
                    data.writeInt(ARTIFACT_UPLOAD_TAG)
                    val target = request.artifactUpload
                    data.writeUtf8(target.objectKey)
                    data.writeLong(target.maximumArtifactRows)
                    data.writeLong(target.maximumArtifactBytes)
                    data.writeInt(target.maximumRecordBatchRows)
                    data.writeInt(target.maximumRecordBatchBytes.toIntParameter())
                    data.writeInt(target.maximumCellBytes.toIntParameter())
                    data.writeInt(target.maximumMultipartPartBytes.toIntParameter())
                }
                QueryRequest.DeliveryCase.DELIVERY_NOT_SET,
                null,
                -> error("query request delivery is required")
            }
            // ExecutableRequestAssignmentCodec.requireSnapshotBinding rechecks persisted pre-deploy digests.
            val hasNames = request.parametersList.any { it.name.isNotEmpty() }
            if (request.validateRawSqlBeforeRun || request.expectedColumnNamesCount > 0 || hasNames) {
                data.writeBoolean(request.validateRawSqlBeforeRun)
                data.writeInt(request.expectedColumnNamesCount)
                request.expectedColumnNamesList.forEach { data.writeUtf8(it) }
            }
            if (hasNames) {
                data.writeInt(request.parametersCount)
                request.parametersList.forEach { data.writeUtf8(it.name) }
            }
        }
        return Sha256Digest.of(output.toByteArray())
    }

    private fun Long.toIntParameter(): Int {
        require(this in 1..Int.MAX_VALUE)
        return toInt()
    }

    private fun DataOutputStream.writeParameter(parameter: QueryParameter) {
        when (parameter.valueCase) {
            QueryParameter.ValueCase.STRING_VALUE -> {
                writeInt(TEXT_TAG)
                writeUtf8(parameter.stringValue)
            }
            QueryParameter.ValueCase.INT64_VALUE -> {
                writeInt(INTEGER64_TAG)
                writeLong(parameter.int64Value)
            }
            QueryParameter.ValueCase.FLOAT64_VALUE -> {
                writeInt(FLOAT64_TAG)
                writeLong(java.lang.Double.doubleToRawLongBits(parameter.float64Value))
            }
            QueryParameter.ValueCase.BOOLEAN_VALUE -> {
                writeInt(BOOLEAN_TAG)
                writeBoolean(parameter.booleanValue)
            }
            QueryParameter.ValueCase.BYTES_VALUE -> {
                writeInt(BYTES_TAG)
                writeBytes(parameter.bytesValue.toByteArray())
            }
            QueryParameter.ValueCase.DECIMAL_VALUE -> {
                writeInt(DECIMAL_TAG)
                writeUtf8(parameter.decimalValue)
            }
            QueryParameter.ValueCase.DATE_VALUE -> {
                writeInt(DATE_TAG)
                writeUtf8(parameter.dateValue)
            }
            QueryParameter.ValueCase.TIMESTAMP_VALUE -> {
                writeInt(TIMESTAMP_TAG)
                writeUtf8(parameter.timestampValue)
            }
            QueryParameter.ValueCase.NULL_VALUE -> {
                writeInt(NULL_TAG)
                writeInt(when (parameter.nullValue.type) {
                    QueryParameterType.QUERY_PARAMETER_TYPE_DECIMAL -> DECIMAL_TAG
                    QueryParameterType.QUERY_PARAMETER_TYPE_DATE -> DATE_TAG
                    QueryParameterType.QUERY_PARAMETER_TYPE_TIMESTAMP -> TIMESTAMP_TAG
                    else -> parameter.nullValue.type.number
                })
            }
            QueryParameter.ValueCase.VALUE_NOT_SET,
            null,
            -> error("query parameter value is required")
        }
    }

    private fun DataOutputStream.writeUtf8(value: String) {
        writeBytes(value.toByteArray(Charsets.UTF_8))
    }

    private fun DataOutputStream.writeBytes(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

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
