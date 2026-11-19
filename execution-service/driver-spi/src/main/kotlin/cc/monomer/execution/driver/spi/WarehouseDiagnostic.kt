package cc.monomer.execution.driver.spi

fun warehouseDiagnostic(message: String?, credential: MaterializedCredential?, fallback: String): String {
    var diagnostic = message?.trim()?.takeIf(String::isNotBlank)?.replace(DIAGNOSTIC_URL, "[redacted URL]")
        ?: return fallback
    if (SENSITIVE_DIAGNOSTIC.containsMatchIn(diagnostic)) return fallback
    credential?.fieldNames()?.forEach { name ->
        val containsOpaqueSecret = credential.useSecret(name) { characters ->
            val secret = characters.concatToString()
            if (secret.isNotEmpty() && diagnostic.contains(secret)) {
                true
            } else {
                if (name == "service_account_json") {
                    secret.split(SECRET_DELIMITERS).filter { it.length >= 8 }.distinct()
                        .sortedByDescending(String::length)
                        .forEach { fragment -> diagnostic = diagnostic.replace(fragment, "[redacted]") }
                }
                false
            }
        }
        if (containsOpaqueSecret) return fallback
    }
    return diagnostic.filter { it == '\n' || it == '\t' || !it.isISOControl() }.take(4096)
        .takeIf(String::isNotBlank) ?: fallback
}

private val SECRET_DELIMITERS = Regex("[\\s\\\"{},:]+|\\\\n")
private val SENSITIVE_DIAGNOSTIC = Regex(
    "(?i)(?:password|passwd|pwd|secret|token|private[_ -]?key|authorization|credential)\\s*[\\\"']?\\s*[:=]|" +
        "(?:bearer|basic)\\s+[a-zA-Z0-9+/_.=-]+|-----BEGIN .*PRIVATE KEY-----",
)

private val DIAGNOSTIC_URL = Regex("(?i)[a-z][a-z0-9+.-]*://[^\\s]+")
