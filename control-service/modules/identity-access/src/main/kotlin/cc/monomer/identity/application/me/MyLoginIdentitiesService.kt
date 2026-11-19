package cc.monomer.identity.application.me

import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.LoginIdentity
import cc.monomer.identity.domain.port.LoginIdentityQuery

class MyLoginIdentitiesService(
    private val loginIdentityQuery: LoginIdentityQuery,
) {
    suspend fun list(actorUserId: UserId): List<LoginIdentity> = loginIdentityQuery.listByUser(actorUserId)
}
