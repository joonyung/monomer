package cc.monomer.control.catalog

import cc.monomer.catalog.api.CatalogConflictReason

data class CatalogConflictDetails(
    val code: String,
    val detail: String,
)

fun CatalogConflictReason.toCatalogConflictDetails(): CatalogConflictDetails = when (this) {
    CatalogConflictReason.ACTIVE_INGESTION -> CatalogConflictDetails(
        code = "CATALOG_SELECTION_ACTIVE_INGESTION",
        detail = "Finish or cancel the active Catalog ingestion before changing Catalog selection.",
    )
    CatalogConflictReason.INVALID_CANDIDATE -> CatalogConflictDetails(
        code = "CATALOG_SELECTION_INVALID_CANDIDATE",
        detail = "The Catalog selection is stale or invalid. Refresh the Catalog options and try again.",
    )
}
