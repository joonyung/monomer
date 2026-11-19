CREATE SCHEMA IF NOT EXISTS semantic;

CREATE TABLE IF NOT EXISTS semantic.semantic_models (
    semantic_model_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    definition JSONB NOT NULL CHECK (jsonb_typeof(definition) = 'object'),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    actor_id UUID,
    credential_surface TEXT CHECK (credential_surface IN ('WEB', 'AGENT')),
    agent_client_id TEXT,
    UNIQUE (workspace_id, semantic_model_id),
    CONSTRAINT semantic_models_agent_client_id_check CHECK (
        agent_client_id IS NULL OR (credential_surface = 'AGENT' AND char_length(agent_client_id) BETWEEN 1 AND 2048)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS semantic_models_workspace_name_uq
    ON semantic.semantic_models (workspace_id, ((definition ->> 'name')));

CREATE INDEX IF NOT EXISTS semantic_models_search_page
    ON semantic.semantic_models (
        workspace_id,
        lower(coalesce(definition ->> 'label', definition ->> 'name')),
        lower(definition ->> 'name'),
        semantic_model_id
    );

CREATE INDEX IF NOT EXISTS semantic_models_latest
    ON semantic.semantic_models (workspace_id, updated_at DESC, semantic_model_id DESC);

CREATE TABLE IF NOT EXISTS semantic.metrics (
    metric_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    definition JSONB NOT NULL CHECK (jsonb_typeof(definition) = 'object'),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    actor_id UUID,
    credential_surface TEXT CHECK (credential_surface IN ('WEB', 'AGENT')),
    agent_client_id TEXT,
    UNIQUE (workspace_id, metric_id),
    CONSTRAINT metrics_agent_client_id_check CHECK (
        agent_client_id IS NULL OR (credential_surface = 'AGENT' AND char_length(agent_client_id) BETWEEN 1 AND 2048)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS metrics_workspace_name_uq
    ON semantic.metrics (workspace_id, ((definition ->> 'name')));

CREATE INDEX IF NOT EXISTS metrics_search_page
    ON semantic.metrics (
        workspace_id,
        lower(coalesce(definition ->> 'label', definition ->> 'name')),
        lower(definition ->> 'name'),
        metric_id
    );

CREATE INDEX IF NOT EXISTS metrics_latest
    ON semantic.metrics (workspace_id, updated_at DESC, metric_id DESC);

CREATE TABLE IF NOT EXISTS semantic.time_spine_configurations (
    workspace_id UUID NOT NULL,
    connector_id UUID NOT NULL,
    definition JSONB NOT NULL CHECK (jsonb_typeof(definition) = 'object'),
    PRIMARY KEY (workspace_id, connector_id)
);

CREATE TABLE IF NOT EXISTS semantic.mutation_receipts (
    workspace_id UUID NOT NULL,
    idempotency_key TEXT NOT NULL CHECK (btrim(idempotency_key) <> ''),
    actor_id UUID NOT NULL,
    mutation_kind TEXT NOT NULL
        CHECK (mutation_kind IN ('SEMANTIC_MODEL_CREATE', 'SEMANTIC_MODEL_UPDATE', 'METRIC_CREATE', 'METRIC_UPDATE')),
    mutation_subject TEXT NOT NULL CHECK (btrim(mutation_subject) <> ''),
    response_identity UUID NOT NULL,
    committed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workspace_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS semantic_models_recent_actor
    ON semantic.semantic_models (workspace_id, actor_id, updated_at DESC, semantic_model_id DESC);

CREATE INDEX IF NOT EXISTS metrics_recent_actor
    ON semantic.metrics (workspace_id, actor_id, updated_at DESC, metric_id DESC);

CREATE TABLE IF NOT EXISTS semantic.definition_changes (
    change_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    definition_kind TEXT NOT NULL CHECK (definition_kind IN ('SEMANTIC_MODEL', 'METRIC')),
    definition_id UUID NOT NULL,
    definition_name TEXT NOT NULL CHECK (btrim(definition_name) <> ''),
    action TEXT NOT NULL CHECK (action IN ('CREATED', 'UPDATED', 'DELETED')),
    definition JSONB NOT NULL CHECK (jsonb_typeof(definition) = 'object'),
    actor_id UUID,
    credential_surface TEXT CHECK (credential_surface IN ('WEB', 'AGENT')),
    agent_client_id TEXT,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT definition_changes_agent_client_id_check CHECK (
        agent_client_id IS NULL OR (credential_surface = 'AGENT' AND char_length(agent_client_id) BETWEEN 1 AND 2048)
    )
);

CREATE INDEX IF NOT EXISTS definition_changes_history
    ON semantic.definition_changes (workspace_id, definition_kind, definition_id, occurred_at DESC, change_id DESC);
CREATE INDEX IF NOT EXISTS definition_changes_recent
    ON semantic.definition_changes (workspace_id, definition_kind, occurred_at DESC, change_id DESC);
CREATE INDEX IF NOT EXISTS definition_changes_recent_actor
    ON semantic.definition_changes (workspace_id, definition_kind, actor_id, occurred_at DESC, change_id DESC);
