import type { ExploreResult, ExploreRun, SemanticFilter, SemanticGroupBy, SemanticQuerySpec } from "@/api/explore";
import { partitionDimensionLabel, partitionRelationName, queryPartitionHints, type PartitionFilter, type PartitionRepairAction } from "@/components/semantics/source-partition";
import { emptyExploreDraft } from "./explore-draft";
import type { ExploreErrorPresentation } from "@/api/explore-errors";
import type { SemanticModelResponse } from "@/api/semantic-model";
import type { Connector } from "@/lib/types/connector";
import type { Metric } from "@/lib/types/metric";
import { webPaths } from "@/lib/web-paths";
import { filterOperatorWords } from "./semantic-filters";
import { problemReference, semanticProblemGuidance, semanticReasonCodes, type FailureAction, type FailureGuidance } from "./semantic-problems";
import { formatMetricTimeRange } from "./time-grain-format";

const integer = new Intl.NumberFormat("en-US");

export function formatCount(value: number): string {
  return integer.format(value);
}

export function granularityWord(granularity: string): string {
  return granularity.charAt(0) + granularity.slice(1).toLowerCase();
}

export function metricLabel(metricName: string, metrics: Metric[]): string {
  const metric = metrics.find((candidate) => candidate.name === metricName);
  return metric?.label || metric?.name || metricName;
}

function elementLabel(groupBy: Exclude<SemanticGroupBy, { kind: "METRIC_TIME" }>, models: SemanticModelResponse[]): string {
  const model = models.find((candidate) => candidate.name === groupBy.semanticModelName);
  const element = model?.dimensions.find((candidate) => candidate.name === groupBy.name)
    ?? model?.entities.find((candidate) => candidate.name === groupBy.name);
  return element?.label || groupBy.name;
}

/** A group-by's name without its grain: "Metric time", "Issued at", "Region". */
export function groupByBaseLabel(groupBy: SemanticGroupBy, models: SemanticModelResponse[]): string {
  return groupBy.kind === "METRIC_TIME" ? "Metric time" : elementLabel(groupBy, models);
}

/** Composer token text: "Metric time · Month", "Issued at · Month", "Region". */
export function groupByTokenLabel(groupBy: SemanticGroupBy, models: SemanticModelResponse[]): string {
  const label = groupByBaseLabel(groupBy, models);
  if (groupBy.kind === "METRIC_TIME") return `${label} · ${granularityWord(groupBy.granularity)}`;
  return groupBy.kind === "DIMENSION" && groupBy.granularity ? `${label} · ${granularityWord(groupBy.granularity)}` : label;
}

/** Title wording: a time grouping reads as its grain ("Month"), any other grouping as its label. */
export function groupByTitleLabel(groupBy: SemanticGroupBy, models: SemanticModelResponse[]): string {
  if (groupBy.kind === "METRIC_TIME") return granularityWord(groupBy.granularity);
  if (groupBy.kind === "DIMENSION" && groupBy.granularity) return granularityWord(groupBy.granularity);
  return elementLabel(groupBy, models);
}

export function semanticQueryTitle(query: Pick<SemanticQuerySpec, "metrics" | "groupBys">, metrics: Metric[], models: SemanticModelResponse[]): string {
  const measured = query.metrics.map((metricName) => metricLabel(metricName, metrics)).join(", ") || "Semantic query";
  const grouped = query.groupBys.map((groupBy) => groupByTitleLabel(groupBy, models));
  return grouped.length > 0 ? `${measured} by ${grouped.join(", ")}` : measured;
}

const SQL_PREFIX_LENGTH = 24;

