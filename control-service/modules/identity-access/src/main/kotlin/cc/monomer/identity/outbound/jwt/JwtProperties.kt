package cc.monomer.identity.outbound.jwt

data class JwtProperties(
    val issuer: String,
    val audience: String,
    val accessTokenTtlSeconds: Long,
    val refreshTokenTtlSeconds: Long,
    /** PKCS#8 PEM body. If blank and [privateKeyPath] also blank, generate + persist on first boot. */
    val privateKeyPem: String,
    /** Optional path to a PEM file. If empty, defaults to ${user.home}/.monomer/auth-jwt.pem (auto-managed). */
    val privateKeyPath: String,
) {
    override fun toString(): String =
        "JwtProperties(issuer=$issuer, audience=$audience, accessTokenTtlSeconds=$accessTokenTtlSeconds, " +
            "refreshTokenTtlSeconds=$refreshTokenTtlSeconds, privateKeyPem=[REDACTED], privateKeyPath=$privateKeyPath)"
}
