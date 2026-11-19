package cc.monomer.identity.application.identity

import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.User
import cc.monomer.identity.domain.port.UserRepository

class NormalizedEmailUserResolver(
    private val userRepository: UserRepository,
) {
    data class Resolution(
        val user: User,
        val created: Boolean,
    )

    fun lockOrCreate(normalizedEmail: String, displayName: String): Resolution {
        require(normalizedEmail == normalizedEmail.trim().lowercase()) {
            "email resolver requires a normalized email"
        }
        userRepository.findByPrimaryEmailForUpdate(normalizedEmail)?.let {
            return Resolution(user = it, created = false)
        }

        val candidate = User(
            userId = UserId.generate(),
            primaryEmail = normalizedEmail,
            displayName = displayName,
        )
        if (userRepository.insertIfPrimaryEmailAbsent(candidate)) {
            return Resolution(user = candidate, created = true)
        }
        val winner = userRepository.findByPrimaryEmailForUpdate(normalizedEmail)
            ?: error("email uniqueness winner was not visible")
        return Resolution(user = winner, created = false)
    }
}
