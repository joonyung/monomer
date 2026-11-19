@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.semantic.adapter.outbound.metricflow

import cc.monomer.catalog.api.CatalogPhysicalAddress
import cc.monomer.connector.api.ConnectorId
import cc.monomer.metricflow.application.engine.MetricFlowExplainRequest
import cc.monomer.metricflow.common.errors.FeatureNotSupportedError
import cc.monomer.metricflow.common.errors.InvalidQueryException
import cc.monomer.metricflow.common.errors.MetricFlowInternalError
import cc.monomer.metricflow.common.errors.RenderSqlTemplateException
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationRule
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.SemanticConstantProperty
import cc.monomer.semantic.api.SemanticConversionCalculation
import cc.monomer.semantic.api.SemanticDimensionDefinition
import cc.monomer.semantic.api.SemanticDimensionKind
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticElementReference
import cc.monomer.semantic.api.SemanticEntityDefinition
import cc.monomer.semantic.api.SemanticEntityKind
import cc.monomer.semantic.api.SemanticMeasureAggregation
import cc.monomer.semantic.api.SemanticMeasureDefinition
import cc.monomer.semantic.api.SemanticMetricDefinition
import cc.monomer.semantic.api.SemanticMetricFilter
import cc.monomer.semantic.api.SemanticMetricFilterOperator
import cc.monomer.semantic.api.SemanticMetricInput
import cc.monomer.semantic.api.SemanticMetricPeriodAggregation
import cc.monomer.semantic.api.SemanticMetricType
import cc.monomer.semantic.api.SemanticMetricWindow
import cc.monomer.semantic.api.SemanticMetricWindowUnit
import cc.monomer.semantic.api.SemanticModelDefinition
import cc.monomer.semantic.api.SemanticGroupBy
import cc.monomer.semantic.api.SemanticQueryFilter
import cc.monomer.semantic.api.SemanticQuerySpec
import cc.monomer.semantic.api.SemanticQueryOrder
import cc.monomer.semantic.api.SemanticQueryOrderDirection
import cc.monomer.semantic.api.SemanticQueryOrderTarget
import cc.monomer.semantic.api.resultColumns
import cc.monomer.semantic.api.SemanticSourceRelation
import cc.monomer.semantic.api.SemanticSqlDialect
import cc.monomer.semantic.api.SemanticTextFilterOperator
import cc.monomer.semantic.api.SemanticTimeGranularity
import cc.monomer.semantic.api.SemanticTimestampFilterOperator
import cc.monomer.semantic.api.SemanticInvalidReasonCode
import cc.monomer.semantic.application.SemanticMetricFlowManifest
import cc.monomer.semantic.application.SemanticMetricFlowEngineFaultException
import cc.monomer.semantic.application.SemanticMetricFlowInvalidQueryException
import cc.monomer.semantic.UTC_REPORTING_TIME_ZONE
import cc.monomer.semantic.uncataloguedMetricFlowModel
import cc.monomer.semantic.application.SemanticMetricFlowTimeSpine
import cc.monomer.semantic.application.SemanticMetricFlowRenderResult
import cc.monomer.semantic.application.SemanticMetricFlowGroupByResult
import cc.monomer.semantic.application.SemanticMetricFlowUnsupportedCapability
import cc.monomer.semantic.application.SemanticMetricFlowUnsupportedException
import cc.monomer.semantic.application.SemanticMetricFlowValidationResult
import cc.monomer.semantic.api.SemanticMetricTimeRange
import cc.monomer.semantic.api.SemanticTimeRangeEndpoint
import cc.monomer.semantic.api.SemanticDateFilterOperator
import cc.monomer.semantic.api.SemanticNumberFilterOperator
import cc.monomer.semantic.api.SemanticBooleanFilterOperator
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Focused evidence for the current DSL -> pinned MetricFlow boundary. */
class MetricFlowSemanticRendererCurrentDslTest {
    @Test
    fun `production adapter exposes engine not implemented as unsupported capability`() {
        val fixture = fixture()
        val adapter = MetricFlowSemanticRenderer(
            MetricFlowExplainQuery { _, _ ->
                throw NotImplementedError("engine rendering is not available")
            },
        )

        val result = adapter.render(
            fixture.manifest,
            SemanticQuerySpec(
                metrics = listOf(SemanticElementName("revenue_metric")),
                groupBys = emptyList(),
                metricTimeRange = null,
                filters = emptyList(),
                orderBy = emptyList(),
                rowLimit = 1,
            ),
            listOf(fixture.metricIds.getValue("revenue_metric")),
        )

        val unsupported = assertIs<SemanticMetricFlowRenderResult.Unsupported>(result)
        assertEquals(setOf(SemanticMetricFlowUnsupportedCapability.RELATIONSHIP_JOIN), unsupported.capabilities)
    }

    @Test
    fun `query group by diagnostics identify the failing request index`() {
        val fixture = fixture()
        val modelName = fixture.manifest.models.keys.single()
        val validGroupBy = SemanticGroupBy.Dimension(modelName, SemanticElementName("country"), null)
        val invalidGroupBys = listOf(
            SemanticGroupBy.Dimension(SemanticElementName("missing_model"), SemanticElementName("country"), null) to
                SemanticInvalidReasonCode.QUERY_GROUP_BY_NOT_FOUND,
            SemanticGroupBy.Dimension(modelName, SemanticElementName("missing_dimension"), null) to
                SemanticInvalidReasonCode.QUERY_GROUP_BY_NOT_FOUND,
            SemanticGroupBy.Entity(SemanticElementName("missing_model"), SemanticElementName("order_id")) to
                SemanticInvalidReasonCode.QUERY_GROUP_BY_NOT_FOUND,
            SemanticGroupBy.Entity(modelName, SemanticElementName("missing_entity")) to
                SemanticInvalidReasonCode.QUERY_GROUP_BY_NOT_FOUND,
            SemanticGroupBy.Dimension(modelName, SemanticElementName("ordered_at"), null) to
                SemanticInvalidReasonCode.GROUP_BY_KIND_MISMATCH,
            SemanticGroupBy.Dimension(modelName, SemanticElementName("country"), SemanticTimeGranularity.DAY) to
                SemanticInvalidReasonCode.GROUP_BY_KIND_MISMATCH,
        )

        invalidGroupBys.forEach { (groupBy, code) ->
            val result = MetricFlowSemanticRenderer.production().render(
                fixture.manifest,
                simpleQuery(fixture).copy(groupBys = listOf(validGroupBy, groupBy)),
                listOf(fixture.metricIds.getValue("revenue_metric")),
            )
            val reason = assertIs<SemanticMetricFlowRenderResult.Invalid>(result).reasons.single()
            assertEquals(code, reason.code)
            assertEquals("groupBys[1]", reason.field)
        }
    }

    @Test
    fun `query filter diagnostics identify the failing request index`() {
        val fixture = fixture()
        val modelName = fixture.manifest.models.keys.single()
        val validFilter = assertIs<SemanticQueryFilter.Text>(groupedQuery(fixture).filters.single())
        val invalidTargets = listOf(
            SemanticElementReference(SemanticElementName("missing_model"), SemanticElementName("country")),
            SemanticElementReference(modelName, SemanticElementName("missing_dimension")),
        )

        invalidTargets.forEach { target ->
            val result = MetricFlowSemanticRenderer.production().render(
                fixture.manifest,
                simpleQuery(fixture).copy(filters = listOf(validFilter, validFilter.copy(dimension = target))),
                listOf(fixture.metricIds.getValue("revenue_metric")),
            )
            val reason = assertIs<SemanticMetricFlowRenderResult.Invalid>(result).reasons.single()
            assertEquals(SemanticInvalidReasonCode.UNKNOWN_FILTER_TARGET, reason.code)
            assertEquals("filters[1]", reason.field)
        }
    }

    @Test
    fun `engine failure without a proven request field keeps its location unknown`() {
        val fixture = fixture()
        val adapter = MetricFlowSemanticRenderer(MetricFlowExplainQuery { _, _ ->
            throw IllegalArgumentException("private manifest detail")
        })
        val result = adapter.render(
            fixture.manifest,
            groupedQuery(fixture),
            listOf(fixture.metricIds.getValue("ratio_metric")),
        )
        val reason = assertIs<SemanticMetricFlowRenderResult.Invalid>(result).reasons.single()
        assertEquals(SemanticInvalidReasonCode.QUERY_FILTER_UNRENDERABLE, reason.code)
        assertEquals(null, reason.field)
    }

    @Test
    fun `typed invalid query is returned as input failure without engine details`() {
        val fixture = fixture()
        val adapter = MetricFlowSemanticRenderer(MetricFlowExplainQuery { _, _ ->
            throw InvalidQueryException("private query detail")
        })

        val failure = assertFailsWith<SemanticMetricFlowInvalidQueryException> {
            adapter.render(fixture.manifest, simpleQuery(fixture), listOf(fixture.metricIds.getValue("revenue_metric")))
        }
        assertEquals(SemanticInvalidReasonCode.QUERY_FILTER_UNRENDERABLE, failure.reason)
        assertEquals("MetricFlow rejected the semantic query.", failure.message)
        assertIs<InvalidQueryException>(failure.cause)
    }

    @Test
    fun `SQL template engine fault propagates to the system error boundary`() {
        val fixture = fixture()
        val failure = RenderSqlTemplateException("private SQL template detail")
        val adapter = MetricFlowSemanticRenderer(MetricFlowExplainQuery { _, _ -> throw failure })

        val propagated = assertFailsWith<SemanticMetricFlowEngineFaultException> {
            adapter.render(fixture.manifest, simpleQuery(fixture), listOf(fixture.metricIds.getValue("revenue_metric")))
        }

        assertSame(failure, propagated.cause)
    }

    @Test
    fun `MetricFlow internal engine fault propagates to the system error boundary`() {
        val fixture = fixture()
        val failure = MetricFlowInternalError("private engine detail")
        val adapter = MetricFlowSemanticRenderer(MetricFlowExplainQuery { _, _ -> throw failure })

        val propagated = assertFailsWith<SemanticMetricFlowEngineFaultException> {
            adapter.render(fixture.manifest, simpleQuery(fixture), listOf(fixture.metricIds.getValue("revenue_metric")))
        }

        assertSame(failure, propagated.cause)
    }

    @Test
    fun `typed unsupported query preserves the generic unsupported failure without naming a capability`() {
        val fixture = fixture()
        val failure = FeatureNotSupportedError("private unsupported feature detail")
        val adapter = MetricFlowSemanticRenderer(MetricFlowExplainQuery { _, _ -> throw failure })

        val propagated = assertFailsWith<SemanticMetricFlowUnsupportedException> {
            adapter.render(fixture.manifest, simpleQuery(fixture), listOf(fixture.metricIds.getValue("revenue_metric")))
        }

        assertSame(failure, propagated.cause)
        assertEquals("MetricFlow does not support the semantic request.", propagated.message)
    }

    @Test
    fun `validator typed invalid query remains input failure on validation render and group-by paths`() {
        val fixture = fixture()
        val adapter = validationFailureAdapter(InvalidQueryException("private query detail"))
        val metricIds = listOf(fixture.metricIds.getValue("revenue_metric"))

        val operations = listOf<() -> Any>(
            { adapter.validate(fixture.manifest) },
            { adapter.render(fixture.manifest, simpleQuery(fixture), metricIds) },
            { adapter.listGroupBys(fixture.manifest, metricIds) },
        )
        operations.forEach { operation ->
            val failure = assertFailsWith<SemanticMetricFlowInvalidQueryException> { operation() }
            assertEquals(SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID, failure.reason)
            assertIs<InvalidQueryException>(failure.cause)
        }
    }

    @Test
    fun `group-by discovery retains the default nonempty metric validation rule`() {
        val fixture = fixture()

        val result = MetricFlowSemanticRenderer.production().listGroupBys(fixture.manifest.copy(metrics = emptyMap()), emptyList())

        val invalid = assertIs<SemanticMetricFlowGroupByResult.Invalid>(result)
        assertEquals(SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID, invalid.reasons.single().code)
    }

