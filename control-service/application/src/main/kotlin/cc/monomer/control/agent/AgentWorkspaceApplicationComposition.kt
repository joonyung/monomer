package cc.monomer.control.agent

import cc.monomer.agent.api.AgentResourcePath
import cc.monomer.agent.api.AgentActionOutcome
import cc.monomer.agent.api.AgentCatalogDiscoveryPort
import cc.monomer.agent.api.AgentConnectorOwnerPort
import cc.monomer.agent.api.AgentCreateRequest
import cc.monomer.agent.api.AgentPagesOwnerPort
import cc.monomer.agent.api.AgentDashboardOwnerPort
import cc.monomer.agent.api.AgentEditRequest
import cc.monomer.agent.api.AgentExploreOwnerPort
import cc.monomer.agent.api.AgentInvokeRequest
import cc.monomer.agent.api.AgentListRequest
import cc.monomer.agent.api.AgentListingOutcome
import cc.monomer.agent.api.AgentReadRequest
import cc.monomer.agent.api.AgentResourceOutcome
import cc.monomer.agent.api.AgentSearchPort
import cc.monomer.agent.api.AgentSearchRequest
import cc.monomer.agent.api.AgentSemanticOwnerPort
import cc.monomer.agent.api.AgentWorkspaceAdmissionDecision
import cc.monomer.agent.api.AgentWorkspaceAdmissionDenial
import cc.monomer.agent.api.AgentWorkspaceAdmissionEvidence
import cc.monomer.agent.api.requiredRole
import cc.monomer.agent.api.AgentWorkspaceAdmissionPort
import cc.monomer.agent.api.AgentWorkspaceAdmissionRequest
import cc.monomer.agent.api.AgentWorkspaceCaller
import cc.monomer.agent.api.AgentWorkspaceRoleEvidence
import cc.monomer.agent.api.AgentWorkspaceRoleAdmissionRequest as AgentWorkspaceRoleRequest
import cc.monomer.agent.api.AgentWorkspaceHandler
import cc.monomer.agent.application.AgentResourceRouteRegistry
import cc.monomer.agent.application.AgentWorkspaceCallerAuthenticator
import cc.monomer.agent.application.AgentWorkspaceContractDecoder
import cc.monomer.control.WorkspaceRoleAdmissionRequest
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionDenial
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.dashboard.DashboardDraftEventHub
import cc.monomer.catalog.application.CatalogBrowseUseCase
import cc.monomer.catalog.application.ingest.CatalogIngestApplicationPort
import cc.monomer.catalog.application.ingest.CatalogListRefreshApplicationPort
import cc.monomer.control.connector.application.ConnectorReadUseCase
import cc.monomer.control.explore.ExploreRunAccessService
import cc.monomer.control.explore.ExploreSemanticLaunchService
import cc.monomer.control.explore.ExploreSqlLaunchService
import cc.monomer.control.publicapi.AgentWorkspaceHttpPorts
import cc.monomer.control.publicapi.AgentWorkspacePublicApiRouter
import cc.monomer.control.publicapi.AgentWorkspaceTransportCredentialResolution
import cc.monomer.control.publicapi.AgentWorkspaceTransportCredentialResolver
import cc.monomer.control.publicapi.ControlPublicApiProperties
import cc.monomer.control.publicapi.PublicControlContractValidator
import cc.monomer.dashboard.api.DashboardOwnerPort
import cc.monomer.dashboard.application.DashboardResultReadPort
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.api.IdentityAdmissionDenial
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.semantic.api.SemanticAuthorityUseCase
import cc.monomer.semantic.api.SemanticCurrentReadUseCase
import java.time.Clock
import java.util.UUID
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse

@Configuration(proxyBeanMethods = false)
class AgentWorkspaceApplicationComposition {
    @Bean
    fun agentWorkspaceAdmissionPort(
        controlAdmissionFacade: ControlAdmissionFacade,
        workspaceAccessService: cc.monomer.identity.application.permission.WorkspaceAccessService,
        agentOAuthPort: cc.monomer.identity.api.AgentOAuthPort,
        principalDisplayNames: cc.monomer.identity.api.PrincipalDisplayNames,
    ): AgentWorkspaceAdmissionPort = IdentityAgentWorkspaceAdmissionAdapter(
        controlAdmissionFacade,
        AgentWorkspaceRoleProblems(controlAdmissionFacade, workspaceAccessService, agentOAuthPort, principalDisplayNames),
    )

