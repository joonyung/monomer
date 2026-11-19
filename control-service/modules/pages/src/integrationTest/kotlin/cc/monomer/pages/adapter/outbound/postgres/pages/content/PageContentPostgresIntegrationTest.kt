@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package cc.monomer.pages.adapter.outbound.postgres.pages.content

import cc.monomer.pages.adapter.outbound.postgres.SpringTransactionRunner
import cc.monomer.pages.domain.PageActor
import cc.monomer.pages.domain.PageActorKind
import cc.monomer.pages.domain.common.TransactionRunner
import cc.monomer.pages.domain.content.PageContentCleanupObjectKind
import cc.monomer.pages.domain.content.PageContentFormat
import cc.monomer.pages.domain.content.PageContentHash
import cc.monomer.pages.domain.content.PageContentObjectCleanupCandidate
import cc.monomer.pages.domain.content.PageContentUpload
import cc.monomer.pages.domain.content.PageContentUploadRef
import java.sql.Connection
import java.time.Instant
import javax.sql.DataSource
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@ExtendWith(TargetPagesPostgres::class)
class PageContentPostgresIntegrationTest {
    private val actor = PageActor.create(PageActorKind.USER, "integration-user")
    private val now = Instant.parse("2026-08-21T00:00:00Z")

    @BeforeEach
    fun clearContentAuthorities() {
        TargetPagesPostgres.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "TRUNCATE pages_relocation.page_content_object_cleanup, " +
                        "pages_relocation.page_content_uploads CASCADE",
                )
            }
        }
    }

    @Test
    fun `upload and source cleanup use only the upload and cleanup authorities`() = runBlocking<Unit> {
        val transactionRunner = TargetPagesPostgres.transactionRunner()
        val repository = PostgresPageContentRepository(
            NamedParameterJdbcTemplate(TargetPagesPostgres.dataSource),
            transactionRunner,
        )
        val workspaceId = Uuid.generateV7()
        val upload = PageContentUpload.initiate(
            uploadRef = PageContentUploadRef.generate(),
            workspaceId = workspaceId,
            actor = actor,
            format = PageContentFormat.NOTEBOOK,
            expectedHash = PageContentHash("a".repeat(64)),
            expectedSizeBytes = 4,
            temporaryObjectRef = "workspace/$workspaceId/uploads/source",
            multipartUploadId = "multipart-1",
            expiresAt = now.plusSeconds(900),
            createdAt = now,
        )

        transactionRunner.run {
            repository.insertUploadWithinTx(upload)
            repository.enqueueObjectCleanupWithinTx(
                PageContentObjectCleanupCandidate(
                    workspaceId = workspaceId,
                    objectRef = upload.temporaryObjectRef,
                    objectKind = PageContentCleanupObjectKind.TEMPORARY,
                    multipartUploadId = upload.multipartUploadId,
                    notBefore = now,
                    createdAt = now,
                ),
            )
        }

        assertThat(repository.findUpload(upload.uploadRef)).isEqualTo(upload)
        val claim = repository.claimDueObjectCleanup("pages-test", 60_000, 10).single()
        assertThat(claim.objectKind).isEqualTo(PageContentCleanupObjectKind.TEMPORARY)
        assertThat(claim.objectRef).isEqualTo(upload.temporaryObjectRef)
        assertThat(claim.multipartUploadId).isEqualTo(upload.multipartUploadId)
    }

    @Test
    fun `content writes refuse missing and other datasource transactions before writing`() = runBlocking<Unit> {
        val repository = PostgresPageContentRepository(
            NamedParameterJdbcTemplate(TargetPagesPostgres.dataSource),
            TargetPagesPostgres.transactionRunner(),
        )
        val workspaceId = Uuid.generateV7()
        val upload = PageContentUpload.initiate(
            uploadRef = PageContentUploadRef.generate(),
            workspaceId = workspaceId,
            actor = actor,
            format = PageContentFormat.MARKDOWN,
            expectedHash = PageContentHash("d".repeat(64)),
            expectedSizeBytes = 4,
            temporaryObjectRef = "workspace/$workspaceId/uploads/guard",
            multipartUploadId = "multipart-guard",
            expiresAt = now.plusSeconds(900),
            createdAt = now,
        )

        assertThatThrownBy { repository.insertUploadWithinTx(upload) }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("active owner transaction")
        val otherTransaction = TransactionTemplate(DataSourceTransactionManager(DelegatingDataSource(TargetPagesPostgres.dataSource)))
        assertThatThrownBy { otherTransaction.execute { repository.insertUploadWithinTx(upload) } }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("exact DataSource")
        assertThat(repository.findUpload(upload.uploadRef)).isNull()
    }

    @Test
    fun `schema has no directory event conversion rendition or source registry authorities`() {
        TargetPagesPostgres.dataSource.connection.use { connection ->
            val removedTables = listOf(
                "page_directory_events",
                "page_content_conversion_jobs",
                "page_version_renditions",
                "page_content_rendition_bundles",
                "page_content_source_keys",
                "page_content_source_objects",
            )
            for (table in removedTables) {
                connection.prepareStatement("SELECT to_regclass(?)").use { statement ->
                    statement.setString(1, "pages_relocation.$table")
                    statement.executeQuery().use { result ->
                        assertThat(result.next()).isTrue()
                        assertThat(result.getString(1)).isNull()
                    }
                }
            }
            connection.prepareStatement(
                """SELECT count(*)
                   FROM information_schema.columns
                   WHERE table_schema = 'pages_relocation'
                     AND table_name = 'page_directory_command_results'
                     AND column_name = 'event_revision'""",
            ).use { statement ->
                statement.executeQuery().use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getInt(1)).isZero()
                }
            }
        }
    }

    @Test
    fun `expired recovery owner terminalizes before its deterministic source becomes cleanable`() = runBlocking<Unit> {
        val transactionRunner = TargetPagesPostgres.transactionRunner()
        val repository = PostgresPageContentRepository(
            NamedParameterJdbcTemplate(TargetPagesPostgres.dataSource),
            transactionRunner,
        )
        val workspaceId = Uuid.generateV7()
        val initiated = PageContentUpload.initiate(
            uploadRef = PageContentUploadRef.generate(),
            workspaceId = workspaceId,
            actor = actor,
            format = PageContentFormat.HTML,
            expectedHash = PageContentHash("b".repeat(64)),
            expectedSizeBytes = 4,
            temporaryObjectRef = "workspace/$workspaceId/uploads/recovery/source",
            multipartUploadId = "multipart-recovery",
            expiresAt = now.plusSeconds(2),
            createdAt = now,
        )
        val completing = (initiated.startCompletion(
            requestHash = "c".repeat(64),
            requestedAt = now,
            leaseExpiresAt = now.plusSeconds(1),
            claimToken = Uuid.generateV7(),
        ) as cc.monomer.pages.domain.content.PageContentUploadCompletionStart.Started).upload
        transactionRunner.run {
            repository.insertUploadWithinTx(initiated)
            repository.updateUploadWithinTx(initiated, completing)
        }

        assertThat(repository.terminalizeExpiredUploads(10)).isEqualTo(1)

        val claims = repository.claimDueObjectCleanup("pages-expiry-test", 60_000, 10)
        assertThat(claims.map { claim -> claim.objectKind })
            .containsExactlyInAnyOrder(
                PageContentCleanupObjectKind.TEMPORARY,
                PageContentCleanupObjectKind.FINAL_SOURCE,
            )
        assertThat(claims.single { claim -> claim.objectKind == PageContentCleanupObjectKind.FINAL_SOURCE }.objectRef)
            .isEqualTo("workspace/$workspaceId/source/${initiated.uploadRef}")
    }
}

