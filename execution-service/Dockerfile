# syntax=docker/dockerfile:1.7

FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk@sha256:201fbb8886b2d273218aa3a192f0afbf7b5ff65ee8cc6ef47f5dce2171f013ea AS build
WORKDIR /workspace
COPY execution-service/ execution-service/
COPY protos/execution_plane.proto protos/execution_plane.proto
COPY testkit/data/duckdb/acme-cloud/generate.sql testkit/data/duckdb/acme-cloud/generate.sql
COPY testkit/data/duckdb/acme-cloud/versions/1/generate.sql testkit/data/duckdb/acme-cloud/versions/1/generate.sql
COPY testkit/data/duckdb/acme-cloud/versions/2/generate.sql testkit/data/duckdb/acme-cloud/versions/2/generate.sql
COPY testkit/data/duckdb/acme-cloud/GenerateDataset.java testkit/data/duckdb/acme-cloud/GenerateDataset.java
WORKDIR /workspace/execution-service
RUN --mount=type=cache,id=monomer-execution-gradle,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon :application:installDist
RUN java --enable-native-access=ALL-UNNAMED \
    -cp '/workspace/execution-service/application/build/install/application/lib/*' \
    /workspace/testkit/data/duckdb/acme-cloud/GenerateDataset.java \
    /workspace/testkit/data/duckdb/acme-cloud/versions/1/generate.sql \
    /workspace/sample-data/acme-cloud/1/acme_cloud.duckdb \
    && java --enable-native-access=ALL-UNNAMED \
    -cp '/workspace/execution-service/application/build/install/application/lib/*' \
    /workspace/testkit/data/duckdb/acme-cloud/GenerateDataset.java \
    /workspace/testkit/data/duckdb/acme-cloud/versions/2/generate.sql \
    /workspace/sample-data/acme-cloud/2/acme_cloud.duckdb \
    && java --enable-native-access=ALL-UNNAMED \
    -cp '/workspace/execution-service/application/build/install/application/lib/*' \
    /workspace/testkit/data/duckdb/acme-cloud/GenerateDataset.java \
    /workspace/testkit/data/duckdb/acme-cloud/generate.sql \
    /workspace/sample-data/acme-cloud/3/acme_cloud.duckdb \
    && chmod -R a-w /workspace/sample-data

FROM eclipse-temurin:25-jre@sha256:681c543d6f36c50f45e9b5226930a46203dcfa351d3670e9d0bdf0dabae53539
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/monomer
COPY --from=build --chown=65532:65532 \
    /workspace/execution-service/application/build/install/application \
    /opt/monomer/execution-service
COPY --from=build /workspace/sample-data /opt/monomer/sample-data
USER 65532:65532
EXPOSE 11099
ENTRYPOINT ["/opt/monomer/execution-service/bin/application"]