    @Bean
    fun agentProvenanceNames(
        principalDisplayNames: cc.monomer.identity.api.PrincipalDisplayNames,
        agentAppDisplays: cc.monomer.identity.api.AgentAppDisplays,
    ) = AgentProvenanceNames(principalDisplayNames, agentAppDisplays)

    @Bean
    fun agentWorkspaceCallerAuthenticator(
        agentWorkspaceAdmissionPort: AgentWorkspaceAdmissionPort,
    ) = AgentWorkspaceCallerAuthenticator(agentWorkspaceAdmissionPort)

    @Bean
    fun agentDashboardOwnerPort(
        dashboardOwnerPortProvider: ObjectProvider<DashboardOwnerPort>,
        scheduleObservationProvider: ObjectProvider<cc.monomer.control.dashboard.DashboardScheduleObservation>,
        productControlClockProvider: ObjectProvider<Clock>,
        dashboardDraftEventHubProvider: ObjectProvider<DashboardDraftEventHub>,
        dashboardResultReadPortProvider: ObjectProvider<DashboardResultReadPort>,
        controlAdmissionFacade: ControlAdmissionFacade,
        agentProvenanceNames: AgentProvenanceNames,
    ): AgentDashboardOwnerPort {
        val dashboardOwnerPort = dashboardOwnerPortProvider.ifAvailable ?: return AgentWorkspaceUnavailableOwners()
        val clock = productControlClockProvider.ifAvailable ?: Clock.systemUTC()
        val draftEventHub = dashboardDraftEventHubProvider.ifAvailable
        val resultReadPort = dashboardResultReadPortProvider.ifAvailable ?: return AgentWorkspaceUnavailableOwners()
        return AgentDashboardOwnerAdapter(
            dashboardOwnerPort = dashboardOwnerPort,
            scheduleObservation = scheduleObservationProvider.ifAvailable ?: return AgentWorkspaceUnavailableOwners(),
            resultReadPort = resultReadPort,
            controlAdmissionFacade = controlAdmissionFacade,
            clock = clock,
            provenanceNames = agentProvenanceNames,
            draftMutationCallback = { dashboardId, reason ->
                if (draftEventHub != null) {
                    draftEventHub.emit(dashboardId, reason)
                }
            },
        )
    }

    @Bean
    fun agentSemanticOwnerPort(
        semanticAuthorityUseCaseProvider: ObjectProvider<SemanticAuthorityUseCase>,
        semanticCurrentReadUseCaseProvider: ObjectProvider<SemanticCurrentReadUseCase>,
        semanticDefinitionCheckUseCaseProvider: ObjectProvider<cc.monomer.semantic.api.SemanticDefinitionCheckUseCase>,
        controlAdmissionFacade: ControlAdmissionFacade,
        agentProvenanceNames: AgentProvenanceNames,
    ): AgentSemanticOwnerPort {
        val semanticAuthorityUseCase = semanticAuthorityUseCaseProvider.ifAvailable
            ?: return AgentWorkspaceUnavailableOwners()
        val semanticCurrentReadUseCase = semanticCurrentReadUseCaseProvider.ifAvailable
            ?: return AgentWorkspaceUnavailableOwners()
        val semanticDefinitionCheckUseCase = semanticDefinitionCheckUseCaseProvider.ifAvailable
            ?: return AgentWorkspaceUnavailableOwners()
        return AgentSemanticOwnerAdapter(
            authority = semanticAuthorityUseCase,
            currentRead = semanticCurrentReadUseCase,
            checks = semanticDefinitionCheckUseCase,
            admission = controlAdmissionFacade,
            provenanceNames = agentProvenanceNames,
            editAvailability = AgentSemanticEditAvailability { caller ->
                controlAdmissionFacade.authorizeWorkspaceRole(
                    WorkspaceRoleAdmissionRequest(
                        workspaceId = caller.workspaceId,
                        credential = caller.presentedCredential,
                        expectedOAuthResource = caller.expectedOAuthResource,
                        requiredRole = WorkspaceRole.EDITOR,
                    ),
                ) is ControlAdmissionDecision.Admitted
            },
        )
    }

