@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.lineage

import cc.monomer.authority.api.CurrentResourceFocusCone
import cc.monomer.authority.api.CurrentResourceFocusConePort
import cc.monomer.authority.api.CurrentResourceFocusConeResolution
import cc.monomer.authority.api.ResourceKind
import cc.monomer.authority.api.ResourceRef
import cc.monomer.catalog.api.CatalogCurrentObject
import cc.monomer.catalog.api.CatalogCurrentObjectSet
import cc.monomer.catalog.api.CatalogObjectId
import cc.monomer.catalog.api.CatalogObjectKind
import cc.monomer.catalog.api.CatalogCurrentObjectsReadPort
import cc.monomer.catalog.api.CatalogCurrentRelationPage
import cc.monomer.catalog.api.CatalogCurrentRelationPageCursor
import cc.monomer.catalog.api.CatalogCurrentRelationSummary
import cc.monomer.catalog.api.CatalogPhysicalAddress
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorProductType
import cc.monomer.connector.api.ConnectorWorkspaceReadUseCase
import cc.monomer.connector.api.CurrentConnectorRead
import cc.monomer.control.agent.managementBody
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionDenial
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.identity.api.IdentityAdmissionDenial
import cc.monomer.identity.api.IdentityRoleEvidence
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.domain.port.ConsistentSnapshotTransactionRunner
import cc.monomer.semantic.api.SemanticCurrentReadUseCase
import cc.monomer.semantic.api.SemanticElementName
import cc.monomer.semantic.api.SemanticMetricDefinition
import cc.monomer.semantic.api.SemanticModelDefinition
import cc.monomer.semantic.api.SemanticModelId
import cc.monomer.semantic.api.SemanticModelView
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.MetricView
import java.time.Instant
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking

class LineageGraphQueryServiceTest {
    private val workspaceId = WorkspaceId.generate()
    private val connectorId = ConnectorId.generate()
    private val relation = ResourceRef(
        ResourceKind.RELATION,
        Uuid.parse("019e0000-0000-8000-8000-0000000000a1"),
    )
    private val credential = PresentedCredential("session")
    private val dashboardOwner = mockk<cc.monomer.dashboard.api.DashboardOwnerPort>().also { owner ->
        coEvery { owner.readCurrentSemanticReferences(workspaceId) } returns emptyList()
    }

    @Test
    fun `default focus chooses the most recently updated semantic resource across models and metrics`() = runBlocking {
        val semanticRead = mockk<SemanticCurrentReadUseCase>()
        val olderModel = semanticModelView("orders", Instant.parse("2026-08-20T10:00:00Z"))
        val newerMetric = metricView("net_revenue", Instant.parse("2026-08-21T10:00:00Z"))
        coEvery { semanticRead.findLatestModel(workspaceId) } returns olderModel
        coEvery { semanticRead.findLatestMetric(workspaceId) } returns newerMetric
        val admission = admittedMembership()
        val connectorRead = mockk<ConnectorWorkspaceReadUseCase>()

        val result = service(
            mockk(),
            connectorRead,
            mockk(),
            semanticRead,
            admission,
        ).readDefaultFocus(workspaceId, credential, expectedOAuthResource = null)

        val found = assertIs<LineageDefaultFocusResult.Found>(result)
        assertEquals(ResourceKind.METRIC, found.resource.ref.kind)
        assertEquals(newerMetric.metricId.value, found.resource.ref.id)
        io.mockk.coVerify(exactly = 0) { connectorRead.listByWorkspace(workspaceId) }
    }

