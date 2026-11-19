package cc.monomer.control.agent

import cc.monomer.authority.api.ResourceKind
import cc.monomer.catalog.api.CatalogCurrentRelationPageCursor
import cc.monomer.catalog.api.CatalogObjectId
import cc.monomer.connector.api.ConnectorId
import cc.monomer.control.lineage.LineageResourcePageCursor
import cc.monomer.semantic.api.MetricId
import cc.monomer.semantic.api.MetricPageCursor
import cc.monomer.semantic.api.SemanticModelId
import cc.monomer.semantic.api.SemanticModelPageCursor
import java.util.Base64
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

internal object AgentManagementCursor {
    fun encode(scope: String, keys: List<String>): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(JsonArray((listOf("1", scope) + keys).map(::JsonPrimitive)).toString().toByteArray(Charsets.UTF_8))

    fun decode(scope: String, cursor: String): List<String> {
        val decoded = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8))
        require(decoded is JsonArray)
        val values = decoded.map { require(it is JsonPrimitive && it.isString); it.content }
        require(values.size >= 3 && values[0] == "1" && values[1] == scope)
        return values.drop(2)
    }

    fun encodeLineage(scope: String, cursor: LineageResourcePageCursor): String = encode(scope, when (cursor) {
        is LineageResourcePageCursor.Relation -> listOf(cursor.kind.name, cursor.ownerCursor.normalizedQualifiedName,
            cursor.ownerCursor.connectorId.toString(), cursor.ownerCursor.objectId.toString())
        is LineageResourcePageCursor.SemanticModel -> listOf(cursor.kind.name, cursor.ownerCursor.normalizedLabel,
            cursor.ownerCursor.normalizedName, cursor.ownerCursor.semanticModelId.toString())
        is LineageResourcePageCursor.Metric -> listOf(cursor.kind.name, cursor.ownerCursor.normalizedLabel,
            cursor.ownerCursor.normalizedName, cursor.ownerCursor.metricId.toString())
    })

    fun decodeLineage(scope: String, kind: ResourceKind, cursor: String): LineageResourcePageCursor {
        val keys = decode(scope, cursor)
        require(keys.size == 4 && keys[0] == kind.name)
        return when (kind) {
            ResourceKind.RELATION -> LineageResourcePageCursor.Relation("", CatalogCurrentRelationPageCursor(
                keys[1], ConnectorId(Uuid.parse(keys[2])), CatalogObjectId(Uuid.parse(keys[3]))))
            ResourceKind.SEMANTIC_MODEL -> LineageResourcePageCursor.SemanticModel("", SemanticModelPageCursor(
                keys[1], keys[2], SemanticModelId(Uuid.parse(keys[3]))))
            ResourceKind.METRIC -> LineageResourcePageCursor.Metric("", MetricPageCursor(
                keys[1], keys[2], MetricId(Uuid.parse(keys[3]))))
        }
    }
}