    @Bean
    fun agentCatalogOwnerPort(
        catalogBrowseUseCaseProvider: ObjectProvider<CatalogBrowseUseCase>,
        catalogIngestApplicationPortProvider: ObjectProvider<CatalogIngestApplicationPort>,
        catalogListRefreshApplicationPortProvider: ObjectProvider<CatalogListRefreshApplicationPort>,
        connectorReadUseCaseProvider: ObjectProvider<ConnectorReadUseCase>,
        controlAdmissionFacade: ControlAdmissionFacade,
        agentProvenanceNames: AgentProvenanceNames,
    ): AgentCatalogDiscoveryPort {
        val catalogBrowse = catalogBrowseUseCaseProvider.ifAvailable ?: return AgentWorkspaceUnavailableOwners()
        val catalogIngest = catalogIngestApplicationPortProvider.ifAvailable ?: return AgentWorkspaceUnavailableOwners()
        val catalogListRefresh = catalogListRefreshApplicationPortProvider.ifAvailable ?: return AgentWorkspaceUnavailableOwners()
        val connectorRead = connectorReadUseCaseProvider.ifAvailable ?: return AgentWorkspaceUnavailableOwners()
        return AgentCatalogOwnerAdapter(
            catalogBrowse = catalogBrowse,
            catalogIngest = catalogIngest,
            catalogListRefresh = catalogListRefresh,
            connectorRead = connectorRead,
            controlAdmissionFacade = controlAdmissionFacade,
            provenanceNames = agentProvenanceNames,
        )
    }

    @Bean
    fun agentExploreOwnerPort(
        exploreRunAccessServiceProvider: ObjectProvider<ExploreRunAccessService>,
        exploreSqlLaunchServiceProvider: ObjectProvider<ExploreSqlLaunchService>,
        exploreSemanticLaunchServiceProvider: ObjectProvider<ExploreSemanticLaunchService>,
        productControlClockProvider: ObjectProvider<Clock>,
        agentProvenanceNames: AgentProvenanceNames,
    ): AgentExploreOwnerPort {
        val runAccessService = exploreRunAccessServiceProvider.ifAvailable
            ?: return AgentWorkspaceUnavailableOwners()
        val sqlLaunchService = exploreSqlLaunchServiceProvider.ifAvailable
            ?: return AgentWorkspaceUnavailableOwners()
        val semanticLaunchService = exploreSemanticLaunchServiceProvider.ifAvailable
            ?: return AgentWorkspaceUnavailableOwners()
        return AgentExploreOwnerAdapter(
            runAccessService = runAccessService,
            sqlLaunchService = sqlLaunchService,
            semanticLaunchService = semanticLaunchService,
            clock = productControlClockProvider.ifAvailable ?: Clock.systemUTC(),
            objectMapper = jacksonObjectMapper(),
            provenanceNames = agentProvenanceNames,
        )
    }

    @Bean
    fun agentConnectorOwnerPort(
        authority: ObjectProvider<cc.monomer.connector.api.ConnectorAuthorityUseCase>,
        @org.springframework.beans.factory.annotation.Qualifier("connectorWorkspaceReadUseCase")
        currentRead: ObjectProvider<cc.monomer.connector.api.ConnectorWorkspaceReadUseCase>,
        admission: ControlAdmissionFacade,
        workspaceSamples: ObjectProvider<cc.monomer.identity.api.WorkspaceSampleQuery>,
    ): AgentConnectorOwnerPort {
        val owner = authority.ifAvailable ?: return AgentWorkspaceUnavailableOwners()
        val read = currentRead.ifAvailable ?: return AgentWorkspaceUnavailableOwners()
        val samples = workspaceSamples.ifAvailable ?: return AgentWorkspaceUnavailableOwners()
        return AgentConnectorOwnerAdapter(owner, read, admission, samples)
    }

