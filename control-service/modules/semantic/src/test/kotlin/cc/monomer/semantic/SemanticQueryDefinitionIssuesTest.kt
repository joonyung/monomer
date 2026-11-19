@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.semantic

import cc.monomer.catalog.api.CatalogCurrentObjectSet
import cc.monomer.catalog.api.CatalogCurrentObjectsReadPort
import cc.monomer.catalog.api.CatalogDigest
import cc.monomer.catalog.api.CatalogObjectId
import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.adapter.outbound.metricflow.MetricFlowSemanticRenderer
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.SemanticCheckDraft
import cc.monomer.semantic.api.SemanticCheckRequest
import cc.monomer.semantic.api.SemanticCheckResult
import cc.monomer.semantic.api.SemanticDefinitionIssue
import cc.monomer.semantic.api.SemanticDefinitionReference
import cc.monomer.semantic.api.SemanticDimensionDefinition
import cc.monomer.semantic.api.SemanticDimensionKind
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticElementReference
import cc.monomer.semantic.api.SemanticEntityDefinition
import cc.monomer.semantic.api.SemanticEntityKind
import cc.monomer.semantic.api.SemanticExcludedModel
import cc.monomer.semantic.api.SemanticGroupBy
import cc.monomer.semantic.api.SemanticGroupByOptionsRequest
import cc.monomer.semantic.api.SemanticGroupByOptionsResult
import cc.monomer.semantic.api.SemanticInvalidReason
import cc.monomer.semantic.api.SemanticInvalidReasonCode
import cc.monomer.semantic.api.SemanticMeasureAggregation
import cc.monomer.semantic.api.SemanticMeasureDefinition
import cc.monomer.semantic.api.SemanticMetricDefinition
import cc.monomer.semantic.api.SemanticMetricInput
import cc.monomer.semantic.api.SemanticModelDefinition
import cc.monomer.semantic.api.SemanticModelId
import cc.monomer.semantic.api.SemanticQueryFilter
import cc.monomer.semantic.api.SemanticQuerySpec
import cc.monomer.semantic.api.SemanticRenderRequest
import cc.monomer.semantic.api.SemanticRenderResult
import cc.monomer.semantic.api.SemanticSourceRelation
import cc.monomer.semantic.api.SemanticTextFilterOperator
import cc.monomer.semantic.api.SemanticTimeGranularity
import cc.monomer.semantic.application.SemanticDefinitionCheckService
import cc.monomer.semantic.application.SemanticMetricFlowIssue
import cc.monomer.semantic.application.SemanticMetricFlowIssueSubject
import cc.monomer.semantic.application.SemanticMetricFlowInvalidReason
import cc.monomer.semantic.application.SemanticMetricFlowManifest
import cc.monomer.semantic.application.SemanticMetricFlowPort
import cc.monomer.semantic.application.SemanticMetricFlowValidationResult
import cc.monomer.semantic.application.SemanticQueryService
import cc.monomer.semantic.domain.CurrentMetric
import cc.monomer.semantic.domain.CurrentSemanticModel
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking

class SemanticQueryDefinitionIssuesTest {
    private val workspace = WorkspaceId.generate()
    private val connector = ConnectorId.generate()
    private val repository = InMemorySemanticRepository()
    private var currentCatalog: CatalogCurrentObjectSet? = catalogWith("customers")

    @Test
    fun `an unrelated candidate the engine rejects is left out of render batch group-bys and metric checks`(): Unit = runBlocking {
        saveModel(orders())
        saveModel(shipments())
        val metricId = saveMetric(revenue())
        val engine = RecordingValidation(MetricFlowSemanticRenderer.production())
        val service = queryService(engine)

        assertIs<SemanticRenderResult.Rendered>(service.render(query("revenue")))
        val complete = engine.validations.first()
        assertEquals(setOf("orders", "shipments"), complete.first)
        assertIs<SemanticMetricFlowValidationResult.Invalid>(complete.second, "the fixture must break the complete manifest")
        assertIs<SemanticRenderResult.Rendered>(service.renderBatch(listOf(query("revenue")), Instant.now().plusSeconds(10)).single())

        val listed = assertIs<SemanticGroupByOptionsResult.Listed>(service.listGroupBys(groupBys("revenue")))
        assertEquals(emptyList(), listed.excludedSemanticModels)
        val selected = assertIs<SemanticRenderResult.Invalid>(
            service.render(query("revenue", groupBys = listOf(
                SemanticGroupBy.Dimension(SemanticElementName("shipments"), SemanticElementName("status"), SemanticTimeGranularity.DAY),
            ))),
        )
        assertEquals(SemanticDefinitionReference.SemanticModel(SemanticElementName("shipments")), selected.definitionIssues.single().definition)
        assertTrue("status" in selected.definitionIssues.single().message)
        assertTrue(SemanticGroupBy.Dimension(SemanticElementName("orders"), SemanticElementName("status"), null) in listed.groupBys, listed.toString())
        assertTrue(listed.groupBys.none { it is SemanticGroupBy.Dimension && it.semanticModelName.value == "shipments" })

        val checked = assertIs<SemanticCheckResult.Checked>(
            checkService(MetricFlowSemanticRenderer.production())
                .check(SemanticCheckRequest(workspace, SemanticCheckDraft.Metric(metricId, revenue(), null))),
        )
        assertEquals(emptyList(), checked.problems)
        assertEquals(listOf(connector), checked.runsOn)
    }

