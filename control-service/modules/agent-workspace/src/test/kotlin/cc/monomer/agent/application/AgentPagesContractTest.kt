@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.agent.application

import cc.monomer.agent.api.*
import cc.monomer.identity.api.WorkspaceRole
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.*

class AgentPagesContractTest {
    private val decoder = AgentWorkspaceContractDecoder()
    private val mapper = ObjectMapper()
    private val id = "0198ef28-2000-7000-8000-000000000001"
    private val page = "workspace/pages/$id"
    private val upload = "workspace/page-content-uploads/$id"
    private val create = """{"path":"workspace/pages","input":{"kind":"FOLDER_PAGE","title":"Reports","baseRevision":"0","commandId":"create-folder"}}"""
    private val edit = """{"path":"$page","revision":"0","commandId":"rename-page","patch":[{"op":"replace","path":"/title","value":"Weekly"},{"op":"replace","path":"/description","value":null}]}"""

    @Test
    fun `optional creation fields normalize omission and null to the same typed command`() {
        val omitted = decoder.create(create).value
        val explicit = decoder.create(create.replace("\"title\":\"Reports\"", "\"title\":\"Reports\",\"description\":null,\"parentPageId\":null,\"index\":null")).value
        assertEquals(omitted, explicit)
        assertIs<AgentCreateInput.FolderPage>(omitted.input)
        assertEquals(WorkspaceRole.EXPLORER, AgentWorkspaceAdmissionIntents.create(omitted)!!.requiredRole)
        listOf("DOCUMENT_PAGE" to "uploadRef", "DASHBOARD_PAGE" to "dashboardId").forEach { (kind, field) ->
            val input = create.replace("FOLDER_PAGE", kind).replace("\"title\":", "\"$field\":\"$id\",\"title\":")
            assertNotNull(AgentWorkspaceAdmissionIntents.create(decoder.create(input).value))
            rejectsCreate(input.replace(field, if (field == "uploadRef") "dashboardId" else "uploadRef"))
        }
    }

    @Test
    fun `create accepts only its own fields and path with one command and revision key`() {
        listOf("pageKind" to "\"FOLDER\"", "pageId" to "\"$id\"", "actor" to "\"agent\"", "requestId" to "\"tracking\"", "revision" to "\"0\"", "uploadRef" to "\"$id\"", "dashboardId" to "\"$id\"").forEach { (field,value) ->
            rejectsCreate(create.replace("\"kind\":", "\"$field\":$value,\"kind\":"))
        }
        rejectsCreate(create.replace(",\"commandId\":\"create-folder\"", ""))
        rejectsCreate(create.replace("workspace/pages", page))
        rejectsCreate(create.replace("\"Reports\"", "\"   \""))
    }

