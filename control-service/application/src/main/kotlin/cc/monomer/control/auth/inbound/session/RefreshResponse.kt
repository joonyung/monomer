package cc.monomer.control.auth.inbound.session

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Success body for `POST /auth/refresh`. Keys are snake_case — the FE reads
 * `data.access_token` and `data.expires_at`. `expiresAt` is the access token's ISO-8601
 * expiry (`Instant.toString()`).
 */
data class RefreshResponse(
    @get:JsonProperty("access_token") val accessToken: String,
    @get:JsonProperty("expires_at") val expiresAt: String,
)
