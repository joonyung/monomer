package cc.monomer.control.semantic.inbound.rest

import cc.monomer.control.*
import cc.monomer.identity.api.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ResponseStatusException

class SemanticWorkspaceRequestAuthorizerTest {
    private val workspace = WorkspaceId.generate()
    private val facade = mockk<ControlAdmissionFacade>()
    private val authorizer = SemanticWorkspaceRequestAuthorizer(facade)
    private val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/").header("Authorization", "Bearer session-token"))

    @Test fun `query requires Explorer with original workspace and credential`() = runTest {
        coEvery { facade.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Admitted(mockk())
        authorizer.authorizeExplorer(exchange, workspace)
        coVerify(exactly = 1) { facade.authorizeWorkspaceRole(match {
            it.workspaceId == workspace && it.credential == PresentedCredential("session-token") &&
                it.expectedOAuthResource == null && it.requiredRole == WorkspaceRole.EXPLORER
        }) }
    }

    @Test fun `Viewer denial is never converted to empty candidate list`() = runTest {
        coEvery { facade.authorizeWorkspaceRole(any()) } returns ControlAdmissionDecision.Denied(
            ControlAdmissionStage.IDENTITY, ControlAdmissionDenial.Identity(IdentityAdmissionDenial.PERMISSION_UNAVAILABLE),
        )
        assertEquals(403, assertFailsWith<ResponseStatusException> { authorizer.authorizeExplorer(exchange, workspace) }.statusCode.value())
    }

    @Test fun `definition mutation preserves the precise intent and credential`() = runTest {
        val context = mockk<AuthorizedMutationContext>()
        coEvery { facade.authorizeMutation(any()) } returns ControlAdmissionDecision.Admitted(context)
        for (intent in listOf(MutationIntent.SEMANTIC_MODEL_CREATE, MutationIntent.SEMANTIC_MODEL_UPDATE,
            MutationIntent.METRIC_CREATE, MutationIntent.METRIC_UPDATE, MutationIntent.SEMANTIC_TIME_SPINE_UPDATE)) {
            assertSame(context, authorizer.authorizeMutation(exchange, workspace, intent))
            coVerify { facade.authorizeMutation(match {
                it.intent == intent && it.common.workspaceId == workspace &&
                    it.common.credential == PresentedCredential("session-token") && it.common.expectedOAuthResource == null
            }) }
        }
    }
}
