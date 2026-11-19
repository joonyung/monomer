CREATE SCHEMA IF NOT EXISTS connector;

CREATE OR REPLACE FUNCTION connector.properties_are_valid(
    requested_connector_type TEXT,
    requested_properties JSONB
)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    requested_port INTEGER;
BEGIN
    IF jsonb_typeof(requested_properties) <> 'object'
        OR EXISTS (
            SELECT 1
            FROM jsonb_each(requested_properties) AS property(key, value)
            WHERE jsonb_typeof(property.value) <> 'string'
        )
    THEN
        RETURN FALSE;
    END IF;
    IF requested_connector_type = 'duckdb' THEN
        RETURN requested_properties IN ('{"sample_dataset":"acme-cloud@1"}'::jsonb, '{"sample_dataset":"acme-cloud@2"}'::jsonb, '{"sample_dataset":"acme-cloud@3"}'::jsonb);
    END IF;
    IF requested_connector_type = 'postgresql' THEN
        IF NOT (
            requested_properties ?& ARRAY['database', 'host', 'port']
            AND requested_properties - ARRAY['database', 'host', 'port', 'schema', 'ssl_mode'] = '{}'::jsonb
            AND requested_properties ->> 'database' ~ '^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$'
            AND requested_properties ->> 'host' ~ '^([A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?|\[[0-9A-Fa-f:]+\])$'
            AND requested_properties ->> 'host' !~ '\.\.'
            AND requested_properties ->> 'port' ~ '^[1-9][0-9]{0,4}$'
            AND (NOT requested_properties ? 'schema' OR requested_properties ->> 'schema' ~ '^[A-Za-z_][A-Za-z0-9_$]{0,127}$')
            AND (NOT requested_properties ? 'ssl_mode' OR requested_properties ->> 'ssl_mode' IN ('disable', 'require', 'verify_ca', 'verify_full'))
        ) THEN RETURN FALSE; END IF;
        requested_port := (requested_properties ->> 'port')::INTEGER;
        RETURN requested_port BETWEEN 1 AND 65535 AND requested_properties ->> 'port' = requested_port::TEXT;
    END IF;
    IF requested_connector_type = 'bigquery' THEN
        RETURN (SELECT count(*) = 2 FROM jsonb_object_keys(requested_properties))
            AND requested_properties ?& ARRAY['project_id', 'location']
            AND requested_properties ->> 'project_id' ~ '^[a-z][a-z0-9-]{4,28}[a-z0-9]$'
            AND requested_properties ->> 'location' ~ '^[A-Za-z0-9]+(-[A-Za-z0-9]+)*$';
    END IF;
    IF requested_connector_type = 'snowflake' THEN
        RETURN requested_properties ?& ARRAY['account', 'warehouse', 'database']
            AND requested_properties - ARRAY['account', 'warehouse', 'database', 'schema', 'role'] = '{}'::jsonb
            AND requested_properties ->> 'account' ~ '^([A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?|\[[0-9A-Fa-f:]+\])$'
            AND requested_properties ->> 'account' !~ '\.\.'
            AND requested_properties ->> 'warehouse' ~ '^[A-Za-z_][A-Za-z0-9_$]{0,127}$'
            AND requested_properties ->> 'database' ~ '^[A-Za-z_][A-Za-z0-9_$]{0,127}$'
            AND (NOT requested_properties ? 'schema' OR requested_properties ->> 'schema' ~ '^[A-Za-z_][A-Za-z0-9_$]{0,127}$')
            AND (NOT requested_properties ? 'role' OR requested_properties ->> 'role' ~ '^[A-Za-z_][A-Za-z0-9_$]{0,127}$');
    END IF;
    IF requested_connector_type = 'trino' THEN
        IF NOT (
            requested_properties ?& ARRAY['host', 'port', 'catalog']
            AND requested_properties - ARRAY['host', 'port', 'catalog', 'schema', 'ssl', 'sslVerification'] = '{}'::jsonb
            AND requested_properties ->> 'host' ~ '^([A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?|\[[0-9A-Fa-f:]+\])$'
            AND requested_properties ->> 'host' !~ '\.\.'
            AND requested_properties ->> 'port' ~ '^[1-9][0-9]{0,4}$'
            AND requested_properties ->> 'catalog' ~ '^[A-Za-z_][A-Za-z0-9_$]{0,127}$'
            AND (NOT requested_properties ? 'schema' OR requested_properties ->> 'schema' ~ '^[A-Za-z_][A-Za-z0-9_$]{0,127}$')
            AND (NOT requested_properties ? 'ssl' OR requested_properties ->> 'ssl' IN ('true', 'false'))
            AND (NOT requested_properties ? 'sslVerification' OR requested_properties ->> 'sslVerification' IN ('FULL', 'CA', 'NONE'))
        ) THEN RETURN FALSE; END IF;
        requested_port := (requested_properties ->> 'port')::INTEGER;
        RETURN requested_port BETWEEN 1 AND 65535 AND requested_properties ->> 'port' = requested_port::TEXT;
    END IF;
    IF requested_connector_type = 'redshift' THEN
        IF NOT (
            requested_properties ?& ARRAY['host', 'port', 'database']
            AND requested_properties - ARRAY['host', 'port', 'database', 'schema', 'sslMode'] = '{}'::jsonb
            AND requested_properties ->> 'host' ~ '^([A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?|\[[0-9A-Fa-f:]+\])$'
            AND requested_properties ->> 'host' !~ '\.\.'
            AND requested_properties ->> 'port' ~ '^[1-9][0-9]{0,4}$'
            AND requested_properties ->> 'database' ~ '^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$'
            AND (NOT requested_properties ? 'schema' OR requested_properties ->> 'schema' ~ '^[A-Za-z_][A-Za-z0-9_$]{0,127}$')
            AND (NOT requested_properties ? 'sslMode' OR requested_properties ->> 'sslMode' IN ('disable', 'allow', 'prefer', 'require', 'verify-ca', 'verify-full'))
        ) THEN RETURN FALSE; END IF;
        requested_port := (requested_properties ->> 'port')::INTEGER;
        RETURN requested_port BETWEEN 1 AND 65535 AND requested_properties ->> 'port' = requested_port::TEXT;
    END IF;
    IF requested_connector_type = 'databricks' THEN
        RETURN requested_properties ?& ARRAY['host', 'warehouseId']
            AND requested_properties - ARRAY['host', 'warehouseId', 'catalog', 'schema'] = '{}'::jsonb
            AND requested_properties ->> 'host' ~ '^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$'
            AND requested_properties ->> 'host' !~ '\.\.'
            AND requested_properties ->> 'warehouseId' ~ '^[A-Za-z0-9]{1,128}$'
            AND (NOT requested_properties ? 'catalog' OR requested_properties ->> 'catalog' ~ '^[A-Za-z_][A-Za-z0-9_$]{0,127}$')
            AND (NOT requested_properties ? 'schema' OR requested_properties ->> 'schema' ~ '^[A-Za-z_][A-Za-z0-9_$]{0,127}$');
    END IF;
    RETURN FALSE;