    @Test
    fun `validator typed unsupported feature propagates on validation render and group-by paths`() {
        val fixture = fixture()
        val failure = FeatureNotSupportedError("private unsupported feature detail")
        val adapter = validationFailureAdapter(failure)
        val metricIds = listOf(fixture.metricIds.getValue("revenue_metric"))
        val operations = listOf<() -> Any>(
            { adapter.validate(fixture.manifest) },
            { adapter.render(fixture.manifest, simpleQuery(fixture), metricIds) },
            { adapter.listGroupBys(fixture.manifest, metricIds) },
        )

        operations.forEach { operation ->
            val propagated = assertFailsWith<SemanticMetricFlowUnsupportedException> { operation() }
            assertSame(failure, propagated.cause)
        }
    }

    @Test
    fun `validator engine faults propagate on validation render and group-by paths`() {
        val fixture = fixture()
        val metricIds = listOf(fixture.metricIds.getValue("revenue_metric"))
        val failures = listOf(RenderSqlTemplateException("private template detail"), MetricFlowInternalError("private engine detail"))

        failures.forEach { failure ->
            val adapter = validationFailureAdapter(failure)
            val operations = listOf<() -> Any>(
                { adapter.validate(fixture.manifest) },
                { adapter.render(fixture.manifest, simpleQuery(fixture), metricIds) },
                { adapter.listGroupBys(fixture.manifest, metricIds) },
            )
            operations.forEach { operation ->
                val propagated = assertFailsWith<SemanticMetricFlowEngineFaultException> { operation() }
                assertSame(failure, propagated.cause)
                assertEquals("MetricFlow could not compute the semantic request.", propagated.message)
            }
        }
    }

    @Test
    fun `validator cancellation propagates on validation render and group-by paths`() {
        val fixture = fixture()
        val failure = java.util.concurrent.CancellationException("request cancelled")
        val adapter = validationFailureAdapter(failure)
        val metricIds = listOf(fixture.metricIds.getValue("revenue_metric"))
        val operations = listOf<() -> Any>(
            { adapter.validate(fixture.manifest) },
            { adapter.render(fixture.manifest, simpleQuery(fixture), metricIds) },
            { adapter.listGroupBys(fixture.manifest, metricIds) },
        )

        operations.forEach { operation ->
            val propagated = assertFailsWith<java.util.concurrent.CancellationException> { operation() }
            assertSame(failure, propagated)
        }
    }

    @Test
    fun `a later validator engine fault is not hidden by an earlier input failure`() {
        val fixture = fixture()
        val fault = MetricFlowInternalError("private engine detail")
        val adapter = MetricFlowSemanticRenderer(
            MetricFlowExplainQuery { engine, request -> engine.explain(request) },
            listOf(
                SemanticManifestValidationRule { throw InvalidQueryException("private query detail") },
                SemanticManifestValidationRule { throw fault },
            ),
        )

        val propagated = assertFailsWith<SemanticMetricFlowEngineFaultException> { adapter.validate(fixture.manifest) }

        assertSame(fault, propagated.cause)
    }

    @Test
    fun `a later validator engine fault does not replace cancellation`() {
        val fixture = fixture()
        val cancellation = java.util.concurrent.CancellationException("request cancelled")
        val adapter = MetricFlowSemanticRenderer(
            MetricFlowExplainQuery { engine, request -> engine.explain(request) },
            listOf(
                SemanticManifestValidationRule { throw cancellation },
                SemanticManifestValidationRule { throw MetricFlowInternalError("private engine detail") },
            ),
        )

        val propagated = assertFailsWith<java.util.concurrent.CancellationException> { adapter.validate(fixture.manifest) }

        assertSame(cancellation, propagated)
    }

    private fun validationFailureAdapter(failure: Throwable): MetricFlowSemanticRenderer =
        MetricFlowSemanticRenderer(
            MetricFlowExplainQuery { engine, request -> engine.explain(request) },
            listOf(SemanticManifestValidationRule { throw failure }),
        )

    @Test
    fun `render maps engine state failures like group-by discovery instead of throwing`() {
        val fixture = fixture()
        val adapter = MetricFlowSemanticRenderer(MetricFlowExplainQuery { _, _ ->
            throw IllegalStateException("private engine state")
        })
        val result = adapter.render(
            fixture.manifest,
            simpleQuery(fixture),
            listOf(fixture.metricIds.getValue("revenue_metric")),
        )
        val reason = assertIs<SemanticMetricFlowRenderResult.Invalid>(result).reasons.single()
        assertEquals(SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID, reason.code)
        assertEquals(null, reason.field)
    }

    @Test
    fun `render lets cancellation propagate instead of mapping it as an engine state failure`() {
        val fixture = fixture()
        val adapter = MetricFlowSemanticRenderer(MetricFlowExplainQuery { _, _ ->
            throw java.util.concurrent.CancellationException("request cancelled")
        })
        kotlin.test.assertFailsWith<java.util.concurrent.CancellationException> {
            adapter.render(fixture.manifest, simpleQuery(fixture), listOf(fixture.metricIds.getValue("revenue_metric")))
        }
    }

    @Test
    fun `production adapter lowers current model fields and all five metric shapes`() {
        val fixture = fixture()
        val adapter = MetricFlowSemanticRenderer(
            MetricFlowExplainQuery { engine, request -> engine.explain(request).copy(sql = "captured sql") },
        )

        assertEquals(SemanticMetricFlowValidationResult.Valid, adapter.validate(fixture.manifest))

        val rendered = assertIs<SemanticMetricFlowRenderResult.Rendered>(
            adapter.render(
                fixture.manifest,
                SemanticQuerySpec(
                    metrics = listOf(SemanticElementName("revenue_metric")),
                    groupBys = emptyList(),
                    metricTimeRange = null,
                    filters = emptyList(),
                    orderBy = emptyList(),
                    rowLimit = 1,
                ),
                listOf(fixture.metricIds.getValue("revenue_metric")),
            ),
        )
        assertEquals("captured sql", rendered.sql)

        val engineManifest = MetricFlowManifestMapper.toEngineManifest(fixture.manifest)
        val model = engineManifest.semanticModels.single()
        assertEquals("orders", model.name)
        assertEquals(null, model.nodeRelation.database)
        assertEquals("analytics", model.nodeRelation.schemaName)
        assertEquals("orders", model.nodeRelation.alias)
        assertEquals("Orders", model.label)
        assertEquals("Current orders", model.description)
        assertEquals(null, model.primaryEntity)
        assertEquals("order_id", model.entities.single { it.type.name == "PRIMARY" }.expr)
        assertEquals("ordered_at", model.defaults?.aggTimeDimension)
        assertEquals("ordered_at", model.dimensions.single { it.name == "ordered_at" }.expr)
        assertEquals(
            "day",
            model.dimensions.single { it.name == "ordered_at" }.typeParams?.timeGranularity?.value,
        )

        val measures = model.measures.associateBy { it.name }
        assertEquals("revenue", measures.getValue("revenue").expr)
        assertEquals("ordered_at", measures.getValue("revenue").aggTimeDimension)
        assertEquals("ordered_at", measures.getValue("snapshot_balance").nonAdditiveDimension?.name)
        assertEquals("PERCENTILE", measures.getValue("median_value").agg.name)
        assertEquals(0.5, measures.getValue("median_value").aggParams?.percentile)
        assertEquals("SUM", measures.getValue("active_flag").agg.name)
        assertEquals(
            "CASE WHEN is_active THEN 1 ELSE 0 END",
            measures.getValue("active_flag").expr,
        )

        val metrics = engineManifest.metrics.associateBy { it.name }
        assertEquals(
            SemanticMetricType.entries.map { it.name }.toSet(),
            fixture.metricDefinitions.map { it.type.name }.toSet(),
        )
        assertEquals("revenue", metrics.getValue("revenue_metric").typeParams.measure?.name)
        assertEquals("revenue_metric", metrics.getValue("ratio_metric").typeParams.numerator?.name)
        assertEquals("revenue_metric", metrics.getValue("derived_metric").typeParams.metrics?.first()?.name)
        val cumulative = assertNotNull(metrics.getValue("cumulative_metric").typeParams.cumulativeTypeParams)
        assertEquals("7 day", cumulative.window?.windowString)
        assertEquals("last", cumulative.periodAgg.name.lowercase())
        val conversion = assertNotNull(metrics.getValue("conversion_metric").typeParams.conversionTypeParams)
        assertEquals("order_count_metric", conversion.baseMetric?.name)
        assertEquals("converted_metric", conversion.conversionMetric?.name)
        assertEquals("order_id", conversion.entity)
        assertEquals("conversion_rate", conversion.calculation.name.lowercase())
        assertEquals("country", conversion.constantProperties?.single()?.baseProperty)
        assertEquals("country", conversion.constantProperties?.single()?.conversionProperty)
        assertEquals("revenue_input", metrics.getValue("derived_metric").typeParams.metrics?.first()?.alias)
        assertEquals("1 day", metrics.getValue("derived_metric").typeParams.metrics?.first()?.offsetWindow?.windowString)
        assertEquals("day", metrics.getValue("derived_metric").typeParams.metrics?.last()?.offsetToGrain)
        assertEquals(
            "{{ Dimension('order_id__country') }} = 'US'",
            metrics.getValue("ratio_metric").typeParams.numerator?.filter?.whereFilters?.single()?.whereSqlTemplate,
        )
    }

    @Test
    fun `adapter lowers primary entity dunders and time grain for group by and filters`() {
        val fixture = fixture()
        val capturedRequest = AtomicReference<MetricFlowExplainRequest>()
        val adapter = MetricFlowSemanticRenderer(
            MetricFlowExplainQuery { engine, request ->
                capturedRequest.set(request)
                engine.explain(request).copy(sql = "captured sql")
            },
        )

        val result = adapter.render(
            fixture.manifest,
            groupedQuery(fixture),
            listOf(fixture.metricIds.getValue("ratio_metric")),
        )

        assertIs<SemanticMetricFlowRenderResult.Rendered>(result)
        assertEquals(
            listOf("order_id__country", "order_id__ordered_at__month"),
            capturedRequest.get().groupByNames,
        )
        assertEquals(
            listOf("{{ Dimension('order_id__country') }} = 'CA'"),
            capturedRequest.get().whereConstraints,
        )
    }

    @Test
    fun `timestamp filters keep seconds and fractions as UTC Workspace wall clocks in engine SQL`() {
        val fixture = hourFixture(fixture())
        val timestampCases = listOf(
            "1900-01-01T00:00:00Z" to "1900-01-01 00:00:00",
            "1900-01-01T00:00:01Z" to "1900-01-01 00:00:01",
            "2026-09-09T12:34:00.1Z" to "2026-09-09 12:34:00.1",
            "2026-09-09T12:34:00.12Z" to "2026-09-09 12:34:00.12",
            "2026-09-09T12:34:00.123Z" to "2026-09-09 12:34:00.123",
            "2026-09-09T12:34:00.1234Z" to "2026-09-09 12:34:00.1234",
            "2026-09-09T12:34:00.12345Z" to "2026-09-09 12:34:00.12345",
            "2026-09-09T12:34:00.123456Z" to "2026-09-09 12:34:00.123456",
            "2026-10-01T00:00:00.123456+09:00" to "2026-09-30 15:00:00.123456",
            "2026-10-01T00:00:00.000000+09:00" to "2026-09-30 15:00:00",
            "1900-01-01T09:00:00+09:00" to "1900-01-01 00:00:00",
        )
        SemanticSqlDialect.entries.forEach { dialect ->
            timestampCases.forEach { (input, expected) ->
                val capturedRequest = AtomicReference<MetricFlowExplainRequest>()
                val adapter = MetricFlowSemanticRenderer(MetricFlowExplainQuery { engine, request ->
                    capturedRequest.set(request)
                    engine.explain(request)
                })
                val result = adapter.render(
                    fixture.manifest.copy(dialect = dialect),
                    simpleQuery(fixture).copy(filters = listOf(SemanticQueryFilter.Timestamp(
                        dimension = SemanticElementReference(
                            fixture.manifest.models.keys.single(),
                            SemanticElementName("ordered_at"),
                        ),
                        operator = SemanticTimestampFilterOperator.LESS_THAN,
                        values = listOf(Instant.parse(input)),
                    ))),
                    listOf(fixture.metricIds.getValue("revenue_metric")),
                )
                val rendered = assertIs<SemanticMetricFlowRenderResult.Rendered>(result, input)
                val literal = wallClockLiteral(dialect, expected)
                assertEquals(
                    listOf("{{ Dimension('order_id__ordered_at__hour') }} < $literal"),
                    capturedRequest.get().whereConstraints,
                    input,
                )
                assertTrue(rendered.sql.contains(literal), rendered.sql)
            }
        }
    }