    @Bean
    fun agentPagesOwnerPort(
        directory: ObjectProvider<cc.monomer.pages.application.PagesDirectoryUseCase>,
        content: ObjectProvider<cc.monomer.pages.application.content.PageContentUseCase>,
        uploads: ObjectProvider<cc.monomer.pages.application.content.PageContentUploadUseCase>,
        dashboard: ObjectProvider<DashboardOwnerPort>,
        admission: ControlAdmissionFacade,
        clock: ObjectProvider<Clock>,
        pageReader: ObjectProvider<cc.monomer.control.pages.publicpages.PageReaderService>,
        agentProvenanceNames: AgentProvenanceNames,
    ): AgentPagesOwnerPort {
        return AgentPagesOwnerAdapter(
        directory.ifAvailable ?: return AgentWorkspaceUnavailableOwners(),
        content.ifAvailable ?: return AgentWorkspaceUnavailableOwners(),
        uploads.ifAvailable ?: return AgentWorkspaceUnavailableOwners(),
        dashboard.ifAvailable ?: return AgentWorkspaceUnavailableOwners(),
        admission,
        clock.ifAvailable ?: Clock.systemUTC(),
        AgentPageReader(pageReader.ifAvailable ?: return AgentWorkspaceUnavailableOwners()),
        agentProvenanceNames,
        )
    }

    @Bean
    fun agentManagementOwnerPort(
        oauth: cc.monomer.identity.api.AgentOAuthPort,
        admission: cc.monomer.control.ControlAdmissionFacade,
        me: cc.monomer.identity.application.me.MeUseCase,
        managedWorkspaces: cc.monomer.identity.application.workspace.ManagedWorkspacesUseCase,
        organizationMembers: cc.monomer.identity.application.organization.OrganizationMembersUseCase,
        invitations: cc.monomer.identity.application.organization.OrganizationInvitationService,
        members: cc.monomer.identity.application.workspace.WorkspaceMembersUseCase,
        candidates: cc.monomer.identity.application.workspace.WorkspaceMemberCandidatesUseCase,
        authorization: cc.monomer.identity.application.grant.WorkspaceAuthorizationAssignmentsUseCase,
        addMember: cc.monomer.identity.application.workspace.AddWorkspaceMemberUseCase,
        removeMember: cc.monomer.identity.application.workspace.RemoveWorkspaceMemberUseCase,
        setMemberRole: cc.monomer.identity.application.grant.SetWorkspaceMemberRoleUseCase,
        reportingTimeZones: cc.monomer.identity.api.WorkspaceReportingTimeZoneQuery,
        lineage: cc.monomer.control.lineage.LineageGraphQueryService,
        clock: java.time.Clock,
    ): cc.monomer.agent.api.AgentManagementOwnerPort = AgentManagementOwnerAdapter(
        oauth = oauth,
        admission = admission,
        me = me,
        managedWorkspaces = managedWorkspaces,
        organizationMembers = organizationMembers,
        invitations = invitations,
        members = members,
        candidates = candidates,
        authorization = authorization,
        addMember = addMember,
        removeMember = removeMember,
        setMemberRole = setMemberRole,
        reportingTimeZones = reportingTimeZones,
        lineage = lineage,
        clock = clock,
    )

    @Bean
    fun agentWorkspaceHandler(
        agentExploreOwnerPort: AgentExploreOwnerPort,
        agentConnectorOwnerPort: AgentConnectorOwnerPort,
        agentCatalogOwnerPort: AgentCatalogDiscoveryPort,
        agentSemanticOwnerPort: AgentSemanticOwnerPort,
        agentDashboardOwnerPort: AgentDashboardOwnerPort,
        agentPagesOwnerPort: AgentPagesOwnerPort,
        agentSearchPortProvider: ObjectProvider<AgentSearchPort>,
        managementOwnerPort: cc.monomer.agent.api.AgentManagementOwnerPort,
    ): AgentWorkspaceHandler {
        return AgentResourceRouteRegistry(
            searchPort = agentSearchPortProvider.ifAvailable ?: object : AgentSearchPort {
                override suspend fun search(caller: AgentWorkspaceCaller, request: AgentSearchRequest) =
                    cc.monomer.agent.api.AgentSearchOutcome.Rejected(AgentOwnerProblems.ownerNotInstalled)
            },
            exploreOwnerPort = agentExploreOwnerPort,
            connectorOwnerPort = agentConnectorOwnerPort,
            catalogDiscoveryPort = agentCatalogOwnerPort,
            semanticOwnerPort = agentSemanticOwnerPort,
            dashboardOwnerPort = agentDashboardOwnerPort,
            pagesOwnerPort = agentPagesOwnerPort,
            managementOwnerPort = managementOwnerPort,
            workspaceCollections = buildList {
                if (agentPagesOwnerPort !is AgentWorkspaceUnavailableOwners) add(AgentResourcePath.parse("workspace/pages"))
                if (agentConnectorOwnerPort !is AgentWorkspaceUnavailableOwners) add(AgentResourcePath.parse("workspace/connectors"))
                if (agentSemanticOwnerPort !is AgentWorkspaceUnavailableOwners) {
                    add(AgentResourcePath.parse("workspace/semantic-models"))
                    add(AgentResourcePath.parse("workspace/metrics"))
                }
                if (agentExploreOwnerPort !is AgentWorkspaceUnavailableOwners) add(AgentResourcePath.parse("workspace/explore/runs"))
                if (agentDashboardOwnerPort !is AgentWorkspaceUnavailableOwners) add(AgentResourcePath.parse("workspace/dashboards"))
            },
        )
    }

