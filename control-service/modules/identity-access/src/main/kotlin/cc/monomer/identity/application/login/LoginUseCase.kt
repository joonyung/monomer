package cc.monomer.identity.application.login

import cc.monomer.identity.domain.model.AuthenticationAssertion
import cc.monomer.identity.domain.model.LoginIdentity
import cc.monomer.identity.domain.model.User

interface LoginUseCase {
    suspend fun login(assertion: AuthenticationAssertion, firstSignIn: FirstSignIn): Response

    // A brand-new user's first sign-in either starts their own Organization or arrives through an
    // invitation; an invitee joins the inviting Organization instead of getting a personal one.
    enum class FirstSignIn { CREATE_PERSONAL_ORGANIZATION, JOIN_BY_INVITATION }

    data class Response(
        val user: User,
        val loginIdentity: LoginIdentity,
        val newUser: Boolean,
    )
}
