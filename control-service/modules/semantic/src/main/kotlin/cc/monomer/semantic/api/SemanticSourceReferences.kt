package cc.monomer.semantic.api

import cc.monomer.catalog.api.CatalogPhysicalAddress

fun SemanticSourceRelation.matchesCurrentAddress(address: CatalogPhysicalAddress): Boolean =
    (catalog == null || address.catalogName == catalog) &&
        address.schemaName == schema &&
        address.objectName == relation &&
        address.memberName == null
