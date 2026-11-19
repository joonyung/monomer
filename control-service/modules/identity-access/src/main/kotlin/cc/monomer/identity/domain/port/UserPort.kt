package cc.monomer.identity.domain.port

import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.User

interface UserQuery {
    suspend fun find(id: UserId): User?
}

interface UserRepository {
    fun findByIdForUpdate(userId: UserId): User?
    fun findByPrimaryEmailForUpdate(primaryEmail: String): User?
    fun insertIfPrimaryEmailAbsent(user: User): Boolean
    fun updateDisplayName(userId: UserId, displayName: String)
    fun delete(userId: UserId)
}
