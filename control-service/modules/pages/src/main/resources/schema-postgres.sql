-- Relocated Pages persistence.
CREATE SCHEMA IF NOT EXISTS pages_relocation;

CREATE TABLE IF NOT EXISTS pages_relocation.page_directories (
    workspace_id       UUID PRIMARY KEY,
    placements         JSONB NOT NULL,
    revision           BIGINT NOT NULL,
    updated_at         BIGINT NOT NULL,
    updated_by_kind    VARCHAR(20) NOT NULL,
    updated_by_id      VARCHAR(200) NOT NULL,

    CONSTRAINT ck_page_directories_placements_array CHECK (jsonb_typeof(placements) = 'array'),
    CONSTRAINT ck_page_directories_revision CHECK (revision >= 0),
    CONSTRAINT ck_page_directories_payload_size CHECK (octet_length(placements::text) <= 262144),
    CONSTRAINT ck_page_directories_actor_kind CHECK (updated_by_kind IN ('USER', 'AGENT', 'SYSTEM')),
    CONSTRAINT ck_page_directories_actor_id CHECK (btrim(updated_by_id) <> '')
);

CREATE TABLE IF NOT EXISTS pages_relocation.pages (
    id                 UUID PRIMARY KEY,
    workspace_id       UUID NOT NULL,
    kind               VARCHAR(20) NOT NULL,
    dashboard_id       UUID DEFAULT NULL,
    title              VARCHAR(500) DEFAULT NULL,
    description        TEXT DEFAULT NULL,
    created_at         BIGINT NOT NULL,
    created_by_kind    VARCHAR(20) NOT NULL,
    created_by_id      VARCHAR(200) NOT NULL,
    updated_at         BIGINT NOT NULL,
    updated_by_kind    VARCHAR(20) NOT NULL,
    updated_by_id      VARCHAR(200) NOT NULL,
    created_by_agent_client_id TEXT DEFAULT NULL,
    updated_by_agent_client_id TEXT DEFAULT NULL,

    CONSTRAINT ck_pages_agent_client_ids CHECK (
        (created_by_agent_client_id IS NULL
            OR (created_by_kind = 'AGENT' AND char_length(created_by_agent_client_id) BETWEEN 1 AND 2048))
        AND (updated_by_agent_client_id IS NULL
            OR (updated_by_kind = 'AGENT' AND char_length(updated_by_agent_client_id) BETWEEN 1 AND 2048))
    ),
    CONSTRAINT ck_pages_kind CHECK (kind IN ('FOLDER', 'DOCUMENT', 'DASHBOARD')),
    CONSTRAINT ck_pages_dashboard_reference CHECK ((kind = 'DASHBOARD') = (dashboard_id IS NOT NULL)),
    CONSTRAINT ck_pages_title_required CHECK (title IS NOT NULL),
    CONSTRAINT ck_pages_title_not_blank CHECK (title IS NULL OR btrim(title) <> ''),
    CONSTRAINT ck_pages_created_actor_kind CHECK (created_by_kind IN ('USER', 'AGENT', 'SYSTEM')),
    CONSTRAINT ck_pages_updated_actor_kind CHECK (updated_by_kind IN ('USER', 'AGENT', 'SYSTEM')),
    CONSTRAINT ck_pages_actor_ids CHECK (btrim(created_by_id) <> '' AND btrim(updated_by_id) <> ''),
    CONSTRAINT fk_pages_directory
        FOREIGN KEY (workspace_id)
        REFERENCES pages_relocation.page_directories(workspace_id)
        ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_pages_workspace ON pages_relocation.pages (workspace_id, created_at, id);

CREATE TABLE IF NOT EXISTS pages_relocation.page_directory_command_results (
    workspace_id       UUID NOT NULL,
    actor_kind         VARCHAR(20) NOT NULL,
    actor_id           VARCHAR(200) NOT NULL,
    request_id         VARCHAR(200) NOT NULL,
    request_hash       CHAR(64) NOT NULL,
    request_body       JSONB NOT NULL,
    outcome_type       VARCHAR(50) NOT NULL,
    result_payload     JSONB NOT NULL,
    created_at         BIGINT NOT NULL,

    CONSTRAINT pk_page_directory_command_results
        PRIMARY KEY (workspace_id, actor_kind, actor_id, request_id),
    CONSTRAINT fk_page_directory_command_results_directory
        FOREIGN KEY (workspace_id)
        REFERENCES pages_relocation.page_directories(workspace_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_page_directory_command_results_actor_kind
        CHECK (actor_kind IN ('USER', 'AGENT', 'SYSTEM')),
    CONSTRAINT ck_page_directory_command_results_identity
        CHECK (btrim(actor_id) <> '' AND btrim(request_id) <> ''),
    CONSTRAINT ck_page_directory_command_results_payload
        CHECK (jsonb_typeof(request_body) = 'object' AND jsonb_typeof(result_payload) = 'object'),
    CONSTRAINT ck_page_directory_command_results_outcome
        CHECK (outcome_type IN ('APPLIED', 'REVISION_CONFLICT', 'REJECTED'))
);

CREATE TABLE IF NOT EXISTS pages_relocation.page_content_uploads (
    upload_ref              UUID PRIMARY KEY,
    workspace_id            UUID NOT NULL,
    actor_kind              VARCHAR(20) NOT NULL,
    actor_id                VARCHAR(200) NOT NULL,
    format                  VARCHAR(20) NOT NULL,
    expected_hash           CHAR(64) NOT NULL,
    expected_size_bytes     BIGINT NOT NULL,
    temporary_object_ref    TEXT NOT NULL,
    multipart_upload_id     TEXT NOT NULL,
    expires_at              BIGINT NOT NULL,
    status                  VARCHAR(20) NOT NULL,
    completion_request_hash CHAR(64) DEFAULT NULL,
    completion_started_at   BIGINT DEFAULT NULL,
    completion_lease_expires_at BIGINT DEFAULT NULL,
    completion_claim_token  UUID DEFAULT NULL,
    source_object_ref       TEXT DEFAULT NULL,
    actual_hash             CHAR(64) DEFAULT NULL,
    actual_size_bytes       BIGINT DEFAULT NULL,
    completed_at            BIGINT DEFAULT NULL,
    consumed_at             BIGINT DEFAULT NULL,
    consumed_by_page_id     UUID DEFAULT NULL,
    aborted_at              BIGINT DEFAULT NULL,
    failure_code            VARCHAR(100) DEFAULT NULL,
    created_at              BIGINT NOT NULL,

    CONSTRAINT uk_page_content_uploads_temporary_object UNIQUE (temporary_object_ref),
    CONSTRAINT ck_page_content_uploads_actor_kind CHECK (actor_kind IN ('USER', 'AGENT')),
    CONSTRAINT ck_page_content_uploads_actor_id CHECK (btrim(actor_id) <> ''),
    CONSTRAINT ck_page_content_uploads_format CHECK (format IN ('MARKDOWN', 'NOTEBOOK', 'HTML')),
    CONSTRAINT ck_page_content_uploads_expected_hash CHECK (expected_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_page_content_uploads_expected_size CHECK (expected_size_bytes > 0),
    CONSTRAINT ck_page_content_uploads_refs CHECK (
        btrim(temporary_object_ref) <> '' AND btrim(multipart_upload_id) <> ''
    ),
    CONSTRAINT ck_page_content_uploads_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_page_content_uploads_status CHECK (
        status IN ('INITIATED', 'COMPLETING', 'COMPLETED', 'FAILED', 'CONSUMED', 'ABORTED')
    ),
    CONSTRAINT ck_page_content_uploads_completion_hash CHECK (
        completion_request_hash IS NULL OR completion_request_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_page_content_uploads_actual_hash CHECK (
        actual_hash IS NULL OR actual_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_page_content_uploads_actual_size CHECK (
        actual_size_bytes IS NULL OR actual_size_bytes > 0
    ),
    CONSTRAINT ck_page_content_uploads_failure_code CHECK (
        failure_code IS NULL OR failure_code ~ '^[A-Z0-9_.-]{1,100}$'
    ),
    CONSTRAINT ck_page_content_uploads_state CHECK (
        (status = 'INITIATED' AND completion_request_hash IS NULL AND completion_started_at IS NULL
            AND completion_lease_expires_at IS NULL AND source_object_ref IS NULL
            AND completion_claim_token IS NULL
            AND actual_hash IS NULL AND actual_size_bytes IS NULL AND completed_at IS NULL
            AND consumed_at IS NULL AND consumed_by_page_id IS NULL AND aborted_at IS NULL AND failure_code IS NULL) OR
        (status = 'COMPLETING' AND completion_request_hash IS NOT NULL AND completion_started_at IS NOT NULL
            AND completion_lease_expires_at > completion_started_at AND source_object_ref IS NULL
            AND completion_claim_token IS NOT NULL
            AND actual_hash IS NULL AND actual_size_bytes IS NULL AND completed_at IS NULL
            AND consumed_at IS NULL AND consumed_by_page_id IS NULL AND aborted_at IS NULL AND failure_code IS NULL) OR
        (status = 'COMPLETED' AND completion_request_hash IS NOT NULL AND completion_started_at IS NOT NULL
            AND completion_lease_expires_at IS NULL AND source_object_ref IS NOT NULL
            AND completion_claim_token IS NULL
            AND actual_hash IS NOT NULL AND actual_size_bytes IS NOT NULL AND completed_at IS NOT NULL
            AND consumed_at IS NULL AND consumed_by_page_id IS NULL AND aborted_at IS NULL AND failure_code IS NULL) OR
        (status = 'FAILED' AND completion_request_hash IS NOT NULL AND completion_started_at IS NOT NULL
            AND completion_lease_expires_at IS NULL AND source_object_ref IS NULL
            AND completion_claim_token IS NULL
            AND actual_hash IS NULL AND actual_size_bytes IS NULL AND completed_at IS NULL
            AND consumed_at IS NULL AND consumed_by_page_id IS NULL AND aborted_at IS NULL AND failure_code IS NOT NULL) OR
        (status = 'CONSUMED' AND completion_request_hash IS NOT NULL AND completion_started_at IS NOT NULL
            AND completion_lease_expires_at IS NULL AND source_object_ref IS NOT NULL
            AND completion_claim_token IS NULL
            AND actual_hash IS NOT NULL AND actual_size_bytes IS NOT NULL AND completed_at IS NOT NULL
            AND consumed_at IS NOT NULL AND consumed_by_page_id IS NOT NULL AND aborted_at IS NULL AND failure_code IS NULL) OR
        (status = 'ABORTED' AND completion_lease_expires_at IS NULL AND completion_claim_token IS NULL
            AND consumed_at IS NULL AND consumed_by_page_id IS NULL AND aborted_at IS NOT NULL
            AND aborted_at >= created_at AND (
                (source_object_ref IS NULL AND actual_hash IS NULL AND actual_size_bytes IS NULL
                    AND completed_at IS NULL AND (
                        (completion_request_hash IS NULL AND completion_started_at IS NULL AND failure_code IS NULL) OR
                        (completion_request_hash IS NOT NULL AND completion_started_at IS NOT NULL
                            AND failure_code IS NOT NULL)
                    )) OR
                (source_object_ref IS NOT NULL AND actual_hash IS NOT NULL AND actual_size_bytes IS NOT NULL
                    AND completed_at IS NOT NULL AND completion_request_hash IS NOT NULL
                    AND completion_started_at IS NOT NULL AND failure_code IS NULL)
            ))
    )
);

ALTER TABLE pages_relocation.page_content_uploads
    ADD COLUMN IF NOT EXISTS aborted_at BIGINT DEFAULT NULL;
ALTER TABLE pages_relocation.page_content_uploads
    DROP CONSTRAINT IF EXISTS ck_page_content_uploads_status;
ALTER TABLE pages_relocation.page_content_uploads
    ADD CONSTRAINT ck_page_content_uploads_status CHECK (
        status IN ('INITIATED', 'COMPLETING', 'COMPLETED', 'FAILED', 'CONSUMED', 'ABORTED')
    );
ALTER TABLE pages_relocation.page_content_uploads
    DROP CONSTRAINT IF EXISTS ck_page_content_uploads_state;
ALTER TABLE pages_relocation.page_content_uploads
    ADD CONSTRAINT ck_page_content_uploads_state CHECK (
        (status = 'INITIATED' AND completion_request_hash IS NULL AND completion_started_at IS NULL
            AND completion_lease_expires_at IS NULL AND source_object_ref IS NULL
            AND completion_claim_token IS NULL
            AND actual_hash IS NULL AND actual_size_bytes IS NULL AND completed_at IS NULL
            AND consumed_at IS NULL AND consumed_by_page_id IS NULL AND aborted_at IS NULL AND failure_code IS NULL) OR
        (status = 'COMPLETING' AND completion_request_hash IS NOT NULL AND completion_started_at IS NOT NULL
            AND completion_lease_expires_at > completion_started_at AND source_object_ref IS NULL
            AND completion_claim_token IS NOT NULL
            AND actual_hash IS NULL AND actual_size_bytes IS NULL AND completed_at IS NULL
            AND consumed_at IS NULL AND consumed_by_page_id IS NULL AND aborted_at IS NULL AND failure_code IS NULL) OR
        (status = 'COMPLETED' AND completion_request_hash IS NOT NULL AND completion_started_at IS NOT NULL
            AND completion_lease_expires_at IS NULL AND source_object_ref IS NOT NULL
            AND completion_claim_token IS NULL
            AND actual_hash IS NOT NULL AND actual_size_bytes IS NOT NULL AND completed_at IS NOT NULL
            AND consumed_at IS NULL AND consumed_by_page_id IS NULL AND aborted_at IS NULL AND failure_code IS NULL) OR
        (status = 'FAILED' AND completion_request_hash IS NOT NULL AND completion_started_at IS NOT NULL
            AND completion_lease_expires_at IS NULL AND source_object_ref IS NULL
            AND completion_claim_token IS NULL
            AND actual_hash IS NULL AND actual_size_bytes IS NULL AND completed_at IS NULL
            AND consumed_at IS NULL AND consumed_by_page_id IS NULL AND aborted_at IS NULL AND failure_code IS NOT NULL) OR
        (status = 'CONSUMED' AND completion_request_hash IS NOT NULL AND completion_started_at IS NOT NULL
            AND completion_lease_expires_at IS NULL AND source_object_ref IS NOT NULL
            AND completion_claim_token IS NULL
            AND actual_hash IS NOT NULL AND actual_size_bytes IS NOT NULL AND completed_at IS NOT NULL
            AND consumed_at IS NOT NULL AND consumed_by_page_id IS NOT NULL AND aborted_at IS NULL AND failure_code IS NULL) OR
        (status = 'ABORTED' AND completion_lease_expires_at IS NULL AND completion_claim_token IS NULL
            AND consumed_at IS NULL AND consumed_by_page_id IS NULL AND aborted_at IS NOT NULL
            AND aborted_at >= created_at AND (
                (source_object_ref IS NULL AND actual_hash IS NULL AND actual_size_bytes IS NULL
                    AND completed_at IS NULL AND (
                        (completion_request_hash IS NULL AND completion_started_at IS NULL AND failure_code IS NULL) OR
                        (completion_request_hash IS NOT NULL AND completion_started_at IS NOT NULL
                            AND failure_code IS NOT NULL)
                    )) OR
                (source_object_ref IS NOT NULL AND actual_hash IS NOT NULL AND actual_size_bytes IS NOT NULL
                    AND completed_at IS NOT NULL AND completion_request_hash IS NOT NULL
                    AND completion_started_at IS NOT NULL AND failure_code IS NULL)
            ))
    );

CREATE INDEX IF NOT EXISTS idx_page_content_uploads_workspace_actor
    ON pages_relocation.page_content_uploads (workspace_id, actor_kind, actor_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_page_content_uploads_expiry
    ON pages_relocation.page_content_uploads (status, expires_at)
    WHERE status IN ('INITIATED', 'COMPLETING', 'FAILED');

CREATE TABLE IF NOT EXISTS pages_relocation.page_versions (
    page_id               UUID NOT NULL,
    version_no            INT NOT NULL,
    format                VARCHAR(20) NOT NULL,
    source_object_ref     TEXT NOT NULL,
    content_hash          CHAR(64) NOT NULL,
    content_size_bytes    BIGINT NOT NULL,
    finalized_by_kind     VARCHAR(20) NOT NULL,
    finalized_by_id       VARCHAR(200) NOT NULL,
    finalized_at          BIGINT NOT NULL,
    finalized_by_agent_client_id TEXT DEFAULT NULL,

    CONSTRAINT ck_page_versions_agent_client_id CHECK (
        finalized_by_agent_client_id IS NULL
        OR (finalized_by_kind = 'AGENT' AND char_length(finalized_by_agent_client_id) BETWEEN 1 AND 2048)
    ),
    CONSTRAINT pk_page_versions PRIMARY KEY (page_id, version_no),
    CONSTRAINT fk_page_versions_page FOREIGN KEY (page_id)
        REFERENCES pages_relocation.pages(id)
        ON DELETE CASCADE,
    CONSTRAINT ck_page_versions_number CHECK (version_no > 0),
    CONSTRAINT ck_page_versions_format CHECK (format IN ('MARKDOWN', 'NOTEBOOK', 'HTML')),
    CONSTRAINT ck_page_versions_source_ref CHECK (btrim(source_object_ref) <> ''),
    CONSTRAINT ck_page_versions_content_hash CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_page_versions_content_size CHECK (content_size_bytes > 0),
    CONSTRAINT ck_page_versions_actor_kind CHECK (finalized_by_kind IN ('USER', 'AGENT', 'SYSTEM')),
    CONSTRAINT ck_page_versions_actor_id CHECK (btrim(finalized_by_id) <> '')
);

CREATE INDEX IF NOT EXISTS idx_page_versions_latest
    ON pages_relocation.page_versions (page_id, version_no DESC);
CREATE INDEX IF NOT EXISTS idx_page_versions_source_object
    ON pages_relocation.page_versions (source_object_ref);

CREATE TABLE IF NOT EXISTS pages_relocation.page_content_command_key_fences (
    workspace_id UUID NOT NULL,
    actor_kind   VARCHAR(20) NOT NULL,
    actor_id     VARCHAR(200) NOT NULL,
    request_id   VARCHAR(200) NOT NULL,
    created_at   BIGINT NOT NULL,

    CONSTRAINT pk_page_content_command_key_fences
        PRIMARY KEY (workspace_id, actor_kind, actor_id, request_id),
    CONSTRAINT ck_page_content_command_key_fences_actor_kind CHECK (actor_kind IN ('USER', 'AGENT')),
    CONSTRAINT ck_page_content_command_key_fences_actor_id CHECK (btrim(actor_id) <> ''),
    CONSTRAINT ck_page_content_command_key_fences_request_id CHECK (
        btrim(request_id) <> '' AND char_length(request_id) <= 200
    )
);

CREATE INDEX IF NOT EXISTS idx_page_content_command_key_fences_created
    ON pages_relocation.page_content_command_key_fences (created_at);

CREATE TABLE IF NOT EXISTS pages_relocation.page_content_command_results (
    workspace_id UUID NOT NULL,
    actor_kind   VARCHAR(20) NOT NULL,
    actor_id     VARCHAR(200) NOT NULL,
    request_id   VARCHAR(200) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    request_body JSONB NOT NULL,
    page_id      UUID NOT NULL,
    version_no   INT NOT NULL,
    format       VARCHAR(20) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    content_size_bytes BIGINT NOT NULL,
    finalized_by_kind VARCHAR(20) NOT NULL,
    finalized_by_id VARCHAR(200) NOT NULL,
    finalized_at BIGINT NOT NULL,
    created_at   BIGINT NOT NULL,
    finalized_by_agent_client_id TEXT DEFAULT NULL,

    CONSTRAINT ck_page_content_command_results_agent_client_id CHECK (
        finalized_by_agent_client_id IS NULL
        OR (finalized_by_kind = 'AGENT' AND char_length(finalized_by_agent_client_id) BETWEEN 1 AND 2048)
    ),
    CONSTRAINT pk_page_content_command_results
        PRIMARY KEY (workspace_id, actor_kind, actor_id, request_id),
    CONSTRAINT ck_page_content_command_results_actor_kind CHECK (actor_kind IN ('USER', 'AGENT')),
    CONSTRAINT ck_page_content_command_results_actor_id CHECK (btrim(actor_id) <> ''),
    CONSTRAINT ck_page_content_command_results_request_id CHECK (btrim(request_id) <> ''),
    CONSTRAINT ck_page_content_command_results_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_page_content_command_results_request_body CHECK (jsonb_typeof(request_body) = 'object'),
    CONSTRAINT ck_page_content_command_results_version CHECK (version_no > 0),
    CONSTRAINT ck_page_content_command_results_format CHECK (format IN ('MARKDOWN', 'NOTEBOOK', 'HTML')),
    CONSTRAINT ck_page_content_command_results_hash CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_page_content_command_results_size CHECK (content_size_bytes > 0),
    CONSTRAINT ck_page_content_command_results_finalized_actor_kind CHECK (
        finalized_by_kind IN ('USER', 'AGENT', 'SYSTEM')
    ),
    CONSTRAINT ck_page_content_command_results_finalized_actor_id CHECK (btrim(finalized_by_id) <> '')
);

CREATE TABLE IF NOT EXISTS pages_relocation.page_content_object_cleanup (
    workspace_id UUID NOT NULL,
    object_ref   TEXT NOT NULL,
    object_kind  VARCHAR(20) NOT NULL,
    multipart_upload_id TEXT DEFAULT NULL,
    not_before   BIGINT NOT NULL,
    created_at   BIGINT NOT NULL,
    completed_at BIGINT DEFAULT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    claim_token  UUID DEFAULT NULL,
    lease_expires_at BIGINT DEFAULT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    updated_at   BIGINT NOT NULL,

    CONSTRAINT pk_page_content_object_cleanup PRIMARY KEY (object_kind, object_ref),
    CONSTRAINT ck_page_content_object_cleanup_ref CHECK (btrim(object_ref) <> ''),
    CONSTRAINT ck_page_content_object_cleanup_kind CHECK (object_kind IN ('TEMPORARY', 'FINAL_SOURCE')),
    CONSTRAINT ck_page_content_object_cleanup_multipart CHECK (
        (object_kind = 'TEMPORARY' AND multipart_upload_id IS NOT NULL AND btrim(multipart_upload_id) <> '') OR
        (object_kind = 'FINAL_SOURCE' AND multipart_upload_id IS NULL)
    ),
    CONSTRAINT ck_page_content_object_cleanup_grace CHECK (not_before >= created_at)
);

ALTER TABLE pages_relocation.page_content_object_cleanup
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE pages_relocation.page_content_object_cleanup
    ADD COLUMN IF NOT EXISTS claim_token UUID DEFAULT NULL;
ALTER TABLE pages_relocation.page_content_object_cleanup
    ADD COLUMN IF NOT EXISTS lease_expires_at BIGINT DEFAULT NULL;
ALTER TABLE pages_relocation.page_content_object_cleanup
    ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE pages_relocation.page_content_object_cleanup
    ADD COLUMN IF NOT EXISTS updated_at BIGINT;
UPDATE pages_relocation.page_content_object_cleanup
SET status = CASE WHEN completed_at IS NULL THEN 'PENDING' ELSE 'COMPLETED' END,
    updated_at = COALESCE(updated_at, completed_at, created_at)
WHERE updated_at IS NULL;
ALTER TABLE pages_relocation.page_content_object_cleanup
    ALTER COLUMN updated_at SET NOT NULL;
ALTER TABLE pages_relocation.page_content_object_cleanup
    DROP CONSTRAINT IF EXISTS ck_page_content_object_cleanup_lifecycle;
ALTER TABLE pages_relocation.page_content_object_cleanup
    ADD CONSTRAINT ck_page_content_object_cleanup_lifecycle CHECK (
        (status = 'PENDING' AND completed_at IS NULL AND claim_token IS NULL AND lease_expires_at IS NULL) OR
        (status = 'CLEANING' AND completed_at IS NULL AND claim_token IS NOT NULL AND lease_expires_at IS NOT NULL) OR
        (status = 'COMPLETED' AND completed_at IS NOT NULL AND claim_token IS NULL AND lease_expires_at IS NULL)
    );
ALTER TABLE pages_relocation.page_content_object_cleanup
    DROP CONSTRAINT IF EXISTS ck_page_content_object_cleanup_attempt;
ALTER TABLE pages_relocation.page_content_object_cleanup
    ADD CONSTRAINT ck_page_content_object_cleanup_attempt CHECK (attempt_count >= 0);

DROP INDEX IF EXISTS pages_relocation.idx_page_content_object_cleanup_ready;
CREATE INDEX idx_page_content_object_cleanup_ready
    ON pages_relocation.page_content_object_cleanup (not_before, updated_at, object_ref)
    WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_page_content_object_cleanup_expired_claim
    ON pages_relocation.page_content_object_cleanup (lease_expires_at, updated_at, object_ref)
    WHERE status = 'CLEANING';
CREATE UNIQUE INDEX IF NOT EXISTS uk_page_content_object_cleanup_claim_token
    ON pages_relocation.page_content_object_cleanup (claim_token)
    WHERE claim_token IS NOT NULL;

-- audit_events
CREATE TABLE IF NOT EXISTS pages_relocation.audit_events (
    event_id          UUID PRIMARY KEY,
    aggregate_type    VARCHAR(50) NOT NULL,
    aggregate_id      UUID NOT NULL,
    event_type        VARCHAR(100) NOT NULL,
    payload           JSONB NOT NULL,
    created_at        BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_events_aggregate
    ON pages_relocation.audit_events (aggregate_type, aggregate_id);
