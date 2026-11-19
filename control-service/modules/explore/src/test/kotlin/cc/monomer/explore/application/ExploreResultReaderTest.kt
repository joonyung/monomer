@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.explore.application

import cc.monomer.connector.api.ConnectorId
import cc.monomer.execution.api.ExecutionArtifactHead
import cc.monomer.execution.api.ExecutionArtifactStore
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.ResultLogicalType
import cc.monomer.execution.api.ResultPreviewCell
import cc.monomer.explore.api.ExploreConnectorBinding
import cc.monomer.explore.api.ExploreDigest
import cc.monomer.explore.api.ExploreResultReadOutcome
import cc.monomer.explore.api.ExploreResultProjectionReadOutcome
import cc.monomer.explore.api.ExploreResultReadTransaction
import cc.monomer.explore.api.ExploreResultView
import cc.monomer.explore.api.ExploreRunId
import cc.monomer.explore.api.ExploreRunState
import cc.monomer.explore.api.ReadExploreResultCommand
import cc.monomer.explore.domain.ExploreRun
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.WorkspaceId
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.time.Clock
import java.time.Instant
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.Decimal256Vector
import org.apache.arrow.vector.Float8Vector
import org.apache.arrow.vector.TimeMicroVector
import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowFileWriter
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate
import org.apache.arrow.vector.DateDayVector
import org.apache.arrow.vector.TimeStampMicroTZVector
import org.apache.arrow.vector.TimeStampMicroVector
import org.apache.arrow.vector.types.DateUnit
import org.apache.arrow.vector.types.TimeUnit

class ExploreResultReaderTest {
    @Test
    fun `Statement API temporal Arrow preview preserves Gregorian and DST wall fields separately from UTC`() {
        listOf("0000-01-01", "1582-10-10", "2025-03-09", "2025-11-02").forEach { date ->
            val day = LocalDate.parse(date).toEpochDay()
            val micros = day * 86_400_000_000L + 9_000_000_001L
            val fields = listOf(
                Field("date", FieldType.nullable(ArrowType.Date(DateUnit.DAY)), null),
                Field("instant", FieldType.nullable(ArrowType.Timestamp(TimeUnit.MICROSECOND, "America/Los_Angeles")), null),
                Field("wall", FieldType.nullable(ArrowType.Timestamp(TimeUnit.MICROSECOND, null)), null),
            )
            val result = assertIs<ExploreResultReadOutcome.Visible>(runReader(fields) { vectors ->
                (vectors[0] as DateDayVector).setSafe(0, Math.toIntExact(day))
                (vectors[1] as TimeStampMicroTZVector).setSafe(0, micros)
                (vectors[2] as TimeStampMicroVector).setSafe(0, micros)
            }).result
            assertEquals(listOf(
                ResultPreviewCell.LocalDateValue(date),
                ResultPreviewCell.InstantValue("${date}T02:30:00.000001Z"),
                ResultPreviewCell.LocalDateTimeValue("${date}T02:30:00.000001"),
            ), result.rows.single())
        }
    }

    @Test
    fun `Statement API raw extremes retain the existing four-digit preview boundary`() {
        val date = Field("date", FieldType.nullable(ArrowType.Date(DateUnit.DAY)), null)
        listOf(Int.MIN_VALUE, Int.MAX_VALUE, LocalDate.parse("-0001-01-01").toEpochDay().toInt()).forEach { day ->
            assertIs<ExploreResultReadOutcome.PreviewUnavailable>(runReader(date) { (it as DateDayVector).setSafe(0, day) })
        }
        listOf("UTC", null).forEach { timezone ->
            val field = Field("time", FieldType.nullable(ArrowType.Timestamp(TimeUnit.MICROSECOND, timezone)), null)
            listOf(Long.MIN_VALUE, Long.MAX_VALUE).forEach { micros ->
                assertIs<ExploreResultReadOutcome.PreviewUnavailable>(runReader(field) {
                    if (it is TimeStampMicroTZVector) it.setSafe(0, micros) else (it as TimeStampMicroVector).setSafe(0, micros)
                })
            }
        }
    }

