import { describe, expect, it } from "vitest";
import { buildConnectorMutationRequest, CONNECTOR_TYPE_CONTRACTS, initialFieldValues } from "./mutation-contract";

const input = {
  type: "POSTGRESQL" as const,
  displayName: " Lab Postgres ",
  properties: { host: "localhost", port: "5432", database: "warehouse", sslMode: "REQUIRE", schema: "" },
  credentials: { username: "reader", password: " secret " },
  credentialMethodId: "USERNAME_PASSWORD",
};

describe("typed connector requests", () => {
  it("creates with typed properties and a discriminated write-only credential", () => {
    expect(buildConnectorMutationRequest({ ...input, mode: "create" })).toEqual({ request: {
      connectorType: "POSTGRESQL", displayName: "Lab Postgres",
      properties: { host: "localhost", port: 5432, database: "warehouse", sslMode: "REQUIRE" },
      credential: { kind: "USERNAME_PASSWORD", username: "reader", password: " secret " },
    } });
  });
  it("settings never include credentials even if a stale draft contains them", () => {
    expect(buildConnectorMutationRequest({ ...input, mode: "revise" })).toEqual({ request: {
      displayName: "Lab Postgres", properties: { host: "localhost", port: 5432, database: "warehouse", sslMode: "REQUIRE" },
    } });
  });
  it("rotation requires a complete credential and contains no settings", () => {
    expect(buildConnectorMutationRequest({ ...input, mode: "rotate" })).toEqual({ request: {
      kind: "USERNAME_PASSWORD", username: "reader", password: " secret ",
    } });
    expect(buildConnectorMutationRequest({ ...input, mode: "rotate", credentials: { username: "reader" } })).toEqual({ errors: { password: "Password is required." } });
  });
  it.each(["0", "65536", "2.5", "1e3", "abc"])("rejects invalid port %s", (port) => {
    expect(buildConnectorMutationRequest({ ...input, mode: "create", properties: { ...input.properties, port } })).toEqual({ errors: { port: "Port must be an integer from 1 to 65535." } });
  });
  it("emits boolean false and omits unrecognized draft properties", () => {
    expect(buildConnectorMutationRequest({ ...input, type: "TRINO", mode: "revise", properties: { host: "trino", port: "443", catalog: "lake", ssl: "false", unknown: "value" } })).toEqual({ request: {
      displayName: "Lab Postgres", properties: { host: "trino", port: 443, catalog: "lake", ssl: false },
    } });
  });
  it("uses the warehouse ID for create and settings without retaining an HTTP path", () => {
    for (const mode of ["create", "revise"] as const) {
      const result = buildConnectorMutationRequest({ ...input, type: "DATABRICKS", mode,
        properties: { host: "workspace.cloud.databricks.com", warehouseId: "abc0123", httpPath: "/sql/1.0/warehouses/stale" },
        credentialMethodId: "ACCESS_TOKEN", credentials: { accessToken: "token" },
      });
      expect(result).toMatchObject({ request: { properties: { host: "workspace.cloud.databricks.com", warehouseId: "abc0123" } } });
      if (!("request" in result) || !("properties" in result.request)) throw new Error("Expected connector settings");
      expect(result.request.properties).not.toHaveProperty("httpPath");
    }
  });
  it.each(["/sql/1.0/warehouses/abc", "abc/def", "abc-def", "abc_def", "a".repeat(129)])("rejects invalid warehouse ID %s", (warehouseId) => {
    expect(buildConnectorMutationRequest({ ...input, type: "DATABRICKS", mode: "revise",
      properties: { host: "workspace.cloud.databricks.com", warehouseId },
    })).toEqual({ errors: { warehouseId: "Warehouse ID must contain 1 to 128 letters or digits." } });
  });
});

describe("credential variants", () => {
  it.each([
    ["BIGQUERY", "SERVICE_ACCOUNT_JSON", { projectId: "project", location: "US" }, { serviceAccountJson: '{"type":"service_account"}' }],
    ["SNOWFLAKE", "USERNAME_PRIVATE_KEY", { account: "account", warehouse: "compute", database: "db" }, { username: "reader", privateKey: "key", privateKeyPassphrase: "passphrase" }],
    ["REDSHIFT", "AWS_IAM", { host: "redshift", port: "5439", database: "db" }, { awsAccessKeyId: "id", awsSecretAccessKey: "secret", awsRegion: "us-east-1", clusterId: "cluster", dbUser: "reader" }],
    ["DATABRICKS", "ACCESS_TOKEN", { host: "databricks", warehouseId: "warehouse123" }, { accessToken: "token" }],
    ["DATABRICKS", "OAUTH_CLIENT", { host: "databricks", warehouseId: "warehouse123" }, { oauthClientId: "client", oauthClientSecret: "secret" }],
  ] as const)("creates %s using %s", (type, kind, properties, credentials) => {
    const result = buildConnectorMutationRequest({ ...input, type, mode: "create", properties, credentials, credentialMethodId: kind });
    expect(result).toMatchObject({ request: { connectorType: type, credential: { kind, ...credentials } } });
  });
});

