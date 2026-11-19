package cc.monomer.identity.domain.model

import java.time.Instant

data class IssuedToken(
    val serialized: String,
    val expiresAt: Instant,
) {
    override fun toString(): String = "IssuedToken(serialized=[REDACTED], expiresAt=$expiresAt)"
}

data class GeneratedRefreshToken(
    val raw: String,
    val hash: String,
) {
    override fun toString(): String = "GeneratedRefreshToken(raw=[REDACTED], hash=$hash)"
}
