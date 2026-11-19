package cc.monomer.control.auth.inbound.graphql

import cc.monomer.identity.application.me.MeUseCase
import cc.monomer.identity.application.me.MyLoginIdentitiesService
import cc.monomer.identity.domain.id.LoginIdentityId
import cc.monomer.identity.domain.model.AuthenticationProtocol
import cc.monomer.identity.domain.model.LoginIdentity
import cc.monomer.identity.domain.model.LoginIdentityKey
import cc.monomer.identity.domain.model.LoginIdentityLinkedVia
import cc.monomer.identity.domain.port.LoginIdentityQuery
import java.time.Instant
import cc.monomer.identity.application.me.UpdateMyDisplayNameService
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.User
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.reactor.mono
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder

class MeResolverTest {
    private val updates = mockk<UpdateMyDisplayNameService>()
    private val loginIdentities = mockk<LoginIdentityQuery>()
    private val resolver = MeResolver(mockk<MeUseCase>(), updates, MyLoginIdentitiesService(loginIdentities))
    private val actor = UserId.generate()

    @Test
    fun `mutation uses authenticated self and returns canonical user`() {
        val user = User(actor, "self@example.test", "사용자")
        coEvery { updates.update(actor, " 사용자 ") } returns UpdateMyDisplayNameService.Outcome.Updated(user)
        val result = authenticated { resolver.updateMyDisplayName(" 사용자 ") }
        assertThat(result).isEqualTo(UserView(actor.toString(), user.primaryEmail, user.displayName))
        coVerify(exactly = 1) { updates.update(actor, " 사용자 ") }
    }

    @Test
    fun `missing and unauthenticated contexts cannot mutate`() {
        assertThatThrownBy { mono { resolver.updateMyDisplayName("Name") }.block() }
            .isInstanceOf(AuthenticationCredentialsNotFoundException::class.java)
        assertThatThrownBy {
            mono { resolver.updateMyDisplayName("Name") }.contextWrite(
                ReactiveSecurityContextHolder.withAuthentication(UsernamePasswordAuthenticationToken(actor.toString(), "")),
            ).block()
        }.isInstanceOf(AuthenticationCredentialsNotFoundException::class.java)
        coVerify(exactly = 0) { updates.update(any(), any()) }
    }

    @Test
    fun `invalid name maps to existing GraphQL bad request boundary`() {
        coEvery { updates.update(actor, " ") } returns UpdateMyDisplayNameService.Outcome.InvalidDisplayName
        assertThatThrownBy { authenticated { resolver.updateMyDisplayName(" ") } }
            .isInstanceOf(InvalidCanonicalGraphqlInputException::class.java)
    }

    @Test
    fun `login identities are read only for the authenticated self`() {
        val linkedAt = Instant.parse("2026-10-01T00:00:00Z")
        coEvery { loginIdentities.listByUser(actor) } returns listOf(LoginIdentity(LoginIdentityId.generate(), actor,
            LoginIdentityKey(AuthenticationProtocol.OIDC, "https://accounts.google.com", "subject"), LoginIdentityLinkedVia.SIGNUP, linkedAt))
        val me = MeView(UserView(actor.toString(), "self@example.test", "Self"), emptyList())
        assertThat(authenticated { resolver.loginIdentities(me) })
            .containsExactly(LoginIdentityView(provider = "Google", linkedAt = "2026-10-01T00:00:00Z"))
        val otherMe = me.copy(user = me.user.copy(id = UserId.generate().toString()))
        assertThatThrownBy { authenticated { resolver.loginIdentities(otherMe) } }.isInstanceOf(IllegalStateException::class.java)
        coVerify(exactly = 1) { loginIdentities.listByUser(any()) }
    }

    private fun <T : Any> authenticated(block: suspend () -> T): T? = mono { block() }
        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
            UsernamePasswordAuthenticationToken(actor.toString(), "", emptyList()),
        )).block()
}
