package cc.monomer.control.auth.inbound.graphql

import cc.monomer.identity.application.grant.InvalidWorkspaceRoleTargetException
import graphql.ErrorClassification
import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

@Component
@org.springframework.core.annotation.Order(0)
class AuthorizationAssignmentExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(
        exception: Throwable,
        environment: DataFetchingEnvironment,
    ): GraphQLError? = when (exception) {
        is InvalidWorkspaceRoleTargetException,
        -> error(exception, environment, ErrorType.BAD_REQUEST, "BAD_REQUEST")
        else -> null
    }

    private fun error(
        exception: Throwable,
        environment: DataFetchingEnvironment,
        errorType: ErrorClassification,
        code: String,
    ): GraphQLError = GraphQLError.newError()
        .errorType(errorType)
        .message(exception.message)
        .path(environment.executionStepInfo.path)
        .location(environment.field.sourceLocation)
        .extensions(mapOf("code" to code))
        .build()
}
