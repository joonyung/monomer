package cc.monomer.identity.domain.model

import cc.monomer.identity.domain.id.LoginIdentityId
import cc.monomer.identity.domain.id.UserId
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LoginIdentityProviderTest {
    @Test
    fun `provider is a stable display string derived from the stored identity`() {
        assertThat(identity(AuthenticationProtocol.OIDC, "https://accounts.google.com").providerDisplayName).isEqualTo("Google")
        assertThat(identity(AuthenticationProtocol.DEVELOPMENT, "urn:monomer:auth:dev").providerDisplayName).isEqualTo("Local development")
        assertThat(identity(AuthenticationProtocol.OIDC, "https://login.example.com/tenant").providerDisplayName).isEqualTo("login.example.com")
        assertThat(identity(AuthenticationProtocol.OIDC, "urn:example:issuer").providerDisplayName).isEqualTo("urn:example:issuer")
    }

    private fun identity(protocol: AuthenticationProtocol, namespace: String) = LoginIdentity(
        id = LoginIdentityId.generate(),
        userId = UserId.generate(),
        key = LoginIdentityKey(protocol, namespace, "subject"),
        linkedVia = LoginIdentityLinkedVia.SIGNUP,
        linkedAt = Instant.EPOCH,
    )
}
