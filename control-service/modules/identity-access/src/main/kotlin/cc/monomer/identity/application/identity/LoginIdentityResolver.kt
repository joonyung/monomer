package cc.monomer.identity.application.identity

import cc.monomer.identity.domain.id.LoginIdentityId
import cc.monomer.identity.domain.model.AuthenticationAssertion
import cc.monomer.identity.domain.model.AuthenticationProtocol
import cc.monomer.identity.domain.model.EmailEvidence
import cc.monomer.identity.domain.model.LoginIdentity
import cc.monomer.identity.domain.model.LoginIdentityLinkedVia
import cc.monomer.identity.domain.model.User
import cc.monomer.identity.domain.port.LoginIdentityRepository
import cc.monomer.identity.domain.port.UserRepository
import java.time.Clock

class LoginIdentityResolver(
    private val normalizedEmailUserResolver: NormalizedEmailUserResolver,
    private val userRepository: UserRepository,
    private val loginIdentityRepository: LoginIdentityRepository,
    private val clock: Clock,
) {
    data class Resolution(
        val user: User,
        val loginIdentity: LoginIdentity,
        val newUser: Boolean,
    )

    fun resolve(assertion: AuthenticationAssertion): Resolution {
        val existingIdentity = loginIdentityRepository.find(assertion.identityKey)
        if (existingIdentity != null) {
            val user = userRepository.findByIdForUpdate(existingIdentity.userId)
                ?: error("login identity ${existingIdentity.id} has no user")
            return Resolution(user, existingIdentity, newUser = false)
        }

        val mayBootstrapByEmail = assertion.identityKey.protocol == AuthenticationProtocol.DEVELOPMENT ||
            assertion.emailEvidence == EmailEvidence.AUTHORITATIVE ||
            assertion.emailEvidence == EmailEvidence.CHALLENGE_VERIFIED
        if (!mayBootstrapByEmail) throw EmailChallengeRequiredException()

        val userResolution = normalizedEmailUserResolver.lockOrCreate(
            normalizedEmail = assertion.normalizedEmail,
            displayName = assertion.displayName,
        )

        loginIdentityRepository.find(assertion.identityKey)?.let { winner ->
            removeLosingCandidate(userResolution, winner)
            return Resolution(
                user = lockedUser(winner),
                loginIdentity = winner,
                newUser = false,
            )
        }

        if (!userResolution.created) throw IdentityLinkRequiredException()
        val linkedVia = LoginIdentityLinkedVia.SIGNUP
        val identity = LoginIdentity(
            id = LoginIdentityId.generate(),
            userId = userResolution.user.userId,
            key = assertion.identityKey,
            linkedVia = linkedVia,
            linkedAt = clock.instant(),
        )
        if (!loginIdentityRepository.insertIfAbsent(identity)) {
            val winner = loginIdentityRepository.find(assertion.identityKey)
                ?: error("login identity uniqueness winner was not visible")
            removeLosingCandidate(userResolution, winner)
            return Resolution(
                user = lockedUser(winner),
                loginIdentity = winner,
                newUser = false,
            )
        }

        return Resolution(userResolution.user, identity, newUser = true)
    }

    private fun lockedUser(loginIdentity: LoginIdentity): User =
        userRepository.findByIdForUpdate(loginIdentity.userId)
            ?: error("login identity ${loginIdentity.id} has no user")

    private fun removeLosingCandidate(
        userResolution: NormalizedEmailUserResolver.Resolution,
        winningIdentity: LoginIdentity,
    ) {
        if (userResolution.created && winningIdentity.userId != userResolution.user.userId) {
            userRepository.delete(userResolution.user.userId)
        }
    }
}

class IdentityLinkRequiredException : RuntimeException("An existing user requires explicit identity linking")

class EmailChallengeRequiredException : RuntimeException("Email ownership challenge is required")
