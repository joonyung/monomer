package cc.monomer.identity.domain.port

import cc.monomer.identity.domain.model.FreeOrganizationLimits

sealed class FreeOrganizationLimitException(message: String) : RuntimeException(message)

class FreeOrganizationAdminLimitException : FreeOrganizationLimitException(
    "You can administer up to ${FreeOrganizationLimits.ADMINISTERED_ORGANIZATIONS} free organizations.",
)
class FreeOrganizationMemberLimitException : FreeOrganizationLimitException(
    "This free organization has reached its ${FreeOrganizationLimits.MEMBERS} member limit.",
)
class FreeWorkspaceLimitException : FreeOrganizationLimitException(
    "This free organization has reached its ${FreeOrganizationLimits.WORKSPACES} workspace limit.",
)
class FreePendingInvitationLimitException : FreeOrganizationLimitException(
    "This free organization has reached its ${FreeOrganizationLimits.PENDING_INVITATIONS} pending invitation limit.",
)