    @Test
    fun `Statement API decimal and float preview preserves trailing scale and special values`() {
        val decimal = Field("amount", FieldType.nullable(ArrowType.Decimal(38, 18, 128)), null)
        val value = BigDecimal("99999999999999999999.010000000000000000")
        val result = assertIs<ExploreResultReadOutcome.Visible>(runReader(decimal) {
            (it as org.apache.arrow.vector.DecimalVector).setSafe(0, value)
        }).result
        assertEquals(ResultPreviewCell.Number(value.toPlainString()), result.rows.single().single())
        val floating = Field("ratio", FieldType.nullable(ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)), null)
        listOf(Double.NaN to "NaN", Double.POSITIVE_INFINITY to "Infinity", Double.NEGATIVE_INFINITY to "-Infinity", -0.0 to "-0.0").forEach { (number, expected) ->
            val preview = assertIs<ExploreResultReadOutcome.Visible>(runReader(floating) { (it as Float8Vector).setSafe(0, number) }).result
            assertEquals(ResultPreviewCell.Number(expected), preview.rows.single().single())
        }
    }

    @Test
    fun `date buckets and UTC timestamps preserve epoch leap day and year boundaries`() {
        listOf("1969-12-31", "1970-01-01", "2024-02-29", "2024-12-31", "2025-01-01").forEach { date ->
            val day = LocalDate.parse(date).toEpochDay()
            val fields = listOf(
                Field("date", FieldType.nullable(ArrowType.Date(DateUnit.DAY)), null),
                Field("timestamp", FieldType.nullable(ArrowType.Timestamp(TimeUnit.MICROSECOND, "UTC")), null),
            )
            val outcome = runReader(fields) { vectors ->
                (vectors[0] as DateDayVector).setSafe(0, Math.toIntExact(day))
                (vectors[1] as TimeStampMicroTZVector).setSafe(0, day * 86_400_000_000L - 1L)
            }
            val result = assertIs<ExploreResultReadOutcome.Visible>(outcome).result
            assertEquals(listOf(ResultLogicalType.DATE, ResultLogicalType.TIMESTAMP), result.columns.map { it.logicalType })
            assertEquals(
                listOf(ResultPreviewCell.LocalDateValue(date), ResultPreviewCell.InstantValue("${LocalDate.parse(date).minusDays(1)}T23:59:59.999999Z")),
                result.rows.single(),
            )
            val nulls = assertIs<ExploreResultReadOutcome.Visible>(runReader(fields) { vectors -> vectors.forEach { it.setNull(0) } })
            assertEquals(listOf(ResultPreviewCell.Null, ResultPreviewCell.Null), nulls.result.rows.single())
        }
    }

    @Test
    fun `timezone-free datetime preserves local fields microseconds and null`() {
        val field = Field("datetime", FieldType.nullable(ArrowType.Timestamp(TimeUnit.MICROSECOND, null)), null)
        listOf("1969-12-31", "1970-01-01", "2024-02-29", "2024-12-31", "2025-01-01").forEach { date ->
            val micros = LocalDate.parse(date).toEpochDay() * 86_400_000_000L + 12_345_678_901L
            val result = assertIs<ExploreResultReadOutcome.Visible>(runReader(field) {
                (it as TimeStampMicroVector).setSafe(0, micros)
            }).result
            assertEquals(ResultLogicalType.DATETIME, result.columns.single().logicalType)
            assertEquals(ResultPreviewCell.LocalDateTimeValue("${date}T03:25:45.678901"), result.rows.single().single())
        }
        val beforeEpoch = assertIs<ExploreResultReadOutcome.Visible>(runReader(field) {
            (it as TimeStampMicroVector).setSafe(0, -1L)
        }).result
        assertEquals(ResultPreviewCell.LocalDateTimeValue("1969-12-31T23:59:59.999999"), beforeEpoch.rows.single().single())
        val nulls = assertIs<ExploreResultReadOutcome.Visible>(runReader(field) { it.setNull(0) })
        assertEquals(ResultLogicalType.DATETIME, nulls.result.columns.single().logicalType)
        assertEquals(ResultPreviewCell.Null, nulls.result.rows.single().single())
    }

    @Test
    fun `semantic preview uses canonical key and preserves exact decimal and null`() {
        val query = cc.monomer.semantic.api.SemanticQuerySpec(
            metrics = listOf(cc.monomer.semantic.api.SemanticElementName("a".repeat(600))),
            groupBys = emptyList(), metricTimeRange = null, filters = emptyList(), orderBy = emptyList(), rowLimit = 10,
        )
        val number = runReader(
            fields = listOf(taggedDecimalField("__monomer_result_0")),
            semanticQuery = query,
            setValues = { vectors -> (vectors.single() as VarCharVector).setSafe(0, "12345678901234567890.1234567890".toByteArray()) },
        )
        number.assertVisibleNumber("12345678901234567890.1234567890")
        assertEquals("metric.${"a".repeat(600)}", assertIs<ExploreResultReadOutcome.Visible>(number).result.columns.single().name)
        val nullResult = runReader(
            fields = listOf(taggedDecimalField("__monomer_result_0")),
            semanticQuery = query,
            setValues = { vectors -> vectors.single().setNull(0) },
        )
        assertEquals(ResultPreviewCell.Null, assertIs<ExploreResultReadOutcome.Visible>(nullResult).result.rows.single().single())
        val oldArtifact = runReader(
            fields = listOf(taggedDecimalField("amount")), semanticQuery = query,
            setValues = { vectors -> vectors.single().setNull(0) },
        )
        assertIs<ExploreResultReadOutcome.PreviewUnavailable>(oldArtifact)
    }

    @Test
    fun `semantic preview joins saved display and ignores mismatched saved keys`() {
        val query = cc.monomer.semantic.api.SemanticQuerySpec(
            metrics = listOf(cc.monomer.semantic.api.SemanticElementName("rate")), groupBys = emptyList(),
            metricTimeRange = null, filters = emptyList(), orderBy = emptyList(), rowLimit = 10,
        )
        val display = cc.monomer.semantic.api.MetricDisplay.Percent(2)
        listOf("metric.rate" to display, "metric.renamed" to null).forEach { (key, expected) ->
            val outcome = runReader(
                fields = listOf(taggedDecimalField("__monomer_result_0")), semanticQuery = query,
                outputColumnDisplays = listOf(cc.monomer.semantic.api.SemanticOutputColumnDisplay(cc.monomer.semantic.api.SemanticResultColumnKey(key), display)),
                setValues = { vectors -> (vectors.single() as VarCharVector).setSafe(0, "0.123400000".toByteArray()) },
            )
            val result = assertIs<ExploreResultReadOutcome.Visible>(outcome).result
            assertEquals(listOf(expected), result.columnDisplays)
            assertEquals(ResultPreviewCell.Number("0.123400000"), result.rows.single().single())
        }
    }

    @Test
    fun `projection reports exact preview counts and truncation from manifest total`() {
        val outcome = runProjection(totalRowCount = 3u, artifactRowCount = 2)

        val projection = assertIs<ExploreResultProjectionReadOutcome.Visible>(outcome).projection
        assertEquals(listOf("amount"), projection.columns.map { it.name })
        assertEquals(2, projection.rows.size)
        assertEquals(3u, projection.totalRowCount)
        assertEquals(2, projection.returnedRowCount)
        assertEquals(true, projection.truncated)
    }

    @Test
    fun `zero row projection keeps columns and reports an exact empty result`() {
        val outcome = runProjection(totalRowCount = 0u, artifactRowCount = 0)

        val projection = assertIs<ExploreResultProjectionReadOutcome.Visible>(outcome).projection
        assertEquals(listOf("amount"), projection.columns.map { it.name })
        assertEquals(emptyList(), projection.rows)
        assertEquals(0, projection.returnedRowCount)
        assertEquals(false, projection.truncated)
    }

    @Test
    fun `projection preserves owner and retention failures`() {
        assertIs<ExploreResultProjectionReadOutcome.AccessDenied>(
            runProjection(totalRowCount = 1u, artifactRowCount = 1, useOtherOwner = true),
        )
        assertIs<ExploreResultProjectionReadOutcome.RetentionExpired>(
            runProjection(totalRowCount = 1u, artifactRowCount = 1, expired = true),
        )
    }

    @Test
    fun `tagged utf8 decimal is exposed as an exact number`() = runReader(
        field = taggedDecimalField(),
        setValue = { vector ->
            (vector as VarCharVector).setSafe(0, "12345678901234567890.1234567890".toByteArray())
        },
    ).assertVisibleNumber("12345678901234567890.1234567890")

    @Test
    fun `partial and unknown decimal metadata fail closed`() {
        listOf(
            mapOf(DECIMAL_LOGICAL_TYPE_KEY to DECIMAL_LOGICAL_TYPE_VALUE),
            mapOf(DECIMAL_PHYSICAL_ENCODING_KEY to DECIMAL_PHYSICAL_ENCODING_VALUE),
            mapOf(
                DECIMAL_LOGICAL_TYPE_KEY to DECIMAL_LOGICAL_TYPE_VALUE,
                DECIMAL_PHYSICAL_ENCODING_KEY to "UNKNOWN",
            ),
            mapOf(
                DECIMAL_LOGICAL_TYPE_KEY to "UNKNOWN",
                DECIMAL_PHYSICAL_ENCODING_KEY to DECIMAL_PHYSICAL_ENCODING_VALUE,
            ),
        ).forEach { metadata ->
            val outcome = runReader(
                field = Field(
                    "amount",
                    FieldType(true, ArrowType.Utf8.INSTANCE, null, metadata),
                    emptyList(),
                ),
                setValue = { vector ->
                    (vector as VarCharVector).setSafe(0, "1".toByteArray())
                },
            )
            assertIs<ExploreResultReadOutcome.PreviewUnavailable>(outcome)
        }

        val wrongPhysicalType = runReader(
            field = Field(
                "amount",
                FieldType(
                    true,
                    ArrowType.Decimal(10, 2, 128),
                    null,
                    mapOf(
                        DECIMAL_LOGICAL_TYPE_KEY to DECIMAL_LOGICAL_TYPE_VALUE,
                        DECIMAL_PHYSICAL_ENCODING_KEY to DECIMAL_PHYSICAL_ENCODING_VALUE,
                    ),
                ),
                emptyList(),
            ),
            setValue = { vector ->
                (vector as org.apache.arrow.vector.DecimalVector).setSafe(0, BigDecimal("1.00"))
            },
        )
        assertIs<ExploreResultReadOutcome.PreviewUnavailable>(wrongPhysicalType)
    }

    @Test
    fun `non decimal logical metadata retains its physical preview type`() {
        val outcome = runReader(
            field = Field(
                "payload",
                FieldType(
                    true,
                    ArrowType.Utf8.INSTANCE,
                    null,
                    mapOf(
                        DECIMAL_LOGICAL_TYPE_KEY to "BIGQUERY_JSON",
                        DECIMAL_PHYSICAL_ENCODING_KEY to "UTF8_JSON",
                    ),
                ),
                emptyList(),
            ),
            setValue = { vector ->
                (vector as VarCharVector).setSafe(0, "{\"amount\":1}".toByteArray())
            },
        )

        val visible = assertIs<ExploreResultReadOutcome.Visible>(outcome)
        assertEquals(ResultLogicalType.TEXT, visible.result.columns.single().logicalType)
        assertEquals(
            ResultPreviewCell.Text("{\"amount\":1}"),
            visible.result.rows.single().single(),
        )
    }

    @Test
    fun `floating point preview stays exponent-free for large and small values`() {
        listOf(12345678.0 to "12345678", 0.0001 to "0.0001", 1.5e-7 to "0.00000015", 0.25 to "0.25").forEach { (value, expected) ->
            val visible = assertIs<ExploreResultReadOutcome.Visible>(runReader(
                field = Field("ratio", FieldType(true, ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE), null), emptyList()),
                setValue = { vector -> (vector as Float8Vector).setSafe(0, value) },
            ))
            assertEquals(ResultLogicalType.FLOAT, visible.result.columns.single().logicalType)
            assertEquals(ResultPreviewCell.Number(expected), visible.result.rows.single().single())
        }
    }

    @Test
    fun `time preview shows a microsecond clock time`() {
        val visible = assertIs<ExploreResultReadOutcome.Visible>(runReader(
            field = Field("at", FieldType(true, ArrowType.Time(TimeUnit.MICROSECOND, 64), null), emptyList()),
            setValue = { vector -> (vector as TimeMicroVector).setSafe(0, 45_296_000_001L) },
        ))
        assertEquals(ResultPreviewCell.Text("12:34:56.000001"), visible.result.rows.single().single())
    }

    @Test
    fun `a preview that holds every row ends the result even at the row limit`() {
        val visible = assertIs<ExploreResultReadOutcome.Visible>(runReader(
            fields = listOf(taggedDecimalField()),
            previewRowLimit = 1,
        ) { vectors -> (vectors.single() as VarCharVector).setSafe(0, "1".toByteArray()) })
        assertEquals(cc.monomer.execution.api.ResultPreviewStopReason.END_OF_RESULT, visible.result.stopReason)
    }

    @Test
    fun `decimal256 preview uses exponent-free plain text`() = runReader(
        field = Field(
            "amount",
            FieldType(true, ArrowType.Decimal(50, 0, 256), null),
            emptyList(),
        ),
        setValue = { vector ->
            (vector as Decimal256Vector).setSafe(0, BigDecimal("1E+40").setScale(0))
        },
    ).assertVisibleNumber("10000000000000000000000000000000000000000")

    @Test
    fun `number longer than the old 1024 character limit remains visible within budget`() = runReader(
        field = taggedDecimalField(),
        setValue = { vector ->
            (vector as VarCharVector).setSafe(0, "7".repeat(1_500).toByteArray())
        },
    ).let { outcome ->
        val visible = assertIs<ExploreResultReadOutcome.Visible>(outcome)
        val number = assertIs<ResultPreviewCell.Number>(visible.result.rows.single().single())
        assertEquals(1_500, number.canonicalValue.length)
    }

    @Test
    fun `number larger than preview budget makes only preview unavailable`() {
        val outcome = runReader(
            field = taggedDecimalField(),
            setValue = { vector ->
                (vector as VarCharVector).setSafe(0, "8".repeat(2 * 1024 * 1024 + 1).toByteArray())
            },
        )

        assertIs<ExploreResultReadOutcome.PreviewUnavailable>(outcome)
    }

    @Test
    fun `first row larger than preview budget makes only preview unavailable`() {
        val outcome = runReader(
            fields = listOf(taggedDecimalField("left_amount"), taggedDecimalField("right_amount")),
            setValues = { vectors ->
                vectors.forEach { vector ->
                    (vector as VarCharVector).setSafe(0, "9".repeat(1_100_000).toByteArray())
                }
            },
        )

        assertIs<ExploreResultReadOutcome.PreviewUnavailable>(outcome)
    }

    private fun ExploreResultReadOutcome.assertVisibleNumber(expected: String) {
        val visible = assertIs<ExploreResultReadOutcome.Visible>(this)
        assertEquals(ResultLogicalType.DECIMAL, visible.result.columns.single().logicalType)
        assertEquals(
            ResultPreviewCell.Number(expected),
            visible.result.rows.single().single(),
        )
    }

    private fun runReader(
        field: Field,
        setValue: (org.apache.arrow.vector.FieldVector) -> Unit,
    ): ExploreResultReadOutcome = runReader(listOf(field)) { vectors -> setValue(vectors.single()) }

    private fun runReader(
        fields: List<Field>,
        semanticQuery: cc.monomer.semantic.api.SemanticQuerySpec? = null,
        previewRowLimit: Int = 10,
        outputColumnDisplays: List<cc.monomer.semantic.api.SemanticOutputColumnDisplay>? = null,
        setValues: (List<org.apache.arrow.vector.FieldVector>) -> Unit,
    ): ExploreResultReadOutcome {
        val runId = ExploreRunId.generate()
        val ownerPrincipalId = PrincipalId.generate()
        val workspaceId = WorkspaceId.generate()
        val artifactKey = "artifacts/explore/${runId.value}.arrow"
        val artifact = writeArtifact(fields, setValues)
        val activatedAt = Instant.parse("2026-08-20T00:00:00Z")
        val result = ExploreResultView(
            runId = runId,
            objectKey = artifactKey,
            objectEtag = "etag",
            byteSize = artifact.size.toULong(),
            mediaType = "application/vnd.apache.arrow.file",
            rowCount = 1u,
            columnCount = fields.size,
            retention = cc.monomer.explore.api.ExploreResultRetention(activatedAt.plusSeconds(60)),
            ownerPrincipalId = ownerPrincipalId,
            activatedAt = activatedAt,
        )
        val binding = ExploreConnectorBinding(
            workspaceId = workspaceId,
            connectorId = ConnectorId.generate(),
            connectorType = ConnectorType.POSTGRESQL,
        )
        val run = ExploreRun(
            id = runId,
            kind = ExploreRun.Kind.Sql,
            idempotencyKey = "reader-test-$runId",
            ownerPrincipalId = ownerPrincipalId,
            credentialSurface = null,
            agentClientId = null,
            connectorBinding = binding,
            requestDigest = ExploreDigest("sha256:${"a".repeat(64)}"),
            state = ExploreRunState.SUCCEEDED,
            visibleResult = result,
            failureCode = null,
            failureMessage = null,
            revision = 1,
            createdAt = activatedAt,
            updatedAt = activatedAt,
        )
        val projectedRun = semanticQuery?.let { query ->
            cc.monomer.explore.domain.ExploreRun(
                id = run.id,
                kind = cc.monomer.explore.domain.ExploreRun.Kind.Semantic(query = query, outputColumnDisplays = outputColumnDisplays, reportingTimeZone = java.time.ZoneId.of("UTC")),
                idempotencyKey = run.idempotencyKey,
                ownerPrincipalId = run.ownerPrincipalId,
                credentialSurface = null,
                agentClientId = null,
                connectorBinding = run.connectorBinding,
                requestDigest = run.requestDigest,
                state = run.state,
                visibleResult = run.visibleResult,
                failureCode = null,
                failureMessage = null,
                revision = run.revision,
                createdAt = run.createdAt,
                updatedAt = run.updatedAt,
            )
        } ?: run
        val reader = ExploreResultReader(
            resultReadTransactionPort = DirectResultReadTransactionPort,
            repository = FixedExploreRepository(projectedRun, result),
            executionArtifactStore = InMemoryArtifactStore(artifactKey, artifact),
            clock = Clock.fixed(activatedAt.plusSeconds(1), java.time.ZoneOffset.UTC),
        )
        return kotlinx.coroutines.runBlocking {
            reader.read(
                ReadExploreResultCommand(
                    runId = runId,
                    ownerPrincipalId = ownerPrincipalId,
                    previewRowLimit = previewRowLimit,
                ),
            )
        }
    }

    private fun runProjection(
        totalRowCount: ULong,
        artifactRowCount: Int,
        useOtherOwner: Boolean = false,
        expired: Boolean = false,
    ): ExploreResultProjectionReadOutcome {
        val field = taggedDecimalField()
        val runId = ExploreRunId.generate()
        val ownerPrincipalId = PrincipalId.generate()
        val workspaceId = WorkspaceId.generate()
        val artifactKey = "artifacts/explore/${runId.value}.arrow"
        val artifact = writeArtifact(
            fields = listOf(field),
            setValues = { vectors ->
                val vector = vectors.single() as VarCharVector
                repeat(artifactRowCount) { index -> vector.setSafe(index, (index + 1).toString().toByteArray()) }
            },
            rowCount = artifactRowCount,
        )
        val activatedAt = Instant.parse("2026-08-20T00:00:00Z")
        val expiresAt = activatedAt.plusSeconds(60)
        val result = ExploreResultView(
            runId = runId,
            objectKey = artifactKey,
            objectEtag = "etag",
            byteSize = artifact.size.toULong(),
            mediaType = "application/vnd.apache.arrow.file",
            rowCount = totalRowCount,
            columnCount = 1,
            retention = cc.monomer.explore.api.ExploreResultRetention(expiresAt),
            ownerPrincipalId = ownerPrincipalId,
            activatedAt = activatedAt,
        )
        val run = ExploreRun(
            id = runId,
            kind = ExploreRun.Kind.Sql,
            idempotencyKey = "projection-$runId",
            ownerPrincipalId = ownerPrincipalId,
            credentialSurface = null,
            agentClientId = null,
            connectorBinding = ExploreConnectorBinding(workspaceId, ConnectorId.generate(), ConnectorType.POSTGRESQL),
            requestDigest = ExploreDigest("sha256:${"c".repeat(64)}"),
            state = ExploreRunState.SUCCEEDED,
            visibleResult = result,
            failureCode = null,
            failureMessage = null,
            revision = 1,
            createdAt = activatedAt,
            updatedAt = activatedAt,
        )
        val reader = ExploreResultReader(
            resultReadTransactionPort = DirectResultReadTransactionPort,
            repository = FixedExploreRepository(run, result),
            executionArtifactStore = InMemoryArtifactStore(artifactKey, artifact),
            clock = Clock.fixed(if (expired) expiresAt else activatedAt.plusSeconds(1), java.time.ZoneOffset.UTC),
        )
        return kotlinx.coroutines.runBlocking {
            reader.readProjection(
                ReadExploreResultCommand(
                    runId = runId,
                    ownerPrincipalId = if (useOtherOwner) PrincipalId.generate() else ownerPrincipalId,
                    previewRowLimit = 10,
                ),
            )
        }
    }

    private fun writeArtifact(
        fields: List<Field>,
        setValues: (List<org.apache.arrow.vector.FieldVector>) -> Unit,
        rowCount: Int = 1,
    ): ByteArray {
        RootAllocator().use { allocator ->
            val root = VectorSchemaRoot.create(Schema(fields), allocator)
            root.allocateNew()
            try {
                setValues(root.fieldVectors)
                root.fieldVectors.forEach { vector -> vector.valueCount = rowCount }
                root.rowCount = rowCount
                val output = ByteArrayOutputStream()
                ArrowFileWriter(root, null, Channels.newChannel(output)).use { writer ->
                    writer.start()
                    writer.writeBatch()
                    writer.end()
                }
                return output.toByteArray()
            } finally {
                root.close()
            }
        }
    }

    private fun taggedDecimalField(): Field =
        taggedDecimalField("amount")

    private fun taggedDecimalField(name: String): Field =
        Field(
            name,
            FieldType(
                true,
                ArrowType.Utf8.INSTANCE,
                null,
                mapOf(
                    DECIMAL_LOGICAL_TYPE_KEY to DECIMAL_LOGICAL_TYPE_VALUE,
                    DECIMAL_PHYSICAL_ENCODING_KEY to DECIMAL_PHYSICAL_ENCODING_VALUE,
                ),
            ),
            emptyList(),
        )

    private object DirectResultReadTransactionPort : ExploreResultReadTransactionPort {
        override suspend fun <T> inResultReadTransaction(
            block: (ExploreResultReadTransaction) -> T,
        ): T = block(ExploreResultReadTransaction())
    }

    private class FixedExploreRepository(
        private val run: ExploreRun,
        private val result: ExploreResultView,
    ) : ExploreRunRepository {
        override fun lockAdmissionKey(workspaceId: WorkspaceId, idempotencyKey: String) = error("unused")

        override fun findByAdmissionKey(workspaceId: WorkspaceId, idempotencyKey: String): ExploreRun? = error("unused")

        override fun findRun(runId: ExploreRunId): ExploreRun? = error("unused")

        override fun findRuns(
            workspaceId: WorkspaceId,
            ownerPrincipalId: PrincipalId,
            after: cc.monomer.explore.api.ExploreRunPageBoundary?,
            limit: Int,
        ): List<ExploreRun> = error("unused")

        override fun insertAdmittedRun(run: ExploreRun): Boolean = error("unused")

        override fun lockRunForOwnerMutation(runId: ExploreRunId): ExploreRun? = error("unused")

        override fun lockRunForResultRead(runId: ExploreRunId): ExploreRun = run

        override fun findTerminalReceipt(
            workspaceId: WorkspaceId,
            settlementIdempotencyKey: String,
        ) = error("unused")

        override fun commitTerminalReceipt(
            run: ExploreRun,
            receipt: cc.monomer.explore.domain.ExploreTerminalReceipt,
            expectedRevision: Long,
        ): Boolean = error("unused")

        override fun lockVisibleResultForRead(runId: ExploreRunId): ExploreResultView = result
    }

    private class InMemoryArtifactStore(
        private val objectKey: String,
        private val bytes: ByteArray,
    ) : ExecutionArtifactStore {
        override suspend fun head(objectKey: String, deadline: java.time.Instant?): ExecutionArtifactHead =
            ExecutionArtifactHead(
                objectKey = this.objectKey,
                byteSize = bytes.size.toULong(),
                etag = "etag",
                observedAt = Instant.parse("2026-08-20T00:00:00Z"),
            )

        override suspend fun read(objectKey: String): ByteArray = bytes.copyOf()

        override suspend fun readRange(objectKey: String, expectedEtag: String, byteRange: LongRange): ByteArray {
            assertEquals("etag", expectedEtag)
            return bytes.copyOfRange(byteRange.first.toInt(), byteRange.last.toInt() + 1)
        }

        override suspend fun presignGet(objectKey: String, expiresAt: Instant, fileName: String): cc.monomer.execution.api.ExecutionArtifactDownloadLink = error("unused")

        override fun readChunks(
            objectKey: String,
            expectedEtag: String,
            chunkSizeBytes: Int,
        ): Flow<ByteBuffer> = flowOf(ByteBuffer.wrap(bytes).asReadOnlyBuffer())

        override suspend fun delete(objectKey: String) = error("unused")
    }

    private companion object {
        const val DECIMAL_LOGICAL_TYPE_KEY = "cc.monomer.logical_type"
        const val DECIMAL_LOGICAL_TYPE_VALUE = "DECIMAL"
        const val DECIMAL_PHYSICAL_ENCODING_KEY = "cc.monomer.physical_encoding"
        const val DECIMAL_PHYSICAL_ENCODING_VALUE = "UTF8_CANONICAL_DECIMAL"
    }
}