    @Test
    fun `timestamp ranges in a UTC Workspace use UTC wall clocks and retain metric period semantics`() {
        val fixture = fixture()
        val cases = listOf(
            "2026-09-01T00:00:00Z" to "2026-09-01T00:00",
            "2026-09-01T09:30:01.123456+09:00" to "2026-09-01T00:30:01.123456",
            "2026-09-02T01:00:00+09:00" to "2026-09-01T16:00",
        )
        SemanticSqlDialect.entries.forEach { dialect ->
            cases.forEach { (input, expected) ->
                val captured = AtomicReference<MetricFlowExplainRequest>()
                val adapter = MetricFlowSemanticRenderer(MetricFlowExplainQuery { engine, request ->
                    captured.set(request)
                    engine.explain(request)
                })
                val result = adapter.render(
                    fixture.manifest.copy(dialect = dialect),
                    simpleQuery(fixture).copy(metricTimeRange = SemanticMetricTimeRange(
                        start = SemanticTimeRangeEndpoint.Timestamp(Instant.parse(input)),
                        end = SemanticTimeRangeEndpoint.Timestamp(Instant.parse("2026-09-10T12:34:56.654321Z")),
                    )),
                    listOf(fixture.metricIds.getValue("revenue_metric")),
                )
                val rendered = assertIs<SemanticMetricFlowRenderResult.Rendered>(result, "$dialect: $input")
                assertEquals(expected, captured.get().timeConstraintStart)
                assertEquals("2026-09-10T12:34:56.654321", captured.get().timeConstraintEnd)
                assertTrue(Regex("BETWEEN (?:timestamp )?'2026-09-01' AND (?:timestamp )?'2026-09-10'", RegexOption.IGNORE_CASE).containsMatchIn(rendered.sql), rendered.sql)
            }
        }
    }

    @Test
    fun `date ranges retain the existing engine period boundaries`() {
        val fixture = fixture()
        val captured = AtomicReference<MetricFlowExplainRequest>()
        val adapter = MetricFlowSemanticRenderer(MetricFlowExplainQuery { engine, request ->
            captured.set(request)
            engine.explain(request)
        })
        val result = adapter.render(
            fixture.manifest,
            simpleQuery(fixture).copy(metricTimeRange = SemanticMetricTimeRange(
                start = SemanticTimeRangeEndpoint.Date(LocalDate.parse("2026-09-01")),
                end = SemanticTimeRangeEndpoint.Date(LocalDate.parse("2026-09-10")),
            )),
            listOf(fixture.metricIds.getValue("revenue_metric")),
        )
        val rendered = assertIs<SemanticMetricFlowRenderResult.Rendered>(result)
        assertEquals("2026-09-01", captured.get().timeConstraintStart)
        assertEquals("2026-09-10", captured.get().timeConstraintEnd)
        assertTrue(Regex("BETWEEN (?:timestamp )?'2026-09-01' AND (?:timestamp )?'2026-09-10'", RegexOption.IGNORE_CASE).containsMatchIn(rendered.sql), rendered.sql)
    }

    @Test
    fun `weekly August metric time range includes complete boundary weeks`() {
        val fixture = fixture()
        val query = simpleQuery(fixture).copy(
            groupBys = listOf(SemanticGroupBy.MetricTime(SemanticTimeGranularity.WEEK)),
            metricTimeRange = SemanticMetricTimeRange(
                start = SemanticTimeRangeEndpoint.Date(LocalDate.parse("2026-08-01")),
                end = SemanticTimeRangeEndpoint.Date(LocalDate.parse("2026-08-31")),
            ),
            rowLimit = 100,
        )
        val sql = renderBigQuerySql(fixture, query)
        assertTrue("DATE_TRUNC(ordered_at, isoweek)" in sql, sql)
        assertTrue(
            "WHERE DATE_TRUNC(ordered_at, day) BETWEEN '2026-07-27' AND '2026-09-06' GROUP BY" in sql,
            sql,
        )
    }

    @Test
    fun `date month filters compare calendar days before weekly aggregation`() {
        val fixture = fixture()
        val dimension = SemanticElementReference(
            fixture.manifest.models.keys.single(), SemanticElementName("ordered_at"),
        )
        val query = simpleQuery(fixture).copy(
            groupBys = listOf(SemanticGroupBy.MetricTime(SemanticTimeGranularity.WEEK)),
            metricTimeRange = SemanticMetricTimeRange(
                start = SemanticTimeRangeEndpoint.Date(LocalDate.parse("2026-08-01")),
                end = SemanticTimeRangeEndpoint.Date(LocalDate.parse("2026-08-31")),
            ),
            filters = listOf(
                SemanticQueryFilter.Date(dimension, SemanticDateFilterOperator.GREATER_THAN_OR_EQUAL, listOf(LocalDate.parse("2026-08-01"))),
                SemanticQueryFilter.Date(dimension, SemanticDateFilterOperator.LESS_THAN, listOf(LocalDate.parse("2026-09-01"))),
            ),
            rowLimit = 100,
        )
        val sql = renderBigQuerySql(fixture, query)
        assertTrue("DATE_TRUNC(ordered_at, day) AS order_id__ordered_at__day" in sql, sql)
        assertTrue("DATE_TRUNC(ordered_at, isoweek) AS metric_time__week" in sql, sql)
        assertTrue(
            Regex("SELECT CAST\\(metric_time__week AS DATE\\) AS `[^`]+` , SUM\\(revenue_metric\\) AS `[^`]+` FROM \\(")
                .containsMatchIn(sql),
            sql,
        )
        assertTrue(
            Regex(
                "revenue AS revenue_metric FROM analytics\\.orders \\w+ " +
                    "WHERE DATE_TRUNC\\(ordered_at, day\\) BETWEEN '2026-07-27' AND '2026-09-06' " +
                    "\\) \\w+ WHERE " +
                    "\\( CAST\\(order_id__ordered_at__day AS DATE\\) >= DATE '2026-08-01' \\) AND " +
                    "\\( CAST\\(order_id__ordered_at__day AS DATE\\) < DATE '2026-09-01' \\) GROUP BY",
            ).containsMatchIn(sql),
            sql,
        )
    }

    @Test
    fun `BigQuery partitioned order lines renders production SQL for August weekly fixture verification`() {
        val base = fixture()
        val modelName = SemanticElementName("mcp_qa_joo38_order_lines")
        val dateName = SemanticElementName("order_date")
        val metricNames = listOf("gross_revenue", "net_revenue")
        val address = CatalogPhysicalAddress("monomer-dw-test", "monomer_test", "order_lines", null)
        val source = base.manifest.models.values.single().definition
        val model = source.copy(
            name = modelName,
            label = null,
            description = null,
            sourceRelations = listOf(SemanticSourceRelation(
                base.manifest.connectorId, "monomer-dw-test", "monomer_test", "order_lines",
            )),
            aggregationTimeDimension = dateName,
            entities = listOf(source.entities.single().copy(
                name = SemanticElementName("order_item_id"), expression = "order_item_id",
            )),
            dimensions = listOf("order_date", "order_ts").map { name ->
                SemanticDimensionDefinition(
                    name = SemanticElementName(name), type = SemanticDimensionKind.TIME,
                    expression = name, granularity = SemanticTimeGranularity.DAY,
                    label = null, description = null,
                    isPartition = false,
                    timeKind = null,
                )
            },
            measures = metricNames.map { name ->
                measure(name, SemanticMeasureAggregation.SUM, name).copy(aggregationTimeDimension = dateName)
            },
        )
        val metrics = metricNames.associate { name ->
            MetricId.generate() to SemanticMetricDefinition.Simple(
                schemaVersion = 3u, name = SemanticElementName("mcp_qa_joo38_$name"),
                label = null, description = null, timeGranularity = SemanticTimeGranularity.DAY,
                filters = emptyList(), semanticModelName = modelName, measure = SemanticElementName(name),
                display = null,
            )
        }
        val manifest = base.manifest.copy(
            dialect = SemanticSqlDialect.BIGQUERY,
            models = mapOf(modelName to uncataloguedMetricFlowModel(model, address, SemanticSqlDialect.POSTGRESQL)),
            metrics = metrics,
        )
        val week = SemanticGroupBy.MetricTime(SemanticTimeGranularity.WEEK)
        val timestampDimension = SemanticElementReference(modelName, SemanticElementName("order_ts"))
        val dateDimension = SemanticElementReference(modelName, dateName)
        val strictQuery = SemanticQuerySpec(
            metrics = metrics.values.map { it.name }, groupBys = listOf(week),
            metricTimeRange = SemanticMetricTimeRange(
                SemanticTimeRangeEndpoint.Date(LocalDate.parse("2026-08-01")),
                SemanticTimeRangeEndpoint.Date(LocalDate.parse("2026-08-31")),
            ),
            filters = listOf(
                SemanticQueryFilter.Date(timestampDimension, SemanticDateFilterOperator.GREATER_THAN_OR_EQUAL,
                    listOf(LocalDate.parse("2026-08-01"))),
                SemanticQueryFilter.Date(timestampDimension, SemanticDateFilterOperator.LESS_THAN,
                    listOf(LocalDate.parse("2026-09-01"))),
            ),
            orderBy = listOf(SemanticQueryOrder(SemanticQueryOrderTarget.GroupBy(week), SemanticQueryOrderDirection.ASC)),
            rowLimit = 100,
        )
        val dateFilters = listOf(
            SemanticQueryFilter.Date(dateDimension, SemanticDateFilterOperator.GREATER_THAN_OR_EQUAL,
                listOf(LocalDate.parse("2026-08-01"))),
            SemanticQueryFilter.Date(dateDimension, SemanticDateFilterOperator.LESS_THAN,
                listOf(LocalDate.parse("2026-09-01"))),
        )
        val queries = linkedMapOf(
            "strict" to strictQuery,
            "strict-date" to strictQuery.copy(filters = strictQuery.filters + dateFilters),
            "bucket" to strictQuery.copy(filters = emptyList()),
            "date-no-range" to strictQuery.copy(metricTimeRange = null, filters = dateFilters),
            "strict-no-range" to strictQuery.copy(metricTimeRange = null),
        )
        val outputDirectory = Path.of("build", "joo39-bigquery-product-sql")
        Files.createDirectories(outputDirectory)
        queries.forEach { (variant, query) ->
            val rendered = assertIs<SemanticMetricFlowRenderResult.Rendered>(
                MetricFlowSemanticRenderer.production().render(manifest, query, metrics.keys.toList()), variant,
            )
            val sql = rendered.sql
            assertEquals(query.resultColumns().map { it.sqlName }, rendered.outputColumns.map { it.sqlName })
            assertTrue("FROM monomer-dw-test.monomer_test.order_lines " in sql, sql)
            assertTrue("DATE_TRUNC(order_date, isoweek)" in sql, sql)
            assertTrue("DATETIME_TRUNC" !in sql, sql)
            if (query.metricTimeRange != null) {
                assertTrue("DATE_TRUNC(order_date, day) BETWEEN '2026-07-27' AND '2026-09-06'" in sql, sql)
            } else {
                assertTrue("BETWEEN" !in sql, sql)
            }
            assertTrue(Regex("CAST\\((?:metric_time__week|DATE_TRUNC\\(order_date, isoweek\\)) AS DATE\\) AS `__monomer_result_0`").containsMatchIn(sql), sql)
            if (query.filters.any { it.dimension == timestampDimension }) {
                assertTrue("DATE_TRUNC(order_ts, day) AS order_item_id__order_ts__day" in sql, sql)
                assertTrue("CAST(order_item_id__order_ts__day AS DATE) >= DATE '2026-08-01'" in sql, sql)
                assertTrue("CAST(order_item_id__order_ts__day AS DATE) < DATE '2026-09-01'" in sql, sql)
            }
            if (query.filters.any { it.dimension == dateDimension }) {
                assertTrue("CAST(order_item_id__order_date__day AS DATE) >= DATE '2026-08-01'" in sql, sql)
                assertTrue("CAST(order_item_id__order_date__day AS DATE) < DATE '2026-09-01'" in sql, sql)
            }
            Files.writeString(outputDirectory.resolve("$variant.sql"), sql + "\n")
        }
    }

