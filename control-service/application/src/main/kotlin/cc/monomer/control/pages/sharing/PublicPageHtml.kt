package cc.monomer.control.pages.sharing

import java.net.URI

data class PublicPageMetadata(val title: String, val description: String?)

internal object PublicPageHtml {
    const val START = "<!-- monomer:page-metadata:start -->"
    const val END = "<!-- monomer:page-metadata:end -->"

    fun validateShell(shell: String) {
        require(shell.split(START).size == 2 && shell.split(END).size == 2)
        val start = shell.indexOf(START)
        val end = shell.indexOf(END)
        require(start > shell.indexOf("<head>") && shell.indexOf("<head>") >= 0)
        require(end > start && end < shell.indexOf("</head>"))
    }

    fun origin(origin: URI): String {
        require(origin.scheme == "https" && !origin.host.isNullOrBlank())
        require(origin.userInfo == null && origin.rawQuery == null && origin.rawFragment == null)
        require(origin.rawPath.isNullOrEmpty() && origin.port == -1)
        require(origin.toASCIIString() == "https://${origin.host.lowercase()}")
        return origin.toASCIIString()
    }

    fun render(shell: String, origin: URI, workspaceId: String, pageId: String, page: PublicPageMetadata): String {
        validateShell(shell)
        val base = origin(origin)
        val title = escape(clean(page.title, 200).ifBlank { "Monomer" })
        val description = escape(clean(page.description.orEmpty(), 300).ifBlank { "Explore this analysis on Monomer." })
        val canonical = "$base/workspaces/$workspaceId/pages/$pageId"
        val image = "$base/social/monomer.png"
        val metadata = """
            <title>$title</title>
            <meta name="description" content="$description" />
            <link rel="canonical" href="$canonical" />
            <meta property="og:type" content="website" />
            <meta property="og:title" content="$title" />
            <meta property="og:description" content="$description" />
            <meta property="og:url" content="$canonical" />
            <meta property="og:image" content="$image" />
            <meta property="og:image:alt" content="Monomer — business intelligence" />
            <meta property="og:image:width" content="1200" />
            <meta property="og:image:height" content="630" />
            <meta name="twitter:card" content="summary_large_image" />
            <meta name="twitter:title" content="$title" />
            <meta name="twitter:description" content="$description" />
            <meta name="twitter:image" content="$image" />
            <meta name="twitter:image:alt" content="Monomer — business intelligence" />
        """.trimIndent()
        return shell.substringBefore(START) + START + "\n" + metadata + "\n" + END + shell.substringAfter(END)
    }

    private fun clean(value: String, length: Long): String = value.codePoints()
        .filter { !Character.isISOControl(it) && Character.getType(it) != Character.FORMAT.toInt() }
        .limit(length).toArray().let { String(it, 0, it.size) }.trim()

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
