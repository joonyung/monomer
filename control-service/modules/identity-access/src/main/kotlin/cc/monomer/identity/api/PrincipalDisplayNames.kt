@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.identity.api

import kotlin.uuid.Uuid

interface PrincipalDisplayNames {
    suspend fun read(userIds: Set<Uuid>): Map<Uuid, String>
}
