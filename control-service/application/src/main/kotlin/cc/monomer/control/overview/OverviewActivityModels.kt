@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.overview

import cc.monomer.connector.api.ConnectorId
import java.time.Instant
import java.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.uuid.Uuid

enum class OverviewActivityKind { EXPLORE, CATALOG_INGEST, CATALOG_REFRESH, PAGE, DASHBOARD, DASHBOARD_SESSION, DASHBOARD_RUN, SEMANTIC_MODEL, METRIC;
    val recentOnly: Boolean get() = this in setOf(PAGE, DASHBOARD, SEMANTIC_MODEL, METRIC)
}
enum class OverviewActivitySection { LIVE, RECENT }

/** Who is responsible for a row: a member, a Dashboard schedule, an anonymous public Page reader, or nobody recorded. */
enum class OverviewActorKind { USER, SCHEDULE, PUBLIC_READER, UNRECORDED }

data class OverviewActivityItem(
    val id: String,
    val kind: OverviewActivityKind,
    val title: String,
    val description: String,
    val state: String,
    val startedAt: Instant?,
    val occurredAt: Instant,
    val actorId: String?,
    val actorName: String?,
    val actorKind: OverviewActorKind,
    val credentialSurface: String?,
    /** The agent app that did AGENT work; null for Web work and for agent work recorded before apps were kept. */
    val agentApp: cc.monomer.control.agentapp.AgentAppResponse?,
    /** A one-line cause for a failed row or a Session with a failed query; null otherwise. */
    val failureReason: String?,
    val href: String,
)

data class OverviewActivityPage(
    val items: List<OverviewActivityItem>,
    val watched: List<OverviewActivityItem>,
    val nextCursor: String?,
    val unavailableSources: List<OverviewActivityKind>,
    val observedAt: Instant,
)

internal data class ActivityCandidate(
    val item: OverviewActivityItem,
    val agentClientId: cc.monomer.identity.api.AgentClientId?,
    val connectorId: ConnectorId?,
    val exploreRun: cc.monomer.explore.api.ExploreActivity?,
)

/** Agent apps seen in a Workspace's recent activity, most recently used first. */
data class OverviewActivityAgentApps(
    val items: List<cc.monomer.control.agentapp.AgentAppResponse>,
)

internal val activityOrder = compareByDescending<ActivityCandidate> { it.item.occurredAt }
    .thenByDescending { it.item.kind.name }.thenByDescending { it.item.id }

internal data class ActivityCursor(val at: Instant, val kind: OverviewActivityKind, val id: Uuid) {
    fun ownerId(source: OverviewActivityKind): Uuid = when {
        source.name < kind.name -> Uuid.parse("ffffffff-ffff-ffff-ffff-ffffffffffff")
        source.name > kind.name -> Uuid.parse("00000000-0000-0000-0000-000000000000")
        else -> id
    }

    fun encode(scope: String, key: SecretKey): String {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(scope.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal("$at|${kind.name}|$id".toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(nonce + ciphertext)
    }

    companion object {
        fun decode(value: String, scope: String, key: SecretKey): ActivityCursor {
            require(value.length <= 512)
            val bytes = Base64.getUrlDecoder().decode(value)
            require(bytes.size > 28)
            val decoded = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                cipher.updateAAD(scope.toByteArray(Charsets.UTF_8))
                cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
            } catch (exception: java.security.GeneralSecurityException) {
                throw IllegalArgumentException("Activity cursor is no longer valid; reload activity.", exception)
            }
            val parts = decoded.split('|')
            require(parts.size == 3)
            val at = try { Instant.parse(parts[0]) } catch (exception: java.time.DateTimeException) { throw IllegalArgumentException("Invalid cursor timestamp", exception) }
            return ActivityCursor(at, OverviewActivityKind.valueOf(parts[1]), Uuid.parse(parts[2]))
        }
    }
}
