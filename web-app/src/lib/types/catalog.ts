import type { AgentApp } from "@/lib/types/provenance";
import type { ConnectorType } from "@/lib/types/connector";

export interface CatalogSummary {
  name: string;
  lastRefreshedAt?: number | null;
}

export interface SchemaSummary {
  catalogName?: string | null;
  name: string;
  lastRefreshedAt?: number | null;
}

export type RelationKind =
  | "TABLE"
  | "VIEW"
  | "MATERIALIZED_VIEW"
  | "EXTERNAL"
  | "FOREIGN"
  | "DYNAMIC"
  | "SNAPSHOT"
  | "UNKNOWN";

export interface RelationSummary {
  catalogName?: string | null;
  schemaName: string;
  name: string;
  kind: RelationKind;
  columnCount: number;
  lastRefreshedAt?: number | null;
}

export interface ColumnInfo {
  name: string;
  nativeType: string;
  ordinalPosition: number;
  nullable: boolean;
  defaultValue?: string | null;
}

export interface PrimaryKeyInfo {
  name?: string | null;
  columns: string[];
}

export interface ForeignKeyInfo {
  name?: string | null;
  columns: string[];
  referencedConnectorId: string;
  referencedCatalogName?: string | null;
  referencedSchemaName: string;
  referencedRelationName: string;
  referencedColumns: string[];
}

export interface RelationDetail {
  lineageResourceRef?: { kind: "catalog_table" | "catalog_view"; id: string };
  catalogName?: string | null;
  schemaName: string;
  name: string;
  kind: RelationKind;
  viewDefinition?: string | null;
  columns: ColumnInfo[];
  partitionKeys: string[];
  requiresPartitionFilter: boolean | null;
  clusteringKeys: string[];
  primaryKey?: PrimaryKeyInfo | null;
  foreignKeys: ForeignKeyInfo[];
}

export interface SchemaListPage {
  schemas: SchemaSummary[];
  totalCount: number;
  page: number;
  pageSize: number;
}

export interface RelationListPage {
  relations: RelationSummary[];
  totalCount: number;
  page: number;
  pageSize: number;
}

export type CatalogRelationCoverage =
  | { kind: "ALL_RELATIONS" }
  | { kind: "SELECTED_RELATIONS"; relationNames: string[] };

export interface CatalogSelectedSchema {
  schemaName: string;
  relations: CatalogRelationCoverage;
}

export interface CatalogSelection {
  selectedSchemas: CatalogSelectedSchema[];
}

export interface CatalogSelectionView {
  selectedSchemas: CatalogSelectedSchema[];
  committedAt?: string;
}

export interface ConnectorCatalogSelection {
  selectedCatalogNames: string[];
}

export interface ConnectorCatalogSelectionView extends ConnectorCatalogSelection {
  committedAt?: string;
}

export type CatalogIngestJobState =
  | "RUNNING"
  | "CANCEL_REQUESTED"
  | "COMPLETED"
  | "COMPLETED_WITH_ERRORS"
  | "CANCELLED";

export type CatalogIngestTaskState =
  | "RUNNING"
  | "SUCCEEDED"
  | "FAILED"
  | "CANCELLED";

export type CatalogIngestTaskTargetKind = "CONNECTOR" | "CATALOG" | "SCHEMA" | "RELATION";

export interface CatalogIngestTask {
  taskId: string;
  parentTaskId: string | null;
  targetKind: CatalogIngestTaskTargetKind;
  catalogName: string | null;
  schemaName: string | null;
  relationName: string | null;
  relationKind: RelationKind | null;
  state: CatalogIngestTaskState;
  loading: boolean;
  failureCode: string | null;
  safeMessage: string | null;
}

export interface CatalogRequester {
  principalId: string;
  displayName: string | null;
  credentialSurface: "WEB" | "AGENT" | null;
  /** The agent app that started an AGENT job; null for Web jobs and older agent jobs. */
  agentApp: AgentApp | null;
}

export interface CatalogIngestJob {
  jobId: string;
  connectorId: string;
  state: CatalogIngestJobState;
  entryKind: "CONNECTOR" | "CATALOG";
  catalogName: string | null;
  requestedAt: string;
  terminalAt: string | null;
  refreshedRelationCount: number;
  runningTaskCount: number;
  errorCount: number;
  requestedBy: CatalogRequester;
  tasks: CatalogIngestTask[];
}

export interface CatalogIngestJobSummary {
  jobId: string;
  connectorId: string;
  state: CatalogIngestJobState;
  entryKind: "CONNECTOR" | "CATALOG";
  catalogName: string | null;
  requestedAt: string;
  terminalAt: string | null;
  refreshedRelationCount: number;
  runningTaskCount: number;
  errorCount: number;
  requestedBy: CatalogRequester;
}

export interface CatalogIngestJobPage {
  jobs: CatalogIngestJobSummary[];
  totalCount: number;
  page: number;
  pageSize: number;
}

export interface CatalogIngestErrorPage {
  errors: CatalogIngestTask[];
  totalCount: number;
  page: number;
  pageSize: number;
}

export interface CatalogIngestAccepted {
  jobId: string;
  workspaceId: string;
  connectorId: string;
  entryKind: "CONNECTOR" | "CATALOG";
  catalogName: string | null;
}

export type CatalogListRefreshParentKind = "CONNECTOR" | "CATALOG" | "SCHEMA";
export type CatalogListRefreshChildKind = "CATALOG" | "SCHEMA" | "RELATION";

export type CatalogListRefreshState =
  | "RUNNING"
  | "CANCEL_REQUESTED"
  | "COMPLETED"
  | "COMPLETED_WITH_ERRORS"
  | "CANCELLED";

export interface CatalogListRefreshJob {
  jobId: string;
  parentKind: CatalogListRefreshParentKind;
  childKind: CatalogListRefreshChildKind;
  catalogName: string | null;
  schemaName: string | null;
  state: CatalogListRefreshState;
  requestedAt: string;
  terminalAt: string | null;
  failureCode: string | null;
  safeMessage: string | null;
}

export interface CatalogListOption {
  name: string;
  relationKind: RelationKind | null;
  available: boolean;
}

export interface CatalogListOptionPage {
  parentKind: CatalogListRefreshParentKind;
  childKind: CatalogListRefreshChildKind;
  catalogName: string | null;
  schemaName: string | null;
  fetchedAt: string | null;
  options: CatalogListOption[];
  totalCount: number;
  page: number;
  pageSize: number;
  latestJob: CatalogListRefreshJob | null;
}

export interface CatalogConnectorLastRefresh {
  jobId: string;
  state: CatalogIngestJobState;
  catalogName: string | null;
  requestedAt: string;
  finishedAt: string | null;
  errorCount: number;
  // Non-null when the connector or catalog entry itself failed, so nothing below it was refreshed.
  entryFailureCode: string | null;
}

export interface CatalogConnectorSummary {
  connectorId: string;
  selectedCatalogCount: number;
  selectedSchemaCount: number;
  tableCount: number;
  lastRefresh: CatalogConnectorLastRefresh | null;
  lastSuccessfulRefreshAt: string | null;
}

export interface CatalogTableSearchHit {
  connectorId: string;
  connectorName: string;
  connectorType: ConnectorType;
  catalogName: string | null;
  schemaName: string;
  tableName: string;
  relationKind: RelationKind;
  matchedColumns: string[];
}
