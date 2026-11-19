import type { AgentApp, RecordedActor } from "@/lib/types/provenance";
import { apiFetch } from "@/lib/api/client";
import { CONTROL_API_URL } from "@/lib/auth/config";
import { SEMANTIC_QUERY_SCHEMA_VERSION } from "@/lib/types/explore";
export { SEMANTIC_QUERY_SCHEMA_VERSION } from "@/lib/types/explore";
export type {
  ExploreResult,
  ExploreRun,
  SemanticGroupBy,
  SemanticQuerySpec,
  SemanticSqlRender,
  SemanticTimeGranularity,
  SemanticMetricTimeRange,
  SemanticQueryOrder,
  SemanticFilter,
  SemanticExcludedModel,
  SemanticDimensionFilterType,
} from "@/lib/types/explore";
import type {
  ExploreResult,
  ExploreRun,
  ExploreRunCredentialSurface,
  ExploreResultState,
  ExploreRunKind,
  ExploreRunState,
  SemanticGroupBy,
  SemanticExcludedModel,
  SemanticDimensionFilterType,
  SemanticGroupByOptionsResponse,
  SemanticQuerySpec,
  SemanticSqlRender,
  SemanticTimeGranularity,
  SemanticMetricTimeRange,
  SemanticQueryOrder,
  SemanticExploreRun,
  SemanticFilter,
  SqlExploreRun,
  StartSemanticExploreRunRequest,
  StartSqlExploreRunRequest,
} from "@/lib/types/explore";

const workspaceExploreUrl = (workspaceId: string): string =>
  `${CONTROL_API_URL}/v2/workspaces/${encodeURIComponent(workspaceId)}/explore`;

const sqlRunsUrl = (workspaceId: string): string =>
  `${workspaceExploreUrl(workspaceId)}/sql-runs`;

const semanticRunsUrl = (workspaceId: string): string =>
  `${workspaceExploreUrl(workspaceId)}/semantic-runs`;

const runUrl = (workspaceId: string, runId: string): string =>
  `${workspaceExploreUrl(workspaceId)}/runs/${encodeURIComponent(runId)}`;

const cancelRunUrl = (workspaceId: string, runId: string): string =>
  `${runUrl(workspaceId, runId)}:cancel`;

const semanticGroupByOptionsUrl = (workspaceId: string): string =>
  `${CONTROL_API_URL}/v2/workspaces/${encodeURIComponent(workspaceId)}/semantic/query/group-bys`;

const semanticSqlRenderUrl = (workspaceId: string): string =>
  `${CONTROL_API_URL}/v2/workspaces/${encodeURIComponent(workspaceId)}/semantic/query/render-sql`;

const SEMANTIC_SQL_DIALECTS: readonly SemanticSqlRender["dialect"][] = [
  "POSTGRESQL",
  "BIGQUERY",
  "SNOWFLAKE",
  "DATABRICKS",
  "REDSHIFT",
  "TRINO",
];

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" ? value as Record<string, unknown> : {};
}

function parseResult(value: unknown): ExploreResult | null {
  if (!value || typeof value !== "object") return null;
  const source = asRecord(value);
  if (!Array.isArray(source.columns) || !Array.isArray(source.rows)) return null;
  return {
    columns: source.columns.map((column, index) => {
      const item = asRecord(column);
      return {
        ordinal: Number(item.ordinal ?? index + 1),
        name: String(item.name ?? `column_${index + 1}`),
        logicalType: String(item.logicalType ?? "UNKNOWN"),
        nullable: Boolean(item.nullable),
        display: (item.display ?? null) as NonNullable<ExploreResult>["columns"][number]["display"],
      };
    }),
    rows: source.rows.map((row) => Array.isArray(row)
      ? row.map((cell) =>
        cell === null || typeof cell === "string" || typeof cell === "boolean" ? cell : String(cell),
      )
      : []),
    returnedRowCount: Number(source.returnedRowCount ?? source.rows.length),
    stopReason: String(source.stopReason ?? "ROW_LIMIT"),
  };
}

function parseGroupBy(value: unknown): SemanticGroupBy | null {
  const source = asRecord(value);
  const kind = source.kind;
  if (kind === "METRIC_TIME") {
    return {
      kind,
      granularity: String(source.granularity) as SemanticTimeGranularity,
    };
  }
  if ((kind !== "DIMENSION" && kind !== "ENTITY") || typeof source.semanticModelName !== "string" || typeof source.name !== "string") {
    return null;
  }
  if (kind === "ENTITY") {
    return {
      kind,
      semanticModelName: source.semanticModelName,
      name: source.name,
    };
  }
  return {
    kind,
    semanticModelName: source.semanticModelName,
    name: source.name,
    granularity: source.granularity == null ? null : String(source.granularity) as SemanticTimeGranularity,
  };
}

function parseExcludedModel(value: unknown): SemanticExcludedModel | null {
  const source = asRecord(value);
  if (typeof source.semanticModelName !== "string" || typeof source.message !== "string") return null;
  return { semanticModelName: source.semanticModelName, message: source.message };
}

