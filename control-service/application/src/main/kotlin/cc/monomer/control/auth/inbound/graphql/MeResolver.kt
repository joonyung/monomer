package cc.monomer.control.auth.inbound.graphql

import cc.monomer.identity.application.me.MeUseCase
import cc.monomer.identity.application.me.MyLoginIdentitiesService
import cc.monomer.identity.application.me.UpdateMyDisplayNameService
import cc.monomer.identity.domain.id.UserId
import kotlin.uuid.Uuid
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.stereotype.Controller

@Controller
class MeResolver(
    private val meUseCase: MeUseCase,
    private val updateMyDisplayNameService: UpdateMyDisplayNameService,
    private val myLoginIdentitiesService: MyLoginIdentitiesService,
) {
    @MutationMapping
    suspend fun updateMyDisplayName(@Argument displayName: String): UserView {
        val authentication = ReactiveSecurityContextHolder.getContext().awaitSingleOrNull()?.authentication
        if (authentication == null || !authentication.isAuthenticated) {
            throw AuthenticationCredentialsNotFoundException("Authentication is required")
        }
        return when (val outcome = updateMyDisplayNameService.update(UserId(Uuid.parse(authentication.name)), displayName)) {
            is UpdateMyDisplayNameService.Outcome.Updated -> with(outcome.user) {
                UserView(userId.toString(), primaryEmail, this.displayName)
            }
            UpdateMyDisplayNameService.Outcome.InvalidDisplayName ->
                throw InvalidCanonicalGraphqlInputException("displayName must not be blank")
            UpdateMyDisplayNameService.Outcome.UserNotFound -> throw AccessDeniedException("User is unavailable")
        }
    }

    @QueryMapping
    suspend fun me(): MeView {
        val authentication = ReactiveSecurityContextHolder.getContext()
            .map { securityContext -> checkNotNull(securityContext.authentication) }
            .awaitSingle()
        val userId = UserId(Uuid.parse(authentication.name))
        val response = meUseCase.me(MeUseCase.Command(userId))
        return MeView.from(response)
    }

    @SchemaMapping(typeName = "Me", field = "loginIdentities")
    suspend fun loginIdentities(me: MeView): List<LoginIdentityView> {
        val authentication = ReactiveSecurityContextHolder.getContext()
            .map { securityContext -> checkNotNull(securityContext.authentication) }
            .awaitSingle()
        val actorUserId = UserId(Uuid.parse(authentication.name))
        check(me.user.id == actorUserId.toString()) { "Me must belong to the authenticated user" }
        return myLoginIdentitiesService.list(actorUserId).map { identity ->
            LoginIdentityView(provider = identity.providerDisplayName, linkedAt = identity.linkedAt.toString())
        }
    }
}

data class LoginIdentityView(
    val provider: String,
    val linkedAt: String,
)

data class MeView(
    val user: UserView,
    val organizationMemberships: List<OrganizationMembershipViewDto>,
) {
    companion object {
        fun from(response: MeUseCase.Response): MeView = MeView(
            user = UserView(
                id = response.user.userId.toString(),
                email = response.user.primaryEmail,
                displayName = response.user.displayName,
            ),
            organizationMemberships = response.organizationMemberships.map { m ->
                OrganizationMembershipViewDto(
                    organization = OrganizationView(
                        id = m.organization.id.toString(),
                        displayName = m.organization.displayName,
                        plan = m.organization.plan.name,
                        createdAt = m.organization.createdAt.toString(),
                    ),
                    role = m.role.name,
                )
            },
        )
    }
}

data class UserView(
    val id: String,
    val email: String,
    val displayName: String,
)

data class OrganizationView(
    val id: String,
    val displayName: String,
    val plan: String,
    val createdAt: String,
)

data class OrganizationMembershipViewDto(
    val organization: OrganizationView,
    val role: String,
)
