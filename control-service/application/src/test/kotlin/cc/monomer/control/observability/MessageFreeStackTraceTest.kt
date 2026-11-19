package cc.monomer.control.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageFreeStackTraceTest {
    @Test fun rendersOnlyClassNamesAndFramesAlongTheCauseChain() {
        val cause = IllegalArgumentException("password=cause-secret SELECT private@example.com").apply {
            stackTrace = arrayOf(StackTraceElement("sample.Cause", "load", "Cause.kt", 23))
        }
        val suppressed = UnsupportedOperationException("Bearer suppressed-secret").apply {
            stackTrace = arrayOf(StackTraceElement("sample.Suppressed", "hidden", "Suppressed.kt", 99))
            initCause(ArithmeticException("suppressed-cause-secret"))
        }
        val failure = IllegalStateException("Bearer root-secret", cause).apply {
            stackTrace = arrayOf(
                StackTraceElement("sample.Root", "run", "File.kt", 17),
                StackTraceElement("sample.Native", "call", "Native.kt", -2),
                StackTraceElement("sample.Unknown", "call", null, -1),
                StackTraceElement("sample.NoLine", "call", "NoLine.kt", -1),
            )
            addSuppressed(suppressed)
        }
        val trace = messageFreeStackTrace(failure)
        assertEquals(
            "java.lang.IllegalStateException\n" +
                "\tat sample.Root.run(File.kt:17)\n" +
                "\tat sample.Native.call(Native Method)\n" +
                "\tat sample.Unknown.call(Unknown Source)\n" +
                "\tat sample.NoLine.call(NoLine.kt)\n" +
                "\tSuppressed: java.lang.UnsupportedOperationException\n" +
                "Caused by: java.lang.IllegalArgumentException\n" +
                "\tat sample.Cause.load(Cause.kt:23)",
            trace,
        )
        for (excluded in listOf("root-secret", "cause-secret", "suppressed-secret", "SELECT", "private@example.com", "password=", "Bearer", "sample.Suppressed", "Suppressed.kt", "ArithmeticException")) {
            assertFalse(trace.contains(excluded), excluded)
        }
    }

    @Test fun neverReadsThrowableMessagesOrToString() {
        val failure = MessageTrapFailure().apply {
            stackTrace = emptyArray()
            initCause(MessageTrapFailure().apply { stackTrace = emptyArray() })
            addSuppressed(MessageTrapFailure())
        }
        val className = MessageTrapFailure::class.java.name
        assertEquals("$className\n\tSuppressed: $className\nCaused by: $className", messageFreeStackTrace(failure))
    }

    @Test fun keepsAtMostFortyFramesForEachThrowable() {
        val failures = List(5) { throwableIndex ->
            IllegalStateException("password=secret").apply {
                stackTrace = Array(45) { frameIndex -> StackTraceElement("sample.Cause$throwableIndex", "frame$frameIndex", "File.kt", frameIndex + 1) }
            }
        }
        failures.zipWithNext().forEach { (failure, cause) -> failure.initCause(cause) }
        val trace = messageFreeStackTrace(failures.first())
        assertEquals(200, trace.lines().count { it.startsWith("\tat ") })
        assertEquals(5, trace.lines().count { it == "\t... 5 more" })
        failures.indices.forEach { index ->
            assertTrue(trace.contains("sample.Cause$index.frame39(File.kt:40)"))
            assertFalse(trace.contains("sample.Cause$index.frame40"))
        }
        assertFalse(trace.endsWith('\n'))
    }

    @Test fun includesRootAndOnlyFourCauses() {
        val failures = List(8) { index ->
            IllegalArgumentException("Bearer secret-$index").apply {
                stackTrace = arrayOf(StackTraceElement("sample.Cause$index", "run", "File.kt", index + 1))
            }
        }
        failures.zipWithNext().forEach { (failure, cause) -> failure.initCause(cause) }
        val trace = messageFreeStackTrace(failures.first())
        assertEquals(4, trace.lines().count { it.startsWith("Caused by: ") })
        assertTrue(trace.contains("sample.Cause4.run(File.kt:5)"))
        assertFalse(trace.contains("sample.Cause5"))
        assertTrue(trace.endsWith("\n... more causes omitted"))
    }

    @Test fun keepsOnlyTenSuppressedClassNamesForEachThrowable() {
        val failures = List(2) {
            IllegalStateException("password=secret").apply {
                stackTrace = emptyArray()
                repeat(13) { index ->
                    addSuppressed(UnsupportedOperationException("Bearer suppressed-$index").apply {
                        stackTrace = arrayOf(StackTraceElement("sample.Suppressed", "hidden", "Suppressed.kt", index))
                        initCause(ArithmeticException("hidden-cause"))
                    })
                }
            }
        }
        failures.first().initCause(failures.last())
        val trace = messageFreeStackTrace(failures.first())
        assertEquals(20, trace.lines().count { it == "\tSuppressed: java.lang.UnsupportedOperationException" })
        assertEquals(2, trace.lines().count { it == "\t... 3 more suppressed" })
        assertFalse(trace.contains("sample.Suppressed"))
        assertFalse(trace.contains("ArithmeticException"))
    }

    @Test fun detectsCircularCausesByIdentity() {
        val root = EqualFailure().apply { stackTrace = emptyArray() }
        val cause = EqualFailure().apply { stackTrace = emptyArray() }
        root.initCause(cause)
        cause.initCause(root)
        val className = EqualFailure::class.java.name
        assertEquals("$className\nCaused by: $className\n[circular cause omitted]", messageFreeStackTrace(root))
    }

    @Test fun boundsFinalTraceIncludingTruncationSuffix() {
        val failure = IllegalStateException("password=secret").apply {
            stackTrace = arrayOf(StackTraceElement("x".repeat(40000), "run", "File.kt", 17))
        }
        val trace = messageFreeStackTrace(failure)
        assertEquals(32768, trace.length)
        assertTrue(trace.startsWith("java.lang.IllegalStateException\n\tat "))
        assertTrue(trace.endsWith("\n... stack trace truncated"))
        assertFalse(trace.endsWith('\n'))
        assertFalse(trace.contains("password=secret"))
    }

    @Test fun preservesAnExactlyFittingTraceAndTruncatesWhenAnotherLineWouldOverflow() {
        val fixedCharacters = "java.lang.IllegalStateException\n\tat .run(File.kt:17)".length
        val failure = IllegalStateException("password=secret").apply {
            stackTrace = arrayOf(StackTraceElement("x".repeat(32768 - fixedCharacters), "run", "File.kt", 17))
        }
        val fittingTrace = messageFreeStackTrace(failure)
        assertEquals(32768, fittingTrace.length)
        assertTrue(fittingTrace.endsWith(".run(File.kt:17)"))
        assertFalse(fittingTrace.contains("stack trace truncated"))
        failure.addSuppressed(IllegalArgumentException("Bearer secret"))
        val truncatedTrace = messageFreeStackTrace(failure)
        assertEquals(32768, truncatedTrace.length)
        assertTrue(truncatedTrace.endsWith("\n... stack trace truncated"))
    }

    private class MessageTrapFailure : RuntimeException() {
        override val message: String? get() = error("Throwable message must not be read")
        override fun toString(): String = error("Throwable toString must not be read")
    }

    private class EqualFailure : RuntimeException() {
        override fun equals(other: Any?): Boolean = other is EqualFailure
        override fun hashCode(): Int = 0
    }
}
