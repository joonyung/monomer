package cc.monomer.control.auth.inbound.graphql

import cc.monomer.identity.application.organization.OrganizationInvitationService
import cc.monomer.identity.domain.id.UserId
import io.mockk.mockk
import java.time.Clock
import kotlinx.coroutines.reactor.mono
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder

class OrganizationInvitationResolverTest {
    private val invitations = mockk<OrganizationInvitationService>()
    private val resolver = OrganizationInvitationResolver(invitations, Clock.systemUTC())
    private val actor = UserId.generate()

    @Test
    fun `a malformed organization id is a bad request instead of a server error`() {
        assertThatThrownBy {
            mono { resolver.organizationMembershipInvitations("not-a-uuid") }.contextWrite(
                ReactiveSecurityContextHolder.withAuthentication(UsernamePasswordAuthenticationToken(actor.toString(), "")),
            ).block()
        }.isInstanceOf(InvalidCanonicalGraphqlInputException::class.java)
    }
}
