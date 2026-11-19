# Core Product Principles

These design principles govern the entire Monomer platform and sit above individual module specifications. Every domain model, API surface, and execution flow aligns to these invariants.

---

## 1. Design for B2B First; Reuse the Same Model for Single Users

**Design order is B2B first.** Model the general multi-tenant, multi-member, governed case first, and let single-user usage fall out naturally as the degenerate case (one member in one workspace inside one organization). Never maintain a parallel B2C domain model—use the exact same entities and code paths.

**Do not confuse design order with go-to-market order.** Onboarding can still be zero-friction and product-led. What changes is the *order of modeling*: derive single-user flows from the general model rather than bolting multi-tenancy onto a single-user model later.

**Rationale.** Designing for single users first looks simple initially, but breaks as soon as team collaboration arrives—causing schema fragmentation, permission conflicts, and migration debt. When the general case is modeled cleanly from day one, moving from solo to team use means *adding members*, not *changing entity types*.

**Heuristic.** Reaching for a branch such as `isPersonalUser()` is a sign the order is inverted. Behavior should derive from current membership, role, and member count.

**Resource boundaries follow the same rule.** Connectors, catalogs, semantic models, metrics, pages, and dashboards belong to a workspace. Cross-workspace reuse is handled through explicit sharing rather than implicit organization-wide inheritance. Sharing *logic* (metric definitions) is distinct from sharing *data*—a consuming workspace binds shared definitions to its own connector so data never silently crosses boundaries.

---

## 2. Show Result Snapshots First and Surface Live Execution Reactively

Dashboards are an active bounded module owned by Control. Each dashboard has one current definition and one workspace-shared draft; the builder and immutable viewer sessions share dashboard-owned `QueryRun` records across distinct aggregate boundaries. Explorers and above may hard-delete a dashboard, cleaning up linked dashboard pages, drafts, sessions, query runs, and schedules within the owner transaction while cancelling in-flight runs and preserving definition audit history.

**Execution is shared within a session while exploration stays independent per browser view.** A `DashboardSession` snapshots the dashboard definition and complete immutable `ParameterBindings`, starting initial query runs. Each viewer tab initializes from the snapshot's variable defaults and manages its own local complete variables, target run IDs, and ready widget bundles in the browser. Filtering, refreshing, or leaving one tab never mutates another viewer's selection or displayed state. Session rows do not store shared mutable current variables or shared target pointers.

**Opening a dashboard selects the latest session and resolves initial inputs.** If a URL specifies a session ID, that snapshot is restored. Otherwise, internal workspace entry reads the current definition and most recent session (`createdAt DESC, sessionId DESC`) and opens a fresh session with the same immutable `ParameterBindings` if an apply has advanced the current definition. Read endpoints (`GET`, polling, SSE) never trigger background execution as a side effect. Changing immutable parameters opens a new session against the current definition, while page readers can only resolve variables within an existing session.

**Reuse equivalent succeeded or running queries within the same session.** Every physical execution receives a UUIDv7 `QueryRun` identity. Each resolve request validates all variable inputs and plans every query (current semantic compilation or typed SQL template binding). Control prefers the latest matching `SUCCEEDED` run within the same workspace, dashboard, session, query name, query kind, connector, resolved metadata, compiled SQL, and output columns—enforcing current role/page authorization, ownership, integrity, and retention checks before returning results. If none exists, Control joins an equivalent `RUNNING` run, admitting a new execution only when neither exists. Lookup, creation, and admission are serialized under the session row lock so concurrent callers consume admission and quota only once. Results are never reused across different sessions.

Explicit refresh skips `SUCCEEDED` reuse only for the requested query names while still joining equivalent `RUNNING` runs; unselected queries keep their existing equivalent runs and fail atomically if none exist. Filter changes, refreshes, or tab closures never cancel a shared `RUNNING` query.

**Recompute boundaries are determined by exact execution inputs.** Variables consumed by a query participate in server resolution and equivalence checks. Display-only variables are still validated in the complete variable map, but identical execution plans reuse existing results. Resizing or re-rendering never issues a server resolve call. For SQL queries, equivalence compares the compiled executable SQL, ordered typed bindings (including repeated parameters and typed nulls), and row limit. Widgets swap their rendered bundle atomically only after all required run results have been read completely; authorization denials clear display state immediately rather than masking failure behind stale data.

**Separate immutable parameters from per-view variables.** Top-level dashboard `parameters` are immutable session arguments supplied in full before session creation (including explicit `null` for nullable parameters). Every `variable` declares an `initial` value from a literal, assignable `ParameterRef`, or `null`. Once initialized, `ParameterRef` is not a live fallback—calling `set(null)` keeps the variable null rather than snapping back to the parameter value. Queries and views read values directly via `ParameterRef | VariableRef`, while user interactions write only to `VariableRef`.

