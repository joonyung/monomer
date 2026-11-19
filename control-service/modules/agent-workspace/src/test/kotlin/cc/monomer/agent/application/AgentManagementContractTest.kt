@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package cc.monomer.agent.application

import cc.monomer.agent.api.*
import kotlin.test.*
import kotlin.uuid.Uuid

class AgentManagementContractTest {
    private val decoder = AgentWorkspaceContractDecoder()
    private val id = "01942b04-6e00-7000-8000-000000000001"
    private val decision = """{"path":"workspace/members/$id","action":"set-role","input":{"kind":"WORKSPACE_MEMBER_ROLE","role":"EDITOR"}}"""
    @Test fun `authorization schema permits only canonical members and revision`() {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val resourceNames = listOf("agent-workspace.schema.json", "problem-details.schema.json", "metric-display.schema.json", "connector/connector.schema.json", "semantic/model.schema.json", "semantic/metric.schema.json", "semantic/query.schema.json", "dashboard/dashboard.schema.json")
        val documents = resourceNames.map { name -> javaClass.classLoader.getResourceAsStream("contracts/$name")!!.use(mapper::readTree) }
        val factory = com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012) { builder ->
            builder.schemaLoaders { loaders -> loaders.schemas(documents.associate { it.path("\$id").asText() to it.toString() }) }
        }
        fun validate(definition: String, value: String): Boolean {
            val schema = documents.first().deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply { put("\$ref", "#/\$defs/$definition") }
            return factory.getSchema(schema).validate(mapper.readTree(value)).isEmpty()
        }
        val member = """{"userId":"$id","displayName":"Alex","email":"alex@example.test","role":"EDITOR"}"""
        val body = """{"authorizationRevision":"12","members":[$member]}"""
        val envelope = """{"requestId":"trace","kind":"WORKSPACE_AUTHORIZATION","path":"workspace/authorization","body":$body,"availableOperations":[]}"""
        assertTrue(validate("AgentResourceEnvelope", envelope))
        assertFalse(validate("AgentResourceEnvelope", envelope.replace("\"authorizationRevision\":", "\"principals\":[],\"authorizationRevision\":")))
        assertFalse(validate("AgentResourceEnvelope", envelope.replace(",\"members\":[$member]", "")))
    }
    @Test fun `closed role commands reject forged scope and retired access controls`() {
        assertEquals(cc.monomer.identity.api.WorkspaceRole.EDITOR, assertIs<AgentInvokeInput.WorkspaceMemberRole>(decoder.invoke(decision).value.input).role)
        for (invalid in listOf(decision.replace("EDITOR","ADMIN"), decision.replace("set-role","assign-authoring"), decision.replace("workspace/members/$id","workspace/access"), decision.replace("\"role\":", "\"actorId\":\"$id\",\"role\":"), decision.replace(id,"invalid"))) assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.invoke(invalid) }
    }
    @Test fun `membership requires an explicit direct role`() {
        val add = """{"path":"workspace/members","action":"add-member","input":{"kind":"WORKSPACE_MEMBER_ADD","userId":"$id","role":"EXPLORER"}}"""
        assertEquals(cc.monomer.identity.api.WorkspaceRole.EXPLORER, assertIs<AgentInvokeInput.WorkspaceMemberAdd>(decoder.invoke(add).value.input).role)
        assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.invoke(add.replace(",\"role\":\"EXPLORER\"", "")) }
        assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.invoke(add.replace("\"userId\":", "\"principalKind\":\"UNKNOWN\",\"userId\":")) }
    }
    @Test fun `Organization members have no status command or implicit membership state`() {
        val request = """{"path":"organization/members/$id","action":"set-status","input":{"kind":"ORGANIZATION_MEMBER_STATUS","status":"ACTIVE"}}"""
        assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.invoke(request) }
        assertFalse(AgentManagementPaths.readable("organization/members/$id/status"))
        for (field in listOf("status", "source")) {
            assertFailsWith<AgentWorkspaceRequestDecodingException> {
                decoder.invoke(decision.replace("\"role\":", "\"$field\":\"obsolete\",\"role\":"))
            }
        }
    }
    @Test fun `organization workspace role command uses Organization scope and rejects undeclared actions`() {
        val input = """{"path":"organization/workspaces/$id/members/$id","action":"set-role","input":{"kind":"WORKSPACE_MEMBER_ROLE","role":"EDITOR"}}"""
        val request = decoder.invoke(input).value
        assertTrue(AgentManagementPaths.organization(request.path.value))
        assertTrue(AgentManagementPaths.readable(request.path.value))
        assertNull(AgentWorkspaceAdmissionIntents.invoke(request))
        assertEquals(cc.monomer.identity.api.WorkspaceRole.EDITOR, assertIs<AgentInvokeInput.WorkspaceMemberRole>(request.input).role)
        for (invalid in listOf(input.replace("set-role", "remove-member"), input.replace("EDITOR", "ADMIN"), input.replace("\"role\":", "\"actorId\":\"$id\",\"role\":"))) {
            assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.invoke(invalid) }
        }
        assertTrue(AgentWorkspaceResponseRelations.matches(request, AgentActionResult.Invoke(request.path, request.action, AgentActionStatus.COMPLETED, AgentActionResultReference(AgentResourceKind.WORKSPACE_MEMBER, request.path))))
        val removal = decoder.invoke("""{"path":"organization/workspaces/$id/members/$id","action":"remove-member"}""").value
        assertEquals(AgentInvokeInput.None, removal.input)
        assertTrue(AgentManagementPaths.invocation(removal))
        assertNull(AgentWorkspaceAdmissionIntents.invoke(removal))
        val collection = AgentResourcePath.parse("organization/workspaces/$id/members")
        assertTrue(AgentWorkspaceResponseRelations.matches(removal, AgentActionResult.Invoke(removal.path, removal.action, AgentActionStatus.COMPLETED, AgentActionResultReference(AgentResourceKind.COLLECTION, collection))))
        assertFalse(AgentWorkspaceResponseRelations.matches(removal, AgentActionResult.Invoke(removal.path, removal.action, AgentActionStatus.COMPLETED, AgentActionResultReference(AgentResourceKind.COLLECTION, AgentResourcePath.parse("workspace/members")))))
    }
    @Test fun `invitations admit only email and exact parameterless actions`() {
        val create = """{"path":"organization/invitations","input":{"kind":"ORGANIZATION_INVITATION_CREATE","email":"alex@example.com"}}"""
        assertEquals(emptyList(), assertIs<AgentCreateInput.OrganizationInvitation>(decoder.create(create).value.input).workspaceAccess)
        val withAccess = create.replace("\"email\":", "\"workspaceAccess\":[{\"workspaceId\":\"$id\",\"role\":\"EXPLORER\"}],\"email\":")
        assertEquals(listOf(AgentCreateInput.OrganizationInvitation.InvitationWorkspaceAccess(kotlin.uuid.Uuid.parse(id), cc.monomer.identity.api.WorkspaceRole.EXPLORER)),
            assertIs<AgentCreateInput.OrganizationInvitation>(decoder.create(withAccess).value.input).workspaceAccess)
        assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.create(withAccess.replace("EXPLORER", "ADMIN")) }
        assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.create(withAccess.replace("\"role\":\"EXPLORER\"", "\"role\":\"EXPLORER\",\"organizationId\":\"$id\"")) }
        assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.create(create.replace("ORGANIZATION_INVITATION_CREATE", "UNKNOWN_INVITATION_CREATE")) }
        for (field in listOf("workspaceId","organizationId","actorId","tenantId","role","token")) assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.create(create.replace("\"email\":","\"$field\":\"$id\",\"email\":")) }
        for (action in listOf("resend","cancel")) {
            val request = """{"path":"organization/invitations/$id","action":"$action"}"""
            assertEquals(AgentInvokeInput.None,decoder.invoke(request).value.input)
            assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.invoke(request.dropLast(1)+",\"input\":null}") }
        }
    }
    @Test fun `workspace admission excludes organization and removal results reference living collection`() {
        assertNull(AgentWorkspaceAdmissionIntents.read(AgentReadRequest(AgentResourcePath.parse("organization"),null)))
        assertNotNull(AgentWorkspaceAdmissionIntents.invoke(decoder.invoke(decision).value))
        val path=AgentResourcePath.parse("workspace/members/$id")
        val request=AgentInvokeRequest(path,"remove-member",AgentInvokeInput.None)
        val action=AgentActionResult.Invoke(path,"remove-member",AgentActionStatus.COMPLETED,AgentActionResultReference(AgentResourceKind.COLLECTION,AgentResourcePath.parse("workspace/members")))
        assertTrue(AgentWorkspaceResponseRelations.matches(request,action))
        assertFalse(AgentWorkspaceResponseRelations.matches(request,action.copy(result=AgentActionResultReference(AgentResourceKind.WORKSPACE_MEMBER,path))))
        val list=AgentListRequest(AgentResourcePath.parse("organization/members"),null,100)
        assertTrue(AgentWorkspaceResponseRelations.matches(list,AgentResourceListingResponse(listOf(AgentResourceListingItem.OrganizationMember(AgentResourcePath.parse("organization/members/$id"),Uuid.parse(id),"Alex",AgentOrganizationRole.MEMBER)),null,emptyList())))
    }
}