    @Test
    fun `default focus falls back to the first deterministic relation only when semantic resources are empty`() = runBlocking {
        val semanticRead = mockk<SemanticCurrentReadUseCase>()
        coEvery { semanticRead.findLatestModel(workspaceId) } returns null
        coEvery { semanticRead.findLatestMetric(workspaceId) } returns null
        val connectorRead = mockk<ConnectorWorkspaceReadUseCase>()
        val connector = connector()
        coEvery { connectorRead.findByWorkspaceAndId(workspaceId, connectorId) } returns connector
        val catalogRead = mockk<CatalogCurrentObjectsReadPort>()
        every { catalogRead.searchCurrentRelations(workspaceId, "", null, 1) } returns CatalogCurrentRelationPage(
            items = listOf(
                CatalogCurrentRelationSummary(
                    id = CatalogObjectId(Uuid.parse("019e0000-0000-8000-8000-0000000000b1")),
                    connectorId = connectorId,
                    qualifiedName = "warehouse.analytics.accounts",
                ),
            ),
            nextCursor = CatalogCurrentRelationPageCursor(
                "warehouse.analytics.accounts",
                connectorId,
                CatalogObjectId(Uuid.parse("019e0000-0000-8000-8000-0000000000b1")),
            ),
        )

        val result = service(
            mockk(),
            connectorRead,
            catalogRead,
            semanticRead,
            admittedMembership(),
        ).readDefaultFocus(workspaceId, credential, expectedOAuthResource = null)

        val found = assertIs<LineageDefaultFocusResult.Found>(result)
        assertEquals(ResourceKind.RELATION, found.resource.ref.kind)
        assertEquals("warehouse.analytics.accounts", found.resource.label)
    }

    @Test
    fun `default focus is empty when the workspace has no current resources`() = runBlocking {
        val semanticRead = mockk<SemanticCurrentReadUseCase>()
        coEvery { semanticRead.findLatestModel(workspaceId) } returns null
        coEvery { semanticRead.findLatestMetric(workspaceId) } returns null
        val catalogRead = mockk<CatalogCurrentObjectsReadPort>()
        every { catalogRead.searchCurrentRelations(workspaceId, "", null, 1) } returns
            CatalogCurrentRelationPage(emptyList(), null)

        val result = service(
            mockk(),
            mockk(),
            catalogRead,
            semanticRead,
            admittedMembership(),
        ).readDefaultFocus(workspaceId, credential, expectedOAuthResource = null)

        assertEquals(LineageDefaultFocusResult.Empty, result)
    }

    @Test
    fun `non member cannot observe a default focus`() = runBlocking {
        val semanticRead = mockk<SemanticCurrentReadUseCase>()
        val admission = mockk<ControlAdmissionFacade>()
        coEvery { admission.authorizeWorkspaceRole(any()) } returns
            ControlAdmissionDecision.Denied(
                cc.monomer.control.ControlAdmissionStage.IDENTITY,
                ControlAdmissionDenial.Identity(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE),
            )

        val result = service(
            mockk(),
            mockk(),
            mockk(),
            semanticRead,
            admission,
        ).readDefaultFocus(workspaceId, credential, expectedOAuthResource = null)

        assertEquals(LineageDefaultFocusResult.Unavailable, result)
        io.mockk.coVerify(exactly = 0) { semanticRead.findLatestModel(workspaceId) }
        io.mockk.coVerify(exactly = 0) { semanticRead.findLatestMetric(workspaceId) }
    }