internal object TargetPagesPostgres : BeforeAllCallback, AfterAllCallback {
    private const val POSTGRES_IMAGE =
        "postgres:16.14-alpine3.24@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777"
    private val postgres = PostgreSQLContainer(
        DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"),
    )
        .withDatabaseName("pages_content_gate_ephemeral")
        .withUsername("pages_content_gate")
        .withPassword("pages_content_gate_test_only")
    private var activeDataSource: DataSource? = null

    val dataSource: DataSource
        get() = checkNotNull(activeDataSource) { "Disposable Pages PostgreSQL is not running" }

    fun transactionRunner(): TransactionRunner {
        val transactionManager = DataSourceTransactionManager(dataSource)
        return SpringTransactionRunner(
            TransactionTemplate(transactionManager),
            TransactionTemplate(transactionManager).apply { isReadOnly = true },
            blockingDispatcher = kotlinx.coroutines.Dispatchers.IO,
        )
    }

    override fun beforeAll(context: ExtensionContext) {
        postgres.start()
        activeDataSource = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
            setCurrentSchema("pages_relocation,public")
        }
        dataSource.connection.use(::applySchemaTwice)
    }

    override fun afterAll(context: ExtensionContext) {
        try {
            postgres.stop()
        } finally {
            activeDataSource = null
        }
    }

    private fun applySchemaTwice(connection: Connection) {
        val schema = checkNotNull(javaClass.classLoader.getResource("schema-postgres.sql")).readText()
        connection.createStatement().use { statement ->
            repeat(2) { statement.execute(schema) }
        }
    }
}
