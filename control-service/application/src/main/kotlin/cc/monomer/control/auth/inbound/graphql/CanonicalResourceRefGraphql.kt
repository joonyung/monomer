package cc.monomer.control.auth.inbound.graphql

import cc.monomer.identity.api.WorkspaceId
import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import kotlin.uuid.Uuid
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

internal fun parseCanonicalUuid(rawValue: String, fieldName: String): Uuid {
    val parsedValue = runCatching { Uuid.parse(rawValue) }.getOrElse {
        throw InvalidCanonicalGraphqlInputException("$fieldName must be a canonical UUID")
    }
    if (parsedValue.toString() != rawValue) {
        throw InvalidCanonicalGraphqlInputException("$fieldName must be a canonical UUID")
    }
    return parsedValue
}

internal fun parseCanonicalWorkspaceId(rawValue: String): WorkspaceId {
    val parsedValue = parseCanonicalUuid(rawValue, "workspace id")
    return try {
        WorkspaceId(parsedValue)
    } catch (_: IllegalArgumentException) {
        throw InvalidCanonicalGraphqlInputException("workspace id must be a canonical UUID")
    }
}

class InvalidCanonicalGraphqlInputException(message: String) : RuntimeException(message)

@Component
@org.springframework.core.annotation.Order(0)
class InvalidCanonicalGraphqlInputExceptionResolver : DataFetcherExceptionResolverAdapter() {
    override fun resolveToSingleError(ex: Throwable, env: DataFetchingEnvironment): GraphQLError? =
        when (ex) {
            is InvalidCanonicalGraphqlInputException -> GraphQLError.newError()
                .errorType(ErrorType.BAD_REQUEST)
                .message(ex.message)
                .path(env.executionStepInfo.path)
                .location(env.field.sourceLocation)
                .build()
            else -> null
        }
}
