package cc.monomer.control.auth

import cc.monomer.identity.domain.model.DeveloperAccessPolicy

import cc.monomer.identity.application.AdminAuthorization
import cc.monomer.identity.application.organization.OrganizationMembersService
import cc.monomer.identity.application.organization.OrganizationUsageService
import cc.monomer.identity.application.organization.CreateOrganizationService
import cc.monomer.identity.application.organization.RemoveOrganizationMemberService
import cc.monomer.identity.application.organization.UpdateOrganizationDisplayNameService
import cc.monomer.identity.application.organization.UpdateOrganizationMemberRoleService
import cc.monomer.identity.application.agent.AgentOAuthTokenHasher
import cc.monomer.identity.application.audit.AuditEventFactory
import cc.monomer.identity.application.grant.SetWorkspaceMemberRoleService
import cc.monomer.identity.application.grant.WorkspaceAuthorizationAssignmentsService
import cc.monomer.identity.application.identity.LoginIdentityResolver
import cc.monomer.identity.application.identity.NormalizedEmailUserResolver
import cc.monomer.identity.application.login.LoginService
import cc.monomer.identity.application.me.UpdateMyDisplayNameService
import cc.monomer.identity.application.me.MeService
import cc.monomer.identity.application.me.MyLoginIdentitiesService
import cc.monomer.identity.application.permission.WorkspaceAccessService
import cc.monomer.identity.application.session.LogoutAllSessionsUseCase
import cc.monomer.identity.application.session.LogoutUseCase
import cc.monomer.identity.application.session.RefreshSessionUseCase
import cc.monomer.identity.application.session.StartSessionUseCase
import cc.monomer.identity.application.workspace.AccessibleWorkspacesService
import cc.monomer.identity.application.workspace.AddWorkspaceMemberService
import cc.monomer.identity.application.workspace.CreateWorkspaceService
import cc.monomer.identity.application.workspace.ManagedWorkspacesService
import cc.monomer.identity.application.workspace.RemoveWorkspaceMemberService
import cc.monomer.identity.application.workspace.SetWorkspacePublicPagesService
import cc.monomer.identity.application.workspace.SetWorkspaceReportingTimeZoneService
import cc.monomer.identity.application.workspace.RenameWorkspaceService
import cc.monomer.identity.application.workspace.WorkspaceAuthorizationBootstrap
import cc.monomer.identity.application.workspace.WorkspaceMemberCandidatesService
import cc.monomer.identity.application.workspace.WorkspaceMembersService
import cc.monomer.identity.application.workspace.OwnerWorkspaceMutationRunner
import cc.monomer.identity.application.workspace.WorkspaceOwnerCandidatesService
import cc.monomer.identity.outbound.jwt.JwtKeyConfiguration
import cc.monomer.identity.outbound.jwt.JwtKeySource
import cc.monomer.identity.outbound.jwt.JwtIssuer
import cc.monomer.identity.outbound.jwt.JwtProperties
import cc.monomer.identity.outbound.jwt.JwtSessionLifetimePolicy
import cc.monomer.identity.outbound.jwt.JwtVerifier
import java.time.Clock
import javax.sql.DataSource
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthJwtProperties::class, DeveloperAccessProperties::class)
@ComponentScan(basePackages = ["cc.monomer.identity.outbound"])
@Import(
    AdminAuthorization::class,
    OrganizationMembersService::class,
    OrganizationUsageService::class,
    CreateOrganizationService::class,
    RemoveOrganizationMemberService::class,
    UpdateOrganizationMemberRoleService::class,
    UpdateOrganizationDisplayNameService::class,
    AgentOAuthTokenHasher::class,
    AuditEventFactory::class,
    SetWorkspaceMemberRoleService::class,
    WorkspaceAuthorizationAssignmentsService::class,
    LoginIdentityResolver::class,
    NormalizedEmailUserResolver::class,
    LoginService::class,
    MeService::class,
    MyLoginIdentitiesService::class,
    UpdateMyDisplayNameService::class,
    WorkspaceAccessService::class,
    LogoutUseCase::class,
    LogoutAllSessionsUseCase::class,
    RefreshSessionUseCase::class,
    StartSessionUseCase::class,
    AccessibleWorkspacesService::class,
    AddWorkspaceMemberService::class,
    CreateWorkspaceService::class,
    cc.monomer.identity.application.workspace.SampleWorkspaceService::class,
    ManagedWorkspacesService::class,
    RemoveWorkspaceMemberService::class,
    RenameWorkspaceService::class,
    SetWorkspacePublicPagesService::class,
    SetWorkspaceReportingTimeZoneService::class,
    WorkspaceAuthorizationBootstrap::class,
    WorkspaceMemberCandidatesService::class,
    WorkspaceMembersService::class,
    OwnerWorkspaceMutationRunner::class,
    WorkspaceOwnerCandidatesService::class,
)
class AuthApplicationComposition {
    @Bean
    fun developerAccessPolicy(properties: DeveloperAccessProperties) =
        DeveloperAccessPolicy(properties.mode, properties.allowedEmails)

