package cc.monomer.control.overview

import kotlin.test.Test
import kotlin.test.assertEquals

class OverviewActivityPresentationTest {
    @Test
    fun `SQL run titles read megabyte dotted table names without regex stack growth`() {
        val sql = "select * from warehouse" + " . part".repeat(150_000) + " . orders"
        assertEquals("orders · select * from warehouse…", sqlRunTitle(sql))
    }
}
