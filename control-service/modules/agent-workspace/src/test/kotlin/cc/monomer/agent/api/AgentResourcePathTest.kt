package cc.monomer.agent.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class AgentResourcePathTest {
    private val root = "workspace/connectors/0198ef28-1000-7000-8000-000000000001/catalog"

    @Test
    fun `resource UUID routes accept only lowercase canonical UUID segments`() {
        val id = "01942b04-6e00-7000-8000-000000000010"
        val paths = listOf(
            "workspace/connectors/$id",
            "workspace/connectors/$id/catalog",
            "workspace/dashboards/$id",
            "workspace/dashboards/$id/sessions/$id/query-runs/$id",
            "workspace/explore/runs/$id/result",
            "workspace/pages/$id",
            "workspace/members/$id",
        )
        assertTrue(AgentManagementPaths.readable("organization/members/$id"))
        for (alias in listOf(id.uppercase(), id.replace("-", ""))) assertFalse(AgentManagementPaths.readable("organization/members/$alias"))
        for (path in paths) {
            assertNotNull(AgentWorkspaceAdmissionIntents.read(AgentReadRequest(AgentResourcePath.parse(path), null)), path)
            for (alias in listOf(id.uppercase(), id.replace("-", ""))) {
                val invalid = path.replace(id, alias)
                assertNull(AgentWorkspaceAdmissionIntents.read(AgentReadRequest(AgentResourcePath.parse(invalid), null)), invalid)
            }
        }
    }

    @Test
    fun `name segments round trip without collisions or navigation`() {
        val names = listOf("한글 이름", "😀𐐀", "a/b", "a~b", "a~1b", "a~01b", "%2F", "%", "+?#", "a|b", "a\\\"b", ".", "..", "schemas", "relations", "ingestions", "options", "option-refreshes", "a\u2028b", "a\u2029b")
        val segments = names.map(AgentResourcePath::encodeNameSegment)
        assertEquals(names.size, segments.toSet().size)
        assertEquals("a~01b", AgentResourcePath.encodeNameSegment("a~1b"))
        names.zip(segments).forEach { (name, segment) ->
            val path = AgentResourcePath.parse("$root/schemas/$segment")
            assertEquals(name, AgentResourcePath.decodeNameSegment(path.value.split('/').last()))
            assertEquals(6, path.value.split('/').size)
        }
    }

    @Test
    fun `all owner maximum names fit both hierarchy depths including the longest option resource`() {
        val maximumNames = listOf("/".repeat(1024), "~".repeat(1024), "한".repeat(341) + "a", "😀".repeat(256), "a".repeat(1024))
        maximumNames.forEach { name ->
            val segment = AgentResourcePath.encodeNameSegment(name)
            val paths = listOf(
                "$root/schemas/$segment/relations/$segment",
                "$root/catalogs/$segment/schemas/$segment/relations/$segment",
                "$root/catalogs/$segment/selection/options/schemas/$segment/relations/$segment",
            )
            paths.forEach { assertEquals(it, AgentResourcePath.parse(it).value) }
        }
        val segment = "~1".repeat(1024)
        assertEquals(AgentResourcePath.MAXIMUM_RESOURCE_PATH_BYTES, "$root/catalogs/$segment/selection/options/schemas/$segment/relations/$segment".toByteArray().size)
        assertEquals(6257, AgentResourcePath.MAXIMUM_RESOURCE_PATH_BYTES)
        assertEquals(6990, AgentResourcePath.MAXIMUM_CURSOR_LENGTH)
    }

    @Test
    fun `reject malformed escape noncanonical Unicode and empty segments`() {
        listOf("", "~", "~2", "a~x", "e\u0301", "\uD800", "\uDC00", "a\u0000b", "a\u007Fb", "a\u0085b", " name", "name ", "a".repeat(1025), "😀".repeat(257)).forEach { invalid ->
            assertFailsWith<IllegalArgumentException>(invalid) { AgentResourcePath.parse("$root/schemas/$invalid") }
        }
        listOf("/workspace", "workspace/", "workspace//schemas", "Workspace", "$root/schemas//x").forEach {
            assertFailsWith<IllegalArgumentException> { AgentResourcePath.parse(it) }
        }
    }

    @Test
    fun `name escaping does not relax fixed semantic slugs UUIDs or actions`() {
        listOf(".", "..", "a~1b", "한글").forEach { name ->
            val request = AgentReadRequest(AgentResourcePath.parse("workspace/semantic-models/$name"), null)
            assertNull(AgentWorkspaceAdmissionIntents.read(request))
        }
    }
}
