-- Dashboard owns one current definition, one shared Draft, Viewer sessions, and QueryRun facts.
-- Definition changes survive hard deletion; restoring a definition recreates only current state.

CREATE SCHEMA IF NOT EXISTS dashboard;

CREATE TABLE IF NOT EXISTS dashboard.dashboards (
    dashboard_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    definition JSONB NOT NULL,
    updated_by TEXT CHECK (updated_by IS NULL OR btrim(updated_by) <> ''),
    credential_surface TEXT CHECK (credential_surface IN ('WEB', 'AGENT')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    agent_client_id TEXT,
    CONSTRAINT dashboards_agent_client_id_check CHECK (
        agent_client_id IS NULL OR (credential_surface = 'AGENT' AND char_length(agent_client_id) BETWEEN 1 AND 2048)
    ),
    CONSTRAINT dashboards_id_not_nil CHECK (dashboard_id <> '00000000-0000-0000-0000-000000000000'),
    CONSTRAINT dashboards_timestamps_monotonic CHECK (updated_at >= created_at),
    CONSTRAINT dashboards_workspace_id_unique UNIQUE (workspace_id, dashboard_id)
);

DROP INDEX IF EXISTS dashboard.dashboards_workspace_updated_idx;

CREATE INDEX dashboards_workspace_updated_idx
    ON dashboard.dashboards (workspace_id, updated_at DESC, dashboard_id DESC);

CREATE TABLE IF NOT EXISTS dashboard.drafts (
    workspace_id UUID NOT NULL,
    dashboard_id UUID NOT NULL,
    definition JSONB NOT NULL,
    parameter_bindings JSONB NOT NULL,
    variables JSONB NOT NULL,
    authoring_revision BIGINT NOT NULL,
    target_query_runs JSONB NOT NULL,
    ready_results JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    last_edited_at TIMESTAMPTZ,
    last_edited_by TEXT CHECK (last_edited_by IS NULL OR btrim(last_edited_by) <> ''),
    last_edit_surface TEXT CHECK (last_edit_surface IN ('WEB', 'AGENT')),
    last_edit_agent_client_id TEXT,
    CONSTRAINT drafts_last_edit_agent_client_id_check CHECK (
        last_edit_agent_client_id IS NULL
        OR (last_edit_surface = 'AGENT' AND char_length(last_edit_agent_client_id) BETWEEN 1 AND 2048)
    ),
    CONSTRAINT drafts_edit_metadata CHECK ((last_edited_at IS NULL) = (last_edited_by IS NULL)),
    CONSTRAINT drafts_authoring_revision_non_negative CHECK (authoring_revision >= 0),
    CONSTRAINT drafts_timestamps_monotonic CHECK (updated_at >= created_at),
    CONSTRAINT drafts_workspace_dashboard_pk PRIMARY KEY (workspace_id, dashboard_id),
    CONSTRAINT drafts_dashboard_fk FOREIGN KEY (workspace_id, dashboard_id)
        REFERENCES dashboard.dashboards (workspace_id, dashboard_id)
);

CREATE TABLE IF NOT EXISTS dashboard.sessions (
    session_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    dashboard_id UUID NOT NULL,
    definition_snapshot JSONB NOT NULL,
    parameter_bindings JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT sessions_id_not_nil CHECK (session_id <> '00000000-0000-0000-0000-000000000000'),
    CONSTRAINT sessions_timestamps_monotonic CHECK (updated_at >= created_at),
    CONSTRAINT sessions_workspace_id_unique UNIQUE (workspace_id, session_id),
    CONSTRAINT sessions_dashboard_fk FOREIGN KEY (workspace_id, dashboard_id)
        REFERENCES dashboard.dashboards (workspace_id, dashboard_id)
);

DROP INDEX IF EXISTS dashboard.sessions_workspace_dashboard_updated_idx;

CREATE INDEX IF NOT EXISTS sessions_workspace_dashboard_created_idx
    ON dashboard.sessions (workspace_id, dashboard_id, created_at DESC, session_id DESC);

CREATE TABLE IF NOT EXISTS dashboard.query_runs (
    query_run_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    dashboard_id UUID NOT NULL,
    viewer_session_id UUID,
    query_name TEXT NOT NULL,
    query_type TEXT NOT NULL CHECK (query_type IN ('SEMANTIC', 'SQL')),
    connector_id UUID NOT NULL,
    resolved_query JSONB NOT NULL,
    rendered_sql TEXT CHECK (rendered_sql IS NULL OR btrim(rendered_sql) <> ''),
    output_column_names JSONB NOT NULL,
    output_column_displays JSONB CHECK (output_column_displays IS NULL OR jsonb_typeof(output_column_displays) = 'array'),
    reporting_time_zone TEXT,
    actor_id TEXT CHECK (actor_id IS NULL OR btrim(actor_id) <> ''),
    credential_surface TEXT CHECK (credential_surface IN ('WEB', 'AGENT')),
    state TEXT NOT NULL,
    result_manifest JSONB,
    failure_code TEXT,
    failure_message TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    agent_client_id TEXT,
    CONSTRAINT query_runs_agent_client_id_check CHECK (
        agent_client_id IS NULL OR (credential_surface = 'AGENT' AND char_length(agent_client_id) BETWEEN 1 AND 2048)
    ),
    CONSTRAINT query_runs_reporting_time_zone_check CHECK (
        reporting_time_zone IS NULL OR (query_type = 'SEMANTIC' AND btrim(reporting_time_zone) <> '')
    ),
    CONSTRAINT query_runs_id_not_nil CHECK (query_run_id <> '00000000-0000-0000-0000-000000000000'),
    CONSTRAINT query_runs_name_not_blank CHECK (length(btrim(query_name)) > 0),
    CONSTRAINT query_runs_state_valid CHECK (state IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT query_runs_terminal_payload CHECK (
        (state = 'SUCCEEDED' AND result_manifest IS NOT NULL AND failure_code IS NULL AND failure_message IS NULL)
        OR (state = 'FAILED' AND result_manifest IS NULL AND length(btrim(failure_code)) > 0)
        OR (state = 'CANCELLED' AND result_manifest IS NULL AND failure_code IS NULL AND failure_message IS NULL)
        OR (state = 'RUNNING' AND result_manifest IS NULL AND failure_code IS NULL AND failure_message IS NULL)
    ),
    CONSTRAINT query_runs_timestamps_monotonic CHECK (updated_at >= created_at),
    CONSTRAINT query_runs_workspace_id_unique UNIQUE (workspace_id, query_run_id),
    CONSTRAINT query_runs_dashboard_fk FOREIGN KEY (workspace_id, dashboard_id)
        REFERENCES dashboard.dashboards (workspace_id, dashboard_id),
    CONSTRAINT query_runs_viewer_session_fk FOREIGN KEY (workspace_id, viewer_session_id)
        REFERENCES dashboard.sessions (workspace_id, session_id)
);

DROP INDEX IF EXISTS dashboard.query_runs_dashboard_query_updated_idx;

CREATE INDEX query_runs_dashboard_query_updated_idx
    ON dashboard.query_runs (workspace_id, dashboard_id, viewer_session_id, query_name, created_at DESC, query_run_id DESC);

CREATE INDEX IF NOT EXISTS query_runs_viewer_session_created_idx
    ON dashboard.query_runs (workspace_id, viewer_session_id, created_at DESC, query_run_id DESC)
    WHERE viewer_session_id IS NOT NULL;

DROP INDEX IF EXISTS dashboard.query_runs_shared_draft_recent_idx;

CREATE INDEX query_runs_shared_draft_recent_idx
    ON dashboard.query_runs (workspace_id, dashboard_id, created_at DESC, query_run_id DESC)
    WHERE viewer_session_id IS NULL;

CREATE TABLE IF NOT EXISTS dashboard.command_receipts (
    workspace_id UUID NOT NULL,
    actor_id TEXT,
    idempotency_key TEXT NOT NULL,
    command_kind TEXT NOT NULL,
    subject_id TEXT NOT NULL,
    normalized_request JSONB NOT NULL,
    dashboard_id UUID,
    session_id UUID,
    query_run_ids JSONB NOT NULL,
    response_snapshot JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE NULLS NOT DISTINCT (workspace_id, actor_id, idempotency_key),
    CONSTRAINT command_receipts_public_actor CHECK ((actor_id IS NULL) = (command_kind = 'RESOLVE_PUBLIC_SESSION')),
    CONSTRAINT command_receipts_actor_not_blank CHECK (length(btrim(actor_id)) > 0),
    CONSTRAINT command_receipts_key_not_blank CHECK (length(btrim(idempotency_key)) > 0),
    CONSTRAINT command_receipts_kind_not_blank CHECK (length(btrim(command_kind)) > 0),
    CONSTRAINT command_receipts_subject_not_blank CHECK (length(btrim(subject_id)) > 0)
);

CREATE INDEX IF NOT EXISTS command_receipts_created_idx
    ON dashboard.command_receipts (workspace_id, created_at DESC);

CREATE INDEX IF NOT EXISTS query_runs_workspace_activity_live_idx
    ON dashboard.query_runs (workspace_id, created_at DESC, query_run_id DESC) WHERE state = 'RUNNING';
CREATE INDEX IF NOT EXISTS query_runs_workspace_activity_recent_idx
    ON dashboard.query_runs (workspace_id, updated_at DESC, query_run_id DESC) WHERE state <> 'RUNNING';

CREATE TABLE IF NOT EXISTS dashboard.schedule_cursors (
    workspace_id UUID NOT NULL,
    dashboard_id UUID NOT NULL,
    schedule_key TEXT NOT NULL,
    next_scheduled_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workspace_id, dashboard_id, schedule_key),
    FOREIGN KEY (workspace_id, dashboard_id) REFERENCES dashboard.dashboards(workspace_id, dashboard_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS schedule_cursors_due ON dashboard.schedule_cursors (next_scheduled_at, workspace_id);

CREATE TABLE IF NOT EXISTS dashboard.schedule_occurrences (
    workspace_id UUID NOT NULL,
    dashboard_id UUID NOT NULL,
    schedule_key TEXT NOT NULL,
    scheduled_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL,
    outcome TEXT NOT NULL CHECK (outcome IN ('ACCEPTED', 'SKIPPED', 'ADMISSION_FAILED')),
    reason TEXT,
    definition JSONB NOT NULL,
    parameters JSONB,
    session_id UUID,
    initial_query_runs JSONB NOT NULL,
    PRIMARY KEY (workspace_id, dashboard_id, schedule_key, scheduled_at),
    FOREIGN KEY (workspace_id, dashboard_id) REFERENCES dashboard.dashboards(workspace_id, dashboard_id) ON DELETE CASCADE,
    FOREIGN KEY (workspace_id, session_id) REFERENCES dashboard.sessions(workspace_id, session_id),
    CHECK ((outcome = 'ACCEPTED') = (session_id IS NOT NULL)),
    CHECK (outcome <> 'ACCEPTED' OR parameters IS NOT NULL),
    CHECK ((outcome = 'ACCEPTED') = (reason IS NULL)),
    CHECK (outcome = 'ACCEPTED' OR initial_query_runs = '{}'::jsonb)
);
CREATE INDEX IF NOT EXISTS schedule_occurrences_history ON dashboard.schedule_occurrences (workspace_id, dashboard_id, scheduled_at DESC, schedule_key DESC);
CREATE INDEX IF NOT EXISTS schedule_occurrences_last_accepted ON dashboard.schedule_occurrences (workspace_id, dashboard_id, schedule_key, scheduled_at DESC) WHERE outcome = 'ACCEPTED';
CREATE INDEX IF NOT EXISTS schedule_occurrences_session ON dashboard.schedule_occurrences (workspace_id, session_id) WHERE session_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS command_receipts_public_session ON dashboard.command_receipts (workspace_id, session_id) WHERE command_kind = 'RESOLVE_PUBLIC_SESSION';

CREATE TABLE IF NOT EXISTS dashboard.definition_changes (
    change_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    dashboard_id UUID NOT NULL,
    title TEXT NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('CREATED', 'APPLIED', 'DELETED')),
    definition JSONB NOT NULL,
    actor_id UUID,
    credential_surface TEXT CHECK (credential_surface IN ('WEB', 'AGENT')),
    agent_client_id TEXT,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT definition_changes_agent_client_id_check CHECK (
        agent_client_id IS NULL OR (credential_surface = 'AGENT' AND char_length(agent_client_id) BETWEEN 1 AND 2048)
    )
);
CREATE INDEX IF NOT EXISTS definition_changes_history
    ON dashboard.definition_changes (workspace_id, dashboard_id, occurred_at DESC, change_id DESC);
CREATE INDEX IF NOT EXISTS definition_changes_activity
    ON dashboard.definition_changes (workspace_id, occurred_at DESC, change_id DESC);
