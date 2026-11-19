import type { AgentApp, RecordedActor } from "@/lib/types/provenance";
import type { MetricDisplay } from "@/lib/metric-display";
export const EXPLORE_RUN_STATES = [
  "RUNNING",
  "SUCCEEDED",
  "FAILED",
  "CANCELLED",
] as const;

export type ExploreRunState = (typeof EXPLORE_RUN_STATES)[number];

export type ExploreRunKind = "SQL" | "SEMANTIC";
/** Exact run reads say whether a result preview exists; list, start, and cancel views carry none. */
export type ExploreResultState = "AVAILABLE" | "EXPIRED" | "PREVIEW_UNAVAILABLE" | "NONE";
export type ExploreRunCredentialSurface = "WEB" | "AGENT";

export interface ExploreResultColumn {
  ordinal: number;
  name: string;
  logicalType: string;
  nullable: boolean;
  display?: MetricDisplay | null;
}

export interface ExploreResult {
  columns: ExploreResultColumn[];
  rows: Array<Array<string | boolean | null>>;
  returnedRowCount: number;
  stopReason: string;
}

export interface ExploreResultMetadata {
  byteSize: string;
  mediaType: string;
  rowCount: string;
  columnCount: number;
  expiresAt: string;
}

interface ExploreRunBase {
  runId: string;
  kind: ExploreRunKind;
  workspaceId: string;
  connectorId: string;
  requestDigest: string;
  state: ExploreRunState;
  revision?: number;
  resultAvailable?: boolean;
  failureCode: string | null;
  failureDetail: string | null;
  createdAt: string;
  updatedAt: string;
  resultMetadata: ExploreResultMetadata | null;
  result: ExploreResult | null;
  /** Null when the view came from a list, start, or cancel response rather than an exact read. */
  resultState: ExploreResultState | null;
  /** SQL the run was committed to execute: submitted SQL, or the SQL rendered at Semantic launch. */
  sql: string;
  /** The person whose Web session or agent connection started the run; null only when a response omitted it. */
  startedBy: RecordedActor | null;
  credentialSurface: ExploreRunCredentialSurface | null;
  /** The agent app that started an AGENT run; null for Web runs and older agent runs. */
  agentApp: AgentApp | null;
}

export interface SqlExploreRun extends ExploreRunBase {
  kind: "SQL";
}

export interface SemanticExploreRun extends ExploreRunBase {
  kind: "SEMANTIC";
  query?: SemanticQuerySpec;
  /** The IANA zone the run's SQL was rendered in; null for runs recorded before zones were kept. */
  reportingTimeZone: string | null;
}

export type ExploreRun = SqlExploreRun | SemanticExploreRun;

export interface StartSqlExploreRunRequest {
  connectorId: string;
  sql: string;
}

export const SEMANTIC_QUERY_SCHEMA_VERSION = 4 as const;
export const SEMANTIC_TIME_GRANULARITIES = [
  "NANOSECOND",
  "MICROSECOND",
  "MILLISECOND",
  "SECOND",
  "MINUTE",
  "HOUR",
  "DAY",
  "WEEK",
  "MONTH",
  "QUARTER",
  "YEAR",
] as const;

export type SemanticTimeGranularity = (typeof SEMANTIC_TIME_GRANULARITIES)[number];

export interface SemanticDimensionReference {
  semanticModelName: string;
  name: string;
}

export interface SemanticDimensionGroupBy {
  kind: "DIMENSION";
  semanticModelName: string;
  name: string;
  granularity?: SemanticTimeGranularity | null;
}

export interface SemanticEntityGroupBy {
  kind: "ENTITY";
  semanticModelName: string;
  name: string;
}

export interface SemanticMetricTimeGroupBy {
  kind: "METRIC_TIME";
  granularity: SemanticTimeGranularity;
}

export type SemanticGroupBy =
  | SemanticDimensionGroupBy
  | SemanticEntityGroupBy
  | SemanticMetricTimeGroupBy;

export interface SemanticMetricTimeRangeEndpoint {
  kind: "DATE" | "TIMESTAMP";
  value: string;
}

export interface SemanticMetricTimeRange {
  start: SemanticMetricTimeRangeEndpoint;
  end: SemanticMetricTimeRangeEndpoint;
}

type SemanticNullableFilterOperator = "IS_NULL" | "IS_NOT_NULL";
type SemanticEqualityFilterOperator = "EQUALS" | "NOT_EQUALS" | "IN" | "NOT_IN";
type SemanticOrderedFilterOperator =
  | SemanticEqualityFilterOperator
  | "GREATER_THAN"
  | "GREATER_THAN_OR_EQUAL"
  | "LESS_THAN"
  | "LESS_THAN_OR_EQUAL";

export type SemanticFilter =
  | {
    kind: "TEXT";
    dimension: SemanticDimensionReference;
    operator: SemanticEqualityFilterOperator | SemanticNullableFilterOperator;
    values: string[];
  }
  | {
    kind: "NUMBER" | "DATE" | "TIMESTAMP";
    dimension: SemanticDimensionReference;
    operator: SemanticOrderedFilterOperator | SemanticNullableFilterOperator;
    values: string[];
  }
  | {
    kind: "BOOLEAN";
    dimension: SemanticDimensionReference;
    operator: "EQUALS" | "NOT_EQUALS" | SemanticNullableFilterOperator;
    values: boolean[];
  };

export interface SemanticQueryOrder {
  target: {
    kind: "METRIC";
    metricName: string;
  } | {
    kind: "GROUP_BY";
    groupBy: SemanticGroupBy;
  };
  direction: "ASC" | "DESC";
}

export interface SemanticQuerySpec {
  schemaVersion: typeof SEMANTIC_QUERY_SCHEMA_VERSION;
  metrics: string[];
  groupBys: SemanticGroupBy[];
  metricTimeRange: SemanticMetricTimeRange | null;
  filters: SemanticFilter[];
  orderBy: SemanticQueryOrder[];
  rowLimit: number;
}

export interface StartSemanticExploreRunRequest {
  connectorId: string;
  query: SemanticQuerySpec;
}

export type SemanticSqlDialect =
  | "POSTGRESQL"
  | "BIGQUERY"
  | "SNOWFLAKE"
  | "DATABRICKS"
  | "REDSHIFT"
  | "TRINO";

export interface SemanticSqlRender {
  dialect: SemanticSqlDialect;
  sql: string;
}

/** A model left out of a question's options because the engine rejected its definition. */
export interface SemanticExcludedModel {
  semanticModelName: string;
  message: string;
}

export interface SemanticDimensionFilterType {
  dimension: SemanticDimensionReference;
  kind: "TEXT" | "NUMBER" | "BOOLEAN";
}

export interface SemanticGroupByOptionsResponse {
  groupBys: SemanticGroupBy[];
  excludedSemanticModels: SemanticExcludedModel[];
  dimensionFilterTypes: SemanticDimensionFilterType[];
}
