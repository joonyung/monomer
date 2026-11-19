package cc.monomer.identity.domain.model

import cc.monomer.identity.domain.id.UserId

data class User(
    val userId: UserId,
    val primaryEmail: String,
    val displayName: String,
)