EXCEPTION
    WHEN invalid_text_representation OR numeric_value_out_of_range THEN
        RETURN FALSE;
END;
$$;

CREATE TABLE IF NOT EXISTS connector.credential_versions (
    credential_version_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    connector_id UUID NOT NULL,
    connector_type TEXT NOT NULL CHECK (
        connector_type IN ('bigquery', 'databricks', 'postgresql', 'redshift', 'snowflake', 'trino', 'duckdb')
    ),
    key_version TEXT NOT NULL CHECK (
        char_length(key_version) BETWEEN 1 AND 512
        AND key_version ~ '^[A-Za-z0-9._~:/-]+$'
        AND key_version !~ '(^|/)\.{1,2}(/|$)'
    ),
    nonce BYTEA NOT NULL CHECK (octet_length(nonce) = 12),
    additional_authenticated_data BYTEA NOT NULL CHECK (octet_length(additional_authenticated_data) BETWEEN 1 AND 8192),
    ciphertext BYTEA NOT NULL CHECK (octet_length(ciphertext) BETWEEN 17 AND 1048592),
    envelope_digest TEXT NOT NULL CHECK (envelope_digest ~ '^sha256:[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (workspace_id, credential_version_id),
    UNIQUE (workspace_id, connector_id, credential_version_id)
);

CREATE TABLE IF NOT EXISTS connector.connectors (
    connector_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    connector_type TEXT NOT NULL CHECK (
        connector_type IN ('bigquery', 'databricks', 'postgresql', 'redshift', 'snowflake', 'trino', 'duckdb')
    ),
    display_name TEXT NOT NULL CHECK (btrim(display_name) <> ''),
    properties JSONB NOT NULL CHECK (connector.properties_are_valid(connector_type, properties)),
    credential_version_id UUID,
    authority_revision BIGINT NOT NULL CHECK (authority_revision > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    CHECK (updated_at >= created_at),
    CHECK (deleted_at IS NULL OR deleted_at >= created_at),
    CONSTRAINT connectors_credential_matches_lifecycle
        CHECK ((deleted_at IS NULL) = (credential_version_id IS NOT NULL)),
    UNIQUE (workspace_id, connector_id),
    UNIQUE (workspace_id, connector_id, credential_version_id),
    FOREIGN KEY (workspace_id, connector_id, credential_version_id)
        REFERENCES connector.credential_versions (workspace_id, connector_id, credential_version_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS connectors_workspace_sample_idx ON connector.connectors (workspace_id) WHERE connector_type = 'duckdb';

CREATE INDEX IF NOT EXISTS connectors_workspace_id_idx
    ON connector.connectors (workspace_id, connector_id);

CREATE OR REPLACE FUNCTION connector.reject_connector_type_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.connector_type = 'duckdb' THEN
        RAISE EXCEPTION 'Sample connectors are managed by Monomer' USING ERRCODE = '23514';
    END IF;
    IF OLD.deleted_at IS NOT NULL THEN
        RAISE EXCEPTION 'Deleted connectors cannot be changed'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.connector_id IS DISTINCT FROM OLD.connector_id
        OR NEW.workspace_id IS DISTINCT FROM OLD.workspace_id
        OR NEW.connector_type IS DISTINCT FROM OLD.connector_type
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NEW.authority_revision <> OLD.authority_revision + 1
        OR (OLD.connector_type IN ('postgresql', 'redshift')
            AND NEW.properties ->> 'database' IS DISTINCT FROM OLD.properties ->> 'database')
    THEN
        RAISE EXCEPTION 'Connector identity, type, and two-depth database are immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS connector_identity_immutable ON connector.connectors;
CREATE TRIGGER connector_identity_immutable
BEFORE UPDATE ON connector.connectors
FOR EACH ROW EXECUTE FUNCTION connector.reject_connector_type_mutation();

CREATE OR REPLACE FUNCTION connector.reject_immutable_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'connector immutable authority rows cannot be changed';
END;
$$;

DROP TRIGGER IF EXISTS credential_versions_immutable ON connector.credential_versions;
CREATE TRIGGER credential_versions_immutable
BEFORE UPDATE ON connector.credential_versions
FOR EACH ROW EXECUTE FUNCTION connector.reject_immutable_change();

CREATE OR REPLACE FUNCTION connector.reject_live_credential_destruction()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM connector.connectors
        WHERE workspace_id = OLD.workspace_id
          AND connector_id = OLD.connector_id
          AND deleted_at IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'Credential versions are destroyed only with their deleted connector';
    END IF;
    RETURN OLD;
END;
$$;

DROP TRIGGER IF EXISTS credential_versions_destroyed_with_connector ON connector.credential_versions;
CREATE TRIGGER credential_versions_destroyed_with_connector
BEFORE DELETE ON connector.credential_versions
FOR EACH ROW EXECUTE FUNCTION connector.reject_live_credential_destruction();

CREATE TABLE IF NOT EXISTS connector.connector_mutation_receipts (
    workspace_id UUID NOT NULL,
    mutation_idempotency_key TEXT NOT NULL CHECK (btrim(mutation_idempotency_key) <> ''),
    actor_id UUID NOT NULL,
    mutation_kind TEXT NOT NULL CHECK (mutation_kind IN ('CREATE', 'REVISE', 'ROTATE_CREDENTIAL')),
    authorization_scope_digest TEXT NOT NULL CHECK (authorization_scope_digest ~ '^sha256:[0-9a-f]{64}$'),
    request_digest TEXT NOT NULL CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    connector_id UUID NOT NULL,
    credential_version_id UUID NOT NULL,
    committed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workspace_id, mutation_idempotency_key)
);

DROP TRIGGER IF EXISTS connector_mutation_receipts_immutable
    ON connector.connector_mutation_receipts;
CREATE TRIGGER connector_mutation_receipts_immutable
BEFORE UPDATE OR DELETE ON connector.connector_mutation_receipts
FOR EACH ROW EXECUTE FUNCTION connector.reject_immutable_change();
