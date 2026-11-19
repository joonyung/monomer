package cc.monomer.control.auth.inbound.graphql

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

class UnsupportedReportingTimeZoneException : RuntimeException("Choose a time zone from the supported list.")

@Component
@org.springframework.core.annotation.Order(0)
class WorkspaceSettingsExceptionResolver : DataFetcherExceptionResolverAdapter() {
    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? =
        if (exception is UnsupportedReportingTimeZoneException) GraphQLError.newError()
            .errorType(ErrorType.BAD_REQUEST)
            .message(exception.message)
            .path(environment.executionStepInfo.path)
            .location(environment.field.sourceLocation)
            .extensions(mapOf("code" to "UNSUPPORTED_REPORTING_TIME_ZONE"))
            .build()
        else null
}
