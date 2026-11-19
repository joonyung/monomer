@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.lineage

import cc.monomer.authority.api.LineageEdge
import cc.monomer.authority.api.ResourceKind
import cc.monomer.authority.api.ResourceRef
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorProductType
import cc.monomer.control.auth.inbound.graphql.InvalidCanonicalGraphqlInputException
import cc.monomer.control.auth.inbound.oauth.JwtAuthentication
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.UserCredentialProvenance
import cc.monomer.semantic.api.SemanticDimensionKind
import cc.monomer.semantic.api.SemanticEntityKind
import cc.monomer.semantic.api.SemanticMeasureAggregation
import cc.monomer.semantic.api.SemanticMetricType
import cc.monomer.semantic.api.SemanticTimeGranularity
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import kotlinx.coroutines.reactor.mono
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.core.io.ClassPathResource
import org.springframework.graphql.execution.DefaultExecutionGraphQlService
import org.springframework.graphql.execution.GraphQlSource
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.core.context.ReactiveSecurityContextHolder

class LineageGraphqlResolverTest {
    @Test
    fun `nil and non IETF workspace ids are invalid input instead of internal errors`() {
        val resolver = LineageGraphqlResolver(mockk())
        val authentication = JwtAuthentication(
            UserCredentialProvenance.BrowserSession(UserId(Uuid.generateV7()), SessionId(Uuid.generateV7())),
            PresentedCredential("web-fixture"),
        )
        val focus = LineageResourceRefInput("METRIC", "019e0000-0000-7000-8000-0000000000a4")
        for (workspaceId in listOf("00000000-0000-0000-0000-000000000000", "019e0000-0000-7000-0000-0000000000a1")) {
            for (call in listOf<suspend () -> Any?>(
                { resolver.lineageDefaultFocus(workspaceId) },
                { resolver.lineageResources(workspaceId, "METRIC", null, null, 10) },
                { resolver.lineageFocusCone(workspaceId, focus) },
            )) {
                assertThatThrownBy {
                    mono { call() }
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                        .block()
                }.isInstanceOf(InvalidCanonicalGraphqlInputException::class.java)
                    .hasMessage("workspace id must be a canonical UUID")
            }
        }
    }

    @Test
    fun `connector view exposes the existing connector identity and product type`() {
        val connectorId = ConnectorId(Uuid.parse("019e0000-0000-7000-8000-0000000000a3"))

        val view = LineageConnectorView.from(
            LineageConnector(
                id = connectorId,
                displayName = "Finance lakehouse",
                type = ConnectorProductType.TRINO,
            ),
        )

        assertEquals(connectorId.toString(), view.id)
        assertEquals("Finance lakehouse", view.displayName)
        assertEquals("TRINO", view.type)
    }

