package cc.monomer.dashboard.application

import cc.monomer.dashboard.domain.DashboardResolvedQuery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DashboardLayoutNestingTest {
    @Test
    fun `preserves nested groups and parent weights for every direction and separation combination`() {
        for (outerDirection in listOf("ROW", "COLUMN")) {
            for (innerDirection in listOf("ROW", "COLUMN")) {
                for (outerSeparation in listOf(null, "GAP", "DIVIDER")) {
                    for (innerSeparation in listOf(null, "GAP", "DIVIDER")) {
                        val document = definition(outerDirection, innerDirection, outerSeparation, innerSeparation)
                        val decoded = DashboardDefinitionDecoder.decode(document)
                        assertEquals(document, decoded.document)
                        assertEquals(document.getValue("layout"), decoded.layout)
                        assertEquals(document, DashboardDefinitionDecoder.decode(decoded.document.toString()).document)
                    }
                }
            }
        }
    }

    @Test
    fun `keeps nested stack structural and weight validation`() {
        val valid = definition("ROW", "ROW", "GAP", "DIVIDER")
        val invalidGroups = listOf(
            inner(valid).replace("direction", JsonPrimitive("DIAGONAL")),
            inner(valid).replace("separation", JsonPrimitive("NONE")),
            inner(valid).replace("id", JsonPrimitive("outer")),
            withChildren(inner(valid), children(inner(valid)).take(1)),
            withChildren(inner(valid), children(inner(valid)).map { it.replace("weight", JsonPrimitive(2)) }),
            withChildren(inner(valid), children(inner(valid)).mapIndexed { index, child ->
                if (index == 0) child.replace("weight", JsonPrimitive(0)) else child
            }),
            withChildren(inner(valid), children(inner(valid)).mapIndexed { index, child ->
                if (index == 0) child.replace("weight", JsonPrimitive(1.5)) else child
            }),
            withChildren(inner(valid), listOf(children(inner(valid))[0], children(inner(valid))[0])),
            withChildren(inner(valid), children(inner(valid)) + wrapper(1, spacer("emptyOne")) + wrapper(1, spacer("emptyTwo"))),
            withChildren(inner(valid), children(inner(valid)) + wrapper(1, widgetNode("duplicatePlacement", "first"))),
            withChildren(inner(valid), children(inner(valid)) + wrapper(1, widgetNode("unknownPlacement", "missing"))),
        )
        for (invalidGroup in invalidGroups) {
            assertFailsWith<IllegalArgumentException> {
                DashboardDefinitionDecoder.decode(withInner(valid, invalidGroup))
            }
        }
        val invalidOuter = withChildren(root(valid), children(root(valid)).map { it.replace("weight", JsonPrimitive(2)) })
        assertFailsWith<IllegalArgumentException> {
            DashboardDefinitionDecoder.decode(withRoot(valid, invalidOuter))
        }
    }

    @Test
    fun `preserves legal nested spacers and rejects sections without widgets`() {
        val valid = definition("COLUMN", "COLUMN", "DIVIDER", null)
        val spacedGroup = withChildren(inner(valid), children(inner(valid)) + wrapper(1, spacer("trailingSpace")))
        val document = withInner(valid, spacedGroup)
        assertEquals(document, DashboardDefinitionDecoder.decode(document).document)

        val unplaced = withInner(valid, withChildren(inner(valid), listOf(
            wrapper(1, widgetNode("firstItem", "first")), wrapper(1, spacer("missingSecond")),
        )))
        assertFailsWith<IllegalArgumentException> { DashboardDefinitionDecoder.decode(unplaced) }
        val empty = withRoot(valid, spacer("emptySection")).replace("widgets", JsonObject(emptyMap()))
        assertFailsWith<IllegalArgumentException> { DashboardDefinitionDecoder.decode(empty) }
    }

    @Test
    fun `widget nodes require a direct reference and reject the former item shape`() {
        val valid = definition("ROW", "COLUMN", null, "DIVIDER")
        val widget = widgetNode("firstItem", "first")
        val invalidNodes = listOf(
            JsonObject(widget - "widgetId"),
            widget.replace("widgetId", kotlinx.serialization.json.JsonNull),
            widget.replace("widgetId", JsonPrimitive(1)),
            widget.replace("widgetId", JsonPrimitive("")),
            widget.replace("widgetId", JsonPrimitive("bad reference")),
            widget.replace("widgetId", JsonObject(mapOf("id" to JsonPrimitive("first")))),
            widget.replace("widgetId", JsonArray(listOf(JsonPrimitive("first")))),
            widget.replace("id", JsonPrimitive("main")),
            widget.replace("item", JsonObject(mapOf("kind" to JsonPrimitive("WIDGET"), "id" to JsonPrimitive("first")))),
            Json.parseToJsonElement("""{"type":"ITEM","id":"firstItem","item":{"kind":"WIDGET","id":"first"}}""").jsonObject,
        )
        for (node in invalidNodes) {
            val group = withChildren(inner(valid), children(inner(valid)).mapIndexed { index, child ->
                if (index == 0) child.replace("node", node) else child
            })
            assertFailsWith<IllegalArgumentException>(node.toString()) {
                DashboardDefinitionDecoder.decode(withInner(valid, group))
            }
        }
    }

    private fun definition(outerDirection: String, innerDirection: String, outerSeparation: String?, innerSeparation: String?): JsonObject {
        val document = Json.parseToJsonElement("""
            {
              "schemaVersion": 1, "meta": { "title": "Nested groups" },
              "parameters": {}, "variables": {}, "queries": {"q": {"type":"SQL","connectorId":"0195f3b2-5c84-7a44-8cc4-3e9bf4fd10de","sql":"select 1 as value","bindings":{},"columns":["value"],"rowLimit":1}},
              "widgets": {
                "first": { "type": "TABLE", "queryName": "q", "columns": [{"field":"value"}] },
                "second": { "type": "TABLE", "queryName": "q", "columns": [{"field":"value"}] },
                "third": { "type": "TABLE", "queryName": "q", "columns": [{"field":"value"}] }
              },
              "layout": { "sections": [{ "id": "main", "height": { "mode": "SCALED", "scale": 2.0 }, "root": {} }] }
            }
        """.trimIndent()).jsonObject
        val nested = stack("inner", innerDirection, innerSeparation, listOf(
            wrapper(1, widgetNode("firstItem", "first")), wrapper(2, widgetNode("secondItem", "second")),
        ))
        return withRoot(document, stack("outer", outerDirection, outerSeparation, listOf(
            wrapper(3, nested), wrapper(1, widgetNode("thirdItem", "third")),
        )))
    }

    private fun stack(id: String, direction: String, separation: String?, children: List<JsonObject>): JsonObject = JsonObject(
        mapOf("type" to JsonPrimitive("STACK"), "id" to JsonPrimitive(id), "direction" to JsonPrimitive(direction), "children" to JsonArray(children)) +
            if (separation == null) emptyMap() else mapOf("separation" to JsonPrimitive(separation)),
    )

    private fun widgetNode(id: String, widgetId: String): JsonObject = JsonObject(mapOf(
        "type" to JsonPrimitive("WIDGET"), "id" to JsonPrimitive(id),
        "widgetId" to JsonPrimitive(widgetId),
    ))

    private fun spacer(id: String): JsonObject = JsonObject(mapOf("type" to JsonPrimitive("SPACER"), "id" to JsonPrimitive(id)))
    private fun wrapper(weight: Int, node: JsonObject): JsonObject = JsonObject(mapOf("weight" to JsonPrimitive(weight), "node" to node))
    private fun root(document: JsonObject): JsonObject = document.getValue("layout").jsonObject.getValue("sections").jsonArray.single().jsonObject.getValue("root").jsonObject
    private fun children(node: JsonObject): List<JsonObject> = node.getValue("children").jsonArray.map { it.jsonObject }
    private fun inner(document: JsonObject): JsonObject = children(root(document))[0].getValue("node").jsonObject
    private fun withChildren(node: JsonObject, children: List<JsonObject>): JsonObject = node.replace("children", JsonArray(children))
    private fun withInner(document: JsonObject, node: JsonObject): JsonObject = withRoot(document, withChildren(root(document), children(root(document)).mapIndexed { index, child ->
        if (index == 0) child.replace("node", node) else child
    }))
    private fun withRoot(document: JsonObject, node: JsonObject): JsonObject {
        val section = document.getValue("layout").jsonObject.getValue("sections").jsonArray.single().jsonObject
        return document.replace("layout", JsonObject(mapOf("sections" to JsonArray(listOf(section.replace("root", node))))))
    }
    private fun JsonObject.replace(key: String, value: kotlinx.serialization.json.JsonElement): JsonObject = JsonObject(this + (key to value))
}
