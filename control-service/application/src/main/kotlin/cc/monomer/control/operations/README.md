# Operations read API

All routes require the dedicated operations Bearer credential; duplicate Authorization headers
are rejected. Responses, including errors, use `Cache-Control: no-store`. Fixed query filters
are accepted; SQL, customer results, credentials and raw failure messages are never returned.

Every successful response includes `queriedAt`, `from`, and `to`. Lists include `items` and
nullable `nextCursor`. The Kotlin response types in `OperationsQueriesController.kt` define the
wire fields. Identity and Explore overview panels have separate snapshots; a failed database
panel is null and named in `unavailablePanels`, never replaced with zero data. Zero-filled UTC
daily series are created only for successfully read panels.

- `GET /ops/v1/overview`: `from`, `to`.
- `GET /ops/v1/users`: optional literal `q`, optional `from` and `to`, `limit`, `cursor`.
- `GET /ops/v1/users/{id}`: current user and current Organization/Workspace memberships.
- `GET /ops/v1/workspaces`: `q`, `organizationId`, `withExploreRuns`, `from`, `to`, `limit`, `cursor`.
- `GET /ops/v1/workspaces/{id}`: `from`, `to`; current members/connectors and period run statistics.
- `GET /ops/v1/runs`: `workspaceId`, `kind` (SQL/SEMANTIC), `state`
  (RUNNING/SUCCEEDED/FAILED/CANCELLED), `timeBasis` (createdAt/terminalAt), `from`, `to`, `limit`, `cursor`.
- `GET /ops/v1/runs/{id}`: safe run metadata, current Workspace name when retained, elapsed milliseconds.
- `GET /ops/v1/journeys`: required `from` and `to`, repeatable `excludeOrganizationId`; per-Organization
  first-reach milestones and UTC weekly activity (`OperationsJourneysController.kt`).

Periods use UTC `[from,to)`, default the last seven days, and cannot exceed 30 days.
Both bounds must be supplied together. Users without dates include all current users.
Workspaces/runs cursor requests must explicitly echo the prior response's `from` and `to`,
including when the first request used the default period. Every filter is bound to the cursor;
changing any filter requires starting a new page sequence. All lists sort by creation time then
ID descending. Limits default to 50 and range from 1 to 100. IDs must be canonical UUID strings.

Journeys is the one exception to the 30-day cap and to paging, because distinct users can only be
counted exactly when the whole population is in one response. `from` and `to` are UTC Monday
00:00:00Z, the period spans 1 to 26 whole weeks, and `to` is at most 7 days after the Monday of the
current UTC week. At most 50 canonical lowercase `excludeOrganizationId` values are accepted;
duplicates collapse and unknown IDs are ignored. Any other query parameter, including `cursor` and
`limit`, is a 400. The population is the current Organizations created before `to`, minus exclusions;
more than 1,000 is a 422 rather than a partial list. Identity reads the population first, then
Connector, Explore, Dashboard, Semantic, and Agent grant owners read concurrently in their own
read-only transactions. Activity counts only for a current member of the Organization that owns the
row's Workspace. A failed owner is named in `unavailableSources` and every value that depends on it
is null; a failed population or agent app name read fails the request with 503. Ownership and null-fallback rules are enforced by the Identity operations query ports.

`firstConnectorAt` excludes DuckDB sample connectors. `firstSampleConnectorAt` records their
first creation and `firstSampleAgentRunAt` is the first actual AGENT Explore run or Dashboard
query run in a sample Workspace, over all retained history and by a current Organization member.
Definition changes, including sample provisioning, do not reach the sample Agent-run milestone.
Existing definition-inclusive milestones and activity retain their meanings. A Connector-source
failure nulls Connector-dependent milestones; Explore/Dashboard failures also null the sample
Agent-run milestone. Weekly `exploreRuns` retains total/succeeded/surface counts and adds
`sampleTotal`, `sampleSucceeded`, `externalTotal`, and `externalSucceeded`, classified by the
immutable engine on each retained Explore run rather than a current Connector lookup.

Authentication failures are 401, denied operations access is 403, invalid queries are 400,
missing details are 404, an oversized journeys population is 422, and owner database failure is 503
outside the partial overview and journeys responses.
Run `:application:test --tests '*Operations*ControllerTest'` and
`:application:integrationTest --tests '*Operations*PostgresIntegrationTest'`.