    @Test
    fun `sample metric focus serializes DuckDB ancestry and dashboard usages together`() {
        val metricRef = ResourceRef(ResourceKind.METRIC, Uuid.generateV7())
        val modelRef = ResourceRef(ResourceKind.SEMANTIC_MODEL, Uuid.generateV7())
        val relationRef = ResourceRef(ResourceKind.RELATION, Uuid.generateV7())
        val connectorId = ConnectorId(Uuid.generateV7())
        val dashboardId = Uuid.generateV7()
        val graph = LineageGraph(
            workspaceId = WorkspaceId(Uuid.generateV7()),
            focus = LineageResource(metricRef, "Monthly recurring revenue", "mrr", null, null, false),
            ancestors = listOf(
                LineageResource(modelRef, "Monthly account MRR", "monthly_account_mrr", null, null, false),
                LineageResource(
                    ref = relationRef,
                    label = "acme.monthly_account_mrr",
                    address = "acme.monthly_account_mrr",
                    description = null,
                    connector = LineageConnector(connectorId, "Acme Cloud", ConnectorProductType.DUCKDB),
                    missing = false,
                ),
            ),
            descendants = emptyList(),
            siblings = emptyList(),
            edges = listOf(LineageEdge(metricRef, modelRef), LineageEdge(modelRef, relationRef)),
            siblingEdges = emptyList(),
            entityPeers = emptyList(),
            detail = LineageResourceDetail(
                relation = null,
                semanticModel = null,
                metric = LineageMetricDetail(
                    type = SemanticMetricType.SIMPLE,
                    inputs = listOf(LineageMetricInput(LineageMetricInputKind.MEASURE, "account_mrr", "monthly_account_mrr")),
                    timeGranularity = SemanticTimeGranularity.DAY,
                ),
            ),
            missingDefinitions = emptyList(),
            dashboardUsages = listOf(LineageDashboardUsage(dashboardId, "Revenue overview", listOf("revenue"), listOf(metricRef.id))),
        )
        val source = GraphQlSource.schemaResourceBuilder()
            .schemaResources(ClassPathResource("graphql/schema.graphqls"))
            .configureRuntimeWiring { wiring ->
                wiring.type("Query") { query ->
                    query.dataFetcher("lineageFocusCone") { LineageFocusConeView.from(graph) }
                }
            }
            .build()
        val response = ExecutionGraphQlServiceTester.create(DefaultExecutionGraphQlService(source))
            .document(
                """
                query(${'$'}workspaceId: ID!, ${'$'}focus: LineageResourceRefInput!) {
                    lineageFocusCone(workspaceId: ${'$'}workspaceId, focus: ${'$'}focus) {
                        focus { resource { kind id } }
                        ancestors { resource { kind id } connector { id displayName type } }
                        edges { dependent { kind id } parent { kind id } }
                        detail { metric { type inputs { kind name semanticModelName } } }
                        dashboardUsages { dashboardId title queryNames metricIds }
                    }
                }
                """.trimIndent(),
            )
            .variable("workspaceId", graph.workspaceId.toString())
            .variable("focus", mapOf("kind" to metricRef.kind.name, "id" to metricRef.id.toString()))
            .execute()

        response.errors().verify()
        response.path("lineageFocusCone.focus.resource.id").entity(String::class.java).isEqualTo(metricRef.id.toString())
        response.path("lineageFocusCone.ancestors[1].connector.type").entity(String::class.java).isEqualTo("DUCKDB")
        response.path("lineageFocusCone.ancestors[1].connector.id").entity(String::class.java).isEqualTo(connectorId.toString())
        response.path("lineageFocusCone.ancestors[1].connector.displayName").entity(String::class.java).isEqualTo("Acme Cloud")
        response.path("lineageFocusCone.edges[1].parent.id").entity(String::class.java).isEqualTo(relationRef.id.toString())
        response.path("lineageFocusCone.detail.metric.inputs[0].semanticModelName").entity(String::class.java).isEqualTo("monthly_account_mrr")
        response.path("lineageFocusCone.dashboardUsages[0].dashboardId").entity(String::class.java).isEqualTo(dashboardId.toString())
        response.path("lineageFocusCone.dashboardUsages[0].queryNames").entityList(String::class.java).containsExactly("revenue")
        response.path("lineageFocusCone.dashboardUsages[0].metricIds").entityList(String::class.java).containsExactly(metricRef.id.toString())
    }

    @Test
    fun `detail view keeps exactly one typed focus container`() {
        val detail = LineageResourceDetail(
            relation = null,
            semanticModel = LineageSemanticModelDetail(
                entities = listOf(
                    LineageSemanticEntity(
                        name = "organization",
                        label = "Organization",
                        description = "Owning organization",
                        type = SemanticEntityKind.PRIMARY,
                    ),
                ),
                dimensions = listOf(
                    LineageSemanticDimension(
                        name = "ordered_at",
                        label = null,
                        description = null,
                        type = SemanticDimensionKind.TIME,
                        granularity = SemanticTimeGranularity.DAY,
                    ),
                ),
                measures = listOf(
                    LineageSemanticMeasure(
                        name = "revenue",
                        label = null,
                        description = null,
                        aggregation = SemanticMeasureAggregation.SUM,
                    ),
                ),
            ),
            metric = null,
        )

        val view = LineageResourceDetailView.from(detail)

        assertEquals(null, view.relation)
        assertEquals(listOf("organization"), view.semanticModel?.entities?.map { it.name })
        assertEquals(listOf("ordered_at"), view.semanticModel?.dimensions?.map { it.name })
        assertEquals(listOf("revenue"), view.semanticModel?.measures?.map { it.name })
    }
}
