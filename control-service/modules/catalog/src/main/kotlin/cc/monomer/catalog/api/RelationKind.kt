package cc.monomer.catalog.api

import kotlinx.serialization.Serializable

@Serializable
enum class RelationKind {
    TABLE,
    VIEW,
    MATERIALIZED_VIEW,
    EXTERNAL,
    FOREIGN,
    DYNAMIC,
    SNAPSHOT,
    UNKNOWN,
}
