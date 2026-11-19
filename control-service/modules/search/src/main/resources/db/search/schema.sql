CREATE SCHEMA IF NOT EXISTS search;

CREATE TABLE IF NOT EXISTS search.documents (
    workspace_id uuid NOT NULL,
    resource_path text COLLATE "C" NOT NULL,
    resource_kind text NOT NULL CHECK (resource_kind IN ('CONNECTOR', 'CATALOG', 'SCHEMA', 'RELATION', 'SEMANTIC_MODEL', 'METRIC', 'DASHBOARD')),
    primary_names text[] NOT NULL,
    search_vector tsvector NOT NULL,
    PRIMARY KEY (workspace_id, resource_path)
);

CREATE INDEX IF NOT EXISTS search_documents_vector_idx ON search.documents USING gin(search_vector);
CREATE INDEX IF NOT EXISTS search_documents_names_idx ON search.documents USING gin(primary_names);
