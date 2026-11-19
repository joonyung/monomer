package cc.monomer.agent.application

import cc.monomer.agent.api.*
import cc.monomer.identity.api.WorkspaceRole
import kotlin.test.*

class AgentSemanticHistoryContractTest {
    private val decoder = AgentWorkspaceContractDecoder()
    private val id = "01942b04-6e00-7000-8000-000000000010"

    @Test
    fun `delete and restore are exact inputless Editor actions for both semantic kinds`() {
        for (collection in listOf("semantic-models", "metrics")) {
            val resource = "workspace/$collection/orders"
            val version = "$resource/history/$id/$id"
            for ((path, action) in listOf(resource to "delete", version to "restore")) {
                val json = """{"path":"$path","action":"$action"}"""
                val request = decoder.invoke(json).value
                assertEquals(AgentInvokeInput.None, request.input)
                val intent = assertNotNull(AgentWorkspaceAdmissionIntents.invoke(request))
                assertEquals(WorkspaceRole.EDITOR, intent.requiredRole)
                assertTrue(AgentWorkspaceResponseRelations.matchesInvocation(request.path, action, null))
                for (invalid in listOf(
                    json.dropLast(1) + ",\"input\":null}",
                    json.dropLast(1) + ",\"input\":{}}",
                    json.dropLast(1) + ",\"revision\":\"1\"}",
                    json.replace(path, "workspace/$collection"),
                    json.replace(action, "unsupported"),
                )) assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.invoke(invalid) }
            }
            assertFailsWith<AgentWorkspaceRequestDecodingException> {
                decoder.invoke("""{"path":"$resource","action":"restore"}""")
            }
            assertFailsWith<AgentWorkspaceRequestDecodingException> {
                decoder.invoke("""{"path":"$version","action":"delete"}""")
            }
        }
    }

    @Test
    fun `history reads are Explorer operations and invalid identities do not admit`() {
        for (collection in listOf("semantic-models", "metrics")) {
            val history = AgentResourcePath.parse("workspace/$collection/orders/history/$id")
            val version = AgentResourcePath.parse("${history.value}/$id")
            assertEquals(WorkspaceRole.EXPLORER, assertNotNull(AgentWorkspaceAdmissionIntents.list(AgentListRequest(history, null, 20))).requiredRole)
            assertEquals(WorkspaceRole.EXPLORER, assertNotNull(AgentWorkspaceAdmissionIntents.read(AgentReadRequest(version, null))).requiredRole)
            assertNull(AgentWorkspaceAdmissionIntents.read(AgentReadRequest(history, null)))
            assertNull(AgentWorkspaceAdmissionIntents.list(AgentListRequest(version, null, 20)))
            val wrongVersion = version.value.replace("-7000-", "-4000-")
            assertFailsWith<AgentWorkspaceRequestDecodingException> {
                decoder.invoke("""{"path":"$wrongVersion","action":"restore"}""")
            }
        }
    }

    @Test
    fun `completed delete and restore results must stay in the matching collection and name`() {
        val resource = AgentResourcePath.parse("workspace/metrics/revenue")
        val delete = AgentInvokeRequest(resource, "delete", AgentInvokeInput.None)
        val removed = AgentActionResult.Invoke(resource, "delete", AgentActionStatus.COMPLETED,
            AgentActionResultReference(AgentResourceKind.COLLECTION, AgentResourcePath.parse("workspace/metrics")))
        assertTrue(AgentWorkspaceResponseRelations.matches(delete, removed))
        assertFalse(AgentWorkspaceResponseRelations.matches(delete, removed.copy(result = AgentActionResultReference(
            AgentResourceKind.COLLECTION, AgentResourcePath.parse("workspace/semantic-models")))))
        val version = AgentResourcePath.parse("${resource.value}/history/$id/$id")
        val restore = AgentInvokeRequest(version, "restore", AgentInvokeInput.None)
        val saved = AgentActionResult.Invoke(version, "restore", AgentActionStatus.COMPLETED,
            AgentActionResultReference(AgentResourceKind.METRIC, resource))
        assertTrue(AgentWorkspaceResponseRelations.matches(restore, saved))
        assertFalse(AgentWorkspaceResponseRelations.matches(restore, saved.copy(result = AgentActionResultReference(
            AgentResourceKind.METRIC, AgentResourcePath.parse("workspace/metrics/other")))))
    }
}
