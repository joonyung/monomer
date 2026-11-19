package cc.monomer.control.auth

import cc.monomer.control.auth.inbound.oauth.GoogleOAuthProperties
import kotlin.test.Test
import kotlin.test.assertFalse

class AuthSecretPropertiesRedactionTest {
    @Test
    fun `authentication properties never print secrets`() {
        val google = GoogleOAuthProperties(clientId = "client-id", clientSecret = "google-client-secret")
        val jwt = AuthJwtProperties("issuer", "audience", 900, 86_400, "-----BEGIN PRIVATE KEY-----jwt-key", "")

        assertFalse("google-client-secret" in google.toString())
        assertFalse("jwt-key" in jwt.toString())
    }
}
