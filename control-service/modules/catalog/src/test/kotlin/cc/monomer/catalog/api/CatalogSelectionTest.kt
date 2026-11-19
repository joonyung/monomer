package cc.monomer.catalog.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CatalogSelectionTest {
    @Test
    fun `empty selection includes nothing`() {
        val selection = CatalogSelection(emptySet())

        assertFalse(selection.includesSchema("public"))
        assertFalse(selection.includesRelation("public", "events"))
    }

    @Test
    fun `all relations includes names observed later`() {
        val selection = CatalogSelection(
            setOf(CatalogSelectedSchema("public", CatalogRelationSelection.AllRelations)),
        )

        assertTrue(selection.includesSchema("public"))
        assertTrue(selection.includesRelation("public", "events"))
        assertTrue(selection.includesRelation("public", "new_events"))
        assertFalse(selection.includesRelation("analytics", "events"))
    }

    @Test
    fun `selected relations is exact and an empty set selects no relation`() {
        val selection = CatalogSelection(
            setOf(
                CatalogSelectedSchema(
                    "analytics",
                    CatalogRelationSelection.SelectedRelations(setOf("orders", "customers")),
                ),
            ),
        )

        assertTrue(selection.includesRelation("analytics", "orders"))
        assertFalse(selection.includesRelation("analytics", "events"))
        assertFalse(CatalogRelationSelection.SelectedRelations(emptySet()).includes("orders"))
    }

    @Test
    fun `duplicate schema addresses are rejected even when relation modes differ`() {
        assertFailsWith<IllegalArgumentException> {
            CatalogSelection(
                setOf(
                    CatalogSelectedSchema("public", CatalogRelationSelection.AllRelations),
                    CatalogSelectedSchema("public", CatalogRelationSelection.SelectedRelations(setOf("events"))),
                ),
            )
        }
    }
}
