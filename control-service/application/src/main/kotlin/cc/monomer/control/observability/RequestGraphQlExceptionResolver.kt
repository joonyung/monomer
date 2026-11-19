package cc.monomer.control.observability

import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.schema.DataFetchingEnvironment
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
internal class RequestGraphQlExceptionResolver : DataFetcherExceptionResolverAdapter() {
    init { setThreadLocalContextAware(true) }

    override fun resolveToSingleError(ex: Throwable, env: DataFetchingEnvironment): GraphQLError {
        log.atError()
            .addKeyValue("event", "graphql_data_fetch_failed")
            .addCorrelationId("request_id", MDC.get("request_id"))
            .addKeyValue("code", ErrorType.INTERNAL_ERROR.name)
            .setCause(ex)
            .log("graphql_data_fetch_failed")
        return GraphqlErrorBuilder.newError(env)
            .errorType(ErrorType.INTERNAL_ERROR)
            .message("${ErrorType.INTERNAL_ERROR} for ${env.executionId}")
            .build()
    }

    companion object {
        private val log = LoggerFactory.getLogger(RequestGraphQlExceptionResolver::class.java)
    }
}
