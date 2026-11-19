package cc.monomer.execution.application

import cc.monomer.execution.driver.spi.ArrowResultManifest
import cc.monomer.execution.driver.spi.BinaryValue
import cc.monomer.execution.driver.spi.ConnectorPropertyValue
import cc.monomer.execution.driver.spi.ConnectorId
import cc.monomer.execution.driver.spi.ConnectorRuntimeBinding
import cc.monomer.execution.driver.spi.ConnectorType
import cc.monomer.execution.driver.spi.InlineResultBatch as DriverInlineResultBatch
import cc.monomer.execution.driver.spi.NullQueryParameterType
import cc.monomer.execution.driver.spi.QueryParameter
import cc.monomer.execution.driver.spi.QueryRequest
import cc.monomer.execution.driver.spi.QueryRequestCanonicalizer
import cc.monomer.execution.driver.spi.QueryResultTarget
import cc.monomer.execution.driver.spi.RunAssignment
import cc.monomer.execution.driver.spi.Sha256Digest
import cc.monomer.execution.driver.spi.WorkspaceId
import cc.monomer.execution.protocol.v1.ControlAssignment
import cc.monomer.execution.protocol.v1.ValidateRawSqlRequest
import cc.monomer.execution.protocol.v1.InlineResultBatch as ProtoInlineResultBatch
import cc.monomer.execution.protocol.v1.ArrowResultManifest as ProtoArrowResultManifest

internal fun ControlAssignment.toDriverAssignment(): RunAssignment {
    require(unknownFields.asMap().isEmpty())
    require(runId.isNotBlank())
    require(hasConnector())
    require(hasRequest())
    require(deadlineEpochMillis > 0)
    return RunAssignment(
        workspaceId = WorkspaceId.parse(workspaceId),
        connector = connector.toDriverBinding(),
        request = request.toDriverRequest(),
        deadlineEpochMillis = deadlineEpochMillis,
    ).also { assignment ->
        cc.monomer.execution.driver.spi.requireQueryParameterNames(assignment.connector.connectorType, assignment.request.parameters)
    }
}

private fun cc.monomer.execution.protocol.v1.ConnectorRuntimeBinding.toDriverBinding():
    ConnectorRuntimeBinding {
    require(unknownFields.asMap().isEmpty())
    val mappedProperties =
        propertiesList.map { property ->
            require(property.unknownFields.asMap().isEmpty())
            require(property.name.isNotBlank())
            property.name to property.toDriverValue()
        }
    require(
        mappedProperties.map(Pair<String, ConnectorPropertyValue>::first).distinct().size ==
            mappedProperties.size,
    )
    return ConnectorRuntimeBinding(
        schemaVersion = schemaVersion.toUInt(),
        connectorId = ConnectorId.parse(connectorId),
        connectorType = connectorType.toDriverConnectorType(),
        properties = mappedProperties.toMap(),
    )
}

private fun cc.monomer.execution.protocol.v1.ConnectorRuntimeProperty.toDriverValue():
    ConnectorPropertyValue =
    when (valueCase) {
        cc.monomer.execution.protocol.v1.ConnectorRuntimeProperty.ValueCase.STRING_VALUE ->
            ConnectorPropertyValue.Text(stringValue)
        cc.monomer.execution.protocol.v1.ConnectorRuntimeProperty.ValueCase.INTEGER_VALUE ->
            ConnectorPropertyValue.Integer(integerValue)
        cc.monomer.execution.protocol.v1.ConnectorRuntimeProperty.ValueCase.BOOLEAN_VALUE ->
            ConnectorPropertyValue.BooleanValue(booleanValue)
        cc.monomer.execution.protocol.v1.ConnectorRuntimeProperty.ValueCase.STRING_LIST -> {
            require(stringList.unknownFields.asMap().isEmpty())
            ConnectorPropertyValue.TextList(stringList.valuesList.toList())
        }
        cc.monomer.execution.protocol.v1.ConnectorRuntimeProperty.ValueCase.VALUE_NOT_SET ->
            throw IllegalArgumentException("connector property value is missing")
    }

private fun String.toDriverConnectorType(): ConnectorType =
    when (uppercase()) {
        "POSTGRESQL" -> ConnectorType.POSTGRESQL
        "BIGQUERY" -> ConnectorType.BIGQUERY
        "SNOWFLAKE" -> ConnectorType.SNOWFLAKE
        "TRINO" -> ConnectorType.TRINO
        "REDSHIFT" -> ConnectorType.REDSHIFT
        "DATABRICKS" -> ConnectorType.DATABRICKS
        "DUCKDB" -> ConnectorType.DUCKDB
        else -> throw IllegalArgumentException("connector type is unsupported")
    }

