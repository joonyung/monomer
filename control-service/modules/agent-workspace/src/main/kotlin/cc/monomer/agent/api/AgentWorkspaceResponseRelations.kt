@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.agent.api

import cc.monomer.identity.api.WorkspaceId

object AgentWorkspaceResponseRelations {
    fun matches(request: AgentReadRequest, resource: AgentResourceEnvelope): Boolean =
        request.path == resource.path && semanticVersionMatches(resource) && dashboardVersionMatches(resource) && dashboardDraftStatusMatches(resource) && managementResourceMatches(resource) && (resource.body == null || resource.body?.kind == resource.kind) &&
            ((resource as? AgentResourceEnvelope.ExploreResult)?.body?.download != null) == (request.download != null)

    fun matches(request: AgentListRequest, listing: AgentResourceListingResponse): Boolean =
        listing.items.all { item ->
            if (item is AgentResourceListingItem.DashboardDefinitionVersion) {
                if (!AgentDashboardHistoryPaths.version.matches(item.path.value) ||
                    item.path.value != "workspace/dashboards/${item.definitionId}/history/${item.changeId}") return@all false
            }
            if (item is AgentResourceListingItem.SemanticDefinitionVersion) {
                val parts = item.path.value.split('/')
                if (!AgentSemanticHistoryPaths.version.matches(item.path.value) || parts[2] != item.name ||
                    parts[4] != item.definitionId.toString() || parts[5] != item.changeId.toString() ||
                    item.kind != if (parts[1] == "semantic-models") AgentResourceKind.SEMANTIC_MODEL_VERSION else AgentResourceKind.METRIC_VERSION) return@all false
            }
            if (request.path.value == "organization/workspaces") return@all item is AgentResourceListingItem.Collection && Regex("^organization/workspaces/[^/]+/members$").matches(item.path.value)
            if (AgentDashboardSchedulePaths.occurrences.matches(request.path.value)) return@all item is AgentResourceListingItem.DashboardScheduleOccurrence && item.path.value.startsWith("${request.path.value}/") && AgentDashboardSchedulePaths.validOccurrence(item.path.value)
            val child = item.path.value.removePrefix("${request.path.value}/")
            item.path.value.startsWith("${request.path.value}/") &&
                (request.path.value == "workspace" || '/' !in child)
        }

    fun matches(request: AgentCreateRequest, action: AgentActionResult): Boolean {
        if (request.input is AgentCreateInput.OrganizationInvitation) return request.path.value == "organization/invitations" && action is AgentActionResult.Create && action.path == request.path && action.result.kind == AgentResourceKind.ORGANIZATION_INVITATION && AgentManagementPaths.invitation.matches(action.result.path.value)

        if (request.input is AgentCreateInput.PageContentUpload) {
            return action is AgentActionResult.PageUploadPrepared && action.path == request.path &&
                request.path.value == "workspace/page-content-uploads" && action.result.kind == AgentResourceKind.PAGE_CONTENT_UPLOAD &&
                action.result.path.value == "workspace/page-content-uploads/${action.upload.uploadRef}" &&
                action.upload.format == request.input.format && action.upload.byteSize == request.input.byteSize && action.upload.sha256 == request.input.sha256
        }
        if (action !is AgentActionResult.Create || action.path != request.path) return false
        val expectedKind = when (request.input) {
            is AgentCreateInput.OrganizationInvitation -> return false
            is AgentCreateInput.FolderPage, is AgentCreateInput.DocumentPage, is AgentCreateInput.DashboardPage -> AgentResourceKind.PAGE
            is AgentCreateInput.PageVersion -> AgentResourceKind.PAGE_VERSION
            is AgentCreateInput.PageContentUpload -> return false
            is AgentCreateInput.Connector -> AgentResourceKind.CONNECTOR
            is AgentCreateInput.SemanticModel -> AgentResourceKind.SEMANTIC_MODEL
            is AgentCreateInput.Metric -> AgentResourceKind.METRIC
            is AgentCreateInput.Dashboard -> AgentResourceKind.DASHBOARD
        }
        return AgentWorkspaceAdmissionIntents.create(request) != null && action.result.kind == expectedKind &&
            directChild(request.path.value, action.result.path.value) && when (val input = request.input) {
                is AgentCreateInput.SemanticModel -> action.result.path.value == "${request.path.value}/${input.definition.name}"
                is AgentCreateInput.Metric -> action.result.path.value == "${request.path.value}/${input.definition.name}"
                is AgentCreateInput.OrganizationInvitation -> false
                is AgentCreateInput.PageContentUpload -> false
                is AgentCreateInput.PageVersion -> AgentPagesPaths.version.matches(action.result.path.value)
                is AgentCreateInput.FolderPage, is AgentCreateInput.DocumentPage, is AgentCreateInput.DashboardPage,
                is AgentCreateInput.Connector, is AgentCreateInput.Dashboard -> uuidChild(request.path.value, action.result.path.value)
            }
    }

