package cc.monomer.identity.domain.model

import cc.monomer.identity.api.OrganizationPlan
import cc.monomer.identity.domain.id.OrganizationId
import java.time.Instant

data class Organization(
    val id: OrganizationId,
    val displayName: String,
    val billingEmail: String?,
    val plan: OrganizationPlan,
    val createdAt: Instant,
)
