# Product Structure Map

This document provides a conceptual map of Monomer's resource hierarchy and cross-surface contracts. For canonical field definitions, HTTP paths, and module boundaries, consult in order:

1. [Core Product Principles](../product-principles.md)
2. [Product Architecture](product-architecture.md) and [`contracts/architecture/module-ownership.json`](../../contracts/architecture/module-ownership.json)
3. [Control OpenAPI Contract](../../contracts/control-service.openapi.json) and owning module source code

---

## Product Resource Hierarchy

```text
Organization
├── User Principal · Direct Membership · Role
└── Workspace
    ├── Connector
    │   └── Catalog Projection: Database · Schema · Table/View · Column
    ├── Semantic Model (Current Definition)
    ├── Metric (Current Definition → Model/Metric Name Reference)
    ├── Explore Run (Raw SQL / Semantic)
    ├── Pages Directory
    │   └── Page: Folder | Document | Dashboard Reference
    │       └── Immutable PageVersion (Document Only)
    └── Dashboard (Current Definition · Workspace-Shared Draft)
        └── Viewer Session · Dashboard-Owned QueryRun · Immutable Result Reference
```

- Organization and workspace admission are owned by `identity-access`.
- `connector`, `catalog`, `semantic`, `explore`, `dashboard`, and `pages` are independent bounded modules inside `control-service`, each owning its own aggregates and PostgreSQL schema.
- Application composition reads current Catalog and Semantic state within a single `REPEATABLE READ` snapshot to build lineage graphs. `resource-authority` owns graph value objects and pure validation/traversal without persisting edge tables.
- Long-running execution state belongs to each owning run aggregate rather than a shared global Operation index.
- External Execution (`execution-service`) is a DB-less physical executor and owns none of the product resources above.

---

## General B2B Model and Single-User Workspaces

Monomer models the multi-workspace, multi-principal B2B organization as the universal primitive. A single-user workspace is simply a one-member organization and workspace—never a separate entity type. Connectors, semantic models, metrics, pages, and dashboards belong to a workspace, and cross-workspace reuse is governed by explicit sharing decisions rather than implicit inheritance. See [Core Product Principles](../product-principles.md).

---

## Current Semantic Definitions and Execution

Each Semantic Model and Metric maintains a single current definition under a stable ID. Metrics reference models and other metrics by name without pinning versions, publications, digests, or catalog snapshots. At query time, Control gathers the transitive set of current definitions needed for the requested metrics and resolves model relation addresses against the selected connector's current catalog—without duplicating connector properties or credentials.

---

## Dashboards and Document Pages

Dashboards are workspace-scoped operational BI capabilities owned by Control. Each dashboard maintains one current definition and one workspace-shared draft; humans and agents collaborate on the draft using optimistic authoring-revision compare-and-swap (CAS), and only `Apply` promotes a draft to current. A Viewer Session snapshots the current definition and immutable parameters at open time. Dashboard-owned `QueryRun` records preserve both draft and viewer executions durably. Control re-checks workspace roles and resource ownership at admission, pre-execution, and result-read boundaries: Explorers and above access internal `QueryRun` results, while Viewers read only session results linked to a published Page.

Document Pages store immutable `PageVersion` snapshots of narrative deliverables (Markdown, Notebook, HTML) without conflating free-form documents with the Dashboard DSL. A Dashboard reference page holds only a workspace-local dashboard ID plus independent navigation title and placement.

---

## Lineage and Workspace Roles

```text
Relation
  ← Semantic Model
  ← Simple Metric
  ← Composed Metric (Direct Metric Parents)
```

Application composition reads current resource identities and direct references from Catalog and Semantic owner APIs to assemble the lineage graph. `resource-authority` owns topology structures and validation, while `identity-access` evaluates direct membership and effective workspace roles. Role capabilities and ownership checks are specified in [Authorization](authorization.md).

The Lineage GraphQL surface provides focused dependency cones to Explorers and above for impact analysis and relationship discovery; it does not edit permissions or compute per-resource ACL decisions.

---

## Cross-Surface Rules

The Web UI and Agent Workspace Facade invoke the same Control domain use cases and public product model. The Agent REST/MCP surface exposes six request-response operations (`list`, `search`, `read`, `create`, `edit`, `invoke`) with server-issued request IDs without replacing underlying owner aggregates or durable runs.

---

## Authority Routing

| Domain / Responsibility | Canonical Source |
|---|---|
| Organization, Workspace, Principal, RBAC | `control-service/modules/identity-access`, [Authorization](authorization.md) |
| Resource Ownership & Lineage Topology | `control-service/modules/resource-authority` |
| Connector State & Credential Versions | `control-service/modules/connector` |
| Catalog Selection & Ingestion | `control-service/modules/catalog` |
| Semantic Models & Metrics | `control-service/modules/semantic` |
| Ad-hoc Explore Runs & Results | `control-service/modules/explore` |
| Dashboards, Drafts, Sessions, QueryRuns | `control-service/modules/dashboard` |
| Pages & Immutable Document Versions | `control-service/modules/pages` |
| Agent Facade & Resource Routing | `control-service/modules/agent-workspace` |
| Physical `ExecutionRun` Ledger & Dialing | `control-service/modules/execution-runtime` |
| Control ↔ Execution gRPC Wire Contract | [`protos/execution_plane.proto`](../../protos/execution_plane.proto) |
| Module Ownership Graph | [`contracts/architecture/module-ownership.json`](../../contracts/architecture/module-ownership.json) |
| Control HTTP & Agent OpenAPI Surface | [`contracts/control-service.openapi.json`](../../contracts/control-service.openapi.json) |
