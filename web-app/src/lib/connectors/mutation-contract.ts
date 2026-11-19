import type { components } from "@/api/control/generated";
import type { Connector, ExternalConnectorType } from "@/lib/types/connector";

export type CreateConnectorRequest = components["schemas"]["CreateConnectorRequest"];
export type ReviseConnectorRequest = components["schemas"]["ReviseConnectorRequest"];
export type RotateConnectorCredentialsRequest = components["schemas"]["RotateConnectorCredentialsRequest"];
export type ConnectorMutationRequest = CreateConnectorRequest | ReviseConnectorRequest | RotateConnectorCredentialsRequest;
export type ConnectorMutationResponse = components["schemas"]["ConnectorMutationResponse"];

export type ConnectorFieldKind = "text" | "password" | "textarea" | "select";

export interface ConnectorFormField {
  key: string;
  label: string;
  kind: ConnectorFieldKind;
  required: boolean;
  options?: readonly { value: string; label: string }[];
  placeholder?: string;
  helperText?: string;
  initialValue?: string;
  autocomplete?: string;
}

export interface ConnectorCredentialMethod {
  id: string;
  label: string;
  fields: readonly ConnectorFormField[];
}

export interface ExternalConnectorTypeContract {
  type: ExternalConnectorType;
  properties: readonly ConnectorFormField[];
  credentialMethods: readonly ConnectorCredentialMethod[];
}

const SSL_MODE_OPTIONS = [
  { value: "DISABLE", label: "disable" },
  { value: "REQUIRE", label: "require" },
  { value: "VERIFY_CA", label: "verify_ca" },
  { value: "VERIFY_FULL", label: "verify_full" },
] as const;

const REDSHIFT_SSL_MODE_OPTIONS = [
  { value: "DISABLE", label: "disable" },
  { value: "ALLOW", label: "allow" },
  { value: "PREFER", label: "prefer" },
  { value: "REQUIRE", label: "require" },
  { value: "VERIFY_CA", label: "verify-ca" },
  { value: "VERIFY_FULL", label: "verify-full" },
] as const;

const TRINO_SSL_OPTIONS = [
  { value: "true", label: "Enabled" },
  { value: "false", label: "Disabled" },
] as const;

const TRINO_SSL_VERIFICATION_OPTIONS = [
  { value: "FULL", label: "FULL" },
  { value: "CA", label: "CA" },
  { value: "NONE", label: "NONE" },
] as const;