    fun matches(request: AgentEditRequest, action: AgentActionResult): Boolean {
        if (action !is AgentActionResult.Edit || action.path != request.path || action.result.path != request.path) return false
        val expectedKind = when (AgentWorkspaceAdmissionIntents.edit(request)) {
            is AgentWorkspaceAdmissionIntent.PagesEdit -> if (request.path.value == "workspace/pages/tree") AgentResourceKind.PAGES_TREE else AgentResourceKind.PAGE
            is AgentWorkspaceAdmissionIntent.ConnectorEdit -> AgentResourceKind.CONNECTOR
            is AgentWorkspaceAdmissionIntent.SemanticModelEdit -> AgentResourceKind.SEMANTIC_MODEL
            is AgentWorkspaceAdmissionIntent.MetricEdit -> AgentResourceKind.METRIC
            is AgentWorkspaceAdmissionIntent.DashboardEdit -> AgentResourceKind.DASHBOARD_DRAFT
            is AgentWorkspaceAdmissionIntent.CatalogEdit -> if (request.path.value.endsWith("/catalog-selection")) {
                AgentResourceKind.CONNECTOR_CATALOG_SELECTION
            } else {
                AgentResourceKind.CATALOG_SELECTION
            }
            else -> return false
        }
        return action.result.kind == expectedKind
    }

