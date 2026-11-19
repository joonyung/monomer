package cc.monomer.identity.outbound.jwt

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RefreshTokenFactoryTest {

    private val factory = RefreshTokenFactory()

    @Test
    fun `hash is stable for the same raw token`() {
        val generated = factory.generate()
        assertThat(factory.hash(generated.raw)).isEqualTo(generated.hash)
        assertThat(factory.hash(generated.raw)).isEqualTo(factory.hash(generated.raw))
    }

    @Test
    fun `raw token is url-safe base64 without padding`() {
        val raw = factory.generate().raw
        assertThat(raw).matches("[A-Za-z0-9_-]+")
        assertThat(raw).doesNotContain("=", "+", "/")
    }

    @Test
    fun `stored hash never equals the raw token and is sha-256 hex`() {
        val generated = factory.generate()
        assertThat(generated.hash).isNotEqualTo(generated.raw)
        assertThat(generated.hash).matches("[0-9a-f]{64}")
    }

    @Test
    fun `distinct generations produce distinct raw tokens and hashes`() {
        val first = factory.generate()
        val second = factory.generate()
        assertThat(first.raw).isNotEqualTo(second.raw)
        assertThat(first.hash).isNotEqualTo(second.hash)
    }
}
