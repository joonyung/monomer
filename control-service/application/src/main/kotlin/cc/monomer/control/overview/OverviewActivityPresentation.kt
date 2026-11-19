package cc.monomer.control.overview

import cc.monomer.dashboard.api.DashboardSessionActivity
import cc.monomer.dashboard.api.DashboardSessionOrigin
import cc.monomer.semantic.api.SemanticGroupBy
import cc.monomer.semantic.api.SemanticModelDefinition
import cc.monomer.semantic.api.SemanticQuerySpec
import cc.monomer.semantic.api.SemanticTimeGranularity

// Explore run titles follow the Web run-title rules in web-app/src/pages/explore/run-presentation.ts.
private const val SQL_PREFIX_LENGTH = 24
private const val SQL_IDENTIFIER = """(?:`[^`]+`|"[^"]+"|\[[^\]]+\]|[A-Za-z_][\w$-]*)"""
// Possessive: java.util.regex recurses once per repeated group, and nothing after the group needs it to backtrack.
private val sqlFromTable = Regex("""\bfrom\s+($SQL_IDENTIFIER(?:\s*\.\s*$SQL_IDENTIFIER)*+)""", RegexOption.IGNORE_CASE)

/** "invoices · SELECT status, count(*)…": the first table after FROM and a short SQL prefix. */
internal fun sqlRunTitle(sql: String): String {
    val normalized = sql.replace(Regex("--[^\n]*"), " ").replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .replace(Regex("""\s+"""), " ").trim()
    if (normalized.isEmpty()) return "SQL query"
    val prefix = if (normalized.length > SQL_PREFIX_LENGTH) "${normalized.substring(0, SQL_PREFIX_LENGTH).trimEnd()}…" else normalized
    val from = sqlFromTable.find(normalized) ?: return prefix
    val last = from.groupValues[1].split(Regex("""\s*\.\s*""")).last()
    val table = last.replace(Regex("""^[`"\[]|[`"\]]$"""), "").split(".").last()
    return "$table · $prefix"
}

internal fun granularityWord(granularity: SemanticTimeGranularity): String =
    granularity.name.first() + granularity.name.drop(1).lowercase()

/** "Revenue, Invoices by Month, Region": metric labels, then each grouping by its grain or label. */
internal fun semanticQueryTitle(
    query: SemanticQuerySpec,
    metricLabel: (String) -> String?,
    model: (String) -> SemanticModelDefinition?,
): String {
    val measured = query.metrics.joinToString(", ") { name -> metricLabel(name.value)?.takeIf(String::isNotBlank) ?: name.value }
    val grouped = query.groupBys.map { groupBy ->
        when (groupBy) {
            is SemanticGroupBy.MetricTime -> granularityWord(groupBy.granularity)
            is SemanticGroupBy.Dimension -> groupBy.granularity?.let(::granularityWord) ?: elementLabel(model(groupBy.semanticModelName.value), groupBy.name.value)
            is SemanticGroupBy.Entity -> elementLabel(model(groupBy.semanticModelName.value), groupBy.name.value)
        }
    }
    return if (grouped.isEmpty()) measured else "$measured by ${grouped.joinToString(", ")}"
}

private fun elementLabel(model: SemanticModelDefinition?, name: String): String {
    val label = model?.dimensions?.firstOrNull { it.name.value == name }?.label
        ?: model?.entities?.firstOrNull { it.name.value == name }?.label
    return label?.takeIf(String::isNotBlank) ?: name
}

/** A short, fixed cause for a failed row; never the warehouse message, which may quote SQL. */
internal fun failureReason(kind: OverviewActivityKind, code: String?): String = when (code) {
    "AUTHENTICATION_FAILED" -> "Sign-in rejected"
    "CREDENTIAL_UNAVAILABLE" -> "Credentials unavailable"
    "CONNECTION_FAILED" -> "Warehouse unreachable"
    "PERMISSION_DENIED" -> "Access denied"
    "EXECUTION_ORPHANED" -> "Stopped reporting progress"
    "EXECUTION_BACKPRESSURED" -> "Too many queries running"
    else -> when (kind) {
        OverviewActivityKind.CATALOG_INGEST, OverviewActivityKind.CATALOG_REFRESH -> "Some objects couldn't be read"
        else -> "Warehouse error"
    }
}

/** The worst state among the Session's latest query runs. */
internal fun sessionState(session: DashboardSessionActivity): String = when {
    session.runningCount > 0 -> "RUNNING"
    session.failedCount > 0 -> "FAILED"
    session.cancelledCount > 0 -> "CANCELLED"
    else -> "SUCCEEDED"
}

/** "Opened · 8 queries refreshed", "Scheduled refresh · 2 of 8 queries failed". */
internal fun sessionDescription(session: DashboardSessionActivity): String {
    val opening = if (session.origin == DashboardSessionOrigin.SCHEDULE) "Scheduled refresh" else "Opened"
    fun queries(count: Int) = if (count == 1) "query" else "queries"
    val total = session.queryCount
    val outcome = when {
        session.runningCount > 0 -> "${session.runningCount} of $total ${queries(total)} running"
        session.failedCount > 0 -> "${session.failedCount} of $total ${queries(total)} failed"
        session.cancelledCount > 0 -> "${session.cancelledCount} of $total ${queries(total)} cancelled"
        else -> "$total ${queries(total)} refreshed"
    }
    return "$opening · $outcome"
}