    private fun renderBigQuerySql(fixture: Fixture, query: SemanticQuerySpec): String {
        val rendered = assertIs<SemanticMetricFlowRenderResult.Rendered>(
            MetricFlowSemanticRenderer.production().render(
                fixture.manifest.copy(dialect = SemanticSqlDialect.BIGQUERY), query,
                listOf(fixture.metricIds.getValue("revenue_metric")),
            ),
        )
        return rendered.sql.lineSequence().filterNot { it.trimStart().startsWith("--") }
            .joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }

    @Test
    fun `lists request local common dimensions entities and renderable metric time grains`() {
        val fixture = fixture()
        val result = MetricFlowSemanticRenderer.production().listGroupBys(
            fixture.manifest,
            listOf(fixture.metricIds.getValue("revenue_metric")),
        )

        val listed = assertIs<SemanticMetricFlowGroupByResult.Listed>(result)
        assertTrue(
            SemanticGroupBy.Dimension(
                semanticModelName = fixture.manifest.models.keys.single(),
                name = SemanticElementName("country"),
                granularity = null,
            ) in listed.groupBys,
        )
        assertTrue(
            SemanticGroupBy.Entity(
                semanticModelName = fixture.manifest.models.keys.single(),
                name = SemanticElementName("order_id"),
            ) in listed.groupBys,
        )
        val metricTimes = listed.groupBys.filterIsInstance<SemanticGroupBy.MetricTime>()
        assertEquals(
            listOf(
                SemanticTimeGranularity.DAY,
                SemanticTimeGranularity.WEEK,
                SemanticTimeGranularity.MONTH,
                SemanticTimeGranularity.QUARTER,
                SemanticTimeGranularity.YEAR,
            ),
            metricTimes.map { it.granularity },
        )
        metricTimes.forEach { metricTime ->
            assertIs<SemanticMetricFlowRenderResult.Rendered>(
                MetricFlowSemanticRenderer.production().render(
                    fixture.manifest,
                    simpleQuery(fixture).copy(groupBys = listOf(metricTime)),
                    listOf(fixture.metricIds.getValue("revenue_metric")),
                ),
            )
        }
    }

    @Test
    fun `simple metric time renders from the model while a windowed cumulative query needs a spine`() {
        val fixture = fixture()
        val adapter = MetricFlowSemanticRenderer.production()

        val metricTime = adapter.render(
            fixture.manifest,
            simpleQuery(fixture).copy(
                groupBys = listOf(SemanticGroupBy.MetricTime(SemanticTimeGranularity.DAY)),
            ),
            listOf(fixture.metricIds.getValue("revenue_metric")),
        )
        assertIs<SemanticMetricFlowRenderResult.Rendered>(metricTime)

        val cumulative = adapter.render(
            fixture.manifest,
            simpleQuery(fixture).copy(
                metrics = listOf(SemanticElementName("cumulative_metric")),
                groupBys = listOf(SemanticGroupBy.MetricTime(SemanticTimeGranularity.DAY)),
            ),
            listOf(fixture.metricIds.getValue("cumulative_metric")),
        )
        assertEquals(
            setOf(SemanticMetricFlowUnsupportedCapability.TIME_SPINE_UNAVAILABLE),
            assertIs<SemanticMetricFlowRenderResult.Unsupported>(cumulative).capabilities,
        )

        val options = adapter.listGroupBys(
            fixture.manifest,
            listOf(fixture.metricIds.getValue("cumulative_metric")),
        )
        assertIs<SemanticMetricFlowGroupByResult.Listed>(options)
    }

    @Test
    fun `discovery resolves cumulative metadata and conversion remains renderable without physical spine`() {
        val fixture = fixture()
        val adapter = MetricFlowSemanticRenderer.production()
        for (name in listOf("cumulative_metric", "conversion_metric")) {
            assertIs<SemanticMetricFlowGroupByResult.Listed>(adapter.listGroupBys(
                fixture.manifest, listOf(fixture.metricIds.getValue(name)),
            ))
        }
        assertIs<SemanticMetricFlowRenderResult.Rendered>(adapter.render(
            fixture.manifest, queryFor("conversion_metric"), listOf(fixture.metricIds.getValue("conversion_metric")),
        ))
    }

    @Test
    fun `configured daily spine activates windowed cumulative SQL`() {
        val fixture = fixture()
        val manifest = fixture.manifest.copy(
            timeSpines = listOf(
                SemanticMetricFlowTimeSpine(
                    physicalAddress = CatalogPhysicalAddress(null, "analytics", "time_spine_day", null),
                    primaryColumnName = "date_day",
                    granularity = SemanticTimeGranularity.DAY,
                ),
            ),
        )

        val result = MetricFlowSemanticRenderer.production().render(
            manifest,
            simpleQuery(fixture).copy(
                metrics = listOf(SemanticElementName("cumulative_metric")),
                groupBys = listOf(SemanticGroupBy.MetricTime(SemanticTimeGranularity.DAY)),
            ),
            listOf(fixture.metricIds.getValue("cumulative_metric")),
        )

        val rendered = assertIs<SemanticMetricFlowRenderResult.Rendered>(result)
        assertTrue("analytics.time_spine_day" in rendered.sql)
        assertTrue("warehouse.analytics.time_spine_day" !in rendered.sql)
    }

    @Test
    fun `mapper projects every standard time spine grain`() {
        val fixture = fixture()
        val manifest = fixture.manifest.copy(
            timeSpines = SemanticTimeGranularity.entries.map { granularity ->
                SemanticMetricFlowTimeSpine(
                    physicalAddress = CatalogPhysicalAddress(
                        null,
                        "analytics",
                        "time_spine_${granularity.name.lowercase()}",
                        null,
                    ),
                    primaryColumnName = "time_value",
                    granularity = granularity,
                )
            },
        )

        val engineManifest = MetricFlowManifestMapper.toEngineManifest(manifest)

        assertEquals(
            SemanticTimeGranularity.entries.map { it.name.lowercase() },
            engineManifest.projectConfiguration.timeSpines.map { it.primaryColumn.timeGranularity.value },
        )
    }

    @Test
    fun `production explain renders categorical and time group by with definition and query filters`() {
        val fixture = fixture()
        val result = MetricFlowSemanticRenderer.production().render(
            fixture.manifest,
            groupedQuery(fixture),
            listOf(fixture.metricIds.getValue("ratio_metric")),
        )

        val rendered = assertIs<SemanticMetricFlowRenderResult.Rendered>(result)
        assertTrue(rendered.sql.isNotBlank())
    }

    @Test
    fun `production explain applies stored simple metric filter`() {
        val fixture = fixture()
        val revenueMetricId = fixture.metricIds.getValue("revenue_metric")
        val revenueMetric = assertIs<SemanticMetricDefinition.Simple>(
            fixture.manifest.metrics.getValue(revenueMetricId),
        )
        val filteredManifest = fixture.manifest.copy(
            metrics = fixture.manifest.metrics + (
                revenueMetricId to revenueMetric.copy(
                    filters = listOf(
                        SemanticMetricFilter.Predicate(
                            dimension = SemanticElementReference(
                                fixture.manifest.models.keys.single(),
                                SemanticElementName("country"),
                            ),
                            operator = SemanticMetricFilterOperator.EQ,
                            values = listOf("KR"),
                        ),
                    ),
                )
            ),
        )

        val rendered = assertIs<SemanticMetricFlowRenderResult.Rendered>(
            MetricFlowSemanticRenderer.production().render(
                filteredManifest,
                simpleQuery(fixture),
                listOf(revenueMetricId),
            ),
        )
        assertTrue("KR" in rendered.sql, rendered.sql)
    }

    @Test
    fun `stored metric filters retain their own dimension scope in a multi metric query`() {
        val fixture = fixture()
        val orders = fixture.manifest.models.values.single()
        val revenueId = fixture.metricIds.getValue("revenue_metric")
        val revenue = assertIs<SemanticMetricDefinition.Simple>(fixture.manifest.metrics.getValue(revenueId))
            .copy(filters = listOf(SemanticMetricFilter.Predicate(
                SemanticElementReference(orders.definition.name, SemanticElementName("country")),
                SemanticMetricFilterOperator.EQ,
                listOf("KR"),
            )))
        val subscriptions = orders.definition.copy(
            name = SemanticElementName("subscriptions"),
            label = null,
            sourceRelations = listOf(SemanticSourceRelation(fixture.manifest.connectorId, null, "analytics", "subscriptions")),
            entities = listOf(orders.definition.entities.single().copy(
                name = SemanticElementName("subscription_id"),
                expression = "subscription_id",
            )),
            dimensions = orders.definition.dimensions.filter { it.type == SemanticDimensionKind.TIME },
            measures = listOf(measure("subscription_count", SemanticMeasureAggregation.COUNT, "1")),
        )
        val subscriptionMetric = revenue.copy(
            name = SemanticElementName("subscription_metric"),
            label = null,
            semanticModelName = subscriptions.name,
            measure = SemanticElementName("subscription_count"),
            filters = emptyList(),
        )
        val subscriptionId = MetricId.generate()
        val manifest = fixture.manifest.copy(
            models = fixture.manifest.models + (subscriptions.name to uncataloguedMetricFlowModel(
                subscriptions, CatalogPhysicalAddress(null, "analytics", "subscriptions", null),
                SemanticSqlDialect.POSTGRESQL,
            )),
            metrics = mapOf(revenueId to revenue, subscriptionId to subscriptionMetric),
        )
        val query = simpleQuery(fixture).copy(metrics = listOf(revenue.name, subscriptionMetric.name))
        val validation = cc.monomer.metricflow.application.engine.MetricFlowEngine(
            MetricFlowManifestMapper.toEngineManifest(manifest), ControlMetricFlowSqlPlanRendererRegistry,
        ).validateManifest()
        assertTrue(!validation.hasBlockingIssues, validation.toString())
        val result = MetricFlowSemanticRenderer.production().render(
            manifest,
            query,
            listOf(revenueId, subscriptionId),
        )
        val rendered = assertIs<SemanticMetricFlowRenderResult.Rendered>(result, result.toString())
        assertTrue("KR" in rendered.sql, rendered.sql)
        assertEquals(listOf("metric.revenue_metric", "metric.subscription_metric"), rendered.outputColumns.map { it.name.value })
        val queryFilterResult = MetricFlowSemanticRenderer.production().render(
            manifest,
            query.copy(filters = listOf(SemanticQueryFilter.Text(
                SemanticElementReference(orders.definition.name, SemanticElementName("country")),
                SemanticTextFilterOperator.EQUALS,
                listOf("KR"),
            ))),
            listOf(revenueId, subscriptionId),
        )
        assertEquals(
            SemanticInvalidReasonCode.UNKNOWN_FILTER_TARGET,
            assertIs<SemanticMetricFlowRenderResult.Invalid>(queryFilterResult).reasons.single().code,
        )

        val ratio = assertIs<SemanticMetricDefinition.Ratio>(
            fixture.manifest.metrics.getValue(fixture.metricIds.getValue("ratio_metric")),
        ).copy(
            numerator = input("revenue_metric", filter = revenue.filters.single()),
            denominator = input("subscription_metric"),
        )
        val ratioId = fixture.metricIds.getValue("ratio_metric")
        val ratioResult = MetricFlowSemanticRenderer.production().render(
            manifest.copy(metrics = manifest.metrics + (ratioId to ratio)),
            query.copy(metrics = listOf(ratio.name)),
            listOf(ratioId),
        )
        val renderedRatio = assertIs<SemanticMetricFlowRenderResult.Rendered>(ratioResult, ratioResult.toString())
        assertTrue("KR" in renderedRatio.sql, renderedRatio.sql)
    }