export const CONNECTOR_TYPE_CONTRACTS: Record<ExternalConnectorType, ExternalConnectorTypeContract> = {
  POSTGRESQL: {
    type: "POSTGRESQL",
    properties: [
      { key: "host", label: "Host", kind: "text", required: true, autocomplete: "off" },
      { key: "port", label: "Port", kind: "text", required: true, initialValue: "5432", autocomplete: "off" },
      { key: "database", label: "Database", kind: "text", required: true, autocomplete: "off" },
      { key: "schema", label: "Schema", kind: "text", required: false, autocomplete: "off" },
      {
        key: "sslMode",
        label: "SSL mode",
        kind: "select",
        required: false,
        options: SSL_MODE_OPTIONS,
      },
    ],
    credentialMethods: [
      {
        id: "USERNAME_PASSWORD",
        label: "Username and password",
        fields: [
          { key: "username", label: "Username", kind: "text", required: true, autocomplete: "off" },
          { key: "password", label: "Password", kind: "password", required: true, autocomplete: "new-password" },
        ],
      },
    ],
  },
  BIGQUERY: {
    type: "BIGQUERY",
    properties: [
      { key: "projectId", label: "Project ID", kind: "text", required: true, autocomplete: "off" },
      { key: "location", label: "Location", kind: "text", required: true, initialValue: "US", autocomplete: "off" },
    ],
    credentialMethods: [
      {
        id: "SERVICE_ACCOUNT_JSON",
        label: "Service account JSON",
        fields: [
          {
            key: "serviceAccountJson",
            label: "Service account JSON",
            kind: "textarea",
            required: true,
            helperText: "Paste the full contents of the service account key file (.json).",
            autocomplete: "off",
          },
        ],
      },
    ],
  },
  SNOWFLAKE: {
    type: "SNOWFLAKE",
    properties: [
      { key: "account", label: "Account", kind: "text", required: true, placeholder: "orgname-account_name", autocomplete: "off" },
      { key: "warehouse", label: "Warehouse", kind: "text", required: true, autocomplete: "off" },
      { key: "database", label: "Database", kind: "text", required: true, autocomplete: "off" },
      { key: "schema", label: "Schema", kind: "text", required: false, autocomplete: "off" },
      { key: "role", label: "Role", kind: "text", required: false, autocomplete: "off" },
    ],
    credentialMethods: [
      {
        id: "USERNAME_PASSWORD",
        label: "Username and password",
        fields: [
          { key: "username", label: "Username", kind: "text", required: true, autocomplete: "off" },
          { key: "password", label: "Password", kind: "password", required: true, autocomplete: "new-password" },
        ],
      },
      {
        id: "USERNAME_PRIVATE_KEY",
        label: "Username and private key",
        fields: [
          { key: "username", label: "Username", kind: "text", required: true, autocomplete: "off" },
          { key: "privateKey", label: "Private key", kind: "textarea", required: true, autocomplete: "off" },
          {
            key: "privateKeyPassphrase",
            label: "Private key passphrase",
            kind: "password",
            required: false,
            autocomplete: "new-password",
          },
        ],
      },
    ],
  },
  TRINO: {
    type: "TRINO",
    properties: [
      { key: "host", label: "Host", kind: "text", required: true, autocomplete: "off" },
      { key: "port", label: "Port", kind: "text", required: true, initialValue: "443", autocomplete: "off" },
      { key: "catalog", label: "Catalog", kind: "text", required: true, autocomplete: "off" },
      { key: "schema", label: "Schema", kind: "text", required: false, autocomplete: "off" },
      { key: "ssl", label: "SSL", kind: "select", required: false, options: TRINO_SSL_OPTIONS },
      {
        key: "sslVerification",
        label: "SSL verification",
        kind: "select",
        required: false,
        options: TRINO_SSL_VERIFICATION_OPTIONS,
      },
    ],
    credentialMethods: [
      {
        id: "USERNAME_PASSWORD",
        label: "Username and password",
        fields: [
          { key: "username", label: "Username", kind: "text", required: true, autocomplete: "off" },
          { key: "password", label: "Password", kind: "password", required: true, autocomplete: "new-password" },
        ],
      },
    ],
  },
  REDSHIFT: {
    type: "REDSHIFT",
    properties: [
      { key: "host", label: "Host", kind: "text", required: true, autocomplete: "off" },
      { key: "port", label: "Port", kind: "text", required: true, initialValue: "5439", autocomplete: "off" },
      { key: "database", label: "Database", kind: "text", required: true, autocomplete: "off" },
      { key: "schema", label: "Schema", kind: "text", required: false, autocomplete: "off" },
      {
        key: "sslMode",
        label: "SSL mode",
        kind: "select",
        required: false,
        options: REDSHIFT_SSL_MODE_OPTIONS,
      },
    ],
    credentialMethods: [
      {
        id: "USERNAME_PASSWORD",
        label: "Username and password",
        fields: [
          { key: "username", label: "Username", kind: "text", required: true, autocomplete: "off" },
          { key: "password", label: "Password", kind: "password", required: true, autocomplete: "new-password" },
        ],
      },
      {
        id: "AWS_IAM",
        label: "IAM",
        fields: [
          { key: "awsAccessKeyId", label: "AWS access key ID", kind: "text", required: true, autocomplete: "off" },
          {
            key: "awsSecretAccessKey",
            label: "AWS secret access key",
            kind: "password",
            required: true,
            autocomplete: "new-password",
          },
          { key: "awsRegion", label: "AWS region", kind: "text", required: true, autocomplete: "off" },
          { key: "clusterId", label: "Cluster ID", kind: "text", required: true, autocomplete: "off" },
          { key: "dbUser", label: "Database user", kind: "text", required: true, autocomplete: "off" },
        ],
      },
    ],
  },
  DATABRICKS: {
    type: "DATABRICKS",
    properties: [
      { key: "host", label: "Host", kind: "text", required: true, placeholder: "dbc-….cloud.databricks.com", autocomplete: "off" },
      {
        key: "warehouseId", label: "Warehouse ID", kind: "text", required: true,
        placeholder: "1234567890abcdef", autocomplete: "off",
        helperText: "Find it in the warehouse's connection details: the last segment of the HTTP path.",
      },
      { key: "catalog", label: "Catalog", kind: "text", required: false, autocomplete: "off" },
      { key: "schema", label: "Schema", kind: "text", required: false, autocomplete: "off" },
    ],
    credentialMethods: [
      {
        id: "ACCESS_TOKEN",
        label: "Access token",
        fields: [
          { key: "accessToken", label: "Access token", kind: "password", required: true, autocomplete: "new-password" },
        ],
      },
      {
        id: "OAUTH_CLIENT",
        label: "OAuth client",
        fields: [
          { key: "oauthClientId", label: "OAuth client ID", kind: "text", required: true, autocomplete: "off" },
          {
            key: "oauthClientSecret",
            label: "OAuth client secret",
            kind: "password",
            required: true,
            autocomplete: "new-password",
          },
        ],
      },
    ],
  },
};