    @Test
    fun `member searches and pages current relation resources by display metadata`() = runBlocking {
        val focusConePort = mockk<CurrentResourceFocusConePort>()
        val connectorRead = mockk<ConnectorWorkspaceReadUseCase>()
        val connector = mockk<CurrentConnectorRead>()
        every { connector.connectorId } returns connectorId
        every { connector.displayName } returns "Northstar warehouse"
        every { connector.connectorType } returns ConnectorProductType.POSTGRESQL
        coEvery { connectorRead.findByWorkspaceAndIds(workspaceId, setOf(connectorId)) } returns listOf(connector)
        val catalogRead = mockk<CatalogCurrentObjectsReadPort>()
        val accountRelationId = CatalogObjectId(Uuid.parse("019e0000-0000-8000-8000-0000000000b1"))
        val ordersId = CatalogObjectId(Uuid.parse("019e0000-0000-8000-8000-0000000000b3"))
        val ownerCursor = CatalogCurrentRelationPageCursor(
            "warehouse.analytics.accounts",
            connectorId,
            accountRelationId,
        )
        every { catalogRead.searchCurrentRelations(workspaceId, "analytics", null, 1) } returns
            CatalogCurrentRelationPage(
                listOf(CatalogCurrentRelationSummary(accountRelationId, connectorId, "warehouse.analytics.accounts")),
                ownerCursor,
            )
        every { catalogRead.searchCurrentRelations(workspaceId, "analytics", ownerCursor, 1) } returns
            CatalogCurrentRelationPage(
                listOf(CatalogCurrentRelationSummary(ordersId, connectorId, "warehouse.analytics.z_orders")),
                null,
            )
        val admission = mockk<ControlAdmissionFacade>()
        coEvery { admission.authorizeWorkspaceRole(any()) } returns
            ControlAdmissionDecision.Admitted(mockk<IdentityRoleEvidence>())

        val firstPage = service(
            focusConePort,
            connectorRead,
            catalogRead,
            mockk(),
            admission,
        ).listResources(
            workspaceId = workspaceId,
            kind = ResourceKind.RELATION,
            query = "analytics",
            cursor = null,
            pageSize = 1,
            credential = credential, expectedOAuthResource = null
        )

        val found = assertIs<LineageResourceInventoryResult.Found>(firstPage)
        assertEquals(listOf("warehouse.analytics.accounts"), found.page.items.map(LineageResource::label))
        val cursor = assertIs<LineageResourcePageCursor.Relation>(found.page.nextCursor)
        assertEquals(ownerCursor, cursor.ownerCursor)

        val secondPage = service(
            focusConePort,
            connectorRead,
            catalogRead,
            mockk(),
            admission,
        ).listResources(workspaceId, ResourceKind.RELATION, "analytics", cursor, 1, credential, expectedOAuthResource = null)
        val secondFound = assertIs<LineageResourceInventoryResult.Found>(secondPage)
        assertEquals(listOf("warehouse.analytics.z_orders"), secondFound.page.items.map(LineageResource::label))
        assertEquals(null, secondFound.page.nextCursor)
        io.mockk.coVerify(exactly = 2) {
            connectorRead.findByWorkspaceAndIds(workspaceId, setOf(connectorId))
        }
        io.mockk.coVerify(exactly = 0) { connectorRead.listByWorkspace(workspaceId) }
    }

    @Test
    fun `resource cursor cannot be reused across a different query`() = runBlocking {
        val catalogRead = mockk<CatalogCurrentObjectsReadPort>()
        val admission = mockk<ControlAdmissionFacade>()
        coEvery { admission.authorizeWorkspaceRole(any()) } returns
            ControlAdmissionDecision.Admitted(mockk<IdentityRoleEvidence>())
        val cursor = LineageResourcePageCursor.Relation(
            normalizedQuery = "orders",
            ownerCursor = CatalogCurrentRelationPageCursor(
                normalizedQualifiedName = "warehouse.analytics.orders",
                connectorId = connectorId,
                objectId = CatalogObjectId(Uuid.parse("019e0000-0000-8000-8000-0000000000b1")),
            ),
        )

        val result = service(
            mockk(),
            mockk(),
            catalogRead,
            mockk(),
            admission,
        ).listResources(workspaceId, ResourceKind.RELATION, "customers", cursor, 20, credential, expectedOAuthResource = null)

        assertEquals(LineageResourceInventoryResult.InvalidCursor, result)
        io.mockk.verify(exactly = 0) {
            catalogRead.searchCurrentRelations(workspaceId, "customers", cursor.ownerCursor, 20)
        }
    }

