package cc.monomer.pages.adapter.outbound.postgres.pages.content

import cc.monomer.pages.domain.PageActor
import cc.monomer.pages.domain.PageActorKind
import cc.monomer.pages.domain.PageId
import cc.monomer.pages.domain.common.TransactionRunner
import cc.monomer.pages.domain.content.ClaimedPageContentObjectCleanup
import cc.monomer.pages.domain.content.DocumentPageVersionSource
import cc.monomer.pages.domain.content.PageContentCleanupClaimResult
import cc.monomer.pages.domain.content.PageContentCleanupObjectKind
import cc.monomer.pages.domain.content.PageContentCleanupRepository
import cc.monomer.pages.domain.content.PageContentCommandResult
import cc.monomer.pages.domain.content.PageContentFormat
import cc.monomer.pages.domain.content.PageContentHash
import cc.monomer.pages.domain.content.PageContentObjectCleanupCandidate
import cc.monomer.pages.domain.content.PageContentQueryPort
import cc.monomer.pages.domain.content.PageContentRepository
import cc.monomer.pages.domain.content.PageContentUpload
import cc.monomer.pages.domain.content.PageContentUploadExpirationTransition
import cc.monomer.pages.domain.content.PageContentUploadRef
import cc.monomer.pages.domain.content.PageContentUploadStatus
import cc.monomer.pages.domain.content.PageContentViewQueryPort
import cc.monomer.pages.domain.content.PageVersion
import cc.monomer.pages.domain.content.PageVersionMetadata
import cc.monomer.pages.domain.content.PageVersionNumber
import java.sql.ResultSet
import java.time.Instant
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager

