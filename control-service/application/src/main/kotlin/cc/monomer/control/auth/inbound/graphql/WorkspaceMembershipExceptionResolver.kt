package cc.monomer.control.auth.inbound.graphql

import cc.monomer.identity.application.workspace.WorkspaceMemberRoleConflictException
import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.stereotype.Component

@Component
@org.springframework.core.annotation.Order(0)
class WorkspaceMembershipExceptionResolver : DataFetcherExceptionResolverAdapter() {
    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? =
        if (exception is WorkspaceMemberRoleConflictException) GraphQLError.newError()
            .errorType(OrganizationMembershipErrorType.CONFLICT)
            .message("This member already has a different role. Change the existing member's role.")
            .path(environment.executionStepInfo.path)
            .location(environment.field.sourceLocation)
            .build()
        else null
}
