package cc.monomer.identity.outbound.postgres

object AuthSchemaContract {
    const val POSTGRES_MAJOR = 17
    const val SCHEMA_CONTRACT_REVISION = "2026-10-13.sample-dataset-v3"
    const val SCHEMA_CATALOG_FINGERPRINT = "9c173143238a284c49a09c1ab2aa30e4"
    const val FINGERPRINT_FUNCTION_HASH = "450ba204fce9a438c401f861f132c5b6"

    val applicationTables = listOf(
        "organizations",
        "organization_membership_invitations",
        "organization_membership_invitation_workspaces",
        "principals",
        "users",
        "login_identities",
        "workspaces",
        "organization_members",
        "workspace_members",
        "sessions",
        "pending_authentication_requests",
        "operations_authorization_codes",
        "operations_sessions",
        "refresh_tokens",
        "agent_oauth_grants",
        "agent_oauth_access_tokens",
        "agent_oauth_authorization_codes",
        "agent_oauth_refresh_tokens",
        "audit_log",
    )

    val baseTables = (applicationTables + "schema_contract").toSet()

    val schemaContractColumns = listOf(
        MarkerColumn("singleton", "boolean", false, 1),
        MarkerColumn("contract_revision", "text", false, 2),
        MarkerColumn("catalog_fingerprint", "text", false, 3),
        MarkerColumn("fingerprint_function_hash", "text", false, 4),
        MarkerColumn("installed_at", "timestamp with time zone", false, 5),
    )

    data class MarkerColumn(
        val name: String,
        val postgresDataType: String,
        val nullable: Boolean,
        val ordinalPosition: Int,
    )
}
