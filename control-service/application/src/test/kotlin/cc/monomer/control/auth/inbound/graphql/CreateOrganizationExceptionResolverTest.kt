package cc.monomer.control.auth.inbound.graphql

import cc.monomer.identity.application.InvalidDisplayNameException
import graphql.execution.ResultPath
import graphql.language.SourceLocation
import graphql.schema.DataFetchingEnvironment
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CreateOrganizationExceptionResolverTest {
    @Test
    fun `organization and workspace display name failures retain bad request code`() {
        listOf("createOrganization", "createWorkspace", "updateOrganizationDisplayName", "updateWorkspaceDisplayName").forEach { mutation ->
            val environment = mockk<DataFetchingEnvironment>()
            every { environment.executionStepInfo.path } returns ResultPath.rootPath().segment(mutation)
            every { environment.field.sourceLocation } returns SourceLocation(1, 1)
            val failure = InvalidDisplayNameException("displayName must not be blank")

            val error = checkNotNull(CreateOrganizationExceptionResolver().resolveException(failure, environment).block()).single()

            assertEquals(OrganizationErrorType.BAD_REQUEST, error.errorType)
            assertEquals("BAD_REQUEST", error.toSpecification()["extensions"].let { it as Map<*, *> }["classification"])
            assertEquals(failure.message, error.message)
            assertEquals(listOf(mutation), error.path)
        }
    }
}