    @Test
    fun `missing model metadata is rendered without reading a nonexistent current definition`() = runBlocking {
        val metric = MetricView(MetricId.generate(), workspaceId,
            SemanticMetricDefinition.Simple(3u, SemanticElementName("revenue"), null, null, null, emptyList(),
                SemanticElementName("deleted_orders"), SemanticElementName("amount"), display = null), Instant.now(), Instant.now(),
                    emptyList(),
                )
        val metricRef = ResourceRef(ResourceKind.METRIC, metric.metricId.value)
        val missingRef = ResourceRef(ResourceKind.SEMANTIC_MODEL, Uuid.generateV7())
        val edge = cc.monomer.authority.api.LineageEdge(metricRef, missingRef)
        val port = mockk<CurrentResourceFocusConePort>()
        every { port.readCurrentFocusCone(workspaceId, metricRef) } returns CurrentResourceFocusConeResolution.Resolved(
            CurrentResourceFocusCone(workspaceId, metricRef, emptyList(), emptyList(), emptyList(), listOf(edge), emptyList(),
                listOf(cc.monomer.authority.api.MissingDefinitionReference(missingRef, "deleted_orders"))))
        val semantic = mockk<SemanticCurrentReadUseCase>()
        coEvery { semantic.findMetrics(workspaceId) } returns listOf(metric)
        val result = service(port, mockk(), mockk(), semantic, admittedMembership())
            .read(workspaceId, metricRef, credential, expectedOAuthResource=null)
        val graph = assertIs<LineageGraphQueryResult.Found>(result).graph
        assertEquals(false, graph.focus.missing)
        assertEquals("revenue", graph.focus.label)
        assertEquals(emptyList(), graph.ancestors)
        assertEquals(listOf(edge), graph.edges)
        val missing = graph.missingDefinitions.single()
        assertEquals(true, missing.missing)
        assertEquals("deleted_orders", missing.label)
        assertEquals("Missing definition", missing.description)
        coVerify(exactly=0) { semantic.findModels(workspaceId) }
        val wire = LineageFocusConeView.from(graph)
        assertEquals(true, wire.missingDefinitions.single().missing)
        assertEquals("deleted_orders", wire.missingDefinitions.single().address)
    }

    @Test
    fun `non member cannot observe whether lineage resources exist`() = runBlocking {
        val connectorRead = mockk<ConnectorWorkspaceReadUseCase>()
        val admission = mockk<ControlAdmissionFacade>()
        coEvery { admission.authorizeWorkspaceRole(any()) } returns
            ControlAdmissionDecision.Denied(
                cc.monomer.control.ControlAdmissionStage.IDENTITY,
                ControlAdmissionDenial.Identity(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE),
            )

        val result = service(
            mockk(),
            connectorRead,
            mockk(),
            mockk(),
            admission,
        ).listResources(workspaceId, ResourceKind.RELATION, "", null, 20, credential, expectedOAuthResource = null)

        assertEquals(LineageResourceInventoryResult.Unavailable, result)
        io.mockk.coVerify(exactly = 0) { connectorRead.findByWorkspaceAndIds(workspaceId, any()) }
        io.mockk.coVerify(exactly = 0) { connectorRead.listByWorkspace(workspaceId) }
    }

