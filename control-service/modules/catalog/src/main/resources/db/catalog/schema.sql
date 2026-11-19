CREATE SCHEMA IF NOT EXISTS catalog;

CREATE OR REPLACE FUNCTION catalog.is_canonical_identifier(candidate TEXT)
RETURNS BOOLEAN
LANGUAGE sql
IMMUTABLE
STRICT
AS $$
    SELECT octet_length(candidate) BETWEEN 1 AND 1024
       AND candidate = normalize(candidate, NFC)
       AND candidate !~ '[[:cntrl:]]'
       AND candidate !~ '^[[:space:]]'
       AND candidate !~ '[[:space:]]$';
$$;

CREATE TABLE IF NOT EXISTS catalog.selections (
    workspace_id UUID NOT NULL,
    connector_id UUID NOT NULL,
    entry_catalog_name TEXT CHECK (
        entry_catalog_name IS NULL OR catalog.is_canonical_identifier(entry_catalog_name)
    ),
    selection JSONB NOT NULL,
    committed_by UUID NOT NULL,
    committed_at TIMESTAMPTZ NOT NULL,
    UNIQUE NULLS NOT DISTINCT (workspace_id, connector_id, entry_catalog_name)
);

CREATE TABLE IF NOT EXISTS catalog.connector_catalog_selections (
    workspace_id UUID NOT NULL,
    connector_id UUID NOT NULL,
    selected_catalog_names JSONB NOT NULL CHECK (jsonb_typeof(selected_catalog_names) = 'array'),
    committed_by UUID NOT NULL,
    committed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workspace_id, connector_id)
);

CREATE TABLE IF NOT EXISTS catalog.list_refresh_jobs (
    job_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    connector_id UUID NOT NULL,
    parent_kind TEXT NOT NULL CHECK (parent_kind IN ('CONNECTOR', 'CATALOG', 'SCHEMA')),
    catalog_name TEXT CHECK (catalog_name IS NULL OR catalog.is_canonical_identifier(catalog_name)),
    schema_name TEXT CHECK (schema_name IS NULL OR catalog.is_canonical_identifier(schema_name)),
    target_key TEXT NOT NULL CHECK (btrim(target_key) <> ''),
    child_kind TEXT NOT NULL CHECK (child_kind IN ('CATALOG', 'SCHEMA', 'RELATION')),
    requested_by UUID NOT NULL,
    credential_surface TEXT CHECK (credential_surface IN ('WEB', 'AGENT')),
    requested_at TIMESTAMPTZ NOT NULL,
    state TEXT NOT NULL CHECK (
        state IN ('RUNNING', 'CANCEL_REQUESTED', 'COMPLETED', 'COMPLETED_WITH_ERRORS', 'CANCELLED')
    ),
    runner_instance_id TEXT,
    heartbeat_at TIMESTAMPTZ,
    active_run_id UUID,
    failure_code TEXT,
    safe_message TEXT,
    terminal_at TIMESTAMPTZ,
    agent_client_id TEXT,
    CONSTRAINT list_refresh_jobs_agent_client_id_check CHECK (
        agent_client_id IS NULL OR (credential_surface = 'AGENT' AND char_length(agent_client_id) BETWEEN 1 AND 2048)
    ),
    CHECK ((parent_kind = 'CONNECTOR' AND catalog_name IS NULL AND schema_name IS NULL AND
            child_kind IN ('CATALOG', 'SCHEMA')) OR
           (parent_kind = 'CATALOG' AND catalog_name IS NOT NULL AND schema_name IS NULL AND
            child_kind = 'SCHEMA') OR
           (parent_kind = 'SCHEMA' AND schema_name IS NOT NULL AND child_kind = 'RELATION')),
    CHECK (runner_instance_id IS NULL OR btrim(runner_instance_id) <> ''),
    CHECK ((runner_instance_id IS NULL AND heartbeat_at IS NULL) OR
           (runner_instance_id IS NOT NULL AND heartbeat_at IS NOT NULL)),
    CHECK (runner_instance_id IS NOT NULL OR active_run_id IS NULL),
    CHECK (state <> 'CANCEL_REQUESTED' OR runner_instance_id IS NOT NULL),
    CHECK ((state IN ('RUNNING', 'CANCEL_REQUESTED') AND terminal_at IS NULL) OR
           (state IN ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'CANCELLED') AND
            terminal_at IS NOT NULL AND active_run_id IS NULL)),
    CHECK ((state = 'COMPLETED_WITH_ERRORS' AND failure_code IS NOT NULL AND safe_message IS NOT NULL) OR
           (state <> 'COMPLETED_WITH_ERRORS' AND failure_code IS NULL AND safe_message IS NULL)),
    UNIQUE (workspace_id, connector_id, job_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS list_refresh_jobs_one_active_per_target
    ON catalog.list_refresh_jobs(workspace_id, connector_id, target_key)
    WHERE state IN ('RUNNING', 'CANCEL_REQUESTED');

CREATE TABLE IF NOT EXISTS catalog.selection_option_lists (
    workspace_id UUID NOT NULL,
    connector_id UUID NOT NULL,
    parent_kind TEXT NOT NULL CHECK (parent_kind IN ('CONNECTOR', 'CATALOG', 'SCHEMA')),
    catalog_name TEXT CHECK (catalog_name IS NULL OR catalog.is_canonical_identifier(catalog_name)),
    schema_name TEXT CHECK (schema_name IS NULL OR catalog.is_canonical_identifier(schema_name)),
    target_key TEXT NOT NULL CHECK (btrim(target_key) <> ''),
    child_kind TEXT NOT NULL CHECK (child_kind IN ('CATALOG', 'SCHEMA', 'RELATION')),
    fetched_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workspace_id, connector_id, target_key),
    CHECK ((parent_kind = 'CONNECTOR' AND catalog_name IS NULL AND schema_name IS NULL AND
            child_kind IN ('CATALOG', 'SCHEMA')) OR
           (parent_kind = 'CATALOG' AND catalog_name IS NOT NULL AND schema_name IS NULL AND
            child_kind = 'SCHEMA') OR
           (parent_kind = 'SCHEMA' AND schema_name IS NOT NULL AND child_kind = 'RELATION'))
);

