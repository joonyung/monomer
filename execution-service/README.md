# Execution Service (`execution-service`)

`execution-service` is an independently built, DB-less physical execution runtime for Monomer. It owns no product CRUD state, durable run ledger, user permission graph, connector store, or internal database schema.

## Capabilities

- Standalone gRPC and protobuf bindings generated directly from [`../protos/execution_plane.proto`](../protos/execution_plane.proto)
- Internal gRPC `Run` server dialed by `control-service` (default listen address `127.0.0.1:11099`)
- Per-run connector runtime bindings with in-memory `MCR1` credentials (never persisted to disk or written to logs)
- Six external SQL warehouse drivers (`driver-postgresql`, `driver-bigquery`, `driver-snowflake`, `driver-trino`, `driver-redshift`, `driver-databricks`) with vendor dependencies isolated inside each driver module
- Read-only embedded DuckDB driver (`driver-duckdb`) exclusively serving Monomer's synthetic sample datasets

## Delivery & Validation Boundaries

- **`INLINE_STREAM`:** Streams bounded self-contained Apache Arrow IPC batches directly over gRPC without touching object storage (used for catalog metadata scans).
- **`ARTIFACT_UPLOAD`:** Encodes query results into an immutable Arrow file uploaded directly via multipart upload to S3 under a deployment-injected, prefix-scoped credential (`artifacts/`). Control retains full authority over result visibility, reads, and deterministic cleanup.
- **`ValidateRawSql`:** Non-durable unary RPC called before Explore raw SQL execution to verify single-statement read safety across supported warehouse drivers. Dashboard SQL executes the same native validation inside `Run` before submitting the compiled statement once and checking declared output Arrow column names before upload.

## Verification

```bash
./gradlew :application:run
./gradlew test check
./gradlew confidenceSweep
./gradlew purityTest executionFaultTest boundedMemoryTest
./gradlew :protocol:verifyRootProtoContract
```

- `check` runs unit tests, compilation, dependency purity checks, and protocol/contract verification.
- `confidenceSweep` runs PostgreSQL-backed integration tests.
- Local `:application:run` prepares synthetic sample DuckDB datasets automatically unless `MONOMER_EXECUTION_SAMPLE_DATA_ROOT` points to an existing absolute dataset directory.