function parseDimensionFilterType(value: unknown): SemanticDimensionFilterType | null {
  const source = asRecord(value);
  const dimension = asRecord(source.dimension);
  if (typeof dimension.semanticModelName !== "string" || typeof dimension.name !== "string"
    || (source.kind !== "TEXT" && source.kind !== "NUMBER" && source.kind !== "BOOLEAN")) return null;
  return { dimension: { semanticModelName: dimension.semanticModelName, name: dimension.name }, kind: source.kind };
}

/** Orders share the group-by shape of discovery so a restored order matches its group-by token. */
function parseOrder(value: unknown): SemanticQueryOrder | null {
  const source = asRecord(value);
  const target = asRecord(source.target);
  const direction = source.direction === "DESC" ? "DESC" : source.direction === "ASC" ? "ASC" : null;
  if (!direction) return null;
  if (target.kind === "METRIC" && typeof target.metricName === "string") {
    return { target: { kind: "METRIC", metricName: target.metricName }, direction };
  }
  const groupBy = target.kind === "GROUP_BY" ? parseGroupBy(target.groupBy) : null;
  return groupBy ? { target: { kind: "GROUP_BY", groupBy }, direction } : null;
}

function parseQuery(value: unknown): SemanticQuerySpec | undefined {
  const source = asRecord(value);
  if (source.schemaVersion !== SEMANTIC_QUERY_SCHEMA_VERSION || !Array.isArray(source.metrics) || !Array.isArray(source.groupBys)) return undefined;
  const groupBys = source.groupBys
    .map(parseGroupBy)
    .filter((groupBy): groupBy is SemanticGroupBy => groupBy !== null);
  return {
    schemaVersion: SEMANTIC_QUERY_SCHEMA_VERSION,
    metrics: source.metrics.map(String),
    groupBys,
    metricTimeRange: source.metricTimeRange && typeof source.metricTimeRange === "object"
      ? source.metricTimeRange as SemanticMetricTimeRange
      : null,
    filters: Array.isArray(source.filters) ? source.filters as SemanticFilter[] : [],
    orderBy: Array.isArray(source.orderBy)
      ? source.orderBy.map(parseOrder).filter((order): order is SemanticQueryOrder => order !== null)
      : [],
    rowLimit: Number(source.rowLimit ?? 1000),
  };
}

function parseResultState(value: unknown): ExploreResultState | null {
  return value === "AVAILABLE" || value === "EXPIRED" || value === "PREVIEW_UNAVAILABLE" || value === "NONE" ? value : null;
}

function parseCredentialSurface(value: unknown): ExploreRunCredentialSurface | null {
  return value === "WEB" || value === "AGENT" ? value : null;
}

function parseRecordedActor(value: unknown): RecordedActor | null {
  const source = asRecord(value);
  if (typeof source.id !== "string" || source.id === "") return null;
  return { id: source.id, displayName: typeof source.displayName === "string" ? source.displayName : null };
}

function parseAgentApp(value: unknown): AgentApp | null {
  const source = asRecord(value);
  if (typeof source.clientId !== "string" || typeof source.name !== "string") return null;
  return { clientId: source.clientId, name: source.name, verifiedHost: typeof source.verifiedHost === "string" ? source.verifiedHost : null };
}

function parseRun(value: unknown): ExploreRun {
  const source = asRecord(value);
  const kind: ExploreRunKind = source.kind === "SEMANTIC" ? "SEMANTIC" : "SQL";
  const runId = String(source.runId ?? "");
  if (!runId) throw new Error("Explore run response did not include a run id.");
  const base = {
    kind,
    runId,
    workspaceId: String(source.workspaceId ?? ""),
    connectorId: String(source.connectorId ?? ""),
    requestDigest: String(source.requestDigest ?? ""),
    state: source.state as ExploreRunState,
    resultAvailable: typeof source.resultAvailable === "boolean" ? source.resultAvailable : undefined,
    failureCode: source.failureCode == null ? null : String(source.failureCode),
    failureDetail: source.failureDetail == null ? null : String(source.failureDetail),
    createdAt: String(source.createdAt ?? ""),
    updatedAt: String(source.updatedAt ?? ""),
    resultMetadata: source.resultMetadata && typeof source.resultMetadata === "object"
      ? {
        byteSize: String(asRecord(source.resultMetadata).byteSize ?? "0"),
        mediaType: String(asRecord(source.resultMetadata).mediaType ?? ""),
        rowCount: String(asRecord(source.resultMetadata).rowCount ?? "0"),
        columnCount: Number(asRecord(source.resultMetadata).columnCount ?? 0),
        expiresAt: String(asRecord(source.resultMetadata).expiresAt ?? ""),
      }
      : null,
    result: parseResult(source.result),
    resultState: parseResultState(source.resultState),
    sql: typeof source.sql === "string" ? source.sql : "",
    startedBy: parseRecordedActor(source.startedBy),
    credentialSurface: parseCredentialSurface(source.credentialSurface),
    agentApp: parseAgentApp(source.agentApp),
  };
  return kind === "SEMANTIC"
    ? { ...base, kind, query: parseQuery(source.query), reportingTimeZone: typeof source.reportingTimeZone === "string" ? source.reportingTimeZone : null }
    : { ...base, kind };
}

