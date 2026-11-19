CREATE SCHEMA IF NOT EXISTS execution_run;

CREATE TABLE IF NOT EXISTS execution_run.runs (
    run_id UUID PRIMARY KEY,
    owner_type TEXT NOT NULL CHECK (owner_type IN ('EXPLORE', 'CATALOG', 'DASHBOARD')),
    owner_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    idempotency_key TEXT NOT NULL CHECK (btrim(idempotency_key) <> ''),
    request_fingerprint_sha256 CHAR(64) NOT NULL
        CHECK (request_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    connector_id UUID NOT NULL,
    connector_type TEXT NOT NULL,
    connector_properties BYTEA NOT NULL,
    request_kind TEXT NOT NULL CHECK (request_kind = 'QUERY'),
    canonical_request_sha256 CHAR(64) NOT NULL
        CHECK (canonical_request_sha256 ~ '^[0-9a-f]{64}$'),
    connector_binding_sha256 CHAR(64) NOT NULL
        CHECK (connector_binding_sha256 ~ '^[0-9a-f]{64}$'),
    credential_version UUID NOT NULL,
    credential_key_version TEXT NOT NULL CHECK (btrim(credential_key_version) <> ''),
    credential_binding_sha256 CHAR(64) NOT NULL
        CHECK (credential_binding_sha256 ~ '^[0-9a-f]{64}$'),
    result_sink_schema_version INTEGER NOT NULL CHECK (result_sink_schema_version = 2),
    result_sink_kind TEXT NOT NULL CHECK (result_sink_kind IN ('ARTIFACT_UPLOAD', 'INLINE_STREAM')),
    result_sink_object_key TEXT,
    result_sink_maximum_artifact_rows NUMERIC(20, 0),
    result_sink_maximum_bytes NUMERIC(20, 0),
    result_sink_expires_at TIMESTAMP WITH TIME ZONE,
    result_sink_maximum_record_batch_rows INTEGER NOT NULL
        CHECK (result_sink_maximum_record_batch_rows > 0),
    result_sink_maximum_frame_bytes INTEGER NOT NULL
        CHECK (result_sink_maximum_frame_bytes > 0),
    result_sink_maximum_cell_bytes INTEGER NOT NULL
        CHECK (result_sink_maximum_cell_bytes > 0),
    result_sink_maximum_multipart_part_bytes INTEGER,
    dispatch_deadline_at TIMESTAMP WITH TIME ZONE NOT NULL,
    deadline TIMESTAMP WITH TIME ZONE NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    physical_terminal_idempotency_key TEXT UNIQUE,
    physical_outcome_kind TEXT CHECK (physical_outcome_kind IN ('SUCCEEDED', 'FAILED', 'CANCELLED')),
    physical_failure_code TEXT,
    physical_safe_message TEXT,
    cancel_reason_code TEXT,
    cancel_requested_at TIMESTAMP WITH TIME ZONE,
    owner_settled_at TIMESTAMP WITH TIME ZONE,
    owner_discarded_at TIMESTAMP WITH TIME ZONE,
    artifact_cleanup_completed_at TIMESTAMP WITH TIME ZONE,
    artifact_cleanup_last_attempted_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL CHECK (version >= 0),
    execution_occupancy_acquired_at TIMESTAMP WITH TIME ZONE,
    execution_occupancy_released_at TIMESTAMP WITH TIME ZONE,
    dispatch_claimed_at TIMESTAMP WITH TIME ZONE,
    last_heartbeat_at TIMESTAMP WITH TIME ZONE,
    progress_rows_processed NUMERIC(20, 0),
    progress_bytes_processed NUMERIC(20, 0),
    progress_phase TEXT,
    progress_observed_at TIMESTAMP WITH TIME ZONE,
    result_object_key TEXT,
    result_object_etag TEXT,
    result_byte_size NUMERIC(20, 0),
    result_row_count NUMERIC(20, 0),
    result_column_count INTEGER,
    result_media_type TEXT,
    UNIQUE (owner_type, owner_id, idempotency_key),
    UNIQUE (run_id, owner_type, owner_id, workspace_id),
    CHECK (dispatch_deadline_at > created_at),
    CHECK (deadline >= dispatch_deadline_at),
    CHECK (updated_at >= created_at),
    CHECK (execution_occupancy_acquired_at IS NULL OR dispatch_claimed_at IS NOT NULL),
    CHECK (execution_occupancy_released_at IS NULL OR (execution_occupancy_acquired_at IS NOT NULL
        AND execution_occupancy_released_at >= execution_occupancy_acquired_at AND physical_outcome_kind IS NOT NULL)),
    CHECK (owner_settled_at IS NULL OR owner_settled_at >= updated_at),
    CHECK (dispatch_claimed_at IS NULL OR dispatch_claimed_at >= created_at),
    CHECK (last_heartbeat_at IS NULL OR dispatch_claimed_at IS NOT NULL),
    CHECK (last_heartbeat_at IS NULL OR last_heartbeat_at >= created_at),
    CHECK (
        (result_sink_kind = 'ARTIFACT_UPLOAD'
            AND NULLIF(btrim(result_sink_object_key), '') IS NOT NULL
            AND result_sink_maximum_artifact_rows > 0
            AND result_sink_maximum_bytes > 0
            AND result_sink_expires_at >= deadline
            AND result_sink_maximum_multipart_part_bytes >= result_sink_maximum_frame_bytes)
        OR
        (result_sink_kind = 'INLINE_STREAM'
            AND result_sink_object_key IS NULL
            AND result_sink_maximum_artifact_rows IS NULL
            AND result_sink_maximum_bytes IS NULL
            AND result_sink_expires_at IS NULL
            AND result_sink_maximum_multipart_part_bytes IS NULL)
    ),
    CHECK (
        (physical_terminal_idempotency_key IS NULL AND physical_outcome_kind IS NULL)
        OR (physical_terminal_idempotency_key IS NOT NULL AND physical_outcome_kind IS NOT NULL)
    ),
    CHECK (
        (state = 'RUNNING' AND physical_outcome_kind IS NULL)
        OR (state = 'SUCCEEDED' AND physical_outcome_kind = 'SUCCEEDED')
        OR (state = 'FAILED' AND physical_outcome_kind = 'FAILED')
        OR (state = 'CANCELLED' AND physical_outcome_kind = 'CANCELLED')
    ),
    CHECK (
        (result_object_key IS NULL AND result_object_etag IS NULL AND result_byte_size IS NULL
            AND result_row_count IS NULL AND result_column_count IS NULL AND result_media_type IS NULL)
        OR
        (result_object_key IS NOT NULL AND result_object_etag IS NOT NULL
            AND result_byte_size IS NOT NULL AND result_row_count IS NOT NULL
            AND result_column_count IS NOT NULL AND result_media_type IS NOT NULL)
    ),
    CHECK (state <> 'SUCCEEDED' OR result_object_key IS NOT NULL
        OR result_sink_kind = 'INLINE_STREAM'),
    CHECK (owner_settled_at IS NULL OR physical_outcome_kind IS NOT NULL),
    CONSTRAINT owner_discarded_terminal_check CHECK (
        owner_discarded_at IS NULL OR
        (physical_outcome_kind IS NOT NULL AND owner_settled_at IS NOT NULL)
    ),
    CONSTRAINT artifact_cleanup_terminal_check CHECK (
        artifact_cleanup_completed_at IS NULL OR
        ((state IN ('FAILED', 'CANCELLED') OR (state = 'SUCCEEDED' AND owner_discarded_at IS NOT NULL))
            AND result_sink_kind = 'ARTIFACT_UPLOAD'
            AND artifact_cleanup_last_attempted_at IS NOT NULL)
    ),
    CONSTRAINT artifact_cleanup_attempt_terminal_check CHECK (
        artifact_cleanup_last_attempted_at IS NULL OR
        ((state IN ('FAILED', 'CANCELLED') OR (state = 'SUCCEEDED' AND owner_discarded_at IS NOT NULL))
            AND result_sink_kind = 'ARTIFACT_UPLOAD')
    )
);

CREATE INDEX IF NOT EXISTS execution_run_occupancy_idx
    ON execution_run.runs (workspace_id, deadline, last_heartbeat_at)
    WHERE execution_occupancy_acquired_at IS NOT NULL AND execution_occupancy_released_at IS NULL;

CREATE INDEX IF NOT EXISTS execution_run_recovery_idx
    ON execution_run.runs (state, COALESCE(last_heartbeat_at, dispatch_claimed_at), run_id)
    WHERE state = 'RUNNING';
CREATE INDEX IF NOT EXISTS execution_run_owner_settlement_idx
    ON execution_run.runs (updated_at, run_id)
    WHERE physical_outcome_kind IS NOT NULL AND owner_settled_at IS NULL AND owner_discarded_at IS NULL;
CREATE INDEX IF NOT EXISTS execution_run_artifact_cleanup_idx
    ON execution_run.runs (artifact_cleanup_last_attempted_at NULLS FIRST, run_id)
    WHERE (state IN ('FAILED', 'CANCELLED') OR (state = 'SUCCEEDED' AND owner_discarded_at IS NOT NULL))
      AND result_sink_kind = 'ARTIFACT_UPLOAD'
      AND artifact_cleanup_completed_at IS NULL;
CREATE INDEX IF NOT EXISTS execution_run_workspace_admission_idx
    ON execution_run.runs (workspace_id, state, created_at);

CREATE TABLE IF NOT EXISTS execution_run.dispatch_intents (
    run_id UUID PRIMARY KEY REFERENCES execution_run.runs(run_id),
    template_sha256 CHAR(64) NOT NULL CHECK (template_sha256 ~ '^[0-9a-f]{64}$'),
    assignment_template BYTEA NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    persisted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK (octet_length(assignment_template) BETWEEN 1 AND 2097152),
    CHECK (expires_at > persisted_at)
);

CREATE OR REPLACE FUNCTION execution_run.reject_append_only_record()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = '55000';
END;
$$;

DROP TRIGGER IF EXISTS dispatch_intents_append_only ON execution_run.dispatch_intents;
CREATE TRIGGER dispatch_intents_append_only
BEFORE UPDATE OR DELETE ON execution_run.dispatch_intents
FOR EACH ROW EXECUTE FUNCTION execution_run.reject_append_only_record();
