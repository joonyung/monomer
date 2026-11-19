package cc.monomer.control.mcp

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class McpDashboardViewTest {
    private val directory = createTempDirectory("mcp-dashboard-view")

    @AfterTest
    fun removeFiles() = Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }

    @Test
    fun `configured view file is served unchanged and an unset file serves the unavailable page`() {
        val file = directory.resolve("view.html").apply { writeText("<!doctype html><title>Dashboard</title><p>Gráfico</p>") }
        assertEquals("<!doctype html><title>Dashboard</title><p>Gráfico</p>", McpDashboardView.load(file.toString()))
        val unavailable = McpDashboardView.load("")
        assertTrue(unavailable.contains("The dashboard view is not available in this environment.") && !unavailable.contains("<script"), unavailable)
        assertTrue(McpDashboardView.OUT_OF_DATE_HTML.contains("<title>This view is out of date</title>") && !McpDashboardView.OUT_OF_DATE_HTML.contains("<script"))
    }

    @Test
    fun `missing empty oversized and non UTF-8 view files fail startup with the setting name`() {
        val invalid = listOf(
            directory.resolve("missing.html").toString(),
            directory.toString(),
            directory.resolve("empty.html").apply { writeBytes(ByteArray(0)) }.toString(),
            directory.resolve("large.html").apply { writeBytes(ByteArray(8 * 1024 * 1024 + 1) { 'a'.code.toByte() }) }.toString(),
            directory.resolve("latin1.html").apply { writeBytes(byteArrayOf(0x3c, 0xe9.toByte(), 0x3e)) }.toString(),
        )
        for (path in invalid) {
            val failure = assertFailsWith<IllegalStateException>(path) { McpDashboardView.load(path) }
            assertTrue(failure.message.orEmpty().startsWith("MONOMER_MCP_DASHBOARD_VIEW_FILE must"), failure.message)
        }
        val limit = directory.resolve("limit.html").apply { writeBytes(ByteArray(8 * 1024 * 1024) { 'a'.code.toByte() }) }
        assertEquals(8 * 1024 * 1024, McpDashboardView.load(limit.toString()).length)
    }
}
