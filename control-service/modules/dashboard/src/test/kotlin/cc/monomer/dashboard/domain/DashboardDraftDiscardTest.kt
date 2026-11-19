@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.dashboard.domain

import cc.monomer.dashboard.domain.DashboardResolvedQuery

import cc.monomer.dashboard.api.DashboardId
import cc.monomer.dashboard.application.DashboardDefinitionDecoder
import cc.monomer.identity.api.WorkspaceId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class DashboardDraftDiscardTest {
    private val at = Instant.parse("2026-10-01T00:00:00Z")

    @Test
    fun `discard keeps unchanged inputs, nulls changed nullable inputs, and refuses a required input it cannot restore`() {
        val applied = definition("Applied", """{"region":{"type":"TEXT","nullable":false},"limit":{"type":"NUMBER","nullable":true}}""")
        val edited = definition("Edited", """{"region":{"type":"TEXT","nullable":false},"limit":{"type":"TEXT","nullable":true},"extra":{"type":"TEXT","nullable":false}}""")
        val draft = DashboardDraft.start(
            WorkspaceId.generate(), DashboardId.generate(), applied,
            mapOf("region" to JsonPrimitive("KR"), "limit" to JsonPrimitive("10")), at,
        )
        val agentEdit = assertIs<DraftMutationResult.Applied>(draft.replaceDefinition(
            0, edited, mapOf("region" to JsonPrimitive("JP"), "limit" to JsonPrimitive("ten"), "extra" to JsonPrimitive("x")), at,
        )).draft

        val reset = assertIs<DraftMutationResult.Applied>(agentEdit.discard(1, applied, at.plusSeconds(1))).draft
        assertEquals(applied, reset.definition)
        assertEquals(2, reset.authoringRevision)
        assertEquals(mapOf("region" to JsonPrimitive("JP"), "limit" to JsonNull), reset.parameterBindings)
        assertIs<DraftMutationResult.Conflict>(agentEdit.discard(0, applied, at))

        val removedRequired = assertIs<DraftMutationResult.Applied>(draft.replaceDefinition(
            0, definition("Without region", """{"limit":{"type":"NUMBER","nullable":true}}"""), mapOf("limit" to JsonPrimitive("10")), at,
        )).draft
        val failure = assertFailsWith<IllegalArgumentException> { removedRequired.discard(1, applied, at) }
        assertTrue(failure.message!!.contains("region"))
        assertIs<DraftMutationResult.Conflict>(removedRequired.discard(0, applied, at))
    }

    @Test
    fun `discarding a Draft that already matches the applied definition changes nothing`() {
        val applied = definition("Applied", "{}")
        val draft = DashboardDraft.start(WorkspaceId.generate(), DashboardId.generate(), applied, emptyMap(), at)
        assertEquals(draft, assertIs<DraftMutationResult.Applied>(draft.discard(0, applied, at.plusSeconds(1))).draft)
    }

    private fun definition(title: String, parameters: String): DashboardDefinition = DashboardDefinitionDecoder.decode(
        Json.parseToJsonElement(
            """
            {"schemaVersion":1,"meta":{"title":"$title"},"parameters":$parameters,"variables":{},"queries":{},
             "widgets":{"text":{"type":"TEXT","content":"Notes"}},
             "layout":{"sections":[{"id":"main","height":{"mode":"CONTENT"},"root":{"type":"WIDGET","id":"textItem","widgetId":"text"}}]}}
            """.trimIndent(),
        ).jsonObject,
    )
}