function unquoteIdentifier(part: string): string {
  return part.replace(/^[`"[]|[`"\]]$/g, "");
}

/** "invoices · SELECT status, count(*)…": the first table after FROM and a short SQL prefix. */
export function sqlRunTitle(sql: string): string {
  const normalized = sql.replace(/--[^\n]*/g, " ").replace(/\/\*[\s\S]*?\*\//g, " ").replace(/\s+/g, " ").trim();
  if (!normalized) return "SQL query";
  const prefix = normalized.length > SQL_PREFIX_LENGTH ? `${normalized.slice(0, SQL_PREFIX_LENGTH).trimEnd()}…` : normalized;
  const from = normalized.match(/\bfrom\s+((?:`[^`]+`|"[^"]+"|\[[^\]]+\]|[A-Za-z_][\w$-]*)(?:\s*\.\s*(?:`[^`]+`|"[^"]+"|\[[^\]]+\]|[A-Za-z_][\w$-]*))*)/i);
  if (!from) return prefix;
  const parts = from[1].split(/\s*\.\s*/);
  const table = unquoteIdentifier(parts[parts.length - 1] as string).split(".").pop() as string;
  return `${table} · ${prefix}`;
}

export function exploreRunTitle(run: ExploreRun, metrics: Metric[], models: SemanticModelResponse[]): string {
  if (run.kind === "SQL") return sqlRunTitle(run.sql);
  return run.query ? semanticQueryTitle(run.query, metrics, models) : "Semantic query";
}

export const DELETED_CONNECTOR_NAME = "Deleted connector";

/** `connectors` is null until the workspace list has loaded, so an unknown id is not yet a deleted one. */
export function connectorName(connectorId: string, connectors: Connector[] | null): string {
  if (connectors === null) return "Connector";
  return connectors.find((connector) => connector.id === connectorId)?.name ?? DELETED_CONNECTOR_NAME;
}

function filterSummary(filter: SemanticFilter, models: SemanticModelResponse[]): string {
  const dimension = models.find((model) => model.name === filter.dimension.semanticModelName)
    ?.dimensions.find((candidate) => candidate.name === filter.dimension.name);
  const values = filter.values.map(String).join(", ");
  return `${dimension?.label || filter.dimension.name} ${filterOperatorWords(filter.operator, filter.kind)}${values ? ` ${values}` : ""}`;
}

/** What tells runs of the same question apart: the first filter, the time range, then the connector. */
export function exploreRunSubtitle(run: ExploreRun, connectors: Connector[] | null, models: SemanticModelResponse[]): string {
  const name = connectorName(run.connectorId, connectors);
  if (run.kind === "SQL") return `SQL · ${name}`;
  const query = run.query;
  if (!query) return name;
  const parts: string[] = [];
  const [first, ...rest] = query.filters;
  if (first) parts.push(`${filterSummary(first, models)}${rest.length > 0 ? ` +${rest.length}` : ""}`);
  if (query.metricTimeRange) parts.push(formatMetricTimeRange(query.metricTimeRange, undefined));
  parts.push(name);
  return parts.join(" · ");
}

export interface ResultCaption {
  /** The one fact that matters while reading: how much of the result this preview holds. */
  status: string;
  /** What to do about a partial preview or a reached row limit; null when the preview is the whole result. */
  detail: string | null;
  /** True when the preview holds every row of the result. */
  complete: boolean;
  /** True when a Semantic query returned exactly its row limit, so the warehouse may have more. */
  rowLimitReached: boolean;
  totalRowCount: number;
}

export function resultCaption(result: ExploreResult, totalRowCount: number | null, query: SemanticQuerySpec | null): ResultCaption {
  const total = totalRowCount ?? result.returnedRowCount;
  const previewComplete = total <= result.returnedRowCount && (totalRowCount !== null || result.stopReason === "END_OF_RESULT");
  const rowLimitReached = query !== null && total >= query.rowLimit;
  const rows = previewComplete
    ? `${formatCount(total)} ${total === 1 ? "row" : "rows"}`
    : `First ${formatCount(result.returnedRowCount)} of ${formatCount(total)} rows`;
  const previewNote = previewComplete ? null
    : result.stopReason === "BYTE_LIMIT"
      ? "The preview stopped at its size limit. Download the result for every row."
      : result.stopReason === "FIRST_BATCH_BOUNDARY"
        ? "The preview shows the first block of rows. Download the result for every row."
        : `The preview shows the first ${formatCount(result.returnedRowCount)} rows. Download the result for every row.`;
  const limitNote = rowLimitReached && query
    ? `The query stopped at its ${formatCount(query.rowLimit)}-row limit, so the warehouse may have more rows. Raise the limit to see them.`
    : null;
  return {
    status: rowLimitReached ? `${rows} · limit reached` : rows,
    detail: [limitNote, previewNote].filter(Boolean).join(" ") || null,
    complete: previewComplete && !rowLimitReached,
    rowLimitReached,
    totalRowCount: total,
  };
}

/**
 * "Times in Asia/Seoul": the zone a Semantic run grouped days and read times in, when its question uses
 * time. Runs recorded before zones were kept say nothing; their instants carry "UTC" themselves.
 */
export function resultTimeZoneCaption(run: ExploreRun): string | null {
  if (run.kind !== "SEMANTIC" || !run.reportingTimeZone || !run.query) return null;
  const { groupBys, metricTimeRange, filters } = run.query;
  const usesTime = metricTimeRange !== null
    || groupBys.some((groupBy) => groupBy.kind === "METRIC_TIME" || (groupBy.kind === "DIMENSION" && groupBy.granularity))
    || filters.some((filter) => filter.kind === "DATE" || filter.kind === "TIMESTAMP");
  return usesTime ? `Times in ${run.reportingTimeZone}` : null;
}

interface GuidanceContext {
  workspaceId: string;
  connectorId: string | null;
  connectors: Connector[] | null;
  metricNames: string[];
  metrics: Metric[];
  models: SemanticModelResponse[];
  canEditDefinitions?: boolean;
  onPartitionRepair?: (action: PartitionRepairAction) => void;
}

function metricActions(context: GuidanceContext): FailureAction[] {
  const named = context.metricNames
    .map((metricName) => context.metrics.find((metric) => metric.name === metricName))
    .filter((metric): metric is Metric => Boolean(metric))
    .slice(0, 3);
  if (named.length === 1) return [{ label: "Open metric", href: webPaths.metric(context.workspaceId, named[0].id) }];
  return named.map((metric) => ({ label: `Open ${metric.label || metric.name}`, href: webPaths.metric(context.workspaceId, metric.id) }));
}

function connectorAction(context: GuidanceContext): FailureAction[] {
  return context.connectorId && context.connectors?.some((connector) => connector.id === context.connectorId)
    ? [{ label: "Open connector", href: `${webPaths.connectors(context.workspaceId)}?connector=${encodeURIComponent(context.connectorId)}` }]
    : [];
}

function timeSpineAction(context: GuidanceContext): FailureAction[] {
  const query = context.connectorId ? `&connectorId=${encodeURIComponent(context.connectorId)}` : "";
  return [{ label: "Set up time spine", href: `${webPaths.semantics(context.workspaceId)}?view=time-spines${query}` }];
}

function partitionGuidance(filters: PartitionFilter[], context: GuidanceContext, reference: string | null): FailureGuidance {
  const filter = filters[0];
  if (!filter) return { title: "This query needs a filter on the partition column of a table it reads.", explanation: "Set a time range or add a filter on the partition column, then run again.", actions: [], reference };
  if (filter.kind === "TIME_SPINE") return { title: `Time spine ${partitionRelationName(filter.relation)} needs a filter on ${filter.partitionKeys.join(", ") || "its partition column"}.`, explanation: "Choose a time spine table that can be read without a partition filter.", actions: timeSpineAction(context), reference };
  const model = context.models.find((candidate) => candidate.name === filter.semanticModelName);
  const label = model?.label || filter.semanticModelName;
  const needsDimension = filter.dimensions.length === 0;
  const title = `${label} reads a table that needs a filter on ${filter.partitionKeys.join(", ") || "its partition column"}.${needsDimension ? " The model has no dimension for it." : ""}`;
  const actions: FailureAction[] = [];
  if (needsDimension && model && context.canEditDefinitions) actions.push({ label: "Open model", href: `${webPaths.semanticModel(context.workspaceId, model.id)}?edit=true` });
  if (!needsDimension && context.onPartitionRepair) actions.push({ label: filter.timeRangeApplies ? "Set time range" : "Add filter", onClick: () => context.onPartitionRepair?.(filter.timeRangeApplies ? { kind: "TIME_RANGE" } : { kind: "FILTER", semanticModelName: filter.semanticModelName, dimensionName: filter.dimensions[0] }) });
  const tableCount = new Set(filters.map(({ connectorId, relation }) => JSON.stringify([connectorId, relation.catalog ?? null, relation.schema, relation.name]))).size;
  const remaining = tableCount > 1 ? ` ${tableCount - 1} other ${tableCount === 2 ? "table also needs" : "tables also need"} a partition filter.` : "";
  return { title, explanation: (needsDimension ? context.canEditDefinitions ? "Add a dimension that uses the partition column." : "Ask an editor to add it." : filter.timeRangeApplies ? "Set a time range to run." : `Add a filter on ${partitionDimensionLabel(filter, context.models)}.`) + remaining, actions, reference };
}

const CONNECTOR_CODES = new Set(["PERMISSION_DENIED", "AUTHENTICATION_FAILED", "CREDENTIAL_UNAVAILABLE", "CONNECTION_FAILED"]);
const SELECTION_CODES = new Set(["QUERY_GROUP_BY_NOT_FOUND", "GROUP_BY_KIND_MISMATCH", "UNKNOWN_FILTER_TARGET", "QUERY_FILTER_UNRENDERABLE"]);

/** A rejected start: the server names Semantic reasons in the problem detail as stable codes. */
export function startRejectionGuidance(error: ExploreErrorPresentation, mode: "SEMANTIC" | "SQL", context: GuidanceContext): FailureGuidance {
  const name = context.connectorId ? connectorName(context.connectorId, context.connectors) : "this connector";
  const reasons = semanticReasonCodes(error);
  if (error.code === "PARTITION_FILTER_REQUIRED") return mode === "SEMANTIC" ? partitionGuidance(error.partitionFilters ?? [], context, problemReference(error.code, error.traceId)) : { title: "This query needs a filter on the partition column of a table it reads.", explanation: "Add a filter on the partition column in SQL, then run again.", actions: [], reference: problemReference(error.code, error.traceId) };
  const ref = problemReference(error.code, error.traceId);
  if (mode === "SEMANTIC" && reasons.has("TIME_SPINE_UNAVAILABLE")) {
    return {
      title: `This query needs a time spine on ${name}.`,
      explanation: "Cumulative metrics and some time groupings count across every day, so Monomer needs a table with one row per day.",
      actions: [...timeSpineAction(context), ...metricActions(context)],
      reference: problemReference("TIME_SPINE_UNAVAILABLE", error.traceId),
    };
  }
  if (mode === "SEMANTIC" && reasons.has("RELATIONSHIP_JOIN")) {
    return { title: "These group-bys need a join between models that Monomer can’t run yet.", explanation: "Choose group-bys from the metric’s own model.", actions: metricActions(context), reference: ref };
  }
  if (mode === "SEMANTIC" && reasons.has("UNSUPPORTED_DIALECT")) {
    return { title: `Metrics can’t run on ${name} yet.`, explanation: "This connector’s SQL dialect isn’t supported for metrics. Use SQL mode to query its tables.", actions: [], reference: ref };
  }
  const semantic = mode === "SEMANTIC"
    ? semanticProblemGuidance(error, { workspaceId: context.workspaceId, connectorId: context.connectorId, connectorName: name, metrics: context.metrics, models: context.models })
    : null;
  if (semantic) return semantic;
  if (mode === "SEMANTIC" && [...reasons].some((reason) => SELECTION_CODES.has(reason))) {
    return { title: "A group-by or filter no longer matches the metric definitions.", explanation: "Remove it from the query, or open the metric to check its model.", actions: metricActions(context), reference: ref };
  }
  if (mode === "SQL" && error.code !== null && CONNECTOR_CODES.has(error.code)) {
    return { title: error.detail, explanation: error.retryable ? "You can try this request again." : null, actions: connectorAction(context), reference: ref };
  }
  return { title: error.detail, explanation: error.retryable ? "You can try this request again." : null, actions: [], reference: ref };
}

/** A run that was accepted and then failed in the warehouse. */
export function failedRunGuidance(run: ExploreRun, context: GuidanceContext): FailureGuidance {
  const name = connectorName(run.connectorId, context.connectors);
  const ref = problemReference(run.failureCode, `run ${run.runId}`);
  if (run.failureCode === "PARTITION_FILTER_REQUIRED") {
    if (run.kind === "SQL") return { title: "This query needs a filter on the partition column of a table it reads.", explanation: "Add a filter on the partition column in SQL, then run again.", actions: [], reference: ref };
    const filters = run.kind === "SEMANTIC" && run.query ? queryPartitionHints({ ...emptyExploreDraft().semantic, ...run.query, connectorId: run.connectorId }, run.connectorId, context.metrics, context.models, true) : [];
    return partitionGuidance(filters, context, ref);
  }
  if (run.failureCode === "PERMISSION_DENIED") {
    return {
      title: `The warehouse account on ${name} isn’t allowed to run this query.`,
      explanation: run.failureDetail,
      actions: connectorAction(context),
      reference: ref,
    };
  }
  if (run.failureCode === "CONNECTION_FAILED") {
    return { title: `Monomer couldn’t connect to ${name}.`, explanation: run.failureDetail, actions: connectorAction(context), reference: ref };
  }
  if (run.failureCode === "AUTHENTICATION_FAILED") {
    return { title: `${name} rejected the warehouse sign-in.`, explanation: run.failureDetail, actions: connectorAction(context), reference: ref };
  }
  if (run.failureCode === "EXECUTION_ORPHANED") {
    return { title: "The run stopped reporting progress and was closed.", explanation: "Run it again.", actions: [], reference: ref };
  }
  return {
    title: run.failureDetail || "The warehouse couldn’t finish this query.",
    explanation: run.kind === "SEMANTIC"
      ? "If the warehouse stopped at a time limit, narrow the time range or add a filter and run again. If it names a missing column or table, the metric definition may need an update."
      : "If the warehouse stopped at a time limit, narrow the query and run it again.",
    actions: run.kind === "SEMANTIC" ? metricActions(context) : [],
    reference: ref,
  };
}
