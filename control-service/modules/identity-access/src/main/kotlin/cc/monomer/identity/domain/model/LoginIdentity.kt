package cc.monomer.identity.domain.model

import cc.monomer.identity.domain.id.LoginIdentityId
import cc.monomer.identity.domain.id.UserId
import java.net.URI
import java.time.Instant

enum class AuthenticationProtocol(val databaseValue: String) {
    OIDC("oidc"),
    DEVELOPMENT("development"),
    ;

    companion object {
        fun fromDatabaseValue(value: String): AuthenticationProtocol = entries.first { it.databaseValue == value }
    }
}

enum class LoginIdentityLinkedVia(val databaseValue: String) {
    SIGNUP("signup"),
    EXPLICIT("explicit"),
    SSO_JIT("sso_jit"),
    ;

    companion object {
        fun fromDatabaseValue(value: String): LoginIdentityLinkedVia = entries.first { it.databaseValue == value }
    }
}

data class LoginIdentityKey(
    val protocol: AuthenticationProtocol,
    val namespace: String,
    val subject: String,
) {
    init {
        require(namespace.isNotBlank()) { "login identity namespace must not be blank" }
        require(subject.isNotBlank()) { "login identity subject must not be blank" }
    }
}

data class LoginIdentity(
    val id: LoginIdentityId,
    val userId: UserId,
    val key: LoginIdentityKey,
    val linkedVia: LoginIdentityLinkedVia,
    val linkedAt: Instant,
) {
    val providerDisplayName: String
        get() = when {
            key.protocol == AuthenticationProtocol.OIDC && key.namespace == GOOGLE_OIDC_ISSUER -> "Google"
            key.protocol == AuthenticationProtocol.DEVELOPMENT -> "Local development"
            else -> runCatching { URI(key.namespace).host }.getOrNull()?.takeIf(String::isNotBlank) ?: key.namespace
        }

    private companion object {
        const val GOOGLE_OIDC_ISSUER = "https://accounts.google.com"
    }
}

enum class EmailEvidence(val databaseValue: String) {
    AUTHORITATIVE("authoritative"),
    NON_AUTHORITATIVE("non_authoritative"),
    CHALLENGE_VERIFIED("challenge_verified"),
    ;

    companion object {
        fun fromDatabaseValue(value: String): EmailEvidence = entries.first { it.databaseValue == value }
    }
}

data class AuthenticationAssertion(
    val identityKey: LoginIdentityKey,
    val authenticationSourceRef: String,
    val authenticationIssuer: String,
    val assertedEmail: String,
    val assertedEmailVerified: Boolean,
    val emailEvidence: EmailEvidence,
    val displayName: String,
    val upstreamAuthenticatedAt: Instant?,
    val authenticationContext: String?,
    val authenticationMethods: List<String>,
    val upstreamSessionId: String?,
) {
    init {
        require(authenticationSourceRef.isNotBlank()) { "authentication source must not be blank" }
        require(authenticationIssuer.isNotBlank()) { "authentication issuer must not be blank" }
        require(assertedEmail.isNotBlank()) { "asserted email must not be blank" }
        require(displayName.isNotBlank()) { "display name must not be blank" }
    }

    val normalizedEmail: String get() = assertedEmail.trim().lowercase()
}