    @Test
    fun `Explorer can read the full topology`() = runBlocking {
        val focusConePort = mockk<CurrentResourceFocusConePort>()
        every { focusConePort.readCurrentFocusCone(workspaceId, relation) } returns
            CurrentResourceFocusConeResolution.Resolved(
                CurrentResourceFocusCone(workspaceId, relation, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
            )
        val connectorRead = mockk<ConnectorWorkspaceReadUseCase>()
        val connector = connector()
        coEvery { connectorRead.listByWorkspace(workspaceId) } returns listOf(connector)
        val catalogRead = mockk<CatalogCurrentObjectsReadPort>()
        val catalogObject = mockk<CatalogCurrentObject>()
        every { catalogObject.kind } returns CatalogObjectKind.RELATION
        every { catalogObject.id } returns CatalogObjectId(relation.id)
        every { catalogObject.qualifiedName } returns "warehouse.analytics.orders"
        every { catalogObject.relationKind } returns cc.monomer.catalog.api.RelationKind.TABLE
        val currentObjects = mockk<CatalogCurrentObjectSet>()
        every { currentObjects.connectorId } returns connectorId
        every { currentObjects.objects } returns listOf(catalogObject)
        every { catalogRead.readCurrent(workspaceId, connectorId) } returns currentObjects
        val semanticRead = mockk<SemanticCurrentReadUseCase>()
        coEvery { semanticRead.findModels(workspaceId) } returns emptyList()
        coEvery { semanticRead.findMetrics(workspaceId) } returns emptyList()
        val admission = mockk<ControlAdmissionFacade>()
        coEvery { admission.authorizeWorkspaceRole(any()) } returns
            ControlAdmissionDecision.Admitted(mockk<IdentityRoleEvidence>())
        val result = service(
            focusConePort,
            connectorRead,
            catalogRead,
            semanticRead,
            admission,
        ).read(workspaceId, relation, credential, expectedOAuthResource = null)

        val found = assertIs<LineageGraphQueryResult.Found>(result)
        assertEquals("warehouse.analytics.orders", found.graph.focus.label)
        assertEquals(emptyList(), found.graph.entityPeers)
    }

    @Test
    fun `non member cannot observe whether a focused resource exists`() = runBlocking {
        val focusConePort = mockk<CurrentResourceFocusConePort>()
        val admission = mockk<ControlAdmissionFacade>()
        coEvery { admission.authorizeWorkspaceRole(any()) } returns
            ControlAdmissionDecision.Denied(
                cc.monomer.control.ControlAdmissionStage.IDENTITY,
                ControlAdmissionDenial.Identity(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE),
            )

        val result = service(
            focusConePort,
            mockk(),
            mockk(),
            mockk(),
            admission,
        ).read(workspaceId, relation, credential, expectedOAuthResource = null)

        assertEquals(LineageGraphQueryResult.Unavailable, result)
        io.mockk.verify(exactly = 0) { focusConePort.readCurrentFocusCone(workspaceId, relation) }
        coVerify(exactly = 0) { dashboardOwner.readCurrentSemanticReferences(workspaceId) }
    }

    @Test
    fun `member receives a typed unresolved lineage reason instead of a partial cone`(): Unit = runBlocking {
        val focusConePort = mockk<CurrentResourceFocusConePort>()
        val admission = mockk<ControlAdmissionFacade>()
        coEvery { admission.authorizeWorkspaceRole(any()) } returns
            ControlAdmissionDecision.Admitted(mockk())
        io.mockk.every { focusConePort.readCurrentFocusCone(workspaceId, relation) } returns
            CurrentResourceFocusConeResolution.Denied(
                cc.monomer.authority.api.CurrentResourceFocusConeDenial.UNRESOLVED_LINEAGE,
            )
        val result = service(focusConePort, mockk(), mockk(), mockk(), admission)
            .read(workspaceId, relation, credential, expectedOAuthResource = null)
        assertEquals(
            LineageGraphQueryResult.InvalidLineage(
                cc.monomer.authority.api.CurrentResourceFocusConeDenial.UNRESOLVED_LINEAGE,
            ),
            result,
        )
    }

    @Test
    fun `Dashboard usage includes focus and transitive metrics while excluding ancestors and unrelated queries`() = runBlocking {
        val now = Instant.now()
        fun metric(name: String) = MetricView(MetricId.generate(), workspaceId,
            SemanticMetricDefinition.Simple(3u, SemanticElementName(name), null, null, null, emptyList(),
                SemanticElementName("orders"), SemanticElementName("amount"), display = null), now, now,
                    emptyList(),
                )
        val ancestor = metric("gross_revenue")
        val focus = metric("net_revenue")
        val descendant = metric("revenue_per_order")
        val unrelated = metric("customers")
        val ancestorRef = ResourceRef(ResourceKind.METRIC, ancestor.metricId.value)
        val focusRef = ResourceRef(ResourceKind.METRIC, focus.metricId.value)
        val descendantRef = ResourceRef(ResourceKind.METRIC, descendant.metricId.value)
        val edges = listOf(cc.monomer.authority.api.LineageEdge(focusRef, ancestorRef),
            cc.monomer.authority.api.LineageEdge(descendantRef, focusRef)).sorted()
        val port = mockk<CurrentResourceFocusConePort>()
        every { port.readCurrentFocusCone(workspaceId, focusRef) } returns CurrentResourceFocusConeResolution.Resolved(
            CurrentResourceFocusCone(workspaceId, focusRef, listOf(ancestorRef), listOf(descendantRef), emptyList(), edges, emptyList(), emptyList()))
        val semantic = mockk<SemanticCurrentReadUseCase>()
        coEvery { semantic.findMetrics(workspaceId) } returns listOf(ancestor, focus, descendant, unrelated)
        val dashboardId = cc.monomer.dashboard.api.DashboardId.generate()
        coEvery { dashboardOwner.readCurrentSemanticReferences(workspaceId) } returns listOf(
            cc.monomer.dashboard.api.DashboardCurrentSemanticReferences(dashboardId, "Revenue overview",
                mapOf("z_direct" to setOf("net_revenue"), "a_derived" to setOf("revenue_per_order", "net_revenue"),
                    "ancestor_only" to setOf("gross_revenue"), "unrelated" to setOf("customers"))),
            cc.monomer.dashboard.api.DashboardCurrentSemanticReferences(cc.monomer.dashboard.api.DashboardId.generate(), "Other overview", mapOf("customers" to setOf("customers"))),
        )
        val graph = assertIs<LineageGraphQueryResult.Found>(service(port, mockk(), mockk(), semantic, admittedMembership())
            .read(workspaceId, focusRef, credential, null)).graph
        val usage = graph.dashboardUsages.single()
        assertEquals(dashboardId.value, usage.dashboardId)
        assertEquals(listOf("a_derived", "z_direct"), usage.queryNames)
        assertEquals(setOf(focus.metricId.value, descendant.metricId.value), usage.metricIds.toSet())
        assertEquals(usage.dashboardId.toString(), LineageFocusConeView.from(graph).dashboardUsages.single().dashboardId)
        assertEquals(usage.metricIds, graph.managementBody().dashboardUsages.single().metricIds)
        coVerify(exactly = 1) { dashboardOwner.readCurrentSemanticReferences(workspaceId) }
    }

    @Test
    fun `Model and Relation focus include Dashboards selecting descendant metrics`() = runBlocking {
        val model = semanticModelView("orders", Instant.now())
        val definition = model.definition
        every { definition.entities } returns emptyList()
        every { definition.dimensions } returns emptyList()
        every { definition.measures } returns emptyList()
        val metric = MetricView(MetricId.generate(), workspaceId,
            SemanticMetricDefinition.Simple(3u, SemanticElementName("revenue"), null, null, null, emptyList(),
                SemanticElementName("orders"), SemanticElementName("amount"), display = null), Instant.now(), Instant.now(),
                    emptyList(),
                )
        val modelRef = ResourceRef(ResourceKind.SEMANTIC_MODEL, model.semanticModelId.value)
        val metricRef = ResourceRef(ResourceKind.METRIC, metric.metricId.value)
        val edges = listOf(cc.monomer.authority.api.LineageEdge(modelRef, relation), cc.monomer.authority.api.LineageEdge(metricRef, modelRef)).sorted()
        val port = mockk<CurrentResourceFocusConePort>()
        every { port.readCurrentFocusCone(workspaceId, relation) } returns CurrentResourceFocusConeResolution.Resolved(
            CurrentResourceFocusCone(workspaceId, relation, emptyList(), listOf(modelRef, metricRef).sorted(), emptyList(), edges, emptyList(), emptyList()))
        every { port.readCurrentFocusCone(workspaceId, modelRef) } returns CurrentResourceFocusConeResolution.Resolved(
            CurrentResourceFocusCone(workspaceId, modelRef, listOf(relation), listOf(metricRef), emptyList(), edges, emptyList(), emptyList()))
        val semantic = mockk<SemanticCurrentReadUseCase>()
        coEvery { semantic.findModels(workspaceId) } returns listOf(model)
        coEvery { semantic.findMetrics(workspaceId) } returns listOf(metric)
        val connectorRead = mockk<ConnectorWorkspaceReadUseCase>()
        coEvery { connectorRead.listByWorkspace(workspaceId) } returns listOf(connector())
        val catalog = mockk<CatalogCurrentObjectsReadPort>()
        val objects = mockk<CatalogCurrentObjectSet>()
        every { objects.connectorId } returns connectorId
        every { objects.objects } returns listOf(catalogRelation(relation.id.toString(), "warehouse.analytics.orders"))
        every { catalog.readCurrent(workspaceId, connectorId) } returns objects
        val dashboardId = cc.monomer.dashboard.api.DashboardId.generate()
        coEvery { dashboardOwner.readCurrentSemanticReferences(workspaceId) } returns listOf(
            cc.monomer.dashboard.api.DashboardCurrentSemanticReferences(dashboardId, "Commerce overview", mapOf("sales" to setOf("revenue"))))
        val service = service(port, connectorRead, catalog, semantic, admittedMembership())
        for (focus in listOf(relation, modelRef)) {
            val graph = assertIs<LineageGraphQueryResult.Found>(service.read(workspaceId, focus, credential, null)).graph
            assertEquals(dashboardId.value, graph.dashboardUsages.single().dashboardId)
            assertEquals(listOf(metric.metricId.value), graph.dashboardUsages.single().metricIds)
        }
    }

    private fun service(
        focusConePort: CurrentResourceFocusConePort,
        connectorRead: ConnectorWorkspaceReadUseCase,
        catalogRead: CatalogCurrentObjectsReadPort,
        semanticRead: SemanticCurrentReadUseCase,
        admission: ControlAdmissionFacade,
    ) = LineageGraphQueryService(
        focusConePort = focusConePort,
        connectorReadUseCase = connectorRead,
        catalogCurrentObjectsReadPort = catalogRead,
        semanticCurrentReadUseCase = semanticRead,
        dashboardOwnerPort = dashboardOwner,
        controlAdmissionFacade = admission,
        consistentSnapshotTransactionRunner = object : ConsistentSnapshotTransactionRunner {
            override suspend fun <T> run(action: suspend () -> T): T = action()
        },
    )

    private fun catalogRelation(id: String, qualifiedName: String): CatalogCurrentObject =
        mockk<CatalogCurrentObject>().also { relation ->
            every { relation.kind } returns CatalogObjectKind.RELATION
            every { relation.id } returns CatalogObjectId(Uuid.parse(id))
            every { relation.qualifiedName } returns qualifiedName
            every { relation.relationKind } returns cc.monomer.catalog.api.RelationKind.TABLE
            val addressParts = qualifiedName.split('.')
            require(addressParts.size == 3)
            every { relation.address } returns CatalogPhysicalAddress(
                addressParts[0],
                addressParts[1],
                addressParts[2],
                null,
            )
        }

    private fun connector(
        id: ConnectorId = connectorId,
        name: String = "Northstar warehouse",
        type: ConnectorProductType = ConnectorProductType.POSTGRESQL,
    ): CurrentConnectorRead = mockk<CurrentConnectorRead>().also { connector ->
        every { connector.connectorId } returns id
        every { connector.displayName } returns name
        every { connector.connectorType } returns type
    }

    private fun semanticModelView(name: String, updatedAt: Instant): SemanticModelView =
        mockk<SemanticModelView>().also { model ->
            val definition = mockk<SemanticModelDefinition>()
            every { definition.label } returns null
            every { definition.name } returns SemanticElementName(name)
            every { definition.description } returns null
            every { model.semanticModelId } returns SemanticModelId.generate()
            every { model.definition } returns definition
            every { model.updatedAt } returns updatedAt
        }

    private fun metricView(name: String, updatedAt: Instant): MetricView =
        mockk<MetricView>().also { metric ->
            val definition = mockk<SemanticMetricDefinition>()
            every { definition.label } returns null
            every { definition.name } returns SemanticElementName(name)
            every { definition.description } returns null
            every { metric.metricId } returns MetricId.generate()
            every { metric.definition } returns definition
            every { metric.updatedAt } returns updatedAt
        }

    private fun admittedMembership(): ControlAdmissionFacade = mockk<ControlAdmissionFacade>().also { admission ->
        coEvery { admission.authorizeWorkspaceRole(any()) } returns
            ControlAdmissionDecision.Admitted(mockk<IdentityRoleEvidence>())
    }

}