    @Bean
    fun agentWorkspaceHttpPorts(
        agentWorkspaceCallerAuthenticator: AgentWorkspaceCallerAuthenticator,
        agentWorkspaceHandler: AgentWorkspaceHandler,
    ) = AgentWorkspaceHttpPorts(
        callerAuthenticator = agentWorkspaceCallerAuthenticator,
        handler = agentWorkspaceHandler,
        requestDecoder = AgentWorkspaceContractDecoder(),
    )

    @Bean
    fun agentWorkspacePublicApiRouter(
        agentWorkspaceHttpPorts: AgentWorkspaceHttpPorts,
        controlPublicApiProperties: ControlPublicApiProperties,
        agentOAuthProperties: cc.monomer.control.auth.inbound.agentoauth.AgentOAuthProperties,
        oauth: cc.monomer.identity.api.AgentOAuthPort,
    ): AgentWorkspacePublicApiRouter = AgentWorkspacePublicApiRouter(
        agentPorts = agentWorkspaceHttpPorts,
        oauthResourceMetadataUrl = agentOAuthProperties.issuer.trimEnd('/') + "/.well-known/oauth-protected-resource/v2/workspaces",
        properties = controlPublicApiProperties,
        credentialResolver = AgentWorkspaceAuthorizationHeaderCredentialResolver(),

        contractValidator = PublicControlContractValidator(),
        oauth = oauth,
    )

    @Bean
    fun agentWorkspacePublicApiRoutes(
        agentWorkspacePublicApiRouter: AgentWorkspacePublicApiRouter,
        oauth: cc.monomer.identity.api.AgentOAuthPort,
        managementOwnerPort: cc.monomer.agent.api.AgentManagementOwnerPort,
    ): RouterFunction<ServerResponse> = agentWorkspacePublicApiRouter.routes().and(agentWorkspacePublicApiRouter.organizationRoutes(oauth, managementOwnerPort))
}

private class IdentityAgentWorkspaceAdmissionAdapter(
    private val controlAdmissionFacade: ControlAdmissionFacade,
    private val roleProblems: AgentWorkspaceRoleProblems,
) : AgentWorkspaceAdmissionPort {
    override suspend fun decide(
        request: AgentWorkspaceAdmissionRequest,
    ): AgentWorkspaceAdmissionDecision = when (request) {
        is AgentWorkspaceRoleRequest -> when (
            val decision = controlAdmissionFacade.authorizeWorkspaceRole(
                WorkspaceRoleAdmissionRequest(
                    workspaceId = request.workspaceId,
                    credential = request.credential,
                    expectedOAuthResource = request.expectedOAuthResource,
                    requiredRole = request.intent.requiredRole,
                ),
            )
        ) {
            is ControlAdmissionDecision.Admitted -> AgentWorkspaceAdmissionDecision.Authorized(
                decision.context.toAgentWorkspaceRoleEvidence(request),
            )
            is ControlAdmissionDecision.Denied -> {
                val denial = decision.toAgentWorkspaceDenial()
                val roleDenied = decision.reason == ControlAdmissionDenial.Identity(IdentityAdmissionDenial.PERMISSION_UNAVAILABLE)
                AgentWorkspaceAdmissionDecision.Denied(
                    denial,
                    if (roleDenied) roleProblems.explain(request.workspaceId, request.credential, request.expectedOAuthResource, request.intent) else null,
                )
            }
        }
    }
}

