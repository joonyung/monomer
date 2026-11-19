# Workspace Request Scoping and Resource Authority

## The Request Path Owns Workspace Scope

Workspace context for product APIs is owned exclusively by the URL path `/v2/workspaces/{workspaceId}/...`. Client headers, connector payloads, and arbitrary JWT workspace claims are never trusted as workspace scope authority.

Control ingress resolves the authenticated principal and queries `identity-access` for workspace admission and the operation's minimum required workspace role. Whenever a request references a target resource ID, the owning module resolves the resource's actual workspace and asserts exact equality against the path `workspaceId`.

---

## Current-Definition Semantic Resolution

REST and Agent routes for Semantic Models and Metrics first pass workspace admission and minimum role checks on `/v2/workspaces/{workspaceId}/...`, then verify exact workspace ownership for any target resource ID.

- Semantic Models record `sourceRelations[]` with `connectorId` plus `catalog?`, `schema`, and `relation` names.
- Metrics reference current Semantic Models and parent Metrics by name.
- Definitions never persist catalog object IDs, catalog snapshots, publication hashes, versions, or digests.

When preparing a semantic query, Control resolves relation addresses against the selected connector's current Catalog and traverses transitive name references across current models and metrics within the request scope. Missing relations or broken references fail that request explicitly. Subsequent requests re-read current definitions and catalog state rather than reusing frozen publication closures.

Saving a definition performs definition-local schema validation only; cross-resource reference resolution belongs strictly to the query-planning boundary.

---

## Stateless Resource Authority Lineage Topology

Control composition reads current resources from Catalog and Semantic owner APIs to return lineage topology. `resource-authority` owns stable `ResourceRef` types, graph value objects, structural validation, and traversal—storing neither edge tables nor authorization state. Lineage topology serves relationship discovery and impact analysis rather than access control.

`identity-access` enforces direct workspace membership and cumulative roles (`Viewer < Explorer < Editor < Owner`). Targets that do not exist or belong to another workspace are rejected.

### Module Ownership Summary

- **`identity-access`**: Organization, User, LoginIdentity, workspace admission, and direct workspace roles
- **`resource-authority`**: Stable `ResourceRef`, in-request graph validation, and lineage traversal
- **Owning resource modules**: Resolve true workspace ownership from target IDs
- **`execution-runtime`**: `ExecutionRun` durable ledger and Execution gRPC dialing

Even inside one Control process, modules never join directly across another module's PostgreSQL tables; lineage is assembled strictly from owner API outputs.

---

## Agent and Web Surface Parity

The Agent Workspace Facade enforces the identical path-owned workspace scoping, owner lookup, and current admission checks as the Web UI. Agent OAuth scopes never substitute for direct workspace membership or workspace roles.

Agent OAuth grants belong to `(user, client, audience)` and do not pin an organization. Workspace operations specify `workspaceId` via REST path or MCP parameter without duplicating `organizationId`; Identity resolves the owning organization and validates active organization membership plus direct workspace membership. Organization-level operations address `/v2/agent/organizations/{organizationId}/{operation}` (or MCP `organizationId`) explicitly rather than relying on server-side or browser-selected organization state. Discovery uses MCP `monomer_list` or REST `POST /v2/agent/list` with `organizations` or `workspaces` paths.

---

## Asynchronous Execution Authorization

Before dialing `execution-service` for any warehouse-touching run, Control re-verifies current Identity admission and the operation's minimum workspace role. Execution `Run` streams open only after that check succeeds, and user bearer tokens are never persisted on run rows.

If permissions change while a warehouse query is in flight, the running attempt never expands beyond its original request scope, and every subsequent result read re-checks current workspace role, resource ownership, and artifact integrity. Explore and direct Dashboard reads require `Explorer` or higher; `Viewer` principals read only results linked to a published Page session. See [Authorization](authorization.md).
