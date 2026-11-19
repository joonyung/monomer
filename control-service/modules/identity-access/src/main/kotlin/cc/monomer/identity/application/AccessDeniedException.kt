package cc.monomer.identity.application


/**
 * Raised when an acting user lacks the membership/role required for a query or mutation.
 * Mapped to a GraphQL `FORBIDDEN` error at the inbound boundary.
 */
open class AccessDeniedException(message: String) : RuntimeException(message)

/** The acting user is an Organization member, and only the Organization Admin role allows this action. */
class OrganizationAdminRequiredException(message: String) : AccessDeniedException(message)

class ScopedResourceNotFoundException(message: String) : RuntimeException(message)