CREATE TABLE IF NOT EXISTS catalog.selection_options (
    workspace_id UUID NOT NULL,
    connector_id UUID NOT NULL,
    target_key TEXT NOT NULL,
    child_name TEXT NOT NULL CHECK (catalog.is_canonical_identifier(child_name)),
    relation_kind TEXT CHECK (
        relation_kind IS NULL OR
        relation_kind IN ('TABLE', 'VIEW', 'MATERIALIZED_VIEW', 'EXTERNAL', 'FOREIGN', 'DYNAMIC', 'SNAPSHOT', 'UNKNOWN')
    ),
    available BOOLEAN NOT NULL,
    PRIMARY KEY (workspace_id, connector_id, target_key, child_name),
    FOREIGN KEY (workspace_id, connector_id, target_key)
        REFERENCES catalog.selection_option_lists(workspace_id, connector_id, target_key)
);

CREATE INDEX IF NOT EXISTS selection_options_page
    ON catalog.selection_options(workspace_id, connector_id, target_key, child_name);

CREATE TABLE IF NOT EXISTS catalog.ingest_jobs (
    job_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    connector_id UUID NOT NULL,
    selection JSONB NOT NULL,
    entry_key TEXT NOT NULL CHECK (btrim(entry_key) <> ''),
    entry_kind TEXT NOT NULL CHECK (entry_kind IN ('CONNECTOR', 'CATALOG')),
    entry_catalog_name TEXT CHECK (
        entry_catalog_name IS NULL OR catalog.is_canonical_identifier(entry_catalog_name)
    ),
    requested_by UUID NOT NULL,
    credential_surface TEXT CHECK (credential_surface IN ('WEB', 'AGENT')),
    requested_at TIMESTAMPTZ NOT NULL,
    state TEXT NOT NULL CHECK (
        state IN ('RUNNING', 'CANCEL_REQUESTED', 'COMPLETED', 'COMPLETED_WITH_ERRORS', 'CANCELLED')
    ),
    runner_instance_id TEXT,
    heartbeat_at TIMESTAMPTZ,
    terminal_at TIMESTAMPTZ,
    agent_client_id TEXT,
    CONSTRAINT ingest_jobs_agent_client_id_check CHECK (
        agent_client_id IS NULL OR (credential_surface = 'AGENT' AND char_length(agent_client_id) BETWEEN 1 AND 2048)
    ),
    CHECK ((entry_kind = 'CONNECTOR' AND entry_catalog_name IS NULL) OR
           (entry_kind = 'CATALOG' AND entry_catalog_name IS NOT NULL)),
    CHECK (runner_instance_id IS NULL OR btrim(runner_instance_id) <> ''),
    CHECK ((runner_instance_id IS NULL AND heartbeat_at IS NULL) OR
           (runner_instance_id IS NOT NULL AND heartbeat_at IS NOT NULL)),
    CHECK ((state IN ('RUNNING', 'CANCEL_REQUESTED') AND
            terminal_at IS NULL) OR
           (state IN ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'CANCELLED') AND terminal_at IS NOT NULL)),
    UNIQUE (workspace_id, job_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS ingest_jobs_one_active_per_entry
    ON catalog.ingest_jobs(workspace_id, entry_key)
    WHERE state IN ('RUNNING', 'CANCEL_REQUESTED');

CREATE TABLE IF NOT EXISTS catalog.ingest_tasks (
    task_id UUID PRIMARY KEY,
    job_id UUID NOT NULL,
    parent_task_id UUID,
    workspace_id UUID NOT NULL,
    target_kind TEXT NOT NULL CHECK (target_kind IN ('CONNECTOR', 'CATALOG', 'SCHEMA', 'RELATION')),
    catalog_name TEXT CHECK (catalog_name IS NULL OR catalog.is_canonical_identifier(catalog_name)),
    schema_name TEXT CHECK (schema_name IS NULL OR catalog.is_canonical_identifier(schema_name)),
    relation_name TEXT CHECK (relation_name IS NULL OR catalog.is_canonical_identifier(relation_name)),
    relation_kind TEXT CHECK (
        relation_kind IS NULL OR
        relation_kind IN ('TABLE', 'VIEW', 'MATERIALIZED_VIEW', 'EXTERNAL', 'FOREIGN', 'DYNAMIC', 'SNAPSHOT', 'UNKNOWN')
    ),
    target_key TEXT NOT NULL CHECK (btrim(target_key) <> ''),
    state TEXT NOT NULL CHECK (state IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    active_run_id UUID,
    failure_code TEXT,
    safe_message TEXT,
    failure_run_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    terminal_at TIMESTAMPTZ,
    CHECK ((target_kind = 'CONNECTOR' AND catalog_name IS NULL AND schema_name IS NULL AND relation_name IS NULL AND relation_kind IS NULL) OR
           (target_kind = 'CATALOG' AND catalog_name IS NOT NULL AND schema_name IS NULL AND relation_name IS NULL AND relation_kind IS NULL) OR
           (target_kind = 'SCHEMA' AND schema_name IS NOT NULL AND relation_name IS NULL AND relation_kind IS NULL) OR
           (target_kind = 'RELATION' AND schema_name IS NOT NULL AND relation_name IS NOT NULL AND relation_kind IS NOT NULL)),
    CHECK ((state = 'RUNNING' AND terminal_at IS NULL AND failure_code IS NULL AND safe_message IS NULL AND failure_run_id IS NULL) OR
           (state = 'SUCCEEDED' AND terminal_at IS NOT NULL AND active_run_id IS NULL AND failure_code IS NULL AND safe_message IS NULL AND failure_run_id IS NULL) OR
           (state = 'FAILED' AND terminal_at IS NOT NULL AND active_run_id IS NULL AND failure_code IS NOT NULL AND safe_message IS NOT NULL) OR
           (state = 'CANCELLED' AND terminal_at IS NOT NULL AND active_run_id IS NULL AND failure_code IS NULL AND failure_run_id IS NULL)),
    FOREIGN KEY (workspace_id, job_id) REFERENCES catalog.ingest_jobs(workspace_id, job_id),
    UNIQUE (task_id, workspace_id, job_id),
    FOREIGN KEY (parent_task_id, workspace_id, job_id)
        REFERENCES catalog.ingest_tasks(task_id, workspace_id, job_id),
    UNIQUE (job_id, target_key)
);

CREATE INDEX IF NOT EXISTS ingest_tasks_job_parent
    ON catalog.ingest_tasks(job_id, parent_task_id);

CREATE TABLE IF NOT EXISTS catalog.current_objects (
    workspace_id UUID NOT NULL,
    connector_id UUID NOT NULL,
    object_id UUID NOT NULL,
    identity_digest TEXT NOT NULL CHECK (identity_digest ~ '^sha256:[0-9a-f]{64}$'),
    parent_object_id UUID,
    object_kind TEXT NOT NULL CHECK (object_kind IN ('CATALOG', 'SCHEMA', 'RELATION', 'COLUMN', 'RELATIONSHIP')),
    relation_kind TEXT CHECK (
        relation_kind IS NULL OR
        relation_kind IN ('TABLE', 'VIEW', 'MATERIALIZED_VIEW', 'EXTERNAL', 'FOREIGN', 'DYNAMIC', 'SNAPSHOT', 'UNKNOWN')
    ),
    catalog_name TEXT CHECK (catalog_name IS NULL OR catalog.is_canonical_identifier(catalog_name)),
    schema_name TEXT CHECK (schema_name IS NULL OR catalog.is_canonical_identifier(schema_name)),
    object_name TEXT CHECK (object_name IS NULL OR catalog.is_canonical_identifier(object_name)),
    member_name TEXT CHECK (member_name IS NULL OR catalog.is_canonical_identifier(member_name)),
    qualified_name TEXT NOT NULL,
    native_type TEXT,
    relation_definition TEXT,
    relation_partition_keys TEXT[],
    relation_clustering_keys TEXT[],
    relation_requires_partition_filter BOOLEAN,
    column_ordinal INTEGER,
    column_nullable BOOLEAN,
    column_default_expression TEXT,
    relationship_kind TEXT CHECK (
        relationship_kind IS NULL OR relationship_kind IN ('PRIMARY_KEY', 'FOREIGN_KEY')
    ),
    relationship_member_ordinal INTEGER,
    relationship_constraint_name TEXT CHECK (
        relationship_constraint_name IS NULL OR catalog.is_canonical_identifier(relationship_constraint_name)
    ),
    relationship_source_column TEXT CHECK (
        relationship_source_column IS NULL OR catalog.is_canonical_identifier(relationship_source_column)
    ),
    relationship_referenced_catalog TEXT CHECK (
        relationship_referenced_catalog IS NULL OR catalog.is_canonical_identifier(relationship_referenced_catalog)
    ),
    relationship_referenced_schema TEXT CHECK (
        relationship_referenced_schema IS NULL OR catalog.is_canonical_identifier(relationship_referenced_schema)
    ),
    relationship_referenced_relation TEXT CHECK (
        relationship_referenced_relation IS NULL OR catalog.is_canonical_identifier(relationship_referenced_relation)
    ),
    relationship_referenced_column TEXT CHECK (
        relationship_referenced_column IS NULL OR catalog.is_canonical_identifier(relationship_referenced_column)
    ),
    content_digest TEXT NOT NULL CHECK (content_digest ~ '^sha256:[0-9a-f]{64}$'),
    observed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workspace_id, connector_id, object_id),
    UNIQUE (workspace_id, connector_id, identity_digest),
    CHECK ((object_kind = 'RELATION' AND relation_kind IS NOT NULL) OR
           (object_kind <> 'RELATION' AND relation_kind IS NULL)),
    CHECK ((object_kind = 'RELATION') OR relation_definition IS NULL),
    CHECK ((object_kind = 'COLUMN' AND column_ordinal > 0 AND column_nullable IS NOT NULL) OR
           (object_kind <> 'COLUMN' AND column_ordinal IS NULL AND column_nullable IS NULL AND
            column_default_expression IS NULL)),
    CHECK ((object_kind = 'RELATIONSHIP' AND relationship_kind IS NOT NULL AND
            relationship_member_ordinal > 0 AND relationship_constraint_name IS NOT NULL AND
            relationship_source_column IS NOT NULL AND
            ((relationship_kind = 'PRIMARY_KEY' AND relationship_referenced_catalog IS NULL AND
              relationship_referenced_schema IS NULL AND relationship_referenced_relation IS NULL AND
              relationship_referenced_column IS NULL) OR
             (relationship_kind = 'FOREIGN_KEY' AND relationship_referenced_schema IS NOT NULL AND
              relationship_referenced_relation IS NOT NULL AND relationship_referenced_column IS NOT NULL))) OR
           (object_kind <> 'RELATIONSHIP' AND relationship_kind IS NULL AND
            relationship_member_ordinal IS NULL AND relationship_constraint_name IS NULL AND
            relationship_source_column IS NULL AND
            relationship_referenced_catalog IS NULL AND relationship_referenced_schema IS NULL AND
            relationship_referenced_relation IS NULL AND relationship_referenced_column IS NULL)),
    CHECK ((object_kind = 'CATALOG' AND catalog_name IS NOT NULL AND schema_name IS NULL AND object_name IS NULL AND member_name IS NULL) OR
           (object_kind = 'SCHEMA' AND schema_name IS NOT NULL AND object_name IS NULL AND member_name IS NULL) OR
           (object_kind = 'RELATION' AND schema_name IS NOT NULL AND object_name IS NOT NULL AND member_name IS NULL) OR
           (object_kind IN ('COLUMN', 'RELATIONSHIP') AND schema_name IS NOT NULL AND object_name IS NOT NULL AND member_name IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS current_objects_address
    ON catalog.current_objects(workspace_id, connector_id, catalog_name, schema_name, object_name, member_name);

CREATE UNIQUE INDEX IF NOT EXISTS current_objects_catalog_address
    ON catalog.current_objects(workspace_id, connector_id, catalog_name)
    WHERE object_kind = 'CATALOG';

CREATE UNIQUE INDEX IF NOT EXISTS current_objects_schema_address
    ON catalog.current_objects(workspace_id, connector_id, catalog_name, schema_name) NULLS NOT DISTINCT
    WHERE object_kind = 'SCHEMA';

CREATE UNIQUE INDEX IF NOT EXISTS current_objects_relation_address
    ON catalog.current_objects(workspace_id, connector_id, catalog_name, schema_name, object_name) NULLS NOT DISTINCT
    WHERE object_kind = 'RELATION';

CREATE INDEX IF NOT EXISTS current_relations_search_page
    ON catalog.current_objects(workspace_id, lower(qualified_name), connector_id, object_id)
    WHERE object_kind = 'RELATION';

CREATE INDEX IF NOT EXISTS ingest_jobs_workspace_activity_live_idx
    ON catalog.ingest_jobs (workspace_id, requested_at DESC, job_id DESC) WHERE terminal_at IS NULL;

CREATE INDEX IF NOT EXISTS ingest_jobs_workspace_activity_recent_idx
    ON catalog.ingest_jobs (workspace_id, terminal_at DESC, job_id DESC) WHERE terminal_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS list_refresh_jobs_workspace_activity_live_idx
    ON catalog.list_refresh_jobs (workspace_id, requested_at DESC, job_id DESC) WHERE terminal_at IS NULL;

CREATE INDEX IF NOT EXISTS list_refresh_jobs_workspace_activity_recent_idx
    ON catalog.list_refresh_jobs (workspace_id, terminal_at DESC, job_id DESC) WHERE terminal_at IS NOT NULL;
