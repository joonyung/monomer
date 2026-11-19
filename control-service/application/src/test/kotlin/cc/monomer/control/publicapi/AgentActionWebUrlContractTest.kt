@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.publicapi

import cc.monomer.agent.api.AgentActionResult
import cc.monomer.agent.api.AgentActionResultReference
import cc.monomer.agent.api.AgentActionStatus
import cc.monomer.agent.api.AgentRequestId
import cc.monomer.agent.api.AgentResourceKind
import cc.monomer.agent.api.AgentResourcePath
import cc.monomer.identity.api.WorkspaceId
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AgentActionWebUrlContractTest {
    @Test
    fun `schema requires browser URLs only for dashboard create edit and apply`() {
        val dashboard = AgentResourcePath.parse("workspace/dashboards/01942b04-6e00-7000-8000-000000000010")
        val draft = AgentResourcePath.parse("${dashboard.value}/draft")
        val dashboardReference = AgentActionResultReference(AgentResourceKind.DASHBOARD, dashboard)
        val requestId = AgentRequestId("web-url-contract")
        val workspaceId = WorkspaceId.parse("01942b04-6e00-7000-8000-000000000001")
        val origin = URI("https://app.monomer.cc")
        val validator = PublicControlContractValidator()
        val actions = listOf(
            AgentActionResult.Create(AgentResourcePath.parse("workspace/dashboards"), dashboardReference),
            AgentActionResult.Edit(draft, AgentActionResultReference(AgentResourceKind.DASHBOARD_DRAFT, draft)),
            AgentActionResult.Invoke(draft, "apply", AgentActionStatus.COMPLETED, dashboardReference),
        )
        for (action in actions) {
            val body = AgentWorkspaceJson.agentActionJson(requestId, action, workspaceId, origin)
            assertEquals(body, validator.agentAction(body))
            assertFailsWith<IllegalStateException> { validator.agentAction(body - "webUrl") }
            assertFailsWith<IllegalStateException> { validator.agentAction(body + ("webUrl" to "/relative")) }
        }
        val connector = AgentResourcePath.parse("workspace/connectors/01942b04-6e00-7000-8000-000000000020")
        val unrelated = listOf(
            AgentActionResult.Create(AgentResourcePath.parse("workspace/connectors"), AgentActionResultReference(AgentResourceKind.CONNECTOR, connector)),
            AgentActionResult.Invoke(draft, "start", AgentActionStatus.ACCEPTED, AgentActionResultReference(AgentResourceKind.DASHBOARD_DRAFT, draft)),
        )
        for (action in unrelated) {
            val body = AgentWorkspaceJson.agentActionJson(requestId, action, workspaceId, origin)
            assertFalse(body.containsKey("webUrl"))
            assertEquals(body, validator.agentAction(body))
            assertFailsWith<IllegalStateException> { validator.agentAction(body + ("webUrl" to "https://app.monomer.cc")) }
        }
    }
}
