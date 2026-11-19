@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.identity.application

import cc.monomer.identity.api.AgentClientId
import cc.monomer.identity.api.AgentScope
import cc.monomer.identity.api.CanonicalPrincipal
import cc.monomer.identity.api.CredentialKind
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.ExpectedOAuthResource
import cc.monomer.identity.api.IdentityAdmissionDenial
import cc.monomer.identity.api.IdentityRoleAdmissionDecision
import cc.monomer.identity.api.IdentityRoleAdmissionRequest
import cc.monomer.identity.api.IdentityMembershipAdmissionDecision
import cc.monomer.identity.api.IdentityMembershipAdmissionRequest
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.PrincipalKind
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.identity.api.verifiedHost
import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.id.WorkspaceId as DomainWorkspaceId
import cc.monomer.identity.domain.model.OrganizationRole
import cc.monomer.identity.domain.model.AuthorizationRevision as DomainAuthorizationRevision
import cc.monomer.identity.domain.model.WorkspaceActorAuthorizationFacts
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.uuid.Uuid

class IdentityAdmissionServiceTest {
    @Test
    fun `definition deletes require Editor for browser and Agent admission`() = runBlocking<Unit> {
        val intents = listOf(cc.monomer.identity.api.MutationIntent.SEMANTIC_MODEL_DELETE, cc.monomer.identity.api.MutationIntent.METRIC_DELETE)
        for (intent in intents) {
            assertThat(intent.requiredRole).isEqualTo(cc.monomer.identity.api.WorkspaceRole.EDITOR)
            for (kind in CredentialKind.entries) for (held in cc.monomer.identity.api.WorkspaceRole.entries) {
                val decision = service(snapshot(kind, setOf(AgentScope.WORKSPACE.wireValue), held)).decide(
                    IdentityRoleAdmissionRequest(workspaceId, PresentedCredential("credential"), null, intent.requiredRole))
                if (held >= cc.monomer.identity.api.WorkspaceRole.EDITOR) assertThat(decision).isInstanceOf(IdentityRoleAdmissionDecision.Authorized::class.java)
                else assertThat(decision).isEqualTo(IdentityRoleAdmissionDecision.Denied(IdentityAdmissionDenial.PERMISSION_UNAVAILABLE))
            }
        }
    }

    private val now = Instant.parse("2026-08-20T00:00:00Z")
    private val organizationId = cc.monomer.identity.api.OrganizationId(id(1))
    private val workspaceId = WorkspaceId(id(2))
    private val user = CanonicalPrincipal(PrincipalId(id(3)), PrincipalKind.USER)
    private val agentClient = AgentClientId("https://claude.ai/oauth/mcp-oauth-client-metadata")

    @Test
    fun `browser session membership is admitted without an agent scope`() = runBlocking<Unit> {
        val decision = service(snapshot(CredentialKind.SESSION, emptySet()))
            .decide(
                IdentityMembershipAdmissionRequest(
                    workspaceId = workspaceId,
                    credential = PresentedCredential("browser-session"),
                    expectedOAuthResource = null,
                ),
            )

        assertThat(decision).isInstanceOf(IdentityMembershipAdmissionDecision.Authorized::class.java)
    }

    @Test
    fun `Agent OAuth membership requires workspace read scope`() = runBlocking<Unit> {
        val request = IdentityMembershipAdmissionRequest(
            workspaceId = workspaceId,
            credential = PresentedCredential("agent-oauth"),
            expectedOAuthResource = ExpectedOAuthResource.AGENT_REST,
        )

        val denied = service(snapshot(CredentialKind.AGENT_OAUTH, emptySet())).decide(request)
        assertThat(denied).isEqualTo(
            IdentityMembershipAdmissionDecision.Denied(IdentityAdmissionDenial.AGENT_SCOPE_INSUFFICIENT),
        )

        val admitted = service(
            snapshot(CredentialKind.AGENT_OAUTH, setOf(AgentScope.WORKSPACE.wireValue)),
        ).decide(request)
        assertThat(admitted).isInstanceOf(IdentityMembershipAdmissionDecision.Authorized::class.java)
    }