    fun matches(request: AgentInvokeRequest, action: AgentActionResult): Boolean {
        if (AgentManagementPaths.workspace(request.path.value) || AgentManagementPaths.organization(request.path.value)) {
            if (!AgentManagementPaths.invocation(request) || action !is AgentActionResult.Invoke || action.path != request.path || action.action != request.action || action.status != AgentActionStatus.COMPLETED) return false
            val expected = when(request.action) {
                "add-member" -> AgentActionResultReference(AgentResourceKind.WORKSPACE_MEMBER, AgentResourcePath.parse("workspace/members/${(request.input as AgentInvokeInput.WorkspaceMemberAdd).userId}"))
                "set-role" -> AgentActionResultReference(AgentResourceKind.WORKSPACE_MEMBER, request.path)
                "remove-member" -> AgentActionResultReference(AgentResourceKind.COLLECTION, AgentResourcePath.parse(request.path.value.substringBeforeLast('/')))
                "cancel" -> AgentActionResultReference(AgentResourceKind.COLLECTION, AgentResourcePath.parse("organization/invitations"))
                "resend" -> AgentActionResultReference(AgentResourceKind.ORGANIZATION_INVITATION, request.path)
                else -> AgentActionResultReference(AgentResourceKind.WORKSPACE_AUTHORIZATION, AgentResourcePath.parse("workspace/authorization"))
            }
            return action.result == expected
        }

        if (request.input is AgentInvokeInput.PageContentUploadComplete) {
            return action is AgentActionResult.PageUploadCompleted && action.path == request.path &&
                AgentWorkspaceAdmissionIntents.invoke(request) != null && action.result.kind == AgentResourceKind.PAGE_CONTENT_UPLOAD &&
                action.result.path == request.path && action.result.path.value == "workspace/page-content-uploads/${action.upload.uploadRef}"
        }
        if (request.input is AgentInvokeInput.SemanticModelCheck || request.input is AgentInvokeInput.MetricCheck) {
            val exact = request.path.value.count { it == '/' } == 2
            val expectedKind = when {
                !exact -> AgentResourceKind.COLLECTION
                request.input is AgentInvokeInput.SemanticModelCheck -> AgentResourceKind.SEMANTIC_MODEL
                else -> AgentResourceKind.METRIC
            }
            return action is AgentActionResult.SemanticCheck && action.path == request.path &&
                AgentWorkspaceAdmissionIntents.invoke(request) != null &&
                action.result == AgentActionResultReference(expectedKind, request.path)
        }
        if (request.input is AgentInvokeInput.DashboardSessionResolve || request.input is AgentInvokeInput.DashboardSessionRefresh) {
            return action is AgentActionResult.DashboardSessionResolution && action.path == request.path &&
                action.action == request.action && action.status == AgentActionStatus.ACCEPTED &&
                AgentWorkspaceAdmissionIntents.invoke(request) != null &&
                action.result.kind == (if (AgentPagesPaths.session.matches(request.path.value)) AgentResourceKind.PAGE_SESSION else AgentResourceKind.DASHBOARD_SESSION) && action.result.path == request.path &&
                action.queryRuns.all { (name, runPath) -> name.isNotBlank() && uuidChild("${request.path.value}/query-runs", runPath.value) }
        }
        if (action !is AgentActionResult.Invoke || action.path != request.path || action.action != request.action ||
            AgentWorkspaceAdmissionIntents.invoke(request) == null
        ) return false
        val path = request.path.value
        val result = action.result
        return when (request.input) {
            is AgentInvokeInput.WorkspaceMemberAdd, is AgentInvokeInput.WorkspaceMemberRole -> false
            is AgentInvokeInput.PageDelete -> result.kind == AgentResourceKind.PAGES_TREE && result.path.value == "workspace/pages/tree" && action.status == AgentActionStatus.COMPLETED
            is AgentInvokeInput.PageContentUploadComplete -> false
            is AgentInvokeInput.ConnectorCredentialRotate -> result.kind == AgentResourceKind.CONNECTOR && result.path == request.path && action.status == AgentActionStatus.COMPLETED
            is AgentInvokeInput.SemanticModelCheck, is AgentInvokeInput.MetricCheck -> false
            is AgentInvokeInput.ExploreRun -> result.kind == AgentResourceKind.EXPLORE_RUN && uuidChild(path, result.path.value)
            is AgentInvokeInput.DashboardDraftStart -> result.kind == AgentResourceKind.DASHBOARD_DRAFT && result.path == request.path
            is AgentInvokeInput.DashboardDraftApply -> result.kind == AgentResourceKind.DASHBOARD && result.path.value == path.removeSuffix("/draft")
            is AgentInvokeInput.DashboardDraftDiscard -> result.kind == AgentResourceKind.DASHBOARD_DRAFT && result.path == request.path
            is AgentInvokeInput.DashboardQueryRefresh -> result.path == request.path && result.kind == AgentResourceKind.DASHBOARD_DRAFT
            is AgentInvokeInput.DashboardSessionResolve, is AgentInvokeInput.DashboardSessionRefresh -> false
            is AgentInvokeInput.DashboardSessionOpen -> result.kind == AgentResourceKind.DASHBOARD_SESSION && uuidChild(path, result.path.value)
            AgentInvokeInput.None -> when (request.action) {
                "abort" -> result.kind == AgentResourceKind.PAGES_TREE && result.path.value == "workspace/pages/tree" && action.status == AgentActionStatus.COMPLETED
                "delete" -> result.kind == AgentResourceKind.COLLECTION && result.path.value == path.substringBeforeLast('/') && action.status == AgentActionStatus.COMPLETED
                "restore" -> result.kind == (when { path.startsWith("workspace/dashboards/") -> AgentResourceKind.DASHBOARD; path.startsWith("workspace/semantic-models/") -> AgentResourceKind.SEMANTIC_MODEL; else -> AgentResourceKind.METRIC }) &&
                    result.path.value == path.substringBefore("/history/") && action.status == AgentActionStatus.COMPLETED
                "ingest" -> result.kind == AgentResourceKind.CATALOG_INGESTION && uuidChild("$path/ingestions", result.path.value)
                "refresh" -> result.kind == AgentResourceKind.CATALOG_SELECTION_OPTION_REFRESH &&
                    uuidChild(
                        path.replace("/selection/options/", "/selection/option-refreshes/")
                            .replace("/catalog-selection/options/", "/catalog-selection/option-refreshes/"),
                        result.path.value,
                    )
                "cancel" -> result.path == request.path && result.kind == when {
                    path.startsWith("workspace/explore/runs/") -> AgentResourceKind.EXPLORE_RUN
                    path.startsWith("workspace/dashboards/") -> AgentResourceKind.DASHBOARD_QUERY_RUN
                    path.substringBeforeLast('/').endsWith("/ingestions") -> AgentResourceKind.CATALOG_INGESTION
                    else -> AgentResourceKind.CATALOG_SELECTION_OPTION_REFRESH
                }
                else -> false
            }
        }
    }

