-- Auth service canonical runtime schema.
-- Re-runnable only on an empty or already-canonical auth schema. It is not a migration.

CREATE SCHEMA IF NOT EXISTS identity_runtime;

DO $$
DECLARE
    auth_schema_has_objects BOOLEAN;
    marker_shape_is_valid BOOLEAN;
    marker_row_count INTEGER;
    stored_contract_revision TEXT;
    stored_catalog_fingerprint TEXT;
    stored_fingerprint_function_hash TEXT;
    current_catalog_fingerprint TEXT;
    current_fingerprint_function_hash TEXT;
    fingerprint_function_oid OID;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('monomer-auth-schema-contract', 0));

    SELECT EXISTS (
        SELECT 1
          FROM pg_class relation
          JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
         WHERE namespace.nspname = 'identity_runtime'
        UNION ALL
        SELECT 1
          FROM pg_proc routine
          JOIN pg_namespace namespace ON namespace.oid = routine.pronamespace
         WHERE namespace.nspname = 'identity_runtime'
        UNION ALL
        SELECT 1
          FROM pg_type t
          JOIN pg_namespace namespace ON namespace.oid = t.typnamespace
         WHERE namespace.nspname = 'identity_runtime'
           AND t.typrelid = 0
        UNION ALL
        SELECT 1
          FROM pg_depend dependency
          JOIN pg_namespace namespace
            ON dependency.refclassid = 'pg_catalog.pg_namespace'::regclass
           AND dependency.refobjid = namespace.oid
         WHERE namespace.nspname = 'identity_runtime'
           AND dependency.deptype = 'n'
           AND dependency.refobjsubid = 0
           AND dependency.objsubid = 0
    ) INTO auth_schema_has_objects;

    IF auth_schema_has_objects THEN
        SELECT
            EXISTS (
                SELECT 1
                  FROM pg_class relation
                  JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
                 WHERE namespace.nspname = 'identity_runtime'
                   AND relation.relname = 'schema_contract'
                   AND relation.relkind = 'r'
            )
            AND count(*) = 5
            AND count(*) FILTER (
                WHERE column_name = 'singleton' AND data_type = 'boolean' AND is_nullable = 'NO'
            ) = 1
            AND count(*) FILTER (
                WHERE column_name = 'contract_revision' AND data_type = 'text' AND is_nullable = 'NO'
            ) = 1
            AND count(*) FILTER (
                WHERE column_name = 'catalog_fingerprint' AND data_type = 'text' AND is_nullable = 'NO'
            ) = 1
            AND count(*) FILTER (
                WHERE column_name = 'fingerprint_function_hash' AND data_type = 'text' AND is_nullable = 'NO'
            ) = 1
            AND count(*) FILTER (
                WHERE column_name = 'installed_at'
                  AND data_type = 'timestamp with time zone'
                  AND is_nullable = 'NO'
            ) = 1
          INTO marker_shape_is_valid
          FROM information_schema.columns
         WHERE table_schema = 'identity_runtime'
           AND table_name = 'schema_contract';

        fingerprint_function_oid := to_regprocedure('identity_runtime.compute_schema_catalog_fingerprint()');

        IF NOT marker_shape_is_valid OR fingerprint_function_oid IS NULL OR NOT EXISTS (
            SELECT 1
              FROM pg_proc routine
             WHERE routine.oid = fingerprint_function_oid
               AND routine.prokind = 'f'
               AND routine.prorettype = 'text'::regtype
               AND routine.pronargs = 0
        ) THEN
            RAISE EXCEPTION USING
                ERRCODE = 'PZ001',
                MESSAGE = 'AUTH_SCHEMA_CUTOVER_REQUIRED: Identity schema marker, catalog fingerprint or verifier mismatch; follow source-bound cutover procedures in control-service/modules/identity-access/docs/index.md#persistence; preserve data and obtain environment-specific cutover approval';
        END IF;

        BEGIN
            EXECUTE $query$
                SELECT count(*),
                       min(contract_revision),
                       min(catalog_fingerprint),
                       min(fingerprint_function_hash)
                  FROM identity_runtime.schema_contract
                 WHERE singleton
            $query$
            INTO marker_row_count,
                 stored_contract_revision,
                 stored_catalog_fingerprint,
                 stored_fingerprint_function_hash;
        EXCEPTION WHEN OTHERS THEN
            RAISE EXCEPTION USING
                ERRCODE = 'PZ001',
                MESSAGE = 'AUTH_SCHEMA_CUTOVER_REQUIRED: Identity schema marker, catalog fingerprint or verifier mismatch; follow source-bound cutover procedures in control-service/modules/identity-access/docs/index.md#persistence; preserve data and obtain environment-specific cutover approval';
        END;

        current_fingerprint_function_hash := md5(pg_get_functiondef(fingerprint_function_oid));

        IF marker_row_count <> 1
           OR stored_contract_revision IS DISTINCT FROM '2026-10-13.sample-dataset-v3'
           OR stored_fingerprint_function_hash IS DISTINCT FROM current_fingerprint_function_hash
        THEN
            RAISE EXCEPTION USING
                ERRCODE = 'PZ001',
                MESSAGE = 'AUTH_SCHEMA_CUTOVER_REQUIRED: Identity schema marker, catalog fingerprint or verifier mismatch; follow source-bound cutover procedures in control-service/modules/identity-access/docs/index.md#persistence; preserve data and obtain environment-specific cutover approval';
        END IF;

        BEGIN
            EXECUTE 'SELECT identity_runtime.compute_schema_catalog_fingerprint()'
               INTO current_catalog_fingerprint;
        EXCEPTION WHEN OTHERS THEN
            RAISE EXCEPTION USING
                ERRCODE = 'PZ001',
                MESSAGE = 'AUTH_SCHEMA_CUTOVER_REQUIRED: Identity schema marker, catalog fingerprint or verifier mismatch; follow source-bound cutover procedures in control-service/modules/identity-access/docs/index.md#persistence; preserve data and obtain environment-specific cutover approval';
        END;

        IF stored_catalog_fingerprint IS DISTINCT FROM current_catalog_fingerprint THEN
            RAISE EXCEPTION USING
                ERRCODE = 'PZ001',
                MESSAGE = 'AUTH_SCHEMA_CUTOVER_REQUIRED: Identity schema marker, catalog fingerprint or verifier mismatch; follow source-bound cutover procedures in control-service/modules/identity-access/docs/index.md#persistence; preserve data and obtain environment-specific cutover approval';
        END IF;
    END IF;
