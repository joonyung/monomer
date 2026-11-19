package cc.monomer.control

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.annotation.DirtiesContext
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping
import javax.sql.DataSource

/**
 * Controller unit tests construct their controller directly, so they stay green even when the
 * composition root never registers the controller. This test boots the real context and asserts
 * the enabled Dashboard and Pages routes are present in the live handler mapping.
 */
@ActiveProfiles("local")
@Import(EphemeralControlConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [ControlApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "management.health.db.enabled=false",
        "monomer.credentials.aes-gcm.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "monomer.credentials.aes-gcm.key-ref=integration-test",
        "monomer.control.execution-runtime.credential-vault.keys-base64.local-primary=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    ],
)
class ControlIngressRouteMappingIntegrationTest @Autowired constructor(
    private val requestMappingHandlerMapping: RequestMappingHandlerMapping,
    private val resources: EphemeralControlResources,
    private val dataSource: DataSource,
    private val environment: Environment,
) {
    @Test
    fun `route context uses isolated database and ports`() {
        resources.verifyIsolation(dataSource, environment)
    }

    @Test
    fun `pages routes are registered`() {
        assertRegisteredRoutes("PagesController", PAGES_ROUTES)
        assertRegisteredRoutes("PagesEventStreamController", PAGES_EVENT_STREAM_ROUTES)
        assertRegisteredRoutes("PageContentController", PAGE_CONTENT_ROUTES)
        assertRegisteredRoutes("PageContentViewerController", PAGE_CONTENT_VIEWER_ROUTES)
    }

    @Test
    fun `retired ingress routes are absent while connector reads remain`() {
        assertRegisteredRoutes("ConnectorController", CONNECTOR_READ_ROUTES)
        assertRegisteredRoutes("ConnectorMutationController", CONNECTOR_MUTATION_ROUTES)
        assertRegisteredRoutes(
            "OverviewActivityController",
            setOf("GET $WORKSPACE/overview/activity", "GET $WORKSPACE/overview/activity/agent-apps"),
        )
        assertRegisteredRoutes("CatalogAuthorityController", CATALOG_AUTHORITY_ROUTES)
        assertRegisteredRoutes(
            "CatalogWorkspaceController",
            setOf("GET $WORKSPACE/catalog/connector-summaries", "GET $WORKSPACE/catalog/table-search"),
        )
        assertRegisteredRoutes("WorkspaceSearchController", setOf("GET $WORKSPACE/search"))
        assertRegisteredRoutes("ExploreRunController", EXPLORE_RUN_ROUTES)
        assertRegisteredRoutes("DashboardController", DASHBOARD_ROUTES)
        assertRegisteredRoutes("DashboardDraftController", DASHBOARD_DRAFT_ROUTES)
        assertRegisteredRoutes("DashboardSessionController", DASHBOARD_SESSION_ROUTES)
        assertRegisteredRoutes("DashboardScheduleController", DASHBOARD_SCHEDULE_ROUTES)
        assertRegisteredRoutes("SemanticQueryController", SEMANTIC_QUERY_ROUTES)
        assertRegisteredRoutes("SemanticModelController", SEMANTIC_MODEL_ROUTES)
        assertRegisteredRoutes("MetricController", METRIC_ROUTES)
        assertThat(allRegisteredRoutes())
            .describedAs("all routes registered by the live Control context")
            .doesNotContainAnyElementsOf(RETIRED_ROUTES)
    }

    private fun assertRegisteredRoutes(controllerSimpleName: String, expectedRoutes: Set<String>) {
        val registeredRoutes: Set<String> = requestMappingHandlerMapping.handlerMethods
            .entries
            .filter { entry -> entry.value.beanType.simpleName == controllerSimpleName }
            .flatMap { entry ->
                val httpMethods: List<String> =
                    entry.key.methodsCondition.methods.map { requestMethod -> requestMethod.name }
                entry.key.patternsCondition.patterns.flatMap { pathPattern ->
                    httpMethods.map { httpMethod -> "$httpMethod ${pathPattern.patternString}" }
                }
            }
            .toSet()

        assertThat(registeredRoutes)
            .describedAs("routes registered by %s", controllerSimpleName)
            .isEqualTo(expectedRoutes)
    }

    private fun allRegisteredRoutes(): Set<String> = requestMappingHandlerMapping.handlerMethods
        .entries
        .flatMap { entry ->
            val httpMethods: List<String> =
                entry.key.methodsCondition.methods.map { requestMethod -> requestMethod.name }
            entry.key.patternsCondition.patterns.flatMap { pathPattern ->
                httpMethods.map { httpMethod -> "$httpMethod ${pathPattern.patternString}" }
            }
        }
        .toSet()

    private companion object {
        const val WORKSPACE = "/v2/workspaces/{workspaceId}"
        const val PAGES = "$WORKSPACE/pages"
        const val DASHBOARDS = "$WORKSPACE/dashboards"
        const val EXPLORE = "$WORKSPACE/explore"

        val CONNECTOR_READ_ROUTES = setOf(
            "GET $WORKSPACE/connectors",
            "GET $WORKSPACE/connectors/{connectorId}",
        )

        val CONNECTOR_MUTATION_ROUTES = setOf(
            "POST $WORKSPACE/connectors",
            "PUT $WORKSPACE/connectors/{connectorId}",
            "DELETE $WORKSPACE/connectors/{connectorId}",
            "PUT $WORKSPACE/connectors/{connectorId}/credentials",
        )

        val CATALOG_AUTHORITY_ROUTES = setOf(
            "PUT $WORKSPACE/catalog/connectors/{connectorId}/selection",
            "PUT $WORKSPACE/catalog/connectors/{connectorId}/catalog-selection",
            "PUT $WORKSPACE/catalog/connectors/{connectorId}/catalogs/{catalogName}/selection",
            "POST $WORKSPACE/catalog/connectors/{connectorId}/ingests",
            "POST $WORKSPACE/catalog/connectors/{connectorId}/ingests/{jobId}:cancel",
            "GET $WORKSPACE/catalog/connectors/{connectorId}/selection/list-refresh-jobs/{jobId}",
            "POST $WORKSPACE/catalog/connectors/{connectorId}/selection/list-refresh-jobs",
            "POST $WORKSPACE/catalog/connectors/{connectorId}/selection/list-refresh-jobs/{jobId}/cancel",
            "GET $WORKSPACE/catalog/connectors/{connectorId}/selection",
            "GET $WORKSPACE/catalog/connectors/{connectorId}/catalog-selection",
            "GET $WORKSPACE/catalog/connectors/{connectorId}/catalogs/{catalogName}/selection",
            "GET $WORKSPACE/catalog/connectors/{connectorId}/selection/options",
            "GET $WORKSPACE/catalog/connectors/{connectorId}/ingests",
            "GET $WORKSPACE/catalog/connectors/{connectorId}/ingests/active",
            "GET $WORKSPACE/catalog/connectors/{connectorId}/ingests/{jobId}",
            "GET $WORKSPACE/catalog/connectors/{connectorId}/ingests/{jobId}/errors",
        )

        val EXPLORE_RUN_ROUTES = setOf(
            "POST $WORKSPACE/explore/sql-runs",
            "POST $WORKSPACE/explore/semantic-runs",
            "GET $WORKSPACE/explore/runs",
            "GET $WORKSPACE/explore/runs/{runId}",
            "GET $WORKSPACE/explore/runs/{runId}/result",
            "GET $WORKSPACE/agent/explore/runs/{runId}/result",
            "POST $WORKSPACE/explore/runs/{runId}:cancel",
        )

        val DASHBOARD_ROUTES = setOf(
            "POST $DASHBOARDS",
            "GET $DASHBOARDS",
            "GET $DASHBOARDS/{dashboardId}",
            "DELETE $DASHBOARDS/{dashboardId}",
            "GET $DASHBOARDS/{dashboardId}/history",
            "GET $DASHBOARDS/{dashboardId}/history/{changeId}",
            "POST $DASHBOARDS/{dashboardId}/history/{changeId}/restore",
        )

        val DASHBOARD_SCHEDULE_ROUTES = setOf(
            "GET $DASHBOARDS/{dashboardId}/schedules",
            "GET $DASHBOARDS/{dashboardId}/draft/schedules",
            "GET $DASHBOARDS/{dashboardId}/occurrences",
            "GET $DASHBOARDS/{dashboardId}/occurrences/{scheduleKey}/{scheduledAt}",
        )

        val DASHBOARD_DRAFT_ROUTES = setOf(
            "POST $DASHBOARDS/{dashboardId}/draft",
            "GET $DASHBOARDS/{dashboardId}/draft",
            "PUT $DASHBOARDS/{dashboardId}/draft",
            "POST $DASHBOARDS/{dashboardId}/draft/parameters/{name}:set",
            "POST $DASHBOARDS/{dashboardId}/draft/variables:set",
            "POST $DASHBOARDS/{dashboardId}/draft:apply",
            "POST $DASHBOARDS/{dashboardId}/draft:discard",
            "POST $DASHBOARDS/{dashboardId}/draft:refresh",
            "GET $DASHBOARDS/{dashboardId}/draft/query-runs/{runId}",
            "GET $DASHBOARDS/{dashboardId}/draft/query-runs/{runId}/result",
            "POST $DASHBOARDS/{dashboardId}/draft/query-runs/{runId}:cancel",
            "GET $DASHBOARDS/{dashboardId}/draft/events",
        )

        val DASHBOARD_SESSION_ROUTES = setOf(
            "POST $DASHBOARDS/{dashboardId}/sessions",
            "GET $DASHBOARDS/{dashboardId}/sessions",
            "GET $WORKSPACE/dashboard-sessions/{sessionId}",
            "GET $WORKSPACE/dashboard-sessions/{sessionId}/query-runs",
            "POST $WORKSPACE/dashboard-sessions/{sessionId}:resolve",
            "GET $WORKSPACE/dashboard-sessions/{sessionId}/query-runs/{runId}",
            "POST $WORKSPACE/dashboard-sessions/{sessionId}:refresh",
            "GET $WORKSPACE/dashboard-sessions/{sessionId}/events",
            "GET $WORKSPACE/dashboard-sessions/{sessionId}/query-runs/{runId}/result",
        )

        val SEMANTIC_QUERY_ROUTES = setOf(
            "POST $WORKSPACE/semantic/query/group-bys",
            "POST $WORKSPACE/semantic/query/render-sql",
        )

        val RETIRED_ROUTES = setOf(
            "POST $WORKSPACE/execution-pools",
            "POST $WORKSPACE/execution-pools/{poolId}/revisions",
            "POST $WORKSPACE/execution-pools/{poolId}/revisions/{poolRevisionId}/workers",
            "POST $DASHBOARDS/{id}/runs",
            "POST $EXPLORE/render-sql",
            "POST $EXPLORE/available-group-bys",
            "POST $EXPLORE/inspect",
            "POST $EXPLORE/executions",
            "GET $EXPLORE/executions",
            "POST $EXPLORE/runs",
            "POST /v2/semantic/query/inspect",
            "POST /v2/semantic/query/render-sql",
            "POST /v2/semantic/query/available-group-bys",
            "POST /v2/semantic/health/subgraph",
            "GET /v2/connectors/{id}",
            "PATCH /v2/connectors/{id}",
            "DELETE /v2/connectors/{id}",
            "POST /v2/connectors/{id}/test",
            "POST $WORKSPACE/connectors/test",
            "PATCH $WORKSPACE/connectors/{connectorId}",
            "POST $WORKSPACE/connectors/{connectorId}/test",
            "POST $WORKSPACE/semantic/semantic-models/{semanticModelId}:delete",
            "POST $WORKSPACE/semantic/metrics/{metricId}:delete",
            "GET $WORKSPACE/semantic/publications/{candidateVersionId}",
            "POST $WORKSPACE/semantic/publications/{candidateVersionId}:approve",
            "POST $WORKSPACE/semantic/publications/{candidateVersionId}:reject",
        )

        val SEMANTIC_MODEL_ROUTES = setOf(
            "POST $WORKSPACE/semantic/semantic-models",
            "PUT $WORKSPACE/semantic/semantic-models/{semanticModelId}",
            "GET $WORKSPACE/semantic/semantic-models",
            "GET $WORKSPACE/semantic/semantic-models/{semanticModelId}",
            "DELETE $WORKSPACE/semantic/semantic-models/{semanticModelId}",
            "GET $WORKSPACE/semantic/semantic-models/{semanticModelId}/history",
            "POST $WORKSPACE/semantic/semantic-models/{semanticModelId}/history/{changeId}/restore",
        )

        val METRIC_ROUTES = setOf(
            "POST $WORKSPACE/semantic/metrics",
            "PUT $WORKSPACE/semantic/metrics/{metricId}",
            "GET $WORKSPACE/semantic/metrics",
            "GET $WORKSPACE/semantic/metrics/{metricId}",
            "DELETE $WORKSPACE/semantic/metrics/{metricId}",
            "GET $WORKSPACE/semantic/metrics/{metricId}/history",
            "POST $WORKSPACE/semantic/metrics/{metricId}/history/{changeId}/restore",
        )

        val PAGES_ROUTES = setOf(
            "POST $PAGES",
            "GET $PAGES/tree",
            "PATCH $PAGES/tree",
            "GET $PAGES/{pageId}",
            "PATCH $PAGES/{pageId}",
            "DELETE $PAGES/{pageId}",
        )

        val PAGES_EVENT_STREAM_ROUTES = setOf("GET $PAGES/tree/events")

        val PAGE_CONTENT_ROUTES = setOf(
            "POST $WORKSPACE/page-content-uploads",
            "POST $WORKSPACE/page-content-uploads/{uploadRef}/complete",
            "DELETE $WORKSPACE/page-content-uploads/{uploadRef}",
            "POST $WORKSPACE/pages/{pageId}/versions",
            "GET $WORKSPACE/pages/{pageId}/versions",
            "GET $WORKSPACE/pages/{pageId}/versions/{versionNo}",
        )

        val PAGE_CONTENT_VIEWER_ROUTES = setOf(
            "GET $PAGES/{pageId}/content",
            "GET $PAGES/{pageId}/content/attachment",
        )

    }
}