private fun cc.monomer.execution.protocol.v1.QueryRequest.toDriverRequest(): QueryRequest {
    require(unknownFields.asMap().isEmpty())
    require(schemaVersion >= 1)
    require(sql.isNotBlank())
    val target =
        when (deliveryCase) {
            cc.monomer.execution.protocol.v1.QueryRequest.DeliveryCase.INLINE_STREAM ->
                QueryResultTarget.InlineStream(
                    maximumRecordBatchRows =
                        requirePositiveInt(inlineStream.maximumRecordBatchRows.toLong(), "maximum_record_batch_rows"),
                    maximumRecordBatchBytes =
                        requirePositiveInt(inlineStream.maximumRecordBatchBytes, "maximum_record_batch_bytes"),
                    maximumCellBytes =
                        requirePositiveInt(inlineStream.maximumCellBytes, "maximum_cell_bytes"),
                )
            cc.monomer.execution.protocol.v1.QueryRequest.DeliveryCase.ARTIFACT_UPLOAD -> {
                val artifact = artifactUpload
                QueryResultTarget.ArtifactUpload(
                    objectKey = artifact.objectKey,
                    maximumArtifactRows = requireUnsignedLong(artifact.maximumArtifactRows, "maximum_artifact_rows"),
                    maximumArtifactBytes = requireUnsignedLong(artifact.maximumArtifactBytes, "maximum_artifact_bytes"),
                    maximumRecordBatchRows =
                        requirePositiveInt(artifact.maximumRecordBatchRows.toLong(), "maximum_record_batch_rows"),
                    maximumRecordBatchBytes =
                        requirePositiveInt(artifact.maximumRecordBatchBytes, "maximum_record_batch_bytes"),
                    maximumCellBytes =
                        requirePositiveInt(artifact.maximumCellBytes, "maximum_cell_bytes"),
                    maximumMultipartPartBytes =
                        requirePositiveInt(artifact.maximumMultipartPartBytes, "maximum_multipart_part_bytes"),
                )
            }
            cc.monomer.execution.protocol.v1.QueryRequest.DeliveryCase.DELIVERY_NOT_SET ->
                throw IllegalArgumentException("query result delivery is missing")
        }
    val unsigned =
        QueryRequest(
            validateRawSqlBeforeRun = validateRawSqlBeforeRun,
            expectedColumnNames = expectedColumnNamesList.toList(),
            schemaVersion = schemaVersion.toUInt(),
            canonicalRequestDigest = PLACEHOLDER_DIGEST,
            sql = sql,
            parameters = parametersList.map { parameter -> parameter.toDriverParameter() },
            resultTarget = target,
        )
    return unsigned.copy(
        canonicalRequestDigest = QueryRequestCanonicalizer.canonicalDigest(unsigned),
    )
}

internal data class RawSqlValidationRequest(
    val connector: ConnectorRuntimeBinding,
    val sql: String,
    val parameters: List<QueryParameter>,
    val deadlineEpochMillis: Long,
)

internal fun ValidateRawSqlRequest.toDriverValidationRequest(): RawSqlValidationRequest {
    require(unknownFields.asMap().isEmpty())
    require(runId.isNotBlank())
    require(workspaceId.isNotBlank())
    require(hasConnector())
    require(sql.isNotBlank())
    require(deadlineEpochMillis > 0)
    return RawSqlValidationRequest(
        connector = connector.toDriverBinding(),
        sql = sql,
        parameters = parametersList.map { parameter -> parameter.toDriverParameter() },
        deadlineEpochMillis = deadlineEpochMillis,
    )
}

