package cc.monomer.control.auth.inbound

import cc.monomer.identity.api.PresentedCredential
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import kotlin.test.assertEquals

class BearerCredentialsTest {
    @Test
    fun `an absent Authorization header is missing`() {
        assertEquals(BearerAuthorization.Missing, BearerCredentials.fromFirstAuthorizationHeader(HttpHeaders()))
    }

    @Test
    fun `a Bearer header presents the token after the prefix`() {
        assertEquals(presented("session-token"), parse("Bearer session-token"))
    }

    @Test
    fun `the scheme is matched case-sensitively`() {
        assertEquals(BearerAuthorization.Malformed, parse("bearer session-token"))
        assertEquals(BearerAuthorization.Malformed, parse("BEARER session-token"))
    }

    @Test
    fun `other schemes and separators are malformed`() {
        assertEquals(BearerAuthorization.Malformed, parse("Basic dXNlcjpwYXNz"))
        assertEquals(BearerAuthorization.Malformed, parse("Bearer"))
        assertEquals(BearerAuthorization.Malformed, parse("Bearersession-token"))
        assertEquals(BearerAuthorization.Malformed, parse("Bearer\tsession-token"))
        assertEquals(BearerAuthorization.Malformed, parse(" Bearer session-token"))
        assertEquals(BearerAuthorization.Malformed, parse(""))
    }

    @Test
    fun `an empty or blank token is malformed`() {
        assertEquals(BearerAuthorization.Malformed, parse("Bearer "))
        assertEquals(BearerAuthorization.Malformed, parse("Bearer    "))
        assertEquals(BearerAuthorization.Malformed, parse("Bearer \t"))
    }

    @Test
    fun `the token is passed through without trimming`() {
        assertEquals(presented(" session-token"), parse("Bearer  session-token"))
        assertEquals(presented("session-token "), parse("Bearer session-token "))
        assertEquals(presented("session token"), parse("Bearer session token"))
    }

    @Test
    fun `only the first Authorization header value is read`() {
        assertEquals(presented("first"), parse("Bearer first", "Bearer second"))
        assertEquals(presented("first"), parse("Bearer first", "Basic dXNlcjpwYXNz"))
        assertEquals(BearerAuthorization.Malformed, parse("Basic dXNlcjpwYXNz", "Bearer second"))
        assertEquals(BearerAuthorization.Malformed, parse("Bearer ", "Bearer second"))
    }

    @Test
    fun `the single-value variant reads a lone Bearer header with the same value rules`() {
        assertEquals(BearerAuthorization.Missing, BearerCredentials.fromSingleAuthorizationHeader(HttpHeaders()))
        assertEquals(presented("ops-token"), parseSingle("Bearer ops-token"))
        assertEquals(presented(" ops-token "), parseSingle("Bearer  ops-token "))
        assertEquals(BearerAuthorization.Malformed, parseSingle("bearer ops-token"))
        assertEquals(BearerAuthorization.Malformed, parseSingle("Basic dXNlcjpwYXNz"))
        assertEquals(BearerAuthorization.Malformed, parseSingle(""))
    }

    @Test
    fun `the single-value variant rejects an empty or blank token`() {
        assertEquals(BearerAuthorization.Malformed, parseSingle("Bearer "))
        assertEquals(BearerAuthorization.Malformed, parseSingle("Bearer    "))
        assertEquals(BearerAuthorization.Malformed, parseSingle("Bearer \t"))
    }

    @Test
    fun `the single-value variant rejects more than one Authorization header value`() {
        assertEquals(BearerAuthorization.Malformed, parseSingle("Bearer ops-token", "Bearer ops-token"))
        assertEquals(BearerAuthorization.Malformed, parseSingle("Bearer first", "Bearer second"))
        assertEquals(BearerAuthorization.Malformed, parseSingle("Bearer first", "Basic dXNlcjpwYXNz"))
    }

    private fun parse(vararg authorization: String): BearerAuthorization =
        BearerCredentials.fromFirstAuthorizationHeader(headers(*authorization))

    private fun parseSingle(vararg authorization: String): BearerAuthorization =
        BearerCredentials.fromSingleAuthorizationHeader(headers(*authorization))

    private fun headers(vararg authorization: String): HttpHeaders =
        HttpHeaders().apply { authorization.forEach { add(HttpHeaders.AUTHORIZATION, it) } }

    private fun presented(token: String): BearerAuthorization = BearerAuthorization.Presented(PresentedCredential(token))
}
