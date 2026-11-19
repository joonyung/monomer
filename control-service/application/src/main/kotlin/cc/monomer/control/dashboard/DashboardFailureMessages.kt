package cc.monomer.control.dashboard

internal fun dashboardFailureMessage(code: String?, safeMessage: String?): String? =
    code?.let { dashboardQueryFailureMessage(it, safeMessage) ?: "The query failed." }

internal fun dashboardQueryFailureMessage(code: String, safeMessage: String?): String? = when (code) {
    "PARTITION_FILTER_REQUIRED" -> "This query needs a filter on the partition column of a table it reads."
    "RAW_SQL_VALIDATION_REJECTED", "READ_ONLY_VIOLATION" -> safeMessage?.takeIf { it.isNotBlank() } ?: "The SQL query was rejected by the warehouse validation."
    "QUERY_BIND_UNSUPPORTED" -> safeMessage?.takeIf { it.isNotBlank() } ?: "The warehouse driver cannot bind this query input exactly. Use a Semantic query."
    "RAW_SQL_VALIDATION_FAILED" -> "The SQL query could not be validated."
    "RAW_SQL_VALIDATION_DEADLINE_EXCEEDED" -> "SQL validation exceeded the execution deadline."
    "RESULT_COLUMN_MISMATCH" -> "The query result columns do not match the declared columns."
    "RESULT_ROW_LIMIT_EXCEEDED" -> "The query returned more rows than its row limit."
    "RESULT_LIMIT_EXCEEDED" -> "The query result exceeds the supported size limit. Return fewer rows or smaller values."
    "UNSUPPORTED_RESULT_TYPE" -> safeMessage?.takeIf { it.isNotBlank() } ?: "The query result contains a type that cannot be returned. Cast the column to a supported type."
    "PERMISSION_DENIED" -> "The connector account does not have permission to run this query."
    "AUTHENTICATION_FAILED" -> "The connector credentials were rejected by the warehouse."
    "CONNECTION_FAILED" -> "The warehouse connection failed."
    "DASHBOARD_QUERY_UNAUTHORIZED" -> "The Dashboard query is no longer accessible."
    "EXECUTION_UNAVAILABLE" -> "The query could not start before its dispatch deadline."
    "EXECUTION_DEADLINE_EXCEEDED" -> "The query exceeded its execution deadline."
    "EXECUTION_ORPHANED" -> "The execution connection was lost."
    "CONNECTOR_UNAVAILABLE" -> "The connector was deleted or its credentials are no longer available."
    "CONNECTOR_NOT_FOUND" -> "The connector was not found. It may have been deleted; choose another connector."
    else -> null
}
