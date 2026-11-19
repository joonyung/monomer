package cc.monomer.catalog.domain

data class CatalogQueryParameter(val name: String, val value: String?) {
    init { require(name.isNotBlank()) }
}