    @Test
    fun `text filter values stay inside one string literal on every dialect`() {
        val fixture = fixture()
        val value = "O'Brien \\' OR TRUE --\nC:\\"
        val standardLiteral = "'O''Brien \\'' OR TRUE --\nC:\\'"
        val backslashDoubledLiteral = "'O''Brien \\\\'' OR TRUE --\nC:\\\\'"
        val backslashEscapedLiteral = "'O\\'Brien \\\\\\' OR TRUE --\\nC:\\\\'"
        val expectedLiterals = mapOf(
            SemanticSqlDialect.POSTGRESQL to "E$backslashDoubledLiteral",
            SemanticSqlDialect.TRINO to standardLiteral,
            SemanticSqlDialect.DUCKDB to standardLiteral,
            SemanticSqlDialect.REDSHIFT to backslashDoubledLiteral,
            SemanticSqlDialect.SNOWFLAKE to backslashDoubledLiteral,
            SemanticSqlDialect.BIGQUERY to backslashEscapedLiteral,
            SemanticSqlDialect.DATABRICKS to backslashEscapedLiteral,
        )
        val query = groupedQuery(fixture).let { grouped ->
            grouped.copy(filters = listOf(
                assertIs<SemanticQueryFilter.Text>(grouped.filters.single()).copy(values = listOf(value)),
            ))
        }
        SemanticSqlDialect.entries.forEach { dialect ->
            val captured = AtomicReference<MetricFlowExplainRequest>()
            val adapter = MetricFlowSemanticRenderer(MetricFlowExplainQuery { engine, request ->
                captured.set(request)
                engine.explain(request)
            })
            val result = adapter.render(
                fixture.manifest.copy(dialect = dialect),
                query,
                listOf(fixture.metricIds.getValue("ratio_metric")),
            )
            assertIs<SemanticMetricFlowRenderResult.Rendered>(result, "$dialect: $result")
            assertEquals(
                listOf("{{ Dimension('order_id__country') }} = ${expectedLiterals.getValue(dialect)}"),
                captured.get().whereConstraints,
                dialect.name,
            )
        }
    }

    @Test
    fun `negative query filters include null rows on every dialect and kind`() {
        val fixture = fixture()
        val country = SemanticElementReference(SemanticElementName("orders"), SemanticElementName("country"))
        val time = SemanticElementReference(SemanticElementName("orders"), SemanticElementName("ordered_at"))
        val countryTemplate = "{{ Dimension('order_id__country') }}"
        val timeTemplate = "{{ Dimension('order_id__ordered_at__day') }}"
        val dateOperand = "CAST($timeTemplate AS DATE)"
        val date = LocalDate.parse("2026-08-01")
        val timestamp = Instant.parse("2026-08-01T00:00:00Z")
        val cases = listOf(
            SemanticQueryFilter.Text(country, SemanticTextFilterOperator.NOT_EQUALS, listOf("CA")) to
                "($countryTemplate <> 'CA' OR $countryTemplate IS NULL)",
            SemanticQueryFilter.Text(country, SemanticTextFilterOperator.NOT_IN, listOf("CA", "US")) to
                "($countryTemplate NOT IN ('CA', 'US') OR $countryTemplate IS NULL)",
            SemanticQueryFilter.Number(country, SemanticNumberFilterOperator.NOT_EQUALS, listOf(BigDecimal("1.5"))) to
                "($countryTemplate <> 1.5 OR $countryTemplate IS NULL)",
            SemanticQueryFilter.Number(country, SemanticNumberFilterOperator.NOT_IN, listOf(BigDecimal("1.5"), BigDecimal("2"))) to
                "($countryTemplate NOT IN (1.5, 2) OR $countryTemplate IS NULL)",
            SemanticQueryFilter.Boolean(country, SemanticBooleanFilterOperator.NOT_EQUALS, listOf(true)) to
                "($countryTemplate <> TRUE OR $countryTemplate IS NULL)",
            SemanticQueryFilter.Date(time, SemanticDateFilterOperator.NOT_EQUALS, listOf(date)) to
                "($dateOperand <> DATE '2026-08-01' OR $dateOperand IS NULL)",
            SemanticQueryFilter.Date(time, SemanticDateFilterOperator.NOT_IN, listOf(date, date.plusDays(1))) to
                "($dateOperand NOT IN (DATE '2026-08-01', DATE '2026-08-02') OR $dateOperand IS NULL)",
        )
        val hourTemplate = "{{ Dimension('order_id__ordered_at__hour') }}"
        SemanticSqlDialect.entries.forEach { dialect ->
            cases.forEach { (filter, expected) -> assertQueryFilterSql(fixture, dialect, listOf(filter), listOf(expected)) }
            val first = wallClockLiteral(dialect, "2026-08-01 00:00:00")
            val second = wallClockLiteral(dialect, "2026-08-01 00:01:00")
            listOf(
                SemanticQueryFilter.Timestamp(time, SemanticTimestampFilterOperator.NOT_EQUALS, listOf(timestamp)) to
                    "($hourTemplate <> $first OR $hourTemplate IS NULL)",
                SemanticQueryFilter.Timestamp(time, SemanticTimestampFilterOperator.NOT_IN, listOf(timestamp, timestamp.plusSeconds(60))) to
                    "($hourTemplate NOT IN ($first, $second) OR $hourTemplate IS NULL)",
            ).forEach { (filter, expected) -> assertQueryFilterSql(hourFixture(fixture), dialect, listOf(filter), listOf(expected)) }
        }
    }

    @Test
    fun `text empty includes blank text while other null tests retain null semantics on every dialect`() {
        val fixture = fixture()
        val country = SemanticElementReference(SemanticElementName("orders"), SemanticElementName("country"))
        val time = SemanticElementReference(SemanticElementName("orders"), SemanticElementName("ordered_at"))
        val countryTemplate = "{{ Dimension('order_id__country') }}"
        val timeTemplate = "{{ Dimension('order_id__ordered_at__day') }}"
        val filters = listOf(
            SemanticQueryFilter.Text(country, SemanticTextFilterOperator.IS_NULL, emptyList()),
            SemanticQueryFilter.Text(country, SemanticTextFilterOperator.IS_NOT_NULL, emptyList()),
            SemanticQueryFilter.Number(country, SemanticNumberFilterOperator.IS_NULL, emptyList()),
            SemanticQueryFilter.Number(country, SemanticNumberFilterOperator.IS_NOT_NULL, emptyList()),
            SemanticQueryFilter.Boolean(country, SemanticBooleanFilterOperator.IS_NULL, emptyList()),
            SemanticQueryFilter.Boolean(country, SemanticBooleanFilterOperator.IS_NOT_NULL, emptyList()),
            SemanticQueryFilter.Date(time, SemanticDateFilterOperator.IS_NULL, emptyList()),
            SemanticQueryFilter.Date(time, SemanticDateFilterOperator.IS_NOT_NULL, emptyList()),
            SemanticQueryFilter.Timestamp(time, SemanticTimestampFilterOperator.IS_NULL, emptyList()),
            SemanticQueryFilter.Timestamp(time, SemanticTimestampFilterOperator.IS_NOT_NULL, emptyList()),
        )
        val expected = listOf(
            "($countryTemplate IS NULL OR $countryTemplate = '')",
            "($countryTemplate IS NOT NULL AND $countryTemplate <> '')",
            "$countryTemplate IS NULL", "$countryTemplate IS NOT NULL",
            "$countryTemplate IS NULL", "$countryTemplate IS NOT NULL",
            "$timeTemplate IS NULL", "$timeTemplate IS NOT NULL",
            "$timeTemplate IS NULL", "$timeTemplate IS NOT NULL",
        )
        SemanticSqlDialect.entries.forEach { dialect -> assertQueryFilterSql(fixture, dialect, filters, expected) }
    }

    @Test
    fun `date filters cast the dimension at its declared grain on every dialect`() {
        val fixture = fixture()
        val time = SemanticElementReference(SemanticElementName("orders"), SemanticElementName("ordered_at"))
        val filters = listOf(
            SemanticQueryFilter.Date(time, SemanticDateFilterOperator.GREATER_THAN_OR_EQUAL, listOf(LocalDate.parse("2026-08-01"))),
            SemanticQueryFilter.Date(time, SemanticDateFilterOperator.LESS_THAN, listOf(LocalDate.parse("2026-09-01"))),
        )
        listOf(SemanticTimeGranularity.DAY, SemanticTimeGranularity.MONTH).forEach { grain ->
            val metricId = fixture.metricIds.getValue("revenue_metric")
            val metric = assertIs<SemanticMetricDefinition.Simple>(fixture.manifest.metrics.getValue(metricId))
            val manifest = fixture.manifest.copy(
                models = fixture.manifest.models.mapValues { (_, model) -> model.copy(
                    definition = model.definition.copy(dimensions = model.definition.dimensions.map { dimension ->
                        if (dimension.name == time.name) dimension.copy(granularity = grain) else dimension
                    }),
                ) },
                metrics = mapOf(metricId to metric.copy(timeGranularity = grain)),
            )
            val template = "{{ Dimension('order_id__ordered_at__${grain.name.lowercase()}') }}"
            SemanticSqlDialect.entries.forEach { dialect ->
                assertQueryFilterSql(fixture.copy(manifest = manifest), dialect, filters, listOf(
                    "CAST($template AS DATE) >= DATE '2026-08-01'",
                    "CAST($template AS DATE) < DATE '2026-09-01'",
                ))
            }
        }
    }

    @Test
    fun `template delimiters in text values compare as literal text on every dialect`() {
        val fixture = fixture()
        val country = SemanticElementReference(SemanticElementName("orders"), SemanticElementName("country"))
        val template = "{{ Dimension('order_id__country') }}"
        val cases = mapOf(
            "{{" to "('{' || '{')",
            "}}" to "('}' || '}')",
            "{{x}}" to "('{' || '{x}' || '}')",
            "{%x%}" to "('{' || '%x%' || '}')",
            "{#x#}" to "('{' || '#x#' || '}')",
            "{{{}}}" to "('{' || '{' || '{}' || '}' || '}')",
            "😀{{x}}😀" to "('😀{' || '{x}' || '}😀')",
        )
        SemanticSqlDialect.entries.forEach { dialect ->
            cases.forEach { (value, literal) ->
                assertQueryFilterSql(fixture, dialect, listOf(
                    SemanticQueryFilter.Text(country, SemanticTextFilterOperator.EQUALS, listOf(value)),
                ), listOf("$template = $literal"))
            }
        }
    }

