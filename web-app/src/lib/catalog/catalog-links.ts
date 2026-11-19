import { webPaths } from "@/lib/web-paths";
import type { Connector, ConnectorType } from "@/lib/types/connector";

export const THREE_DEPTH_CONNECTOR_TYPES: ReadonlySet<ConnectorType> = new Set<ConnectorType>(["BIGQUERY", "DATABRICKS", "SNOWFLAKE", "TRINO"]);

export interface CatalogLocation {
  connector: string;
  catalog?: string | null;
  schema?: string | null;
  table?: string | null;
  // Opens the "Choose tables to load" panel.
  choose?: boolean;
}

export interface TableAddress {
  connectorId: string;
  catalogName: string | null;
  schemaName: string;
  tableName: string;
}

function appendIfPresent(params: URLSearchParams, key: string, value: string | null | undefined) {
  if (value) params.set(key, value);
}

export function catalogHref(workspaceId: string, location: CatalogLocation): string {
  const params = new URLSearchParams({ connector: location.connector });
  appendIfPresent(params, "catalog", location.catalog);
  appendIfPresent(params, "schema", location.schema);
  if (location.schema) appendIfPresent(params, "table", location.table);
  if (location.choose) params.set("choose", "1");
  return `${webPaths.catalog(workspaceId)}?${params.toString()}`;
}

export function tableCatalogHref(workspaceId: string, table: TableAddress): string {
  return catalogHref(workspaceId, { connector: table.connectorId, catalog: table.catalogName, schema: table.schemaName, table: table.tableName });
}

function tableParams(table: TableAddress): URLSearchParams {
  const params = new URLSearchParams({ connector: table.connectorId });
  appendIfPresent(params, "catalog", table.catalogName);
  params.set("schema", table.schemaName);
  params.set("table", table.tableName);
  return params;
}

export function exploreTableHref(workspaceId: string, table: TableAddress): string {
  return `${webPaths.explore(workspaceId)}?${tableParams(table).toString()}`;
}

export function newSemanticModelFromTableHref(workspaceId: string, table: TableAddress): string {
  return `${webPaths.newSemanticModel(workspaceId)}?${tableParams(table).toString()}`;
}

export function lineageRelationHref(workspaceId: string, relationResourceId: string): string {
  return `${webPaths.lineage(workspaceId)}?${new URLSearchParams({ kind: "RELATION", id: relationResourceId }).toString()}`;
}

export function qualifiedTableName(table: Pick<TableAddress, "catalogName" | "schemaName" | "tableName">): string {
  return [table.catalogName, table.schemaName, table.tableName].filter(Boolean).join(".");
}

// Lineage addresses a Catalog table by its dot-joined name. Split it only when the part count is
// unambiguous for the connector's depth; a name containing dots falls back to the connector.
export function tableAddressFromQualifiedName(connectorId: string, connectorType: ConnectorType, qualifiedName: string): TableAddress | null {
  const parts = qualifiedName.split(".");
  const threeDepth = THREE_DEPTH_CONNECTOR_TYPES.has(connectorType);
  if (threeDepth && parts.length === 3) return { connectorId, catalogName: parts[0], schemaName: parts[1], tableName: parts[2] };
  if (!threeDepth && parts.length === 2) return { connectorId, catalogName: null, schemaName: parts[0], tableName: parts[1] };
  return null;
}

function propertyText(connector: Pick<Connector, "properties">, key: string): string | null {
  const value = (connector.properties as Record<string, unknown>)[key];
  return typeof value === "string" && value.trim() ? value : null;
}

// The catalog a 3-depth connector names in its settings; the selection panel pre-checks it.
export function configuredCatalogName(connector: Pick<Connector, "type" | "properties">): string | null {
  switch (connector.type) {
    case "SNOWFLAKE": return propertyText(connector, "database");
    case "BIGQUERY": return propertyText(connector, "projectId");
    case "DATABRICKS":
    case "TRINO": return propertyText(connector, "catalog");
    default: return null;
  }
}

// Where the first listing looked, for "Found N schemas in analytics".
export function connectorLocationLabel(connector: Pick<Connector, "type" | "name" | "properties">): string {
  switch (connector.type) {
    case "DUCKDB": return "Acme Cloud";
    case "POSTGRESQL":
    case "REDSHIFT": return propertyText(connector, "database") ?? connector.name;
    case "SNOWFLAKE": return propertyText(connector, "account") ?? connector.name;
    case "DATABRICKS":
    case "TRINO": return propertyText(connector, "host") ?? connector.name;
    case "BIGQUERY": return connector.name;
  }
}

const LAST_CONNECTOR_KEY_PREFIX = "monomer.catalog.lastConnector.";

export function readLastCatalogConnector(workspaceId: string): string | null {
  try {
    return window.localStorage.getItem(`${LAST_CONNECTOR_KEY_PREFIX}${workspaceId}`);
  } catch {
    return null;
  }
}

export function rememberLastCatalogConnector(workspaceId: string, connectorId: string): void {
  try {
    window.localStorage.setItem(`${LAST_CONNECTOR_KEY_PREFIX}${workspaceId}`, connectorId);
  } catch {
    // Browser storage can be unavailable; the Catalog then opens the first connector.
  }
}

// Only same-app workspace paths may be returned to after editing a connector.
export function safeReturnTo(workspaceId: string, returnTo: string | undefined): string | null {
  if (!returnTo || !returnTo.startsWith(`${webPaths.workspace(workspaceId)}/`) || returnTo.startsWith("//")) return null;
  try {
    const parsed = new URL(returnTo, "https://monomer.invalid");
    return parsed.origin === "https://monomer.invalid" ? `${parsed.pathname}${parsed.search}` : null;
  } catch {
    return null;
  }
}

export type ConnectorEditMode = "revise" | "rotate";

export function connectorEditHref(workspaceId: string, connectorId: string, mode: ConnectorEditMode, returnTo: string | null): string {
  const params = new URLSearchParams({ connector: connectorId, mode });
  if (returnTo) params.set("returnTo", returnTo);
  return `${webPaths.connectors(workspaceId)}?${params.toString()}`;
}