    @Test
    fun `browser and agent admission enforce all sixteen role pairs and report effective role`() = runBlocking<Unit> {
        for (kind in CredentialKind.entries) for (held in cc.monomer.identity.api.WorkspaceRole.entries) for (required in cc.monomer.identity.api.WorkspaceRole.entries) {
            val decision = service(snapshot(kind, setOf(AgentScope.WORKSPACE.wireValue), held)).decide(
                IdentityRoleAdmissionRequest(workspaceId, PresentedCredential("credential"), null, required),
            )
            if (held.ordinal >= required.ordinal) {
                val evidence = (decision as IdentityRoleAdmissionDecision.Authorized).evidence
                assertThat(evidence.role).isEqualTo(held)
                assertThat(evidence.organizationPlan).isEqualTo(cc.monomer.identity.api.OrganizationPlan.FREE)
                assertThat(evidence.actor).isEqualTo(user)
                assertThat(evidence.authorizationRevision.value).isEqualTo(7)
                assertThat(evidence.credentialSurface).isEqualTo(if (kind == CredentialKind.SESSION) CredentialSurface.WEB else CredentialSurface.AGENT)
                assertThat(evidence.agentClientId).isEqualTo(if (kind == CredentialKind.SESSION) null else agentClient)
            } else assertThat(decision).isEqualTo(IdentityRoleAdmissionDecision.Denied(IdentityAdmissionDenial.PERMISSION_UNAVAILABLE))
        }
    }

    @Test
    fun `credential denial precedes scope membership and role in every admission path`() = runBlocking<Unit> {
        val states = mapOf(
            CanonicalCredentialState.Unavailable to IdentityAdmissionDenial.CREDENTIAL_UNAVAILABLE,
            CanonicalCredentialState.Expired to IdentityAdmissionDenial.CREDENTIAL_EXPIRED,
            CanonicalCredentialState.Revoked to IdentityAdmissionDenial.CREDENTIAL_REVOKED,
            CanonicalCredentialState.BindingMismatch to IdentityAdmissionDenial.AGENT_BINDING_MISMATCH,
            CanonicalCredentialState.PrincipalMismatch to IdentityAdmissionDenial.PRINCIPAL_MISMATCH,
        )
        for ((state, reason) in states) {
            val admission = service(snapshot(CredentialKind.AGENT_OAUTH, emptySet()).copy(credential = state, workspaceAuthorization = null))
            assertThat(admission.decide(IdentityMembershipAdmissionRequest(workspaceId, PresentedCredential("credential"), null)))
                .isEqualTo(IdentityMembershipAdmissionDecision.Denied(reason))
            assertThat(admission.decide(IdentityRoleAdmissionRequest(workspaceId, PresentedCredential("credential"), null, cc.monomer.identity.api.WorkspaceRole.OWNER)))
                .isEqualTo(IdentityRoleAdmissionDecision.Denied(reason))
            assertThat(admission.decide(cc.monomer.identity.api.IdentityCredentialAdmissionRequest(workspaceId, PresentedCredential("credential"), null)))
                .isEqualTo(cc.monomer.identity.api.IdentityCredentialAdmissionDecision.Denied(reason))
        }
    }

    @Test
    fun `nonmember Admin governance does not admit ordinary workspace requests`() = runBlocking<Unit> {
        for (kind in CredentialKind.entries) {
            val initial = snapshot(kind, setOf(AgentScope.WORKSPACE.wireValue))
            val facts = checkNotNull(initial.workspaceAuthorization).copy(organizationRole = OrganizationRole.ADMIN, role = null)
            val admission = service(initial.copy(workspaceAuthorization = facts))
            assertThat(admission.decide(IdentityMembershipAdmissionRequest(workspaceId, PresentedCredential("credential"), null)))
                .isEqualTo(IdentityMembershipAdmissionDecision.Denied(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE))
            assertThat(admission.decide(IdentityRoleAdmissionRequest(workspaceId, PresentedCredential("credential"), null, cc.monomer.identity.api.WorkspaceRole.VIEWER)))
                .isEqualTo(IdentityRoleAdmissionDecision.Denied(IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE))
        }
    }

