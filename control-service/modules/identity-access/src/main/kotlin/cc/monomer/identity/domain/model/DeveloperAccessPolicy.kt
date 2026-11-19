package cc.monomer.identity.domain.model

class DeveloperAccessPolicy(val mode: Mode, allowedEmails: Set<String>) {
    enum class Mode { OPEN, RESTRICTED }

    val isOpen: Boolean get() = mode == Mode.OPEN
    private val allowedEmails = allowedEmails.map { it.trim().lowercase() }.toSet()

    init {
        require(this.allowedEmails.all { EMAIL.matches(it) }) { "Developer emails must be exact email addresses" }
    }

    fun allows(assertion: AuthenticationAssertion): Boolean = isOpen || (
        assertion.identityKey.protocol == AuthenticationProtocol.OIDC &&
            assertion.identityKey.namespace == "https://accounts.google.com" &&
            allowsGoogleEvidence(assertion.authenticationSourceRef, assertion.authenticationIssuer,
                assertion.assertedEmail, assertion.assertedEmailVerified, assertion.emailEvidence)
        )

    fun allows(session: Session): Boolean = isOpen || (
        session.protocol == AuthenticationProtocol.OIDC &&
            allowsGoogleEvidence(session.authenticationSourceRef, session.authenticationIssuer,
                session.assertedEmail, session.assertedEmailVerified, session.emailEvidence)
        )

    private fun allowsGoogleEvidence(source: String, issuer: String, email: String, verified: Boolean, evidence: EmailEvidence): Boolean =
        source == "builtin/google" && issuer in setOf("https://accounts.google.com", "accounts.google.com") &&
            verified && evidence == EmailEvidence.AUTHORITATIVE && email.trim().lowercase() in allowedEmails

    companion object {
        private val EMAIL = Regex("[^\\s@*]+@[^\\s@*]+\\.[^\\s@*]+")
    }
}

class DeveloperAccessDeniedException : RuntimeException("This Google identity is not admitted to this development environment")
