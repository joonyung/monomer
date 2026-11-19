package cc.monomer.execution.adapter.inbound.grpc

import cc.monomer.execution.protocol.v1.QueryRequest
import cc.monomer.execution.protocol.v1.QueryParameter
import cc.monomer.execution.api.ExecutionRunSnapshot
import cc.monomer.execution.api.ExecutableRequestKind
import com.google.protobuf.InvalidProtocolBufferException
import cc.monomer.execution.api.ResultSinkKind
import cc.monomer.execution.api.BinaryValue
import cc.monomer.execution.application.RunDispatchAssignmentCodec

class ExecutableRequestAssignmentCodec : RunDispatchAssignmentCodec {
    override fun canonicalizeTemplate(assignmentTemplate: BinaryValue): BinaryValue {
        val request = QueryRequest.parseFrom(assignmentTemplate.copyBytes())
        require(request.schemaVersion == 1)
        require(request.sql.isNotBlank())
        require(request.deliveryCase != QueryRequest.DeliveryCase.DELIVERY_NOT_SET)
        require(
            request.parametersList.all { parameter ->
                parameter.valueCase != QueryParameter.ValueCase.VALUE_NOT_SET
            },
        )
        require(
            when (request.deliveryCase) {
                QueryRequest.DeliveryCase.INLINE_STREAM ->
                    request.inlineStream.maximumRecordBatchRows > 0 &&
                        request.inlineStream.maximumRecordBatchBytes > 0 &&
                        request.inlineStream.maximumCellBytes > 0
                QueryRequest.DeliveryCase.ARTIFACT_UPLOAD ->
                    request.artifactUpload.objectKey.isNotBlank() &&
                        request.artifactUpload.maximumArtifactRows > 0 &&
                        request.artifactUpload.maximumArtifactBytes > 0 &&
                        request.artifactUpload.maximumRecordBatchRows > 0 &&
                        request.artifactUpload.maximumRecordBatchBytes > 0 &&
                        request.artifactUpload.maximumCellBytes > 0 &&
                        request.artifactUpload.maximumMultipartPartBytes >=
                            request.artifactUpload.maximumRecordBatchBytes
                QueryRequest.DeliveryCase.DELIVERY_NOT_SET,
                null,
                -> false
            },
        )
        return BinaryValue.copyOf(request.toByteArray())
    }

    override fun readSql(canonicalTemplate: BinaryValue, snapshot: ExecutionRunSnapshot): String? =
        try {
            require(canonicalizeTemplate(canonicalTemplate) == canonicalTemplate)
            val request = QueryRequest.parseFrom(canonicalTemplate.copyBytes())
            requireSnapshotBinding(request, snapshot)
            request.sql
        } catch (_: InvalidProtocolBufferException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun requireSnapshotBinding(request: QueryRequest, snapshot: ExecutionRunSnapshot) {
        require(snapshot.requestKind == ExecutableRequestKind.QUERY)
        require(snapshot.canonicalRequestDigest == ProtoQueryRequestCanonicalizer.canonicalDigest(request))
        val sink = snapshot.runtimeBindings.resultSink
        when (request.deliveryCase) {
            QueryRequest.DeliveryCase.INLINE_STREAM -> {
                require(sink.kind == ResultSinkKind.INLINE_STREAM)
                require(sink.objectKey == null)
                require(sink.maximumRecordBatchRows == request.inlineStream.maximumRecordBatchRows)
                require(sink.maximumFrameBytes == request.inlineStream.maximumRecordBatchBytes.toInt())
                require(sink.maximumCellBytes == request.inlineStream.maximumCellBytes.toInt())
            }
            QueryRequest.DeliveryCase.ARTIFACT_UPLOAD -> {
                val target = request.artifactUpload
                require(sink.kind == ResultSinkKind.ARTIFACT_UPLOAD)
                require(sink.objectKey == target.objectKey)
                require(sink.maximumArtifactRows == target.maximumArtifactRows.toULong())
                require(sink.maximumArtifactBytes == target.maximumArtifactBytes.toULong())
                require(sink.maximumRecordBatchRows == target.maximumRecordBatchRows)
                require(sink.maximumFrameBytes == target.maximumRecordBatchBytes.toInt())
                require(sink.maximumCellBytes == target.maximumCellBytes.toInt())
                require(sink.maximumMultipartPartBytes == target.maximumMultipartPartBytes.toInt())
            }
            QueryRequest.DeliveryCase.DELIVERY_NOT_SET,
            null,
            -> error("query request delivery is required")
        }
    }
}
