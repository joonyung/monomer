@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.execution.adapter.inbound.grpc

import cc.monomer.identity.api.WorkspaceId
import cc.monomer.execution.api.BinaryValue
import cc.monomer.execution.api.ConnectorReference
import cc.monomer.execution.api.ConnectorRuntimeBindingSpec
import cc.monomer.execution.api.ConnectorRuntimePropertyValue
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.CredentialVersionReference
import cc.monomer.execution.api.ExecutionRunId
import cc.monomer.execution.api.InlineQueryRunSpecCommand
import cc.monomer.execution.api.ManagedCredentialDispatchReference
import cc.monomer.execution.api.RawSqlExecutionRequest
import cc.monomer.execution.api.RawSqlExecutionRequestCanonicalizer
import cc.monomer.execution.api.QueryParameterValue
import cc.monomer.execution.api.NullQueryParameterType
import cc.monomer.execution.api.RawSqlRunSpecCommand
import cc.monomer.execution.api.Sha256Digest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ProtoRawSqlRunSpecFactoryTest {
    @Test
    fun `exact execution payload digest matches the execution service golden vector`() {
        val runId =
            ExecutionRunId(
                Uuid.parse("019e0000-0000-7000-8000-0000000000b1"),
            )
        val workspaceId = WorkspaceId.parse("019e0000-0000-7000-8000-0000000000a1")
        val objectKey = "artifacts/workspaces/$workspaceId/explore/runs/$runId/result.arrow"
        val request =
            RawSqlExecutionRequest(
                validateRawSqlBeforeRun = false,
                expectedColumnNames = emptyList(),
                schemaVersion = RawSqlExecutionRequest.SCHEMA_VERSION,
                canonicalRequestDigest = Sha256Digest("0".repeat(64)),
                sql = "SELECT * FROM \"orders\" WHERE total > ? AND active = ?",
                parameters =
                    listOf(
                        QueryParameterValue.Text("café", name = ""),
                        QueryParameterValue.Integer64(-7, name = ""),
                        QueryParameterValue.Float64(-0.0, name = ""),
                        QueryParameterValue.BooleanValue(true, name = ""),
                        QueryParameterValue.Bytes(
                            BinaryValue.copyOf(byteArrayOf(0, 127, -1)),
                            name = "",
                        ),
                        QueryParameterValue.Null(NullQueryParameterType.BYTES, name = ""),
                ),
                maximumResultRows = 42u,
                maximumArtifactBytes = 1_073_741_824u,
                maximumRecordBatchRows = 1024,
                maximumRecordBatchBytes = 8192,
                maximumCellBytes = 4096,
                maximumMultipartPartBytes = 8192,
            ).let { unbound ->
                unbound.copy(
                    canonicalRequestDigest =
                        RawSqlExecutionRequestCanonicalizer.canonicalDigest(unbound, objectKey),
                )
            }

        val spec =
            ProtoRawSqlRunSpecFactory().create(
                RawSqlRunSpecCommand(
                    runId = runId,
                    workspaceId = workspaceId,
                    objectKey = objectKey,
                    connector = connector(),
                    request = request,
                    deadline = Instant.parse("2026-08-18T00:01:00Z"),
                ),
            )

        assertEquals(
            "3c63c5a803327f011e553cb9268d36ac0700ab9bf99c99b0b9a5024e5d39acc7",
            spec.canonicalRequestDigest.value,
        )
        val payload = cc.monomer.execution.protocol.v1.QueryRequest.parseFrom(spec.assignmentTemplate.copyBytes())
        assertEquals(1_073_741_824L, payload.artifactUpload.maximumArtifactBytes)
        assertEquals(1_073_741_824u, spec.runtimeBindings.resultSink.maximumArtifactBytes)
        val named = request.copy(parameters = request.parameters.mapIndexed { index, parameter ->
            parameter.withName("p${index + 1}")
        })
        for ((flag, columns) in listOf(false to emptyList(), true to emptyList(), false to listOf("amount"), true to listOf("amount"))) {
            val unsigned = named.copy(validateRawSqlBeforeRun = flag, expectedColumnNames = columns)
            val canonical = unsigned.copy(canonicalRequestDigest = RawSqlExecutionRequestCanonicalizer.canonicalDigest(unsigned, objectKey))
            val namedSpec = ProtoRawSqlRunSpecFactory().create(RawSqlRunSpecCommand(
                runId, workspaceId, objectKey, connector(), canonical, Instant.parse("2026-08-18T00:01:00Z"),
            ))
            val namedPayload = cc.monomer.execution.protocol.v1.QueryRequest.parseFrom(namedSpec.assignmentTemplate.copyBytes())
            assertEquals(listOf("p1", "p2", "p3", "p4", "p5", "p6"), namedPayload.parametersList.map { it.name })
            assertEquals(canonical.canonicalRequestDigest, ProtoQueryRequestCanonicalizer.canonicalDigest(namedPayload))
            val oldReaderPayload = namedPayload.toBuilder().clearParameters().addAllParameters(
                namedPayload.parametersList.map { it.toBuilder().clearName().build() },
            ).build()
            kotlin.test.assertNotEquals(canonical.canonicalRequestDigest, ProtoQueryRequestCanonicalizer.canonicalDigest(oldReaderPayload))
        }
        val extended = request.copy(
            validateRawSqlBeforeRun = true,
            expectedColumnNames = listOf("amount", "date", "time"),
            parameters = listOf(
                QueryParameterValue.Decimal("1.234567890123456789", name = ""),
                QueryParameterValue.Date(java.time.LocalDate.parse("2026-10-06"), name = ""),
                QueryParameterValue.Timestamp(Instant.parse("2026-10-06T01:02:03.123456Z"), name = ""),
                QueryParameterValue.Null(NullQueryParameterType.DECIMAL, name = ""),
                QueryParameterValue.Null(NullQueryParameterType.DATE, name = ""),
                QueryParameterValue.Null(NullQueryParameterType.TIMESTAMP, name = ""),
            ),
        ).let { unsigned -> unsigned.copy(canonicalRequestDigest = RawSqlExecutionRequestCanonicalizer.canonicalDigest(unsigned, objectKey)) }
        val extendedSpec = ProtoRawSqlRunSpecFactory().create(RawSqlRunSpecCommand(
            runId, workspaceId, objectKey, connector(), extended, Instant.parse("2026-08-18T00:01:00Z"),
        ))
        val extendedPayload = cc.monomer.execution.protocol.v1.QueryRequest.parseFrom(extendedSpec.assignmentTemplate.copyBytes())
        assertTrue(extendedPayload.validateRawSqlBeforeRun)
        assertEquals(extended.expectedColumnNames, extendedPayload.expectedColumnNamesList)
        assertEquals("1.234567890123456789", extendedPayload.parametersList[0].decimalValue)
        assertEquals("2026-10-06", extendedPayload.parametersList[1].dateValue)
        assertEquals("2026-10-06T01:02:03.123456Z", extendedPayload.parametersList[2].timestampValue)
        assertEquals(listOf(6, 7, 8), extendedPayload.parametersList.drop(3).map { it.nullValue.type.number })
    }

    @Test
    fun `inline query digest matches the execution service canonical vector`() {
        val spec =
            ProtoInlineQueryRunSpecFactory().create(
                InlineQueryRunSpecCommand(
                    runId =
                        ExecutionRunId(
                            Uuid.parse("019e0000-0000-7000-8000-0000000000b1"),
                        ),
                    workspaceId =
                        WorkspaceId.parse("019e0000-0000-7000-8000-0000000000a1"),
                    connector = connector(),
                    sql = "SELECT * FROM orders WHERE total > ? AND active = ?",
                    parameters =
                        listOf(
                            QueryParameterValue.Text("café", name = ""),
                            QueryParameterValue.Integer64(-7, name = ""),
                            QueryParameterValue.Float64(-0.0, name = ""),
                            QueryParameterValue.BooleanValue(true, name = ""),
                            QueryParameterValue.Bytes(
                                BinaryValue.copyOf(byteArrayOf(0, 127, -1)),
                                name = "",
                            ),
                            QueryParameterValue.Null(NullQueryParameterType.BYTES, name = ""),
                        ),
                    maximumRecordBatchRows = 1024,
                    maximumRecordBatchBytes = 8192,
                    maximumCellBytes = 4096,
                    deadline = Instant.parse("2026-08-18T00:01:00Z"),
                ),
            )

        assertEquals(
            "cdabb2fbcfa23d4adc352fb25758f15fec666c84cc9d5d63aa8ea68388f03c80",
            spec.canonicalRequestDigest.value,
        )
        assertEquals(
            spec.canonicalRequestDigest,
            ProtoQueryRequestCanonicalizer.canonicalDigest(
                cc.monomer.execution.protocol.v1.QueryRequest.parseFrom(
                    spec.assignmentTemplate.copyBytes(),
                ),
            ),
        )
        val request =
            cc.monomer.execution.protocol.v1.QueryRequest.parseFrom(
                spec.assignmentTemplate.copyBytes(),
            )
        assertTrue(request.hasInlineStream())
        assertFalse(request.hasArtifactUpload())
        assertEquals(null, spec.runtimeBindings.resultSink.objectKey)
    }

    private fun QueryParameterValue.withName(name: String): QueryParameterValue = when (this) {
        is QueryParameterValue.Text -> copy(name = name)
        is QueryParameterValue.Integer64 -> copy(name = name)
        is QueryParameterValue.Float64 -> copy(name = name)
        is QueryParameterValue.BooleanValue -> copy(name = name)
        is QueryParameterValue.Bytes -> copy(name = name)
        is QueryParameterValue.Decimal -> copy(name = name)
        is QueryParameterValue.Date -> copy(name = name)
        is QueryParameterValue.Timestamp -> copy(name = name)
        is QueryParameterValue.Null -> copy(name = name)
    }

    private fun connector(): ConnectorRuntimeBindingSpec =
        ConnectorRuntimeBindingSpec(
            connectorId =
                ConnectorReference.parse("019e0000-0000-7000-8000-0000000000c1"),
            connectorType = ConnectorType.POSTGRESQL,
            properties = mapOf("host" to ConnectorRuntimePropertyValue.Text("localhost")),
            managedCredential =
                ManagedCredentialDispatchReference(
                    credentialVersion =
                        CredentialVersionReference.parse(
                            "019e0000-0000-7000-8000-0000000000d1",
                        ),
                    keyVersion = "local-primary",
                    envelopeDigest = Sha256Digest("a".repeat(64)),
                ),
        )
}
