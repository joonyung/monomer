package cc.monomer.identity.application.login

import cc.monomer.identity.domain.model.DeveloperAccessDeniedException

import cc.monomer.identity.domain.model.DeveloperAccessPolicy

import cc.monomer.identity.application.identity.LoginIdentityResolver
import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.WorkspaceId
import cc.monomer.identity.domain.model.Organization
import cc.monomer.identity.domain.model.OrganizationMembership
import cc.monomer.identity.api.OrganizationPlan
import cc.monomer.identity.domain.model.OrganizationRole
import cc.monomer.identity.domain.model.AuthenticationAssertion
import cc.monomer.identity.domain.model.LoginIdentity
import cc.monomer.identity.domain.model.User
import cc.monomer.identity.domain.model.ReportingTimeZone
import cc.monomer.identity.domain.model.Workspace
import cc.monomer.identity.application.workspace.WorkspaceAuthorizationBootstrap
import cc.monomer.identity.domain.port.OrganizationRepository
import cc.monomer.identity.domain.port.AuditLogRepository
import cc.monomer.identity.domain.port.TransactionRunner
import cc.monomer.identity.domain.port.WorkspaceRepository
import java.time.Clock

class LoginService(
    private val transactionRunner: TransactionRunner,
    private val loginIdentityResolver: LoginIdentityResolver,
    private val organizationRepository: OrganizationRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val workspaceAuthorizationBootstrap: WorkspaceAuthorizationBootstrap,
    private val auditLogRepository: AuditLogRepository,
    private val auditEventFactory: AuditEventFactory,
    private val clock: Clock,
    private val developerAccessPolicy: DeveloperAccessPolicy,
) : LoginUseCase {

    override suspend fun login(assertion: AuthenticationAssertion, firstSignIn: LoginUseCase.FirstSignIn): LoginUseCase.Response =
        transactionRunner.run {
            if (!developerAccessPolicy.allows(assertion)) throw DeveloperAccessDeniedException()
            val resolution = loginIdentityResolver.resolve(assertion)
            responseWithInitialOrganization(
                user = resolution.user,
                loginIdentity = resolution.loginIdentity,
                newUser = resolution.newUser,
                firstSignIn = firstSignIn,
            )
        }

    private fun responseWithInitialOrganization(
        user: User,
        loginIdentity: LoginIdentity,
        newUser: Boolean,
        firstSignIn: LoginUseCase.FirstSignIn,
    ): LoginUseCase.Response {
        if (!newUser) return LoginUseCase.Response(user, loginIdentity, false)
        if (firstSignIn == LoginUseCase.FirstSignIn.JOIN_BY_INVITATION) return LoginUseCase.Response(user, loginIdentity, true)

        val now = clock.instant()
        val organization = Organization(
            id = OrganizationId.generate(),
            displayName = "${user.displayName}'s organization",
            billingEmail = user.primaryEmail,
            plan = OrganizationPlan.FREE,
            createdAt = now,
        )
        val workspace = Workspace(
            id = WorkspaceId.generate(),
            organizationId = organization.id,
            displayName = "My Workspace",
            createdAt = now,
            publicPages = false,
            reportingTimeZone = ReportingTimeZone.UTC,
            sample = null,
        )
        organizationRepository.insert(organization)
        organizationRepository.insertMember(
            OrganizationMembership(
                organizationId = organization.id,
                userId = user.userId,
                role = OrganizationRole.ADMIN,
                joinedAt = now,
            ),
        )
        workspaceRepository.insert(workspace)
        workspaceAuthorizationBootstrap.createInitialBundle(
                workspaceId = workspace.id,
                initialOwnerUserId = user.userId,
                assignedByUserId = user.userId,
                assignedAt = now,
        )
        auditLogRepository.insert(
            auditEventFactory.build(
                actorId = user.userId.asPrincipal(),
                action = "organization.create",
                organizationId = organization.id,
                workspaceId = null,
                resourceType = "organization",
                resourceId = organization.id.value,
                details = "{}",
            ),
        )
        auditLogRepository.insert(
            auditEventFactory.build(
                actorId = user.userId.asPrincipal(),
                action = "workspace.create",
                organizationId = organization.id,
                workspaceId = workspace.id,
                resourceType = "workspace",
                resourceId = workspace.id.value,
                details = "{}",
            ),
        )
        return LoginUseCase.Response(user, loginIdentity, newUser)
    }
}