private fun cc.monomer.execution.protocol.v1.QueryParameter.toDriverParameter(): QueryParameter {
    require(unknownFields.asMap().isEmpty())
    return when (valueCase) {
        cc.monomer.execution.protocol.v1.QueryParameter.ValueCase.STRING_VALUE ->
            QueryParameter.Text(stringValue, name = name)
        cc.monomer.execution.protocol.v1.QueryParameter.ValueCase.INT64_VALUE ->
            QueryParameter.Integer64(int64Value, name = name)
        cc.monomer.execution.protocol.v1.QueryParameter.ValueCase.FLOAT64_VALUE ->
            QueryParameter.Float64(float64Value, name = name)
        cc.monomer.execution.protocol.v1.QueryParameter.ValueCase.BOOLEAN_VALUE ->
            QueryParameter.BooleanValue(booleanValue, name = name)
        cc.monomer.execution.protocol.v1.QueryParameter.ValueCase.BYTES_VALUE ->
            QueryParameter.Bytes(BinaryValue.copyOf(bytesValue.toByteArray()), name = name)
        cc.monomer.execution.protocol.v1.QueryParameter.ValueCase.DECIMAL_VALUE ->
            QueryParameter.Decimal(decimalValue, name = name)
        cc.monomer.execution.protocol.v1.QueryParameter.ValueCase.DATE_VALUE ->
            QueryParameter.Date(java.time.LocalDate.parse(dateValue).also { require(it.toString() == dateValue) }, name = name)
        cc.monomer.execution.protocol.v1.QueryParameter.ValueCase.TIMESTAMP_VALUE ->
            QueryParameter.Timestamp(java.time.Instant.parse(timestampValue).also { require(it.toString() == timestampValue) }, name = name)
        cc.monomer.execution.protocol.v1.QueryParameter.ValueCase.NULL_VALUE -> {
            require(nullValue.unknownFields.asMap().isEmpty())
            QueryParameter.Null(
                when (nullValue.type) {
                    cc.monomer.execution.protocol.v1.QueryParameterType.QUERY_PARAMETER_TYPE_TEXT ->
                        NullQueryParameterType.TEXT
                    cc.monomer.execution.protocol.v1.QueryParameterType.QUERY_PARAMETER_TYPE_INTEGER64 ->
                        NullQueryParameterType.INTEGER64
                    cc.monomer.execution.protocol.v1.QueryParameterType.QUERY_PARAMETER_TYPE_FLOAT64 ->
                        NullQueryParameterType.FLOAT64
                    cc.monomer.execution.protocol.v1.QueryParameterType.QUERY_PARAMETER_TYPE_BOOLEAN ->
                        NullQueryParameterType.BOOLEAN
                    cc.monomer.execution.protocol.v1.QueryParameterType.QUERY_PARAMETER_TYPE_BYTES ->
                        NullQueryParameterType.BYTES
                    cc.monomer.execution.protocol.v1.QueryParameterType.QUERY_PARAMETER_TYPE_DECIMAL ->
                        NullQueryParameterType.DECIMAL
                    cc.monomer.execution.protocol.v1.QueryParameterType.QUERY_PARAMETER_TYPE_DATE ->
                        NullQueryParameterType.DATE
                    cc.monomer.execution.protocol.v1.QueryParameterType.QUERY_PARAMETER_TYPE_TIMESTAMP ->
                        NullQueryParameterType.TIMESTAMP
                    cc.monomer.execution.protocol.v1.QueryParameterType.QUERY_PARAMETER_TYPE_UNSPECIFIED,
                    cc.monomer.execution.protocol.v1.QueryParameterType.UNRECOGNIZED,
                    -> throw IllegalArgumentException("null query parameter type is invalid")
                },
                name = name,
            )
        }
        cc.monomer.execution.protocol.v1.QueryParameter.ValueCase.VALUE_NOT_SET ->
            throw IllegalArgumentException("query parameter is missing")
    }
}

internal fun ArrowResultManifest.toProto(): ProtoArrowResultManifest =
    ProtoArrowResultManifest.newBuilder()
        .setObjectKey(objectKey)
        .setByteSize(byteSize.toLong())
        .setObjectEtag(objectEtag)
        .setRowCount(rowCount.toLong())
        .setColumnCount(columnCount)
        .setMediaType(mediaType)
        .build()

internal fun DriverInlineResultBatch.toProto(): ProtoInlineResultBatch =
    ProtoInlineResultBatch.newBuilder()
        .setSequence(sequence.toLong())
        .setArrowIpc(com.google.protobuf.ByteString.copyFrom(arrowIpc.copyBytes()))
        .setSha256(sha256.value)
        .build()

private fun requireUnsignedLong(value: Long, fieldName: String): ULong {
    require(value >= 0) { "$fieldName must not be negative" }
    return value.toULong()
}

private fun requirePositiveInt(value: Long, fieldName: String): Int {
    require(value in 1..Int.MAX_VALUE.toLong()) {
        "$fieldName must be in 1..${Int.MAX_VALUE}"
    }
    return value.toInt()
}

private val PLACEHOLDER_DIGEST = Sha256Digest("0".repeat(64))
