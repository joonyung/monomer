package cc.monomer.control.auth.inbound.graphql

import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.auth.inbound.oauth.JwtAuthentication
import cc.monomer.identity.api.PresentedCredential
import cc.monomer.identity.application.permission.WorkspaceAccessService
import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.UserCredentialProvenance
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.uuid.Uuid
import kotlinx.coroutines.reactor.mono
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.ReactiveSecurityContextHolder

class WorkspaceAccessResolverTest {
    private val admission = mockk<ControlAdmissionFacade>()
    private val workspaceAccess = mockk<WorkspaceAccessService>()
    private val resolver = WorkspaceAccessResolver(workspaceAccess, admission)
    private val authentication = JwtAuthentication(
        UserCredentialProvenance.BrowserSession(UserId(Uuid.generateV7()), SessionId(Uuid.generateV7())),
        PresentedCredential("web-fixture"),
    )

    @Test
    fun `nil and non IETF workspace ids are invalid input before admission`() {
        for (workspaceId in listOf("00000000-0000-0000-0000-000000000000", "019e0000-0000-7000-0000-0000000000a1")) {
            assertThatThrownBy {
                mono { resolver.workspaceAccess(workspaceId) }
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                    .block()
            }.isInstanceOf(InvalidCanonicalGraphqlInputException::class.java)
                .hasMessage("workspace id must be a canonical UUID")
        }
        coVerify(exactly = 0) { admission.authorizeWorkspaceMembership(any()) }
    }
}
