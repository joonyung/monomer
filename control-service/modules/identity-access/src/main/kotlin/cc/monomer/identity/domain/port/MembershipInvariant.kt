package cc.monomer.identity.domain.port

open class MembershipInvariantException(message: String) : RuntimeException(message)

open class OrganizationMembershipInvariantException(message: String) : MembershipInvariantException(message)

class LastOrganizationAdminException : OrganizationMembershipInvariantException(
    "cannot remove or demote the last organization ADMIN",
)

class LastWorkspaceOwnerException : MembershipInvariantException(
    "cannot remove or demote the last personal workspace Owner",
)
