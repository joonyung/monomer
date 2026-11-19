package cc.monomer.control.mcp

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import org.slf4j.LoggerFactory

object McpDashboardView {
    const val VIEW_CONTRACT = 1
    const val URI = "ui://monomer/dashboard-view/v$VIEW_CONTRACT"
    const val MIME_TYPE = "text/html;profile=mcp-app"
    const val CONTRACT_META_KEY = "cc.monomer/viewContract"
    val VERSIONED_URI = Regex("^ui://monomer/dashboard-view/v[1-9][0-9]{0,8}$")
    private const val MAXIMUM_BYTES = 8L * 1024 * 1024

    val OUT_OF_DATE_HTML = page(
        "This view is out of date",
        "<h1>This view is out of date</h1><p>Reconnect the Monomer connector, or open the dashboard in Monomer.</p>",
    )

    private val UNAVAILABLE_HTML = page(
        "Dashboard view unavailable",
        "<p>The dashboard view is not available in this environment.</p>",
    )

    fun load(configuredFile: String): String {
        if (configuredFile.isBlank()) return UNAVAILABLE_HTML
        val file = Path.of(configuredFile)
        check(Files.isRegularFile(file)) { "MONOMER_MCP_DASHBOARD_VIEW_FILE must name an existing file: $configuredFile" }
        val size = Files.size(file)
        check(size in 1..MAXIMUM_BYTES) { "MONOMER_MCP_DASHBOARD_VIEW_FILE must be 1 byte to 8 MiB; $configuredFile has $size bytes" }
        val bytes = Files.readAllBytes(file)
        check(bytes.size.toLong() in 1..MAXIMUM_BYTES) { "MONOMER_MCP_DASHBOARD_VIEW_FILE changed while loading: $configuredFile" }
        val html = try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            error("MONOMER_MCP_DASHBOARD_VIEW_FILE must be valid UTF-8: $configuredFile")
        }
        LoggerFactory.getLogger(McpDashboardView::class.java).atInfo()
            .addKeyValue("event", "mcp_dashboard_view_loaded")
            .addKeyValue("bytes", bytes.size)
            .addKeyValue("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
            .log("mcp_dashboard_view_loaded")
        return html
    }

    private fun page(title: String, body: String): String =
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>$title</title>" +
            "<style>:root{color-scheme:light dark}body{margin:0;padding:16px;font:14px/1.5 system-ui,sans-serif}" +
            "h1{margin:0 0 4px;font-size:16px}p{margin:0}</style></head><body>$body</body></html>"
}
