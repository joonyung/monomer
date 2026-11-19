package cc.monomer.control.auth.inbound.graphql

import cc.monomer.connector.api.ConnectorProductType
import graphql.language.EnumTypeDefinition
import graphql.language.ObjectTypeDefinition
import graphql.schema.idl.SchemaParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AuthorizationGraphqlSchemaContractTest {

    private val registry = SchemaParser().parse(
        requireNotNull(javaClass.getResource("/graphql/schema.graphqls")).readText(),
    )

    @Test
    fun `public pages setting has an explicit boolean workspace mutation`() {
        val workspace = requireNotNull(registry.getTypeOrNull("Workspace", ObjectTypeDefinition::class.java))
        assertThat(graphql.language.AstPrinter.printAstCompact(workspace.fieldDefinitions.single { it.name == "publicPages" }.type))
            .isEqualTo("Boolean!")
        val mutations = requireNotNull(registry.getTypeOrNull("Mutation", ObjectTypeDefinition::class.java))
        val setting = mutations.fieldDefinitions.single { it.name == "setWorkspacePublicPages" }
        assertThat(setting.inputValueDefinitions.map { it.name }).containsExactly("workspaceId", "publicPages")
        assertThat(setting.inputValueDefinitions.map { graphql.language.AstPrinter.printAstCompact(it.type) })
            .containsExactly("ID!", "Boolean!")
        assertThat(graphql.language.AstPrinter.printAstCompact(setting.type)).isEqualTo("Workspace!")
    }

    @Test
    fun `reporting time zone is readable on both workspace types and set by an explicit mutation`() {
        assertThat(fieldTypes("Workspace")).contains("reportingTimeZone" to "String!")
        assertThat(fieldTypes("AccessibleWorkspace")).contains("reportingTimeZone" to "String!")
        val mutations = requireNotNull(registry.getTypeOrNull("Mutation", ObjectTypeDefinition::class.java))
        val setting = mutations.fieldDefinitions.single { it.name == "setWorkspaceReportingTimeZone" }
        assertThat(setting.inputValueDefinitions.map { it.name }).containsExactly("workspaceId", "timeZone")
        assertThat(setting.inputValueDefinitions.map { graphql.language.AstPrinter.printAstCompact(it.type) })
            .containsExactly("ID!", "String!")
        assertThat(setting.inputValueDefinitions.map { it.defaultValue }).containsOnlyNulls()
        assertThat(graphql.language.AstPrinter.printAstCompact(setting.type)).isEqualTo("Workspace!")
    }

    @Test
    fun `accessible workspaces carry the caller's direct workspace role`() {
        val query = requireNotNull(registry.getTypeOrNull("Query", ObjectTypeDefinition::class.java))
        assertThat(graphql.language.AstPrinter.printAstCompact(query.fieldDefinitions.single { it.name == "accessibleWorkspaces" }.type))
            .isEqualTo("[AccessibleWorkspace!]!")
        assertThat(fieldTypes("AccessibleWorkspace")).contains("role" to "WorkspaceRole!", "organizationId" to "ID!")
    }

    @Test
    fun `me exposes only provider and link time of login identities`() {
        assertThat(fieldTypes("Me")).containsExactly(
            "user" to "User!", "organizationMemberships" to "[OrganizationMembershipView!]!", "loginIdentities" to "[LoginIdentity!]!",
        )
        assertThat(fieldTypes("LoginIdentity")).containsExactly("provider" to "String!", "linkedAt" to "String!")
    }

    @Test
    fun `self display name mutation accepts no target identity`() {
        val mutations = requireNotNull(registry.getTypeOrNull("Mutation", ObjectTypeDefinition::class.java))
        val update = mutations.fieldDefinitions.single { it.name == "updateMyDisplayName" }
        assertThat(update.inputValueDefinitions.map { it.name }).containsExactly("displayName")
        assertThat(graphql.language.AstPrinter.printAstCompact(update.inputValueDefinitions.single().type)).isEqualTo("String!")
        assertThat(graphql.language.AstPrinter.printAstCompact(update.type)).isEqualTo("User!")
    }

    @Test
    fun `organization identity is its UUID and organization creation takes only a display name`() {
        val organization = requireNotNull(registry.getTypeOrNull("Organization", ObjectTypeDefinition::class.java))
        assertThat(organization.fieldDefinitions.map { it.name })
            .containsExactlyInAnyOrder("id", "displayName", "plan", "createdAt")
        val mutations = requireNotNull(registry.getTypeOrNull("Mutation", ObjectTypeDefinition::class.java))
        val createOrganization = mutations.fieldDefinitions.single { it.name == "createOrganization" }
        assertThat(graphql.language.AstPrinter.printAstCompact(createOrganization.type)).isEqualTo("OrganizationBootstrap!")
        assertThat(createOrganization.inputValueDefinitions.map { it.name }).containsExactly("displayName")
        assertThat(createOrganization.inputValueDefinitions.single().type)
            .isInstanceOf(graphql.language.NonNullType::class.java)
    }

    @Test
    fun `retired tenant and group contracts are absent`() {
        assertThat(registry.types().keys).noneMatch { it.startsWith("Account") }
        for (name in listOf("Group", "GroupMember", "WorkspaceGroupRole")) {
            assertThat(registry.getTypeOrNull(name)).isNull()
        }
        val queries = requireNotNull(registry.getTypeOrNull("Query", ObjectTypeDefinition::class.java))
        assertThat(queries.fieldDefinitions.map { it.name }).doesNotContain(
            "accountMembers", "accountMembershipInvitations", "groups", "groupMembers",
        )
        val mutations = requireNotNull(registry.getTypeOrNull("Mutation", ObjectTypeDefinition::class.java))
        assertThat(mutations.fieldDefinitions.map { it.name }).noneMatch { it.contains("Account") }.doesNotContain(
            "createGroup", "addGroupMember", "removeGroupMember", "setWorkspaceGroupRole", "removeWorkspaceGroupRole",
        )
    }

    @Test
    fun `manual membership requires invitation and owner recovery uses ordinary assignment`() {
        val mutations = requireNotNull(registry.getTypeOrNull("Mutation", ObjectTypeDefinition::class.java))
        assertThat(mutations.fieldDefinitions.map { it.name }).doesNotContain(
            "addOrganizationMember", "inviteOrganizationUser", "recoverWorkspaceOwner",
        ).contains(
            "createOrganizationMembershipInvitation", "resendOrganizationMembershipInvitation",
            "cancelOrganizationMembershipInvitation", "setWorkspaceMemberRole",
        )
        val invite = mutations.fieldDefinitions.single { it.name == "createOrganizationMembershipInvitation" }
        assertThat(invite.inputValueDefinitions.map { it.name }).containsExactly("organizationId", "email", "workspaceAccess")
        val createWorkspace = mutations.fieldDefinitions.single { it.name == "createWorkspace" }
        assertThat(createWorkspace.inputValueDefinitions.single { it.name == "initialOwnerUserId" }.type)
            .isInstanceOf(graphql.language.NonNullType::class.java)
    }

    @Test
    fun `workspace role contracts require explicit role choices`() {
        val access = requireNotNull(registry.getTypeOrNull("WorkspaceAccess", ObjectTypeDefinition::class.java))
        assertThat(access.fieldDefinitions.map { it.name }).containsExactly("granted", "organizationId", "role", "owners")
        assertThat(fieldTypes("WorkspaceAccess").single { it.first == "owners" }.second).isEqualTo("[WorkspaceOwnerSummary!]!")
        assertThat(fieldTypes("WorkspaceOwnerSummary")).containsExactly("userId" to "ID!", "displayName" to "String!")
        assertThat(enumValues("WorkspaceRole")).containsExactly("VIEWER", "EXPLORER", "EDITOR", "OWNER")
        val mutations = requireNotNull(registry.getTypeOrNull("Mutation", ObjectTypeDefinition::class.java))
        for (name in listOf("addWorkspaceMember", "setWorkspaceMemberRole")) {
            val mutation = mutations.fieldDefinitions.single { it.name == name }
            val role = mutation.inputValueDefinitions.single { it.name == "role" }
            assertThat(graphql.language.AstPrinter.printAstCompact(role.type)).isEqualTo("WorkspaceRole!")
            assertThat(role.defaultValue).isNull()
        }
        assertThat(mutations.fieldDefinitions.map { it.name }).doesNotContain(
            "assignAuthoringCapability", "revokeAuthoringCapability", "setResourceAccessDecision",
            "revokeResourceAccessDecision", "setWorkspaceRelationAccessPolicy", "removeWorkspaceOwner", "addWorkspaceOwner",
        )
        val assignments = requireNotNull(registry.getTypeOrNull("WorkspaceAuthorizationAssignments", ObjectTypeDefinition::class.java))
        assertThat(assignments.fieldDefinitions.map { it.name }).containsExactly("authorizationRevision", "members")
        assertThat(registry.getTypeOrNull("WorkspacePrincipal")).isNull()
        assertThat(registry.getTypeOrNull("PrincipalRef")).isNull()
        assertThat(registry.getTypeOrNull("AuthoringCapability")).isNull()
        assertThat(registry.getTypeOrNull("ResourceAccessDecision")).isNull()
    }

    @Test
    fun `organization settings read usage limits member join time and direct workspace roles`() {
        val queries = requireNotNull(registry.getTypeOrNull("Query", ObjectTypeDefinition::class.java))
        val usageQuery = queries.fieldDefinitions.single { it.name == "organizationUsage" }
        assertThat(usageQuery.inputValueDefinitions.map { it.name }).containsExactly("organizationId")
        assertThat(graphql.language.AstPrinter.printAstCompact(usageQuery.type)).isEqualTo("OrganizationUsage!")
        assertThat(fieldTypes("OrganizationUsage")).containsExactly(
            "limits" to "OrganizationResourceCounts", "usage" to "OrganizationResourceCounts!",
        )
        assertThat(fieldTypes("OrganizationResourceCounts")).containsExactly(
            "members" to "Int!", "workspaces" to "Int!", "pendingInvitations" to "Int!",
        )
        assertThat(fieldTypes("Organization").map { it.first }).doesNotContain("limits", "usage")
        assertThat(fieldTypes("OrganizationMembershipView")).containsExactly("organization" to "Organization!", "role" to "String!")
        assertThat(fieldTypes("OrganizationMembership")).containsExactly("organizationId" to "ID!", "userId" to "ID!", "role" to "String!")
        assertThat(fieldTypes("OrganizationMember")).containsExactly(
            "workspaces" to "[OrganizationMemberWorkspace!]!", "user" to "User!", "role" to "String!", "joinedAt" to "String!",
        )
        assertThat(fieldTypes("OrganizationMemberWorkspace")).containsExactly(
            "sample" to "WorkspaceSample",
            "id" to "ID!", "name" to "String!", "role" to "WorkspaceRole!", "memberCount" to "Int!", "ownerCount" to "Int!",
        )
        assertThat(fieldTypes("WorkspaceMember").map { it.first }).containsExactly("principalId", "displayName", "user", "role")
    }

    @Test
    fun `lineage exposes a membership readable paged Resource inventory`() {
        val queries = requireNotNull(registry.getTypeOrNull("Query", ObjectTypeDefinition::class.java))
        assertThat(queries.fieldDefinitions.map { it.name })
            .contains("lineageDefaultFocus", "lineageResources", "lineageFocusCone")

        val page = requireNotNull(registry.getTypeOrNull("LineageResourcePage", ObjectTypeDefinition::class.java))
        assertThat(page.fieldDefinitions.map { it.name })
            .containsExactly("items", "nextCursor")
        val resource = requireNotNull(registry.getTypeOrNull("LineageResource", ObjectTypeDefinition::class.java))
        assertThat(resource.fieldDefinitions.map { it.name })
            .containsExactly("resource", "label", "address", "description", "connector", "missing")
        val focusCone = requireNotNull(registry.getTypeOrNull("LineageFocusCone", ObjectTypeDefinition::class.java))
        assertThat(focusCone.fieldDefinitions.map { it.name })
            .containsExactly(
                "workspaceId",
                "focus",
                "ancestors",
                "descendants",
                "siblings",
                "edges",
                "siblingEdges",
                "entityPeers",
                "detail",
                "missingDefinitions",
                "dashboardUsages",
            )

        val connector = requireNotNull(registry.getTypeOrNull("LineageConnector", ObjectTypeDefinition::class.java))
        assertThat(connector.fieldDefinitions.map { it.name })
            .containsExactly("id", "displayName", "type")
        val detail = requireNotNull(registry.getTypeOrNull("LineageResourceDetail", ObjectTypeDefinition::class.java))
        assertThat(detail.fieldDefinitions.map { it.name })
            .containsExactly("relation", "semanticModel", "metric")
        val relationColumn = requireNotNull(registry.getTypeOrNull("LineageRelationColumn", ObjectTypeDefinition::class.java))
        assertThat(relationColumn.fieldDefinitions.map { it.name })
            .containsExactly("name", "nativeType", "nullable", "hasDefault", "primaryKey", "foreignKey")
        val metricInput = requireNotNull(registry.getTypeOrNull("LineageMetricInput", ObjectTypeDefinition::class.java))
        assertThat(metricInput.fieldDefinitions.map { it.name })
            .containsExactly("kind", "name", "semanticModelName")
        assertThat(enumValues("LineageConnectorProductType"))
            .containsExactlyElementsOf(ConnectorProductType.entries.map { it.name })
        assertThat(enumValues("LineageMetricInputKind")).containsExactly("MEASURE", "METRIC")
        assertThat(registry.getTypeOrNull("LineageAccessEvaluationEdge")).isNull()
    }

    @Test
    fun `service era owner authorization contract is absent`() {
        val queries = requireNotNull(registry.getTypeOrNull("Query", ObjectTypeDefinition::class.java))
        assertThat(queries.fieldDefinitions.map { it.name })
            .doesNotContain("resourceMutationAuthorization")
        assertThat(registry.getTypeOrNull("CapabilityAssignment")).isNull()
        assertThat(registry.getTypeOrNull("ResourceBinding")).isNull()
        assertThat(registry.getTypeOrNull("CapabilityAssignmentInput")).isNull()
        assertThat(registry.getTypeOrNull("ResourceBindingInput")).isNull()
        assertThat(registry.getTypeOrNull("MutationOperation")).isNull()
        assertThat(registry.getTypeOrNull("AuthorizationScopeInput")).isNull()
        assertThat(registry.getTypeOrNull("AuthorizedMutationContext")).isNull()
    }

    private fun fieldTypes(name: String): List<Pair<String, String>> =
        (requireNotNull(registry.getTypeOrNull(name, ObjectTypeDefinition::class.java)))
            .fieldDefinitions
            .map { it.name to graphql.language.AstPrinter.printAstCompact(it.type) }

    private fun enumValues(name: String): List<String> =
        (requireNotNull(registry.getTypeOrNull(name, EnumTypeDefinition::class.java)))
            .enumValueDefinitions
            .map { it.name }
}
