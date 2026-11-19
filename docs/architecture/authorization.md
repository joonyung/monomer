# Authorization Architecture

> **Core Principle:** Workspaces define the data boundary; cumulative roles define what a principal may do. Direct workspace membership grants entry, and Web and Agent surfaces enforce the exact same boundary.

---

## 1. Four Cumulative Workspace Roles

Workspace roles are strictly cumulative (`Viewer < Explorer < Editor < Owner`). Every internal workspace entry requires active organization membership and direct workspace membership; the principal's direct member role is their effective role.

| Role | Permitted Capabilities |
|---|---|
| **Viewer** | Browse Pages, view past and current session results for linked Dashboards, and adjust variables on existing linked sessions |
| **Explorer** | All internal workspace reads, raw SQL and Semantic Explore queries, Dashboard and Page authoring/execution, Catalog refresh/cancel/candidate refresh |
| **Editor** | Author and update Semantic Models, Metrics, Time Spines, and Catalog table selection |
| **Owner** | Manage Connectors and credentials, workspace members and roles, and workspace settings (including Public Pages) |

Member creation requires an explicit role (defaulting to `Explorer` in the UI). Removing a membership removes its role immediately; re-adding a user starts with the newly assigned role and never restores prior privileges.

---

## 2. Direct Individual Owner Invariant

Every workspace must retain at least one direct individual `Owner`. Demoting or removing the last direct workspace Owner—or removing that user from the parent Organization—is rejected atomically inside the same transaction.

Organization Admins cannot bypass this invariant. An Organization Admin may assign any role (`Viewer`, `Explorer`, `Editor`, or `Owner`) to members of the same organization (creating direct workspace membership when needed), remove direct members across workspaces in that organization, and rename workspaces. Enabling or disabling `Public pages` remains strictly Owner-only, and Organization Admin status alone does not grant implicit internal data access to a workspace.

---

## 3. Page Reader and Public Pages Boundary

`Viewer` principals interact exclusively with the Pages surface. On every request, Control verifies the full ownership chain in order:

1. Requested page belongs to the current workspace page tree
2. Linked dashboard belongs to the same workspace and is currently attached to that page
3. Session belongs to that dashboard
4. `QueryRun` belongs to that session

Document pages expose only the latest published view to readers—historical document versions, raw attachments, source downloads, new dashboard session creation, manual refreshes, cancellations, SSE streams, internal semantic definitions, catalog metadata, lineage, connectors, workspace overview, and search are all closed to readers.

Browser access to a page is admitted via direct workspace membership or when the workspace has `Public pages` enabled. Authenticated non-members resolving variables on a public page have executions recorded under their real principal and credential; only anonymous public visitors execute without an actor identity. AI agents always require direct workspace membership. Public entry never grants permission to open new sessions.

---

## 4. Separating SQL Safety from Result Read Authorization

- **Dashboard Named Queries (`SEMANTIC | SQL`):** Authoring and internal execution require `Explorer` or higher. Page readers may only resolve variables against an existing linked session; unauthorized entry maps to `NotFound` (`DASHBOARD_QUERY_UNAUTHORIZED`).
- **Typed Template Compilation:** SQL `{{alias}}` placeholders are compiled once at definition decode time into positional `?` bindings. Runtime inputs are transmitted strictly as ordered typed bind parameters and never string-interpolated into SQL. Both native pre-execution validation and physical execution run on the exact same compiled SQL under a single deadline and cancellation scope.
- **Warehouse Privilege Boundary:** Raw SQL and dashboard SQL execute with the permissions of the configured connector account, which may reach tables outside Catalog selection. Workspaces should configure dedicated read-only warehouse credentials; native SQL safety validation is a guard against accidental mutations, not a replacement for least-privilege database roles.
- **Safe Reader Error Surface:** Page display DTOs expose only the query kind (`SEMANTIC` vs. `SQL`), omitting raw SQL text, connector details, and raw validation diagnostics. Readers see a normalized `QUERY_FAILED` state, while `Explorer` and above receive safe engine diagnostics. Trust badges and result reads always rely on the query kind recorded at execution time rather than mutable current definitions.
- **Raw SQL Explore:** Requires `Explorer` or higher. Validation enforces single-statement `SELECT`, prohibits locking clauses and `SELECT INTO`, and performs BigQuery dry-run statement checks where applicable.
- **Preserved Results:** Prior Explore and Dashboard results are not invalidated when current semantic definitions or lineage change, but every read re-verifies current workspace role, workspace/session/run ownership, artifact integrity, and retention window. Personal Explore history and cancellation are scoped to the invoking principal, and presigned download URLs expire within at most 5 minutes.

---

## 5. Ownership Boundaries & Admission Order

- **`identity-access`** verifies credentials, agent scopes, active organization membership, direct workspace membership, and effective workspace roles.
- **`:application`** binds per-action minimum role requirements and cross-owner resource ownership checks.
- **`resource-authority`** supplies current lineage and impact-analysis topology only.
- **`explore`, `dashboard`, and `execution-runtime`** own durable execution state and result lifecycle boundaries.

Identity admission evaluates and rejects strictly in the following precedence order:

1. Valid credential (invalid credentials always fail first)
2. Agent `agent.workspace` OAuth scope (missing scope returns `AGENT_SCOPE_INSUFFICIENT` before membership or existence checks)
3. Active organization membership and direct workspace membership
4. Minimum required workspace role for the operation