    @Test
    fun `template looking text remains escaped text rather than a dimension reference`() {
        val fixture = fixture()
        val country = SemanticElementReference(SemanticElementName("orders"), SemanticElementName("country"))
        val value = "{{ Dimension('order_id__ordered_at__day') }}"
        SemanticSqlDialect.entries.forEach { dialect ->
            val literal = when (dialect) {
                SemanticSqlDialect.BIGQUERY, SemanticSqlDialect.DATABRICKS ->
                    "('{' || '{ Dimension(\\'order_id__ordered_at__day\\') }' || '}')"
                else -> "('{' || '{ Dimension(''order_id__ordered_at__day'') }' || '}')"
            }
            assertQueryFilterSql(fixture, dialect, listOf(
                SemanticQueryFilter.Text(country, SemanticTextFilterOperator.EQUALS, listOf(value)),
            ), listOf("{{ Dimension('order_id__country') }} = $literal"))
        }
    }

    private fun assertQueryFilterSql(
        fixture: Fixture,
        dialect: SemanticSqlDialect,
        filters: List<SemanticQueryFilter>,
        expectedTemplates: List<String>,
    ) {
        val captured = AtomicReference<MetricFlowExplainRequest>()
        val adapter = MetricFlowSemanticRenderer(MetricFlowExplainQuery { engine, request ->
            captured.set(request)
            engine.explain(request)
        })
        val result = adapter.render(
            fixture.manifest.copy(dialect = dialect),
            simpleQuery(fixture).copy(filters = filters),
            listOf(fixture.metricIds.getValue("revenue_metric")),
        )
        val rendered = assertIs<SemanticMetricFlowRenderResult.Rendered>(result, "$dialect: $result")
        assertEquals(expectedTemplates, captured.get().whereConstraints, dialect.name)
        expectedTemplates.forEach { expected ->
            val sqlExpression = expected
                .replace("{{ Dimension('order_id__country') }}", "order_id__country")
                .replace("{{ Dimension('order_id__ordered_at__day') }}", "order_id__ordered_at__day")
                .replace("{{ Dimension('order_id__ordered_at__month') }}", "order_id__ordered_at__month")
                .replace("{{ Dimension('order_id__ordered_at__hour') }}", "order_id__ordered_at__hour")
            assertTrue(sqlExpression in rendered.sql, "$dialect: ${rendered.sql}")
        }
    }

    @Test
    fun `two depth model and spine render schema qualified SQL with canonical addresses`() {
        listOf(SemanticSqlDialect.POSTGRESQL, SemanticSqlDialect.REDSHIFT).forEach { dialect ->
            val fixture = fixture()
            val modelAddress = CatalogPhysicalAddress(null, "analytics", "orders", null)
            val spineAddress = CatalogPhysicalAddress(null, "analytics", "time_spine_day", null)
            val manifest = fixture.manifest.copy(
                dialect = dialect,
                timeSpines = listOf(
                    SemanticMetricFlowTimeSpine(spineAddress, "date_day", SemanticTimeGranularity.DAY),
                ),
            )
            val adapter = MetricFlowSemanticRenderer.production()
            val simple = assertIs<SemanticMetricFlowRenderResult.Rendered>(
                adapter.render(manifest, simpleQuery(fixture), listOf(fixture.metricIds.getValue("revenue_metric"))),
            )
            assertTrue("FROM analytics.orders" in simple.sql, "$dialect: ${simple.sql}")

            val cumulative = assertIs<SemanticMetricFlowRenderResult.Rendered>(
                adapter.render(
                    manifest,
                    simpleQuery(fixture).copy(
                        metrics = listOf(SemanticElementName("cumulative_metric")),
                        groupBys = listOf(SemanticGroupBy.MetricTime(SemanticTimeGranularity.DAY)),
                    ),
                    listOf(fixture.metricIds.getValue("cumulative_metric")),
                ),
            )
            assertTrue("analytics.time_spine_day" in cumulative.sql, "$dialect: ${cumulative.sql}")
            assertTrue("warehouse.analytics" !in cumulative.sql)
            val engineManifest = MetricFlowManifestMapper.toEngineManifest(manifest)
            assertEquals(null, engineManifest.semanticModels.single().nodeRelation.database)
            assertEquals(null, engineManifest.projectConfiguration.timeSpines.single().nodeRelation.database)
        }
    }

    @Test
    fun `three depth model and spine preserve database in SQL`() {
        val fixture = fixture()
        val modelAddress = CatalogPhysicalAddress("warehouse", "analytics", "orders", null)
        val spineAddress = CatalogPhysicalAddress("warehouse", "analytics", "time_spine_day", null)
        val manifest = fixture.manifest.copy(
            dialect = SemanticSqlDialect.TRINO,
            models = fixture.manifest.models.mapValues { (_, model) -> model.copy(physicalAddress = modelAddress) },
            timeSpines = listOf(SemanticMetricFlowTimeSpine(spineAddress, "date_day", SemanticTimeGranularity.DAY)),
        )
        val rendered = assertIs<SemanticMetricFlowRenderResult.Rendered>(
            MetricFlowSemanticRenderer.production().render(
                manifest,
                simpleQuery(fixture).copy(
                    metrics = listOf(SemanticElementName("cumulative_metric")),
                    groupBys = listOf(SemanticGroupBy.MetricTime(SemanticTimeGranularity.DAY)),
                ),
                listOf(fixture.metricIds.getValue("cumulative_metric")),
            ),
        )
        assertTrue("warehouse.analytics.orders" in rendered.sql)
        assertTrue("warehouse.analytics.time_spine_day" in rendered.sql)
    }

    @Test
    fun `production explain fails closed for conflicting cross-model dimension invariants`() {
        val fixture = fixture()
        val conflictingModel = conflictingModel(fixture.manifest.connectorId)
        val manifest = fixture.manifest.copy(
            models = fixture.manifest.models + (
                conflictingModel.name to uncataloguedMetricFlowModel(
                    definition = conflictingModel,
                    address = CatalogPhysicalAddress(null, "analytics", "customers", null),
                    dialect = SemanticSqlDialect.POSTGRESQL,
                )
            ),
        )

        val result = MetricFlowSemanticRenderer.production().render(
            manifest,
            simpleQuery(fixture),
            listOf(fixture.metricIds.getValue("revenue_metric")),
        )

        val invalid = assertIs<SemanticMetricFlowRenderResult.Invalid>(result)
        assertEquals(
            SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID,
            invalid.reasons.single().code,
        )
        assertEquals(null, invalid.reasons.single().field)
        val validation = assertIs<SemanticMetricFlowValidationResult.Invalid>(
            MetricFlowSemanticRenderer.production().validate(manifest),
        )
        assertEquals(
            SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID,
            validation.reasons.single().code,
        )
    }

    @Test
    fun `execution boundaries map excessive metric dependency depth to manifest invalid`() {
        val dependencyFixture = dependencyDepthFixture(fixture(), metricLevels = 101)
        val adapter = MetricFlowSemanticRenderer.production()

        val rendered = adapter.render(
            dependencyFixture.manifest,
            queryFor(dependencyFixture.rootMetricName),
            listOf(dependencyFixture.rootMetricId),
        )
        val listed = adapter.listGroupBys(
            dependencyFixture.manifest,
            listOf(dependencyFixture.rootMetricId),
        )
        val validation = adapter.validate(dependencyFixture.manifest)

        assertEquals(
            SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID,
            assertIs<SemanticMetricFlowRenderResult.Invalid>(rendered).reasons.single().code,
        )
        assertEquals(
            SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID,
            assertIs<SemanticMetricFlowGroupByResult.Invalid>(listed).reasons.single().code,
        )
        assertEquals(
            SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID,
            assertIs<SemanticMetricFlowValidationResult.Invalid>(validation).reasons.single().code,
        )
    }

    @Test
    fun `execution boundaries map metric dependency cycles to manifest invalid`() {
        val dependencyFixture = dependencyCycleFixture(fixture())
        val adapter = MetricFlowSemanticRenderer.production()

        val rendered = adapter.render(
            dependencyFixture.manifest,
            queryFor(dependencyFixture.rootMetricName),
            listOf(dependencyFixture.rootMetricId),
        )
        val listed = adapter.listGroupBys(
            dependencyFixture.manifest,
            listOf(dependencyFixture.rootMetricId),
        )
        val validation = adapter.validate(dependencyFixture.manifest)

        assertEquals(
            SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID,
            assertIs<SemanticMetricFlowRenderResult.Invalid>(rendered).reasons.single().code,
        )
        assertEquals(
            SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID,
            assertIs<SemanticMetricFlowGroupByResult.Invalid>(listed).reasons.single().code,
        )
        assertEquals(
            SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID,
            assertIs<SemanticMetricFlowValidationResult.Invalid>(validation).reasons.single().code,
        )
    }

    @Test
    fun `BigQuery advertised reserved entity renders quoted aliases and references`() {
        val fixture = fixture()
        val modelName = fixture.manifest.models.keys.single()
        val model = fixture.manifest.models.getValue(modelName)
        val definition = model.definition.copy(
            entities = model.definition.entities.map { it.copy(name = SemanticElementName("order")) },
        )
        val manifest = fixture.manifest.copy(
            dialect = SemanticSqlDialect.BIGQUERY,
            models = mapOf(modelName to model.copy(definition = definition)),
            metrics = fixture.manifest.metrics.filterKeys { it == fixture.metricIds.getValue("order_count_metric") },
        )
        val query = queryFor("order_count_metric").copy(
            groupBys = listOf(SemanticGroupBy.Entity(modelName, SemanticElementName("order"))),
            rowLimit = 3,
        )
        val metricIds = listOf(fixture.metricIds.getValue("order_count_metric"))
        val ungrouped = assertIs<SemanticMetricFlowRenderResult.Rendered>(
            MetricFlowSemanticRenderer.production().render(manifest, query.copy(groupBys = emptyList()), metricIds),
        )
        assertTrue(" AS order" !in ungrouped.sql, ungrouped.sql)
        val adapter = MetricFlowSemanticRenderer.production()
        val listed = assertIs<SemanticMetricFlowGroupByResult.Listed>(adapter.listGroupBys(manifest, metricIds))
        assertTrue(query.groupBys.single() in listed.groupBys)
        val rendered = assertIs<SemanticMetricFlowRenderResult.Rendered>(adapter.render(manifest, query, metricIds))
        assertEquals(query.resultColumns().map { it.key }, rendered.outputColumns.map { it.name })
        assertTrue("order_id AS `order`" in rendered.sql, rendered.sql)
        assertTrue("`order` AS `__monomer_result_0`" in rendered.sql, rendered.sql)
        assertTrue("GROUP BY\n  `__monomer_result_0`" in rendered.sql, rendered.sql)
        assertTrue("LIMIT 3" in rendered.sql, rendered.sql)
    }

