package cc.monomer.pages.adapter.outbound.postgres

data class PostgresProperties(
    val jdbcUrl: String,
    val username: String,
    val password: String
) {
    override fun toString(): String =
        "PostgresProperties(jdbcUrl=$jdbcUrl, username=$username, password=[REDACTED])"
}
