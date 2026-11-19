# Architecture Overview

Monomer manages semantic definitions, access control, and reproducible analytical execution as durable product authority rather than duplicating external warehouse data.

1. **Connectors** are created and updated atomically through workspace-scoped HTTP endpoints, maintaining a stable connector identity whose warehouse type is immutable after creation.
2. **Catalog & Semantics** own dedicated PostgreSQL schemas inside `control-service` for catalog ingestion/selection and current Semantic Model and Metric definitions. Catalog ingestion and Explore maintain their own durable job/run states.
3. **External Execution** (`execution-service`) is a DB-less physical executor invoked by `control-service` over internal gRPC (`Run`). See [`execution-service/README.md`](../../execution-service/README.md) and [`protos/execution_plane.proto`](../../protos/execution_plane.proto).
4. **Explore & Dashboards** execute raw SQL against cataloged connectors through the `RAW_SQL` owner route, while Semantic Explore and Dashboards use SQL compiled by the Semantic module. Control enforces current workspace authorization on both execution admission and result retrieval.
5. **Pages** maintain immutable document-version content and workspace directory navigation; Dashboard pages reference dashboards within the same workspace.

Public callers interact through typed owner routes. The Agent Workspace facade exposes six uniform request-response operations (`list`, `search`, `read`, `create`, `edit`, `invoke`) over the same domain use cases, backed by [`contracts/control-service.openapi.json`](../../contracts/control-service.openapi.json). Durable truth always resides in Control owner state and execution ledgers.

For deeper specifications, see:
- [Product Architecture](product-architecture.md) — deployment topology and bounded-module ownership
- [Product Structure](product-structure.md) — Organization/Workspace resource hierarchy
- [Async Job Design](async-job-design.md) — durable run lifecycles and artifact settlement
- [Authorization](authorization.md) — workspace roles, public page readers, and SQL safety boundaries
