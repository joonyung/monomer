# Monomer Product Architecture

## One Product Authority, One External Execution Boundary

All durable product state and business orchestration live inside a single `control-service`. All code that touches external SQL warehouses, databases, or warehouse APIs lives inside a DB-less `execution-service`.

```text
Web / Agent / Operator
          |
          v
  Product Control (control-service)
  identity · resource-authority · connector · catalog · semantic
  execution-runtime · explore · dashboard · pages · search · agent-workspace
          |
          | protos/execution_plane.proto (Run)
          | Control is gRPC client · Execution is gRPC server
          v
  External Execution (execution-service)
  probe / query / catalog scan · bounded Arrow frames · multipart S3 upload
          |
          v
  External SQL Warehouses & Databases
```

The two backend services are independent Gradle builds and independent deployables. There is no shared Kotlin module, no composite build, no shared internal database, and no duplicated `.proto` file—both builds generate bindings directly from the single root [`protos/execution_plane.proto`](../../protos/execution_plane.proto).

---

## Product Control as a Modular Monolith

A modular monolith enforces bounded-context domain vocabularies, hexagonal purity, PostgreSQL schema ownership, and explicit public APIs within a single deployment and transactional boundary.

For JDBC transaction routing, isolation, and cancellation rules, see [Control JDBC Transactions](jdbc-transactions.md).

`control-service` comprises 11 bounded domain modules declared in [`contracts/architecture/module-ownership.json`](../../contracts/architecture/module-ownership.json):

1. `identity-access`
2. `resource-authority`
3. `execution-runtime`
4. `connector`
5. `catalog`
6. `semantic`
7. `explore`
8. `dashboard`
9. `pages`
10. `agent-workspace`
11. `search`

Only `:application` acts as the composition root. Modules never read another module's persistence layer or internal packages; cross-module interactions go exclusively through declared `.api` contracts and events, enforced by Gradle dependency constraints, build topology checks, and source-purity tests.

### Key Module Contracts

- **Dashboard** owns dashboard current definitions, workspace-shared drafts, immutable viewer sessions, and dashboard-owned `QueryRun` state. Direct authoring and execution require the workspace `Explorer` role or higher; `Viewer` access operates exclusively through the linked Pages reader path.
- **Pages** owns Markdown, Notebook, and HTML source content alongside immutable `PageVersion` records. No auxiliary conversion pipeline is required—authorized source is rendered safely in the browser.
- **Lineage & Authorization**: Composition reads current Semantic Model/Metric definitions and Catalog relations within a single `REPEATABLE READ` snapshot to derive lineage topology. `resource-authority` owns topology value objects and structural traversal without persisting materialized edge tables. `identity-access` authorizes workspace access from direct membership and cumulative workspace roles (`Viewer < Explorer < Editor < Owner`). See [Authorization](authorization.md).
- **Raw SQL vs. Semantic Execution**: Raw SQL Explore issues `ValidateRawSql` followed by execution against Execution. Validation checks single-statement `SELECT` safety without maintaining per-relation ACL graphs; actual data access boundaries are enforced by the workspace boundary and the connector's warehouse credentials. Semantic queries validate current definitions and compile warehouse-specific SQL via `metricflow-kotlin`.

---

## External Execution as a Stateless Physical Executor

`execution-service` is responsible strictly for:

- Accepting a `Run` assignment dialed by Control and connecting to the target warehouse
- Physically executing the SQL statement provided by Control
- Returning bounded self-contained Apache Arrow batches over the gRPC stream (`INLINE_STREAM`) or uploading an immutable Arrow result artifact to S3 and returning a manifest (`ARTIFACT_UPLOAD`)
- Using deployment-injected, prefix-scoped object-storage credentials strictly to multipart-upload artifacts under the canonical `artifacts/` prefix (Control retains ownership of visibility, reads, and deterministic cleanup)
- Propagating RPC cancellation directly to the running warehouse statement

Execution does not own connector persistence, user permissions, scheduling, business workflows, durable run ledgers, result activation, DDL, or any internal relational database.

**Embedded Sample Dataset Exception:** To provide an out-of-the-box interactive sample workspace, the Execution runtime includes a read-only synthetic dataset accessed through an isolated DuckDB driver. It stores no customer data and performs no writes; Control's composition root orchestrates sample workspace provisioning solely through module `.api` ports.

PostgreSQL JDBC is permitted strictly inside the external PostgreSQL warehouse driver module. Execution exposes an internal gRPC server dialed exclusively by Control inside the private VPC—never on a public edge. If Execution is temporarily unreachable, Control's persistence and non-execution surfaces remain available.

---

## Connectors and Current Definitions

A **Connector** has a stable workspace-scoped ID, an immutable warehouse type, mutable non-secret properties, and a current credential-version reference. Changing warehouse type requires creating a new connector. When an owner admits a physical run, Control pins the connector ID, type, non-secret properties, and immutable credential-version reference into the dispatch assignment. At dial time, Control materializes that exact credential version into an in-memory `MCR1` document; Execution never writes plaintext credentials to disk or logs.

**Semantic Models and Metrics** maintain a single current definition per stable ID with no pinned versions, publications, or catalog snapshots. At query planning time, Control resolves referenced models/metrics by name and binds relation addresses against the connector's current Catalog in memory. SQL expressions on entities, dimensions, measures, and derived metrics are preserved as opaque strings validated for definition-local well-formedness.

---

## Owner Jobs and `ExecutionRun`

User-visible asynchronous state lives directly in each owning domain module (`ExploreRun`, `CatalogIngestionJob`, and dashboard `QueryRun`) with no global shared Operation index.

`ExecutionRun` is Control's durable physical execution ledger recording typed owner reference, stable connector ID, self-contained dispatch assignment, progress, result target, and terminal outcome:

```text
RUNNING -> SUCCEEDED | FAILED | CANCELLED
```

When Execution sends a terminal outcome on the gRPC stream, the owner transaction settles the result: `ARTIFACT_UPLOAD` verifies object metadata via `HEAD` before activating visibility, while `INLINE_STREAM` catalog queries parse returned Arrow frames directly into current catalog rows. If the stream disconnects without a terminal outcome, Control marks the run `FAILED / EXECUTION_ORPHANED` and schedules deterministic cleanup of any artifact key. Execution never retries autonomously.

---

## Product Surfaces & Deployment Topology

- **Web App (`web-app`)**: Static browser SPA communicating with Control via `VITE_MONOMER_CONTROL_API_URL` (GraphQL, REST, and SSE).
- **Agent Workspace Facade**: Six uniform request-response operations (`list`, `search`, `read`, `create`, `edit`, `invoke`) exposed over REST and MCP, enforcing both `agent.workspace` OAuth scope and current workspace roles.
- **Headless Clients**: Consume the same typed Control HTTP contract.

Execution exposes no public product API.

| Deployable | Responsibility |
|---|---|
| `control-service` | Single deployable owning PostgreSQL schemas and object-storage authority |
| `execution-service` | Independently deployable, stateless gRPC worker with scoped artifact upload permissions |
| `web-app` | Static SPA served via CloudFront and private S3 |

Although Control module schemas coexist inside one PostgreSQL database, cross-module table reads/writes are strictly forbidden—cross-owner links flow only through typed `.api` interfaces, domain events, and explicit projections.
