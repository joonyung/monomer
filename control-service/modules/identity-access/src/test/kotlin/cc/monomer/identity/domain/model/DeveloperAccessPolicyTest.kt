package cc.monomer.identity.domain.model

import kotlin.test.*
import org.junit.jupiter.api.Test

class DeveloperAccessPolicyTest {
    private val assertion = AuthenticationAssertion(
        LoginIdentityKey(AuthenticationProtocol.OIDC, "https://accounts.google.com", "subject"),
        "builtin/google", "https://accounts.google.com", "Developer@gmail.com", true,
        EmailEvidence.AUTHORITATIVE, "Developer", null, null, emptyList(), null,
    )
    private val restricted = DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.RESTRICTED, setOf("developer@gmail.com"))

    @Test fun `only exact verified authoritative Google email is admitted`() {
        assertTrue(restricted.allows(assertion))
        assertFalse(restricted.allows(assertion.copy(assertedEmail = "another@gmail.com")))
        assertFalse(restricted.allows(assertion.copy(assertedEmailVerified = false)))
        assertFalse(restricted.allows(assertion.copy(emailEvidence = EmailEvidence.NON_AUTHORITATIVE)))
        assertFalse(restricted.allows(assertion.copy(authenticationSourceRef = "other")))
        assertFalse(restricted.allows(assertion.copy(identityKey = assertion.identityKey.copy(namespace = "https://other.test"))))
        assertFalse(restricted.allows(assertion.copy(authenticationIssuer = "https://other.test")))
        assertFalse(restricted.allows(assertion.copy(identityKey = assertion.identityKey.copy(protocol = AuthenticationProtocol.DEVELOPMENT))))
    }

    @Test fun `empty restriction denies while open preserves existing admission`() {
        assertFalse(DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.RESTRICTED, emptySet()).allows(assertion))
        assertTrue(DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.OPEN, emptySet()).allows(assertion.copy(assertedEmailVerified = false)))
        listOf("", "gmail.com", "*@gmail.com", "a b@gmail.com").forEach { address ->
            assertFailsWith<IllegalArgumentException> { DeveloperAccessPolicy(DeveloperAccessPolicy.Mode.RESTRICTED, setOf(address)) }
        }
    }
}