    fun matchesInvocation(path: AgentResourcePath, action: String, inputKind: String?): Boolean = AgentManagementPaths.invocation(path.value, action, inputKind) || when (inputKind) {
        "PAGE_DELETE" -> action == "delete" && AgentPagesPaths.page.matches(path.value)
        "PAGE_CONTENT_UPLOAD_COMPLETE" -> action == "complete" && AgentPagesPaths.upload.matches(path.value)
        "CONNECTOR_CREDENTIAL_ROTATE" -> AgentWorkspaceAdmissionIntent.ConnectorRotate(path, action).isSupported()
        "SEMANTIC_MODEL_CHECK" -> action == "check" && path.value.startsWith("workspace/semantic-models") &&
            AgentWorkspaceAdmissionIntent.SemanticCheck(path, action).isSupported()
        "METRIC_CHECK" -> action == "check" && path.value.startsWith("workspace/metrics") &&
            AgentWorkspaceAdmissionIntent.SemanticCheck(path, action).isSupported()
        "SQL_EXPLORE_RUN", "SEMANTIC_EXPLORE_RUN" -> action == "run" &&
            AgentWorkspaceAdmissionIntent.ExploreRun(path, action).isSupported()
        "DASHBOARD_DRAFT_START" -> action == "start" && AgentWorkspaceAdmissionIntent.DashboardInvoke(path, action).isSupported()
        "DASHBOARD_DRAFT_APPLY" -> action == "apply" && AgentWorkspaceAdmissionIntent.DashboardInvoke(path, action).isSupported()
        "DASHBOARD_DRAFT_DISCARD" -> action == "discard" && AgentWorkspaceAdmissionIntent.DashboardInvoke(path, action).isSupported()
        "DASHBOARD_QUERY_REFRESH" -> action == "refresh" &&
            AgentWorkspaceAdmissionIntent.DashboardInvoke(path, action).isSupported()
        "DASHBOARD_SESSION_RESOLVE" -> action == "resolve" && AgentWorkspaceAdmissionIntent.DashboardSessionInvoke(path, action).isSupported()
        "DASHBOARD_SESSION_REFRESH" -> action == "refresh" && AgentWorkspaceAdmissionIntent.DashboardSessionInvoke(path, action).isSupported()
        "DASHBOARD_SESSION_OPEN" -> action == "open" && AgentWorkspaceAdmissionIntent.DashboardSessionInvoke(path, action).isSupported()
        null -> (action == "abort" && AgentPagesPaths.upload.matches(path.value)) || AgentWorkspaceAdmissionIntent.CatalogInvoke(path, action).isSupported() ||
            AgentWorkspaceAdmissionIntent.ConnectorDelete(path, action).isSupported() ||
            AgentWorkspaceAdmissionIntent.SemanticDelete(path, action).isSupported() ||
            AgentWorkspaceAdmissionIntent.SemanticRestore(path, action).isSupported() ||
            AgentWorkspaceAdmissionIntent.DashboardDelete(path, action).isSupported() ||
            AgentWorkspaceAdmissionIntent.DashboardRestore(path, action).isSupported() ||
            action == "cancel" && (AgentWorkspaceAdmissionIntent.ExploreRun(path, action).isSupported() ||
                AgentWorkspaceAdmissionIntent.DashboardInvoke(path, action).isSupported())
        else -> false
    }

