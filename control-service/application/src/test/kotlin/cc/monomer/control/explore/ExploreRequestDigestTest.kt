package cc.monomer.control.explore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ExploreRequestDigestTest {
    @Test
    fun `request fingerprint is stable across generated execution identities`() {
        assertEquals(
            exploreRequestDigest("SQL", "SELECT 1"),
            exploreRequestDigest("SQL", "SELECT 1"),
        )
        assertNotEquals(
            exploreRequestDigest("SQL", "SELECT 1"),
            exploreRequestDigest("SQL", "SELECT 2"),
        )
    }
}