EXCEPTION WHEN SQLSTATE 'PZ001' THEN
    RAISE;
WHEN OTHERS THEN
    IF auth_schema_has_objects IS TRUE THEN
        RAISE EXCEPTION USING
            ERRCODE = 'PZ001',
            MESSAGE = 'AUTH_SCHEMA_CUTOVER_REQUIRED: Identity schema marker, catalog fingerprint or verifier mismatch; follow source-bound cutover procedures in control-service/modules/identity-access/docs/index.md#persistence; preserve data and obtain environment-specific cutover approval';
    END IF;
    RAISE;
END; $$ LANGUAGE plpgsql;

-- =============== Organizations (GitHub-style) ===============
CREATE TABLE IF NOT EXISTS identity_runtime.organizations (
    id            UUID PRIMARY KEY,
    display_name  TEXT NOT NULL,
    billing_email TEXT,
    plan          TEXT NOT NULL DEFAULT 'free',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =============== User identity ===============
CREATE TABLE IF NOT EXISTS identity_runtime.principals (
    id           UUID PRIMARY KEY,
    kind         TEXT NOT NULL CHECK (kind = 'user'),
    display_name TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Users are global (no organization_id); they join organizations via identity_runtime.organization_members.
CREATE TABLE IF NOT EXISTS identity_runtime.users (
    id            UUID PRIMARY KEY REFERENCES identity_runtime.principals(id) ON DELETE CASCADE,
    primary_email TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT users_primary_email_unique UNIQUE (primary_email),
    CONSTRAINT users_primary_email_normalized CHECK (
        primary_email = lower(btrim(primary_email)) AND primary_email <> ''
    )
);

CREATE TABLE IF NOT EXISTS identity_runtime.login_identities (
    id         UUID PRIMARY KEY,
    user_id    UUID NOT NULL REFERENCES identity_runtime.users(id) ON DELETE CASCADE,
    protocol   TEXT NOT NULL CHECK (protocol IN ('oidc','development')),
    namespace  TEXT NOT NULL CHECK (btrim(namespace) <> ''),
    subject    TEXT NOT NULL CHECK (btrim(subject) <> ''),
    linked_via TEXT NOT NULL CHECK (
        linked_via IN ('signup','explicit','sso_jit')
    ),
    linked_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT login_identities_protocol_namespace_subject_key
        UNIQUE (protocol, namespace, subject),
    CONSTRAINT login_identities_id_user_key UNIQUE (id, user_id)
);
CREATE INDEX IF NOT EXISTS login_identities_user_idx ON identity_runtime.login_identities(user_id);

CREATE TABLE IF NOT EXISTS identity_runtime.workspaces (
    id           UUID PRIMARY KEY,
    organization_id   UUID NOT NULL REFERENCES identity_runtime.organizations(id) ON DELETE CASCADE,
    display_name TEXT NOT NULL,
    authorization_revision BIGINT NOT NULL DEFAULT 0 CHECK (authorization_revision >= 0),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    public_pages BOOLEAN NOT NULL DEFAULT false,
    sample_dataset TEXT,
    sample_state TEXT,
    sample_initiator UUID REFERENCES identity_runtime.users(id),
    reporting_time_zone TEXT NOT NULL DEFAULT 'UTC',
    CONSTRAINT workspace_sample_marker CHECK (
        (sample_dataset IS NULL AND sample_state IS NULL AND sample_initiator IS NULL)
        OR (sample_dataset IS NOT NULL AND sample_state IS NOT NULL AND sample_dataset IN ('acme-cloud@1', 'acme-cloud@2', 'acme-cloud@3') AND sample_state IN ('PROVISIONING', 'READY') AND sample_initiator IS NOT NULL)
    )
);
CREATE INDEX IF NOT EXISTS workspaces_organization_idx ON identity_runtime.workspaces (organization_id);
CREATE UNIQUE INDEX IF NOT EXISTS workspaces_organization_sample_idx ON identity_runtime.workspaces (organization_id) WHERE sample_dataset IS NOT NULL;

-- =============== Memberships ===============
-- Organization membership — user joins organization; at least one ADMIN is required.
CREATE TABLE IF NOT EXISTS identity_runtime.organization_members (
    organization_id UUID NOT NULL REFERENCES identity_runtime.organizations(id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES identity_runtime.users(id)    ON DELETE CASCADE,
    role       TEXT NOT NULL CHECK (role IN ('ADMIN','MEMBER')),
    joined_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, user_id)
);
CREATE INDEX IF NOT EXISTS organization_members_user_idx ON identity_runtime.organization_members (user_id);

CREATE TABLE IF NOT EXISTS identity_runtime.workspace_members (
    workspace_id UUID NOT NULL REFERENCES identity_runtime.workspaces(id) ON DELETE CASCADE,
    user_id      UUID NOT NULL REFERENCES identity_runtime.users(id) ON DELETE CASCADE,
    invited_by   UUID REFERENCES identity_runtime.users(id) ON DELETE SET NULL,
    invited_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    role         TEXT NOT NULL CHECK (role IN ('VIEWER','EXPLORER','EDITOR','OWNER')),
    PRIMARY KEY (workspace_id, user_id)
);
CREATE INDEX IF NOT EXISTS workspace_members_user_idx ON identity_runtime.workspace_members (user_id, workspace_id);

-- =============== Sessions — provenance + opaque rotating refresh tokens ===============
CREATE TABLE IF NOT EXISTS identity_runtime.sessions (
    id                        UUID PRIMARY KEY,
    user_id                   UUID NOT NULL REFERENCES identity_runtime.users(id) ON DELETE CASCADE,
    login_identity_id         UUID NOT NULL,
    protocol                  TEXT NOT NULL CHECK (protocol IN ('oidc','development')),
    authentication_source_ref TEXT NOT NULL CHECK (btrim(authentication_source_ref) <> ''),
    authentication_issuer     TEXT NOT NULL CHECK (btrim(authentication_issuer) <> ''),
    asserted_email            TEXT NOT NULL CHECK (btrim(asserted_email) <> ''),
    asserted_email_verified   BOOLEAN NOT NULL,
    email_evidence            TEXT NOT NULL CHECK (
        email_evidence IN ('authoritative','non_authoritative','challenge_verified')
    ),
    upstream_authenticated_at TIMESTAMPTZ,
    authentication_context    TEXT,
    authentication_methods    TEXT[] NOT NULL DEFAULT '{}',
    upstream_session_id       TEXT,
    created_at                TIMESTAMPTZ NOT NULL,
    last_refreshed_at         TIMESTAMPTZ NOT NULL,
    absolute_expires_at       TIMESTAMPTZ NOT NULL,
    revoked_at                TIMESTAMPTZ,
    revocation_reason         TEXT CHECK (revocation_reason IN ('logout','refresh_reuse','user_wide')),
    CONSTRAINT sessions_identity_user_fk
        FOREIGN KEY (login_identity_id, user_id)
        REFERENCES identity_runtime.login_identities(id, user_id) ON DELETE CASCADE,
    CONSTRAINT sessions_expiry_order CHECK (absolute_expires_at > created_at),
    CONSTRAINT sessions_refresh_time_order CHECK (
        last_refreshed_at >= created_at AND last_refreshed_at <= absolute_expires_at
    ),
    CONSTRAINT sessions_revocation_pair CHECK (
        (revoked_at IS NULL AND revocation_reason IS NULL)
        OR (revoked_at IS NOT NULL AND revocation_reason IS NOT NULL)
    )
);
CREATE INDEX IF NOT EXISTS sessions_user_idx ON identity_runtime.sessions(user_id);

-- Only the SHA-256 hash of the raw token is stored. One Session is one refresh family.
CREATE TABLE IF NOT EXISTS identity_runtime.refresh_tokens (
    id         UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES identity_runtime.sessions(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    issued_at  TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT refresh_tokens_expiry_order CHECK (expires_at > issued_at)
);
CREATE INDEX IF NOT EXISTS refresh_tokens_session_idx ON identity_runtime.refresh_tokens(session_id);

CREATE OR REPLACE FUNCTION identity_runtime.enforce_login_identity_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'login identity is immutable (id=%)', OLD.id
        USING ERRCODE = 'check_violation',
              CONSTRAINT = 'login_identities_immutable';
END; $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_login_identity_immutable ON identity_runtime.login_identities;
CREATE TRIGGER trg_login_identity_immutable
    BEFORE UPDATE ON identity_runtime.login_identities
    FOR EACH ROW EXECUTE FUNCTION identity_runtime.enforce_login_identity_immutable();

CREATE OR REPLACE FUNCTION identity_runtime.enforce_session_provenance() RETURNS trigger AS $$
DECLARE
    identity_protocol TEXT;
BEGIN
    IF TG_OP = 'UPDATE' AND (
        OLD.user_id IS DISTINCT FROM NEW.user_id
        OR OLD.login_identity_id IS DISTINCT FROM NEW.login_identity_id
        OR OLD.protocol IS DISTINCT FROM NEW.protocol
        OR OLD.authentication_source_ref IS DISTINCT FROM NEW.authentication_source_ref
        OR OLD.authentication_issuer IS DISTINCT FROM NEW.authentication_issuer
        OR OLD.asserted_email IS DISTINCT FROM NEW.asserted_email
        OR OLD.asserted_email_verified IS DISTINCT FROM NEW.asserted_email_verified
        OR OLD.email_evidence IS DISTINCT FROM NEW.email_evidence
        OR OLD.upstream_authenticated_at IS DISTINCT FROM NEW.upstream_authenticated_at
        OR OLD.authentication_context IS DISTINCT FROM NEW.authentication_context
        OR OLD.authentication_methods IS DISTINCT FROM NEW.authentication_methods
        OR OLD.upstream_session_id IS DISTINCT FROM NEW.upstream_session_id
        OR OLD.created_at IS DISTINCT FROM NEW.created_at
        OR OLD.absolute_expires_at IS DISTINCT FROM NEW.absolute_expires_at
    ) THEN
        RAISE EXCEPTION 'session authentication provenance is immutable (id=%)', OLD.id
            USING ERRCODE = 'check_violation',
                  CONSTRAINT = 'sessions_provenance_immutable';
    END IF;

    SELECT protocol INTO identity_protocol
      FROM identity_runtime.login_identities
     WHERE id = NEW.login_identity_id AND user_id = NEW.user_id;
    IF identity_protocol IS DISTINCT FROM NEW.protocol THEN
        RAISE EXCEPTION 'session protocol does not match login identity (session_id=%)', NEW.id
            USING ERRCODE = 'check_violation',
                  CONSTRAINT = 'sessions_identity_protocol_match';
    END IF;
    RETURN NEW;
END; $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_session_provenance ON identity_runtime.sessions;
CREATE TRIGGER trg_session_provenance
    BEFORE INSERT OR UPDATE ON identity_runtime.sessions
    FOR EACH ROW EXECUTE FUNCTION identity_runtime.enforce_session_provenance();

CREATE OR REPLACE FUNCTION identity_runtime.enforce_refresh_token_family() RETURNS trigger AS $$
DECLARE
    session_absolute_expires_at TIMESTAMPTZ;
BEGIN
    IF TG_OP = 'UPDATE' AND (
        OLD.session_id IS DISTINCT FROM NEW.session_id
        OR OLD.token_hash IS DISTINCT FROM NEW.token_hash
        OR OLD.issued_at IS DISTINCT FROM NEW.issued_at
        OR OLD.expires_at IS DISTINCT FROM NEW.expires_at
    ) THEN
        RAISE EXCEPTION 'refresh token identity and lifetime are immutable (id=%)', OLD.id
            USING ERRCODE = 'check_violation',
                  CONSTRAINT = 'refresh_tokens_immutable';
    END IF;

    SELECT absolute_expires_at INTO session_absolute_expires_at
      FROM identity_runtime.sessions
     WHERE id = NEW.session_id;
    IF NEW.expires_at > session_absolute_expires_at THEN
        RAISE EXCEPTION 'refresh token exceeds Session absolute expiry (id=%)', NEW.id
            USING ERRCODE = 'check_violation',
                  CONSTRAINT = 'refresh_tokens_session_expiry_bound';
    END IF;
    RETURN NEW;
END; $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_refresh_token_family ON identity_runtime.refresh_tokens;
CREATE TRIGGER trg_refresh_token_family
    BEFORE INSERT OR UPDATE ON identity_runtime.refresh_tokens
    FOR EACH ROW EXECUTE FUNCTION identity_runtime.enforce_refresh_token_family();

-- =============== Agent OAuth — opaque public-alpha credentials ===============
CREATE TABLE IF NOT EXISTS identity_runtime.agent_oauth_grants (
    id                UUID PRIMARY KEY,
    owner_user_id     UUID NOT NULL REFERENCES identity_runtime.users(id) ON DELETE CASCADE,
    oauth_client_id   TEXT NOT NULL,
    client_label      TEXT,
    resource_audience TEXT NOT NULL,
    scopes            TEXT[] NOT NULL,
    issued_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at        TIMESTAMPTZ,
    last_used_at      TIMESTAMPTZ
);
ALTER TABLE identity_runtime.agent_oauth_grants
    DROP CONSTRAINT IF EXISTS agent_oauth_grants_scopes_check;
ALTER TABLE identity_runtime.agent_oauth_grants
    ADD CONSTRAINT agent_oauth_grants_scopes_check CHECK (
        cardinality(scopes) > 0
        AND scopes <@ ARRAY[
            'agent.workspace'
        ]::TEXT[]
    );
CREATE INDEX IF NOT EXISTS agent_oauth_grants_owner_user_idx ON identity_runtime.agent_oauth_grants(owner_user_id);
CREATE INDEX IF NOT EXISTS agent_oauth_grants_client_idx ON identity_runtime.agent_oauth_grants(oauth_client_id, issued_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS identity_runtime.agent_oauth_access_tokens (
    id         UUID PRIMARY KEY,
    grant_id   UUID NOT NULL REFERENCES identity_runtime.agent_oauth_grants(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    issued_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS agent_oauth_access_tokens_grant_idx ON identity_runtime.agent_oauth_access_tokens(grant_id);

CREATE TABLE IF NOT EXISTS identity_runtime.agent_oauth_authorization_codes (
    code_hash TEXT PRIMARY KEY,
    grant_id UUID NOT NULL UNIQUE REFERENCES identity_runtime.agent_oauth_grants(id) ON DELETE CASCADE,
    redirect_uri TEXT NOT NULL,
    code_challenge TEXT NOT NULL CHECK (code_challenge ~ '^[A-Za-z0-9_-]{43}$'),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);
CREATE TABLE IF NOT EXISTS identity_runtime.agent_oauth_refresh_tokens (
    id UUID PRIMARY KEY,
    grant_id UUID NOT NULL REFERENCES identity_runtime.agent_oauth_grants(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > issued_at),
    consumed_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS agent_oauth_refresh_tokens_grant_idx ON identity_runtime.agent_oauth_refresh_tokens(grant_id);

-- =============== Authorization ===============
 CREATE OR REPLACE FUNCTION identity_runtime.bump_workspace_authorization_revisions(workspace_ids UUID[]) RETURNS void AS $$
BEGIN
    CREATE TEMP TABLE IF NOT EXISTS pg_temp.monomer_auth_revision_bumps (
        workspace_id UUID PRIMARY KEY
    ) ON COMMIT DELETE ROWS;

    WITH newly_marked AS (
        INSERT INTO pg_temp.monomer_auth_revision_bumps (workspace_id)
        SELECT DISTINCT workspace_id
          FROM unnest(workspace_ids) requested(workspace_id)
         WHERE workspace_id IS NOT NULL
        ON CONFLICT DO NOTHING
        RETURNING workspace_id
    )
    UPDATE identity_runtime.workspaces workspace
       SET authorization_revision = workspace.authorization_revision + 1
      FROM newly_marked
     WHERE workspace.id = newly_marked.workspace_id;
END; $$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION identity_runtime.bump_direct_workspace_authorization_revision() RETURNS trigger AS $$
DECLARE
    affected_workspace_ids UUID[];
BEGIN
    IF TG_OP = 'INSERT' THEN
        affected_workspace_ids := ARRAY[NEW.workspace_id];
    ELSIF TG_OP = 'DELETE' THEN
        affected_workspace_ids := ARRAY[OLD.workspace_id];
    ELSE
        affected_workspace_ids := ARRAY[OLD.workspace_id, NEW.workspace_id];
    END IF;

    PERFORM identity_runtime.bump_workspace_authorization_revisions(affected_workspace_ids);
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END; $$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION identity_runtime.bump_organization_workspace_authorization_revisions() RETURNS trigger AS $$
DECLARE
    affected_organization_id UUID;
    affected_workspace_ids UUID[];
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.role IS NOT DISTINCT FROM NEW.role
    THEN
        RETURN NEW;
    END IF;
    affected_organization_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.organization_id ELSE NEW.organization_id END;
    SELECT COALESCE(array_agg(id ORDER BY id), ARRAY[]::UUID[])
      INTO affected_workspace_ids
      FROM identity_runtime.workspaces
     WHERE organization_id = affected_organization_id;
    PERFORM identity_runtime.bump_workspace_authorization_revisions(affected_workspace_ids);
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END; $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_workspace_members_authorization_revision ON identity_runtime.workspace_members;
CREATE TRIGGER trg_workspace_members_authorization_revision
    AFTER INSERT OR UPDATE OR DELETE ON identity_runtime.workspace_members
    FOR EACH ROW EXECUTE FUNCTION identity_runtime.bump_direct_workspace_authorization_revision();
DROP TRIGGER IF EXISTS trg_organization_members_authorization_revision ON identity_runtime.organization_members;
CREATE TRIGGER trg_organization_members_authorization_revision
    AFTER INSERT OR UPDATE OR DELETE ON identity_runtime.organization_members
    FOR EACH ROW EXECUTE FUNCTION identity_runtime.bump_organization_workspace_authorization_revisions();
-- =============== Audit ===============
-- Append-only by convention. Both organization-level and workspace-scoped governance
-- events live here; Identity is the sole producer.
CREATE TABLE IF NOT EXISTS identity_runtime.audit_log (
    id            UUID PRIMARY KEY,
    workspace_id  UUID,
    organization_id    UUID,
    actor_id      UUID NOT NULL,
    action        TEXT NOT NULL,
    resource_type TEXT,
    resource_id   UUID,
    request_id    TEXT,
    actor_kind    TEXT CONSTRAINT audit_log_actor_kind_check CHECK (actor_kind IN ('USER','AGENT')),
    actor_ref     TEXT,
    details       JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS audit_log_occurred_idx    ON identity_runtime.audit_log (occurred_at DESC);
CREATE INDEX IF NOT EXISTS audit_log_workspace_idx   ON identity_runtime.audit_log (workspace_id);
CREATE INDEX IF NOT EXISTS audit_log_organization_idx     ON identity_runtime.audit_log (organization_id);
CREATE INDEX IF NOT EXISTS audit_log_actor_idx       ON identity_runtime.audit_log (actor_id);

-- ============================================================
-- Trigger 2 — serialized Organization membership invariants
-- ============================================================
CREATE OR REPLACE FUNCTION identity_runtime.enforce_organization_member_invariants() RETURNS trigger AS $$
DECLARE
    target_organization_id UUID;
BEGIN
    target_organization_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.organization_id ELSE NEW.organization_id END;

    PERFORM id
      FROM identity_runtime.organizations
     WHERE id = target_organization_id
     FOR UPDATE;

    IF TG_OP = 'UPDATE'
       AND (OLD.organization_id IS DISTINCT FROM NEW.organization_id OR OLD.user_id IS DISTINCT FROM NEW.user_id)
    THEN
        RAISE EXCEPTION 'organization membership identity is immutable (organization_id=%, user_id=%)',
            OLD.organization_id, OLD.user_id
            USING ERRCODE = 'check_violation',
                  CONSTRAINT = 'organization_members_identity_immutable';
    END IF;

    IF TG_OP IN ('UPDATE', 'DELETE')
       AND OLD.role = 'ADMIN'
       AND (
           TG_OP = 'DELETE'
           OR NEW.role <> 'ADMIN'
       )
       AND NOT EXISTS (
           SELECT 1
             FROM identity_runtime.organization_members other_admin
            WHERE other_admin.organization_id = OLD.organization_id
              AND other_admin.user_id <> OLD.user_id
              AND other_admin.role = 'ADMIN'
       )
    THEN
        RAISE EXCEPTION 'cannot remove or demote last Organization ADMIN (organization_id=%)', OLD.organization_id
            USING ERRCODE = 'restrict_violation',
                  CONSTRAINT = 'organization_members_last_admin';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END; $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_organization_member_invariants ON identity_runtime.organization_members;
CREATE TRIGGER trg_organization_member_invariants
    BEFORE INSERT OR DELETE OR UPDATE ON identity_runtime.organization_members
    FOR EACH ROW EXECUTE FUNCTION identity_runtime.enforce_organization_member_invariants();

CREATE OR REPLACE FUNCTION identity_runtime.enforce_organization_commit_invariants() RETURNS trigger AS $$
DECLARE
    target_organization_id UUID;
    admin_count BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'organization_members' THEN
        target_organization_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.organization_id ELSE NEW.organization_id END;
    ELSE
        target_organization_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.id ELSE NEW.id END;
    END IF;

    PERFORM id
      FROM identity_runtime.organizations
     WHERE id = target_organization_id
     FOR UPDATE;

    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    SELECT count(*) INTO admin_count
      FROM identity_runtime.organization_members
     WHERE organization_id = target_organization_id AND role = 'ADMIN';

    IF admin_count < 1 THEN
        RAISE EXCEPTION 'organization must commit with an ADMIN (organization_id=%)', target_organization_id
            USING ERRCODE = 'check_violation',
                  CONSTRAINT = 'organizations_admin_commit_invariant';
    END IF;

    RETURN NULL;
END; $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_organization_commit_invariants ON identity_runtime.organizations;
CREATE CONSTRAINT TRIGGER trg_organization_commit_invariants
    AFTER INSERT ON identity_runtime.organizations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION identity_runtime.enforce_organization_commit_invariants();

DROP TRIGGER IF EXISTS trg_organization_member_commit_invariants ON identity_runtime.organization_members;
CREATE CONSTRAINT TRIGGER trg_organization_member_commit_invariants
    AFTER INSERT OR DELETE OR UPDATE ON identity_runtime.organization_members
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION identity_runtime.enforce_organization_commit_invariants();

-- ============================================================
-- Trigger 3 — minimum authorization anchors
-- ============================================================
CREATE OR REPLACE FUNCTION identity_runtime.workspace_has_owner(target_workspace_id UUID) RETURNS boolean AS $$
    SELECT EXISTS (
        SELECT 1
          FROM identity_runtime.workspace_members owner_assignment
          JOIN identity_runtime.workspaces workspace ON workspace.id = owner_assignment.workspace_id
          JOIN identity_runtime.organization_members organization_member
            ON organization_member.organization_id = workspace.organization_id
           AND organization_member.user_id = owner_assignment.user_id
         WHERE owner_assignment.workspace_id = target_workspace_id AND owner_assignment.role = 'OWNER'
    );
$$ LANGUAGE sql STABLE;

CREATE OR REPLACE FUNCTION identity_runtime.assert_workspace_authorization_anchors(
    target_workspace_id UUID
) RETURNS void AS $$
BEGIN
    PERFORM 1
      FROM identity_runtime.workspaces
     WHERE id = target_workspace_id
     FOR UPDATE;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    IF NOT identity_runtime.workspace_has_owner(target_workspace_id) THEN
        RAISE EXCEPTION 'workspace must retain an OWNER (workspace_id=%)', target_workspace_id
            USING ERRCODE = 'restrict_violation',
                  CONSTRAINT = 'workspace_last_owner';
    END IF;

END; $$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION identity_runtime.enforce_last_workspace_owner() RETURNS trigger AS $$
BEGIN
    PERFORM 1 FROM identity_runtime.workspaces WHERE id = OLD.workspace_id FOR UPDATE;
    IF FOUND AND OLD.role = 'OWNER' AND (TG_OP = 'DELETE' OR NEW.role <> 'OWNER') AND NOT EXISTS (
        SELECT 1 FROM identity_runtime.workspace_members other_owner
        JOIN identity_runtime.workspaces workspace ON workspace.id = other_owner.workspace_id
        JOIN identity_runtime.organization_members organization_member
          ON organization_member.organization_id = workspace.organization_id AND organization_member.user_id = other_owner.user_id
        WHERE other_owner.workspace_id = OLD.workspace_id AND other_owner.user_id <> OLD.user_id AND other_owner.role = 'OWNER'
    ) THEN
        RAISE EXCEPTION 'workspace must retain a direct personal OWNER'
            USING ERRCODE = 'restrict_violation', CONSTRAINT = 'workspace_last_owner';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END; $$ LANGUAGE plpgsql;
DROP TRIGGER IF EXISTS trg_last_workspace_owner ON identity_runtime.workspace_members;
CREATE TRIGGER trg_last_workspace_owner BEFORE DELETE OR UPDATE OF role ON identity_runtime.workspace_members
    FOR EACH ROW EXECUTE FUNCTION identity_runtime.enforce_last_workspace_owner();

CREATE OR REPLACE FUNCTION identity_runtime.enforce_owner_membership() RETURNS trigger AS $$
DECLARE
    target_workspace_id UUID;
BEGIN
    FOR target_workspace_id IN
        SELECT workspace.id
          FROM identity_runtime.workspaces workspace
         WHERE workspace.organization_id = OLD.organization_id
         ORDER BY workspace.id
    LOOP
        PERFORM 1 FROM identity_runtime.workspaces
         WHERE id = target_workspace_id
         FOR UPDATE;

        IF EXISTS (
            SELECT 1
              FROM identity_runtime.workspace_members owner_assignment
             WHERE owner_assignment.workspace_id = target_workspace_id AND owner_assignment.role = 'OWNER'
               AND owner_assignment.user_id = OLD.user_id
        ) AND NOT EXISTS (
            SELECT 1
              FROM identity_runtime.workspace_members other_owner
              JOIN identity_runtime.organization_members other_member
                ON other_member.organization_id = OLD.organization_id
               AND other_member.user_id = other_owner.user_id
             WHERE other_owner.workspace_id = target_workspace_id AND other_owner.role = 'OWNER'
               AND other_owner.user_id <> OLD.user_id
        ) THEN
            RAISE EXCEPTION 'cannot remove last OWNER user_id=%', OLD.user_id
                USING ERRCODE = 'restrict_violation',
                      CONSTRAINT = 'workspace_last_owner';
        END IF;

    END LOOP;
    RETURN OLD;
END; $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_owner_membership ON identity_runtime.organization_members;
CREATE TRIGGER trg_owner_membership
    BEFORE DELETE ON identity_runtime.organization_members
    FOR EACH ROW EXECUTE FUNCTION identity_runtime.enforce_owner_membership();

CREATE OR REPLACE FUNCTION identity_runtime.enforce_workspace_organization_immutable() RETURNS trigger AS $$
BEGIN
    IF OLD.sample_dataset IS DISTINCT FROM NEW.sample_dataset
       OR OLD.sample_initiator IS DISTINCT FROM NEW.sample_initiator
       OR (OLD.sample_state = 'READY' AND NEW.sample_state IS DISTINCT FROM 'READY') THEN
        RAISE EXCEPTION 'workspace sample identity is immutable' USING ERRCODE = 'check_violation', CONSTRAINT = 'workspace_sample_immutable';
    END IF;
    IF OLD.organization_id IS DISTINCT FROM NEW.organization_id THEN
        RAISE EXCEPTION 'workspace organization is immutable (workspace_id=%)', OLD.id
            USING ERRCODE = 'check_violation',
                  CONSTRAINT = 'workspace_organization_immutable';
    END IF;
    RETURN NEW;
END; $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_workspace_organization_immutable ON identity_runtime.workspaces;
CREATE TRIGGER trg_workspace_organization_immutable
    BEFORE UPDATE OF organization_id, sample_dataset, sample_state, sample_initiator ON identity_runtime.workspaces
    FOR EACH ROW EXECUTE FUNCTION identity_runtime.enforce_workspace_organization_immutable();

CREATE OR REPLACE FUNCTION identity_runtime.enforce_workspace_authorization_anchor_commit() RETURNS trigger AS $$
DECLARE
    anchor_workspace_id UUID;
BEGIN
    IF TG_TABLE_NAME = 'workspaces' THEN
        anchor_workspace_id := NEW.id;
        PERFORM identity_runtime.assert_workspace_authorization_anchors(anchor_workspace_id);
    ELSIF TG_OP = 'DELETE' THEN
        anchor_workspace_id := OLD.workspace_id;
        PERFORM identity_runtime.assert_workspace_authorization_anchors(anchor_workspace_id);
    ELSIF TG_OP = 'UPDATE' THEN
        anchor_workspace_id := OLD.workspace_id;
        PERFORM identity_runtime.assert_workspace_authorization_anchors(anchor_workspace_id);
        IF NEW.workspace_id IS DISTINCT FROM OLD.workspace_id THEN
            anchor_workspace_id := NEW.workspace_id;
            PERFORM identity_runtime.assert_workspace_authorization_anchors(anchor_workspace_id);
        END IF;
    ELSE
        anchor_workspace_id := NEW.workspace_id;
        PERFORM identity_runtime.assert_workspace_authorization_anchors(anchor_workspace_id);
    END IF;
    RETURN NULL;
END; $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_workspace_authorization_anchor_commit ON identity_runtime.workspaces;
CREATE CONSTRAINT TRIGGER trg_workspace_authorization_anchor_commit
    AFTER INSERT ON identity_runtime.workspaces
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION identity_runtime.enforce_workspace_authorization_anchor_commit();

DROP TRIGGER IF EXISTS trg_workspace_owner_anchor_commit ON identity_runtime.workspace_members;
CREATE CONSTRAINT TRIGGER trg_workspace_owner_anchor_commit
    AFTER UPDATE OR DELETE ON identity_runtime.workspace_members
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION identity_runtime.enforce_workspace_authorization_anchor_commit();

CREATE OR REPLACE FUNCTION identity_runtime.enforce_organization_workspace_owner_commit() RETURNS trigger AS $$
DECLARE
    target_workspace_id UUID;
BEGIN
    FOR target_workspace_id IN
        SELECT workspace.id
          FROM identity_runtime.workspaces workspace
         WHERE workspace.organization_id =
               CASE WHEN TG_OP = 'DELETE' THEN OLD.organization_id ELSE NEW.organization_id END
         ORDER BY workspace.id
    LOOP
        PERFORM identity_runtime.assert_workspace_authorization_anchors(target_workspace_id);
    END LOOP;
    RETURN NULL;
END; $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_organization_workspace_owner_commit ON identity_runtime.organization_members;
CREATE CONSTRAINT TRIGGER trg_organization_workspace_owner_commit
    AFTER UPDATE OR DELETE ON identity_runtime.organization_members
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION identity_runtime.enforce_organization_workspace_owner_commit();

-- ============================================================
-- Trigger 5 — every workspace member is a direct user of the workspace organization.
-- ============================================================
CREATE OR REPLACE FUNCTION identity_runtime.enforce_workspace_member_organization_match() RETURNS trigger AS $$
DECLARE
    ws_organization_id  UUID;
BEGIN
    IF TG_OP = 'UPDATE'
       AND (
           OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
           OR OLD.user_id IS DISTINCT FROM NEW.user_id
       )
    THEN
        RAISE EXCEPTION 'workspace membership identity is immutable (workspace_id=%, user_id=%)',
            OLD.workspace_id, OLD.user_id
            USING ERRCODE = 'check_violation',
                  CONSTRAINT = 'workspace_members_identity_immutable';
    END IF;

    SELECT organization_id INTO ws_organization_id FROM identity_runtime.workspaces WHERE id = NEW.workspace_id;
    IF NOT EXISTS (
        SELECT 1 FROM identity_runtime.organization_members
         WHERE organization_id = ws_organization_id
           AND user_id = NEW.user_id

    ) THEN
        RAISE EXCEPTION 'user_id=% is not a member of organization_id=% (workspace_id=%)',
            NEW.user_id, ws_organization_id, NEW.workspace_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NEW;
END; $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_workspace_member_organization_match ON identity_runtime.workspace_members;
CREATE TRIGGER trg_workspace_member_organization_match
    BEFORE INSERT OR UPDATE ON identity_runtime.workspace_members
    FOR EACH ROW EXECUTE FUNCTION identity_runtime.enforce_workspace_member_organization_match();

CREATE TABLE IF NOT EXISTS identity_runtime.pending_authentication_requests (
    request_hash TEXT PRIMARY KEY CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    browser_hash TEXT NOT NULL CHECK (browser_hash ~ '^[0-9a-f]{64}$'),
    kind TEXT NOT NULL CHECK (kind IN ('GOOGLE', 'AGENT', 'OPERATIONS', 'INVITATION')),
    payload JSONB NOT NULL CHECK (octet_length(payload::text) <= 32768),
    user_id TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
    CHECK (user_id IS NULL OR kind IN ('AGENT', 'OPERATIONS'))
);
CREATE INDEX IF NOT EXISTS pending_authentication_browser_kind ON identity_runtime.pending_authentication_requests (browser_hash, kind, created_at DESC, request_hash DESC);
CREATE INDEX IF NOT EXISTS pending_authentication_expiration ON identity_runtime.pending_authentication_requests (expires_at, request_hash);

CREATE TABLE IF NOT EXISTS identity_runtime.operations_authorization_codes (
    code_hash TEXT PRIMARY KEY CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    user_id UUID NOT NULL REFERENCES identity_runtime.users(id) ON DELETE CASCADE,
    client_id TEXT NOT NULL CHECK (client_id = 'monomer-operations-local'),
    redirect_uri TEXT NOT NULL,
    resource TEXT NOT NULL,
    code_challenge TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE IF NOT EXISTS identity_runtime.operations_sessions (
    token_hash TEXT PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    user_id UUID NOT NULL REFERENCES identity_runtime.users(id) ON DELETE CASCADE,
    client_id TEXT NOT NULL CHECK (client_id = 'monomer-operations-local'),
    resource TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE OR REPLACE FUNCTION identity_runtime.compute_schema_catalog_fingerprint() RETURNS TEXT AS $$
    WITH catalog_entries(entry) AS (
        SELECT DISTINCT jsonb_build_array(
            'namespace_object',
            identified_object.type,
            identified_object.schema,
            identified_object.name,
            identified_object.identity
        )::TEXT
          FROM pg_depend dependency
          JOIN pg_namespace namespace
            ON dependency.refclassid = 'pg_catalog.pg_namespace'::regclass
           AND dependency.refobjid = namespace.oid
         CROSS JOIN LATERAL pg_identify_object(
            dependency.classid,
            dependency.objid,
            dependency.objsubid
         ) identified_object
         WHERE namespace.nspname = 'identity_runtime'
           AND dependency.deptype = 'n'
           AND dependency.refobjsubid = 0
           AND dependency.objsubid = 0

        UNION ALL

        SELECT jsonb_build_array(
            'relation',
            relation.relname,
            relation.relkind,
            relation.relpersistence,
            relation.relrowsecurity,
            relation.relforcerowsecurity,
            CASE
                WHEN relation.relkind = 'p' THEN pg_get_partkeydef(relation.oid)
                ELSE NULL
            END
        )::TEXT
          FROM pg_class relation
          JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
         WHERE namespace.nspname = 'identity_runtime'

        UNION ALL

        SELECT jsonb_build_array(
            'column',
            relation.relname,
            row_number() OVER (PARTITION BY relation.oid ORDER BY attribute.attnum),
            attribute.attname,
            format_type(attribute.atttypid, attribute.atttypmod),
            attribute.attnotnull,
            attribute.attidentity,
            attribute.attgenerated,
            attribute.attstorage,
            attribute.attcompression,
            pg_get_expr(attribute_default.adbin, attribute_default.adrelid),
            collation_namespace.nspname,
            catalog_collation.collname
        )::TEXT
          FROM pg_class relation
          JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
          JOIN pg_attribute attribute
            ON attribute.attrelid = relation.oid
           AND attribute.attnum > 0
           AND NOT attribute.attisdropped
          LEFT JOIN pg_attrdef attribute_default
            ON attribute_default.adrelid = relation.oid
           AND attribute_default.adnum = attribute.attnum
          LEFT JOIN pg_collation catalog_collation ON catalog_collation.oid = attribute.attcollation
          LEFT JOIN pg_namespace collation_namespace
            ON collation_namespace.oid = catalog_collation.collnamespace
         WHERE namespace.nspname = 'identity_runtime'

        UNION ALL

        SELECT jsonb_build_array(
            'constraint',
            relation.relname,
            constraint_definition.conname,
            constraint_definition.contype,
            constraint_definition.condeferrable,
            constraint_definition.condeferred,
            constraint_definition.convalidated,
            constraint_definition.connoinherit,
            pg_get_constraintdef(constraint_definition.oid, FALSE)
        )::TEXT
          FROM pg_constraint constraint_definition
          JOIN pg_namespace namespace ON namespace.oid = constraint_definition.connamespace
          LEFT JOIN pg_class relation ON relation.oid = constraint_definition.conrelid
         WHERE namespace.nspname = 'identity_runtime'

        UNION ALL

        SELECT jsonb_build_array(
            'index',
            table_relation.relname,
            index_relation.relname,
            index_definition.indisunique,
            index_definition.indisprimary,
            index_definition.indisexclusion,
            index_definition.indimmediate,
            index_definition.indisclustered,
            index_definition.indisvalid,
            index_definition.indcheckxmin,
            index_definition.indisready,
            index_definition.indislive,
            index_definition.indisreplident,
            pg_get_indexdef(index_relation.oid)
        )::TEXT
          FROM pg_index index_definition
          JOIN pg_class index_relation ON index_relation.oid = index_definition.indexrelid
          JOIN pg_class table_relation ON table_relation.oid = index_definition.indrelid
          JOIN pg_namespace namespace ON namespace.oid = table_relation.relnamespace
         WHERE namespace.nspname = 'identity_runtime'

        UNION ALL

        SELECT jsonb_build_array(
            'trigger',
            relation.relname,
            trigger_definition.tgname,
            trigger_definition.tgenabled,
            pg_get_triggerdef(trigger_definition.oid, FALSE)
        )::TEXT
          FROM pg_trigger trigger_definition
          JOIN pg_class relation ON relation.oid = trigger_definition.tgrelid
          JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
         WHERE namespace.nspname = 'identity_runtime'
           AND NOT trigger_definition.tgisinternal

        UNION ALL

        SELECT jsonb_build_array(
            'function',
            routine.proname,
            pg_get_function_identity_arguments(routine.oid),
            pg_get_function_result(routine.oid),
            routine.prokind,
            routine_language.lanname,
            routine.provolatile,
            routine.proisstrict,
            routine.prosecdef,
            routine.proleakproof,
            routine.proparallel,
            routine.proconfig,
            pg_get_functiondef(routine.oid)
        )::TEXT
          FROM pg_proc routine
          JOIN pg_namespace namespace ON namespace.oid = routine.pronamespace
          JOIN pg_language routine_language ON routine_language.oid = routine.prolang
         WHERE namespace.nspname = 'identity_runtime'

        UNION ALL

        SELECT jsonb_build_array(
            'view',
            relation.relname,
            pg_get_viewdef(relation.oid, FALSE)
        )::TEXT
          FROM pg_class relation
          JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
         WHERE namespace.nspname = 'identity_runtime'
           AND relation.relkind IN ('v', 'm')

        UNION ALL

        SELECT jsonb_build_array(
            'rule',
            relation.relname,
            rewrite_rule.rulename,
            rewrite_rule.ev_enabled,
            pg_get_ruledef(rewrite_rule.oid, FALSE)
        )::TEXT
          FROM pg_rewrite rewrite_rule
          JOIN pg_class relation ON relation.oid = rewrite_rule.ev_class
          JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
         WHERE namespace.nspname = 'identity_runtime'
           AND rewrite_rule.rulename <> '_RETURN'

        UNION ALL

        SELECT jsonb_build_array(
            'policy',
            relation.relname,
            row_policy.polname,
            row_policy.polpermissive,
            row_policy.polcmd,
            ARRAY(
                SELECT role.rolname
                  FROM unnest(row_policy.polroles) policy_role_oid
                  JOIN pg_roles role ON role.oid = policy_role_oid
                 ORDER BY role.rolname
            ),
            pg_get_expr(row_policy.polqual, row_policy.polrelid),
            pg_get_expr(row_policy.polwithcheck, row_policy.polrelid)
        )::TEXT
          FROM pg_policy row_policy
          JOIN pg_class relation ON relation.oid = row_policy.polrelid
          JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
         WHERE namespace.nspname = 'identity_runtime'

        UNION ALL

        SELECT jsonb_build_array(
            'type',
            t.typname,
            t.typtype,
            t.typcategory,
            t.typnotnull,
            CASE WHEN t.typbasetype = 0 THEN NULL ELSE format_type(t.typbasetype, NULL) END,
            CASE WHEN t.typelem = 0 THEN NULL ELSE format_type(t.typelem, NULL) END,
            t.typlen,
            t.typbyval,
            t.typalign,
            t.typstorage
        )::TEXT
          FROM pg_type t
          JOIN pg_namespace namespace ON namespace.oid = t.typnamespace
         WHERE namespace.nspname = 'identity_runtime'
           AND t.typrelid = 0
    )
    SELECT md5(COALESCE(string_agg(entry, E'\n' ORDER BY entry COLLATE "C"), ''))
      FROM catalog_entries;
$$ LANGUAGE sql STABLE
SET search_path = pg_catalog, public;

CREATE TABLE IF NOT EXISTS identity_runtime.organization_membership_invitations (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES identity_runtime.organizations(id) ON DELETE CASCADE,
    delivery_email TEXT NOT NULL CHECK (length(delivery_email) BETWEEN 3 AND 254),
    normalized_email TEXT NOT NULL CHECK (normalized_email = lower(btrim(delivery_email))),
    token_hash TEXT NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    invited_by_user_id UUID REFERENCES identity_runtime.users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
    last_delivery_attempt_at TIMESTAMPTZ,
    last_sent_at TIMESTAMPTZ,
    UNIQUE (organization_id, normalized_email)
);

-- Workspace access an Organization Admin attached to an invitation. Accepting creates these
-- Workspace memberships; a Workspace that is no longer in the Organization is skipped then.
CREATE TABLE IF NOT EXISTS identity_runtime.organization_membership_invitation_workspaces (
    invitation_id UUID NOT NULL REFERENCES identity_runtime.organization_membership_invitations(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('VIEWER','EXPLORER','EDITOR','OWNER')),
    PRIMARY KEY (invitation_id, workspace_id)
);

CREATE TABLE IF NOT EXISTS identity_runtime.schema_contract (
    singleton                 BOOLEAN PRIMARY KEY CHECK (singleton),
    contract_revision         TEXT NOT NULL,
    catalog_fingerprint       TEXT NOT NULL,
    fingerprint_function_hash TEXT NOT NULL,
    installed_at              TIMESTAMPTZ NOT NULL
);

DO $$
DECLARE
    fingerprint_function_oid OID;
    current_catalog_fingerprint TEXT;
    current_fingerprint_function_hash TEXT;
BEGIN
    fingerprint_function_oid := to_regprocedure('identity_runtime.compute_schema_catalog_fingerprint()');
    current_fingerprint_function_hash := md5(pg_get_functiondef(fingerprint_function_oid));
    current_catalog_fingerprint := identity_runtime.compute_schema_catalog_fingerprint();

    INSERT INTO identity_runtime.schema_contract (
        singleton,
        contract_revision,
        catalog_fingerprint,
        fingerprint_function_hash,
        installed_at
    ) VALUES (
        TRUE,
        '2026-10-13.sample-dataset-v3',
        current_catalog_fingerprint,
        current_fingerprint_function_hash,
        now()
    )
    ON CONFLICT (singleton) DO UPDATE
        SET contract_revision = EXCLUDED.contract_revision,
            catalog_fingerprint = EXCLUDED.catalog_fingerprint,
            fingerprint_function_hash = EXCLUDED.fingerprint_function_hash;
END; $$ LANGUAGE plpgsql;
