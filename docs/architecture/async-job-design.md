# Asynchronous Query Execution and Job Management

## Purpose

When a user or agent submits a query, `control-service` creates a durable run record and immediately returns its identifier (`runId`). Query execution is decoupled from the browser request lifecycle so runs continue even if the user navigates away or closes the tab; clients reconnect later using `runId` to inspect state and retrieve results.

This specification defines the shared execution contract across Explore, Catalog, and Dashboard queries. Surface-specific input schemas, result rendering, and authorization rules remain with each owning module.

---

## Core Principles

- **Control** is the sole durable authority for jobs and product state.
- **Execution** is a DB-less physical executor dialed by Control over internal gRPC.
- Each `runId` identifies at most one physical execution; a retry or resubmission creates a new `runId`.
- Canonical results are either owner-settled domain rows (`INLINE_STREAM`) or a finalized Apache Arrow artifact in S3 (`ARTIFACT_UPLOAD`).
- Progress messages are observational telemetry; terminal states and result metadata are committed authoritatively by Control.
- Client polling or SSE disconnection never alters server-side execution lifetime.

---

## Division of Responsibilities

### Control (`control-service`)
- Authenticate principals and verify workspace roles and connector authorization
- Admit runs and handle idempotent resubmissions
- Pin the current Connector binding into the physical run assignment and materialize credentials on dial
- Persist run states, heartbeats, progress snapshots, and normalized failure codes
- Commit terminal transitions and delivery-specific result metadata
- Serve artifact result reads, enforce retention, and clean up orphaned/failed artifacts
- Run periodic recovery scans to detect and close orphaned runs

### Execution (`execution-service`)
- Connect to external warehouses using the assignment-supplied connector binding
- Execute queries and encode rows into Apache Arrow
- Stream self-contained Arrow batches (`INLINE_STREAM`) or multipart-upload an Arrow file artifact to object storage (`ARTIFACT_UPLOAD`)
- Stream progress telemetry, heartbeats, and physical terminal outcomes back to Control

Execution never persists connectors, credentials, runs, or result metadata; credentials exist strictly in memory for the duration of a single execution.

---

## Execution Lifecycle

1. Control validates the request and authorization, persisting a durable run with its stable connector ID and self-contained dispatch assignment.
2. Control dials Execution using the stored assignment (without re-reading mutable connector state), supplying `runId`, SQL, materialized credentials, and delivery mode (`INLINE_STREAM` or `ARTIFACT_UPLOAD`).
3. Execution runs the query while streaming periodic heartbeats and progress events.
4. Execution streams bounded Arrow batches inline or completes a multipart Arrow file upload to S3.
5. Execution emits the physical terminal outcome and delivery metadata.
6. Control's owning module adopts complete inline frames or validates the uploaded artifact manifest (`HEAD`) and commits the terminal state.

Clients poll or subscribe by `runId`; reconnecting resumes observation without triggering a new physical execution.

---

## States, Transitions, and Idempotency

Runs transition monotonically from admission through `RUNNING` to a terminal state (`SUCCEEDED`, `FAILED`, or `CANCELLED`). Terminal states are never reversed.

- `INLINE_STREAM` succeeds only when a verified complete frame sequence and terminal outcome are adopted by the owning module.
- `ARTIFACT_UPLOAD` succeeds only when the finalized artifact manifest passes integrity verification.
- Conditional terminal updates ensure late progress frames or out-of-order events cannot mutate an already-terminal run. Losing transient progress is acceptable; losing terminal state or result metadata is not.

**Idempotency:** Repeating the exact same request with the same idempotency key returns the existing run; reusing an idempotency key with a different payload is rejected as a conflict. Failed runs are never automatically retried—explicit user resubmission allocates a fresh `runId`.

---

## Heartbeats, Orphan Recovery, and Artifact Cleanup

Control updates a run's last-heartbeat timestamp while the gRPC stream is alive. A background recovery worker periodically scans active runs and terminates any run exceeding its staleness window as `FAILED / EXECUTION_ORPHANED`.

Orphan recovery does not attempt optimistic success reconciliation: even if Execution finished uploading bytes right before crashing prior to sending its terminal outcome, the run fails and its deterministic S3 key is scheduled for cleanup.

**CAS Artifact Cleanup Loop:** Run failure settlement is decoupled from object-store deletion so transient S3 errors never block failure reporting. The recovery loop selects failed or cancelled `ARTIFACT_UPLOAD` runs where `artifact_cleanup_completed_at IS NULL` past their execution deadline, claims the attempt by CAS-updating `artifact_cleanup_last_attempted_at`, deletes the exact stored object key, and marks completion via versioned CAS. Candidate ordering prioritizes unattempted and oldest-attempted rows so persistent failures cannot starve subsequent cleanups. Terminal state recording uses a dedicated `READ COMMITTED` transaction with `SELECT ... FOR UPDATE` on the execution row to serialize cleanly against cleanup completion, while normal runtime and owner transactions remain `REPEATABLE READ`.

---

## Delivery Modes and Failure Handling

- **`ARTIFACT_UPLOAD`** (Explore and Dashboard queries): Execution encodes the complete result into an immutable Arrow file uploaded directly via multipart upload to S3; Control receives progress and final artifact metadata. Previews are bounded projections over the canonical Arrow artifact.
- **`INLINE_STREAM`** (Catalog metadata scans): Execution returns Arrow batches directly on the gRPC response stream without touching object storage; Control's Catalog module updates current metadata rows directly.

External warehouse errors, invalid SQL, connector errors, encoding/upload failures, and executor disconnects all resolve to structured run failures storing both a stable machine-readable failure code and a safe user-facing message. Partial uploads or incomplete inline streams are never exposed as valid results.

---

## Security & Implementation Invariants

- Execution gRPC listens strictly on an internal private address dialed only by Control.
- Execution's object-storage IAM role allows only scoped artifact uploads and multipart aborts under the canonical `artifacts/` prefix.
- All result reads pass through Control authentication and authorization checks.
- Connector secrets are never logged or embedded in failure messages.
- No durable run state exists outside Control; at most one physical execution occurs per `runId`; admitted connector assignments are immutable regardless of later connector edits.
