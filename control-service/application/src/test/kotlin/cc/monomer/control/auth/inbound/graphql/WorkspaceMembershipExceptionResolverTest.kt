package cc.monomer.control.auth.inbound.graphql

import cc.monomer.identity.application.workspace.WorkspaceMemberRoleConflictException
import graphql.execution.ResultPath
import graphql.language.SourceLocation
import graphql.schema.DataFetchingEnvironment
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkspaceMembershipExceptionResolverTest {
    @Test
    fun `role conflict maps outside Organization resolver with surface neutral guidance`() {
        val environment = mockk<DataFetchingEnvironment>()
        every { environment.executionStepInfo.path } returns ResultPath.rootPath().segment("addWorkspaceMember")
        every { environment.field.sourceLocation } returns SourceLocation(1, 1)
        val conflict = WorkspaceMemberRoleConflictException()
        val error = checkNotNull(WorkspaceMembershipExceptionResolver().resolveException(conflict, environment).block()).single()
        assertEquals(OrganizationMembershipErrorType.CONFLICT, error.errorType)
        assertEquals("This member already has a different role. Change the existing member's role.", error.message)
        assertEquals(listOf("addWorkspaceMember"), error.path)
        assertNull(OrganizationMembershipExceptionResolver().resolveException(conflict, environment).block())
    }
}
