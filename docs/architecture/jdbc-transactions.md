# Control JDBC Transaction Execution

JDBC and Spring's `DataSourceTransactionManager` bind database connections to the executing thread. Java Virtual Threads reduce carrier-thread blocking during JDBC I/O, but do not increase database pool capacity or convert JDBC into a non-blocking API.

`:application` provides the shared `jdbcDispatcher` bean via `JdbcVirtualThreadDispatcher`. Transactional adapters in `identity-access`, `pages`, `dashboard`, `catalog`, `semantic`, operations read controllers, and same-snapshot `connector` lookups inject this dispatcher while keeping domain and application module ports free of Spring or `:application` dependencies.

---

## Execution and Nesting Rules

- **Top-level JDBC entry** outside an active transaction dispatches onto a Virtual Thread.
- **Same-DataSource nesting** inside an active transaction stays on the current thread and reuses the bound JDBC connection.
- **Cross-DataSource or unbound anomalies** (an active transaction on a different `DataSource` or a non-transactional bound connection) fail fast.
- **Nested `REQUIRED` calls** join the outer transaction without resetting statement or lock timeouts; if a nested participant fails, the outer transaction is marked rollback-only.
- **Isolation enforcement:** Boundaries requiring specific isolation levels—such as `REPEATABLE_READ` for cross-module snapshots or `READ_COMMITTED` for catalog/semantic mutations—reject incompatible outer transactions immediately.
- **No `REQUIRES_NEW` connection expansion:** Independent operations queries must start at the root; acquiring a second concurrent connection via `REQUIRES_NEW` inside an active transaction is disallowed.

---

## Coroutine Bridge and I/O Boundaries

Transaction callbacks stay synchronous JDBC code wherever possible. When an existing `suspend` owner API must be composed inside a snapshot transaction, the `runBlocking` bridge preserves the caller's coroutine context and `Job` while stripping `ContinuationInterceptor` so execution stays pinned to the transaction thread's event loop.

Callbacks must never switch to `Dispatchers.IO`, launch parallel child JDBC tasks, or perform external network calls inside a JDBC transaction. External I/O—such as reading Arrow artifacts from S3—runs strictly outside database transaction boundaries.

---

## Serialization Retries and Cancellation

Only the top-level snapshot owner retries serialization failures (up to three attempts over the entire transaction); nested helpers never retry partial fragments. Coroutine cancellation is never retried and propagates cleanly through the bridge to trigger rollback and connection release. Blocking SQL cancellation relies on JDBC statement/transaction timeouts and deadline-driven thread interruption.

`JdbcVirtualThreadDispatcherTest` and `JdbcTransactionCompositionTest` verify connection acquisition counts, thread affinity, isolation rejection, rollback-only propagation, retries, and post-cancellation connection return against real Spring transaction managers, complemented by PostgreSQL integration tests for Dashboard Sessions and Operations queries.
