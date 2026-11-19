# Control Service (`control-service`)

`control-service` is a standalone multi-module Gradle build assembling Monomer's durable product authority into a single Spring Boot deployable (`:application`). Eleven bounded context modules enforce explicit public packages and dependency rules declared in [`../contracts/architecture/module-ownership.json`](../contracts/architecture/module-ownership.json).

- **Default HTTP port:** `127.0.0.1:11077` (REST, GraphQL, SSE, MCP)
- **Execution Plane gRPC target port:** `11078` / `11099` (configurable)
- **gRPC bindings:** Generated directly at build time from [`../protos/execution_plane.proto`](../protos/execution_plane.proto) without duplicating the `.proto` file.

---

## Build & Verification Commands

Control consumes its `metricflow-kotlin` semantic SQL compiler dependency from a pinned Maven repository bundle verified against the release artifact contract (`MONOMER_METRICFLOW_REPOSITORY` or Gradle property `-PmonomerMetricflowRepository`).

```bash
./gradlew -PmonomerMetricflowRepository="/path/to/metricflow-maven-repository" test check
./gradlew -PmonomerMetricflowRepository="/path/to/metricflow-maven-repository" confidenceSweep
./gradlew -PmonomerMetricflowRepository="/path/to/metricflow-maven-repository" :application:integrationTest
./gradlew -PmonomerMetricflowRepository="/path/to/metricflow-maven-repository" :application:bootRun
curl http://127.0.0.1:11077/actuator/health
```

- `check` runs unit tests plus architectural fitness gates: module topology, dependency cycle prevention, private-package import blocking, hexagonal pure-layer import verification, root `.proto` integrity, absence of warehouse drivers in Control's runtime classpath, and pinned `metricflow-kotlin` artifact consumption.
- `confidenceSweep` additionally runs PostgreSQL integration suites.

---

## Semantic Schemas

Current-definition JSON schemas for Semantic Models, Metrics, and Semantic Queries live under `modules/semantic/src/main/resources/schema/semantic/` (`model.schema.json`, `metric.schema.json`, `query.schema.json`). `schemaVersion` acts strictly as a storage/wire shape discriminator rather than a definition publication version.

---

## Container Secrets

The Control container runs the JVM directly as non-root UID/GID `65532`. In cloud deployments, ECS injects database passwords, credential-vault AES keys, OAuth secrets, and JWT PKCS#8 private keys from AWS Secrets Manager via environment variables; secrets are never baked into images or build arguments.
