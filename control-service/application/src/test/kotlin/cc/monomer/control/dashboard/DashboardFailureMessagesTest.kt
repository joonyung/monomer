package cc.monomer.control.dashboard

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class DashboardFailureMessagesTest {
    @Test
    fun `deleted connector failures are named instead of a generic failure`() {
        assertEquals(
            "The connector was not found. It may have been deleted; choose another connector.",
            dashboardFailureMessage("CONNECTOR_NOT_FOUND", null),
        )
        assertEquals(
            "The connector was deleted or its credentials are no longer available.",
            dashboardFailureMessage("CONNECTOR_UNAVAILABLE", null),
        )
        assertEquals("The query failed.", dashboardFailureMessage("EXECUTION_FAILED", null))
        assertNull(dashboardFailureMessage(null, null))
    }
    @Test
    fun `validation rejection uses the safe engine message and mismatch has a stable message`() {
        assertEquals("Only read-only queries are allowed.", dashboardFailureMessage("RAW_SQL_VALIDATION_REJECTED", "Only read-only queries are allowed."))
        assertEquals("The query result columns do not match the declared columns.", dashboardFailureMessage("RESULT_COLUMN_MISMATCH", null))
    }

    @Test
    fun `unsupported exact bind preserves the type-specific safe message`() {
        val bytesMessage = "Databricks binary parameters, including typed nulls, are unsupported. Use a Semantic query."
        assertEquals(bytesMessage, dashboardFailureMessage("QUERY_BIND_UNSUPPORTED", bytesMessage))
        assertEquals(
            "The warehouse driver cannot bind this query input exactly. Use a Semantic query.",
            dashboardFailureMessage("QUERY_BIND_UNSUPPORTED", null),
        )
        assertEquals(dashboardFailureMessage("QUERY_BIND_UNSUPPORTED", null), dashboardFailureMessage("QUERY_BIND_UNSUPPORTED", " "))
    }

    @Test
    fun `result limit failure has a stable actionable message`() {
        assertEquals(
            "The query result exceeds the supported size limit. Return fewer rows or smaller values.",
            dashboardFailureMessage("RESULT_LIMIT_EXCEEDED", "internal limit diagnostic"),
        )
    }

    @Test
    fun `unsupported result preserves safe column and type detail with an actionable fallback`() {
        val detail = "Result column 'sample' has unsupported type 'vendor_type'."
        assertEquals(detail, dashboardFailureMessage("UNSUPPORTED_RESULT_TYPE", detail))
        val fallback = "The query result contains a type that cannot be returned. Cast the column to a supported type."
        assertEquals(fallback, dashboardFailureMessage("UNSUPPORTED_RESULT_TYPE", null))
        assertEquals(fallback, dashboardFailureMessage("UNSUPPORTED_RESULT_TYPE", " "))
    }
}
