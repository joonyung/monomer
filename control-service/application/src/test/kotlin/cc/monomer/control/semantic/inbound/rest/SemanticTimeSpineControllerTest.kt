@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.semantic.inbound.rest

import cc.monomer.connector.api.ConnectorId
import cc.monomer.identity.api.AuthorizedMutationContext
import cc.monomer.identity.api.WorkspaceId
import cc.monomer.semantic.api.PutSemanticTimeSpineConfigurationCommand
import cc.monomer.semantic.api.SemanticCurrentReadUseCase
import cc.monomer.semantic.api.SemanticTimeGranularity
import cc.monomer.semantic.api.SemanticTimeSpine
import cc.monomer.semantic.api.SemanticTimeSpineConfigurationView
import cc.monomer.semantic.api.SemanticTimeSpineDefinition
import cc.monomer.semantic.api.SemanticTimeSpineMutationResult
import cc.monomer.semantic.api.SemanticTimeSpinePrimaryColumn
import cc.monomer.semantic.api.SemanticTimeSpineRelation
import cc.monomer.semantic.api.SemanticTimeSpineUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

class SemanticTimeSpineControllerTest {
    private val timeSpines = mockk<SemanticTimeSpineUseCase>()
    private val currentRead = mockk<SemanticCurrentReadUseCase>()
    private val authorizer = mockk<SemanticWorkspaceRequestAuthorizer>()
    private val client = WebTestClient.bindToController(
        SemanticTimeSpineController(timeSpines, currentRead, authorizer),
    ).build()

    @Test
    fun `put accepts definition body and uses semantic edit admission`() {
        coEvery {
            authorizer.authorizeMutation(any(), WORKSPACE, cc.monomer.identity.api.MutationIntent.SEMANTIC_TIME_SPINE_UPDATE)
        } returns mockk<AuthorizedMutationContext>()
        coEvery { timeSpines.putTimeSpineConfiguration(any()) } returns
            SemanticTimeSpineMutationResult.Committed(
                SemanticTimeSpineConfigurationView(CONNECTOR, definition(SemanticTimeGranularity.DAY)),
            )

        client.put()
            .uri("/v2/workspaces/$WORKSPACE/semantic/time-spines/$CONNECTOR")
            .header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """
                {
                  "schemaVersion": 1,
                  "timeSpines": [
                    {
                      "relation": {"schema": "analytics", "relation": "time_spine"},
                      "primaryColumn": {"name": "date_utc", "granularity": "day"}
                    }
                  ]
                }
                """.trimIndent(),
            )
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.connectorId").isEqualTo(CONNECTOR.toString())
            .jsonPath("$.definition.schemaVersion").isEqualTo(1)
            .jsonPath("$.definition.timeSpines[0].primaryColumn.granularity").isEqualTo("day")

        val command = slot<PutSemanticTimeSpineConfigurationCommand>()
        coVerify(exactly = 1) {
            authorizer.authorizeMutation(any(), WORKSPACE, cc.monomer.identity.api.MutationIntent.SEMANTIC_TIME_SPINE_UPDATE)
        }
        coVerify(exactly = 1) { timeSpines.putTimeSpineConfiguration(capture(command)) }
        assertEquals(WORKSPACE, command.captured.workspaceId)
        assertEquals(CONNECTOR, command.captured.connectorId)
        assertEquals(1u, command.captured.definition.schemaVersion)
        assertEquals(SemanticTimeGranularity.DAY, command.captured.definition.timeSpines.single().primaryColumn.granularity)
    }

    @Test
    fun `get returns current configurations after membership admission`() {
        coEvery { authorizer.authorizeExplorer(any(), WORKSPACE) } returns Unit
        coEvery { currentRead.findTimeSpineConfigurations(WORKSPACE) } returns
            listOf(SemanticTimeSpineConfigurationView(CONNECTOR, definition(SemanticTimeGranularity.HOUR)))

        client.get()
            .uri("/v2/workspaces/$WORKSPACE/semantic/time-spines")
            .header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].connectorId").isEqualTo(CONNECTOR.toString())
            .jsonPath("$[0].definition.timeSpines[0].primaryColumn.granularity").isEqualTo("hour")

        coVerify(exactly = 1) { authorizer.authorizeExplorer(any(), WORKSPACE) }
        coVerify(exactly = 1) { currentRead.findTimeSpineConfigurations(WORKSPACE) }
    }

    private fun definition(granularity: SemanticTimeGranularity): SemanticTimeSpineDefinition =
        SemanticTimeSpineDefinition(
            schemaVersion = 1u,
            timeSpines = listOf(
                SemanticTimeSpine(
                    relation = SemanticTimeSpineRelation(null, "analytics", "time_spine"),
                    primaryColumn = SemanticTimeSpinePrimaryColumn("date_utc", granularity),
                ),
            ),
        )

    private companion object {
        val WORKSPACE = WorkspaceId(Uuid.parse("019e0000-0000-7000-8000-0000000000a1"))
        val CONNECTOR = ConnectorId(Uuid.parse("019e0000-0000-7000-8000-0000000000a2"))
    }
}