    @Test
    fun `agent scope is required for owner role`() = runBlocking<Unit> {
        val decision = service(snapshot(CredentialKind.AGENT_OAUTH, emptySet(), cc.monomer.identity.api.WorkspaceRole.OWNER)).decide(
            IdentityRoleAdmissionRequest(workspaceId, PresentedCredential("credential"), ExpectedOAuthResource.AGENT_MCP, cc.monomer.identity.api.WorkspaceRole.OWNER),
        )
        assertThat(decision).isEqualTo(IdentityRoleAdmissionDecision.Denied(IdentityAdmissionDenial.AGENT_SCOPE_INSUFFICIENT))
    }

    @Test
    fun `agent scope precedes missing workspace membership and role in every admission path`() = runBlocking<Unit> {
        val initial = snapshot(CredentialKind.AGENT_OAUTH, emptySet())
        for (facts in listOf(null, checkNotNull(initial.workspaceAuthorization).copy(role = null), initial.workspaceAuthorization)) {
            val admission = service(initial.copy(workspaceAuthorization = facts))
            assertThat(admission.decide(IdentityMembershipAdmissionRequest(workspaceId, PresentedCredential("credential"), ExpectedOAuthResource.AGENT_REST)))
                .isEqualTo(IdentityMembershipAdmissionDecision.Denied(IdentityAdmissionDenial.AGENT_SCOPE_INSUFFICIENT))
            assertThat(admission.decide(IdentityRoleAdmissionRequest(workspaceId, PresentedCredential("credential"), ExpectedOAuthResource.AGENT_REST, cc.monomer.identity.api.WorkspaceRole.VIEWER)))
                .isEqualTo(IdentityRoleAdmissionDecision.Denied(IdentityAdmissionDenial.AGENT_SCOPE_INSUFFICIENT))
            assertThat(admission.decide(cc.monomer.identity.api.IdentityCredentialAdmissionRequest(workspaceId, PresentedCredential("credential"), ExpectedOAuthResource.AGENT_REST)))
                .isEqualTo(cc.monomer.identity.api.IdentityCredentialAdmissionDecision.Denied(IdentityAdmissionDenial.AGENT_SCOPE_INSUFFICIENT))
        }
    }

    @Test
    fun `membership precedes role and credential admission does not require membership`() = runBlocking<Unit> {
        val initial = snapshot(CredentialKind.AGENT_OAUTH, setOf(AgentScope.WORKSPACE.wireValue), cc.monomer.identity.api.WorkspaceRole.VIEWER)
        val initialFacts = checkNotNull(initial.workspaceAuthorization)
        for ((facts, denial) in listOf(
            null to IdentityAdmissionDenial.WORKSPACE_UNAVAILABLE,
            initialFacts.copy(organizationRole = null) to IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE,
            initialFacts.copy(role = null) to IdentityAdmissionDenial.WORKSPACE_MEMBERSHIP_UNAVAILABLE,
        )) {
            val admission = service(initial.copy(workspaceAuthorization = facts))
            assertThat(admission.decide(IdentityMembershipAdmissionRequest(workspaceId, PresentedCredential("credential"), ExpectedOAuthResource.AGENT_REST)))
                .isEqualTo(IdentityMembershipAdmissionDecision.Denied(denial))
            assertThat(admission.decide(IdentityRoleAdmissionRequest(workspaceId, PresentedCredential("credential"), ExpectedOAuthResource.AGENT_REST, cc.monomer.identity.api.WorkspaceRole.OWNER)))
                .isEqualTo(IdentityRoleAdmissionDecision.Denied(denial))
            assertThat(admission.decide(cc.monomer.identity.api.IdentityCredentialAdmissionRequest(workspaceId, PresentedCredential("credential"), ExpectedOAuthResource.AGENT_REST)))
                .isInstanceOf(cc.monomer.identity.api.IdentityCredentialAdmissionDecision.Authorized::class.java)
        }
    }