    @Test
    fun `final SQL projection keeps same name dimensions metrics and grains aligned`() {
        val fixture = fixture()
        val base = assertIs<SemanticMetricDefinition.Simple>(fixture.metricDefinitions.first())
        val names = listOf("country", "order_count_metric")
        val metrics = names.associate { MetricId.generate() to base.copy(name = SemanticElementName(it), label = null) }
        val manifest = fixture.manifest.copy(metrics = metrics)
        val modelName = manifest.models.keys.single()
        val groupBys = listOf(
            SemanticGroupBy.Dimension(modelName, SemanticElementName("country"), null),
            SemanticGroupBy.Dimension(modelName, SemanticElementName("ordered_at"), SemanticTimeGranularity.DAY),
            SemanticGroupBy.Dimension(modelName, SemanticElementName("ordered_at"), SemanticTimeGranularity.MONTH),
            SemanticGroupBy.Entity(modelName, SemanticElementName("order_id")),
            SemanticGroupBy.MetricTime(SemanticTimeGranularity.DAY),
        )
        val query = queryFor("country").copy(
            metrics = names.map(::SemanticElementName),
            groupBys = groupBys,
            orderBy = listOf(
                SemanticQueryOrder(SemanticQueryOrderTarget.GroupBy(groupBys[2]), SemanticQueryOrderDirection.ASC),
                SemanticQueryOrder(SemanticQueryOrderTarget.Metric(SemanticElementName("country")), SemanticQueryOrderDirection.DESC),
            ),
        )
        SemanticSqlDialect.entries.forEach { dialect ->
            val adapter = MetricFlowSemanticRenderer.production()
            val result = adapter.render(manifest.copy(dialect = dialect), query, metrics.keys.toList())
            val rendered = assertIs<SemanticMetricFlowRenderResult.Rendered>(result)
            assertEquals(query.resultColumns().map { it.key }, rendered.outputColumns.map { it.name })
            assertEquals(query.resultColumns().map { it.sqlName }, rendered.outputColumns.map { it.sqlName })
            val delimiter = when (dialect) {
                SemanticSqlDialect.BIGQUERY, SemanticSqlDialect.DATABRICKS -> "`"
                else -> "\""
            }
            rendered.outputColumns.forEach {
                assertTrue(" AS $delimiter${it.sqlName}$delimiter" in rendered.sql, rendered.sql)
            }
            assertTrue(
                "ORDER BY ${delimiter}__monomer_result_2$delimiter, ${delimiter}__monomer_result_5$delimiter DESC" in rendered.sql,
                rendered.sql,
            )
            if (dialect == SemanticSqlDialect.BIGQUERY) {
                val groupingSql = rendered.sql.substringAfter("GROUP BY\n").substringBefore("\nORDER BY")
                groupBys.indices.forEach { index ->
                    assertTrue("${delimiter}__monomer_result_$index$delimiter" in groupingSql, rendered.sql)
                }
            }
        }
    }

    @Test
    fun `metric_time metric identity is distinct but the pinned engine reserves that metric name`() {
        val fixture = fixture()
        val metric = assertIs<SemanticMetricDefinition.Simple>(fixture.metricDefinitions.first())
            .copy(name = SemanticElementName("metric_time"), label = null)
        val metricId = MetricId.generate()
        val query = queryFor("metric_time").copy(
            groupBys = listOf(SemanticGroupBy.MetricTime(SemanticTimeGranularity.DAY)),
        )
        assertEquals(listOf("metric_time.day", "metric.metric_time"), query.resultColumns().map { it.key.value })
        val result = MetricFlowSemanticRenderer.production().render(
            fixture.manifest.copy(metrics = mapOf(metricId to metric)), query, listOf(metricId),
        )
        assertEquals(SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID,
            assertIs<SemanticMetricFlowRenderResult.Invalid>(result).reasons.single().code)
    }

    @Test
    fun `resolved engine output order mismatch is rejected before exposing result identity`() {
        val fixture = fixture()
        val query = groupedQuery(fixture).let { query -> query.copy(groupBys = query.groupBys +
            SemanticGroupBy.Entity(fixture.manifest.models.keys.single(), SemanticElementName("order_id"))) }
        val adapter = MetricFlowSemanticRenderer(MetricFlowExplainQuery { engine, request ->
            val result = engine.explain(request)
            result.copy(querySpec = result.querySpec.copy(inputSpecOrder = result.querySpec.inputSpecOrder.copy(
                groupByItemSpecs = result.querySpec.inputSpecOrder.groupByItemSpecs.reversed(),
            )))
        })
        val invalid = assertIs<SemanticMetricFlowRenderResult.Invalid>(
            adapter.render(fixture.manifest, query, listOf(fixture.metricIds.getValue("ratio_metric"))),
        )
        assertEquals(SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID, invalid.reasons.single().code)
    }

    @Test
    fun `same named cross model dimensions preserve identity when rendered separately`() {
        val fixture = fixture()
        val baseModel = fixture.manifest.models.values.single()
        val baseMetric = assertIs<SemanticMetricDefinition.Simple>(fixture.metricDefinitions.first())
        val outputKeys = listOf("orders", "shipments").map { modelName ->
            val model = baseModel.copy(definition = baseModel.definition.copy(name = SemanticElementName(modelName)))
            val metricId = MetricId.generate()
            val manifest = fixture.manifest.copy(
                models = mapOf(model.definition.name to model),
                metrics = mapOf(metricId to baseMetric.copy(semanticModelName = model.definition.name)),
            )
            val query = queryFor("revenue_metric").copy(groupBys = listOf(
                SemanticGroupBy.Dimension(model.definition.name, SemanticElementName("country"), null),
            ))
            val rendered = assertIs<SemanticMetricFlowRenderResult.Rendered>(
                MetricFlowSemanticRenderer.production().render(manifest, query, listOf(metricId)),
            )
            rendered.outputColumns.first().name.value
        }
        assertEquals(listOf("dimension.orders.country", "dimension.shipments.country"), outputKeys)
    }

    @Test
    fun `cross model shared entity join keeps same named dimensions distinct`() {
        val fixture = fixture()
        val orders = fixture.manifest.models.values.single()
        val shipmentModel = orders.definition.copy(
            name = SemanticElementName("shipments"),
            label = null,
            entities = listOf(
                orders.definition.entities.single().copy(type = SemanticEntityKind.FOREIGN),
                orders.definition.entities.single().copy(name = SemanticElementName("shipment_id"), expression = "shipment_id"),
            ),
            measures = listOf(orders.definition.measures.first().copy(name = SemanticElementName("shipment_revenue"))),
        )
        val metricId = MetricId.generate()
        val metric = assertIs<SemanticMetricDefinition.Simple>(fixture.metricDefinitions.first()).copy(
            semanticModelName = shipmentModel.name, measure = SemanticElementName("shipment_revenue"),
        )
        val manifest = fixture.manifest.copy(
            models = fixture.manifest.models + (shipmentModel.name to uncataloguedMetricFlowModel(
                shipmentModel, CatalogPhysicalAddress("warehouse", "analytics", "shipments", null),
                SemanticSqlDialect.POSTGRESQL,
            )),
            metrics = mapOf(metricId to metric),
        )
        val query = queryFor("revenue_metric").copy(groupBys = listOf(
            SemanticGroupBy.Dimension(orders.definition.name, SemanticElementName("country"), null),
            SemanticGroupBy.Dimension(shipmentModel.name, SemanticElementName("country"), null),
        ))
        val result = MetricFlowSemanticRenderer.production().render(manifest, query, listOf(metricId))
        val rendered = assertIs<SemanticMetricFlowRenderResult.Rendered>(result)
        assertEquals(listOf("dimension.orders.country", "dimension.shipments.country", "metric.revenue_metric"),
            rendered.outputColumns.map { it.name.value })
        assertTrue("orders_src_10000.country AS \"__monomer_result_0\"" in rendered.sql, rendered.sql)
        assertTrue("shipments_src_10000.country AS \"__monomer_result_1\"" in rendered.sql, rendered.sql)
        assertTrue("LEFT OUTER JOIN" in rendered.sql)
    }

    @Test
    fun `Snapshot partition changes a shared entity join without warehouse metadata`() {
        val fixture = fixture()
        val orders = fixture.manifest.models.values.single()
        val shipmentModel = orders.definition.copy(
            name = SemanticElementName("shipments"), label = null,
            entities = listOf(
                orders.definition.entities.single().copy(type = SemanticEntityKind.FOREIGN),
                orders.definition.entities.single().copy(name = SemanticElementName("shipment_id"), expression = "shipment_id"),
            ),
            measures = listOf(orders.definition.measures.first().copy(name = SemanticElementName("shipment_revenue"))),
        )
        val metricId = MetricId.generate()
        val metric = assertIs<SemanticMetricDefinition.Simple>(fixture.metricDefinitions.first()).copy(
            semanticModelName = shipmentModel.name, measure = SemanticElementName("shipment_revenue"),
        )
        val query = queryFor("revenue_metric").copy(groupBys = listOf(
            SemanticGroupBy.Dimension(orders.definition.name, SemanticElementName("country"), null),
        ))
        fun rendered(snapshot: Boolean): String {
            fun partitioned(model: SemanticModelDefinition) = model.copy(dimensions = model.dimensions.map { dimension ->
                if (dimension.name == SemanticElementName("ordered_at")) dimension.copy(isPartition = snapshot) else dimension
            })
            val manifest = fixture.manifest.copy(
                models = mapOf(
                    orders.definition.name to orders.copy(definition = partitioned(orders.definition)),
                    shipmentModel.name to uncataloguedMetricFlowModel(partitioned(shipmentModel), CatalogPhysicalAddress("warehouse", "analytics", "shipments", null), SemanticSqlDialect.POSTGRESQL),
                ), metrics = mapOf(metricId to metric),
            )
            assertIs<SemanticMetricFlowValidationResult.Valid>(MetricFlowSemanticRenderer.production().validate(manifest))
            return assertIs<SemanticMetricFlowRenderResult.Rendered>(MetricFlowSemanticRenderer.production().render(manifest, query, listOf(metricId))).sql
        }
        val ordinary = rendered(false)
        val snapshot = rendered(true)
        assertTrue(ordinary != snapshot, snapshot)
        assertTrue(Regex("shipments_src_10000\\.ordered_at[^\\n]*=[^\\n]*orders_src_10000\\.ordered_at").containsMatchIn(snapshot), snapshot)
    }

    @Test
    fun `pinned engine rejects metric and entity sharing an ambiguous selector name`() {
        val fixture = fixture()
        val metric = assertIs<SemanticMetricDefinition.Simple>(fixture.metricDefinitions.first()).copy(
            name = SemanticElementName("order_id"), label = null,
        )
        val metricId = MetricId.generate()
        val query = queryFor("order_id").copy(groupBys = listOf(
            SemanticGroupBy.Entity(fixture.manifest.models.keys.single(), SemanticElementName("order_id")),
        ))
        var engineError: IllegalArgumentException? = null
        val adapter = MetricFlowSemanticRenderer(MetricFlowExplainQuery { engine, request ->
            try { engine.explain(request) } catch (error: IllegalArgumentException) {
                engineError = error
                throw error
            }
        })
        val result = adapter.render(fixture.manifest.copy(metrics = mapOf(metricId to metric)), query, listOf(metricId))
        assertEquals(listOf("entity.orders.order_id", "metric.order_id"), query.resultColumns().map { it.key.value })
        assertEquals(SemanticInvalidReasonCode.QUERY_FILTER_UNRENDERABLE,
            assertIs<SemanticMetricFlowRenderResult.Invalid>(result).reasons.single().code)
        assertNotNull(engineError)
        assertTrue("DuplicateOutputColumnIssue" in engineError.message.orEmpty())
    }

