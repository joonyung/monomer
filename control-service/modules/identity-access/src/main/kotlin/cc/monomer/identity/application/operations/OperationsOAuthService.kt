package cc.monomer.identity.application.operations

import cc.monomer.identity.api.*
import cc.monomer.identity.application.OpaqueCredentials
import cc.monomer.identity.domain.port.TransactionRunner
import java.net.URI
import java.time.Clock
import java.time.Instant

class OperationsAuthorization(val clientId: String, val redirectUri: String, val resource: String, val challenge: String)
class OperationsStoredCode(val userId: String, val authorization: OperationsAuthorization, val expiresAt: Instant)
class OperationsStoredSession(val userId: String, val clientId: String, val resource: String, val expiresAt: Instant)
interface OperationsOAuthRepository {
    fun browserUser(credential: PresentedCredential, now: Instant): String?
    fun currentUser(userId: String): Boolean
    fun insertCode(hash: String, code: OperationsStoredCode)
    fun lockCode(hash: String): OperationsStoredCode?
    fun deleteCode(hash: String)
    fun insertSession(hash: String, session: OperationsStoredSession)
    fun session(hash: String): OperationsStoredSession?
    fun revoke(hash: String)
}
class OperationsOAuthService(private val repository: OperationsOAuthRepository, private val transactions: TransactionRunner,
    private val clock: Clock, private val allowedUserIds: Set<String>, val resource: String) : OperationsAccessPort {
    fun validate(request: OperationsAuthorization) {
        val uri = runCatching { URI(request.redirectUri) }.getOrNull()
        require(request.clientId == CLIENT_ID && request.resource == resource &&
            request.challenge.matches(Regex("[A-Za-z0-9_-]{43}")) && uri != null && uri.scheme == "http" &&
            uri.host == "127.0.0.1" && uri.port in 1..65535 && uri.rawPath == "/oauth/callback" &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawAuthority == "127.0.0.1:${uri.port}")
    }
    suspend fun browserUser(credential: PresentedCredential): String = transactions.readOnly {
        val user = repository.browserUser(credential, clock.instant()) ?: throw OperationsUnauthenticatedException()
        checkOperator(user)
        user
    }
    suspend fun authorize(credential: PresentedCredential, request: OperationsAuthorization): String {
        validate(request)
        return transactions.run {
            val now = clock.instant()
            val user = repository.browserUser(credential, now) ?: throw OperationsUnauthenticatedException()
            checkOperator(user)
            val code = OpaqueCredentials.generate()
            repository.insertCode(OpaqueCredentials.hash(code), OperationsStoredCode(user, request, now.plusSeconds(300)))
            code
        }
    }
    suspend fun exchange(code: String, verifier: String, request: OperationsAuthorization): String {
        require(verifier.matches(Regex("[A-Za-z0-9._~-]{43,128}")))
        validate(request)
        return transactions.run {
            val stored = repository.lockCode(OpaqueCredentials.hash(code)) ?: throw OperationsUnauthenticatedException()
            val expected = stored.authorization
            if (stored.expiresAt <= clock.instant() || expected.clientId != request.clientId ||
                expected.redirectUri != request.redirectUri || expected.resource != request.resource ||
                !OpaqueCredentials.matchesS256CodeChallenge(verifier, expected.challenge)) throw OperationsUnauthenticatedException()
            checkOperator(stored.userId)
            repository.deleteCode(OpaqueCredentials.hash(code))
            val token = OpaqueCredentials.generate()
            repository.insertSession(OpaqueCredentials.hash(token), OperationsStoredSession(stored.userId, CLIENT_ID, resource, clock.instant().plusSeconds(3600)))
            token
        }
    }
    override suspend fun authenticate(credential: PresentedCredential): OperationsAccess = transactions.readOnly {
        val stored = repository.session(OpaqueCredentials.hash(credential.value)) ?: throw OperationsUnauthenticatedException()
        if (stored.expiresAt <= clock.instant() || stored.clientId != CLIENT_ID || stored.resource != resource) throw OperationsUnauthenticatedException()
        checkOperator(stored.userId)
        OperationsAccess(stored.userId, stored.clientId, stored.expiresAt)
    }
    override suspend fun revoke(credential: PresentedCredential) { transactions.run { repository.revoke(OpaqueCredentials.hash(credential.value)) } }
    private fun checkOperator(userId: String) {
        if (userId !in allowedUserIds || !repository.currentUser(userId)) throw OperationsForbiddenException()
    }
    companion object {
        const val CLIENT_ID = "monomer-operations-local"
    }
}
