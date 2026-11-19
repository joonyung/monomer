package cc.monomer.control.auth.inbound.local

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "auth.local-login")
data class LocalLoginProperties(
    val enabled: Boolean,
    val subject: String,
    val email: String,
    val displayName: String,
)
