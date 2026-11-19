package cc.monomer.control.explore

import cc.monomer.explore.api.ExploreDigest
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal fun exploreRequestDigest(kind: String, intent: String): ExploreDigest {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(kind.toByteArray(StandardCharsets.UTF_8))
    digest.update(0)
    val value = digest.digest(intent.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
    return ExploreDigest("sha256:$value")
}
