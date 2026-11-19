@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.agent

import cc.monomer.agent.api.*
import cc.monomer.agent.application.AgentWorkspaceCallerAuthenticator
import cc.monomer.agent.application.AgentWorkspaceContractDecoder
import cc.monomer.catalog.api.CatalogPhysicalAddress
import cc.monomer.connector.api.ConnectorId
import cc.monomer.control.EphemeralPostgresAuthoritySupport
import cc.monomer.identity.api.*
import cc.monomer.semantic.api.*
import cc.monomer.semantic.application.*
import cc.monomer.semantic.adapter.outbound.postgres.*
import cc.monomer.semantic.adapter.outbound.metricflow.MetricFlowSemanticRenderer
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.mockk
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.sql.Connection
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

class AgentSemanticGranularityIntegrationTest {
    private val workspace = WorkspaceId.generate()
    private val connector = ConnectorId.generate()
    private val decoder = AgentWorkspaceContractDecoder()
    private val actor = CanonicalPrincipal(PrincipalId.generate(), PrincipalKind.USER)

    @Test
    fun `Agent subday definitions survive owner persistence and execute PostgreSQL SQL`() = runBlocking {
        database.verifyEphemeralIdentity()
        database.dataSource.connection.use { connection -> connection.createStatement().use { statement ->
            statement.execute(checkNotNull(javaClass.classLoader.getResource("db/semantic/schema.sql")).readText())
            statement.execute("CREATE SCHEMA analytics")
            statement.execute("CREATE TABLE analytics.orders(order_id int, ordered_at timestamp, revenue numeric)")
            statement.execute("INSERT INTO analytics.orders VALUES (1,'2026-09-14 09:00:00',2),(2,'2026-09-14 09:30:00',3),(3,'2026-09-14 10:00:00',7)")
            statement.execute("CREATE TABLE analytics.minute_spine AS SELECT generate_series(timestamp '2026-09-14 09:00:00',timestamp '2026-09-14 10:00:00',interval '1 minute') AS ts")
        } }
        val manager = DataSourceTransactionManager(database.dataSource)
        fun transaction(isolation: Int) = TransactionTemplate(manager).apply { isolationLevel = isolation; timeout = 10 }
        val transactions = SpringSemanticTransactionPort(database.dataSource,
            transaction(Connection.TRANSACTION_REPEATABLE_READ), transaction(Connection.TRANSACTION_READ_COMMITTED),
            Dispatchers.IO, SemanticTransactionTimeouts(Duration.ofSeconds(10), Duration.ofSeconds(8), Duration.ofSeconds(5)))
        val repository = PostgresSemanticRepository(database.dataSource, ObjectMapper())
        val authority = SemanticAuthorityService(repository, transactions, Clock.systemUTC(), mockk(relaxed = true))
        val currentRead = SemanticCurrentReadService(repository, repository, transactions, mockk {
            io.mockk.coEvery { listByWorkspace(workspace) } returns emptyList()
            io.mockk.coEvery { findByWorkspaceAndIds(workspace, any()) } returns emptyList()
        },
            io.mockk.mockk(relaxed = true),
        )
        val admission = mockk<cc.monomer.control.ControlAdmissionFacade>()
        io.mockk.coEvery { admission.authorizeMutation(any()) } coAnswers {
            cc.monomer.control.ControlAdmissionDecision.Admitted(AuthorizedMutationContext(
                OrganizationPlan.FREE, actor, OrganizationId(Uuid.generateV7()), workspace, AuthorizationRevision(1),
                CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"), WorkspaceRole.EDITOR,
                firstArg<cc.monomer.control.MutationAdmissionRequest>().intent, java.time.Instant.now(),
            ))
        }
        val adapter = AgentSemanticOwnerAdapter(authority, currentRead, io.mockk.mockk<cc.monomer.semantic.api.SemanticDefinitionCheckUseCase>(), AgentSemanticEditAvailability { true }, admission, fixedAgentProvenanceNames())
        val model = """{"schemaVersion":3,"name":"orders","sourceRelations":[{"connectorId":"$connector","schema":"analytics","relation":"orders"}],
            "aggregationTimeDimension":"ordered_at","entities":[{"name":"order_id","type":"primary","expression":"order_id"}],
            "dimensions":[{"isPartition":false,"name":"ordered_at","type":"time","expression":"ordered_at","granularity":"minute"}],
            "measures":[{"name":"revenue","aggregation":"sum","expression":"revenue"}]}"""
        val definitions = listOf(
            """{"schemaVersion":3,"name":"base","type":"simple","semanticModelName":"orders","measure":"revenue","timeGranularity":"minute"}""",
            """{"schemaVersion":3,"name":"hourly","type":"simple","semanticModelName":"orders","measure":"revenue","timeGranularity":"hour"}""",
            """{"schemaVersion":3,"name":"filtered","type":"simple","semanticModelName":"orders","measure":"revenue","timeGranularity":"hour",
                "filters":[{"dimension":{"semanticModelName":"orders","name":"ordered_at","granularity":"hour"},"operator":"gte","values":["2026-09-14 10:00:00"]}]}""",
            """{"schemaVersion":3,"name":"offset_metric","type":"derived","expression":"offset_input","inputMetrics":[{"metricName":"base","alias":"offset_input","offsetToGrain":"hour"}]}""",
            """{"schemaVersion":3,"name":"cumulative_metric","type":"cumulative","inputMetric":{"metricName":"base"},"grainToDate":"hour","periodAggregation":"last"}""",
        )
        val aggregateMetrics = definitions.take(3).map { definition ->
            ObjectMapper().readTree(definition).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply {
                remove(listOf("schemaVersion", "type", "semanticModelName"))
            }
        }.joinToString(prefix = "[", postfix = "]")
        for ((collection, kind, definition) in listOf(Triple("semantic-models", "SEMANTIC_MODEL_DEFINITION", model)) +
            definitions.drop(3).map { Triple("metrics", "METRIC_DEFINITION", it) }) {
            val request = decoder.create("""{"path":"workspace/$collection","input":{"kind":"$kind",${if (kind == "SEMANTIC_MODEL_DEFINITION") "\"aggregateMetrics\":$aggregateMetrics," else ""}"definition":$definition}}""").value
            val caller = caller(request)
            val created = assertIs<AgentActionOutcome.Completed>(adapter.create(caller, request))
            val read = assertIs<AgentResourceOutcome.Found>(adapter.read(caller, AgentReadRequest(created.action.result.path, null)))
            when (val input = request.input) {
                is AgentCreateInput.SemanticModel -> assertEquals(input.definition, assertIs<AgentResourceEnvelope.SemanticModel>(read.resource).body.definition)
                is AgentCreateInput.Metric -> assertEquals(input.definition, assertIs<AgentResourceEnvelope.Metric>(read.resource).body.definition)
                else -> error("Unexpected input")
            }
        }
        val storedModel = assertNotNull(currentRead.findModelByName(workspace, SemanticElementName("orders")))
        val storedMetrics = definitions.map { ObjectMapper().readTree(it)["name"].asText() }.map { name ->
            assertNotNull(currentRead.findMetricByName(workspace, SemanticElementName(name)))
        }
        val manifest = SemanticMetricFlowManifest(connector, SemanticSqlDialect.POSTGRESQL,
            mapOf(storedModel.definition.name to SemanticMetricFlowModel(
                storedModel.definition, CatalogPhysicalAddress(null,"analytics","orders",null),
                mapOf(SemanticElementName("ordered_at") to cc.monomer.semantic.application.SemanticMetricFlowTimeDimension(
                    cc.monomer.semantic.api.SemanticTimeKind.LOCAL, null, cc.monomer.semantic.application.SemanticTimeOperand.Column("ordered_at", false, null),
                )),
            )),
            storedMetrics.associate { it.metricId to it.definition },
            listOf(SemanticMetricFlowTimeSpine(CatalogPhysicalAddress(null,"analytics","minute_spine",null),"ts",SemanticTimeGranularity.MINUTE)),
            java.time.ZoneId.of("UTC"))
        for (metric in storedMetrics) {
            val grain = if (metric.definition.name.value in setOf("hourly", "filtered")) SemanticTimeGranularity.HOUR else SemanticTimeGranularity.MINUTE
            val query = SemanticQuerySpec(listOf(metric.definition.name), listOf(SemanticGroupBy.MetricTime(grain)), null, emptyList(), emptyList(), 100)
            val rendered = assertIs<SemanticMetricFlowRenderResult.Rendered>(MetricFlowSemanticRenderer.production().render(manifest,query,listOf(metric.metricId)), metric.definition.name.value)
            assertTrue(rendered.sql.contains("DATE_TRUNC", ignoreCase = true), rendered.sql)
            val rows = database.dataSource.connection.use { connection -> connection.createStatement().use { statement ->
                statement.executeQuery(rendered.sql).use { result -> buildList {
                    while (result.next()) add(result.getTimestamp(1).toLocalDateTime() to result.getBigDecimal(2)?.intValueExact())
                } }
            } }
            val start = LocalDateTime.parse("2026-09-14T09:00:00")
            fun row(minute: Int, value: Int) = start.plusMinutes(minute.toLong()) to value
            val expected = when (metric.definition.name.value) {
                "base" -> listOf(row(0, 2), row(30, 3), row(60, 7))
                "hourly" -> listOf(row(0, 5), row(60, 7))
                "filtered" -> listOf(row(60, 7))
                "offset_metric" -> (0..60).map { row(it, if (it < 60) 2 else 7) }
                "cumulative_metric" -> (0..60).map { row(it, when { it < 30 -> 2; it < 60 -> 5; else -> 7 }) }
                else -> error("Unexpected metric")
            }
            assertEquals(expected, rows.sortedBy { it.first }, metric.definition.name.value)

        }
    }

    private suspend fun caller(request: AgentCreateRequest): AgentWorkspaceCaller {
        val authenticator = AgentWorkspaceCallerAuthenticator(object : AgentWorkspaceAdmissionPort {
            override suspend fun decide(request: AgentWorkspaceAdmissionRequest): AgentWorkspaceAdmissionDecision =
                AgentWorkspaceAdmissionDecision.Authorized(AgentWorkspaceRoleEvidence(
                    actor = actor, organizationId = OrganizationId(Uuid.generateV7()), organizationPlan = OrganizationPlan.FREE,
                    workspaceId = workspace, authorizationRevision = AuthorizationRevision(1),
                    credentialKind = CredentialKind.AGENT_OAUTH, credentialSurface = CredentialSurface.AGENT, agentClientId = cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"),
                    role = WorkspaceRole.EDITOR, intent = request.intent))
        })
        return assertIs<AgentWorkspaceCallerAuthenticationOutcome.Authenticated>(authenticator.authenticate(workspace,
            PresentedCredential("fixture-token"),AgentWorkspaceAdmissionAudience.REST,request,AgentRequestId(Uuid.generateV7().toString()))).caller
    }

    companion object { @JvmField @RegisterExtension val database = EphemeralPostgresAuthoritySupport }
}
