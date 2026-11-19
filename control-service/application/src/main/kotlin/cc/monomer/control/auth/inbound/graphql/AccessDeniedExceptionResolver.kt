package cc.monomer.control.auth.inbound.graphql

import cc.monomer.identity.application.AccessDeniedException
import cc.monomer.identity.application.ScopedResourceNotFoundException
import cc.monomer.control.lineage.LineageResourceUnavailableException
import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

@Component
@org.springframework.core.annotation.Order(0)
class AccessDeniedExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(ex: Throwable, env: DataFetchingEnvironment): GraphQLError? =
        when (ex) {
            is AccessDeniedException -> GraphQLError.newError()
                .errorType(ErrorType.FORBIDDEN)
                .message(ex.message)
                .path(env.executionStepInfo.path)
                .location(env.field.sourceLocation)
                .build()
            is ScopedResourceNotFoundException -> GraphQLError.newError()
                .errorType(ErrorType.NOT_FOUND)
                .message(ex.message)
                .path(env.executionStepInfo.path)
                .location(env.field.sourceLocation)
                .build()
            is LineageResourceUnavailableException -> GraphQLError.newError()
                .errorType(ErrorType.NOT_FOUND)
                .message(ex.message)
                .path(env.executionStepInfo.path)
                .location(env.field.sourceLocation)
                .extensions(buildMap {
                    put("code", "LINEAGE_RESOURCE_UNAVAILABLE")
                    ex.reason?.let { put("reason", it.name) }
                })
                .build()
            else -> null
        }
}
