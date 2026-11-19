package cc.monomer.execution.application

import cc.monomer.execution.driver.spi.QueryRequestCanonicalizer
import cc.monomer.execution.driver.spi.QueryResultTarget
import cc.monomer.execution.protocol.v1.ControlAssignment
import cc.monomer.execution.protocol.v1.InlineStreamTarget
import cc.monomer.execution.protocol.v1.QueryRequest
import cc.monomer.execution.protocol.v1.ArtifactUploadTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class QueryProtocolMapperTest {
    @Test
    fun extendedBindsAndDashboardChecksMapWithoutLosingTypeOrPrecision() {
        val request = QueryRequest.newBuilder().setSchemaVersion(1).setSql("SELECT ?, ?, ?, ?, ?, ?")
            .setValidateRawSqlBeforeRun(true).addAllExpectedColumnNames(listOf("amount", "day", "at"))
            .setInlineStream(InlineStreamTarget.newBuilder().setMaximumRecordBatchRows(128).setMaximumRecordBatchBytes(65_536).setMaximumCellBytes(32_768))
            .addParameters(cc.monomer.execution.protocol.v1.QueryParameter.newBuilder().setDecimalValue("1.234567890123456789"))
            .addParameters(cc.monomer.execution.protocol.v1.QueryParameter.newBuilder().setDateValue("2026-10-06"))
            .addParameters(cc.monomer.execution.protocol.v1.QueryParameter.newBuilder().setTimestampValue("2026-10-06T01:02:03.123456Z"))
        listOf(cc.monomer.execution.protocol.v1.QueryParameterType.QUERY_PARAMETER_TYPE_DECIMAL,
            cc.monomer.execution.protocol.v1.QueryParameterType.QUERY_PARAMETER_TYPE_DATE,
            cc.monomer.execution.protocol.v1.QueryParameterType.QUERY_PARAMETER_TYPE_TIMESTAMP).forEach { type ->
            request.addParameters(cc.monomer.execution.protocol.v1.QueryParameter.newBuilder()
                .setNullValue(cc.monomer.execution.protocol.v1.NullQueryParameter.newBuilder().setType(type)))
        }
        val mapped = assignment(request.build()).toDriverAssignment().request
        assertEquals(true, mapped.validateRawSqlBeforeRun)
        assertEquals(listOf("amount", "day", "at"), mapped.expectedColumnNames)
        assertEquals("1.234567890123456789", assertIs<cc.monomer.execution.driver.spi.QueryParameter.Decimal>(mapped.parameters[0]).value)
        assertEquals(java.time.Instant.parse("2026-10-06T01:02:03.123456Z"), assertIs<cc.monomer.execution.driver.spi.QueryParameter.Timestamp>(mapped.parameters[2]).value)
        assertEquals(listOf(cc.monomer.execution.driver.spi.NullQueryParameterType.DECIMAL,
            cc.monomer.execution.driver.spi.NullQueryParameterType.DATE,
            cc.monomer.execution.driver.spi.NullQueryParameterType.TIMESTAMP),
            mapped.parameters.drop(3).map { assertIs<cc.monomer.execution.driver.spi.QueryParameter.Null>(it).type })
        assertFailsWith<IllegalArgumentException> {
            assignment(request.clone().setParameters(2, cc.monomer.execution.protocol.v1.QueryParameter.newBuilder()
                .setTimestampValue("2026-10-06T01:02:03.123456789Z")).build()).toDriverAssignment()
        }
    }

    @Test
    fun inlineQueryMapsToTheInlineTarget() {
        val assignment = assignment(
            QueryRequest.newBuilder()
                .setSchemaVersion(1)
                .setSql("SELECT 1")
                .setInlineStream(
                    InlineStreamTarget.newBuilder()
                        .setMaximumRecordBatchRows(128)
                        .setMaximumRecordBatchBytes(65_536)
                        .setMaximumCellBytes(32_768),
                )
                .build(),
        )

        val mapped = assignment.toDriverAssignment()
        val request = mapped.request
        val target = assertIs<QueryResultTarget.InlineStream>(request.resultTarget)
        assertEquals(128, target.maximumRecordBatchRows)
        assertEquals(65_536, target.maximumRecordBatchBytes)
        assertEquals(
            QueryRequestCanonicalizer.canonicalDigest(request),
            request.canonicalRequestDigest,
        )
    }

    @Test
    fun artifactTargetCarriesArtifactOnlyFields() {
        val assignment = assignment(
            QueryRequest.newBuilder()
                .setSchemaVersion(1)
                .setSql("SELECT 1")
                .setArtifactUpload(
                    ArtifactUploadTarget.newBuilder()
                        .setObjectKey("artifacts/workspaces/w/r/result.arrow")
                        .setMaximumArtifactRows(10)
                        .setMaximumArtifactBytes(1_048_576)
                        .setMaximumRecordBatchRows(128)
                        .setMaximumRecordBatchBytes(65_536)
                        .setMaximumCellBytes(32_768)
                        .setMaximumMultipartPartBytes(65_536),
                )
                .build(),
        )

        val target = assertIs<QueryResultTarget.ArtifactUpload>(assignment.toDriverAssignment().request.resultTarget)
        assertEquals("artifacts/workspaces/w/r/result.arrow", target.objectKey)
        assertEquals(10u, target.maximumArtifactRows)
    }

    @Test
    fun missingDeliveryIsRejectedBeforeAQueryCanReachADriver() {
        val request = QueryRequest.newBuilder().setSchemaVersion(1).setSql("SELECT 1").build()

        assertFailsWith<IllegalArgumentException> {
            assignment(request).toDriverAssignment()
        }
    }

    @Test
    fun unknownConnectorBindingSchemaVersionIsRejected() {
        val request =
            QueryRequest.newBuilder()
                .setSchemaVersion(1)
                .setSql("SELECT 1")
                .setInlineStream(
                    InlineStreamTarget.newBuilder()
                        .setMaximumRecordBatchRows(128)
                        .setMaximumRecordBatchBytes(65_536)
                        .setMaximumCellBytes(32_768),
                )
                .build()
        val assignment = assignment(request)
        val futureBinding =
            assignment.toBuilder()
                .setConnector(assignment.connector.toBuilder().setSchemaVersion(2))
                .build()

        assertFailsWith<IllegalArgumentException> {
            futureBinding.toDriverAssignment()
        }
    }

    private fun assignment(request: QueryRequest): ControlAssignment =
        ControlAssignment.newBuilder()
            .setRunId(applicationRunId.toString())
            .setWorkspaceId(applicationWorkspaceId.toString())
            .setConnector(
                cc.monomer.execution.protocol.v1.ConnectorRuntimeBinding.newBuilder()
                    .setSchemaVersion(1)
                    .setConnectorId(applicationConnectorId.toString())
                    .setConnectorType("POSTGRESQL"),
            )
            .setRequest(request)
            .setDeadlineEpochMillis(1)
            .build()
}
