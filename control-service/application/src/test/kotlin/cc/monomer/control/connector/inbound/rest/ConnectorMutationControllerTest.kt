@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.control.connector.inbound.rest

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cc.monomer.connector.api.ConnectorProductType
import cc.monomer.connector.api.ConnectorSettingsProperties
import cc.monomer.connector.api.ConnectorCredential
import cc.monomer.connector.api.ConnectorId
import cc.monomer.connector.api.ConnectorMutationView
import cc.monomer.connector.api.CredentialVersionId
import cc.monomer.connector.api.ConnectorAuthorityUseCase
import cc.monomer.connector.api.ConnectorMutationResult
import cc.monomer.connector.api.CreateConnectorCommand
import cc.monomer.connector.api.ConnectorDeletionResult
import cc.monomer.control.ControlAdmissionDecision
import cc.monomer.control.ControlAdmissionDenial
import cc.monomer.control.ControlAdmissionFacade
import cc.monomer.control.ControlAdmissionStage
import cc.monomer.control.MutationAdmissionRequest
import cc.monomer.control.human.auth.ConnectorCatalogProblemHandler
import cc.monomer.identity.api.OrganizationId
import cc.monomer.identity.api.WorkspaceRole
import cc.monomer.identity.api.AuthorizationRevision
import cc.monomer.identity.api.AuthorizedMutationContext
import cc.monomer.identity.api.CanonicalPrincipal
import cc.monomer.identity.api.CredentialKind
import cc.monomer.identity.api.CredentialSurface
import cc.monomer.identity.api.IdentityAdmissionDenial
import cc.monomer.identity.api.MutationIntent
import cc.monomer.identity.api.PrincipalId
import cc.monomer.identity.api.PrincipalKind
import cc.monomer.identity.api.WorkspaceId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

class ConnectorMutationControllerTest {
    private val authorityUseCase: ConnectorAuthorityUseCase = mockk()
    private val admissionFacade: ControlAdmissionFacade = mockk()
    private val workspaceId = "019e0000-0000-7000-8000-0000000000a1"
    private val client =
        WebTestClient.bindToController(ConnectorMutationController(authorityUseCase, admissionFacade))
            .controllerAdvice(ConnectorCatalogProblemHandler(), ConnectorMutationProblemHandler())
            .webFilter<WebTestClient.ControllerSpec>(cc.monomer.control.observability.RequestCorrelationTestSupport.filter())
            .build()

