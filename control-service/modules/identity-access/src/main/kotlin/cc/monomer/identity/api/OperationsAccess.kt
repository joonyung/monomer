package cc.monomer.identity.api

import java.time.Instant

interface OperationsAccessPort {
    suspend fun authenticate(credential: PresentedCredential): OperationsAccess
    suspend fun revoke(credential: PresentedCredential)
}
class OperationsAccess internal constructor(val userId: String, val clientId: String, val expiresAt: Instant)
class OperationsUnauthenticatedException : RuntimeException("Operations credential required")
class OperationsForbiddenException : RuntimeException("Operations access denied")
