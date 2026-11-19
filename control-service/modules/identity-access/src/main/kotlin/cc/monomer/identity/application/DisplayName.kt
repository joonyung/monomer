package cc.monomer.identity.application

class InvalidDisplayNameException(message: String) : RuntimeException(message)

internal fun normalizedDisplayName(displayName: String, subject: String): String {
    val normalized = displayName.trim()
    if (normalized.isBlank()) throw InvalidDisplayNameException("$subject displayName must not be blank")
    return normalized
}