    @Test
    fun `free connector limit returns a safe conflict without automatic retry`() {
        coEvery { admissionFacade.authorizeMutation(any()) } returns
            ControlAdmissionDecision.Admitted(mutationAuthorization(MutationIntent.CONNECTOR_CREATE))
        coEvery { authorityUseCase.create(any()) } returns ConnectorMutationResult.LimitExceeded

        client.post().uri("/v2/workspaces/$workspaceId/connectors")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .header("Idempotency-Key", "limit-1")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mutationJson())
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.CONFLICT)
            .expectHeader().doesNotExist(HttpHeaders.RETRY_AFTER)
            .expectBody()
            .jsonPath("$.code").isEqualTo("CONNECTOR_LIMIT_EXCEEDED")
            .jsonPath("$.retryable").isEqualTo(false)
            .jsonPath("$.detail").value<String> { detail ->
                kotlin.test.assertTrue(detail.contains("10 connectors"))
            }
    }

    @Test
    fun `create admits CONNECTOR_CREATE and returns the committed current connector`() {
        val authorization = mutationAuthorization(MutationIntent.CONNECTOR_CREATE)
        coEvery { admissionFacade.authorizeMutation(any()) } returns ControlAdmissionDecision.Admitted(authorization)
        val command = slot<CreateConnectorCommand>()
        coEvery { authorityUseCase.create(capture(command)) } returns committed()

        client.post().uri("/v2/workspaces/$workspaceId/connectors")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .header("Idempotency-Key", "create-1")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mutationJson())
            .exchange()
            .expectStatus().isCreated
            .expectHeader().doesNotExist(HttpHeaders.LOCATION)
            .expectHeader().doesNotExist(HttpHeaders.RETRY_AFTER)
            .expectBody()
            .jsonPath("$.connectorId").isEqualTo("019e0000-0000-7000-8000-0000000000c1")
            .jsonPath("$.type").isEqualTo("POSTGRESQL")
            .jsonPath("$.validationOperationId").doesNotExist()
            .jsonPath("$.executionPolicy").doesNotExist()
            .jsonPath("$.credentials").doesNotExist()
            .jsonPath("$.credentialVersionId").doesNotExist()
            .jsonPath("$.properties.port").isEqualTo(5432)

        val captured = command.captured
        assertEquals(ConnectorProductType.POSTGRESQL, captured.connectorType)
        assertEquals("External DW Postgres", captured.displayName)
        assertEquals("warehouse", (captured.properties as ConnectorSettingsProperties.Postgresql).database)
        assertEquals("create-1", captured.mutationIdempotencyKey)
        assertEquals("dw_query", (captured.credential as ConnectorCredential.UsernamePassword).username)
        coVerify(exactly = 1) {
            admissionFacade.authorizeMutation(
                match<MutationAdmissionRequest> { request ->
                    request.intent == MutationIntent.CONNECTOR_CREATE &&
                        request.common.workspaceId.toString() == workspaceId
                }
            )
        }
    }

    @Test
    fun `permission denial rejects connector create revise and rotate before owner`() {
        val denied = ControlAdmissionDecision.Denied(
            ControlAdmissionStage.IDENTITY,
            ControlAdmissionDenial.Identity(IdentityAdmissionDenial.PERMISSION_UNAVAILABLE),
        )
        coEvery { admissionFacade.authorizeMutation(any()) } returns denied

        val actorToken = "permission-denied"
        client.post().uri("/v2/workspaces/$workspaceId/connectors")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $actorToken")
            .header("Idempotency-Key", "denied-create")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mutationJson())
            .exchange()
            .expectStatus().isForbidden

        client.put().uri("/v2/workspaces/$workspaceId/connectors/019e0000-0000-7000-8000-0000000000c1")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $actorToken")
            .header("Idempotency-Key", "denied-revise")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(reviseJson())
            .exchange()
            .expectStatus().isForbidden

        client.put().uri("/v2/workspaces/$workspaceId/connectors/019e0000-0000-7000-8000-0000000000c1/credentials")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $actorToken")
            .header("Idempotency-Key", "denied-rotate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"kind":"USERNAME_PASSWORD","username":"reader","password":"sentinel-secret"}""")
            .exchange()
            .expectStatus().isForbidden

        coVerify(exactly = 3) {
            admissionFacade.authorizeMutation(
                match { request -> request.common.credential.value == actorToken },
            )
        }

        coVerify(exactly = 1) {
            admissionFacade.authorizeMutation(match { it.intent == MutationIntent.CONNECTOR_CREATE })
        }
        coVerify(exactly = 2) {
            admissionFacade.authorizeMutation(match { it.intent == MutationIntent.CONNECTOR_UPDATE })
        }
        coVerify(exactly = 0) { authorityUseCase.create(any()) }
        coVerify(exactly = 0) { authorityUseCase.revise(any()) }
        coVerify(exactly = 0) { authorityUseCase.rotateCredential(any()) }
    }

    @Test
    fun `revise returns current settings with HTTP 200`() {
        coEvery { admissionFacade.authorizeMutation(any()) } returns
            ControlAdmissionDecision.Admitted(mutationAuthorization(MutationIntent.CONNECTOR_UPDATE))
        coEvery { authorityUseCase.revise(any()) } returns committed(
            displayName = "Revised Warehouse",
            properties = ConnectorSettingsProperties.Postgresql("warehouse-revised.internal", 5432, "warehouse", null, null),
        )

        client.put().uri("/v2/workspaces/$workspaceId/connectors/019e0000-0000-7000-8000-0000000000c1")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .header("Idempotency-Key", "revise-1")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(reviseJson())
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.displayName").isEqualTo("Revised Warehouse")
            .jsonPath("$.type").isEqualTo("POSTGRESQL")
            .jsonPath("$.properties.host").isEqualTo("warehouse-revised.internal")
            .jsonPath("$.properties.database").isEqualTo("warehouse")

        coVerify(exactly = 1) { authorityUseCase.revise(any()) }
    }

    @Test
    fun `revise rejects fixed database input with HTTP 422`() {
        coEvery { admissionFacade.authorizeMutation(any()) } returns
            ControlAdmissionDecision.Admitted(mutationAuthorization(MutationIntent.CONNECTOR_UPDATE))
        coEvery { authorityUseCase.revise(any()) } returns ConnectorMutationResult.InvalidInput("database is fixed")

        client.put().uri("/v2/workspaces/$workspaceId/connectors/019e0000-0000-7000-8000-0000000000c1")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .header("Idempotency-Key", "revise-database")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(reviseJson(database = "other_database"))
            .exchange()
            .expectStatus().isEqualTo(422)
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
            .jsonPath("$.detail").isEqualTo("database is fixed")
            .jsonPath("$.retryable").isEqualTo(false)

        coVerify(exactly = 1) { authorityUseCase.revise(any()) }
    }

    @Test
    fun `unexpected owner illegal argument remains a logged 500`() {
        coEvery { admissionFacade.authorizeMutation(any()) } returns
            ControlAdmissionDecision.Admitted(mutationAuthorization(MutationIntent.CONNECTOR_UPDATE))
        coEvery { authorityUseCase.revise(any()) } throws IllegalArgumentException("sentinel-secret")
        val response = client.put().uri("/v2/workspaces/$workspaceId/connectors/019e0000-0000-7000-8000-0000000000c1")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .header("Idempotency-Key", "revise-defect")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(reviseJson()).exchange()
            .expectStatus().isEqualTo(500).expectBody()
            .jsonPath("$.retryable").isEqualTo(false).returnResult()
        assertFalse(response.responseBody?.decodeToString().orEmpty().contains("sentinel-secret"))
    }

    @Test
    fun `owner CAS conflict maps to HTTP 409`() {
        coEvery { admissionFacade.authorizeMutation(any()) } returns
            ControlAdmissionDecision.Admitted(mutationAuthorization(MutationIntent.CONNECTOR_UPDATE))
        coEvery { authorityUseCase.revise(any()) } returns ConnectorMutationResult.Conflict

        client.put().uri("/v2/workspaces/$workspaceId/connectors/019e0000-0000-7000-8000-0000000000c1")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .header("Idempotency-Key", "revise-cas-conflict")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(reviseJson())
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.CONFLICT)
            .expectBody()
            .jsonPath("$.code").isEqualTo("CONFLICT")
            .jsonPath("$.retryable").isEqualTo(false)

        coVerify(exactly = 1) { authorityUseCase.revise(any()) }
    }

    @Test
    fun `create does not echo credential values in logs or responses`() {
        assertFalse(ConnectorCredential.UsernamePassword("dw_query", "secret-password").toString().contains("secret-password"))
    }

    @Test
    fun `owner delete returns no content and repeated delete is not found`() {
        coEvery { admissionFacade.authorizeMutation(any()) } returns
            ControlAdmissionDecision.Admitted(mutationAuthorization(MutationIntent.CONNECTOR_DELETE))
        coEvery { authorityUseCase.delete(any()) } returnsMany listOf(
            ConnectorDeletionResult.Deleted,
            ConnectorDeletionResult.NotFoundOrOutsideWorkspace,
        )
        val path = "/v2/workspaces/$workspaceId/connectors/019e0000-0000-7000-8000-0000000000c1"

        client.delete().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer token").exchange()
            .expectStatus().isNoContent.expectBody().isEmpty
        client.delete().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer token").exchange()
            .expectStatus().isNotFound.expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND")

        coVerify(exactly = 2) {
            admissionFacade.authorizeMutation(match { it.intent == MutationIntent.CONNECTOR_DELETE })
            authorityUseCase.delete(match { it.connectorId.toString() == "019e0000-0000-7000-8000-0000000000c1" })
        }
    }

    @Test
    fun `delete during an active refresh is a typed conflict`() {
        coEvery { admissionFacade.authorizeMutation(any()) } returns
            ControlAdmissionDecision.Admitted(mutationAuthorization(MutationIntent.CONNECTOR_DELETE))
        coEvery { authorityUseCase.delete(any()) } returns ConnectorDeletionResult.RefreshActive

        client.delete().uri("/v2/workspaces/$workspaceId/connectors/019e0000-0000-7000-8000-0000000000c1")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token").exchange()
            .expectStatus().isEqualTo(HttpStatus.CONFLICT)
            .expectBody()
            .jsonPath("$.code").isEqualTo("CONNECTOR_REFRESH_ACTIVE")
            .jsonPath("$.retryable").isEqualTo(false)
    }

    @Test
    fun `delete denied by role is forbidden without owner writes`() {
        coEvery { admissionFacade.authorizeMutation(any()) } returns ControlAdmissionDecision.Denied(
            ControlAdmissionStage.IDENTITY,
            ControlAdmissionDenial.Identity(IdentityAdmissionDenial.PERMISSION_UNAVAILABLE),
        )

        client.delete().uri("/v2/workspaces/$workspaceId/connectors/019e0000-0000-7000-8000-0000000000c1")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token").exchange()
            .expectStatus().isForbidden

        coVerify(exactly = 0) { authorityUseCase.delete(any()) }
    }

    @Test
    fun `create without a bearer is unauthorized`() {
        client.post().uri("/v2/workspaces/$workspaceId/connectors")
            .header("Idempotency-Key", "create-1")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mutationJson())
            .exchange()
            .expectStatus().isUnauthorized

        coVerify(exactly = 0) { authorityUseCase.create(any()) }
    }

    @Test
    fun `closed create rejects unknown keys wrong scalar and mismatched credential without owner writes`() {
        coEvery { admissionFacade.authorizeMutation(any()) } returns ControlAdmissionDecision.Admitted(mutationAuthorization(MutationIntent.CONNECTOR_CREATE))
        val invalidInputs = listOf(
            mutationJson().replace("\"port\": 5432", "\"port\": \"5432\""),
            mutationJson().replace("\"host\":", "\"unexpected\": \"sentinel-secret\", \"host\":"),
            mutationJson().replace("USERNAME_PASSWORD", "ACCESS_TOKEN"),
            mutationJson().replace("\"password\":", "\"unknown\": \"sentinel-secret\", \"password\":"),
            mutationJson() + " {}",
            mutationJson().replace("\"displayName\":", "\"displayName\": \"duplicate\", \"displayName\":"),
        )
        for (input in invalidInputs) {
            val response = client.post().uri("/v2/workspaces/$workspaceId/connectors")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .header("Idempotency-Key", "invalid-1")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(input).exchange()
                .expectStatus().isBadRequest.expectBody().returnResult()
            assertFalse(response.responseBody?.decodeToString().orEmpty().contains("sentinel-secret"))
        }
        coVerify(exactly = 0) { authorityUseCase.create(any()) }
    }

    @Test
    fun `out of range port is a value error before owner mutation`() {
        coEvery { admissionFacade.authorizeMutation(any()) } returns ControlAdmissionDecision.Admitted(mutationAuthorization(MutationIntent.CONNECTOR_CREATE))
        client.post().uri("/v2/workspaces/$workspaceId/connectors")
            .header(HttpHeaders.AUTHORIZATION, "Bearer token").header("Idempotency-Key", "invalid-port")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(mutationJson().replace("\"port\": 5432", "\"port\": 0"))
            .exchange().expectStatus().isEqualTo(422).expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
            .jsonPath("$.retryable").isEqualTo(false)
        coVerify(exactly = 0) { authorityUseCase.create(any()) }
    }

    @Test
    fun `settings reject credential material while rotation consumes the direct full credential object`() {
        coEvery { admissionFacade.authorizeMutation(any()) } returns ControlAdmissionDecision.Admitted(mutationAuthorization(MutationIntent.CONNECTOR_UPDATE))
        coEvery { authorityUseCase.rotateCredential(any()) } returns committed()
        val path = "/v2/workspaces/$workspaceId/connectors/019e0000-0000-7000-8000-0000000000c1"
        client.put().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .header("Idempotency-Key", "settings-1").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"displayName":"Analytics","properties":{},"credential":{"kind":"ACCESS_TOKEN","accessToken":"sentinel-secret"}}""")
            .exchange().expectStatus().isBadRequest
        client.put().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .header("Idempotency-Key", "settings-type-1").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"connectorType":"BIGQUERY","displayName":"Analytics","properties":{}}""")
            .exchange().expectStatus().isBadRequest
        client.put().uri("$path/credentials").header(HttpHeaders.AUTHORIZATION, "Bearer token")
            .header("Idempotency-Key", "rotate-1").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"kind":"USERNAME_PASSWORD","username":"reader","password":"sentinel-secret"}""")
            .exchange().expectStatus().isNoContent.expectBody().isEmpty
        coVerify(exactly = 0) { authorityUseCase.revise(any()) }
        coVerify(exactly = 1) { authorityUseCase.rotateCredential(match { it.mutationIdempotencyKey == "rotate-1" }) }
    }

    @Test
    fun rotationRejectsPartialAndNullCredentialsWithoutOwnerWrites() {
        coEvery { admissionFacade.authorizeMutation(any()) } returns ControlAdmissionDecision.Admitted(mutationAuthorization(MutationIntent.CONNECTOR_UPDATE))
        coEvery { authorityUseCase.rotateCredential(any()) } returns committed()
        val path = "/v2/workspaces/$workspaceId/connectors/019e0000-0000-0000-0000-0000000000c1/credentials"
        val invalidInputs = listOf(
            """{"kind":"USERNAME_PASSWORD","username":"reader"}""",
            """{"kind":"USERNAME_PASSWORD","username":"reader","password":null}""",
        )
        invalidInputs.forEachIndexed { index, input ->
            val response = client.put().uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .header("Idempotency-Key", "rotate-invalid-$index")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .exchange()
                .expectStatus().isBadRequest
                .expectBody()
                .returnResult()
            assertFalse(response.responseBody?.decodeToString().orEmpty().contains("reader"))
        }
        coVerify(exactly = 0) { authorityUseCase.rotateCredential(any()) }
    }

    @Test
    fun `unknown mutation outcome is secret safe and does not promise retry`() {
        coEvery { admissionFacade.authorizeMutation(any()) } returns ControlAdmissionDecision.Admitted(mutationAuthorization(MutationIntent.CONNECTOR_CREATE))
        coEvery { authorityUseCase.create(any()) } throws IllegalStateException("sentinel-secret")
        val logger = LoggerFactory.getLogger(cc.monomer.control.observability.RequestCorrelationWebFilter::class.java) as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        try {
            client.post().uri("/v2/workspaces/$workspaceId/connectors")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token").header("Idempotency-Key", "unknown-1")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(mutationJson()).exchange()
                .expectStatus().is5xxServerError.expectBody()
                .jsonPath("$.retryable").isEqualTo(false)
                .jsonPath("$.detail").isEqualTo("The mutation outcome could not be confirmed. Do not automatically repeat the request.")
            assertEquals(1, logs.list.size)
            assertEquals("http_request_failed", logs.list.single().formattedMessage)
            val encoded = cc.monomer.control.observability.RequestLogTestSupport.encoded(logs.list.single())
            assertFalse(encoded.contains("sentinel-secret"))
            assertTrue(encoded.contains("stack_trace"))
            assertTrue(encoded.contains("ConnectorMutationControllerTest.kt:"))
        } finally {
            logger.detachAppender(logs)
        }
    }

    private fun reviseJson(database: String = "warehouse"): String =
        """
        {
          "displayName": "Revised Warehouse",
          "properties": {
            "host": "warehouse-revised.internal",
            "port": 5432,
            "database": "$database"
          }
        }
        """.trimIndent()

    private fun mutationJson(): String =
        """
        {
          "connectorType": "POSTGRESQL",
          "displayName": "External DW Postgres",
          "properties": {
            "host": "dw-postgres",
            "port": 5432,
            "database": "warehouse"
          },
          "credential": {
            "kind": "USERNAME_PASSWORD",
            "username": "dw_query",
            "password": "secret-password"
          }
        }
        """.trimIndent()

    private fun mutationAuthorization(intent: MutationIntent): AuthorizedMutationContext {
        val parsedWorkspace = WorkspaceId.parse(workspaceId)
        val actor = CanonicalPrincipal(PrincipalId(Uuid.parse("019e0000-0000-7000-8000-000000000099")), PrincipalKind.USER)
        return AuthorizedMutationContext.restore(
            actor = actor,
            organizationId = OrganizationId(Uuid.parse("019e0000-0000-7000-8000-0000000000aa")),
            organizationPlan = cc.monomer.identity.api.OrganizationPlan.FREE,
            workspaceId = parsedWorkspace,
            authorizationRevision = AuthorizationRevision(1),
            credentialKind = CredentialKind.SESSION,
            credentialSurface = CredentialSurface.WEB,
            agentClientId = null,
            role = WorkspaceRole.OWNER,
            intent = intent,
            admittedAt = Instant.parse("2026-08-15T12:00:00Z")
        )
    }

    private fun committed(
        displayName: String = "External DW Postgres",
        properties: ConnectorSettingsProperties = ConnectorSettingsProperties.Postgresql("dw-postgres", 5432, "warehouse", null, null),
    ): ConnectorMutationResult.Committed {
        val connectorId = ConnectorId(Uuid.parse("019e0000-0000-7000-8000-0000000000c1"))
        val credentialVersionId = CredentialVersionId(Uuid.parse("019e0000-0000-7000-8000-0000000000c2"))
        return ConnectorMutationResult.Committed(
            connector = ConnectorMutationView(
                connectorId = connectorId,
                workspaceId = WorkspaceId.parse(workspaceId),
                connectorType = "postgresql",
                displayName = displayName,
                properties = properties,
                credentialVersionId = credentialVersionId,
                createdAt = Instant.parse("2026-08-15T12:00:00Z"),
                updatedAt = Instant.parse("2026-08-15T12:00:00Z"),
            ),
        )
    }
}