private class AgentWorkspaceUnavailableOwners :
    AgentExploreOwnerPort,
    AgentConnectorOwnerPort,
    AgentCatalogDiscoveryPort,
    AgentSemanticOwnerPort,
    AgentDashboardOwnerPort,
    AgentPagesOwnerPort {
    override suspend fun list(caller: AgentWorkspaceCaller, request: AgentListRequest): AgentListingOutcome =
        AgentListingOutcome.Rejected(AgentOwnerProblems.ownerNotInstalled)

    override suspend fun read(caller: AgentWorkspaceCaller, request: AgentReadRequest): AgentResourceOutcome =
        AgentResourceOutcome.Rejected(AgentOwnerProblems.ownerNotInstalled)

    override suspend fun create(caller: AgentWorkspaceCaller, request: AgentCreateRequest): AgentActionOutcome =
        AgentActionOutcome.Rejected(AgentOwnerProblems.ownerNotInstalled)

    override suspend fun edit(caller: AgentWorkspaceCaller, request: AgentEditRequest): AgentActionOutcome =
        AgentActionOutcome.Rejected(AgentOwnerProblems.ownerNotInstalled)

    override suspend fun invoke(caller: AgentWorkspaceCaller, request: AgentInvokeRequest): AgentActionOutcome =
        AgentActionOutcome.Rejected(AgentOwnerProblems.ownerNotInstalled)
}

private class AgentWorkspaceAuthorizationHeaderCredentialResolver : AgentWorkspaceTransportCredentialResolver {
    override suspend fun resolve(request: ServerRequest): AgentWorkspaceTransportCredentialResolution {
        val authorization = request.headers().header(HttpHeaders.AUTHORIZATION).singleOrNull()
            ?: return AgentWorkspaceTransportCredentialResolution.Missing
        val token = BEARER_CREDENTIAL.matchEntire(authorization)?.groupValues?.get(1)
            ?: return AgentWorkspaceTransportCredentialResolution.Missing
        return AgentWorkspaceTransportCredentialResolution.Resolved(PresentedCredential(token))
    }

    private companion object {
        val BEARER_CREDENTIAL = Regex("Bearer ([^\\s]+)", RegexOption.IGNORE_CASE)
    }
}

private fun cc.monomer.identity.api.IdentityRoleEvidence.toAgentWorkspaceRoleEvidence(
    request: AgentWorkspaceRoleRequest,
): AgentWorkspaceAdmissionEvidence = AgentWorkspaceRoleEvidence(
    organizationPlan = organizationPlan,
    actor = actor,
    organizationId = organizationId,
    workspaceId = workspaceId,
    authorizationRevision = authorizationRevision,
    credentialKind = credentialKind,
    credentialSurface = credentialSurface,
    agentClientId = agentClientId,
    role = role,
    intent = request.intent,
)

private fun IdentityAdmissionDenial.toAgentWorkspaceDenial(): AgentWorkspaceAdmissionDenial = when (this) {
    IdentityAdmissionDenial.CREDENTIAL_UNAVAILABLE,
    IdentityAdmissionDenial.CREDENTIAL_EXPIRED,
    IdentityAdmissionDenial.CREDENTIAL_REVOKED,
    IdentityAdmissionDenial.PRINCIPAL_MISMATCH,
    IdentityAdmissionDenial.AGENT_BINDING_MISMATCH,
    IdentityAdmissionDenial.AGENT_SCOPE_INSUFFICIENT,
    -> AgentWorkspaceAdmissionDenial.UNAUTHENTICATED
    IdentityAdmissionDenial.WORKSPACE_UNAVAILABLE,
    IdentityAdmissionDenial.ORGANIZATION_MEMBERSHIP_UNAVAILABLE,
    IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE,
    -> AgentWorkspaceAdmissionDenial.NOT_FOUND
    IdentityAdmissionDenial.PERMISSION_UNAVAILABLE,
    -> AgentWorkspaceAdmissionDenial.FORBIDDEN
}

private fun ControlAdmissionDecision.Denied.toAgentWorkspaceDenial(): AgentWorkspaceAdmissionDenial = when (val denial = reason) {
    is ControlAdmissionDenial.Identity -> denial.reason.toAgentWorkspaceDenial()
    ControlAdmissionDenial.INVALID_EXECUTION_TARGET,
    ControlAdmissionDenial.IDENTITY_EVIDENCE_MISMATCH,
    -> AgentWorkspaceAdmissionDenial.FORBIDDEN
}