    fun belongsToWorkspace(workspaceId: WorkspaceId, resource: AgentResourceEnvelope): Boolean = when (val body = resource.body) {
        is AgentSemanticModelResourceBody -> body.workspaceId == workspaceId
        is AgentMetricResourceBody -> body.workspaceId == workspaceId
        is AgentDashboardDefinitionVersionBody -> body.workspaceId == workspaceId
        is AgentSemanticDefinitionVersionBody -> body.workspaceId == workspaceId
        is AgentLineageResourceBody -> body.workspaceId == workspaceId.value
        else -> true
    }

    private fun dashboardVersionMatches(resource: AgentResourceEnvelope): Boolean {
        val body = resource.body as? AgentDashboardDefinitionVersionBody ?: return true
        return resource.path.value == "workspace/dashboards/${body.definitionId}/history/${body.changeId}"
    }

    private fun dashboardDraftStatusMatches(resource: AgentResourceEnvelope): Boolean {
        val body = resource.body as? AgentDashboardDraftStatusBody ?: return true
        val draftRun = Regex("^${Regex.escape(resource.path.value.removeSuffix("/status"))}/query-runs/${AgentPathPatterns.UUID_V7}$")
        return resource.path.value.endsWith("/draft/status") && body.targetQueryRuns.values.all { draftRun.matches(it.path.value) } &&
            body.readyResults.values.all { ready -> ready.queryRuns.values.all { draftRun.matches(it.value) } }
    }

    private fun semanticVersionMatches(resource: AgentResourceEnvelope): Boolean {
        val body = resource.body as? AgentSemanticDefinitionVersionBody ?: return true
        if (!AgentSemanticHistoryPaths.version.matches(resource.path.value)) return false
        val parts = resource.path.value.split('/')
        return parts[2] == body.name && parts[4] == body.definitionId.toString() && parts[5] == body.changeId.toString() &&
            body.kind == if (parts[1] == "semantic-models") AgentResourceKind.SEMANTIC_MODEL_VERSION else AgentResourceKind.METRIC_VERSION
    }

    private fun managementResourceMatches(resource: AgentResourceEnvelope): Boolean = when (val body = resource.body) {
        is AgentOrganizationBody -> resource.path.value == "organization"
        is AgentOrganizationMemberBody -> resource.path.value == "organization/members/${body.userId}"
        is AgentOrganizationInvitationBody -> resource.path.value == "organization/invitations/${body.id}"
        is AgentWorkspaceMemberBody.Member -> resource.path.value == "workspace/members/${body.userId}" || (AgentManagementPaths.organizationWorkspaceMember.matches(resource.path.value) && resource.path.value.endsWith("/members/${body.userId}"))
        is AgentWorkspaceMemberBody.Candidate -> resource.path.value == "workspace/member-candidates/${body.userId}"
        is AgentWorkspaceAccessBody -> resource.path.value == "workspace/access"
        is AgentWorkspaceAuthorizationBody -> resource.path.value == "workspace/authorization"
        is AgentLineageResourceBody -> resource.path.value == "workspace/lineage/${when(body.focus.ref.kind) { AgentManagementResourceKind.RELATION -> "relations"; AgentManagementResourceKind.SEMANTIC_MODEL -> "semantic-models"; AgentManagementResourceKind.METRIC -> "metrics" }}/${body.focus.ref.id}"
        else -> !AgentManagementPaths.organization(resource.path.value) && !AgentManagementPaths.workspace(resource.path.value)
    }

    private fun directChild(parent: String, path: String): Boolean =
        path.startsWith("$parent/") && path.removePrefix("$parent/").let { it.isNotEmpty() && '/' !in it }

    private fun uuidChild(parent: String, path: String): Boolean =
        directChild(parent, path) && UUID_V7.matches(path.substringAfterLast('/'))

    private val UUID_V7 = Regex(AgentPathPatterns.UUID_V7)
}