class PostgresPageContentRepository(
    @param:Qualifier("pagesJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
    private val transactionRunner: TransactionRunner,
) : PageContentRepository, PageContentQueryPort, PageContentCleanupRepository, PageContentViewQueryPort {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    override suspend fun findUpload(uploadRef: PageContentUploadRef): PageContentUpload? = transactionRunner.readOnly {
        findUpload(uploadRef, lock = false)
    }

    override suspend fun findLatestDocumentFormats(
        workspaceId: Uuid,
        pageIds: Set<PageId>,
    ): Map<PageId, PageContentFormat> = transactionRunner.readOnly {
        if (pageIds.isEmpty()) return@readOnly emptyMap()
        jdbc.query(
            """SELECT DISTINCT ON (version.page_id) version.page_id, version.format
               FROM pages_relocation.page_versions version
               JOIN pages_relocation.pages page ON page.id = version.page_id
               WHERE page.workspace_id = :workspaceId
                 AND page.kind = 'DOCUMENT'
                 AND version.page_id IN (:pageIds)
               ORDER BY version.page_id, version.version_no DESC""",
            mapOf(
                "workspaceId" to workspaceId.toJavaUuid(),
                "pageIds" to pageIds.map { it.value.toJavaUuid() },
            ),
        ) { resultSet, _ ->
            PageId(resultSet.getObject("page_id", java.util.UUID::class.java).toKotlinUuid()) to
                PageContentFormat.valueOf(resultSet.getString("format"))
        }.toMap()
    }

    override suspend fun listVersions(
        workspaceId: Uuid,
        pageId: PageId,
        beforeVersionNumber: PageVersionNumber?,
        limit: Int,
    ): List<PageVersion> = transactionRunner.readOnly {
        require(limit in 1..101) { "version query limit must be between 1 and 101" }
        val beforeClause = if (beforeVersionNumber == null) "" else " AND version.version_no < :beforeVersionNo"
        jdbc.query(
            """SELECT version.page_id, version.version_no, version.format, version.source_object_ref,
                          version.content_hash, version.content_size_bytes,
                          version.finalized_by_kind, version.finalized_by_id, version.finalized_at,
                      version.finalized_by_agent_client_id
                   FROM pages_relocation.page_versions version
                   JOIN pages_relocation.pages page ON page.id = version.page_id
                   WHERE page.workspace_id = :workspaceId AND page.id = :pageId AND page.kind = 'DOCUMENT'""" +
                beforeClause + " ORDER BY version.version_no DESC LIMIT :limit",
            mapOf(
                "workspaceId" to workspaceId.toJavaUuid(),
                "pageId" to pageId.value.toJavaUuid(),
                "beforeVersionNo" to beforeVersionNumber?.value,
                "limit" to limit,
            ),
        ) { resultSet, _ -> resultSet.toVersion() }
    }

    override suspend fun findVersion(
        workspaceId: Uuid,
        pageId: PageId,
        versionNumber: PageVersionNumber,
    ): PageVersion? = transactionRunner.readOnly {
        jdbc.query(
            """SELECT version.page_id, version.version_no, version.format, version.source_object_ref,
                      version.content_hash, version.content_size_bytes,
                      version.finalized_by_kind, version.finalized_by_id, version.finalized_at,
                      version.finalized_by_agent_client_id
               FROM pages_relocation.page_versions version
               JOIN pages_relocation.pages page ON page.id = version.page_id
               WHERE page.workspace_id = :workspaceId AND page.id = :pageId AND page.kind = 'DOCUMENT'
                 AND version.version_no = :versionNo""",
            mapOf(
                "workspaceId" to workspaceId.toJavaUuid(),
                "pageId" to pageId.value.toJavaUuid(),
                "versionNo" to versionNumber.value,
            ),
        ) { resultSet, _ -> resultSet.toVersion() }.firstOrNull()
    }

    override suspend fun findDocumentVersionSource(
        workspaceId: Uuid,
        pageId: PageId,
        versionNumber: PageVersionNumber?,
    ): DocumentPageVersionSource? = transactionRunner.readOnly {
        val versionClause = if (versionNumber == null) "" else " AND version.version_no = :versionNo"
        jdbc.query(
            """SELECT page.title AS page_title,
                      version.page_id, version.version_no, version.format, version.source_object_ref,
                      version.content_hash, version.content_size_bytes,
                      version.finalized_by_kind, version.finalized_by_id, version.finalized_at,
                      version.finalized_by_agent_client_id
               FROM pages_relocation.page_versions version
               JOIN pages_relocation.pages page ON page.id = version.page_id
               WHERE page.workspace_id = :workspaceId AND page.id = :pageId AND page.kind = 'DOCUMENT'""" +
                versionClause + " ORDER BY version.version_no DESC LIMIT 1",
            mapOf(
                "workspaceId" to workspaceId.toJavaUuid(),
                "pageId" to pageId.value.toJavaUuid(),
                "versionNo" to versionNumber?.value,
            ),
        ) { resultSet, _ ->
            DocumentPageVersionSource(
                title = resultSet.getString("page_title"),
                version = resultSet.toVersion(),
            )
        }.singleOrNull()
    }

    override fun insertUploadWithinTx(upload: PageContentUpload) {
        requireOwnerTransaction()
        check(upload.status == PageContentUploadStatus.INITIATED) { "new upload must be initiated" }
        val inserted = jdbc.update(
            """INSERT INTO pages_relocation.page_content_uploads
               (upload_ref, workspace_id, actor_kind, actor_id, format, expected_hash, expected_size_bytes,
                temporary_object_ref, multipart_upload_id, expires_at, status, completion_request_hash,
                completion_started_at, completion_lease_expires_at, completion_claim_token,
                source_object_ref, actual_hash, actual_size_bytes, completed_at, consumed_at, aborted_at,
                consumed_by_page_id, failure_code, created_at)
               VALUES (:uploadRef, :workspaceId, :actorKind, :actorId, :format, :expectedHash, :expectedSizeBytes,
                       :temporaryObjectRef, :multipartUploadId, :expiresAt, :status, NULL, NULL, NULL, NULL,
                       NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, :createdAt)""",
            upload.parameters(),
        )
        check(inserted == 1) { "Page content upload insert failed" }
    }

    override fun findUploadForUpdateWithinTx(uploadRef: PageContentUploadRef): PageContentUpload? =
        findUpload(uploadRef, lock = true)

    override fun updateUploadWithinTx(current: PageContentUpload, next: PageContentUpload) {
        requireOwnerTransaction()
        check(current.uploadRef == next.uploadRef) { "upload ref cannot change" }
        check(current.workspaceId == next.workspaceId) { "upload workspace cannot change" }
        check(current.actor == next.actor) { "upload actor cannot change" }
        check(current.format == next.format) { "upload format cannot change" }
        check(current.expectedHash == next.expectedHash) { "upload expected hash cannot change" }
        check(current.expectedSizeBytes == next.expectedSizeBytes) { "upload expected size cannot change" }
        val updated = jdbc.update(
            """UPDATE pages_relocation.page_content_uploads
               SET status = :status,
                   completion_request_hash = :completionRequestHash,
                   completion_started_at = :completionStartedAt,
                   completion_lease_expires_at = :completionLeaseExpiresAt,
                   completion_claim_token = :completionClaimToken,
                   source_object_ref = :sourceObjectRef,
                   actual_hash = :actualHash,
                   actual_size_bytes = :actualSizeBytes,
                   completed_at = :completedAt,
                   consumed_at = :consumedAt,
                   consumed_by_page_id = :consumedByPageId,
                   aborted_at = :abortedAt,
                   failure_code = :failureCode
               WHERE upload_ref = :uploadRef AND status = :currentStatus""",
            next.parameters() + mapOf("currentStatus" to current.status.name),
        )
        check(updated == 1) { "Page content upload state changed while locked" }
    }

    override fun prepareDocumentPageDeletionWithinTx(
        workspaceId: Uuid,
        pageId: PageId,
        notBefore: Instant,
    ) {
        requireOwnerTransaction()
        val lockedDocument = jdbc.query(
            """SELECT kind FROM pages_relocation.pages
               WHERE workspace_id = :workspaceId AND id = :pageId FOR UPDATE""",
            mapOf("workspaceId" to workspaceId.toJavaUuid(), "pageId" to pageId.value.toJavaUuid()),
        ) { resultSet, _ -> resultSet.getString("kind") }.singleOrNull()
        if (lockedDocument != "DOCUMENT") return
        jdbc.query(
            """SELECT DISTINCT source_object_ref
               FROM pages_relocation.page_versions
               WHERE page_id = :pageId""",
            mapOf("pageId" to pageId.value.toJavaUuid()),
        ) { resultSet, _ -> resultSet.getString("source_object_ref") }
            .forEach { sourceObjectRef ->
                enqueueObjectCleanupWithinTx(
                    PageContentObjectCleanupCandidate(
                        workspaceId = workspaceId,
                        objectRef = sourceObjectRef,
                        objectKind = PageContentCleanupObjectKind.FINAL_SOURCE,
                        multipartUploadId = null,
                        notBefore = notBefore,
                        createdAt = notBefore,
                    ),
                )
            }
    }

    override fun lockDocumentPageWithinTx(workspaceId: Uuid, pageId: PageId): Boolean =
        jdbc.query(
            """SELECT kind FROM pages_relocation.pages
               WHERE workspace_id = :workspaceId AND id = :pageId FOR UPDATE""",
            mapOf("workspaceId" to workspaceId.toJavaUuid(), "pageId" to pageId.value.toJavaUuid()),
        ) { resultSet, _ -> resultSet.getString("kind") }.singleOrNull() == "DOCUMENT"

    override fun findLatestVersionNumberWithinTx(pageId: PageId): PageVersionNumber? =
        jdbc.query(
            """SELECT version_no FROM pages_relocation.page_versions
               WHERE page_id = :pageId ORDER BY version_no DESC LIMIT 1 FOR UPDATE""",
            mapOf("pageId" to pageId.value.toJavaUuid()),
        ) { resultSet, _ -> PageVersionNumber(resultSet.getInt("version_no")) }.singleOrNull()

    override fun lockCommandKeyWithinTx(
        workspaceId: Uuid,
        actor: PageActor,
        requestId: String,
        createdAt: Instant,
    ) {
        requireOwnerTransaction()
        jdbc.update(
            """INSERT INTO pages_relocation.page_content_command_key_fences
               (workspace_id, actor_kind, actor_id, request_id, created_at)
               VALUES (:workspaceId, :actorKind, :actorId, :requestId, :createdAt)
               ON CONFLICT (workspace_id, actor_kind, actor_id, request_id) DO NOTHING""",
            mapOf(
                "workspaceId" to workspaceId.toJavaUuid(),
                "actorKind" to actor.kind.name,
                "actorId" to actor.id,
                "requestId" to requestId,
                "createdAt" to createdAt.toEpochMilli(),
            ),
        )
        val locked = jdbc.queryForObject(
            """SELECT TRUE FROM pages_relocation.page_content_command_key_fences
               WHERE workspace_id = :workspaceId AND actor_kind = :actorKind
                 AND actor_id = :actorId AND request_id = :requestId FOR UPDATE""",
            mapOf(
                "workspaceId" to workspaceId.toJavaUuid(),
                "actorKind" to actor.kind.name,
                "actorId" to actor.id,
                "requestId" to requestId,
            ),
            Boolean::class.java,
        )
        check(locked == true) { "Page content command key fence lock failed" }
    }

    override fun findCommandResultWithinTx(
        workspaceId: Uuid,
        actor: PageActor,
        requestId: String,
    ): PageContentCommandResult? = jdbc.query(
        """SELECT request_hash, request_body, page_id, version_no, format, content_hash,
                  content_size_bytes, finalized_by_kind, finalized_by_id, finalized_at, created_at,
                  finalized_by_agent_client_id
           FROM pages_relocation.page_content_command_results
           WHERE workspace_id = :workspaceId AND actor_kind = :actorKind
             AND actor_id = :actorId AND request_id = :requestId FOR UPDATE""",
        mapOf(
            "workspaceId" to workspaceId.toJavaUuid(),
            "actorKind" to actor.kind.name,
            "actorId" to actor.id,
            "requestId" to requestId,
        ),
    ) { resultSet, _ ->
        PageContentCommandResult(
            workspaceId = workspaceId,
            actor = actor,
            requestId = requestId,
            requestHash = resultSet.getString("request_hash"),
            requestBody = json.parseToJsonElement(resultSet.getString("request_body")) as JsonObject,
            version = PageVersionMetadata(
                pageId = PageId(resultSet.getObject("page_id", java.util.UUID::class.java).toKotlinUuid()),
                versionNumber = PageVersionNumber(resultSet.getInt("version_no")),
                format = PageContentFormat.valueOf(resultSet.getString("format")),
                contentHash = PageContentHash(resultSet.getString("content_hash")),
                contentSizeBytes = resultSet.getLong("content_size_bytes"),
                finalizedBy = PageActor.create(
                    PageActorKind.valueOf(resultSet.getString("finalized_by_kind")),
                    resultSet.getString("finalized_by_id"),
                ),
                finalizedByAgentClientId = resultSet.getString("finalized_by_agent_client_id"),
                finalizedAt = Instant.ofEpochMilli(resultSet.getLong("finalized_at")),
            ),
            createdAt = Instant.ofEpochMilli(resultSet.getLong("created_at")),
        )
    }.singleOrNull()

    override fun insertVersionAndConsumeUploadWithinTx(
        upload: PageContentUpload,
        version: PageVersion,
        consumedAt: Instant,
    ) {
        requireOwnerTransaction()
        check(upload.status == PageContentUploadStatus.COMPLETED) { "version requires a completed upload" }
        check(version.format == upload.format) { "version format must match upload" }
        check(version.contentHash == upload.actualHash) { "version hash must match upload" }
        check(version.contentSizeBytes == upload.actualSizeBytes) { "version size must match upload" }
        val versionInserted = jdbc.update(
            """INSERT INTO pages_relocation.page_versions
               (page_id, version_no, format, source_object_ref, content_hash, content_size_bytes,
                finalized_by_kind, finalized_by_id, finalized_at, finalized_by_agent_client_id)
               VALUES (:pageId, :versionNo, :format, :sourceObjectRef, :contentHash, :contentSizeBytes,
                       :finalizedByKind, :finalizedById, :finalizedAt, :finalizedByAgentClientId)""",
            version.parameters(),
        )
        check(versionInserted == 1) { "Page version insert failed" }
        updateUploadWithinTx(upload, upload.consume(version.pageId, consumedAt))
    }

    override fun insertCommandResultWithinTx(result: PageContentCommandResult) {
        requireOwnerTransaction()
        val inserted = jdbc.update(
            """INSERT INTO pages_relocation.page_content_command_results
               (workspace_id, actor_kind, actor_id, request_id, request_hash, request_body,
                page_id, version_no, format, content_hash, content_size_bytes,
                finalized_by_kind, finalized_by_id, finalized_at, created_at, finalized_by_agent_client_id)
               VALUES (:workspaceId, :actorKind, :actorId, :requestId, :requestHash, CAST(:requestBody AS JSONB),
                       :pageId, :versionNo, :format, :contentHash, :contentSizeBytes,
                       :finalizedByKind, :finalizedById, :finalizedAt, :createdAt, :finalizedByAgentClientId)""",
            mapOf(
                "workspaceId" to result.workspaceId.toJavaUuid(),
                "actorKind" to result.actor.kind.name,
                "actorId" to result.actor.id,
                "requestId" to result.requestId,
                "requestHash" to result.requestHash,
                "requestBody" to json.encodeToString(JsonElement.serializer(), result.requestBody),
                "pageId" to result.version.pageId.value.toJavaUuid(),
                "versionNo" to result.version.versionNumber.value,
                "format" to result.version.format.name,
                "contentHash" to result.version.contentHash.value,
                "contentSizeBytes" to result.version.contentSizeBytes,
                "finalizedByKind" to result.version.finalizedBy.kind.name,
                "finalizedById" to result.version.finalizedBy.id,
                "finalizedByAgentClientId" to result.version.finalizedByAgentClientId,
                "finalizedAt" to result.version.finalizedAt.toEpochMilli(),
                "createdAt" to result.createdAt.toEpochMilli(),
            ),
        )
        check(inserted == 1) { "Page content command result insert failed" }
    }

    override fun enqueueObjectCleanupWithinTx(candidate: PageContentObjectCleanupCandidate) {
        requireOwnerTransaction()
        jdbc.update(
            """INSERT INTO pages_relocation.page_content_object_cleanup
               (workspace_id, object_ref, object_kind, multipart_upload_id, not_before, created_at, completed_at,
                status, claim_token, lease_expires_at, attempt_count, updated_at)
               VALUES (:workspaceId, :objectRef, :objectKind, :multipartUploadId, :notBefore, :createdAt, NULL,
                       'PENDING', NULL, NULL, 0, :updatedAt)
               ON CONFLICT (object_kind, object_ref) DO UPDATE
               SET not_before = LEAST(pages_relocation.page_content_object_cleanup.not_before, EXCLUDED.not_before),
                   completed_at = NULL, status = 'PENDING', claim_token = NULL,
                   lease_expires_at = NULL, updated_at = EXCLUDED.updated_at""",
            mapOf(
                "workspaceId" to candidate.workspaceId.toJavaUuid(),
                "objectRef" to candidate.objectRef,
                "objectKind" to candidate.objectKind.name,
                "multipartUploadId" to candidate.multipartUploadId,
                "notBefore" to candidate.notBefore.toEpochMilli(),
                "createdAt" to candidate.createdAt.toEpochMilli(),
                "updatedAt" to candidate.notBefore.toEpochMilli(),
            ),
        )
    }

    override suspend fun terminalizeExpiredUploads(limit: Int): Int {
        require(limit in 1..100) { "expired upload batch limit must be between 1 and 100" }
        return transactionRunner.run {
            val expiredAt = databaseNow()
            val uploads = jdbc.query(
                """SELECT upload_ref, workspace_id, actor_kind, actor_id, format, expected_hash,
                          expected_size_bytes, temporary_object_ref, multipart_upload_id, expires_at, status,
                          completion_request_hash, completion_started_at, completion_lease_expires_at,
                          completion_claim_token, source_object_ref, actual_hash, actual_size_bytes,
                          completed_at, consumed_at, consumed_by_page_id, aborted_at, failure_code, created_at
                   FROM pages_relocation.page_content_uploads
                   WHERE expires_at <= $DATABASE_NOW_MILLIS
                     AND (status IN ('INITIATED', 'FAILED', 'COMPLETED')
                          OR (status = 'COMPLETING' AND completion_lease_expires_at <= $DATABASE_NOW_MILLIS))
                   ORDER BY expires_at ASC, created_at ASC, upload_ref ASC
                   FOR UPDATE SKIP LOCKED LIMIT :limit""",
                mapOf("limit" to limit),
            ) { resultSet, _ -> resultSet.toUpload() }
            uploads.count { upload ->
                when (val transition = upload.expire(expiredAt)) {
                    is PageContentUploadExpirationTransition.Expired -> {
                        updateUploadWithinTx(upload, transition.upload)
                        enqueueObjectCleanupWithinTx(transition.upload.temporaryCleanup(expiredAt))
                        enqueueObjectCleanupWithinTx(
                            transition.upload.finalSourceCleanup(
                                "workspace/${transition.upload.workspaceId}/source/${transition.upload.uploadRef}",
                                expiredAt,
                            ),
                        )
                        true
                    }
                    is PageContentUploadExpirationTransition.Replayed,
                    PageContentUploadExpirationTransition.Consumed,
                    PageContentUploadExpirationTransition.InProgress,
                    -> false
                }
            }
        }
    }

    override suspend fun claimDueObjectCleanup(
        workerId: String,
        leaseDurationMillis: Long,
        limit: Int,
    ): List<ClaimedPageContentObjectCleanup> {
        require(workerId.isNotBlank() && workerId.length <= 200) { "cleanup workerId is invalid" }
        require(leaseDurationMillis in 1_000..300_000) {
            "cleanup leaseDurationMillis must be between 1000 and 300000"
        }
        require(limit in 1..100) { "cleanup claim limit must be between 1 and 100" }
        return transactionRunner.run {
            val temporaryClaims = claimCleanupRows(
                kind = PageContentCleanupObjectKind.TEMPORARY,
                leaseDurationMillis = leaseDurationMillis,
                limit = limit,
                requireNoPageReference = false,
            )
            val remaining = limit - temporaryClaims.size
            if (remaining == 0) return@run temporaryClaims
            temporaryClaims + claimCleanupRows(
                kind = PageContentCleanupObjectKind.FINAL_SOURCE,
                leaseDurationMillis = leaseDurationMillis,
                limit = remaining,
                requireNoPageReference = true,
            )
        }
    }

    private fun claimCleanupRows(
        kind: PageContentCleanupObjectKind,
        leaseDurationMillis: Long,
        limit: Int,
        requireNoPageReference: Boolean,
    ): List<ClaimedPageContentObjectCleanup> {
        val finalReferenceGuard = if (requireNoPageReference) {
            """AND NOT EXISTS (
                       SELECT 1 FROM pages_relocation.page_versions version
                       WHERE version.source_object_ref = cleanup.object_ref
                   )
                   AND NOT EXISTS (
                       SELECT 1 FROM pages_relocation.page_content_uploads upload
                       WHERE upload.source_object_ref = cleanup.object_ref AND upload.status = 'COMPLETED'
                   )"""
        } else {
            ""
        }
        return jdbc.query(
            """WITH available AS (
                   SELECT object_kind, object_ref
                   FROM pages_relocation.page_content_object_cleanup cleanup
                   WHERE cleanup.object_kind = :kind
                     AND ((cleanup.status = 'PENDING' AND cleanup.not_before <= $DATABASE_NOW_MILLIS)
                          OR (cleanup.status = 'CLEANING' AND cleanup.lease_expires_at <= $DATABASE_NOW_MILLIS))
                     $finalReferenceGuard
                   ORDER BY CASE WHEN cleanup.status = 'PENDING' THEN cleanup.not_before
                                 ELSE cleanup.lease_expires_at END ASC,
                            cleanup.updated_at ASC, cleanup.object_ref ASC
                   FOR UPDATE SKIP LOCKED LIMIT :limit
               ), claimed AS (
                   UPDATE pages_relocation.page_content_object_cleanup cleanup
                   SET status = 'CLEANING', claim_token = gen_random_uuid(),
                       lease_expires_at = $DATABASE_NOW_MILLIS + :leaseDurationMillis,
                       attempt_count = cleanup.attempt_count + 1,
                       updated_at = $DATABASE_NOW_MILLIS
                   FROM available
                   WHERE cleanup.object_kind = available.object_kind
                     AND cleanup.object_ref = available.object_ref
                   RETURNING cleanup.*
               )
               SELECT workspace_id, object_ref, object_kind, multipart_upload_id,
                      claim_token, lease_expires_at
               FROM claimed ORDER BY updated_at ASC, object_ref ASC""",
            mapOf(
                "kind" to kind.name,
                "leaseDurationMillis" to leaseDurationMillis,
                "limit" to limit,
            ),
        ) { resultSet, _ -> resultSet.toCleanupClaim() }
    }

    override suspend fun completeObjectCleanup(
        claim: ClaimedPageContentObjectCleanup,
    ): PageContentCleanupClaimResult = transactionRunner.run {
        val updated = jdbc.update(
            """UPDATE pages_relocation.page_content_object_cleanup
               SET status = 'COMPLETED', completed_at = $DATABASE_NOW_MILLIS,
                   claim_token = NULL, lease_expires_at = NULL, updated_at = $DATABASE_NOW_MILLIS
               WHERE object_kind = :objectKind AND object_ref = :objectRef
                 AND workspace_id = :workspaceId AND status = 'CLEANING'
                 AND claim_token = :claimToken AND lease_expires_at > $DATABASE_NOW_MILLIS""",
            claim.parameters(),
        )
        if (updated == 1) PageContentCleanupClaimResult.APPLIED else PageContentCleanupClaimResult.STALE
    }

    override suspend fun retryObjectCleanup(
        claim: ClaimedPageContentObjectCleanup,
        retryBackoffMillis: Long,
    ): PageContentCleanupClaimResult {
        require(retryBackoffMillis in 0..3_600_000) {
            "cleanup retryBackoffMillis must be between 0 and 3600000"
        }
        return transactionRunner.run {
            val updated = jdbc.update(
                """UPDATE pages_relocation.page_content_object_cleanup
                   SET status = 'PENDING', not_before = $DATABASE_NOW_MILLIS + :retryBackoffMillis,
                       claim_token = NULL, lease_expires_at = NULL, updated_at = $DATABASE_NOW_MILLIS
                   WHERE object_kind = :objectKind AND object_ref = :objectRef
                     AND workspace_id = :workspaceId AND status = 'CLEANING'
                     AND claim_token = :claimToken AND lease_expires_at > $DATABASE_NOW_MILLIS""",
                claim.parameters() + mapOf("retryBackoffMillis" to retryBackoffMillis),
            )
            if (updated == 1) PageContentCleanupClaimResult.APPLIED else PageContentCleanupClaimResult.STALE
        }
    }

    private fun requireOwnerTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Page content mutation requires an active owner transaction"
        }
        check(TransactionSynchronizationManager.hasResource(checkNotNull(jdbc.jdbcTemplate.dataSource))) {
            "Page content mutation requires the transaction bound to the exact DataSource"
        }
    }

    private fun databaseNow(): Instant = Instant.ofEpochMilli(
        checkNotNull(jdbc.queryForObject("SELECT $DATABASE_NOW_MILLIS", emptyMap<String, Any>(), Long::class.java)),
    )

    private fun findUpload(uploadRef: PageContentUploadRef, lock: Boolean): PageContentUpload? {
        val lockClause = if (lock) " FOR UPDATE" else ""
        return jdbc.query(
            """SELECT upload_ref, workspace_id, actor_kind, actor_id, format, expected_hash,
                      expected_size_bytes, temporary_object_ref, multipart_upload_id, expires_at, status,
                      completion_request_hash, completion_started_at, completion_lease_expires_at,
                      completion_claim_token, source_object_ref, actual_hash, actual_size_bytes,
                      completed_at, consumed_at, consumed_by_page_id, aborted_at, failure_code, created_at
               FROM pages_relocation.page_content_uploads
               WHERE upload_ref = :uploadRef""" + lockClause,
            mapOf("uploadRef" to uploadRef.value.toJavaUuid()),
        ) { resultSet, _ -> resultSet.toUpload() }.singleOrNull()
    }

    private fun ResultSet.toUpload(): PageContentUpload = PageContentUpload(
        uploadRef = PageContentUploadRef(getObject("upload_ref", java.util.UUID::class.java).toKotlinUuid()),
        workspaceId = getObject("workspace_id", java.util.UUID::class.java).toKotlinUuid(),
        actor = PageActor.create(PageActorKind.valueOf(getString("actor_kind")), getString("actor_id")),
        format = PageContentFormat.valueOf(getString("format")),
        expectedHash = PageContentHash(getString("expected_hash")),
        expectedSizeBytes = getLong("expected_size_bytes"),
        temporaryObjectRef = getString("temporary_object_ref"),
        multipartUploadId = getString("multipart_upload_id"),
        expiresAt = Instant.ofEpochMilli(getLong("expires_at")),
        status = PageContentUploadStatus.valueOf(getString("status")),
        completionRequestHash = getString("completion_request_hash"),
        completionStartedAt = getObject("completion_started_at", Long::class.javaObjectType)?.let(Instant::ofEpochMilli),
        completionLeaseExpiresAt = getObject("completion_lease_expires_at", Long::class.javaObjectType)
            ?.let(Instant::ofEpochMilli),
        completionClaimToken = getObject("completion_claim_token", java.util.UUID::class.java)?.toKotlinUuid(),
        sourceObjectRef = getString("source_object_ref"),
        actualHash = getString("actual_hash")?.let(::PageContentHash),
        actualSizeBytes = getObject("actual_size_bytes", Long::class.javaObjectType),
        completedAt = getObject("completed_at", Long::class.javaObjectType)?.let(Instant::ofEpochMilli),
        consumedAt = getObject("consumed_at", Long::class.javaObjectType)?.let(Instant::ofEpochMilli),
        consumedByPageId = getObject("consumed_by_page_id", java.util.UUID::class.java)
            ?.toKotlinUuid()?.let(::PageId),
        abortedAt = getObject("aborted_at", Long::class.javaObjectType)?.let(Instant::ofEpochMilli),
        failureCode = getString("failure_code"),
        createdAt = Instant.ofEpochMilli(getLong("created_at")),
    )

    private fun ResultSet.toVersion(): PageVersion = PageVersion(
        pageId = PageId(getObject("page_id", java.util.UUID::class.java).toKotlinUuid()),
        versionNumber = PageVersionNumber(getInt("version_no")),
        format = PageContentFormat.valueOf(getString("format")),
        sourceObjectRef = getString("source_object_ref"),
        contentHash = PageContentHash(getString("content_hash")),
        contentSizeBytes = getLong("content_size_bytes"),
        finalizedBy = PageActor.create(
            PageActorKind.valueOf(getString("finalized_by_kind")),
            getString("finalized_by_id"),
        ),
        finalizedByAgentClientId = getString("finalized_by_agent_client_id"),
        finalizedAt = Instant.ofEpochMilli(getLong("finalized_at")),
    )

    private fun ResultSet.toCleanupClaim(): ClaimedPageContentObjectCleanup = ClaimedPageContentObjectCleanup(
        workspaceId = getObject("workspace_id", java.util.UUID::class.java).toKotlinUuid(),
        objectRef = getString("object_ref"),
        objectKind = PageContentCleanupObjectKind.valueOf(getString("object_kind")),
        multipartUploadId = getString("multipart_upload_id"),
        claimToken = getObject("claim_token", java.util.UUID::class.java).toKotlinUuid(),
        leaseExpiresAt = Instant.ofEpochMilli(getLong("lease_expires_at")),
    )

    private fun ClaimedPageContentObjectCleanup.parameters(): Map<String, Any> = mapOf(
        "workspaceId" to workspaceId.toJavaUuid(),
        "objectRef" to objectRef,
        "objectKind" to objectKind.name,
        "claimToken" to claimToken.toJavaUuid(),
    )

    private fun PageContentUpload.parameters(): Map<String, Any?> = mapOf(
        "uploadRef" to uploadRef.value.toJavaUuid(),
        "workspaceId" to workspaceId.toJavaUuid(),
        "actorKind" to actor.kind.name,
        "actorId" to actor.id,
        "format" to format.name,
        "expectedHash" to expectedHash.value,
        "expectedSizeBytes" to expectedSizeBytes,
        "temporaryObjectRef" to temporaryObjectRef,
        "multipartUploadId" to multipartUploadId,
        "expiresAt" to expiresAt.toEpochMilli(),
        "status" to status.name,
        "completionRequestHash" to completionRequestHash,
        "completionStartedAt" to completionStartedAt?.toEpochMilli(),
        "completionLeaseExpiresAt" to completionLeaseExpiresAt?.toEpochMilli(),
        "completionClaimToken" to completionClaimToken?.toJavaUuid(),
        "sourceObjectRef" to sourceObjectRef,
        "actualHash" to actualHash?.value,
        "actualSizeBytes" to actualSizeBytes,
        "completedAt" to completedAt?.toEpochMilli(),
        "consumedAt" to consumedAt?.toEpochMilli(),
        "consumedByPageId" to consumedByPageId?.value?.toJavaUuid(),
        "abortedAt" to abortedAt?.toEpochMilli(),
        "failureCode" to failureCode,
        "createdAt" to createdAt.toEpochMilli(),
    )

    private fun PageVersion.parameters(): Map<String, Any?> = mapOf(
        "pageId" to pageId.value.toJavaUuid(),
        "versionNo" to versionNumber.value,
        "format" to format.name,
        "sourceObjectRef" to sourceObjectRef,
        "contentHash" to contentHash.value,
        "contentSizeBytes" to contentSizeBytes,
        "finalizedByKind" to finalizedBy.kind.name,
        "finalizedById" to finalizedBy.id,
        "finalizedAt" to finalizedAt.toEpochMilli(),
        "finalizedByAgentClientId" to finalizedByAgentClientId,
    )

    private fun PageContentUpload.temporaryCleanup(notBefore: Instant): PageContentObjectCleanupCandidate =
        PageContentObjectCleanupCandidate(
            workspaceId = workspaceId,
            objectRef = temporaryObjectRef,
            objectKind = PageContentCleanupObjectKind.TEMPORARY,
            multipartUploadId = multipartUploadId,
            notBefore = notBefore,
            createdAt = createdAt,
        )

    private fun PageContentUpload.finalSourceCleanup(
        objectRef: String,
        notBefore: Instant,
    ): PageContentObjectCleanupCandidate = PageContentObjectCleanupCandidate(
        workspaceId = workspaceId,
        objectRef = objectRef,
        objectKind = PageContentCleanupObjectKind.FINAL_SOURCE,
        multipartUploadId = null,
        notBefore = notBefore,
        createdAt = createdAt,
    )

    private companion object {
        const val DATABASE_NOW_MILLIS = "CAST(EXTRACT(EPOCH FROM statement_timestamp()) * 1000 AS BIGINT)"
    }
}