    @Test
    fun `create and delete take the directory base revision as a decimal revision string`() {
        val delete = """{"path":"$page","action":"delete","input":{"kind":"PAGE_DELETE","baseRevision":"0","commandId":"delete-page"}}"""
        val folder = assertIs<AgentCreateInput.FolderPage>(decoder.create(create.replace("\"baseRevision\":\"0\"", "\"baseRevision\":\"12\"")).value.input)
        assertEquals(12L, folder.baseRevision)
        assertEquals(AgentInvokeInput.PageDelete(12L, "delete-page"), decoder.invoke(delete.replace("\"baseRevision\":\"0\"", "\"baseRevision\":\"12\"")).value.input)
        listOf("12", "\"012\"", "\"-1\"", "\"12345678901234567890\"", "\"9223372036854775808\"").forEach { invalid ->
            val revisionFailure = assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.edit(edit.replace("\"revision\":\"0\"", "\"revision\":$invalid")) }
            val expected = revisionFailure.violations.map { it.copy(field = "input.baseRevision") }
            listOf(
                assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.create(create.replace("\"baseRevision\":\"0\"", "\"baseRevision\":$invalid")) },
                assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.invoke(delete.replace("\"baseRevision\":\"0\"", "\"baseRevision\":$invalid")) },
            ).forEach { failure -> assertEquals(expected, failure.violations, invalid) }
        }
    }

    @Test
    fun `metadata edit requires each complete field exactly once with replace only`() {
        val request = decoder.edit(edit).value
        assertEquals("rename-page", request.commandId)
        assertEquals(0L, request.revision!!.value)
        assertNotNull(AgentWorkspaceAdmissionIntents.edit(request))
        listOf("add", "remove", "move", "copy", "test").forEach { rejectsEdit(edit.replace("\"op\":\"replace\"", "\"op\":\"$it\"")) }
        rejectsEdit(edit.replace("/description", "/title"))
        rejectsEdit(edit.replace(",\"commandId\":\"rename-page\"", ""))
        rejectsEdit(edit.replace("\"revision\":\"0\"", "\"baseRevision\":\"0\""))
        rejectsEdit(edit.replace("\"revision\":\"0\"", "\"revision\":0"))
        rejectsEdit(edit.replace("/description", "/dashboardId"))
        rejectsEdit(edit.replace(page, "workspace/dashboards/$id/draft"))
        val missing = mapper.readTree(edit).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        (missing.path("patch") as com.fasterxml.jackson.databind.node.ArrayNode).remove(1)
        rejectsEdit(missing.toString())
    }

    @Test
    fun `tree edits expose only existing Agent patch operations`() {
        listOf("add", "replace", "remove").forEach { op ->
            val value = if (op == "remove") "" else ",\"value\":[]"
            val json = """{"path":"workspace/pages/tree","revision":"4","commandId":"tree-command","patch":[{"op":"$op","path":"/placements/0"$value}]}"""
            assertNotNull(AgentWorkspaceAdmissionIntents.edit(decoder.edit(json).value))
            rejectsEdit(json.replace("\"op\":\"$op\"", "\"op\":\"move\""))
        }
    }

    @Test
    fun `version commits and upload operations do not accept directory revisions or duplicated target IDs`() {
        val version = """{"path":"$page/versions","input":{"kind":"PAGE_VERSION","commandId":"commit-content","uploadRef":"$id"}}"""
        assertIs<AgentCreateInput.PageVersion>(decoder.create(version).value.input)
        listOf("baseRevision" to "\"0\"", "revision" to "\"0\"", "pageId" to "\"$id\"", "versionNo" to "1").forEach { (field,value) -> rejectsCreate(version.replace("\"kind\":", "\"$field\":$value,\"kind\":")) }
        val prepare = """{"path":"workspace/page-content-uploads","input":{"kind":"PAGE_CONTENT_UPLOAD","format":"MARKDOWN","byteSize":12,"sha256":"${"a".repeat(64)}"}}"""
        assertIs<AgentCreateInput.PageContentUpload>(decoder.create(prepare).value.input)
        listOf("commandId" to "\"upload-command\"", "content" to "\"bytes\"", "url" to "\"https://example.test\"").forEach { (field,value) -> rejectsCreate(prepare.replace("\"kind\":", "\"$field\":$value,\"kind\":")) }
        rejectsCreate(prepare.replace("20971520", "20971521").replace("\"byteSize\":12", "\"byteSize\":20971521"))
        val complete = """{"path":"$upload","action":"complete","input":{"kind":"PAGE_CONTENT_UPLOAD_COMPLETE","parts":[{"partNumber":1,"eTag":"etag"}]}}"""
        assertIs<AgentInvokeInput.PageContentUploadComplete>(decoder.invoke(complete).value.input)
        listOf("commandId", "uploadRef", "requestId").forEach { field -> rejectsInvoke(complete.replace("\"kind\":", "\"$field\":\"$id\",\"kind\":")) }
        assertEquals(AgentInvokeInput.None, decoder.invoke("""{"path":"$upload","action":"abort"}""").value.input)
        rejectsInvoke(complete.replace("complete", "abort"))
        rejectsInvoke(complete.replace(upload, page))
    }

    @Test
    fun `admission distinguishes exact pages operations from content download search and invalid targets`() {
        listOf(page, "$page/versions/1", "workspace/pages/tree").forEach { path ->
            val intent = AgentWorkspaceAdmissionIntents.read(decoder.read("""{"path":"$path"}"""))!!
            assertEquals(if ("/versions/" in path) cc.monomer.identity.api.WorkspaceRole.EXPLORER else cc.monomer.identity.api.WorkspaceRole.VIEWER, intent.requiredRole)
        }
        listOf(upload, "$page/versions/01", "$page/versions/0", "$page/versions/../1").forEach { path ->
            assertNull(AgentWorkspaceAdmissionIntents.read(decoder.read("""{"path":"$path"}""")))
        }
        assertNull(AgentWorkspaceAdmissionIntents.list(decoder.list("""{"path":"workspace/page-content-uploads","limit":20}""")))
        assertTrue(AgentSearchScope.supports(AgentResourcePath.parse("workspace/pages")))
        assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.read("""{"path":"$page/versions/1","download":"arrow"}""") }
        val delete = decoder.invoke("""{"path":"$page","action":"delete","input":{"kind":"PAGE_DELETE","commandId":"delete-page","baseRevision":"4"}}""").value
        assertEquals(WorkspaceRole.EXPLORER, AgentWorkspaceAdmissionIntents.invoke(delete)!!.requiredRole)
        assertTrue(AgentWorkspaceResponseRelations.matches(delete, AgentActionResult.Invoke(delete.path, "delete", AgentActionStatus.COMPLETED, AgentActionResultReference(AgentResourceKind.PAGES_TREE, AgentResourcePath.parse("workspace/pages/tree")))))
        assertFalse(AgentWorkspaceResponseRelations.matches(delete, AgentActionResult.Invoke(delete.path, "delete", AgentActionStatus.COMPLETED, AgentActionResultReference(AgentResourceKind.PAGE, delete.path))))
        assertFailsWith<IllegalArgumentException> { AgentAvailableOperation.Invoke(delete.path, "complete", "PAGE_CONTENT_UPLOAD_COMPLETE") }
    }

    @Test
    fun `typed upload results and page relations agree with the owning response schema`() {
        val resourceNames = listOf("agent-workspace.schema.json", "problem-details.schema.json", "metric-display.schema.json", "connector/connector.schema.json", "semantic/model.schema.json", "semantic/metric.schema.json", "semantic/query.schema.json", "dashboard/dashboard.schema.json")
        val documents = resourceNames.map { name -> javaClass.classLoader.getResourceAsStream("contracts/$name")!!.use(mapper::readTree) }
        val factory = com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012) { builder ->
            builder.schemaLoaders { loaders -> loaders.schemas(documents.associate { it.path("\$id").asText() to it.toString() }) }
        }
        fun validate(definition: String, value: String): Boolean {
            val schema = documents.first().deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply { put("\$ref", "#/\$defs/$definition") }
            return factory.getSchema(schema).validate(mapper.readTree(value)).isEmpty()
        }
        val metadata = """{"uploadRef":"$id","format":"MARKDOWN","byteSize":12,"sha256":"${"a".repeat(64)}","expiresAt":"2026-09-12T00:00:00Z"}"""
        val prepared = """{"requestId":"trace","operation":"CREATE","path":"workspace/page-content-uploads","status":"COMPLETED","result":{"kind":"PAGE_CONTENT_UPLOAD","path":"$upload"},"upload":$metadata,"partSizeBytes":5242880,"parts":[{"partNumber":1,"contentLength":12,"url":"https://storage.example.test/part","requiredHeaders":{"Content-Length":"12"}}]}"""
        assertTrue(validate("AgentActionResult", prepared))
        assertFalse(validate("AgentActionResult", prepared.replace("PAGE_CONTENT_UPLOAD", "PAGE")))
        val completed = """{"requestId":"trace","operation":"INVOKE","path":"$upload","action":"complete","status":"COMPLETED","result":{"kind":"PAGE_CONTENT_UPLOAD","path":"$upload"},"upload":$metadata}"""
        assertTrue(validate("AgentActionResult", completed))
        assertFalse(validate("AgentAvailableOperation", """{"operation":"READ","path":"$upload"}"""))
        assertTrue(validate("AgentAvailableOperation", """{"operation":"INVOKE","path":"$upload","action":"complete","inputKind":"PAGE_CONTENT_UPLOAD_COMPLETE"}"""))
        val dashboardBody = """{"pageKind":"DASHBOARD","title":"Weekly","description":null,"displayTitle":"Weekly","dashboard":{"kind":"DASHBOARD","path":"workspace/dashboards/$id"},"latestVersion":null,"documentFormat":null,"createdAt":"2026-09-12T00:00:00Z","updatedAt":"2026-09-12T00:00:00Z"}"""
        val envelope = """{"requestId":"trace","kind":"PAGE","path":"$page","revision":"4","body":$dashboardBody,"availableOperations":[]}"""
        assertTrue(validate("AgentResourceEnvelope", envelope))
        assertFalse(validate("AgentResourceEnvelope", envelope.replace("\"pageKind\":\"DASHBOARD\"", "\"pageKind\":\"FOLDER\"")))
        assertFalse(validate("AgentResourceEnvelope", envelope.replace("\"title\":", "\"pageId\":\"$id\",\"title\":")))
    }

    private fun rejectsCreate(value: String) { assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.create(value) } }
    private fun rejectsEdit(value: String) { assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.edit(value) } }
    private fun rejectsInvoke(value: String) { assertFailsWith<AgentWorkspaceRequestDecodingException> { decoder.invoke(value) } }
}
