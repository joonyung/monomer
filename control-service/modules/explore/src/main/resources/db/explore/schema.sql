CREATE SCHEMA IF NOT EXISTS explore;

CREATE OR REPLACE FUNCTION explore.is_sha256_digest(candidate TEXT)
RETURNS BOOLEAN
LANGUAGE sql
IMMUTABLE
STRICT
AS $$ SELECT candidate ~ '^sha256:[0-9a-f]{64}$' $$;

CREATE OR REPLACE FUNCTION explore.reject_immutable_receipt_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'explore terminal receipt cannot be %', TG_OP;
END;
$$;

CREATE TABLE IF NOT EXISTS explore.admission_keys (
    workspace_id UUID NOT NULL,
    idempotency_key TEXT NOT NULL CHECK (btrim(idempotency_key) <> ''),
    PRIMARY KEY (workspace_id, idempotency_key)
);

CREATE TABLE IF NOT EXISTS explore.runs (
    run_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    idempotency_key TEXT NOT NULL CHECK (btrim(idempotency_key) <> ''),
    owner_principal_id UUID NOT NULL,
    credential_surface TEXT CHECK (credential_surface IN ('WEB', 'AGENT')),
    connector_id UUID NOT NULL,
    connector_type TEXT NOT NULL CHECK (
        connector_type IN (
            'POSTGRESQL', 'BIGQUERY', 'SNOWFLAKE', 'TRINO', 'REDSHIFT', 'DATABRICKS', 'DUCKDB'
        )
    ),
    kind TEXT NOT NULL CHECK (kind IN ('SQL', 'SEMANTIC')),
    request_digest TEXT NOT NULL CHECK (explore.is_sha256_digest(request_digest)),
    state TEXT NOT NULL CHECK (state IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    failure_code TEXT CHECK (
        failure_code IN (
            'PARTITION_FILTER_REQUIRED',
            'EXECUTION_FAILED', 'EXECUTION_ORPHANED', 'CANCELLED_BY_ACTOR', 'PERMISSION_DENIED',
            'CONNECTION_FAILED', 'AUTHENTICATION_FAILED'
        )
    ),
    revision BIGINT NOT NULL CHECK (revision >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    terminal_at TIMESTAMPTZ,
    agent_client_id TEXT,
    UNIQUE (workspace_id, idempotency_key),
    CONSTRAINT runs_agent_client_id_check CHECK (
        agent_client_id IS NULL OR (credential_surface = 'AGENT' AND char_length(agent_client_id) BETWEEN 1 AND 2048)
    ),
    UNIQUE (run_id, workspace_id),
    UNIQUE (run_id, workspace_id, kind),
    CHECK (updated_at >= created_at),
    CHECK (
        (state = 'RUNNING' AND failure_code IS NULL AND terminal_at IS NULL)
        OR (state = 'SUCCEEDED' AND failure_code IS NULL AND terminal_at IS NOT NULL)
        OR (state IN ('FAILED', 'CANCELLED') AND failure_code IS NOT NULL AND terminal_at IS NOT NULL)
    )
);

CREATE TABLE IF NOT EXISTS explore.semantic_run_details (
    run_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    kind TEXT NOT NULL CHECK (kind = 'SEMANTIC'),
    query_json TEXT NOT NULL CHECK (btrim(query_json) <> ''),
    output_column_displays JSONB CHECK (output_column_displays IS NULL OR jsonb_typeof(output_column_displays) = 'array'),
    reporting_time_zone TEXT CHECK (reporting_time_zone IS NULL OR btrim(reporting_time_zone) <> ''),
    PRIMARY KEY (run_id, workspace_id, kind),
    FOREIGN KEY (run_id, workspace_id, kind)
        REFERENCES explore.runs (run_id, workspace_id, kind)
);

CREATE TABLE IF NOT EXISTS explore.terminal_receipts (
    workspace_id UUID NOT NULL,
    settlement_idempotency_key TEXT NOT NULL CHECK (btrim(settlement_idempotency_key) <> ''),
    run_id UUID NOT NULL UNIQUE,
    terminal_state TEXT NOT NULL CHECK (terminal_state IN ('SUCCEEDED', 'FAILED', 'CANCELLED')),
    failure_code TEXT CHECK (
        failure_code IN (
            'PARTITION_FILTER_REQUIRED',
            'EXECUTION_FAILED', 'EXECUTION_ORPHANED', 'CANCELLED_BY_ACTOR', 'PERMISSION_DENIED',
            'CONNECTION_FAILED', 'AUTHENTICATION_FAILED'
        )
    ),
    failure_message TEXT,
    object_key TEXT,
    object_etag TEXT,
    byte_size BIGINT,
    media_type TEXT,
    row_count BIGINT,
    column_count INTEGER,
    retention_expires_at TIMESTAMPTZ,
    owner_principal_id UUID,
    settled_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workspace_id, settlement_idempotency_key),
    FOREIGN KEY (run_id, workspace_id) REFERENCES explore.runs (run_id, workspace_id),
    CHECK (
        (terminal_state = 'SUCCEEDED'
            AND failure_code IS NULL AND failure_message IS NULL
            AND object_key IS NOT NULL AND object_key LIKE 'artifacts/%'
            AND object_etag IS NOT NULL AND btrim(object_etag) <> ''
            AND byte_size IS NOT NULL AND byte_size > 0
            AND media_type IS NOT NULL AND btrim(media_type) <> ''
            AND row_count IS NOT NULL AND row_count >= 0
            AND column_count IS NOT NULL AND column_count > 0
            AND retention_expires_at IS NOT NULL AND retention_expires_at > settled_at
            AND owner_principal_id IS NOT NULL)
        OR (terminal_state <> 'SUCCEEDED'
            AND failure_code IS NOT NULL
            AND failure_message IS NOT NULL AND btrim(failure_message) <> ''
            AND object_key IS NULL AND object_etag IS NULL AND byte_size IS NULL
            AND media_type IS NULL AND row_count IS NULL AND column_count IS NULL
            AND retention_expires_at IS NULL AND owner_principal_id IS NULL)
    )
);

CREATE INDEX IF NOT EXISTS explore_terminal_receipts_result_lookup_index
    ON explore.terminal_receipts (run_id, owner_principal_id, retention_expires_at)
    WHERE terminal_state = 'SUCCEEDED';

CREATE INDEX IF NOT EXISTS explore_runs_recent_owner_index
    ON explore.runs (workspace_id, owner_principal_id, created_at DESC, run_id DESC);

DROP TRIGGER IF EXISTS terminal_receipts_immutable ON explore.terminal_receipts;
CREATE TRIGGER terminal_receipts_immutable
    BEFORE UPDATE OR DELETE ON explore.terminal_receipts
    FOR EACH ROW EXECUTE FUNCTION explore.reject_immutable_receipt_mutation();

CREATE INDEX IF NOT EXISTS runs_workspace_activity_live_idx
    ON explore.runs (workspace_id, created_at DESC, run_id DESC) WHERE state = 'RUNNING';
CREATE INDEX IF NOT EXISTS runs_workspace_activity_recent_idx
    ON explore.runs (workspace_id, terminal_at DESC, run_id DESC) WHERE state <> 'RUNNING';