describe("field validation", () => {
  it.each([
    ["POSTGRESQL", "USERNAME_PASSWORD", ["host", "port", "database"], ["username", "password"]],
    ["BIGQUERY", "SERVICE_ACCOUNT_JSON", ["projectId", "location"], ["serviceAccountJson"]],
    ["SNOWFLAKE", "USERNAME_PASSWORD", ["account", "warehouse", "database"], ["username", "password"]],
    ["SNOWFLAKE", "USERNAME_PRIVATE_KEY", ["account", "warehouse", "database"], ["username", "privateKey"]],
    ["TRINO", "USERNAME_PASSWORD", ["host", "port", "catalog"], ["username", "password"]],
    ["REDSHIFT", "USERNAME_PASSWORD", ["host", "port", "database"], ["username", "password"]],
    ["REDSHIFT", "AWS_IAM", ["host", "port", "database"], ["awsAccessKeyId", "awsSecretAccessKey", "awsRegion", "clusterId", "dbUser"]],
    ["DATABRICKS", "ACCESS_TOKEN", ["host", "warehouseId"], ["accessToken"]],
    ["DATABRICKS", "OAUTH_CLIENT", ["host", "warehouseId"], ["oauthClientId", "oauthClientSecret"]],
  ] as const)("collects every missing field for %s / %s in each mode", (type, credentialMethodId, propertyKeys, credentialKeys) => {
    for (const mode of ["create", "revise", "rotate"] as const) {
      const result = buildConnectorMutationRequest({
        type, credentialMethodId, mode, displayName: " ", properties: {}, credentials: {},
      });
      expect(result).toHaveProperty("errors");
      if (!("errors" in result)) throw new Error("Expected field validation errors");
      expect(Object.keys(result.errors).sort()).toEqual([
        ...(mode === "rotate" ? [] : ["displayName", ...propertyKeys]),
        ...(mode === "revise" ? [] : credentialKeys),
      ].sort());
      expect(Object.values(result.errors).every((message) => message.endsWith(" is required."))).toBe(true);
    }
  });

  it("collects invalid options, invalid port, and missing values together", () => {
    expect(buildConnectorMutationRequest({
      ...input, mode: "create", displayName: "", properties: { port: "2.5", sslMode: "UNKNOWN" }, credentials: {},
    })).toEqual({ errors: {
      displayName: "Display name is required.", host: "Host is required.",
      port: "Port must be an integer from 1 to 65535.", database: "Database is required.",
      sslMode: "SSL mode is invalid.", username: "Username is required.", password: "Password is required.",
    } });
  });

  it("ignores missing credentials on revise and invalid settings on rotate", () => {
    expect(buildConnectorMutationRequest({ ...input, mode: "revise", credentials: {} })).toHaveProperty("request");
    expect(buildConnectorMutationRequest({ ...input, mode: "rotate", displayName: "", properties: { port: "invalid" } })).toEqual({
      request: { kind: "USERNAME_PASSWORD", username: "reader", password: " secret " },
    });
  });

  it("validates only the selected credential method", () => {
    expect(buildConnectorMutationRequest({
      ...input, type: "DATABRICKS", mode: "rotate", credentialMethodId: "OAUTH_CLIENT",
      credentials: { accessToken: "stale token", oauthClientId: " ", oauthClientSecret: " " },
    })).toEqual({ errors: { oauthClientId: "OAuth client ID is required.", oauthClientSecret: "OAuth client secret is required." } });
  });
});

describe("create defaults", () => {
  it("prefills conventional required values as real values instead of placeholders", () => {
    expect(initialFieldValues(CONNECTOR_TYPE_CONTRACTS.POSTGRESQL.properties)).toMatchObject({ port: "5432", host: "" });
    expect(initialFieldValues(CONNECTOR_TYPE_CONTRACTS.BIGQUERY.properties)).toMatchObject({ location: "US", projectId: "" });
    expect(initialFieldValues(CONNECTOR_TYPE_CONTRACTS.TRINO.properties)).toMatchObject({ port: "443" });
    expect(initialFieldValues(CONNECTOR_TYPE_CONTRACTS.REDSHIFT.properties)).toMatchObject({ port: "5439" });
    const prefilledFields = Object.values(CONNECTOR_TYPE_CONTRACTS).flatMap((contract) => contract.properties).filter((field) => field.initialValue);
    expect(prefilledFields.every((field) => field.placeholder === undefined)).toBe(true);
  });
});
