package cc.monomer.identity.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpaqueCredentialsTest {
    private val rfc7636Verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    private val rfc7636Challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

    @Test
    fun `generated credentials are 32 random bytes in url-safe base64 without padding`() {
        val first = OpaqueCredentials.generate()
        val second = OpaqueCredentials.generate()
        assertThat(first).matches("[A-Za-z0-9_-]{43}")
        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `hash is the lowercase sha-256 hex of the utf-8 value`() {
        assertThat(OpaqueCredentials.hash("abc"))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun `s256 code challenge follows the RFC 7636 example and matching is exact`() {
        assertThat(OpaqueCredentials.s256CodeChallenge(rfc7636Verifier)).isEqualTo(rfc7636Challenge)
        assertThat(OpaqueCredentials.matchesS256CodeChallenge(rfc7636Verifier, rfc7636Challenge)).isTrue()
        assertThat(OpaqueCredentials.matchesS256CodeChallenge(rfc7636Verifier, rfc7636Challenge.dropLast(1) + "d")).isFalse()
        assertThat(OpaqueCredentials.matchesS256CodeChallenge(rfc7636Verifier, rfc7636Challenge.dropLast(1))).isFalse()
        assertThat(OpaqueCredentials.matchesS256CodeChallenge(rfc7636Verifier, rfc7636Challenge.dropLast(1) + "é")).isFalse()
    }
}
