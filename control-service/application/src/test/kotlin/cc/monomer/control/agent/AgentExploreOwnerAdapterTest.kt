@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.agent

import cc.monomer.agent.api.AgentExploreResultResourceBody
import cc.monomer.agent.api.AgentExploreRunResourceBody
import cc.monomer.agent.api.AgentInvokeRequest
import cc.monomer.agent.api.AgentListRequest
import cc.monomer.agent.api.AgentListingOutcome
import cc.monomer.agent.api.AgentReadRequest
import cc.monomer.agent.api.AgentRequestId
import cc.monomer.agent.api.AgentResourceOutcome
import cc.monomer.agent.api.AgentResourcePath
import cc.monomer.agent.api.AgentWorkspaceAdmissionAudience
import cc.monomer.agent.api.AgentWorkspaceAdmissionDecision
import cc.monomer.agent.api.AgentWorkspaceAdmissionPort
import cc.monomer.agent.api.AgentWorkspaceAdmissionRequest
import cc.monomer.agent.api.AgentWorkspaceCaller
import cc.monomer.agent.api.AgentWorkspaceCallerAuthenticationOutcome
import cc.monomer.agent.api.AgentWorkspaceRoleEvidence
import cc.monomer.agent.application.AgentWorkspaceCallerAuthenticator
import cc.monomer.agent.application.AgentWorkspaceContractDecoder
import cc.monomer.connector.api.ConnectorId
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionDenial
import cc.monomer.control.ControlAdmissionStage
import cc.monomer.control.explore.ExploreResultProjectionAccessOutcome
import cc.monomer.control.explore.ExploreRunAccessService
import cc.monomer.control.explore.ExploreRunCancelOutcome
import cc.monomer.control.explore.ExploreRunListOutcome
import cc.monomer.control.explore.ExploreRunSnapshotOutcome
import cc.monomer.control.explore.ExploreSemanticLaunchOutcome
import cc.monomer.control.explore.ExploreSemanticLaunchService
import cc.monomer.control.explore.ExploreSqlLaunchOutcome
import cc.monomer.control.explore.ExploreSqlLaunchService
import cc.monomer.control.publicapi.AgentWorkspaceJson
import cc.monomer.control.publicapi.PublicControlContractValidator
import cc.monomer.execution.api.ConnectorType
import cc.monomer.execution.api.ResultLogicalType
import cc.monomer.execution.api.ResultPreviewCell
import cc.monomer.explore.api.ExploreConnectorBinding
import cc.monomer.explore.api.ExploreDigest
import cc.monomer.explore.api.ExploreResultColumn
import cc.monomer.explore.api.ExploreResultMetadata
import cc.monomer.explore.api.ExploreResultProjection
import cc.monomer.explore.api.ExploreRunId
import cc.monomer.explore.api.ExploreRunFailureCode
import cc.monomer.explore.api.ExploreRunPage
import cc.monomer.explore.api.ExploreRunPageBoundary
import cc.monomer.explore.api.ExploreRunState
import cc.monomer.explore.api.ExploreRunView
import cc.monomer.identity.api.OrganizationId
import cc.monomer.identity.api.AuthorizationRevision
import cc.monomer.identity.api.CanonicalPrincipal
import cc.monomer.identity.api.CredentialKind
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.IdentityAdmissionDenial
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.PrincipalKind
import cc.monomer.identity.api.WorkspaceId
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class AgentExploreOwnerAdapterTest {
    private val workspaceId = WorkspaceId(Uuid.parse("01942b04-6e00-7000-8000-000000000001"))
    private val actor = CanonicalPrincipal(
        PrincipalId(Uuid.parse("01942b04-6e00-7000-8000-000000000002")),
        PrincipalKind.USER,
    )
    private val connectorId = ConnectorId(Uuid.parse("01942b04-6e00-7000-8000-000000000003"))
    private val now = Instant.parse("2026-09-04T00:00:00Z")
    private val credential = PresentedCredential("agent-token")
    private val runAccess = mockk<ExploreRunAccessService>()
    private val sqlLaunch = mockk<ExploreSqlLaunchService>()
    private val semanticLaunch = mockk<ExploreSemanticLaunchService>()
    private val adapter = AgentExploreOwnerAdapter(
        runAccess,
        sqlLaunch,
        semanticLaunch,
        Clock.fixed(now, ZoneOffset.UTC),
        ObjectMapper(),
        fixedAgentProvenanceNames(),
    )

    @Test
    fun `list converts first and next owner pages and exhausts the cursor`() = runTest {
        val newer = run("01942b04-6e00-7000-8000-000000000020", now, ExploreRunState.RUNNING)
        val older = run("01942b04-6e00-7000-8000-000000000010", now.minusSeconds(1), ExploreRunState.FAILED)
        coEvery { runAccess.listRuns(match { it.after == null }) } returns ExploreRunListOutcome.Observed(
            ExploreRunPage(listOf(newer), ExploreRunPageBoundary(newer.createdAt, newer.id)),
        )
        coEvery { runAccess.listRuns(match { it.after?.runId == newer.id }) } returns ExploreRunListOutcome.Observed(
            ExploreRunPage(listOf(older), null),
        )
        val firstRequest = AgentListRequest(path(RUN_COLLECTION), null, 1)
        val first = assertIs<AgentListingOutcome.Found>(
            adapter.list(caller(firstRequest), firstRequest),
        ).listing
        val secondRequest = AgentListRequest(path(RUN_COLLECTION), first.nextCursor, 1)
        val second = assertIs<AgentListingOutcome.Found>(
            adapter.list(caller(secondRequest), secondRequest),
        ).listing

        assertEquals(newer.id.toString(), first.items.single().path.value.substringAfterLast('/'))
        assertEquals(older.id.toString(), second.items.single().path.value.substringAfterLast('/'))
        assertEquals("RUNNING", assertIs<cc.monomer.agent.api.AgentResourceListingItem.ExploreRun>(first.items.single()).state.name)
        assertNull(second.nextCursor)
        assertEquals(setOf("SEMANTIC_EXPLORE_RUN", "SQL_EXPLORE_RUN"), first.availableOperations.mapNotNull { (it as? cc.monomer.agent.api.AgentAvailableOperation.Invoke)?.inputKind }.toSet())
    }

    @Test
    fun `list rejects malformed non canonical and wrong path cursors before owner access`() = runTest {
        val wrongPathPayload =
            """{"version":1,"path":"workspace/metrics","createdAt":"$now","runId":"01942b04-6e00-7000-8000-000000000020"}"""
        val wrongPath = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(wrongPathPayload.toByteArray(StandardCharsets.UTF_8))
        val invalidTimestampPayload =
            """{"version":1,"path":"$RUN_COLLECTION","createdAt":"not-an-instant","runId":"01942b04-6e00-7000-8000-000000000020"}"""
        val invalidTimestamp = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(invalidTimestampPayload.toByteArray(StandardCharsets.UTF_8))
        listOf("not-base64!", wrongPath, "$wrongPath=", invalidTimestamp).forEach { cursor ->
            val request = AgentListRequest(path(RUN_COLLECTION), cursor, 1)
            assertEquals(
                "CURSOR_INVALID",
                assertIs<AgentListingOutcome.Rejected>(adapter.list(caller(request), request)).problem.code,
            )
        }
        coVerify(exactly = 0) { runAccess.listRuns(any()) }
    }

    @Test
    fun `list admission denials preserve safe HTTP semantics by admission stage`() = runTest {
        val request = AgentListRequest(path(RUN_COLLECTION), null, 1)

        coEvery { runAccess.listRuns(any()) } returns ExploreRunListOutcome.AdmissionDenied(
            denied(ControlAdmissionStage.REQUEST, ControlAdmissionDenial.INVALID_EXECUTION_TARGET),
        )
        val invalid = assertIs<AgentListingOutcome.Rejected>(adapter.list(caller(request), request))
        assertEquals("EXPLORE_REQUEST_INVALID", invalid.problem.code)
        assertEquals(400, invalid.problem.status.value)

        coEvery { runAccess.listRuns(any()) } returns ExploreRunListOutcome.AdmissionDenied(
            denied(
                ControlAdmissionStage.IDENTITY, ControlAdmissionDenial.Identity(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE),
            ),
        )
        assertIs<AgentListingOutcome.NotFound>(adapter.list(caller(request), request))

        for (reason in listOf(IdentityAdmissionDenial.CREDENTIAL_EXPIRED, IdentityAdmissionDenial.PRINCIPAL_MISMATCH, IdentityAdmissionDenial.AGENT_BINDING_MISMATCH, IdentityAdmissionDenial.AGENT_SCOPE_INSUFFICIENT)) {
            coEvery { runAccess.listRuns(any()) } returns ExploreRunListOutcome.AdmissionDenied(
                denied(ControlAdmissionStage.IDENTITY, ControlAdmissionDenial.Identity(reason)))
            val unauthenticated = assertIs<AgentListingOutcome.Rejected>(adapter.list(caller(request), request))
            assertEquals("AUTHENTICATION_REQUIRED", unauthenticated.problem.code)
            assertEquals(401, unauthenticated.problem.status.value)
        }

        coEvery { runAccess.listRuns(any()) } returns ExploreRunListOutcome.AdmissionDenied(
            denied(
                ControlAdmissionStage.IDENTITY,
                ControlAdmissionDenial.Identity(IdentityAdmissionDenial.PERMISSION_UNAVAILABLE),
            ),
        )
        assertIs<AgentListingOutcome.Forbidden>(adapter.list(caller(request), request))
    }

    @Test
    fun `read result and run cancel admission denials use the same mapping`() = runTest {
        val run = run("01942b04-6e00-7000-8000-000000000020", now, ExploreRunState.RUNNING)
        val runRequest = AgentReadRequest(path("$RUN_COLLECTION/${run.id}"), null)
        coEvery { runAccess.readSnapshot(any()) } returns ExploreRunSnapshotOutcome.AdmissionDenied(
            denied(
                ControlAdmissionStage.IDENTITY,
                ControlAdmissionDenial.Identity(IdentityAdmissionDenial.CREDENTIAL_REVOKED),
            ),
        )
        val read = assertIs<AgentResourceOutcome.Rejected>(adapter.read(caller(runRequest), runRequest))
        assertEquals("AUTHENTICATION_REQUIRED", read.problem.code)
        assertEquals(401, read.problem.status.value)

        val resultRequest = AgentReadRequest(path("$RUN_COLLECTION/${run.id}/result"), null)
        coEvery { runAccess.readResult(any()) } returns ExploreResultProjectionAccessOutcome.AdmissionDenied(
            denied(
                ControlAdmissionStage.IDENTITY, ControlAdmissionDenial.Identity(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE),
            ),
        )
        assertIs<AgentResourceOutcome.NotFound>(adapter.read(caller(resultRequest), resultRequest))

        val cancelRequest = AgentWorkspaceContractDecoder().invoke(
            """{"path":"$RUN_COLLECTION/${run.id}","action":"cancel"}""",
        ).value
        coEvery { runAccess.cancel(any()) } returns ExploreRunCancelOutcome.AdmissionDenied(
            denied(
                ControlAdmissionStage.REQUEST,
                ControlAdmissionDenial.INVALID_EXECUTION_TARGET,
            ),
        )
        val cancel = assertIs<cc.monomer.agent.api.AgentActionOutcome.Rejected>(
            adapter.invoke(caller(cancelRequest), cancelRequest),
        )
        assertEquals("EXPLORE_REQUEST_INVALID", cancel.problem.code)
        assertEquals(400, cancel.problem.status.value)
    }

    private fun denied(
        stage: ControlAdmissionStage,
        reason: ControlAdmissionDenial,
    ) = ControlAdmissionDecision.Denied(stage, reason)

    @Test
    fun `exact run read advertises only truthful result or cancel operations`() = runTest {
        val running = run("01942b04-6e00-7000-8000-000000000020", now, ExploreRunState.RUNNING)
        val succeeded = run(
            "01942b04-6e00-7000-8000-000000000021",
            now.minusSeconds(1),
            ExploreRunState.SUCCEEDED,
            resultExpiresAt = now.plusSeconds(60),
        )
        val expired = run(
            "01942b04-6e00-7000-8000-000000000022",
            now.minusSeconds(2),
            ExploreRunState.SUCCEEDED,
            resultExpiresAt = now,
        )
        for (run in listOf(running, succeeded, expired)) {
            coEvery { runAccess.readSnapshot(match { it.runId == run.id }) } returns
                ExploreRunSnapshotOutcome.Observed(run, "SELECT '당시 입력'")
        }

        val runningResource = readRun(running)
        val succeededResource = readRun(succeeded)
        val expiredResource = readRun(expired)

        assertIs<AgentExploreRunResourceBody>(runningResource.body)
        assertEquals(listOf("cancel"), runningResource.availableOperations.mapNotNull { (it as? cc.monomer.agent.api.AgentAvailableOperation.Invoke)?.action })
        assertEquals(listOf("${RUN_COLLECTION}/${succeeded.id}/result"), succeededResource.availableOperations.map { it.path.value })
        assertEquals(emptyList(), expiredResource.availableOperations)

        val failed = run(
            "01942b04-6e00-7000-8000-000000000023",
            now.minusSeconds(3),
            ExploreRunState.FAILED,
        )
        coEvery { runAccess.readSnapshot(match { it.runId == failed.id }) } returns
            ExploreRunSnapshotOutcome.Observed(failed, "SELECT 1 / 0")
        val failedBody = assertIs<AgentExploreRunResourceBody>(readRun(failed).body)
        assertEquals("EXECUTION_FAILED", failedBody.failureCode)
        assertEquals("BigQuery: Cannot query table 'qa.orders' without a filter over column 'order_date' for partition elimination", failedBody.failureDetail)
        coEvery { runAccess.readSnapshot(match { it.runId == failed.id }) } returns
            ExploreRunSnapshotOutcome.AdmissionDenied(
                denied(
                    ControlAdmissionStage.IDENTITY, ControlAdmissionDenial.Identity(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE),
                ),
            )
        val deniedRequest = AgentReadRequest(path("$RUN_COLLECTION/${failed.id}"), null)
        assertIs<AgentResourceOutcome.NotFound>(adapter.read(caller(deniedRequest), deniedRequest))
    }

    @Test
    fun `exact run read carries the surface and agent app the Web shows`() = runTest {
        val claude = cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata")
        val rows = listOf(
            cc.monomer.identity.api.CredentialSurface.WEB to null,
            cc.monomer.identity.api.CredentialSurface.AGENT to claude,
            cc.monomer.identity.api.CredentialSurface.AGENT to null,
            cc.monomer.identity.api.CredentialSurface.AGENT to cc.monomer.identity.api.AgentClientId("https://retired.example/client"),
            null to null,
        )
        for ((surface, client) in rows) {
            val recorded = run("01942b04-6e00-7000-8000-000000000020", now, ExploreRunState.RUNNING)
                .copy(credentialSurface = surface, agentClientId = client)
            coEvery { runAccess.readSnapshot(match { it.runId == recorded.id }) } returns ExploreRunSnapshotOutcome.Observed(recorded, "SELECT 1")
            val body = assertIs<AgentExploreRunResourceBody>(readRun(recorded).body)
            assertEquals(surface, body.credentialSurface, "$surface $client")
            assertEquals(
                if (client == claude) cc.monomer.agent.api.AgentApp(claude.value, "Claude", "claude.ai") else null,
                body.agentApp,
                "$surface $client",
            )
        }
    }

    @Test
    fun `Semantic result read carries the run's Reporting time zone`() = runTest {
        val sqlRun = run("01942b04-6e00-7000-8000-000000000027", now.minusSeconds(1), ExploreRunState.SUCCEEDED, resultExpiresAt = now.plusSeconds(60))
        val run = sqlRun.copy(kind = ExploreRunView.Kind.Semantic(
            cc.monomer.semantic.api.SemanticQuerySpec(listOf(cc.monomer.semantic.api.SemanticElementName("revenue")), emptyList(), null, emptyList(), emptyList(), 10),
            java.time.ZoneId.of("Asia/Seoul"),
        ))
        coEvery { runAccess.readResult(match { it.runId == run.id }) } returns ExploreResultProjectionAccessOutcome.Observed(
            ExploreResultProjection(
                run = run,
                columns = listOf(ExploreResultColumn("metric_time__hour", ResultLogicalType.DATETIME, display = null)),
                rows = listOf(listOf(ResultPreviewCell.LocalDateTimeValue("2026-03-01T09:00:00.000000"))),
                totalRowCount = 1uL,
                returnedRowCount = 1,
                truncated = false,
                expiresAt = now.plusSeconds(60),
            ),
        )
        val resultPath = path("$RUN_COLLECTION/${run.id}/result")
        val body = assertIs<AgentExploreResultResourceBody>(assertIs<AgentResourceOutcome.Found>(
            adapter.read(caller(AgentReadRequest(resultPath, null)), AgentReadRequest(resultPath, null)),
        ).resource.body)

        assertEquals(java.time.ZoneId.of("Asia/Seoul"), body.reportingTimeZone)
    }

    @Test
    fun `result read preserves lossless large total row count and closes retention and ACL failures`() = runTest {
        val run = run(
            "01942b04-6e00-7000-8000-000000000021",
            now.minusSeconds(1),
            ExploreRunState.SUCCEEDED,
            resultExpiresAt = now.plusSeconds(60),
        )
        val projection = ExploreResultProjection(
            run = run,
            columns = listOf(ExploreResultColumn("revenue", ResultLogicalType.DECIMAL, display = null), ExploreResultColumn("local_time", ResultLogicalType.DATETIME, display = null)),
            rows = listOf(
                listOf(ResultPreviewCell.Number("12.50"), ResultPreviewCell.LocalDateTimeValue("1969-12-31T23:59:59.999999")),
                listOf(ResultPreviewCell.Number("13.75"), ResultPreviewCell.Null),
            ),
            totalRowCount = 9007199254740993uL,
            returnedRowCount = 2,
            truncated = true,
            expiresAt = now.plusSeconds(60),
        )
        coEvery { runAccess.readResult(match { it.runId == run.id }) } returns
            ExploreResultProjectionAccessOutcome.Observed(projection)
        val resultPath = path("$RUN_COLLECTION/${run.id}/result")
        val visible = assertIs<AgentResourceOutcome.Found>(
            adapter.read(caller(AgentReadRequest(resultPath, null)), AgentReadRequest(resultPath, null)),
        ).resource
        val body = assertIs<AgentExploreResultResourceBody>(visible.body)

        assertEquals("9007199254740993", body.totalRowCount)
        assertEquals(2, body.returnedRowCount)
        assertEquals(true, body.truncated)
        assertEquals("12.50", body.rows.first().first().toString().trim('"'))

        assertEquals("DATETIME", body.columns[1].logicalType)
        assertEquals(kotlinx.serialization.json.JsonPrimitive("1969-12-31T23:59:59.999999"), body.rows[0][1])
        assertEquals(kotlinx.serialization.json.JsonNull, body.rows[1][1])
        assertEquals(null, body.download)
        assertEquals(null, body.reportingTimeZone)
        coVerify(exactly = 0) { runAccess.readDownloadLink(any()) }
        val downloadRequest = AgentReadRequest(resultPath, cc.monomer.agent.api.AgentResultDownloadFormat.ARROW)
        val link = cc.monomer.explore.api.ExploreResultDownloadLink(
            url = "https://objects.example.test/result.arrow?signature=fixture",
            fileName = "explore-${run.id}.arrow",
            mediaType = "application/vnd.apache.arrow.file",
            byteSize = ULong.MAX_VALUE,
            expiresAt = now.plusSeconds(30),
        )
        coEvery { runAccess.readDownloadLink(match {
            it.runId == run.id && it.workspaceId == workspaceId && it.expectedOAuthResource == cc.monomer.identity.api.ExpectedOAuthResource.AGENT_REST
        }) } returns cc.monomer.control.explore.ExploreRunDownloadLinkReadOutcome.Visible(link)
        val downloadBody = assertIs<AgentExploreResultResourceBody>(assertIs<AgentResourceOutcome.Found>(
            adapter.read(caller(downloadRequest), downloadRequest),
        ).resource.body)
        assertEquals("18446744073709551615", downloadBody.download?.byteSize)
        assertEquals(link.url, downloadBody.download?.url)
        assertEquals(body.columns, downloadBody.columns)
        assertEquals(body.rows, downloadBody.rows)
        coEvery { runAccess.readDownloadLink(any()) } returns cc.monomer.control.explore.ExploreRunDownloadLinkReadOutcome.AccessDenied
        assertIs<AgentResourceOutcome.Forbidden>(adapter.read(caller(downloadRequest), downloadRequest))
        coEvery { runAccess.readDownloadLink(any()) } returns cc.monomer.control.explore.ExploreRunDownloadLinkReadOutcome.RetentionExpired
        assertIs<AgentResourceOutcome.NotFound>(adapter.read(caller(downloadRequest), downloadRequest))
        coEvery { runAccess.readDownloadLink(any()) } returns cc.monomer.control.explore.ExploreRunDownloadLinkReadOutcome.AdmissionDenied(
            denied(ControlAdmissionStage.IDENTITY, ControlAdmissionDenial.Identity(IdentityAdmissionDenial.CREDENTIAL_REVOKED)),
        )
        assertEquals("AUTHENTICATION_REQUIRED", assertIs<AgentResourceOutcome.Rejected>(adapter.read(caller(downloadRequest), downloadRequest)).problem.code)
        coEvery { runAccess.readDownloadLink(any()) } returns cc.monomer.control.explore.ExploreRunDownloadLinkReadOutcome.ArtifactUnavailable
        assertEquals("EXPLORE_RESULT_UNAVAILABLE", assertIs<AgentResourceOutcome.Rejected>(adapter.read(caller(downloadRequest), downloadRequest)).problem.code)

        coEvery { runAccess.readResult(match { it.runId == run.id }) } returns
            ExploreResultProjectionAccessOutcome.RetentionExpired
        assertIs<AgentResourceOutcome.NotFound>(
            adapter.read(caller(AgentReadRequest(resultPath, null)), AgentReadRequest(resultPath, null)),
        )
        coEvery { runAccess.readResult(match { it.runId == run.id }) } returns
            ExploreResultProjectionAccessOutcome.AccessDenied
        assertIs<AgentResourceOutcome.Forbidden>(
            adapter.read(caller(AgentReadRequest(resultPath, null)), AgentReadRequest(resultPath, null)),
        )
    }

    @Test
    fun `cancel delegates to current owner access service and preserves accepted follow up path`() = runTest {
        val run = run("01942b04-6e00-7000-8000-000000000020", now, ExploreRunState.RUNNING)
        coEvery { runAccess.cancel(match { it.runId == run.id && it.reasonCode == "AGENT_REQUESTED" }) } returns
            ExploreRunCancelOutcome.Accepted(run)
        val request = AgentWorkspaceContractDecoder().invoke(
            """{"path":"$RUN_COLLECTION/${run.id}","action":"cancel"}""",
        ).value

        val action = assertIs<cc.monomer.agent.api.AgentActionOutcome.Completed>(
            adapter.invoke(caller(request), request),
        ).action

        assertEquals("ACCEPTED", action.status.name)
        assertEquals("$RUN_COLLECTION/${run.id}", action.result.path.value)
    }

    @Test
    fun `SQL and Semantic run invokes delegate to the current launch owners`() = runTest {
        val started = run("01942b04-6e00-7000-8000-000000000020", now, ExploreRunState.RUNNING)
        coEvery { sqlLaunch.start(match { it.connectorId == connectorId && it.sql == "SELECT 1" }) } returns
            ExploreSqlLaunchOutcome.Started(started, "SELECT 1")
        coEvery {
            semanticLaunch.start(
                match {
                    it.connectorId == connectorId &&
                        it.query.metrics.single().value == "revenue"
                },
            )
        } returns ExploreSemanticLaunchOutcome.Existing(started)
        val sqlRequest = AgentWorkspaceContractDecoder().invoke(
            """{"path":"$RUN_COLLECTION","action":"run","input":{"kind":"SQL_EXPLORE_RUN","connectorId":"$connectorId","sql":"SELECT 1"}}""",
        ).value
        val semanticRequest = AgentWorkspaceContractDecoder().invoke(
            """
            {
              "path":"$RUN_COLLECTION",
              "action":"run",
              "input":{
                "kind":"SEMANTIC_EXPLORE_RUN",
                "connectorId":"$connectorId",
                "query":{
                  "schemaVersion":4,
                  "metrics":["revenue"],
                  "groupBys":[],
                  "metricTimeRange":null,
                  "filters":[],
                  "orderBy":[],
                  "rowLimit":100
                }
              }
            }
            """.trimIndent(),
        ).value

        val sqlAction = assertIs<cc.monomer.agent.api.AgentActionOutcome.Completed>(
            adapter.invoke(caller(sqlRequest), sqlRequest),
        ).action
        val semanticAction = assertIs<cc.monomer.agent.api.AgentActionOutcome.Completed>(
            adapter.invoke(caller(semanticRequest), semanticRequest),
        ).action

        assertEquals("ACCEPTED", sqlAction.status.name)
        assertEquals("COMPLETED", semanticAction.status.name)
        assertEquals("$RUN_COLLECTION/${started.id}", semanticAction.result.path.value)
    }

    @Test
    fun `SQL and Semantic runs on a missing or deleted connector name the connector`() = runTest {
        coEvery { sqlLaunch.start(any()) } returns ExploreSqlLaunchOutcome.ConnectorNotFound
        coEvery { semanticLaunch.start(any()) } returns ExploreSemanticLaunchOutcome.ConnectorNotFound
        val sqlRequest = AgentWorkspaceContractDecoder().invoke(
            """{"path":"$RUN_COLLECTION","action":"run","input":{"kind":"SQL_EXPLORE_RUN","connectorId":"$connectorId","sql":"SELECT 1"}}""",
        ).value
        val semanticRequest = AgentWorkspaceContractDecoder().invoke(
            """{"path":"$RUN_COLLECTION","action":"run","input":{"kind":"SEMANTIC_EXPLORE_RUN","connectorId":"$connectorId","query":{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":100}}}""",
        ).value
        for (request in listOf(sqlRequest, semanticRequest)) {
            val problem = assertIs<cc.monomer.agent.api.AgentActionOutcome.Rejected>(adapter.invoke(caller(request), request)).problem
            assertEquals("CONNECTOR_NOT_FOUND", problem.code)
            assertEquals(404, problem.status.value)
            assertEquals(false, problem.retryable)
            assertEquals("The connector was not found. It may have been deleted; choose another connector.", problem.detail)
        }
        coEvery { semanticLaunch.start(any()) } returns ExploreSemanticLaunchOutcome.NotFound
        assertEquals(cc.monomer.agent.api.AgentActionOutcome.NotFound, adapter.invoke(caller(semanticRequest), semanticRequest))
    }

    @Test
    fun `Semantic run names a missing top-level Metric in a 404 violation`() = runTest {
        coEvery { semanticLaunch.start(any()) } returns ExploreSemanticLaunchOutcome.MetricNotFound(cc.monomer.semantic.api.SemanticElementName("revenue"))
        val request = AgentWorkspaceContractDecoder().invoke(
            """{"path":"$RUN_COLLECTION","action":"run","input":{"kind":"SEMANTIC_EXPLORE_RUN","connectorId":"$connectorId","query":{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":100}}}""",
        ).value
        val problem = assertIs<cc.monomer.agent.api.AgentActionOutcome.Rejected>(adapter.invoke(caller(request), request)).problem
        val message = "Metric `revenue` was not found. It may have been deleted."
        assertEquals(404, problem.status.value)
        assertEquals("NOT_FOUND", problem.code)
        assertEquals(false, problem.retryable)
        assertEquals(message, problem.detail)
        assertEquals(listOf(Triple("workspace/metrics/revenue", "METRIC_NOT_FOUND", message)),
            problem.violations.map { Triple(it.field, it.code, it.message) })
        coVerify(exactly = 1) { semanticLaunch.start(any()) }
        coVerify(exactly = 0) { sqlLaunch.start(any()) }
    }

    @Test
    fun `Semantic run rejection names each broken saved definition by its resource path`() = runTest {
        val modelIssue = cc.monomer.semantic.api.SemanticDefinitionIssue(
            cc.monomer.semantic.api.SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID,
            cc.monomer.semantic.api.SemanticDefinitionReference.SemanticModel(cc.monomer.semantic.api.SemanticElementName("orders")),
            "In semantic model `orders`, type conflict for dimension `status`.",
        )
        val unattributed = cc.monomer.semantic.api.SemanticDefinitionIssue(
            cc.monomer.semantic.api.SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID,
            null,
            "Metric inputs form a cycle or are more than 100 levels deep.",
        )
        coEvery { semanticLaunch.start(any()) } returns ExploreSemanticLaunchOutcome.Invalid(
            listOf(cc.monomer.semantic.api.SemanticInvalidReason(cc.monomer.semantic.api.SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID, null)),
            listOf(modelIssue, unattributed),
        )
        val request = AgentWorkspaceContractDecoder().invoke(
            """{"path":"$RUN_COLLECTION","action":"run","input":{"kind":"SEMANTIC_EXPLORE_RUN","connectorId":"$connectorId","query":{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":100}}}""",
        ).value

        val problem = assertIs<cc.monomer.agent.api.AgentActionOutcome.Rejected>(adapter.invoke(caller(request), request)).problem

        assertEquals("INVALID_SEMANTIC_QUERY", problem.code)
        assertEquals("METRICFLOW_MANIFEST_INVALID", problem.detail)
        assertEquals(
            listOf(
                Triple("input.query", "METRICFLOW_MANIFEST_INVALID", "The Semantic query cannot execute with the current definitions and selected Connector."),
                Triple("workspace/semantic-models/orders", "METRICFLOW_MANIFEST_INVALID", modelIssue.message),
                Triple("input.query", "METRICFLOW_MANIFEST_INVALID", unattributed.message),
            ),
            problem.violations.map { Triple(it.field, it.code, it.message) },
        )

        coEvery { semanticLaunch.start(any()) } returns ExploreSemanticLaunchOutcome.Invalid(
            listOf(cc.monomer.semantic.api.SemanticInvalidReason(cc.monomer.semantic.api.SemanticInvalidReasonCode.METRICFLOW_MANIFEST_INVALID, null)),
            List(120) { modelIssue },
        )
        val capped = assertIs<cc.monomer.agent.api.AgentActionOutcome.Rejected>(adapter.invoke(caller(request), request)).problem
        assertEquals(100, capped.violations.size)
    }

    @Test
    fun `Catalog comparison type mismatch names the filter kind as an Agent input error`() = runTest {
        coEvery { semanticLaunch.start(any()) } returns ExploreSemanticLaunchOutcome.Invalid(
            listOf(cc.monomer.semantic.api.SemanticInvalidReason(
                cc.monomer.semantic.api.SemanticInvalidReasonCode.QUERY_FILTER_UNRENDERABLE, "filters[0].kind",
            )),
            emptyList(),
        )
        val request = AgentWorkspaceContractDecoder().invoke(
            """{"path":"$RUN_COLLECTION","action":"run","input":{"kind":"SEMANTIC_EXPLORE_RUN","connectorId":"$connectorId","query":{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,"filters":[{"kind":"TEXT","dimension":{"semanticModelName":"orders","name":"amount"},"operator":"EQUALS","values":["1"]}],"orderBy":[],"rowLimit":100}}}""",
        ).value

        val problem = assertIs<cc.monomer.agent.api.AgentActionOutcome.Rejected>(adapter.invoke(caller(request), request)).problem

        assertEquals(400, problem.status.value)
        assertEquals("INVALID_SEMANTIC_QUERY", problem.code)
        assertEquals(false, problem.retryable)
        assertEquals(listOf(Triple(
            "input.query.filters[0].kind", "QUERY_FILTER_UNRENDERABLE",
            "Filter comparison type must match the column's Catalog type; time dimensions use DATE at day or coarser grain and TIMESTAMP at finer grain.",
        )), problem.violations.map { Triple(it.field, it.code, it.message) })
        coVerify(exactly = 1) { semanticLaunch.start(any()) }
        coVerify(exactly = 0) { sqlLaunch.start(any()) }
    }

    @Test
    fun `unsupported Semantic query retains capability repair facts as input rejection`() = runTest {
        coEvery { semanticLaunch.start(any()) } returns ExploreSemanticLaunchOutcome.Unsupported(
            setOf(cc.monomer.semantic.api.SemanticUnsupportedCapability.TIME_SPINE_UNAVAILABLE))
        val request = AgentWorkspaceContractDecoder().invoke(
            """{"path":"$RUN_COLLECTION","action":"run","input":{"kind":"SEMANTIC_EXPLORE_RUN","connectorId":"$connectorId","query":{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":100}}}""").value
        val problem = assertIs<cc.monomer.agent.api.AgentActionOutcome.Rejected>(adapter.invoke(caller(request), request)).problem
        assertEquals("UNSUPPORTED_SEMANTIC_QUERY", problem.code)
        assertEquals("TIME_SPINE_UNAVAILABLE", problem.detail)
        assertEquals(400, problem.status.value)
        assertEquals(false, problem.retryable)
    }

    @Test
    fun `accepted SQL warehouse failures expose their classified code and safe detail to Agent`() = runTest {
        listOf(
            ExploreRunFailureCode.PERMISSION_DENIED to "permission denied for schema private_data",
            ExploreRunFailureCode.CONNECTION_FAILED to "Connection to 127.0.0.1:15432 refused.",
            ExploreRunFailureCode.AUTHENTICATION_FAILED to "password authentication failed for user reader",
        ).forEach { (failureCode, failureDetail) ->
            val failed = run("01942b04-6e00-7000-8000-000000000023", now, ExploreRunState.FAILED).copy(
                failureCode = failureCode,
                failureDetail = failureDetail,
            )
            coEvery { runAccess.readSnapshot(match { it.runId == failed.id }) } returns
                ExploreRunSnapshotOutcome.Observed(failed, "SELECT * FROM private_data.orders")
            val resource = readRun(failed)
            val body = assertIs<AgentExploreRunResourceBody>(resource.body)
            assertEquals(failureCode.name, body.failureCode)
            assertEquals(failureDetail, body.failureDetail)
            PublicControlContractValidator().agentResource(AgentWorkspaceJson.agentResourceJson(AgentRequestId("srv_explore"), resource))
        }
    }

    @Test
    fun `SQL rejection and execution outages have distinct Agent retry policies`() = runTest {
        val request = AgentWorkspaceContractDecoder().invoke(
            """{"path":"$RUN_COLLECTION","action":"run","input":{"kind":"SQL_EXPLORE_RUN","connectorId":"$connectorId","sql":"SELECT 1"}}""",
        ).value
        listOf(
            ExploreSqlLaunchOutcome.SqlValidationFailed("RAW_SQL_VALIDATION_REJECTED", "SQL has no output columns."),
            ExploreSqlLaunchOutcome.SqlValidationFailed("PERMISSION_DENIED", "The Connector's warehouse account is not allowed to run this SQL."),
            ExploreSqlLaunchOutcome.SqlValidationFailed("READ_ONLY_VIOLATION", "The warehouse transaction is read-only."),
            ExploreSqlLaunchOutcome.ExecutionUnavailable("EXECUTION_UNAVAILABLE"),
            ExploreSqlLaunchOutcome.ExecutionUnavailable("EXECUTION_BACKPRESSURED"),
            ExploreSqlLaunchOutcome.ExecutionUnavailable("RESOURCE_EXHAUSTED"),
            ExploreSqlLaunchOutcome.ExecutionUnavailable("CREDENTIAL_UNAVAILABLE"),
            ExploreSqlLaunchOutcome.ExecutionUnavailable("CONNECTION_FAILED"),
            ExploreSqlLaunchOutcome.ExecutionUnavailable("AUTHENTICATION_FAILED"),
            ExploreSqlLaunchOutcome.ExecutionUnavailable("RAW_SQL_VALIDATION_DEADLINE_EXCEEDED"),
            ExploreSqlLaunchOutcome.ExecutionUnavailable("RAW_SQL_VALIDATION_FAILED"),
        ).forEach { outcome ->
            coEvery { sqlLaunch.start(any()) } returns outcome
            val problem = assertIs<cc.monomer.agent.api.AgentActionOutcome.Rejected>(adapter.invoke(caller(request), request)).problem
            val rejected = outcome as? ExploreSqlLaunchOutcome.SqlValidationFailed
            val unavailable = outcome as? ExploreSqlLaunchOutcome.ExecutionUnavailable
            assertEquals(if (unavailable == null) 409 else 503, problem.status.value)
            assertEquals(rejected?.code ?: if (unavailable?.failureCode == "EXECUTION_UNAVAILABLE") "OWNER_UNAVAILABLE" else unavailable?.failureCode, problem.code)
            assertEquals(unavailable?.retryable ?: false, problem.retryable)
            assertEquals(unavailable?.message ?: rejected?.message, problem.detail)
        }
    }

    @Test
    fun `unconfirmed SQL and Semantic starts require readback while owner arguments propagate`() = runTest {
        val decoder = AgentWorkspaceContractDecoder()
        val sql = decoder.invoke("""{"path":"$RUN_COLLECTION","action":"run","input":{"kind":"SQL_EXPLORE_RUN","connectorId":"$connectorId","sql":"SELECT 1"}}""").value
        val semantic = decoder.invoke("""{"path":"$RUN_COLLECTION","action":"run","input":{"kind":"SEMANTIC_EXPLORE_RUN","connectorId":"$connectorId","query":{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":100}}}""").value
        coEvery { sqlLaunch.start(any()) } throws IllegalStateException("private diagnostic")
        coEvery { semanticLaunch.start(any()) } throws IllegalStateException("private diagnostic")
        for (request in listOf(sql, semantic)) {
            val problem = assertIs<cc.monomer.agent.api.AgentActionOutcome.Rejected>(adapter.invoke(caller(request), request)).problem
            assertEquals(AgentOwnerProblems.exploreStartUnconfirmed, problem)
            assertEquals(false, problem.retryable)
        }
        coEvery { sqlLaunch.start(any()) } throws IllegalArgumentException("private diagnostic")
        coEvery { semanticLaunch.start(any()) } throws IllegalArgumentException("private diagnostic")
        for (request in listOf(sql, semantic)) {
            kotlin.test.assertFailsWith<IllegalArgumentException> { adapter.invoke(caller(request), request) }
        }
    }

    @Test
    fun `MetricFlow invalid and unsupported inputs use existing Agent 400 codes while engine faults propagate`() = runTest {
        val request = AgentWorkspaceContractDecoder().invoke("""{"path":"$RUN_COLLECTION","action":"run","input":{"kind":"SEMANTIC_EXPLORE_RUN","connectorId":"$connectorId","query":{"schemaVersion":4,"metrics":["revenue"],"groupBys":[],"metricTimeRange":null,"filters":[],"orderBy":[],"rowLimit":100}}}""").value
        val failures = listOf(
            cc.monomer.semantic.application.SemanticMetricFlowInvalidQueryException(
                cc.monomer.semantic.api.SemanticInvalidReasonCode.QUERY_FILTER_UNRENDERABLE, IllegalStateException("private diagnostic"),
            ) to "SEMANTIC_QUERY_INVALID",
            cc.monomer.semantic.application.SemanticMetricFlowUnsupportedException(IllegalStateException("private diagnostic")) to "SEMANTIC_QUERY_UNSUPPORTED",
        )
        for ((failure, code) in failures) {
            coEvery { semanticLaunch.start(any()) } throws failure
            val problem = assertIs<cc.monomer.agent.api.AgentActionOutcome.Rejected>(adapter.invoke(caller(request), request)).problem
            assertEquals(code, problem.code)
            assertEquals(400, problem.status.value)
            assertEquals(false, problem.retryable)
            assertNull(problem.retryAfterSeconds)
            kotlin.test.assertFalse(problem.toString().contains("private diagnostic"))
        }
        val fault = cc.monomer.semantic.application.SemanticMetricFlowEngineFaultException(IllegalStateException("private diagnostic"))
        coEvery { semanticLaunch.start(any()) } throws fault
        kotlin.test.assertSame(fault, kotlin.test.assertFailsWith<cc.monomer.semantic.application.SemanticMetricFlowEngineFaultException> {
            adapter.invoke(caller(request), request)
        })
    }

    @Test
    fun `Semantic detail round trips original rich query and committed SQL without rendering`() = runTest {
        val request = AgentWorkspaceContractDecoder().invoke(
            """
            {"path":"$RUN_COLLECTION","action":"run","input":{
              "kind":"SEMANTIC_EXPLORE_RUN","connectorId":"$connectorId","query":{
                "schemaVersion":4,"metrics":["revenue"],
                "groupBys":[
                  {"kind":"DIMENSION","semanticModelName":"orders","name":"ordered_at","granularity":"SECOND"},
                  {"kind":"ENTITY","semanticModelName":"orders","name":"customer"},
                  {"kind":"METRIC_TIME","granularity":"HOUR"}
                ],
                "metricTimeRange":{"start":{"kind":"TIMESTAMP","value":"2026-01-01T00:00:00Z"},"end":{"kind":"TIMESTAMP","value":"2026-01-02T00:00:00Z"}},
                "filters":[
                  {"kind":"TEXT","dimension":{"semanticModelName":"orders","name":"region"},"operator":"IN","values":["대한민국","日本"]},
                  {"kind":"NUMBER","dimension":{"semanticModelName":"orders","name":"amount"},"operator":"GREATER_THAN","values":["123.45"]},
                  {"kind":"BOOLEAN","dimension":{"semanticModelName":"orders","name":"paid"},"operator":"EQUALS","values":[true]},
                  {"kind":"DATE","dimension":{"semanticModelName":"orders","name":"date"},"operator":"EQUALS","values":["2026-01-01"]},
                  {"kind":"TIMESTAMP","dimension":{"semanticModelName":"orders","name":"ordered_at"},"operator":"GREATER_THAN","values":["2026-01-01T00:00:00Z"]}
                ],
                "orderBy":[{"target":{"kind":"METRIC","metricName":"revenue"},"direction":"DESC"},
                  {"target":{"kind":"GROUP_BY","groupBy":{"kind":"ENTITY","semanticModelName":"orders","name":"customer"}},"direction":"ASC"}],
                "rowLimit":123
              }}}
            """.trimIndent(),
        ).value
        val original = (request.input as cc.monomer.agent.api.AgentInvokeInput.ExploreRun).request as cc.monomer.agent.api.AgentExploreRunRequest.Semantic
        val captured = io.mockk.slot<cc.monomer.control.explore.LaunchSemanticExploreRunCommand>()
        val base = run("01942b04-6e00-7000-8000-000000000024", now, ExploreRunState.FAILED)
        coEvery { semanticLaunch.start(capture(captured)) } returns ExploreSemanticLaunchOutcome.Existing(base)
        adapter.invoke(caller(request), request)
        val semantic = ExploreRunView(
            id = base.id,
            kind = ExploreRunView.Kind.Semantic(query = captured.captured.query, reportingTimeZone = null),
            workspaceId = base.workspaceId,
            connectorBinding = base.connectorBinding,
            requestDigest = base.requestDigest,
            state = base.state,
            revision = base.revision,
            resultAvailable = base.resultAvailable,
            failureCode = base.failureCode,
            failureDetail = base.failureDetail,
            createdAt = base.createdAt,
            updatedAt = base.updatedAt,
            resultMetadata = base.resultMetadata,
            ownerPrincipalId = base.ownerPrincipalId,
            credentialSurface = base.credentialSurface,
            agentClientId = base.agentClientId,
        )
        val sql = "SELECT '당시 리터럴 🐧' WHERE amount > 123.4500\n".repeat(20_000)
        coEvery { runAccess.readSnapshot(match { it.runId == semantic.id }) } returns ExploreRunSnapshotOutcome.Observed(semantic, sql)

        val body = assertIs<AgentExploreRunResourceBody.Semantic>(readRun(semantic).body)

        assertEquals(original.query, body.query)
        assertEquals(sql, body.sql)
        assertEquals("123.45", assertIs<cc.monomer.agent.api.AgentSemanticFilter.Number>(body.query.filters[1]).values.single().toPlainString())
        coVerify(exactly = 1) { semanticLaunch.start(any()) }
    }

    private suspend fun readRun(run: ExploreRunView): cc.monomer.agent.api.AgentResourceEnvelope {
        val request = AgentReadRequest(path("$RUN_COLLECTION/${run.id}"), null)
        return assertIs<AgentResourceOutcome.Found>(adapter.read(caller(request), request)).resource
    }

    private fun run(
        id: String,
        createdAt: Instant,
        state: ExploreRunState,
        resultExpiresAt: Instant? = null,
    ): ExploreRunView = ExploreRunView(
        id = ExploreRunId(Uuid.parse(id)),
        kind = ExploreRunView.Kind.Sql,
        workspaceId = workspaceId,
        connectorBinding = ExploreConnectorBinding(workspaceId, connectorId, ConnectorType.POSTGRESQL),
        requestDigest = ExploreDigest("sha256:${"1".repeat(64)}"),
        state = state,
        revision = 0,
        resultAvailable = resultExpiresAt != null,
        failureCode = if (state == ExploreRunState.FAILED) ExploreRunFailureCode.EXECUTION_FAILED else null,
        failureDetail = if (state == ExploreRunState.FAILED) "BigQuery: Cannot query table 'qa.orders' without a filter over column 'order_date' for partition elimination" else null,
        createdAt = createdAt,
        updatedAt = createdAt,
        resultMetadata = resultExpiresAt?.let {
            ExploreResultMetadata(1u, "application/vnd.apache.arrow.stream", 2u, 1, it)
        },
        ownerPrincipalId = cc.monomer.identity.api.PrincipalId(FixedPrincipalDisplayNames.MINA),
        credentialSurface = cc.monomer.identity.api.CredentialSurface.AGENT,
        agentClientId = cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"),
    )

    private suspend fun caller(request: AgentListRequest): AgentWorkspaceCaller = authenticated { authenticator, requestId ->
        authenticator.authenticate(workspaceId, credential, AgentWorkspaceAdmissionAudience.REST, request, requestId)
    }

    private suspend fun caller(request: AgentReadRequest): AgentWorkspaceCaller = authenticated { authenticator, requestId ->
        authenticator.authenticate(workspaceId, credential, AgentWorkspaceAdmissionAudience.REST, request, requestId)
    }

    private suspend fun caller(request: AgentInvokeRequest): AgentWorkspaceCaller = authenticated { authenticator, requestId ->
        authenticator.authenticate(workspaceId, credential, AgentWorkspaceAdmissionAudience.REST, request, requestId)
    }

    private suspend fun authenticated(
        authenticate: suspend (AgentWorkspaceCallerAuthenticator, AgentRequestId) -> AgentWorkspaceCallerAuthenticationOutcome,
    ): AgentWorkspaceCaller {
        val authenticator = AgentWorkspaceCallerAuthenticator(
            object : AgentWorkspaceAdmissionPort {
                override suspend fun decide(request: AgentWorkspaceAdmissionRequest): AgentWorkspaceAdmissionDecision =
                    AgentWorkspaceAdmissionDecision.Authorized(
                        AgentWorkspaceRoleEvidence(
                            organizationPlan = cc.monomer.identity.api.OrganizationPlan.FREE,
                            role = cc.monomer.identity.api.WorkspaceRole.EXPLORER,
                            actor = actor,
                            organizationId = OrganizationId(Uuid.parse("01942b04-6e00-7000-8000-000000000004")),
                            workspaceId = workspaceId,
                            authorizationRevision = AuthorizationRevision(1),
                            credentialKind = CredentialKind.AGENT_OAUTH,
                            credentialSurface = CredentialSurface.AGENT,
                            agentClientId = cc.monomer.identity.api.AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata"),
                            intent = request.intent,
                        ),
                    )
            },
        )
        return assertIs<AgentWorkspaceCallerAuthenticationOutcome.Authenticated>(
            authenticate(authenticator, AgentRequestId("srv_test")),
        ).caller
    }

    private fun path(value: String) = AgentResourcePath.parse(value)

    private companion object {
        const val RUN_COLLECTION = "workspace/explore/runs"
    }
}
