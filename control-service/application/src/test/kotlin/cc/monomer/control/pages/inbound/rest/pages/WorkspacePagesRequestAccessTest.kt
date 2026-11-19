package cc.monomer.control.pages.inbound.rest.pages

import cc.monomer.control.ControlAdmissionFacade
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange

class WorkspacePagesRequestAccessTest {
    private val admission = mockk<ControlAdmissionFacade>()
    private val access = WorkspacePagesRequestAccess(admission)

    @Test
    fun `nil and non IETF workspace ids are invalid requests before admission`() = runTest {
        for (rawWorkspaceId in listOf(
            "00000000-0000-0000-0000-000000000000",
            "019e501f-0000-7000-0000-000000000011",
            "019E501F-0000-7000-8000-000000000011",
            "not-a-uuid",
        )) {
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v2/workspaces/$rawWorkspaceId/pages/tree"))
            val failure = runCatching { access.require(exchange, rawWorkspaceId) }.exceptionOrNull()

            assertThat(failure).isInstanceOf(PagesRestProblem::class.java)
            failure as PagesRestProblem
            assertThat(failure.status).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(failure.code).isEqualTo("INVALID_WORKSPACE_ID")
        }
        coVerify(exactly = 0) { admission.authorizeWorkspaceRole(any()) }
    }
}
