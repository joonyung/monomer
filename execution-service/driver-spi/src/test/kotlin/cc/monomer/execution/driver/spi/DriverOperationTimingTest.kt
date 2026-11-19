package cc.monomer.execution.driver.spi

import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class DriverOperationTimingTest {
    @Test
    fun summaryIsBoundedAndContainsOnlyAllowlistedMetadata() {
        val logs = mutableListOf<LogRecord>()
        val logger = Logger.getLogger(DriverOperationTiming::class.java.name)
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { logs += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        try {
            val timing = DriverOperationTiming(ConnectorType.REDSHIFT, DriverOperation.QUERY,
                "password=secret\nSELECT private", "https://secret-host/?token=secret")
            val failure = IllegalStateException("password=secret token=secret person@example.test SQL SELECT private result=secret")
            assertFailsWith<IllegalStateException> {
                timing.measure(DriverPhase.READ) {
                    timing.measure(DriverPhase.UPLOAD) { throw failure }
                }
            }
            timing.failed(DriverPhase.READ, IllegalStateException("wrapped secret", failure))
            timing.measure(DriverPhase.CLEANUP) {
                runCatching { timing.measure(DriverPhase.CREDENTIAL) { throw IllegalStateException("cleanup secret") } }
            }
            timing.cleanupFailed()
            timing.log(DriverPhysicalResult.Failed("PASSWORD=secret", "secret message"))
            timing.log(DriverPhysicalResult.Cancelled)
            val record = logs.single()
            val line = record.message
            assertNull(record.thrown)
            assertEquals(Level.INFO, record.level)
            val fields = assertIs<Map<*, *>>(record.parameters.single())
            assertEquals(1, record.parameters.size)
            assertEquals("none", fields["run_id"])
            assertEquals("none", fields["workspace_id"])
            assertEquals("REDSHIFT", fields["driver"])
            assertEquals("QUERY", fields["operation"])
            assertEquals("FAILED", fields["outcome"])
            assertEquals("UPLOAD", fields["failure_phase"])
            assertEquals("UNCLASSIFIED_FAILURE", fields["failure_code"])
            assertIs<Long>(fields["total_ms"])
            assertEquals(0L, fields["poll_count"])
            assertEquals(1L, fields["cleanup_failure_count"])
            assertIs<Long>(fields["read_ms"])
            assertIs<Long>(fields["upload_ms"])
            assertNull(fields["connect_ms"])
            assertTrue(fields.values.all { it == null || it is String || it is Long })
            assertEquals("driver_operation_timing " + fields.entries.joinToString(" ") { (key, value) -> "$key=${value ?: "null"}" }, line)
            assertTrue(line.contains("run_id=none workspace_id=none driver=REDSHIFT operation=QUERY outcome=FAILED"))
            assertTrue(line.contains("failure_phase=UPLOAD failure_code=UNCLASSIFIED_FAILURE"))
            assertTrue(Regex("read_ms=\\d+ upload_ms=\\d+").containsMatchIn(line))
            assertTrue(line.contains("connect_ms=null"))
            listOf("secret", "SELECT", "password", "https", "result=", "token=", "person@example.test").forEach {
                assertTrue(!line.contains(it))
                assertTrue(!fields.toString().contains(it))
            }
        } finally { logger.removeHandler(handler) }
    }

    @Test
    fun cancelledActiveAttemptReportsElapsedPhaseAndPollingWithoutThrowable() {
        val logs = mutableListOf<LogRecord>()
        val logger = Logger.getLogger(DriverOperationTiming::class.java.name)
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { logs += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        try {
            val timing = DriverOperationTiming(ConnectorType.BIGQUERY, DriverOperation.QUERY,
                "019f0000-0000-7000-8000-000000000001", "019f0000-0000-7000-8000-000000000002")
            val active = timing.begin(DriverPhase.WAIT)
            repeat(100) { timing.polled() }
            timing.log(DriverPhysicalResult.Cancelled)
            timing.finish(active)
            val record = logs.single()
            val line = record.message
            val fields = assertIs<Map<*, *>>(record.parameters.single())
            assertEquals(1, record.parameters.size)
            assertNull(record.thrown)
            assertEquals("019f0000-0000-7000-8000-000000000001", fields["run_id"])
            assertEquals("019f0000-0000-7000-8000-000000000002", fields["workspace_id"])
            assertEquals("CANCELLED", fields["outcome"])
            assertEquals("WAIT", fields["failure_phase"])
            assertEquals("CANCELLED", fields["failure_code"])
            assertEquals(100L, fields["poll_count"])
            assertEquals(0L, fields["cleanup_failure_count"])
            assertIs<Long>(fields["wait_ms"])
            assertNull(fields["read_ms"])
            assertTrue(line.contains("outcome=CANCELLED failure_phase=WAIT failure_code=CANCELLED"))
            assertTrue(line.contains("poll_count=100"))
            assertTrue(Regex("wait_ms=\\d+").containsMatchIn(line))
        } finally { logger.removeHandler(handler) }
    }

    @Test
    fun successfulUnattemptedPhasesAndAllowlistedFailureCodeRetainTheirTypes() {
        val logs = mutableListOf<LogRecord>()
        val logger = Logger.getLogger(DriverOperationTiming::class.java.name)
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { logs += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        try {
            val succeeded = DriverOperationTiming(null, DriverOperation.VALIDATE, null, null)
            succeeded.log(DriverPhysicalResult.Succeeded(123uL, 456uL, null))
            succeeded.log(DriverPhysicalResult.Cancelled)
            val successRecord = logs.single()
            val successFields = assertIs<Map<*, *>>(successRecord.parameters.single())
            assertEquals("UNKNOWN", successFields["driver"])
            assertEquals("VALIDATE", successFields["operation"])
            assertEquals("SUCCEEDED", successFields["outcome"])
            assertEquals("none", successFields["failure_phase"])
            assertEquals("none", successFields["failure_code"])
            assertEquals(0L, successFields["poll_count"])
            assertEquals(0L, successFields["cleanup_failure_count"])
            assertTrue(assertIs<Long>(successFields["total_ms"]) >= 0L)
            DriverPhase.entries.forEach { phase ->
                assertTrue(successFields.containsKey("${phase.name.lowercase(java.util.Locale.ROOT)}_ms"))
                assertNull(successFields["${phase.name.lowercase(java.util.Locale.ROOT)}_ms"])
            }
            assertEquals(10 + DriverPhase.entries.size, successFields.size)
            assertTrue(successFields.keys.all { it is String })
            assertTrue(successFields.values.none { it == 123uL || it == 456uL })

            val failed = DriverOperationTiming(ConnectorType.POSTGRESQL, DriverOperation.QUERY,
                "ABCDEF00-0000-7000-8000-000000000001", "ABCDEF00-0000-7000-8000-000000000002")
            failed.measure(DriverPhase.EXECUTE) { Unit }
            failed.markFailure(DriverPhase.EXECUTE)
            failed.log(DriverPhysicalResult.Failed("PERMISSION_DENIED", "token=secret person@example.test SELECT private result=secret"))
            val failureRecord = logs.last()
            val failureFields = assertIs<Map<*, *>>(failureRecord.parameters.single())
            assertEquals("abcdef00-0000-7000-8000-000000000001", failureFields["run_id"])
            assertEquals("abcdef00-0000-7000-8000-000000000002", failureFields["workspace_id"])
            assertEquals("PERMISSION_DENIED", failureFields["failure_code"])
            assertEquals("EXECUTE", failureFields["failure_phase"])
            assertTrue(assertIs<Long>(failureFields["execute_ms"]) >= 0L)
            assertNull(failureRecord.thrown)
            listOf("secret", "token=", "person@example.test", "SELECT", "result=").forEach {
                assertTrue(!failureRecord.message.contains(it))
                assertTrue(!failureFields.toString().contains(it))
            }
            assertEquals(2, logs.size)
        } finally { logger.removeHandler(handler) }
    }
}