async function jsonResponse(response: Response): Promise<unknown> {
  return response.json();
}

async function startSqlRun({
  workspaceId,
  request,
  idempotencyKey,
}: {
  workspaceId: string;
  request: StartSqlExploreRunRequest;
  idempotencyKey: string;
}): Promise<SqlExploreRun> {
  const response = await apiFetch(sqlRunsUrl(workspaceId), {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Idempotency-Key": idempotencyKey,
    },
    body: JSON.stringify(request),
  });
  return parseRun(await jsonResponse(response)) as SqlExploreRun;
}

async function readRun({
  workspaceId,
  runId,
}: {
  workspaceId: string;
  runId: string;
}): Promise<ExploreRun> {
  const response = await apiFetch(runUrl(workspaceId, runId));
  return parseRun(await jsonResponse(response));
}

export const exploreAPI = {
  startSqlRun,

  startSemanticRun: async ({
    workspaceId,
    request,
    idempotencyKey,
  }: {
    workspaceId: string;
    request: StartSemanticExploreRunRequest;
    idempotencyKey: string;
  }): Promise<SemanticExploreRun> => {
    const response = await apiFetch(semanticRunsUrl(workspaceId), {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": idempotencyKey,
      },
      body: JSON.stringify(request),
    });
    return parseRun(await jsonResponse(response)) as SemanticExploreRun;
  },

  readRun,

  listRecentRuns: async ({ workspaceId }: { workspaceId: string }): Promise<ExploreRun[]> => {
    const response = await apiFetch(`${workspaceExploreUrl(workspaceId)}/runs`);
    const source = asRecord(await jsonResponse(response));
    return (Array.isArray(source.runs) ? source.runs : []).map((run) => parseRun(run));
  },

  downloadResult: ({ workspaceId, runId, format, signal }: {
    workspaceId: string;
    runId: string;
    format: "csv" | "arrow";
    signal: AbortSignal;
  }): Promise<Response> => apiFetch(`${runUrl(workspaceId, runId)}/result?format=${format}`, {
    signal,
    cache: "no-store",
    headers: { Accept: format === "csv" ? "text/csv" : "application/vnd.apache.arrow.file" },
  }),

  cancelRun: async ({
    workspaceId,
    runId,
  }: {
    workspaceId: string;
    runId: string;
  }): Promise<ExploreRun> => {
    const response = await apiFetch(cancelRunUrl(workspaceId, runId), { method: "POST" });
    return parseRun(await jsonResponse(response));
  },

  listSemanticGroupBys: async ({
    workspaceId,
    connectorId,
    metrics,
  }: {
    workspaceId: string;
    connectorId: string;
    metrics: string[];
  }): Promise<SemanticGroupByOptionsResponse> => {
    const response = await apiFetch(semanticGroupByOptionsUrl(workspaceId), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ connectorId, metrics }),
    });
    const value = asRecord(await jsonResponse(response));
    const rawGroupBys = Array.isArray(value.groupBys) ? value.groupBys : [];
    const rawExcluded = Array.isArray(value.excludedSemanticModels) ? value.excludedSemanticModels : [];
    const rawDimensionFilterTypes = Array.isArray(value.dimensionFilterTypes) ? value.dimensionFilterTypes : [];
    return {
      groupBys: rawGroupBys
        .map(parseGroupBy)
        .filter((groupBy): groupBy is SemanticGroupBy => groupBy !== null),
      excludedSemanticModels: rawExcluded
        .map(parseExcludedModel)
        .filter((model): model is SemanticExcludedModel => model !== null),
      dimensionFilterTypes: rawDimensionFilterTypes
        .map(parseDimensionFilterType)
        .filter((dimension): dimension is SemanticDimensionFilterType => dimension !== null),
    };
  },

  renderSemanticSql: async ({
    workspaceId,
    request,
  }: {
    workspaceId: string;
    request: StartSemanticExploreRunRequest;
  }): Promise<SemanticSqlRender> => {
    const response = await apiFetch(semanticSqlRenderUrl(workspaceId), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(request),
    });
    const value = asRecord(await jsonResponse(response));
    if (
      typeof value.dialect !== "string"
      || !SEMANTIC_SQL_DIALECTS.some((dialect) => dialect === value.dialect)
      || typeof value.sql !== "string"
      || value.sql.trim().length === 0
    ) {
      throw new Error("Semantic SQL render response is invalid.");
    }
    return {
      dialect: value.dialect as SemanticSqlRender["dialect"],
      sql: value.sql,
    };
  },
};

export function newExploreIdempotencyKey(kind: ExploreRunKind): string {
  return `explore-${kind.toLowerCase()}-${crypto.randomUUID()}`;
}
