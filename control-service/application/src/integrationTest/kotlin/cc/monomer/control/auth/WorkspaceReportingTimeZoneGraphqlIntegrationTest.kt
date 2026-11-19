package cc.monomer.control.auth

import cc.monomer.control.ControlApplication
import cc.monomer.control.EphemeralControlConfiguration
import cc.monomer.control.EphemeralControlResources
import cc.monomer.identity.api.WorkspaceReportingTimeZoneQuery
import cc.monomer.identity.domain.id.LoginIdentityId
import cc.monomer.identity.domain.id.SessionId
import cc.monomer.identity.domain.id.UserId
import cc.monomer.identity.domain.model.AuthenticationProtocol
import cc.monomer.identity.domain.model.EmailEvidence
import cc.monomer.identity.domain.model.Session
import cc.monomer.identity.domain.port.SessionRepository
import cc.monomer.identity.outbound.jwt.JwtIssuer
import java.time.Instant
import java.time.ZoneId
import javax.sql.DataSource
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient

@ActiveProfiles("local")
@Import(EphemeralControlConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [ControlApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "management.health.db.enabled=false",
        "spring.graphql.schema.locations=classpath*:graphql/**/",
        "monomer.credentials.aes-gcm.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "monomer.credentials.aes-gcm.key-ref=integration-test",
        "monomer.control.execution-runtime.credential-vault.keys-base64.local-primary=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    ],
)
class WorkspaceReportingTimeZoneGraphqlIntegrationTest @Autowired constructor(
    private val environment: Environment,
    private val jwtIssuer: JwtIssuer,
    private val resources: EphemeralControlResources,
    private val dataSource: DataSource,
    private val reportingTimeZoneQuery: WorkspaceReportingTimeZoneQuery,
) {
    @MockitoBean
    private lateinit var sessionRepository: SessionRepository

    private val organization = Uuid.generateV7()
    private val workspace = Uuid.generateV7()
    private val owner = Uuid.generateV7()
    private val admin = Uuid.generateV7()
    private val viewer = Uuid.generateV7()

    @Test
    fun `members read the zone, owner and organization admin set it, and unlisted zones are refused`() = runBlocking<Unit> {
        resources.verifyIsolation(dataSource, environment)
        seed()
        val publicWorkspace = cc.monomer.identity.api.WorkspaceId(workspace)

        graphql(viewer, "{ accessibleWorkspaces { id reportingTimeZone role } }")
            .jsonPath("$.data.accessibleWorkspaces[0].id").isEqualTo(workspace.toString())
            .jsonPath("$.data.accessibleWorkspaces[0].reportingTimeZone").isEqualTo("UTC")
            .jsonPath("$.data.accessibleWorkspaces[0].role").isEqualTo("VIEWER")
        assertThat(reportingTimeZoneQuery.reportingTimeZone(publicWorkspace)).isEqualTo(ZoneId.of("UTC"))

        graphql(viewer, setZone("Asia/Seoul"))
            .jsonPath("$.errors[0].extensions.classification").isEqualTo("FORBIDDEN")
            .jsonPath("$.data").doesNotExist()
        graphql(owner, setZone("US/Eastern"))
            .jsonPath("$.errors[0].extensions.classification").isEqualTo("BAD_REQUEST")
            .jsonPath("$.errors[0].extensions.code").isEqualTo("UNSUPPORTED_REPORTING_TIME_ZONE")
            .jsonPath("$.errors[0].message").isEqualTo("Choose a time zone from the supported list.")
        assertThat(reportingTimeZoneQuery.reportingTimeZone(publicWorkspace)).isEqualTo(ZoneId.of("UTC"))

        graphql(owner, setZone("Asia/Seoul"))
            .jsonPath("$.errors").doesNotExist()
            .jsonPath("$.data.setWorkspaceReportingTimeZone.id").isEqualTo(workspace.toString())
            .jsonPath("$.data.setWorkspaceReportingTimeZone.reportingTimeZone").isEqualTo("Asia/Seoul")
        assertThat(reportingTimeZoneQuery.reportingTimeZone(publicWorkspace)).isEqualTo(ZoneId.of("Asia/Seoul"))

        graphql(admin, setZone("America/New_York"))
            .jsonPath("$.errors").doesNotExist()
            .jsonPath("$.data.setWorkspaceReportingTimeZone.reportingTimeZone").isEqualTo("America/New_York")
        graphql(admin, """{ managedWorkspaces(organizationId: "$organization") { id reportingTimeZone } }""")
            .jsonPath("$.data.managedWorkspaces[0].reportingTimeZone").isEqualTo("America/New_York")
        graphql(viewer, "{ accessibleWorkspaces { reportingTimeZone } }")
            .jsonPath("$.data.accessibleWorkspaces[0].reportingTimeZone").isEqualTo("America/New_York")
        assertThat(reportingTimeZoneQuery.reportingTimeZone(publicWorkspace)).isEqualTo(ZoneId.of("America/New_York"))
        assertThat(JdbcTemplate(dataSource).queryForList(
            "SELECT details->>'reportingTimeZone' FROM identity_runtime.audit_log " +
                "WHERE workspace_id = '$workspace' AND action = 'workspace.reporting_time_zone.set'",
            String::class.java,
        )).containsExactlyInAnyOrder("Asia/Seoul", "America/New_York")
    }

    private fun setZone(timeZone: String) =
        """mutation { setWorkspaceReportingTimeZone(workspaceId: "$workspace", timeZone: "$timeZone") { id reportingTimeZone } }"""

    private fun graphql(user: Uuid, query: String): WebTestClient.BodyContentSpec =
        WebTestClient.bindToServer()
            .baseUrl("http://127.0.0.1:${environment.getRequiredProperty("local.server.port")}")
            .build()
            .post().uri("/v2/graphql")
            .header("Authorization", "Bearer ${sessionToken(user).serialized}")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("query" to query))
            .exchange()
            .expectStatus().isOk
            .expectBody()

    private fun sessionToken(user: Uuid): cc.monomer.identity.domain.model.IssuedToken {
        val userId = UserId(user)
        val sessionId = SessionId.generate()
        val now = Instant.now()
        val session = Session(
            sessionId, userId, LoginIdentityId.generate(), AuthenticationProtocol.OIDC,
            "builtin/google", "https://accounts.google.com", "$user@example.test", true,
            EmailEvidence.AUTHORITATIVE, null, null, emptyList(), null,
            now, now, now.plusSeconds(3600), null, null,
        )
        doReturn(session).`when`(sessionRepository).findById(sessionId)
        return jwtIssuer.issueBrowserSession(userId, sessionId)
    }

    private fun seed() {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                statement.execute("INSERT INTO identity_runtime.organizations(id,display_name) VALUES('$organization','Reporting')")
                for ((user, role) in listOf(owner to "MEMBER", admin to "ADMIN", viewer to "MEMBER")) {
                    statement.execute("INSERT INTO identity_runtime.principals(id,kind,display_name) VALUES('$user','user','Fixture user')")
                    statement.execute("INSERT INTO identity_runtime.users(id,primary_email) VALUES('$user','$user@example.test')")
                    statement.execute(
                        "INSERT INTO identity_runtime.organization_members(organization_id,user_id,role) VALUES('$organization','$user','$role')",
                    )
                }
                statement.execute(
                    "INSERT INTO identity_runtime.workspaces(id,organization_id,display_name) VALUES('$workspace','$organization','Reporting')",
                )
                statement.execute(
                    "INSERT INTO identity_runtime.workspace_members(workspace_id,user_id,role) " +
                        "VALUES('$workspace','$owner','OWNER'),('$workspace','$viewer','VIEWER')",
                )
            }
            connection.commit()
        }
    }
}