export const CONNECTOR_TYPE_ORDER: ExternalConnectorType[] = [
  "POSTGRESQL",
  "BIGQUERY",
  "SNOWFLAKE",
  "DATABRICKS",
  "TRINO",
  "REDSHIFT",
];

export function newMutationIdempotencyKey(prefix: string): string {
  return `${prefix}-${crypto.randomUUID()}`;
}

function compactStringMap(values: Record<string, string>): Record<string, string> {
  return Object.fromEntries(
    Object.entries(values)
      .map(([key, value]) => [key, value.trim()] as const)
      .filter(([, value]) => value.length > 0),
  );
}

function presentStringMap(values: Record<string, string>): Record<string, string> {
  return Object.fromEntries(Object.entries(values).filter(([, value]) => value.trim().length > 0));
}

export function initialFieldValues(fields: readonly ConnectorFormField[]): Record<string, string> {
  return Object.fromEntries(fields.map((field) => [field.key, field.initialValue ?? ""]));
}

export function credentialMethodById(
  type: ExternalConnectorType,
  methodId: string,
): ConnectorCredentialMethod {
  const methods = CONNECTOR_TYPE_CONTRACTS[type].credentialMethods;
  return methods.find((method) => method.id === methodId) ?? methods[0];
}

export function propertiesFromConnector(connector: Connector, type: ExternalConnectorType): Record<string, string> {
  const allowed = new Set(CONNECTOR_TYPE_CONTRACTS[type].properties.map((field) => field.key));
  return Object.fromEntries(
    Object.entries(connector.properties).filter(([key, value]) => allowed.has(key) && value != null).map(([key, value]) => [key, String(value)]),
  );
}

export function buildConnectorMutationRequest(input: {
  type: ExternalConnectorType;
  displayName: string;
  properties: Record<string, string>;
  credentials: Record<string, string>;
  credentialMethodId: string;
  mode: "create" | "revise" | "rotate";
}): { request: ConnectorMutationRequest } | { errors: Record<string, string> } {
  const contract = CONNECTOR_TYPE_CONTRACTS[input.type];
  const errors: Record<string, string> = {};
  const displayName = input.displayName.trim();
  const properties: Record<string, string | number | boolean> = {};
  if (input.mode !== "rotate") {
    if (!displayName) errors.displayName = "Display name is required.";
    const values = compactStringMap(input.properties);
    for (const field of contract.properties) {
      const value = values[field.key];
      if (!value) {
        if (field.required) errors[field.key] = `${field.label} is required.`;
        continue;
      }
      if (field.options && !field.options.some((option) => option.value === value)) {
        errors[field.key] = `${field.label} is invalid.`;
        continue;
      }
      if (field.key === "port") {
        if (!/^\d+$/.test(value) || Number(value) < 1 || Number(value) > 65535) {
          errors.port = "Port must be an integer from 1 to 65535.";
          continue;
        }
        properties.port = Number(value);
      } else if (input.type === "DATABRICKS" && field.key === "warehouseId") {
        if (!/^[A-Za-z0-9]{1,128}$/.test(value)) {
          errors.warehouseId = "Warehouse ID must contain 1 to 128 letters or digits.";
          continue;
        }
        properties.warehouseId = value;
      } else properties[field.key] = field.key === "ssl" ? value === "true" : value;
    }
  }
  if (input.mode === "revise") {
    if (Object.keys(errors).length > 0) return { errors };
    return { request: { displayName, properties } as ReviseConnectorRequest };
  }
  const method = credentialMethodById(input.type, input.credentialMethodId);
  const credentials = presentStringMap(Object.fromEntries(method.fields.map((field) => [field.key, input.credentials[field.key] ?? ""])));
  for (const field of method.fields) {
    if (field.required && !credentials[field.key]) errors[field.key] = `${field.label} is required.`;
  }
  if (Object.keys(errors).length > 0) return { errors };
  const credential = { kind: method.id, ...credentials } as RotateConnectorCredentialsRequest;
  if (input.mode === "rotate") return { request: credential };
  return { request: { connectorType: input.type, displayName, properties, credential } as CreateConnectorRequest };
}
