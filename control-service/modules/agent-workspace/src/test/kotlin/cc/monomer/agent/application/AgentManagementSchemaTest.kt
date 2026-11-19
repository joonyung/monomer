package cc.monomer.agent.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import kotlin.test.*

class AgentManagementSchemaTest {
    private val mapper = ObjectMapper()
    private val document = javaClass.classLoader.getResourceAsStream("contracts/agent-workspace.schema.json")!!.use(mapper::readTree)
    private fun valid(definition: String, value: String): Boolean {
        val schema = mapper.createObjectNode().apply {
            put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            put("\$ref", "#/\$defs/$definition")
            set<JsonNode>("\$defs", document.path("\$defs"))
        }
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema).validate(mapper.readTree(value)).isEmpty()
    }
    @Test fun `organization body has one model and rejects retired account kind`() {
        val body = """{"id":"01942b04-6e00-7000-8000-000000000001","displayName":"Organization","caller":{"userId":"01942b04-6e00-7000-8000-000000000002","displayName":"Admin","role":"ADMIN"}}"""
        assertTrue(valid("AgentOrganizationBody", body))
        for (kind in listOf("PERSONAL", "ORGANIZATION")) {
            assertFalse(valid("AgentOrganizationBody", body.replace("\"displayName\":\"Organization\"", "\"accountKind\":\"$kind\",\"displayName\":\"Organization\"")))
        }
    }
    @Test fun `retired tenant schemas paths and invitation kinds are absent`() {
        assertFalse(document.path("\$defs").fieldNames().asSequence().any { it.startsWith("AgentAccount") })
        assertFalse(valid("AgentOrganizationReadRequest", """{"path":"account"}"""))
        assertFalse(valid("AgentOrganizationListRequest", """{"path":"account/members"}"""))
        assertFalse(valid("AgentOrganizationCreateRequest", """{"path":"organization/invitations","input":{"kind":"ACCOUNT_INVITATION_CREATE","email":"member@example.test"}}"""))
    }
    @Test fun `Organization membership projections contain identity and role only`() {
        val id = "01942b04-6e00-7000-8000-000000000001"
        val values = mapOf(
            "AgentOrganizationSelf" to """{"userId":"$id","displayName":"Member","role":"MEMBER"}""",
            "AgentOrganizationMemberBody" to """{"userId":"$id","displayName":"Member","email":"member@example.test","role":"MEMBER"}""",
            "AgentWorkspaceMemberBodyCandidate" to """{"userId":"$id","displayName":"Member","email":"member@example.test"}""",
            "AgentResourceListingItem" to """{"kind":"ORGANIZATION_MEMBER","path":"organization/members/$id","userId":"$id","displayName":"Member","role":"MEMBER"}""",
        )
        for ((definition, value) in values) {
            assertTrue(valid(definition, value), definition)
            for (field in listOf("status", "source")) {
                assertFalse(valid(definition, value.dropLast(1) + ",\"$field\":\"obsolete\"}"), "$definition.$field")
            }
        }
    }
    @Test fun `organization ingress schemas exclude workspace scope and bound pagination`() {
        assertTrue(valid("AgentOrganizationReadRequest", """{"path":"organization"}"""))
        assertFalse(valid("AgentOrganizationReadRequest", """{"path":"workspace/access"}"""))
        assertFalse(valid("AgentOrganizationReadRequest", """{"path":"organization","workspaceId":"01942b04-6e00-7000-8000-000000000001"}"""))
        assertTrue(valid("AgentOrganizationListRequest", """{"path":"organization/members","limit":100,"cursor":"opaque"}"""))
        assertFalse(valid("AgentOrganizationListRequest", """{"path":"organization/members","limit":101}"""))
    }
    @Test fun `Organization workspace management contract permits role assignment and member removal`() {
        val id = "01942b04-6e00-7000-8000-000000000001"
        val memberPath = "organization/workspaces/$id/members/$id"
        assertTrue(valid("AgentOrganizationListRequest", """{"path":"organization/workspaces","limit":20}"""))
        assertTrue(valid("AgentOrganizationListRequest", """{"path":"organization/workspaces/$id/members","limit":20}"""))
        assertTrue(valid("AgentOrganizationReadRequest", """{"path":"$memberPath"}"""))
        assertTrue(valid("AgentResourceListingItem", """{"kind":"COLLECTION","path":"organization/workspaces/$id/members","name":"Team"}"""))
        assertTrue(valid("AgentResourceListingItem", """{"kind":"WORKSPACE_MEMBER","path":"$memberPath","userId":"$id","displayName":"Owner","email":"owner@example.test","role":"OWNER"}"""))
        val invoke = """{"path":"$memberPath","action":"set-role","input":{"kind":"WORKSPACE_MEMBER_ROLE","role":"OWNER"}}"""
        assertTrue(valid("AgentOrganizationInvokeRequest", invoke))
        assertFalse(valid("AgentOrganizationInvokeRequest", invoke.replace("set-role", "remove-member")))
        assertFalse(valid("AgentOrganizationInvokeRequest", invoke.replace("OWNER", "ADMIN")))
        val remove = """{"path":"$memberPath","action":"remove-member"}"""
        assertTrue(valid("AgentOrganizationInvokeRequest", remove))
        assertFalse(valid("AgentOrganizationInvokeRequest", remove.replace("remove-member", "delete-member")))
        assertTrue(valid("AgentActionResult", """{"requestId":"srv_1","operation":"INVOKE","path":"$memberPath","action":"remove-member","status":"COMPLETED","result":{"kind":"COLLECTION","path":"organization/workspaces/$id/members"}}"""))
        assertTrue(valid("AgentAvailableOperation", """{"operation":"INVOKE","path":"$memberPath","action":"remove-member"}"""))
    }
    @Test fun `lineage resource paths replace data access without a compatibility path`() {
        val id = "01942b04-6e00-7000-8000-000000000001"
        for (collection in listOf("relations", "semantic-models", "metrics")) {
            assertTrue(valid("LineageResourcePath", "\"workspace/lineage/$collection/$id\""))
            assertFalse(valid("LineageResourcePath", "\"workspace/data-access/$collection/$id\""))
        }
        assertFalse(document.path("\$defs").has("AgentDataAccessResourceBody"))
    }
    @Test fun `lineage connector enums detail and principal projections are closed`() {
        val connector = """{"id":"01942b04-6e00-7000-8000-000000000001","displayName":"Warehouse","type":"POSTGRESQL"}"""
        assertTrue(valid("AgentLineageConnector",connector))
        assertFalse(valid("AgentLineageConnector",connector.replace("POSTGRESQL","MYSQL")))
        assertFalse(valid("AgentLineageConnector",connector.dropLast(1)+",\"credential\":\"secret\"}"))
        assertTrue(valid("AgentLineageDetail", """{"relation":{"columns":[]}}"""))
        assertFalse(valid("AgentLineageDetail", """{"relation":{"columns":[]},"metric":{"type":"SIMPLE","inputs":[]}}"""))
        assertFalse(valid("AgentLineageDetail", """{"relation":{"columns":[],"sql":"select 1"}}"""))
        assertFalse(document.path("\$defs").has("AgentResourceDecisionState"))
    }
}