    private fun fixture(): Fixture {
        val connectorId = ConnectorId.generate()
        val model = model(connectorId)
        val metricDefinitions = listOf<SemanticMetricDefinition>(
            SemanticMetricDefinition.Simple(
                schemaVersion = 3u,
                name = SemanticElementName("revenue_metric"),
                label = "Revenue",
                description = "Revenue metric",
                timeGranularity = SemanticTimeGranularity.DAY,
                filters = emptyList(),
                semanticModelName = model.name,
                measure = SemanticElementName("revenue"),
                display = null,
            ),
            SemanticMetricDefinition.Simple(
                schemaVersion = 3u,
                name = SemanticElementName("order_count_metric"),
                label = null,
                description = null,
                timeGranularity = null,
                filters = emptyList(),
                semanticModelName = model.name,
                measure = SemanticElementName("order_count"),
                display = null,
            ),
            SemanticMetricDefinition.Simple(
                schemaVersion = 3u,
                name = SemanticElementName("converted_metric"),
                label = null,
                description = null,
                timeGranularity = null,
                filters = emptyList(),
                semanticModelName = model.name,
                measure = SemanticElementName("converted_count"),
                display = null,
            ),
            SemanticMetricDefinition.Ratio(
                schemaVersion = 3u,
                name = SemanticElementName("ratio_metric"),
                label = null,
                description = null,
                timeGranularity = null,
                filters = emptyList(),
                numerator = input(
                    metricName = "revenue_metric",
                    filter = SemanticMetricFilter.Predicate(
                        dimension = SemanticElementReference(model.name, SemanticElementName("country")),
                        operator = SemanticMetricFilterOperator.EQ,
                        values = listOf("US"),
                    ),
                ),
                denominator = input("order_count_metric", alias = "count_input"),
                display = null,
            ),
            SemanticMetricDefinition.Derived(
                schemaVersion = 3u,
                name = SemanticElementName("derived_metric"),
                label = null,
                description = null,
                timeGranularity = null,
                filters = emptyList(),
                expression = "revenue_input + count_input",
                inputMetrics = listOf(
                    input(
                        metricName = "revenue_metric",
                        alias = "revenue_input",
                        offsetWindow = SemanticMetricWindow(1, SemanticMetricWindowUnit.DAY),
                    ),
                    input(
                        metricName = "order_count_metric",
                        alias = "count_input",
                        offsetToGrain = SemanticTimeGranularity.DAY,
                    ),
                ),
                display = null,
            ),
            SemanticMetricDefinition.Cumulative(
                schemaVersion = 3u,
                name = SemanticElementName("cumulative_metric"),
                label = null,
                description = null,
                timeGranularity = null,
                filters = emptyList(),
                inputMetric = input("revenue_metric"),
                window = SemanticMetricWindow(7, SemanticMetricWindowUnit.DAY),
                grainToDate = null,
                periodAggregation = SemanticMetricPeriodAggregation.LAST,
                display = null,
            ),
            SemanticMetricDefinition.Conversion(
                schemaVersion = 3u,
                name = SemanticElementName("conversion_metric"),
                label = null,
                description = null,
                timeGranularity = null,
                filters = emptyList(),
                entity = SemanticElementName("order_id"),
                calculation = SemanticConversionCalculation.CONVERSION_RATE,
                baseMetric = input("order_count_metric"),
                conversionMetric = input("converted_metric"),
                window = SemanticMetricWindow(7, SemanticMetricWindowUnit.DAY),
                constantProperties = listOf(
                    SemanticConstantProperty(
                        baseProperty = SemanticElementName("country"),
                        conversionProperty = SemanticElementName("country"),
                    ),
                ),
                display = null,
            ),
        )
        val metricIds = metricDefinitions.associate { it.name.value to MetricId.generate() }
        val manifest = SemanticMetricFlowManifest(
            connectorId = connectorId,
            dialect = SemanticSqlDialect.POSTGRESQL,
            models = mapOf(
                model.name to uncataloguedMetricFlowModel(
                    definition = model,
                    address = CatalogPhysicalAddress(null, "analytics", "orders", null),
                    dialect = SemanticSqlDialect.POSTGRESQL,
                ),
            ),
            metrics = metricDefinitions.associate { definition -> metricIds.getValue(definition.name.value) to definition },
            timeSpines = emptyList(),
            reportingTimeZone = UTC_REPORTING_TIME_ZONE,
        )
        return Fixture(manifest, metricIds, metricDefinitions)
    }

    private fun dependencyDepthFixture(fixture: Fixture, metricLevels: Int): DependencyFixture {
        require(metricLevels >= 1)
        val definitions = mutableListOf(fixture.metricDefinitions.first())
        repeat(metricLevels - 1) { index ->
            val inputName = definitions.last().name.value
            definitions += derivedMetric(
                name = "dependency_level_${index + 2}",
                inputName = inputName,
            )
        }
        return dependencyFixture(fixture, definitions)
    }

    private fun dependencyCycleFixture(fixture: Fixture): DependencyFixture = dependencyFixture(
        fixture,
        listOf(
            derivedMetric(name = "cycle_a", inputName = "cycle_b"),
            derivedMetric(name = "cycle_b", inputName = "cycle_a"),
        ),
    )

    private fun dependencyFixture(
        fixture: Fixture,
        definitions: List<SemanticMetricDefinition>,
    ): DependencyFixture {
        val metrics = definitions.associate { definition -> MetricId.generate() to definition }
        val rootMetricId = metrics.entries.single { (_, definition) -> definition == definitions.last() }.key
        return DependencyFixture(
            manifest = fixture.manifest.copy(metrics = metrics),
            rootMetricId = rootMetricId,
            rootMetricName = definitions.last().name.value,
        )
    }

    private fun derivedMetric(name: String, inputName: String): SemanticMetricDefinition.Derived =
        SemanticMetricDefinition.Derived(
            schemaVersion = 3u,
            name = SemanticElementName(name),
            label = null,
            description = null,
            timeGranularity = null,
            filters = emptyList(),
            expression = "input_metric",
            inputMetrics = listOf(input(inputName, alias = "input_metric")),
            display = null,
        )

    private fun model(connectorId: ConnectorId): SemanticModelDefinition = SemanticModelDefinition(
        schemaVersion = 3u,
        name = SemanticElementName("orders"),
        label = "Orders",
        description = "Current orders",
        sourceRelations = listOf(
            SemanticSourceRelation(connectorId, null, "analytics", "orders"),
        ),
        aggregationTimeDimension = SemanticElementName("ordered_at"),
        entities = listOf(
            SemanticEntityDefinition(
                name = SemanticElementName("order_id"),
                type = SemanticEntityKind.PRIMARY,
                expression = "order_id",
                label = null,
                description = null,
            ),
        ),
        dimensions = listOf(
            SemanticDimensionDefinition(
                name = SemanticElementName("ordered_at"),
                type = SemanticDimensionKind.TIME,
                expression = "ordered_at",
                granularity = SemanticTimeGranularity.DAY,
                label = null,
                description = null,
                isPartition = false,
                timeKind = null,
            ),
            SemanticDimensionDefinition(
                name = SemanticElementName("country"),
                type = SemanticDimensionKind.CATEGORICAL,
                expression = "country",
                granularity = null,
                label = null,
                description = null,
                isPartition = false,
                timeKind = null,
            ),
        ),
        measures = listOf(
            measure("revenue", SemanticMeasureAggregation.SUM, "revenue"),
            measure("order_count", SemanticMeasureAggregation.COUNT, "1"),
            measure("converted_count", SemanticMeasureAggregation.COUNT, "1"),
            measure(
                name = "snapshot_balance",
                aggregation = SemanticMeasureAggregation.MAX,
                expression = "balance",
                nonAdditive = cc.monomer.semantic.api.SemanticNonAdditiveDimension(
                    name = SemanticElementName("ordered_at"),
                    windowChoice = cc.monomer.semantic.api.SemanticNonAdditiveWindowChoice.MAX,
                    windowGroupings = null,
                ),
            ),
            measure("median_value", SemanticMeasureAggregation.MEDIAN, "amount"),
            measure("active_flag", SemanticMeasureAggregation.SUM_BOOLEAN, "is_active"),
        ),
    )

    private fun conflictingModel(connectorId: ConnectorId): SemanticModelDefinition {
        val source = model(connectorId)
        return source.copy(
            name = SemanticElementName("customers"),
            entities = source.entities.map { entity ->
                if (entity.type == SemanticEntityKind.PRIMARY) {
                    entity.copy(
                        name = SemanticElementName("customer_id"),
                        expression = "customer_id",
                    )
                } else {
                    entity
                }
            },
            dimensions = source.dimensions.map { dimension ->
                if (dimension.name == SemanticElementName("country")) {
                    dimension.copy(
                        type = SemanticDimensionKind.TIME,
                        expression = "country",
                        granularity = SemanticTimeGranularity.DAY,
                    )
                } else {
                    dimension
                }
            },
        )
    }

    /** The fixture with `ordered_at` defined at hour grain, where TIMESTAMP filters apply. */
    private fun hourFixture(fixture: Fixture): Fixture = fixture.copy(
        manifest = fixture.manifest.copy(models = fixture.manifest.models.mapValues { (_, model) ->
            val definition = model.definition.copy(dimensions = model.definition.dimensions.map { dimension ->
                if (dimension.name == SemanticElementName("ordered_at")) dimension.copy(granularity = SemanticTimeGranularity.HOUR) else dimension
            })
            uncataloguedMetricFlowModel(definition, model.physicalAddress, SemanticSqlDialect.POSTGRESQL)
        }),
    )

    private fun wallClockLiteral(dialect: SemanticSqlDialect, wallClock: String): String = when (dialect) {
        SemanticSqlDialect.BIGQUERY -> "DATETIME '$wallClock'"
        SemanticSqlDialect.SNOWFLAKE -> "'$wallClock'::TIMESTAMP_NTZ"
        SemanticSqlDialect.DATABRICKS -> "TIMESTAMP_NTZ '$wallClock'"
        SemanticSqlDialect.POSTGRESQL, SemanticSqlDialect.REDSHIFT, SemanticSqlDialect.TRINO, SemanticSqlDialect.DUCKDB ->
            "TIMESTAMP '$wallClock'"
    }

    private fun simpleQuery(fixture: Fixture): SemanticQuerySpec = SemanticQuerySpec(
        metrics = listOf(SemanticElementName("revenue_metric")),
        groupBys = emptyList(),
        metricTimeRange = null,
        filters = emptyList(),
        orderBy = emptyList(),
        rowLimit = 1,
    )

    private fun groupedQuery(fixture: Fixture): SemanticQuerySpec = SemanticQuerySpec(
        metrics = listOf(SemanticElementName("ratio_metric")),
        groupBys = listOf(
            SemanticGroupBy.Dimension(
                semanticModelName = fixture.manifest.models.keys.single(),
                name = SemanticElementName("country"),
                granularity = null,
            ),
            SemanticGroupBy.Dimension(
                semanticModelName = fixture.manifest.models.keys.single(),
                name = SemanticElementName("ordered_at"),
                granularity = SemanticTimeGranularity.MONTH,
            ),
        ),
        metricTimeRange = null,
        filters = listOf(
            SemanticQueryFilter.Text(
                dimension = SemanticElementReference(
                    fixture.manifest.models.keys.single(),
                    SemanticElementName("country"),
                ),
                operator = SemanticTextFilterOperator.EQUALS,
                values = listOf("CA"),
            ),
        ),
        orderBy = emptyList(),
        rowLimit = 10,
    )

    private fun queryFor(metricName: String): SemanticQuerySpec = SemanticQuerySpec(
        metrics = listOf(SemanticElementName(metricName)),
        groupBys = emptyList(),
        metricTimeRange = null,
        filters = emptyList(),
        orderBy = emptyList(),
        rowLimit = 1,
    )

    private fun measure(
        name: String,
        aggregation: SemanticMeasureAggregation,
        expression: String,
        nonAdditive: cc.monomer.semantic.api.SemanticNonAdditiveDimension? = null,
    ): SemanticMeasureDefinition = SemanticMeasureDefinition(
        name = SemanticElementName(name),
        aggregation = aggregation,
        expression = expression,
        aggregationTimeDimension = SemanticElementName("ordered_at"),
        nonAdditiveDimension = nonAdditive,
        label = null,
        description = null,
    )

    private fun input(
        metricName: String,
        filter: SemanticMetricFilter? = null,
        alias: String? = null,
        offsetWindow: SemanticMetricWindow? = null,
        offsetToGrain: SemanticTimeGranularity? = null,
    ): SemanticMetricInput = SemanticMetricInput(
        metricName = SemanticElementName(metricName),
        filter = filter,
        alias = alias?.let(::SemanticElementName),
        offsetWindow = offsetWindow,
        offsetToGrain = offsetToGrain,
    )

    private data class Fixture(
        val manifest: SemanticMetricFlowManifest,
        val metricIds: Map<String, MetricId>,
        val metricDefinitions: List<SemanticMetricDefinition>,
    )

    private data class DependencyFixture(
        val manifest: SemanticMetricFlowManifest,
        val rootMetricId: MetricId,
        val rootMetricName: String,
    )
}