    @Test
    fun `agent evidence names its app and web evidence names none`() {
        assertThatThrownBy {
            cc.monomer.identity.api.IdentityCredentialEvidence(user, CredentialKind.AGENT_OAUTH, CredentialSurface.AGENT, null)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            cc.monomer.identity.api.IdentityCredentialEvidence(user, CredentialKind.SESSION, CredentialSurface.WEB, agentClient)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AgentClientId(" ") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AgentClientId("https://app.example/\n") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AgentClientId("https://app.example/a b") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AgentClientId("https://app.example/\u00A0") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AgentClientId("a".repeat(AgentClientId.MAX_LENGTH + 1)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(AgentClientId("a".repeat(AgentClientId.MAX_LENGTH)).value).hasSize(AgentClientId.MAX_LENGTH)
    }

    @Test
    fun `the cutover guard rejects exactly the characters a client id may not hold`() {
        val sqlClass = listOf(0x01..0x20, 0x7F..0xA0, 0x1680..0x1680, 0x2000..0x200A, 0x2028..0x2029, 0x202F..0x202F, 0x205F..0x205F, 0x3000..0x3000)
        for (code in 1..0xFFFF) {
            val character = code.toChar()
            val rejected = character.isWhitespace() || character.isISOControl()
            assertThat(sqlClass.any { code in it }).describedAs("U+%04X", code).isEqualTo(rejected)
        }
    }

    @Test
    fun `only an https client id has a verified host`() {
        assertThat(agentClient.verifiedHost()).isEqualTo("claude.ai")
        assertThat(AgentClientId("https://Chatgpt.com/metadata.json").verifiedHost()).isEqualTo("chatgpt.com")
        assertThat(AgentClientId("monomer-native").verifiedHost()).isNull()
        assertThat(AgentClientId("http://localhost/metadata").verifiedHost()).isNull()
    }

    @Test
    fun `presented credential is redacted in diagnostics`() {
        val raw = "raw-agent-token"
        val request = IdentityMembershipAdmissionRequest(
            workspaceId = workspaceId,
            credential = PresentedCredential(raw),
            expectedOAuthResource = null,
        )

        assertThat(request.credential.toString()).isEqualTo("[REDACTED]")
        assertThat(request.toString()).doesNotContain(raw)
    }

    private fun service(snapshot: CanonicalIdentityAdmissionSnapshot): IdentityAdmissionService =
        IdentityAdmissionService(FakeSnapshotPort(snapshot))

    private fun snapshot(
        kind: CredentialKind,
        scopes: Set<String>,
        role: cc.monomer.identity.api.WorkspaceRole = cc.monomer.identity.api.WorkspaceRole.EXPLORER,
    ): CanonicalIdentityAdmissionSnapshot =
        CanonicalIdentityAdmissionSnapshot(
            organizationPlan = cc.monomer.identity.api.OrganizationPlan.FREE,
            credential = CanonicalCredentialState.Active(
                actor = user,
                kind = kind,
                surface = if (kind == CredentialKind.SESSION) CredentialSurface.WEB else CredentialSurface.AGENT,
                agentClientId = if (kind == CredentialKind.SESSION) null else agentClient,
                expiresAt = now.plusSeconds(60),
                agentScopes = scopes,
            ),
            workspaceAuthorization = WorkspaceActorAuthorizationFacts(
                actorUserId = UserId(user.id.value),
                workspaceId = DomainWorkspaceId(workspaceId.value),
                organizationId = OrganizationId(organizationId.value),
                authorizationRevision = DomainAuthorizationRevision(7),
                organizationRole = OrganizationRole.MEMBER,
                role = role,
            ),
        )

    private class FakeSnapshotPort(
        private val snapshot: CanonicalIdentityAdmissionSnapshot,
    ) : CanonicalIdentityAdmissionSnapshotPort {
        override suspend fun read(
            credential: PresentedCredential,
            workspaceId: WorkspaceId,
            expectedOAuthResource: ExpectedOAuthResource?,
        ): CanonicalIdentityAdmissionSnapshot = snapshot
    }

    private fun id(value: Int): Uuid =
        Uuid.parse("00000000-0000-7000-8000-${value.toString().padStart(12, '0')}")
}
