package cc.monomer.identity.domain.port

import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.LoginIdentity
import cc.monomer.identity.domain.model.LoginIdentityKey

interface LoginIdentityRepository {
    fun find(key: LoginIdentityKey): LoginIdentity?
    fun insertIfAbsent(loginIdentity: LoginIdentity): Boolean
}

interface LoginIdentityQuery {
    suspend fun listByUser(userId: UserId): List<LoginIdentity>
}
