package cc.monomer.control.auth.inbound.graphql

import cc.monomer.identity.domain.id.OrganizationId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.port.MembershipInvariantException
import cc.monomer.identity.domain.port.FreeOrganizationLimitException
import graphql.ErrorClassification
import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.stereotype.Component

@Component
@org.springframework.core.annotation.Order(0)
class OrganizationMembershipExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? =
        when (exception) {
            is OrganizationMemberNotFoundException -> buildError(
                exception,
                environment,
                OrganizationMembershipErrorType.NOT_FOUND,
            )
            is FreeOrganizationLimitException,
            is OrganizationMemberNoTransitionException,
            is MembershipInvariantException,
            -> buildError(exception, environment, OrganizationMembershipErrorType.CONFLICT)
            else -> null
        }

    private fun buildError(
        exception: Throwable,
        environment: DataFetchingEnvironment,
        errorType: OrganizationMembershipErrorType,
    ): GraphQLError = GraphQLError.newError()
        .errorType(errorType)
        .message(exception.message)
        .path(environment.executionStepInfo.path)
        .location(environment.field.sourceLocation)
        .build()
}

class OrganizationMemberNotFoundException(organizationId: OrganizationId, userId: UserId) : RuntimeException(
    "organization member userId=$userId not found in organizationId=$organizationId",
)

class OrganizationMemberNoTransitionException(organizationId: OrganizationId, userId: UserId, field: String) : RuntimeException(
    "organization member userId=$userId in organizationId=$organizationId already has the requested $field",
)

enum class OrganizationMembershipErrorType : ErrorClassification {
    NOT_FOUND,
    CONFLICT,
}