    @Test
    fun `a group-by reachable only through a left-out model disappears and selecting it names that model`(): Unit = runBlocking {
        saveModel(orders())
        val customersId = saveModel(customers(statusKind = SemanticDimensionKind.TIME))
        saveMetric(revenue())
        val service = queryService(MetricFlowSemanticRenderer.production())
        val label = SemanticGroupBy.Dimension(SemanticElementName("customers"), SemanticElementName("label"), null)

        val listed = assertIs<SemanticGroupByOptionsResult.Listed>(service.listGroupBys(groupBys("revenue")))
        assertEquals(listOf(SemanticElementName("customers")), listed.excludedSemanticModels.map(SemanticExcludedModel::name))
        assertTrue(listed.groupBys.none { it is SemanticGroupBy.Dimension && it.semanticModelName.value == "customers" }, listed.toString())

        val expectedIssue = SemanticDefinitionIssue(
            SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID,
            SemanticDefinitionReference.SemanticModel(SemanticElementName("customers")),
            listed.excludedSemanticModels.single().message,
        )
        val grouped = assertIs<SemanticRenderResult.Invalid>(service.render(query("revenue", groupBys = listOf(label))))
        assertEquals(listOf(SemanticInvalidReason(SemanticInvalidReasonCode.QUERY_GROUP_BY_NOT_FOUND, "groupBys[0]")), grouped.reasons)
        assertEquals(listOf(expectedIssue), grouped.definitionIssues)
        val filtered = assertIs<SemanticRenderResult.Invalid>(
            service.render(
                query(
                    "revenue",
                    filters = listOf(
                        SemanticQueryFilter.Text(
                            SemanticElementReference(SemanticElementName("customers"), SemanticElementName("label")),
                            SemanticTextFilterOperator.EQUALS,
                            listOf("EU"),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(listOf(expectedIssue), filtered.definitionIssues)

        repository.models[customersId] = repository.models.getValue(customersId).copy(definition = customers(statusKind = SemanticDimensionKind.CATEGORICAL))
        val repaired = assertIs<SemanticGroupByOptionsResult.Listed>(service.listGroupBys(groupBys("revenue")))
        assertEquals(emptyList(), repaired.excludedSemanticModels)
        assertTrue(label in repaired.groupBys, repaired.toString())
        assertIs<SemanticRenderResult.Rendered>(service.render(query("revenue", groupBys = listOf(label))))
    }

    @Test
    fun `an excluded model sharing an entity only with a listed group-by model is kept`(): Unit = runBlocking {
        saveModel(orders())
        saveModel(customers(SemanticDimensionKind.CATEGORICAL).copy(
            name = SemanticElementName("bridge"),
            entities = listOf(entity("customer", SemanticEntityKind.PRIMARY), entity("region", SemanticEntityKind.FOREIGN)),
        ))
        saveModel(candidate("regions").copy(entities = listOf(entity("region", SemanticEntityKind.PRIMARY))))
        saveModel(candidate("unlisted").copy(entities = listOf(entity("other", SemanticEntityKind.PRIMARY))))
        saveModel(candidate("unrelated").copy(entities = listOf(entity("other", SemanticEntityKind.PRIMARY))))
        saveMetric(revenue())
        val validation = ScriptedValidation { names ->
            when {
                "regions" in names -> invalid(SemanticMetricFlowIssue("regions broken", SemanticMetricFlowIssueSubject.Model("regions")))
                "unrelated" in names -> invalid(SemanticMetricFlowIssue("unrelated broken", SemanticMetricFlowIssueSubject.Model("unrelated")))
                else -> SemanticMetricFlowValidationResult.Valid
            }
        }
        val options = listOf(
            SemanticGroupBy.Dimension(SemanticElementName("bridge"), SemanticElementName("label"), null),
            SemanticGroupBy.Entity(SemanticElementName("bridge"), SemanticElementName("region")),
        )
        options.forEach { option ->
            val port = object : SemanticMetricFlowPort by validation {
                override fun listGroupBys(manifest: SemanticMetricFlowManifest, metricIds: List<MetricId>) =
                    cc.monomer.semantic.application.SemanticMetricFlowGroupByResult.Listed(listOf(option))
            }
            val listed = assertIs<SemanticGroupByOptionsResult.Listed>(queryService(port).listGroupBys(groupBys("revenue")))
            assertEquals(listOf(option), listed.groupBys)
            assertEquals(listOf(SemanticExcludedModel(SemanticElementName("regions"), "regions broken")), listed.excludedSemanticModels)
        }
    }

    @Test
    fun `MetricFlow listed group-bys reach a model sharing an entity with an excluded candidate`(): Unit = runBlocking {
        currentCatalog = catalogWith("customers", "regions")
        saveModel(orders())
        saveModel(customers(SemanticDimensionKind.CATEGORICAL).copy(
            entities = listOf(entity("customer", SemanticEntityKind.PRIMARY), entity("region", SemanticEntityKind.FOREIGN)),
        ))
        saveModel(shipments().copy(
            name = SemanticElementName("regions"),
            sourceRelations = listOf(SemanticSourceRelation(connector, null, "analytics", "regions")),
            entities = listOf(entity("region", SemanticEntityKind.PRIMARY)),
        ))
        saveMetric(revenue())
        val listed = assertIs<SemanticGroupByOptionsResult.Listed>(
            queryService(MetricFlowSemanticRenderer.production()).listGroupBys(groupBys("revenue")),
        )
        assertTrue(SemanticGroupBy.Dimension(SemanticElementName("customers"), SemanticElementName("label"), null) in listed.groupBys)
        assertEquals(listOf(SemanticElementName("regions")), listed.excludedSemanticModels.map(SemanticExcludedModel::name))
        assertTrue("status" in listed.excludedSemanticModels.single().message)
    }

    @Test
    fun `shared entity joins toward foreign or natural candidates are omitted`(): Unit = runBlocking {
        saveModel(orders())
        saveMetric(revenue())
        listOf(SemanticEntityKind.FOREIGN, SemanticEntityKind.NATURAL).forEach { candidateKind ->
            val candidateId = saveModel(candidate("excluded").copy(entities = listOf(entity("excluded_id", SemanticEntityKind.PRIMARY), entity("customer", candidateKind))))
            val port = ScriptedValidation { names ->
                if ("excluded" in names) invalid(SemanticMetricFlowIssue("excluded broken", SemanticMetricFlowIssueSubject.Model("excluded")))
                else SemanticMetricFlowValidationResult.Valid
            }
            val listed = assertIs<SemanticGroupByOptionsResult.Listed>(queryService(port).listGroupBys(groupBys("revenue")))
            assertEquals(emptyList(), listed.excludedSemanticModels, candidateKind.name)
            repository.models.remove(candidateId)
        }
    }

    @Test
    fun `a shared unique entity on an excluded model is kept`(): Unit = runBlocking {
        saveModel(orders())
        saveModel(candidate("excluded").copy(entities = listOf(entity("excluded_id", SemanticEntityKind.PRIMARY), entity("customer", SemanticEntityKind.UNIQUE))))
        saveMetric(revenue())
        val port = ScriptedValidation { names ->
            if ("excluded" in names) invalid(SemanticMetricFlowIssue("excluded broken", SemanticMetricFlowIssueSubject.Model("excluded")))
            else SemanticMetricFlowValidationResult.Valid
        }
        val listed = assertIs<SemanticGroupByOptionsResult.Listed>(queryService(port).listGroupBys(groupBys("revenue")))
        assertEquals(listOf(SemanticExcludedModel(SemanticElementName("excluded"), "excluded broken")), listed.excludedSemanticModels)
    }

    @Test
    fun `a definition the metric needs still fails the request and is named`(): Unit = runBlocking {
        saveModel(orders())
        saveModel(shipments())
        saveMetric(revenue(measure = "invoice_count"))
        val service = queryService(MetricFlowSemanticRenderer.production())

        val rendered = assertIs<SemanticRenderResult.Invalid>(service.render(query("revenue")))
        assertEquals(listOf(SemanticInvalidReason(SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID, null)), rendered.reasons)
        assertTrue(rendered.definitionIssues.all { it.code == SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID }, rendered.toString())
        assertTrue(rendered.definitionIssues.none { it.definition?.name?.value == "shipments" }, rendered.toString())
        assertTrue(
            rendered.definitionIssues.any { issue ->
                issue.definition == SemanticDefinitionReference.Metric(SemanticElementName("revenue")) && "invoice_count" in issue.message
            },
            rendered.toString(),
        )

        val listed = assertIs<SemanticGroupByOptionsResult.Invalid>(service.listGroupBys(groupBys("revenue")))
        assertEquals(rendered.definitionIssues, listed.definitionIssues)
    }

    @Test
    fun `unresolved references name the metric that uses them`(): Unit = runBlocking {
        saveMetric(derived("total", "base"))
        val service = queryService(AcceptingMetricFlowPort)

        val missingMetric = assertIs<SemanticRenderResult.Invalid>(service.render(query("total")))
        assertEquals(listOf(SemanticInvalidReason(SemanticInvalidReasonCode.METRIC_DEPENDENCY_NOT_FOUND, null)), missingMetric.reasons)
        assertEquals(
            listOf(
                SemanticDefinitionIssue(
                    SemanticInvalidReasonCode.METRIC_DEPENDENCY_NOT_FOUND,
                    SemanticDefinitionReference.Metric(SemanticElementName("total")),
                    "Metric `total` uses metric `base`, which doesn't exist.",
                ),
            ),
            missingMetric.definitionIssues,
        )

        saveMetric(revenue(name = "base", model = "billing"))
        val missingModel = assertIs<SemanticGroupByOptionsResult.Invalid>(service.listGroupBys(groupBys("total")))
        assertEquals(
            listOf(
                SemanticDefinitionIssue(
                    SemanticInvalidReasonCode.METRIC_MODEL_NOT_FOUND,
                    SemanticDefinitionReference.Metric(SemanticElementName("base")),
                    "Metric `base` uses semantic model `billing`, which doesn't exist.",
                ),
            ),
            missingModel.definitionIssues,
        )
    }

    @Test
    fun `a needed model without a usable source on this connector is named`(): Unit = runBlocking {
        val ordersId = saveModel(orders().copy(sourceRelations = listOf(SemanticSourceRelation(ConnectorId.generate(), null, "analytics", "orders"))))
        saveMetric(revenue())
        val service = queryService(AcceptingMetricFlowPort)
        val ordersReference = SemanticDefinitionReference.SemanticModel(SemanticElementName("orders"))

        assertEquals(
            listOf(
                SemanticDefinitionIssue(
                    SemanticInvalidReasonCode.SOURCE_RELATION_NOT_FOUND,
                    ordersReference,
                    "Semantic model `orders` has no source table on this connector.",
                ),
            ),
            assertIs<SemanticRenderResult.Invalid>(service.render(query("revenue"))).definitionIssues,
        )

        repository.models[ordersId] = repository.models.getValue(ordersId).copy(
            definition = orders().copy(sourceRelations = listOf(SemanticSourceRelation(connector, null, "analytics", "missing"))),
        )
        val missing = assertIs<SemanticRenderResult.Invalid>(service.render(query("revenue")))
        assertEquals(listOf(SemanticInvalidReason(SemanticInvalidReasonCode.CATALOG_RELATION_NOT_FOUND, null)), missing.reasons)
        assertEquals(
            listOf(
                SemanticDefinitionIssue(
                    SemanticInvalidReasonCode.CATALOG_RELATION_NOT_FOUND,
                    ordersReference,
                    "Semantic model `orders` reads analytics.missing, which isn't loaded in this connector's Catalog.",
                ),
            ),
            missing.definitionIssues,
        )

        repository.models[ordersId] = repository.models.getValue(ordersId).copy(definition = orders())
        val catalog = catalogWith("customers")
        val schema = catalog.objects.single { it.kind == cc.monomer.catalog.api.CatalogObjectKind.SCHEMA }
        val relation = catalog.objects.single { it.address.objectName == "orders" }
        val otherSchemaId = CatalogObjectId(Uuid.parse("0197d83f-2c9e-8a3b-8c1d-000000000098"))
        currentCatalog = catalog.copy(
            objects = catalog.objects + listOf(
                schema.copy(
                    id = otherSchemaId,
                    identityDigest = CatalogDigest("sha256:" + "b".repeat(64)),
                    address = schema.address.copy(catalogName = "other_warehouse"),
                ),
                relation.copy(
                    id = CatalogObjectId(Uuid.parse("0197d83f-2c9e-8a3b-8c1d-000000000099")),
                    parentId = otherSchemaId,
                    identityDigest = CatalogDigest("sha256:" + "c".repeat(64)),
                    address = relation.address.copy(catalogName = "other_warehouse"),
                ),
            ),
        )
        assertEquals(
            "Semantic model `orders` reads analytics.orders, which matches more than one table in this connector's Catalog.",
            assertIs<SemanticRenderResult.Invalid>(service.render(query("revenue"))).definitionIssues.single().message,
        )
    }

    @Test
    fun `a connector without a current Catalog is a typed invalid on every query path`(): Unit = runBlocking {
        saveModel(orders())
        saveMetric(revenue())
        currentCatalog = null
        val service = queryService(AcceptingMetricFlowPort)
        val reasons = listOf(SemanticInvalidReason(SemanticInvalidReasonCode.CATALOG_RELATION_NOT_FOUND, null))
        val issues = listOf(
            SemanticDefinitionIssue(
                SemanticInvalidReasonCode.CATALOG_RELATION_NOT_FOUND,
                null,
                "This connector has no tables loaded in Catalog yet.",
            ),
        )

        assertEquals(SemanticRenderResult.Invalid(reasons, issues), service.render(query("revenue")))
        assertEquals(SemanticGroupByOptionsResult.Invalid(reasons, issues), service.listGroupBys(groupBys("revenue")))
        assertEquals(
            listOf(SemanticRenderResult.Invalid(reasons, issues), SemanticRenderResult.MetricNotFound(SemanticElementName("unknown"))),
            service.renderBatch(listOf(query("revenue"), query("unknown")), Instant.now().plusSeconds(10)),
        )
    }

    @Test
    fun `candidates the engine names are left out round by round with their first message`(): Unit = runBlocking {
        saveModel(orders())
        listOf("alpha", "bravo", "charlie", "delta").forEach { saveModel(candidate(it)) }
        saveMetric(revenue())
        val longMessage = "alpha ".repeat(120)
        val port = ScriptedValidation { names ->
            when {
                "alpha" in names -> invalid(
                    SemanticMetricFlowIssue(longMessage, SemanticMetricFlowIssueSubject.Model("alpha")),
                    SemanticMetricFlowIssue("alpha again", SemanticMetricFlowIssueSubject.ModelElement("alpha", "status")),
                    SemanticMetricFlowIssue("orders note", SemanticMetricFlowIssueSubject.Model("orders")),
                )
                "bravo" in names -> invalid(
                    SemanticMetricFlowIssue("bravo broken", SemanticMetricFlowIssueSubject.ModelElement("bravo", "status")),
                )
                else -> SemanticMetricFlowValidationResult.Valid
            }
        }

        val listed = assertIs<SemanticGroupByOptionsResult.Listed>(queryService(port).listGroupBys(groupBys("revenue")))

        assertEquals(
            listOf(
                SemanticExcludedModel(SemanticElementName("alpha"), longMessage.take(512)),
                SemanticExcludedModel(SemanticElementName("bravo"), "bravo broken"),
            ),
            listed.excludedSemanticModels,
        )
        assertEquals(
            listOf(
                setOf("orders", "alpha", "bravo", "charlie", "delta"),
                setOf("orders"),
                setOf("orders", "bravo", "charlie", "delta"),
                setOf("orders", "charlie", "delta"),
            ),
            port.validated,
        )
        assertEquals(setOf("orders", "charlie", "delta"), port.listedModels.single())
    }

    @Test
    fun `a candidate that only conflicts with a left-out candidate stays`(): Unit = runBlocking {
        currentCatalog = catalogWith("customers", "regions")
        saveModel(orders())
        saveModel(orderTwin("order_details", "customers", "ordered_at", "region"))
        saveModel(orderTwin("order_regions", "regions", "region"))
        saveMetric(revenue())
        val engine = RecordingValidation(MetricFlowSemanticRenderer.production())

        val listed = assertIs<SemanticGroupByOptionsResult.Listed>(queryService(engine).listGroupBys(groupBys("revenue")))

        assertEquals(listOf(SemanticElementName("order_details")), listed.excludedSemanticModels.map(SemanticExcludedModel::name))
        val complete = assertIs<SemanticMetricFlowValidationResult.Invalid>(engine.validations.first().second)
        assertEquals(
            setOf("order_details", "order_regions"),
            complete.issues.mapNotNull { issue ->
                when (val subject = issue.subject) {
                    is SemanticMetricFlowIssueSubject.Model -> subject.semanticModelName
                    is SemanticMetricFlowIssueSubject.ModelElement -> subject.semanticModelName
                    else -> null
                }
            }.toSet(),
            complete.issues.toString(),
        )
        assertEquals(setOf("orders", "order_regions"), engine.validations.last().first)
        assertIs<SemanticMetricFlowValidationResult.Valid>(engine.validations.last().second)
    }

    @Test
    fun `findings that name no remaining candidate blame each candidate that breaks the closure on its own`(): Unit = runBlocking {
        saveModel(orders())
        listOf("alpha", "bravo", "charlie").forEach { saveModel(candidate(it)) }
        saveMetric(revenue())
        val partial = ScriptedValidation { names ->
            when {
                "alpha" in names -> invalid(SemanticMetricFlowIssue("alpha broken", SemanticMetricFlowIssueSubject.Model("alpha")))
                names.size > 1 -> invalid(
                    SemanticMetricFlowIssue("cannot attribute", null),
                    SemanticMetricFlowIssue("metric note", SemanticMetricFlowIssueSubject.Metric("revenue")),
                )
                else -> SemanticMetricFlowValidationResult.Valid
            }
        }

        val listed = assertIs<SemanticGroupByOptionsResult.Listed>(queryService(partial).listGroupBys(groupBys("revenue")))

        assertEquals(
            listOf("alpha" to "alpha broken", "bravo" to "cannot attribute", "charlie" to "cannot attribute"),
            listed.excludedSemanticModels.map { it.name.value to it.message },
        )
        assertEquals(
            listOf(
                setOf("orders", "alpha", "bravo", "charlie"),
                setOf("orders"),
                setOf("orders", "bravo", "charlie"),
                setOf("orders", "bravo"),
                setOf("orders", "charlie"),
            ),
            partial.validated,
        )
        assertEquals(setOf("orders"), partial.listedModels.single())

        val silent = ScriptedValidation { names ->
            if (names.size > 1) {
                SemanticMetricFlowValidationResult.Invalid(
                    listOf(SemanticMetricFlowInvalidReason(SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID, null)),
                    emptyList(),
                )
            } else {
                SemanticMetricFlowValidationResult.Valid
            }
        }
        assertIs<SemanticRenderResult.Rendered>(queryService(silent).render(query("revenue")))
        assertEquals(
            setOf("MetricFlow can't use this semantic model with the selected metrics."),
            assertIs<SemanticGroupByOptionsResult.Listed>(queryService(silent).listGroupBys(groupBys("revenue")))
                .excludedSemanticModels.map(SemanticExcludedModel::message).toSet(),
        )
    }

    @Test
    fun `a finding that names no model leaves out only the candidate that causes it`(): Unit = runBlocking {
        saveModel(orders())
        listOf("alpha", "bravo", "charlie").forEach { saveModel(candidate(it)) }
        saveMetric(revenue())
        val port = ScriptedValidation { names ->
            if ("bravo" in names) invalid(SemanticMetricFlowIssue("labels differ", null)) else SemanticMetricFlowValidationResult.Valid
        }

        val listed = assertIs<SemanticGroupByOptionsResult.Listed>(queryService(port).listGroupBys(groupBys("revenue")))

        assertEquals(listOf("bravo" to "labels differ"), listed.excludedSemanticModels.map { it.name.value to it.message })
        assertEquals(setOf("orders", "alpha", "charlie"), port.listedModels.single())
    }

    @Test
    fun `an entity label conflict the engine cannot attribute leaves out only the conflicting candidate`(): Unit = runBlocking {
        currentCatalog = catalogWith("customers", "regions")
        saveModel(orders().copy(entities = listOf(entity("order_id", SemanticEntityKind.PRIMARY), labelledEntity("customer", SemanticEntityKind.FOREIGN, "Customer"))))
        saveModel(customers(statusKind = SemanticDimensionKind.CATEGORICAL).copy(entities = listOf(labelledEntity("customer", SemanticEntityKind.PRIMARY, "Customer ID"))))
        saveModel(orderTwin("order_regions", "regions", "region"))
        saveMetric(revenue())
        val engine = RecordingValidation(MetricFlowSemanticRenderer.production())

        val listed = assertIs<SemanticGroupByOptionsResult.Listed>(queryService(engine).listGroupBys(groupBys("revenue")))

        val complete = assertIs<SemanticMetricFlowValidationResult.Invalid>(engine.validations.first().second)
        assertTrue(complete.issues.all { it.subject == null }, complete.issues.toString())
        assertEquals(listOf(SemanticElementName("customers")), listed.excludedSemanticModels.map(SemanticExcludedModel::name))
        assertEquals(setOf("orders", "order_regions"), engine.validations.last().first)
        assertIs<SemanticMetricFlowValidationResult.Valid>(engine.validations.last().second)
    }

    @Test
    fun `a closure the engine rejects fails with its own findings and no candidate is blamed`(): Unit = runBlocking {
        saveModel(orders())
        saveModel(candidate("alpha"))
        saveMetric(revenue())
        val port = ScriptedValidation {
            invalid(
                SemanticMetricFlowIssue("revenue broken", SemanticMetricFlowIssueSubject.Metric("revenue")),
                SemanticMetricFlowIssue("orders broken", SemanticMetricFlowIssueSubject.ModelElement("orders", "status")),
                SemanticMetricFlowIssue("unknown model", SemanticMetricFlowIssueSubject.Model("ghost")),
                SemanticMetricFlowIssue("cycle", null),
            )
        }

        val invalid = assertIs<SemanticRenderResult.Invalid>(queryService(port).render(query("revenue")))

        assertEquals(listOf(SemanticInvalidReason(SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID, null)), invalid.reasons)
        assertEquals(
            listOf(
                SemanticDefinitionReference.Metric(SemanticElementName("revenue")) to "revenue broken",
                SemanticDefinitionReference.SemanticModel(SemanticElementName("orders")) to "orders broken",
                null to "unknown model",
                null to "cycle",
            ),
            invalid.definitionIssues.map { it.definition to it.message },
        )
        assertEquals(listOf(setOf("orders", "alpha"), setOf("orders")), port.validated)
    }

    private fun queryService(port: SemanticMetricFlowPort) = SemanticQueryService(
        repository, repository, ImmediateTransactionPort, port, StaticConnectorRead(workspace, connector, NOW), catalogRead(),
        FixedReportingTimeZone(UTC_REPORTING_TIME_ZONE),
    )

    private fun checkService(port: SemanticMetricFlowPort) = SemanticDefinitionCheckService(
        repository, repository, ImmediateTransactionPort, port, StaticConnectorRead(workspace, connector, NOW), catalogRead(),
        FixedReportingTimeZone(UTC_REPORTING_TIME_ZONE),
    )

    private fun catalogRead() = object : CatalogCurrentObjectsReadPort by StaticCatalogRead(workspace, connector, NOW) {
        override fun readCurrent(workspaceId: WorkspaceId, connectorId: ConnectorId): CatalogCurrentObjectSet? =
            currentCatalog?.takeIf { workspaceId == workspace && connectorId == connector }
    }

    private fun catalogWith(vararg relations: String): CatalogCurrentObjectSet {
        val catalog = catalogObjects(workspace, connector, NOW)
        val orders = catalog.objects.single { it.address.objectName == "orders" }
        return catalog.copy(
            objects = catalog.objects + relations.mapIndexed { index, name ->
                orders.copy(
                    id = CatalogObjectId(Uuid.parse("0197d83f-2c9e-8a3b-8c1d-00000000001$index")),
                    identityDigest = CatalogDigest("sha256:" + "a$index".repeat(32)),
                    address = orders.address.copy(objectName = name),
                    qualifiedName = "analytics.$name",
                )
            },
        )
    }

    private fun saveModel(definition: SemanticModelDefinition): SemanticModelId {
        val id = SemanticModelId.generate()
        repository.models[id] = CurrentSemanticModel(id, workspace, definition, NOW, NOW)
        return id
    }

    private fun saveMetric(definition: SemanticMetricDefinition): MetricId {
        val id = MetricId.generate()
        repository.metrics[id] = CurrentMetric(id, workspace, definition, NOW, NOW)
        return id
    }

    private fun query(
        metric: String,
        groupBys: List<SemanticGroupBy> = emptyList(),
        filters: List<SemanticQueryFilter> = emptyList(),
    ) = SemanticRenderRequest(
        workspace,
        connector,
        SemanticQuerySpec(listOf(SemanticElementName(metric)), groupBys, null, filters, emptyList(), 10),
    )

    private fun groupBys(metric: String) = SemanticGroupByOptionsRequest(workspace, connector, listOf(SemanticElementName(metric)))

    private fun orders() = SemanticModelDefinition(
        schemaVersion = 3u,
        name = SemanticElementName("orders"),
        label = null,
        description = null,
        sourceRelations = listOf(SemanticSourceRelation(connector, null, "analytics", "orders")),
        aggregationTimeDimension = SemanticElementName("ordered_at"),
        entities = listOf(entity("order_id", SemanticEntityKind.PRIMARY), entity("customer", SemanticEntityKind.FOREIGN)),
        dimensions = listOf(
            SemanticDimensionDefinition(SemanticElementName("ordered_at"), SemanticDimensionKind.TIME, "ordered_at", SemanticTimeGranularity.DAY, null, null,
                false,
                null,
            ),
            SemanticDimensionDefinition(SemanticElementName("status"), SemanticDimensionKind.CATEGORICAL, "status", null, null, null,
                false,
                null,
            ),
        ),
        measures = listOf(SemanticMeasureDefinition(SemanticElementName("revenue"), SemanticMeasureAggregation.SUM, "amount", null, null, null, null)),
    )

    /** Its `status` is a time dimension, which conflicts with the categorical `orders.status`. */
    private fun shipments() = SemanticModelDefinition(
        schemaVersion = 3u,
        name = SemanticElementName("shipments"),
        label = null,
        description = null,
        sourceRelations = listOf(SemanticSourceRelation(connector, null, "analytics", "calendar")),
        aggregationTimeDimension = null,
        entities = listOf(entity("shipment_id", SemanticEntityKind.PRIMARY)),
        dimensions = listOf(
            SemanticDimensionDefinition(SemanticElementName("status"), SemanticDimensionKind.TIME, "shipped_at", SemanticTimeGranularity.DAY, null, null,
                false,
                null,
            ),
        ),
        measures = emptyList(),
    )

    private fun customers(statusKind: SemanticDimensionKind) = SemanticModelDefinition(
        schemaVersion = 3u,
        name = SemanticElementName("customers"),
        label = null,
        description = null,
        sourceRelations = listOf(SemanticSourceRelation(connector, null, "analytics", "customers")),
        aggregationTimeDimension = null,
        entities = listOf(entity("customer", SemanticEntityKind.PRIMARY)),
        dimensions = listOf(
            SemanticDimensionDefinition(SemanticElementName("label"), SemanticDimensionKind.CATEGORICAL, "label", null, null, null,
                false,
                null,
            ),
            SemanticDimensionDefinition(
                SemanticElementName("status"),
                statusKind,
                "status",
                SemanticTimeGranularity.DAY.takeIf { statusKind == SemanticDimensionKind.TIME },
                null,
                null,
                isPartition = false,
                timeKind = null,
            ),
        ),
        measures = emptyList(),
    )

    /** Shares `orders`' primary entity, so each extra dimension pairs with `order_id` like a dimension of `orders`. */
    private fun orderTwin(name: String, relation: String, vararg dimensions: String) = shipments().copy(
        name = SemanticElementName(name),
        sourceRelations = listOf(SemanticSourceRelation(connector, null, "analytics", relation)),
        entities = listOf(entity("order_id", SemanticEntityKind.PRIMARY)),
        dimensions = dimensions.map { dimension ->
            if (dimension == "ordered_at") {
                SemanticDimensionDefinition(SemanticElementName(dimension), SemanticDimensionKind.TIME, dimension, SemanticTimeGranularity.DAY, null, null,
                    false,
                    null,
                )
            } else {
                SemanticDimensionDefinition(SemanticElementName(dimension), SemanticDimensionKind.CATEGORICAL, dimension, null, null, null,
                    false,
                    null,
                )
            }
        },
    )

    private fun candidate(name: String) = shipments().copy(
        name = SemanticElementName(name),
        entities = listOf(entity("order_id", SemanticEntityKind.PRIMARY)),
        dimensions = emptyList(),
    )

    private fun labelledEntity(name: String, kind: SemanticEntityKind, label: String) =
        SemanticEntityDefinition(SemanticElementName(name), kind, name, label, null)

    private fun entity(name: String, kind: SemanticEntityKind) =
        SemanticEntityDefinition(SemanticElementName(name), kind, name, null, null)

    private fun revenue(name: String = "revenue", model: String = "orders", measure: String = "revenue") =
        SemanticMetricDefinition.Simple(
            schemaVersion = 3u,
            name = SemanticElementName(name),
            label = null,
            description = null,
            timeGranularity = null,
            filters = emptyList(),
            semanticModelName = SemanticElementName(model),
            measure = SemanticElementName(measure),
            display = null,
        )

    private fun derived(name: String, input: String) = SemanticMetricDefinition.Derived(
        schemaVersion = 3u,
        name = SemanticElementName(name),
        label = null,
        description = null,
        timeGranularity = null,
        filters = emptyList(),
        expression = input,
        inputMetrics = listOf(SemanticMetricInput(SemanticElementName(input), null, null, null, null)),
        display = null,
    )

    private fun invalid(vararg issues: SemanticMetricFlowIssue) = SemanticMetricFlowValidationResult.Invalid(
        listOf(SemanticMetricFlowInvalidReason(SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID, null)),
        issues.toList(),
    )

    private class RecordingValidation(private val delegate: SemanticMetricFlowPort) : SemanticMetricFlowPort by delegate {
        val validations = mutableListOf<Pair<Set<String>, SemanticMetricFlowValidationResult>>()

        override fun validate(manifest: SemanticMetricFlowManifest): SemanticMetricFlowValidationResult =
            delegate.validate(manifest).also { result -> validations += manifest.models.keys.map { it.value }.toSet() to result }
    }

    private class ScriptedValidation(
        private val script: (Set<String>) -> SemanticMetricFlowValidationResult,
    ) : SemanticMetricFlowPort by AcceptingMetricFlowPort {
        val validated = mutableListOf<Set<String>>()
        val listedModels = mutableListOf<Set<String>>()

        override fun validate(manifest: SemanticMetricFlowManifest): SemanticMetricFlowValidationResult {
            val names = manifest.models.keys.map { it.value }.toSet()
            validated += names
            return script(names)
        }

        override fun listGroupBys(
            manifest: SemanticMetricFlowManifest,
            metricIds: List<MetricId>,
        ): cc.monomer.semantic.application.SemanticMetricFlowGroupByResult {
            listedModels += manifest.models.keys.map { it.value }.toSet()
            return AcceptingMetricFlowPort.listGroupBys(manifest, metricIds)
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-06T00:00:00Z")
    }
}
