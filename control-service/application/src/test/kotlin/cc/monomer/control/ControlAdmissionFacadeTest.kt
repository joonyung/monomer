@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package cc.monomer.control

import cc.monomer.identity.api.*
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ControlAdmissionFacadeTest {
    private val workspaceId = WorkspaceId.generate()
    private val actor = CanonicalPrincipal(PrincipalId.generate(), PrincipalKind.USER)
    private val organizationId = OrganizationId.generate()
    private val now = Instant.parse("2026-09-29T00:00:00Z")
    private val identity = mockk<IdentityAdmissionPort>()
    private val facade = ControlAdmissionFacade(identity, Clock.fixed(now, ZoneOffset.UTC))
    private val requests = mutableListOf<IdentityRoleAdmissionRequest>()

    private fun evidence(role: WorkspaceRole, workspace: WorkspaceId = workspaceId, plan: OrganizationPlan = OrganizationPlan.FREE) =
        IdentityRoleEvidence(plan, actor, organizationId, workspace, AuthorizationRevision(1), CredentialKind.SESSION, CredentialSurface.WEB, null, role)

    @Test
    fun `mutation role thresholds preserve actor plan and credential provenance`() = runBlocking {
        val required = mapOf(
            MutationIntent.CONNECTOR_CREATE to WorkspaceRole.OWNER,
            MutationIntent.CONNECTOR_UPDATE to WorkspaceRole.OWNER,
            MutationIntent.CONNECTOR_DELETE to WorkspaceRole.OWNER,
            MutationIntent.CATALOG_SELECTION_UPDATE to WorkspaceRole.EDITOR,
            MutationIntent.SEMANTIC_MODEL_CREATE to WorkspaceRole.EDITOR,
            MutationIntent.SEMANTIC_MODEL_UPDATE to WorkspaceRole.EDITOR,
            MutationIntent.SEMANTIC_MODEL_DELETE to WorkspaceRole.EDITOR,
            MutationIntent.SEMANTIC_TIME_SPINE_UPDATE to WorkspaceRole.EDITOR,
            MutationIntent.METRIC_CREATE to WorkspaceRole.EDITOR,
            MutationIntent.METRIC_UPDATE to WorkspaceRole.EDITOR,
            MutationIntent.METRIC_DELETE to WorkspaceRole.EDITOR,
        )
        assertEquals(MutationIntent.entries.toSet(), required.keys)
        for (plan in OrganizationPlan.entries) {
            coEvery { identity.decide(any<IdentityRoleAdmissionRequest>()) } answers {
                val request = firstArg<IdentityRoleAdmissionRequest>()
                requests += request
                IdentityRoleAdmissionDecision.Authorized(evidence(WorkspaceRole.OWNER, plan = plan))
            }
            for ((intent, role) in required) {
                val result = assertIs<ControlAdmissionDecision.Admitted<AuthorizedMutationContext>>(
                    facade.authorizeMutation(MutationAdmissionRequest(AdmissionRequest(workspaceId, PresentedCredential("session"), null), intent)),
                )
                assertEquals(role, requests.last().requiredRole)
                assertEquals(plan, result.context.organizationPlan)
                assertEquals(actor, result.context.actor)
                assertEquals(CredentialSurface.WEB, result.context.credentialSurface)
                assertEquals(now, result.context.admittedAt)
            }
        }
    }

    @Test
    fun `Explore and catalog execution require Explorer and propagate denial`() = runBlocking {
        coEvery { identity.decide(any<IdentityRoleAdmissionRequest>()) } answers {
            requests += firstArg<IdentityRoleAdmissionRequest>()
            IdentityRoleAdmissionDecision.Denied(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE)
        }
        for (intent in ExecutionIntent.entries) {
            assertIs<ControlAdmissionDecision.Denied>(facade.authorizeExecution(
                ExecutionAdmissionRequest(workspaceId, PresentedCredential("session"), null, intent),
            ))
            assertEquals(WorkspaceRole.EXPLORER, requests.last().requiredRole)
        }
    }

    @Test
    fun `wrong workspace or insufficient role evidence fails closed`() = runBlocking {
        for (evidence in listOf(evidence(WorkspaceRole.OWNER, WorkspaceId.generate()), evidence(WorkspaceRole.VIEWER))) {
            coEvery { identity.decide(any<IdentityRoleAdmissionRequest>()) } returns IdentityRoleAdmissionDecision.Authorized(evidence)
            val denial = assertIs<ControlAdmissionDecision.Denied>(facade.authorizeWorkspaceRole(
                WorkspaceRoleAdmissionRequest(workspaceId, PresentedCredential("session"), null, WorkspaceRole.EXPLORER),
            ))
            assertEquals(ControlAdmissionDenial.IDENTITY_EVIDENCE_MISMATCH, denial.reason)
        }
    }

    @Test
    fun `public reader credential admission preserves verified actor without membership`() = runBlocking {
        val evidence = IdentityCredentialEvidence(actor, CredentialKind.SESSION, CredentialSurface.WEB, null)
        coEvery { identity.decide(any<IdentityCredentialAdmissionRequest>()) } returns IdentityCredentialAdmissionDecision.Authorized(evidence)
        val result = assertIs<ControlAdmissionDecision.Admitted<IdentityCredentialEvidence>>(facade.authorizeCredential(
            IdentityCredentialAdmissionRequest(workspaceId, PresentedCredential("session"), null),
        ))
        assertEquals(evidence, result.context)
    }
}
