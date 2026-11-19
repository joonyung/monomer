package cc.monomer.pages.adapter.outbound.postgres.pages

import cc.monomer.pages.domain.common.JsonPatchError
import cc.monomer.pages.domain.common.TransactionRunner
import cc.monomer.pages.domain.Page
import cc.monomer.pages.domain.PageActor
import cc.monomer.pages.domain.PageActorKind
import cc.monomer.pages.domain.PageDirectoryChange
import cc.monomer.pages.domain.PageDirectoryMutationRecord
import cc.monomer.pages.domain.PageDirectoryRequestId
import cc.monomer.pages.domain.PageDirectoryRequestRejection
import cc.monomer.pages.domain.PageDirectoryRequestRecord
import cc.monomer.pages.domain.PageDirectoryRequestResult
import cc.monomer.pages.domain.PageId
import cc.monomer.pages.domain.PageKind
import cc.monomer.pages.domain.PageMetadataRejection
import cc.monomer.pages.domain.PageNavigationMetadata
import cc.monomer.pages.domain.PagePlacement
import cc.monomer.pages.domain.PagesDirectory
import cc.monomer.pages.domain.PagesDirectoryQueryPort
import cc.monomer.pages.domain.PagesDirectoryRejection
import cc.monomer.pages.domain.PagesDirectoryRepository
import cc.monomer.pages.domain.PagesDirectoryRevision
import cc.monomer.pages.domain.PagesDirectorySnapshot
import cc.monomer.pages.domain.PagesDirectoryValidationResult
import cc.monomer.pages.domain.content.PageContentConsumptionRejection
import java.sql.ResultSet
import java.time.Instant
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager

