# syntax=docker/dockerfile:1.7

FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk@sha256:201fbb8886b2d273218aa3a192f0afbf7b5ff65ee8cc6ef47f5dce2171f013ea AS build
WORKDIR /workspace
COPY control-service/ control-service/
COPY protos/execution_plane.proto protos/execution_plane.proto
COPY contracts/architecture/module-ownership.json contracts/architecture/module-ownership.json
COPY contracts/agent-workspace.schema.json contracts/control-service.openapi.json contracts/problem-details.schema.json contracts/metric-display.schema.json contracts/time-zones.json contracts/
COPY release/contracts/metricflow-artifact-set.json release/contracts/metricflow-artifact-set.json
COPY assets/brand/monomer-favicon.png assets/brand/monomer-favicon.png
COPY testkit/data/duckdb/acme-cloud/definitions/ testkit/data/duckdb/acme-cloud/definitions/
COPY testkit/data/duckdb/acme-cloud/versions/1/definitions/ testkit/data/duckdb/acme-cloud/versions/1/definitions/
COPY testkit/data/duckdb/acme-cloud/versions/2/definitions/ testkit/data/duckdb/acme-cloud/versions/2/definitions/
COPY --from=metricflow-repository . /opt/monomer/metricflow-repository
WORKDIR /workspace/control-service
RUN --mount=type=cache,id=monomer-control-gradle,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon \
    -Pkotlin.daemon.jvmargs=-Xmx1024m \
    -PmonomerMetricflowRepository=/opt/monomer/metricflow-repository \
    :application:bootJar

FROM --platform=$BUILDPLATFORM node:24.19.0-alpine@sha256:d32cdf619f63fe0471182d08996dd516c6275bb5fd31ae06e55a570bd9e1ad43 AS mcp-view
WORKDIR /workspace/web-app
COPY web-app/package.json web-app/package-lock.json ./
COPY web-app/patches ./patches
RUN npm ci
COPY web-app/src ./src
COPY web-app/tsconfig.json web-app/postcss.config.mjs web-app/vite.mcp-view.config.ts ./
COPY contracts/metric-display.schema.json /workspace/contracts/metric-display.schema.json
RUN npm run build:mcp-view

FROM eclipse-temurin:25-jre@sha256:681c543d6f36c50f45e9b5226930a46203dcfa351d3670e9d0bdf0dabae53539 AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/monomer
COPY --from=build /workspace/control-service/application/build/libs/control-service.jar ./control-service.jar
COPY infra/cloud-dev/database-bootstrap/us-east-1-bundle.pem /opt/monomer/rds-ca.pem
COPY --from=mcp-view /workspace/web-app/dist-mcp-view/dashboard-view.html /opt/monomer/mcp/dashboard-view.html
ENV MONOMER_MCP_DASHBOARD_VIEW_FILE=/opt/monomer/mcp/dashboard-view.html
EXPOSE 11077 11078
USER 65532:65532
ENTRYPOINT ["java", "--add-opens=java.base/java.nio=ALL-UNNAMED", "-jar", "/opt/monomer/control-service.jar"]
