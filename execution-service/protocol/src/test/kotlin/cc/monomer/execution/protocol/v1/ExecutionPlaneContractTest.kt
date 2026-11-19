package cc.monomer.execution.protocol.v1

import com.google.protobuf.Descriptors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExecutionPlaneContractTest {
    private val descriptor = ExecutionPlaneProto.getDescriptor()

    @Test
    fun decimalDateAndTimestampAreAppendOnlyTypedBindFields() {
        val descriptor = ExecutionPlaneProto.getDescriptor()
        val parameter = assertNotNull(descriptor.findMessageTypeByName("QueryParameter"))
        assertEquals(mapOf("string_value" to 1, "int64_value" to 2, "float64_value" to 3,
            "boolean_value" to 4, "bytes_value" to 5, "null_value" to 6,
            "decimal_value" to 7, "date_value" to 8, "timestamp_value" to 9, "name" to 10),
            parameter.fields.associate { it.name to it.number })
        assertNull(parameter.findFieldByName("name").containingOneof)
        assertEquals("", QueryParameter.getDefaultInstance().name)
        val type = assertNotNull(descriptor.findEnumTypeByName("QueryParameterType"))
        assertEquals(6, type.findValueByName("QUERY_PARAMETER_TYPE_DECIMAL").number)
        assertEquals(7, type.findValueByName("QUERY_PARAMETER_TYPE_DATE").number)
        assertEquals(8, type.findValueByName("QUERY_PARAMETER_TYPE_TIMESTAMP").number)
    }

    @Test
    fun serviceIsControlInitiatedRun() {
        val service = assertNotNull(descriptor.findServiceByName("ExecutionPlane"))
        val method = assertNotNull(service.findMethodByName("Run"))

        assertEquals("Run", method.name)
        assertTrue(method.isClientStreaming)
        assertTrue(method.isServerStreaming)
        assertEquals("ControlMessage", method.inputType.name)
        assertEquals("ExecutionEvent", method.outputType.name)
        val analyze = assertNotNull(service.findMethodByName("ValidateRawSql"))
        assertTrue(!analyze.isClientStreaming)
        assertTrue(!analyze.isServerStreaming)
        assertEquals("ValidateRawSqlRequest", analyze.inputType.name)
        assertEquals("ValidateRawSqlResponse", analyze.outputType.name)
        assertNull(service.findMethodByName("AnalyzeRawSql"))
        assertNull(service.findMethodByName("ResolveDirectRelations"))
        assertNull(service.findMethodByName("OpenWorkerControl"))
        assertNull(service.findMethodByName("MaterializeCredential"))
    }

    @Test
    fun assignmentUsesGenericQueryRequest() {
        assertOneofFields(
            messageName = "ControlMessage",
            oneofName = "message",
            expectedFields = setOf("assignment"),
        )
        assertMessageField("ControlAssignment", "request", "QueryRequest")
        assertEquals(
            setOf("run_id", "workspace_id", "connector", "request", "deadline_epoch_millis"),
            assertNotNull(descriptor.findMessageTypeByName("ControlAssignment"))
                .fields.map { field -> field.name }.toSet(),
        )

        assertEquals(
            setOf("run_id", "workspace_id", "connector", "sql", "parameters", "deadline_epoch_millis"),
            assertNotNull(descriptor.findMessageTypeByName("ValidateRawSqlRequest"))
                .fields.map { field -> field.name }.toSet(),
        )
        assertOneofFields(
            messageName = "ValidateRawSqlResponse",
            oneofName = "result",
            expectedFields = setOf("accepted", "failed"),
        )
        assertEquals(
            emptySet(),
            assertNotNull(descriptor.findMessageTypeByName("RawSqlAccepted"))
                .fields.map { field -> field.name }.toSet(),
        )
        assertEquals(
            setOf("failure_code", "safe_message"),
            assertNotNull(descriptor.findMessageTypeByName("RawSqlValidationFailed"))
                .fields.map { field -> field.name }.toSet(),
        )
        assertNull(descriptor.findMessageTypeByName("ResolveDirectRelationsRequest"))
        assertNull(descriptor.findMessageTypeByName("ResolveDirectRelationsResponse"))
        assertNull(descriptor.findMessageTypeByName("DirectRelationsResolved"))
        assertNull(descriptor.findMessageTypeByName("DirectRelationsResolutionFailed"))
        assertNull(descriptor.findMessageTypeByName("RelationAddress"))
        assertNull(descriptor.findMessageTypeByName("ReadQuery"))

        val query = assertNotNull(descriptor.findMessageTypeByName("QueryRequest"))
        assertEquals(
            setOf("schema_version", "sql", "parameters", "inline_stream", "artifact_upload", "validate_raw_sql_before_run", "expected_column_names"),
            query.fields.map { field -> field.name }.toSet(),
        )
        assertEquals(
            mapOf(
                "schema_version" to 1,
                "sql" to 2,
                "parameters" to 3,
                "inline_stream" to 5,
                "artifact_upload" to 6,
                "validate_raw_sql_before_run" to 13,
                "expected_column_names" to 14,
            ),
            query.fields.associate { field -> field.name to field.number },
        )
        assertTrue(query.isReservedNumber(4))
        assertTrue(query.isReservedNumber(7))
        assertTrue(query.isReservedNumber(12))
        assertTrue(query.isReservedName("object_key"))
        assertTrue(query.isReservedName("raw_sql"))
        assertTrue(query.isReservedName("catalog_scan"))
        assertNull(descriptor.findMessageTypeByName("ExecutableRequest"))
        assertNull(descriptor.findMessageTypeByName("RawSqlRequest"))
        assertNull(descriptor.findMessageTypeByName("CatalogScanRequest"))
        assertNull(descriptor.findEnumTypeByName("ExecutableRequestKind"))
        assertOneofFields(
            messageName = "QueryRequest",
            oneofName = "delivery",
            expectedFields = setOf("inline_stream", "artifact_upload"),
        )
        assertNull(descriptor.findEnumTypeByName("ResultDelivery"))

        assertEquals(
            mapOf(
                "maximum_record_batch_rows" to 1,
                "maximum_record_batch_bytes" to 2,
                "maximum_cell_bytes" to 3,
            ),
            assertNotNull(descriptor.findMessageTypeByName("InlineStreamTarget"))
                .fields.associate { field -> field.name to field.number },
        )
        assertEquals(
            setOf("progress", "inline_result_batch", "physical_outcome"),
            assertNotNull(descriptor.findMessageTypeByName("ExecutionEvent"))
                .oneofs.single { oneof -> oneof.name == "event" }
                .fields.map { field -> field.name }.toSet(),
        )
        assertEquals(
            mapOf(
                "object_key" to 1,
                "maximum_artifact_rows" to 2,
                "maximum_artifact_bytes" to 3,
                "maximum_record_batch_rows" to 4,
                "maximum_record_batch_bytes" to 5,
                "maximum_cell_bytes" to 6,
                "maximum_multipart_part_bytes" to 7,
            ),
            assertNotNull(descriptor.findMessageTypeByName("ArtifactUploadTarget"))
                .fields.associate { field -> field.name to field.number },
        )
    }

    @Test
    fun eventsContainProgressInlineBatchesAndPhysicalOutcome() {
        assertOneofFields(
            messageName = "ExecutionEvent",
            oneofName = "event",
            expectedFields = setOf("progress", "inline_result_batch", "physical_outcome"),
        )
        val inlineBatch = assertNotNull(descriptor.findMessageTypeByName("InlineResultBatch"))
        assertEquals(
            mapOf("sequence" to 1, "arrow_ipc" to 2, "sha256" to 3),
            inlineBatch.fields.associate { field -> field.name to field.number },
        )
        assertNull(descriptor.findMessageTypeByName("CatalogMetadataBatch"))
        assertNull(descriptor.findMessageTypeByName("CatalogMetadataObservation"))
        assertNull(descriptor.findMessageTypeByName("CatalogScanScope"))

        val outcome = assertNotNull(descriptor.findMessageTypeByName("PhysicalOutcome"))
        assertMessageField("PhysicalOutcome", "arrow_result_manifest", "ArrowResultManifest")
        assertEquals(
            setOf("kind", "failure_code", "safe_message", "arrow_result_manifest"),
            outcome.fields.map { field -> field.name }.toSet(),
        )
    }

    @Test
    fun artifactManifestRemainsTerminalResultOnly() {
        val manifest = assertNotNull(descriptor.findMessageTypeByName("ArrowResultManifest"))
        assertEquals(
            setOf("object_key", "byte_size", "object_etag", "row_count", "column_count", "media_type"),
            manifest.fields.map { field -> field.name }.toSet(),
        )
        assertNull(manifest.findFieldByName("content_digest"))
        assertNull(manifest.findFieldByName("staging_key"))
    }

    private fun assertMessageField(
        messageName: String,
        fieldName: String,
        fieldMessageName: String,
    ) {
        val message = assertNotNull(descriptor.findMessageTypeByName(messageName))
        val field = assertNotNull(message.findFieldByName(fieldName))
        assertEquals(Descriptors.FieldDescriptor.JavaType.MESSAGE, field.javaType)
        assertEquals(fieldMessageName, field.messageType.name)
    }

    private fun assertOneofFields(
        messageName: String,
        oneofName: String,
        expectedFields: Set<String>,
    ) {
        val message = assertNotNull(descriptor.findMessageTypeByName(messageName))
        assertEquals(
            expectedFields,
            message.oneofs.single { oneof -> oneof.name == oneofName }
                .fields.map { field -> field.name }.toSet(),
        )
    }
}
