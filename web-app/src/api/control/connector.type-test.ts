import type { components } from "./generated";

type Create = components["schemas"]["AgentCreateRequest"];
type Edit = components["schemas"]["AgentEditRequest"];
type Rotate = components["schemas"]["RotateConnectorCredentialsRequest"];

export const connectorCreate: Create = { path: "workspace/connectors", input: { kind: "CONNECTOR_CREATE", connectorType: "POSTGRESQL", displayName: "Analytics", properties: { host: "warehouse", port: 5432, database: "analytics" }, credential: { kind: "USERNAME_PASSWORD", username: "reader", password: "example-only" } } };
// @ts-expect-error Ports are numbers; owner property references must remain typed when adding the Agent discriminator.
export const stringPort: Create = { path: "workspace/connectors", input: { kind: "CONNECTOR_CREATE", connectorType: "POSTGRESQL", displayName: "Analytics", properties: { host: "warehouse", port: "5432", database: "analytics" }, credential: { kind: "USERNAME_PASSWORD", username: "reader", password: "example-only" } } };
// @ts-expect-error PostgreSQL only accepts username/password credentials.
export const wrongCredential: Create = { path: "workspace/connectors", input: { kind: "CONNECTOR_CREATE", connectorType: "POSTGRESQL", displayName: "Analytics", properties: { host: "warehouse", port: 5432, database: "analytics" }, credential: { kind: "ACCESS_TOKEN", accessToken: "example-only" } } };
export const rotate: Rotate = { kind: "ACCESS_TOKEN", accessToken: "example-only" };
// @ts-expect-error A full credential is required.
export const incompleteRotation: Rotate = { kind: "USERNAME_PASSWORD", username: "reader" };
export const settings: Edit = { path: "workspace/connectors/0198ef28-1000-7000-8000-000000000001", patch: [{ op: "replace", path: "/displayName", value: "Analytics" }] };
// @ts-expect-error Credentials are outside the editable settings document.
export const secretPatch: Edit = { path: "workspace/connectors/0198ef28-1000-7000-8000-000000000001", patch: [{ op: "replace", path: "/credential/password", value: "example-only" }] };
