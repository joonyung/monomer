package cc.monomer.control.observability

import java.util.Collections
import java.util.IdentityHashMap

private const val MAX_STACK_FRAMES = 40
private const val MAX_THROWABLES = 5
private const val MAX_SUPPRESSED = 10
private const val MAX_STACK_TRACE_CHARACTERS = 32768
private const val TRUNCATION_SUFFIX = "\n... stack trace truncated"

internal fun messageFreeStackTrace(failure: Throwable): String {
    val trace = StringBuilder()
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = failure
    var throwableCount = 0
    var truncated = false

    fun appendLine(vararg parts: String): Boolean {
        if (trace.isNotEmpty()) {
            if (trace.length == MAX_STACK_TRACE_CHARACTERS) {
                truncated = true
                return false
            }
            trace.append('\n')
        }
        for (part in parts) {
            val remaining = MAX_STACK_TRACE_CHARACTERS - trace.length
            if (part.length > remaining) {
                trace.append(part, 0, remaining)
                truncated = true
                return false
            }
            trace.append(part)
        }
        return true
    }

    while (current != null && !truncated) {
        if (!visited.add(current)) {
            appendLine("[circular cause omitted]")
            break
        }
        if (throwableCount == MAX_THROWABLES) {
            appendLine("... more causes omitted")
            break
        }
        if (!appendLine(if (throwableCount == 0) "" else "Caused by: ", current.javaClass.name)) break
        val frames = current.stackTrace
        for (frame in frames.take(MAX_STACK_FRAMES)) {
            val fileName = frame.fileName
            val location = when {
                frame.isNativeMethod -> "Native Method"
                fileName == null -> "Unknown Source"
                frame.lineNumber >= 0 -> "$fileName:${frame.lineNumber}"
                else -> fileName
            }
            if (!appendLine("\tat ", frame.className, ".", frame.methodName, "(", location, ")")) break
        }
        if (truncated) break
        if (frames.size > MAX_STACK_FRAMES && !appendLine("\t... ", (frames.size - MAX_STACK_FRAMES).toString(), " more")) break
        val suppressed = current.suppressed
        for (suppressedFailure in suppressed.take(MAX_SUPPRESSED)) {
            if (!appendLine("\tSuppressed: ", suppressedFailure.javaClass.name)) break
        }
        if (truncated) break
        if (suppressed.size > MAX_SUPPRESSED && !appendLine("\t... ", (suppressed.size - MAX_SUPPRESSED).toString(), " more suppressed")) break
        throwableCount++
        current = current.cause
    }
    return if (truncated) trace.substring(0, MAX_STACK_TRACE_CHARACTERS - TRUNCATION_SUFFIX.length) + TRUNCATION_SUFFIX else trace.toString()
}

class MessageFreeStackTracePrinter : org.springframework.boot.logging.StackTracePrinter {
    override fun printStackTrace(throwable: Throwable, out: Appendable) {
        out.append(messageFreeStackTrace(throwable))
    }
}

class MessageFreeThrowableConverter : ch.qos.logback.classic.pattern.ThrowableHandlingConverter() {
    override fun convert(event: ch.qos.logback.classic.spi.ILoggingEvent): String {
        val proxy = event.throwableProxy as? ch.qos.logback.classic.spi.ThrowableProxy ?: return ""
        return messageFreeStackTrace(proxy.throwable) + "\n"
    }
}
