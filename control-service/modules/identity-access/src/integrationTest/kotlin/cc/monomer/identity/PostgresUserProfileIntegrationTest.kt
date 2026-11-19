package cc.monomer.identity

import cc.monomer.identity.application.identity.LoginIdentityResolver
import cc.monomer.identity.application.identity.NormalizedEmailUserResolver
import cc.monomer.identity.application.me.MeService
import cc.monomer.identity.application.me.MeUseCase
import cc.monomer.identity.application.me.MyLoginIdentitiesService
import cc.monomer.identity.application.me.UpdateMyDisplayNameService
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.LoginIdentityId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.AuthenticationAssertion
import cc.monomer.identity.domain.model.AuthenticationProtocol
import cc.monomer.identity.domain.model.EmailEvidence
import cc.monomer.identity.domain.model.LoginIdentity
import cc.monomer.identity.domain.model.LoginIdentityKey
import cc.monomer.identity.domain.model.LoginIdentityLinkedVia
import cc.monomer.identity.domain.model.User
import cc.monomer.identity.outbound.postgres.PostgresOrganizationQuery
import cc.monomer.identity.outbound.postgres.PostgresLoginIdentityQuery
import cc.monomer.identity.outbound.postgres.PostgresLoginIdentityRepository
import cc.monomer.identity.outbound.postgres.PostgresUserQuery
import cc.monomer.identity.outbound.postgres.PostgresUserRepository
import cc.monomer.identity.outbound.postgres.SpringTransactionRunner
import java.time.Clock
import kotlin.uuid.toJavaUuid
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

class PostgresUserProfileIntegrationTest {
    @Test
    fun `profile persists independently of Organization and other user and survives Google login`() = runBlocking<Unit> {
        TargetIdentityPostgres.dataSource.connection.use {
            TargetIdentityPostgres.applyCanonicalSchemaTwice(it, javaClass.classLoader)
        }
        val jdbc = NamedParameterJdbcTemplate(TargetIdentityPostgres.dataSource)
        val manager = DataSourceTransactionManager(TargetIdentityPostgres.dataSource)
        val transactions = SpringTransactionRunner(
            TransactionTemplate(manager),
            TransactionTemplate(manager).apply { isReadOnly = true }, blockingDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        val users = PostgresUserRepository(jdbc)
        val identities = PostgresLoginIdentityRepository(jdbc)
        val user = User(UserId.generate(), "self@example.test", "Google Original")
        val other = User(UserId.generate(), "other@example.test", "Other User")
        val organizationId = OrganizationId.generate()
        val clock = Clock.systemUTC()
        val googleKey = LoginIdentityKey(AuthenticationProtocol.OIDC, "https://accounts.google.com", user.userId.toString())
        transactions.run {
            check(users.insertIfPrimaryEmailAbsent(user))
            check(users.insertIfPrimaryEmailAbsent(other))
            check(identities.insertIfAbsent(LoginIdentity(LoginIdentityId.generate(), user.userId, googleKey,
                LoginIdentityLinkedVia.SIGNUP, clock.instant())))
            val parameters = mapOf("organization" to organizationId.value.toJavaUuid(), "user" to user.userId.value.toJavaUuid())
            jdbc.update("INSERT INTO identity_runtime.organizations(id,display_name) VALUES(:organization,'Personal Organization')", parameters)
            jdbc.update("INSERT INTO identity_runtime.organization_members(organization_id,user_id,role) VALUES(:organization,:user,'ADMIN')", parameters)
        }

        val update = UpdateMyDisplayNameService(users, transactions)
        assertThat(update.update(user.userId, "  김 사용자  "))
            .isEqualTo(UpdateMyDisplayNameService.Outcome.Updated(user.copy(displayName = "김 사용자")))
        assertThat(update.update(user.userId, " \t\n"))
            .isEqualTo(UpdateMyDisplayNameService.Outcome.InvalidDisplayName)
        val otherKey = LoginIdentityKey(AuthenticationProtocol.OIDC, "https://accounts.google.com", other.userId.toString())
        val linkedAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        transactions.run {
            check(identities.insertIfAbsent(LoginIdentity(LoginIdentityId.generate(), other.userId, otherKey, LoginIdentityLinkedVia.SIGNUP, linkedAt)))
        }
        val ownIdentities = MyLoginIdentitiesService(PostgresLoginIdentityQuery(jdbc, transactions)).list(user.userId)
        assertThat(ownIdentities.map { it.key }).containsExactly(googleKey)
        assertThat(ownIdentities.single().providerDisplayName).isEqualTo("Google")
        assertThat(MyLoginIdentitiesService(PostgresLoginIdentityQuery(jdbc, transactions)).list(other.userId).single().linkedAt).isEqualTo(linkedAt)
        val userQuery = PostgresUserQuery(jdbc, transactions)
        val me = MeService(userQuery, PostgresOrganizationQuery(jdbc, transactions)).me(MeUseCase.Command(user.userId))
        assertThat(me.user).isEqualTo(user.copy(displayName = "김 사용자"))
        assertThat(me.organizationMemberships.single().organization.displayName).isEqualTo("Personal Organization")
        assertThat(userQuery.find(other.userId)).isEqualTo(other)

        val login = LoginIdentityResolver(NormalizedEmailUserResolver(users), users, identities, clock)
        val assertion = AuthenticationAssertion(
            identityKey = googleKey,
            authenticationSourceRef = "builtin/google",
            authenticationIssuer = "https://accounts.google.com",
            assertedEmail = user.primaryEmail,
            assertedEmailVerified = true,
            emailEvidence = EmailEvidence.AUTHORITATIVE,
            displayName = "Changed Google Name",
            upstreamAuthenticatedAt = null,
            authenticationContext = null,
            authenticationMethods = emptyList(),
            upstreamSessionId = null,
        )
        val loginResult = transactions.run { login.resolve(assertion) }
        assertThat(loginResult.user.displayName).isEqualTo("김 사용자")
        assertThat(loginResult.newUser).isFalse()
        assertThat(userQuery.find(user.userId)?.displayName).isEqualTo("김 사용자")
    }

    companion object {
        @JvmField
        @RegisterExtension
        internal val postgres = TargetIdentityPostgres
    }
}
