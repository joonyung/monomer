package cc.monomer.control.auth.inbound.graphql

import cc.monomer.identity.application.grant.InvalidWorkspaceRoleTargetException
import graphql.execution.ResultPath
import graphql.language.SourceLocation
import graphql.schema.DataFetchingEnvironment
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.graphql.execution.ErrorType
import kotlin.test.assertEquals

class AuthorizationAssignmentExceptionResolverTest {
    @Test
    fun `invalid member assignment returns a safe target error`() {
        val environment = mockk<DataFetchingEnvironment>()
        every { environment.executionStepInfo.path } returns ResultPath.rootPath().segment("setWorkspaceMemberRole")
        every { environment.field.sourceLocation } returns SourceLocation(1, 1)
        val failure = InvalidWorkspaceRoleTargetException("role target must be an active direct Workspace user")
        val error = checkNotNull(AuthorizationAssignmentExceptionResolver().resolveException(failure, environment).block()).single()
        assertEquals(ErrorType.BAD_REQUEST, error.errorType)
        assertEquals(failure.message, error.message)
    }
}