    @Bean
    fun jwtKeySource(authJwtProperties: AuthJwtProperties): JwtKeySource =
        JwtKeyConfiguration(authJwtProperties.toIdentityJwtProperties()).jwtKeySource()

    @Bean
    fun jwtIssuer(
        jwtKeySource: JwtKeySource,
        authJwtProperties: AuthJwtProperties,
        productControlClock: Clock,
    ): JwtIssuer =
        JwtIssuer(jwtKeySource, authJwtProperties.toIdentityJwtProperties(), productControlClock)

    @Bean
    fun jwtSessionLifetimePolicy(authJwtProperties: AuthJwtProperties): JwtSessionLifetimePolicy =
        JwtSessionLifetimePolicy(authJwtProperties.toIdentityJwtProperties())

    @Bean
    fun jwtVerifier(
        jwtKeySource: JwtKeySource,
        authJwtProperties: AuthJwtProperties,
        productControlClock: Clock,
    ): JwtVerifier =
        JwtVerifier(jwtKeySource, authJwtProperties.toIdentityJwtProperties(), productControlClock)

    @Bean("postgresJdbcTemplate")
    fun identityRuntimeJdbcTemplate(
        productControlDataSource: DataSource,
    ): NamedParameterJdbcTemplate =
        NamedParameterJdbcTemplate(productControlDataSource)

    @Bean("postgresTransactionTemplate")
    fun identityRuntimeTransactionTemplate(
        productAuthorityTransactionManager: DataSourceTransactionManager,
    ): TransactionTemplate =
        TransactionTemplate(productAuthorityTransactionManager)

    @Bean("postgresConsistentSnapshotTransactionTemplate")
    fun identityRuntimeConsistentSnapshotTransactionTemplate(
        productAuthorityTransactionManager: DataSourceTransactionManager,
    ): TransactionTemplate =
        TransactionTemplate(productAuthorityTransactionManager).apply {
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }

    @Bean("postgresReadOnlyTransactionTemplate")
    fun identityRuntimeReadOnlyTransactionTemplate(
        productAuthorityTransactionManager: DataSourceTransactionManager,
    ): TransactionTemplate =
        TransactionTemplate(productAuthorityTransactionManager).apply {
            isReadOnly = true
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }
}

@ConfigurationProperties(prefix = "auth.jwt")
data class AuthJwtProperties(
    val issuer: String,
    val audience: String,
    val accessTokenTtlSeconds: Long,
    val refreshTokenTtlSeconds: Long,
    val privateKeyPem: String,
    val privateKeyPath: String,
) {
    override fun toString(): String =
        "AuthJwtProperties(issuer=$issuer, audience=$audience, accessTokenTtlSeconds=$accessTokenTtlSeconds, " +
            "refreshTokenTtlSeconds=$refreshTokenTtlSeconds, privateKeyPem=[REDACTED], privateKeyPath=$privateKeyPath)"
}

private fun AuthJwtProperties.toIdentityJwtProperties(): JwtProperties =
    JwtProperties(
        issuer = issuer,
        audience = audience,
        accessTokenTtlSeconds = accessTokenTtlSeconds,
        refreshTokenTtlSeconds = refreshTokenTtlSeconds,
        privateKeyPem = privateKeyPem,
        privateKeyPath = privateKeyPath,
    )

@ConfigurationProperties(prefix = "auth.developer-access")
data class DeveloperAccessProperties(
    val mode: DeveloperAccessPolicy.Mode,
    val allowedEmails: Set<String>,
)