**Never predict warehouse latency in advance.** The same query can complete instantaneously or take minutes depending on warehouse state, caching, concurrency, and data volume. Every remote query is tracked as a durable run, and the UI expresses live refresh state inline on top of ready results rather than blanking charts behind global loading spinners.

---

## 3. Make Long-Running Work Observable and Reactive

**Work the user waits for must be first-class and visible inside the product.** SQL execution, semantic queries, catalog ingestion, dashboard runs, and agent operations should never be hidden behind opaque request/response timeouts. Start, progress, completion, failure, and cancellation form a single continuous stream.

**Reactive UI is a core product capability, not an afterthought.** Users should never have to guess whether the system is hung. Progress, affected resources, next steps, and cancellation controls are surfaced directly in place—including when an AI agent is actively drafting or querying a workspace.

**Observability is built into execution.** Long-running operations include state streams, reconnect state restoration, structured failure codes, cancellation, and history. Small synchronous actions are not forced into async jobs; operations are promoted to durable runs when latency, cost, uncertainty, or multi-step execution matters to the user.

---

## 4. Trusted Business Numbers Come from the Semantic Layer

**Shared organizational metrics must pass through governed metric definitions.** Dashboards, Explore queries, headless clients, and AI agents request data in terms of metrics, dimensions, and filters whenever possible. Scattering business definitions such as "Revenue" across ad-hoc SQL snippets, dashboards, and agent prompts undermines analytical trust.

**Raw SQL is an escape hatch, not the source of certified numbers.** Raw SQL remains essential for ad-hoc investigation and custom analysis, operating under the same workspace and connector boundaries. Operational dashboards and recurring reports default to the semantic layer.

**Chart widget code owns presentation, never metric authority.** Dashboard chart widgets may transform query results into ECharts options in sandboxed TypeScript, but code-derived values are purely visual projections. Any shared operational metric used across exports, schedules, or downstream widgets belongs in a semantic model or metric definition.

**AI-native systems narrow the vocabulary.** Instead of asking agents to reinvent joins, filters, and aggregations on every prompt, agents compose verified metrics, dimensions, and filters that the platform validates for existence, compatibility, and access. Narrow vocabulary applies to *authoring* as well as *querying*: raw column expressions live strictly inside Semantic Model definitions (`entity`, `dimension`, `measure`), while metrics reference measures and dimensions by semantic name.

---

## 5. Web, Agents, and Headless Clients Share One Product Model

**Different surfaces share one resource model and one execution semantics.** The web UI, REST API, MCP server, and headless clients operate over the same workspaces, resources, domain use cases, authorization rules, and execution history. Agents never bypass validation, permissions, or audit trails.

**Agent-native does not mean agent-only.** Agents are a first-class surface over the same product model humans use. Every change, query, or draft performed by an agent records attribution, version/history state, permissions, and reactive progress identically to human actions.

**Heuristic.** Whenever a feature seems to require separate "UI endpoints" and "agent endpoints" with diverging semantics, unify the domain use case first and keep only the transport adapters distinct.

---

## 6. Dashboards and Document Pages Keep Distinct Boundaries

**Dashboards provide structured operational BI.** Explorers and above read current definitions, sessions, and results, edit the workspace-shared draft, and apply changes to current. Editors manage shared semantic definitions and catalog selection; Owners manage connectors, members, roles, and workspace settings. Roles are cumulative (`Viewer < Explorer < Editor < Owner`), require direct workspace membership for internal entry, and apply uniformly to humans and agents without impersonation.

Viewers are authenticated page readers who inspect pages and currently linked dashboard sessions through a public display contract. When an Owner enables `Public pages`, non-members can view published pages and resolve variables on existing linked sessions, while creation of new sessions, manual refresh, cancellation, SSE streams, raw definitions, search, and document version history remain closed. Dashboard schedules are declared directly inside the dashboard DSL and activate upon apply.

**Document pages preserve free-form analytical narratives.** Markdown, notebooks, HTML analyses, and narrative reports are stored as immutable versions on `DOCUMENT` pages without introducing a parallel publication lifecycle.

**Never blur the two tracks.** Dashboards do not embed arbitrary full-page HTML documents, and document pages do not force a dashboard DSL. Page directories organize folders, document pages, and dashboard reference pages; dashboard reference pages store only a workspace-local dashboard ID plus independent navigation metadata, reusing the dashboard viewer directly.

**Charts are governed TypeScript capabilities.** Dashboard chart widgets execute a `setup + render` ABI inside an isolated Worker / SES runtime returning ECharts options with no ambient DOM authority; the host owns queries, parameters, variables, layout, and execution. Each query run records whether it executed as `SEMANTIC` or `SQL`, and widgets backed by raw SQL carry an explicit SQL indicator so raw queries are never mistaken for certified semantic metrics.