class PostgresPagesDirectoryRepository(
    @param:Qualifier("pagesJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
    private val transactionRunner: TransactionRunner,
) : PagesDirectoryRepository, PagesDirectoryQueryPort {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    override fun requireAmbientTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Pages directory mutation requires an active owner transaction"
        }
        check(TransactionSynchronizationManager.hasResource(checkNotNull(jdbc.jdbcTemplate.dataSource))) {
            "Pages directory mutation requires the transaction bound to the exact DataSource"
        }
    }

    override suspend fun findSnapshot(workspaceId: Uuid): PagesDirectorySnapshot? = transactionRunner.readOnly {
        findDirectoryWithPages(workspaceId)?.let(PagesDirectorySnapshot::from)
    }

    override fun findOrCreateForUpdateWithinTx(workspaceId: Uuid): PagesDirectory {
        val initial = PagesDirectory.empty(workspaceId)
        jdbc.update(
            """INSERT INTO pages_relocation.page_directories
               (workspace_id, placements, revision, updated_at, updated_by_kind, updated_by_id)
               VALUES (:workspaceId, '[]'::jsonb, 0, :updatedAt, :actorKind, :actorId)
               ON CONFLICT (workspace_id) DO NOTHING""",
            mapOf(
                "workspaceId" to workspaceId.toJavaUuid(),
                "updatedAt" to initial.updatedAt.toEpochMilli(),
                "actorKind" to initial.updatedBy.kind.name,
                "actorId" to initial.updatedBy.id,
            ),
        )
        // Lock before reading: a READ COMMITTED read that waited on the row lock re-checks only the
        // locked directory row, so the joined pages must come from a statement that starts after the lock.
        val locked = jdbc.queryForList(
            """SELECT workspace_id FROM pages_relocation.page_directories
               WHERE workspace_id = :workspaceId
               FOR UPDATE""",
            mapOf("workspaceId" to workspaceId.toJavaUuid()),
        )
        check(locked.size == 1) { "PagesDirectory insert failed" }
        return findDirectoryWithPages(workspaceId)
            ?: error("PagesDirectory insert failed")
    }

    override fun findRequestResultWithinTx(
        workspaceId: Uuid,
        actor: PageActor,
        requestId: PageDirectoryRequestId,
    ): PageDirectoryRequestResult? {
        val row = jdbc.query(
            """SELECT request_hash, outcome_type, result_payload
               FROM pages_relocation.page_directory_command_results
               WHERE workspace_id = :workspaceId
                 AND actor_kind = :actorKind
                 AND actor_id = :actorId
                 AND request_id = :requestId
               FOR UPDATE""",
            mapOf(
                "workspaceId" to workspaceId.toJavaUuid(),
                "actorKind" to actor.kind.name,
                "actorId" to actor.id,
                "requestId" to requestId.value,
            ),
        ) { rs, _ ->
            RequestResultRow(
                requestHash = rs.getString("request_hash"),
                outcomeType = rs.getString("outcome_type"),
                resultPayload = json.parseToJsonElement(rs.getString("result_payload")),
            )
        }.firstOrNull() ?: return null
        return when (row.outcomeType) {
            "APPLIED" -> json.decodeFromJsonElement(AppliedResultDto.serializer(), row.resultPayload).toDomain(row.requestHash)
            "REVISION_CONFLICT" -> PageDirectoryRequestResult.RevisionConflict(
                requestHash = row.requestHash,
                snapshot = json.decodeFromJsonElement(
                    SnapshotResultDto.serializer(),
                    row.resultPayload,
                ).snapshot.toDomain(),
            )
            "REJECTED" -> {
                val result = json.decodeFromJsonElement(RejectedResultDto.serializer(), row.resultPayload)
                PageDirectoryRequestResult.Rejected(
                    requestHash = row.requestHash,
                    reason = result.reason.toDomain(),
                    snapshot = result.snapshot.toDomain(),
                )
            }
            else -> error("unknown PageDirectory command result type")
        }
    }

    override fun saveMutationWithinTx(
        current: PagesDirectory,
        next: PagesDirectory,
        record: PageDirectoryMutationRecord,
    ): PageDirectoryRequestResult.Applied {
        saveDirectoryWithinTx(current, next)

        val snapshot = PagesDirectorySnapshot.from(next)
        val result = PageDirectoryRequestResult.Applied(
            requestHash = record.requestHash,
            change = record.change,
            snapshot = snapshot,
        )
        insertCommandResult(
            workspaceId = next.workspaceId,
            actor = record.actor,
            requestId = record.requestId,
            requestHash = record.requestHash,
            requestBody = record.requestBody,
            outcomeType = "APPLIED",
            resultPayload = json.encodeToJsonElement(AppliedResultDto.serializer(), result.toDto()),
            createdAt = record.createdAt,
        )
        return result
    }

    override fun saveDirectoryWithinTx(current: PagesDirectory, next: PagesDirectory) {
        requireAmbientTransaction()
        check(next.workspaceId == current.workspaceId) { "workspace cannot change" }
        check(next.revision == current.revision.next()) { "mutation must advance exactly one revision" }
        val currentPagesById = current.pages.associateBy { it.id }
        val nextPagesById = next.pages.associateBy { it.id }

        nextPagesById.values.filter { it.id !in currentPagesById }.forEach(::insertPage)
        nextPagesById.values.filter { page -> currentPagesById[page.id] != null && currentPagesById[page.id] != page }
            .forEach(::updatePage)
        currentPagesById.values.filter { it.id !in nextPagesById }.forEach(::deletePage)

        val updated = jdbc.update(
            """UPDATE pages_relocation.page_directories
               SET placements = CAST(:placements AS JSONB),
                   revision = :revision,
                   updated_at = :updatedAt,
                   updated_by_kind = :actorKind,
                   updated_by_id = :actorId
               WHERE workspace_id = :workspaceId AND revision = :previousRevision""",
            mapOf(
                "workspaceId" to next.workspaceId.toJavaUuid(),
                "placements" to json.encodeToString(ListSerializer(PagePlacement.serializer()), next.placements),
                "revision" to next.revision.value,
                "previousRevision" to current.revision.value,
                "updatedAt" to next.updatedAt.toEpochMilli(),
                "actorKind" to next.updatedBy.kind.name,
                "actorId" to next.updatedBy.id,
            ),
        )
        check(updated == 1) { "PagesDirectory revision changed while locked" }
    }

    override fun saveNonAdvancingResultWithinTx(
        current: PagesDirectory,
        record: PageDirectoryRequestRecord,
        result: PageDirectoryRequestResult.NonAdvancing,
    ): PageDirectoryRequestResult.NonAdvancing {
        val snapshot = PagesDirectorySnapshot.from(current)
        check(result.requestHash == record.requestHash) { "request result hash must match its record" }
        check(result.snapshot == snapshot) { "non-advancing result must preserve the locked snapshot" }
        val (outcomeType, resultPayload) = when (result) {
            is PageDirectoryRequestResult.RevisionConflict -> "REVISION_CONFLICT" to
                json.encodeToJsonElement(
                    SnapshotResultDto.serializer(),
                    SnapshotResultDto(snapshot.toDto()),
                )
            is PageDirectoryRequestResult.Rejected -> "REJECTED" to
                json.encodeToJsonElement(
                    RejectedResultDto.serializer(),
                    RejectedResultDto(result.reason.toDto(), snapshot.toDto()),
                )
        }
        insertCommandResult(
            workspaceId = current.workspaceId,
            actor = record.actor,
            requestId = record.requestId,
            requestHash = record.requestHash,
            requestBody = record.requestBody,
            outcomeType = outcomeType,
            resultPayload = resultPayload,
            createdAt = record.createdAt,
        )
        return result
    }

    private fun insertCommandResult(
        workspaceId: Uuid,
        actor: PageActor,
        requestId: PageDirectoryRequestId,
        requestHash: String,
        requestBody: JsonElement,
        outcomeType: String,
        resultPayload: JsonElement,
        createdAt: Instant,
    ) {
        jdbc.update(
            """INSERT INTO pages_relocation.page_directory_command_results
               (workspace_id, actor_kind, actor_id, request_id, request_hash, request_body,
                outcome_type, result_payload, created_at)
               VALUES (:workspaceId, :actorKind, :actorId, :requestId, :requestHash, CAST(:requestBody AS JSONB),
                       :outcomeType, CAST(:resultPayload AS JSONB), :createdAt)""",
            mapOf(
                "workspaceId" to workspaceId.toJavaUuid(),
                "actorKind" to actor.kind.name,
                "actorId" to actor.id,
                "requestId" to requestId.value,
                "requestHash" to requestHash,
                "requestBody" to json.encodeToString(JsonElement.serializer(), requestBody),
                "outcomeType" to outcomeType,
                "resultPayload" to json.encodeToString(JsonElement.serializer(), resultPayload),
                "createdAt" to createdAt.toEpochMilli(),
            ),
        )
    }

    private fun findDirectoryWithPages(workspaceId: Uuid): PagesDirectory? {
        val rows = jdbc.query(
            """SELECT directory.workspace_id, directory.placements, directory.revision,
                      directory.updated_at, directory.updated_by_kind AS directory_updated_by_kind,
                      directory.updated_by_id AS directory_updated_by_id,
                      page.id AS page_id, page.kind AS page_kind, page.title AS page_title,
                      page.description AS page_description, page.dashboard_id,
                      page.created_at AS page_created_at, page.created_by_kind, page.created_by_id,
                      page.updated_at AS page_updated_at, page.updated_by_kind AS page_updated_by_kind,
                      page.updated_by_id AS page_updated_by_id,
                      page.created_by_agent_client_id, page.updated_by_agent_client_id
               FROM pages_relocation.page_directories directory
               LEFT JOIN pages_relocation.pages page ON page.workspace_id = directory.workspace_id
               WHERE directory.workspace_id = :workspaceId
               ORDER BY page.created_at ASC, page.id ASC""",
            mapOf("workspaceId" to workspaceId.toJavaUuid()),
        ) { rs, _ -> rs.toDirectoryRow() }
        val first = rows.firstOrNull() ?: return null
        val directory = PagesDirectory(
            workspaceId = first.workspaceId,
            pages = rows.mapNotNull { it.page },
            placements = first.placements,
            revision = first.revision,
            updatedAt = first.updatedAt,
            updatedBy = first.updatedBy,
        )
        check(directory.validate() == PagesDirectoryValidationResult.Valid) {
            "Persisted PagesDirectory violates domain invariants"
        }
        return directory
    }

    private fun insertPage(page: Page) {
        jdbc.update(
            """INSERT INTO pages_relocation.pages
               (id, workspace_id, kind, dashboard_id, title, description,
                created_at, created_by_kind, created_by_id, updated_at, updated_by_kind, updated_by_id,
                created_by_agent_client_id, updated_by_agent_client_id)
               VALUES (:id, :workspaceId, :kind, :dashboardId, :title, :description,
                       :createdAt, :createdByKind, :createdById, :updatedAt, :updatedByKind, :updatedById,
                       :createdByAgentClientId, :updatedByAgentClientId)""",
            page.parameters(),
        )
    }

    private fun updatePage(page: Page) {
        val updated = jdbc.update(
            """UPDATE pages_relocation.pages
               SET title = :title, description = :description, updated_at = :updatedAt,
                   updated_by_kind = :updatedByKind, updated_by_id = :updatedById,
                   updated_by_agent_client_id = :updatedByAgentClientId
               WHERE id = :id AND workspace_id = :workspaceId""",
            page.parameters(),
        )
        check(updated == 1) { "Page to update was not found" }
    }

    private fun deletePage(page: Page) {
        val deleted = jdbc.update(
            "DELETE FROM pages_relocation.pages WHERE id = :id AND workspace_id = :workspaceId",
            mapOf("id" to page.id.value.toJavaUuid(), "workspaceId" to page.workspaceId.toJavaUuid()),
        )
        check(deleted == 1) { "Page to delete was not found" }
    }

    private fun Page.parameters(): Map<String, Any?> = mapOf(
        "id" to id.value.toJavaUuid(),
        "workspaceId" to workspaceId.toJavaUuid(),
        "kind" to kind.name,
        "dashboardId" to dashboardId?.toJavaUuid(),
        "title" to navigationMetadata.title,
        "description" to navigationMetadata.description,
        "createdAt" to createdAt.toEpochMilli(),
        "createdByKind" to createdBy.kind.name,
        "createdById" to createdBy.id,
        "updatedAt" to updatedAt.toEpochMilli(),
        "updatedByKind" to updatedBy.kind.name,
        "updatedById" to updatedBy.id,
        "createdByAgentClientId" to createdByAgentClientId,
        "updatedByAgentClientId" to updatedByAgentClientId,
    )

    private fun ResultSet.toDirectoryRow(): DirectoryRow {
        val pageId = getObject("page_id", java.util.UUID::class.java)?.toKotlinUuid()
        return DirectoryRow(
            workspaceId = getObject("workspace_id", java.util.UUID::class.java).toKotlinUuid(),
            placements = json.decodeFromString(ListSerializer(PagePlacement.serializer()), getString("placements")),
            revision = PagesDirectoryRevision(getLong("revision")),
            updatedAt = Instant.ofEpochMilli(getLong("updated_at")),
            updatedBy = PageActor.create(
                PageActorKind.valueOf(getString("directory_updated_by_kind")),
                getString("directory_updated_by_id"),
            ),
            page = pageId?.let {
                Page.restore(
                    pageId = PageId(it),
                    workspaceId = getObject("workspace_id", java.util.UUID::class.java).toKotlinUuid(),
                    kind = PageKind.valueOf(getString("page_kind")),
                    dashboardId = getObject("dashboard_id", java.util.UUID::class.java)?.toKotlinUuid(),
                    navigationMetadata = PageNavigationMetadata.restore(getString("page_title"), getString("page_description")),
                    createdAt = Instant.ofEpochMilli(getLong("page_created_at")),
                    createdBy = PageActor.create(PageActorKind.valueOf(getString("created_by_kind")), getString("created_by_id")),
                    createdByAgentClientId = getString("created_by_agent_client_id"),
                    updatedAt = Instant.ofEpochMilli(getLong("page_updated_at")),
                    updatedBy = PageActor.create(
                        PageActorKind.valueOf(getString("page_updated_by_kind")),
                        getString("page_updated_by_id"),
                    ),
                    updatedByAgentClientId = getString("updated_by_agent_client_id"),
                )
            },
        )
    }

    private data class DirectoryRow(
        val workspaceId: Uuid,
        val placements: List<PagePlacement>,
        val revision: PagesDirectoryRevision,
        val updatedAt: Instant,
        val updatedBy: PageActor,
        val page: Page?,
    )

    private data class RequestResultRow(
        val requestHash: String,
        val outcomeType: String,
        val resultPayload: JsonElement,
    )

    @Serializable
    private data class SnapshotResultDto(
        val snapshot: DirectorySnapshotDto,
    )

    @Serializable
    private data class AppliedResultDto(
        val change: ChangeDto,
        val snapshot: DirectorySnapshotDto,
    ) {
        fun toDomain(requestHash: String): PageDirectoryRequestResult.Applied =
            PageDirectoryRequestResult.Applied(requestHash, change.toDomain(), snapshot.toDomain())
    }

    @Serializable
    private data class RejectedResultDto(
        val reason: RequestRejectionDto,
        val snapshot: DirectorySnapshotDto,
    )

    @Serializable
    private data class RequestRejectionDto(
        val kind: String,
        val reason: String?,
        val patchError: PatchErrorDto?,
    ) {
        fun toDomain(): PageDirectoryRequestRejection = when (kind) {
            "DIRECTORY_INVALID" -> PageDirectoryRequestRejection.DirectoryInvalid(
                PagesDirectoryRejection.valueOf(reason ?: error("directory rejection reason is missing")),
            )
            "METADATA_INVALID" -> PageDirectoryRequestRejection.MetadataInvalid(
                PageMetadataRejection.valueOf(reason ?: error("metadata rejection reason is missing")),
            )
            "PATCH_INVALID" -> PageDirectoryRequestRejection.PatchInvalid(
                (patchError ?: error("patch rejection error is missing")).toDomain(),
            )
            "CONTENT_UPLOAD_INVALID" -> PageDirectoryRequestRejection.ContentUploadInvalid(
                PageContentConsumptionRejection.valueOf(reason ?: error("content upload rejection reason is missing")),
            )
            else -> error("unknown PageDirectory rejection kind")
        }
    }

    @Serializable
    private data class PatchErrorDto(
        val code: String,
        val message: String,
        val path: String?,
        val operationIndex: Int?,
    ) {
        fun toDomain(): JsonPatchError = JsonPatchError(code, message, path, operationIndex)
    }

    @Serializable
    private data class DirectorySnapshotDto(
        val workspaceId: String,
        val pages: List<DirectoryPageDto>,
        val placements: List<PagePlacement>,
        val revision: Long,
        val updatedAtEpochMs: Long,
        val updatedBy: PageActor,
    ) {
        fun toDomain(): PagesDirectorySnapshot = PagesDirectorySnapshot(
            workspaceId = Uuid.parse(workspaceId),
            pages = pages.map(DirectoryPageDto::toDomain),
            placements = placements,
            revision = PagesDirectoryRevision(revision),
            updatedAt = Instant.ofEpochMilli(updatedAtEpochMs),
            updatedBy = updatedBy,
        )
    }

    @Serializable
    private data class DirectoryPageDto(
        val id: String,
        val workspaceId: String,
        val kind: PageKind,
        val dashboardId: String?,
        val title: String?,
        val description: String?,
        val createdAtEpochMs: Long,
        val createdBy: PageActor,
        val updatedAtEpochMs: Long,
        val updatedBy: PageActor,
    ) {
        fun toDomain(): Page = Page.restore(
            pageId = PageId(Uuid.parse(id)),
            workspaceId = Uuid.parse(workspaceId),
            kind = kind,
            dashboardId = dashboardId?.let(Uuid::parse),
            navigationMetadata = PageNavigationMetadata.restore(title, description),
            createdAt = Instant.ofEpochMilli(createdAtEpochMs),
            createdBy = createdBy,
            createdByAgentClientId = null,
            updatedAt = Instant.ofEpochMilli(updatedAtEpochMs),
            updatedBy = updatedBy,
            updatedByAgentClientId = null,
        )
    }

    private fun PagesDirectorySnapshot.toDto(): DirectorySnapshotDto = DirectorySnapshotDto(
        workspaceId = workspaceId.toString(),
        pages = pages.map { page ->
            DirectoryPageDto(
                id = page.id.toString(),
                workspaceId = page.workspaceId.toString(),
                kind = page.kind,
                dashboardId = page.dashboardId?.toString(),
                title = page.navigationMetadata.title,
                description = page.navigationMetadata.description,
                createdAtEpochMs = page.createdAt.toEpochMilli(),
                createdBy = page.createdBy,
                updatedAtEpochMs = page.updatedAt.toEpochMilli(),
                updatedBy = page.updatedBy,
            )
        },
        placements = placements,
        revision = revision.value,
        updatedAtEpochMs = updatedAt.toEpochMilli(),
        updatedBy = updatedBy,
    )

    private fun PageDirectoryRequestRejection.toDto(): RequestRejectionDto = when (this) {
        is PageDirectoryRequestRejection.DirectoryInvalid -> RequestRejectionDto(
            kind = "DIRECTORY_INVALID",
            reason = reason.name,
            patchError = null,
        )
        is PageDirectoryRequestRejection.MetadataInvalid -> RequestRejectionDto(
            kind = "METADATA_INVALID",
            reason = reason.name,
            patchError = null,
        )
        is PageDirectoryRequestRejection.PatchInvalid -> RequestRejectionDto(
            kind = "PATCH_INVALID",
            reason = null,
            patchError = PatchErrorDto(error.code, error.message, error.path, error.operationIndex),
        )
        is PageDirectoryRequestRejection.ContentUploadInvalid -> RequestRejectionDto(
            kind = "CONTENT_UPLOAD_INVALID",
            reason = reason.name,
            patchError = null,
        )
    }

    @Serializable
    private data class ChangeDto(
        val type: String,
        val pageId: String?,
        val appliedOperationCount: Int?,
    ) {
        fun toDomain(): PageDirectoryChange = when (type) {
            "FOLDER_CREATED" -> PageDirectoryChange.FolderCreated(PageId(Uuid.parse(pageId ?: error("pageId is missing"))))
            "DASHBOARD_PAGE_CREATED" -> PageDirectoryChange.DashboardPageCreated(
                PageId(Uuid.parse(pageId ?: error("pageId is missing"))),
            )
            "DOCUMENT_PAGE_CREATED" -> PageDirectoryChange.DocumentPageCreated(
                PageId(Uuid.parse(pageId ?: error("pageId is missing"))),
            )
            "NAVIGATION_METADATA_UPDATED" -> PageDirectoryChange.NavigationMetadataUpdated(
                PageId(Uuid.parse(pageId ?: error("pageId is missing"))),
            )
            "PAGE_DELETED" -> PageDirectoryChange.PageDeleted(PageId(Uuid.parse(pageId ?: error("pageId is missing"))))
            "PLACEMENTS_PATCHED" -> PageDirectoryChange.PlacementsPatched(
                appliedOperationCount ?: error("appliedOperationCount is missing"),
            )
            else -> error("unknown PageDirectory change type")
        }
    }

    private fun PageDirectoryRequestResult.Applied.toDto(): AppliedResultDto = AppliedResultDto(
        change = ChangeDto(
            type = change.type.name,
            pageId = change.pageIdOrNull()?.toString(),
            appliedOperationCount = (change as? PageDirectoryChange.PlacementsPatched)?.appliedOperationCount,
        ),
        snapshot = snapshot.toDto(),
    )

    private fun PageDirectoryChange.pageIdOrNull(): PageId? = when (this) {
        is PageDirectoryChange.FolderCreated -> pageId
        is PageDirectoryChange.DashboardPageCreated -> pageId
        is PageDirectoryChange.DocumentPageCreated -> pageId
        is PageDirectoryChange.NavigationMetadataUpdated -> pageId
        is PageDirectoryChange.PageDeleted -> pageId
        is PageDirectoryChange.PlacementsPatched -> null
    }

}
