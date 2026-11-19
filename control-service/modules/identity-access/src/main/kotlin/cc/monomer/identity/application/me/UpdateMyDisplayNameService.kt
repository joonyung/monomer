package cc.monomer.identity.application.me

import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.User
import cc.monomer.identity.domain.port.TransactionRunner
import cc.monomer.identity.domain.port.UserRepository

class UpdateMyDisplayNameService(
    private val userRepository: UserRepository,
    private val transactionRunner: TransactionRunner,
) {
    sealed interface Outcome {
        data class Updated(val user: User) : Outcome
        data object InvalidDisplayName : Outcome
        data object UserNotFound : Outcome
    }

    suspend fun update(actorUserId: UserId, displayName: String): Outcome {
        val normalizedDisplayName = displayName.trim()
        if (normalizedDisplayName.isBlank()) return Outcome.InvalidDisplayName
        return transactionRunner.run {
            val user = userRepository.findByIdForUpdate(actorUserId)
                ?: return@run Outcome.UserNotFound
            userRepository.updateDisplayName(actorUserId, normalizedDisplayName)
            Outcome.Updated(user.copy(displayName = normalizedDisplayName))
        }
    }
}
