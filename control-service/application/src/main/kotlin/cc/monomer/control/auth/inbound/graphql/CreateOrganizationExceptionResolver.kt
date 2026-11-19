package cc.monomer.control.auth.inbound.graphql

import cc.monomer.identity.application.InvalidDisplayNameException
import graphql.ErrorClassification
import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.stereotype.Component

@Component
@org.springframework.core.annotation.Order(0)
class CreateOrganizationExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(ex: Throwable, env: DataFetchingEnvironment): GraphQLError? =
        when (ex) {
            is InvalidDisplayNameException -> buildError(ex, env, OrganizationErrorType.BAD_REQUEST)
            else -> null
        }

    private fun buildError(
        ex: RuntimeException,
        env: DataFetchingEnvironment,
        errorType: OrganizationErrorType,
    ): GraphQLError = GraphQLError.newError()
        .errorType(errorType)
        .message(ex.message)
        .path(env.executionStepInfo.path)
        .location(env.field.sourceLocation)
        .build()
}

enum class OrganizationErrorType : ErrorClassification {
    BAD_REQUEST,
}
