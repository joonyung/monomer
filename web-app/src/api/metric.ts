import { apiFetch } from "@/lib/api/client";
import type { AgentApp, CredentialSurface, RecordedActor } from "@/lib/types/provenance";
import { semanticBffUrl } from "@/api/workspace-bff";
import type { MetricDisplay } from "@/lib/metric-display";
import type {
  ConstantProperty,
  ConversionMetricTypeParams,
  CumulativeMetricTypeParams,
  DerivedMetricTypeParams,
  Metric,
  MetricFilter,
  MetricFilterPredicate,
  MetricRef,
  MetricType,
  MetricTypeParams,
  PeriodAggregation,
  RatioMetricTypeParams,
  SimpleMetricTypeParams,
  TimeGranularity,
} from "@/lib/types/metric";

const METRIC_API_PATH = "/metrics";

export interface WriteMetricRequest {
  name: string;
  label?: string;
  description?: string;
  type: MetricType;
  typeParams: MetricTypeParams;
  filters?: MetricFilter[];
  timeGranularity?: TimeGranularity;
  display?: MetricDisplay | null;
}

type MetricDsl = Record<string, unknown>;

interface MetricResourceWireResponse {
  metricId: string;
  workspaceId: string;
  definition: MetricDsl;
  createdAt: string;
  updatedAt: string;
  sourcePartitionInfo: NonNullable<Metric["sourcePartitionInfo"]>;
}

interface MetricDetailWireResponse extends MetricResourceWireResponse {
  updatedBy: RecordedActor | null;
  credentialSurface: CredentialSurface | null;
  agentApp: AgentApp | null;
}

/** The detail read adds the last recorded save: who saved it and whether they worked through an agent. */
export type MetricDetail = Metric & {
  updatedBy: RecordedActor | null;
  credentialSurface: CredentialSurface | null;
  agentApp: AgentApp | null;
};

function buildFilter(filter: MetricFilter): Record<string, unknown> {
  if ("and" in filter) return { and: filter.and.map(buildFilter) };
  if ("or" in filter) return { or: filter.or.map(buildFilter) };
  if ("not" in filter) return { not: buildFilter(filter.not) };
  return {
    dimension: {
      semanticModelName: filter.dimension.semanticModelName,
      name: filter.dimension.name,
      ...(filter.dimension.granularity ? { granularity: filter.dimension.granularity } : {}),
    },
    operator: filter.operator,
    values: [...filter.values],
  };
}

function buildMetricRef(ref: MetricRef): Record<string, unknown> {
  return {
    metricName: ref.metricName,
    ...(ref.alias ? { alias: ref.alias } : {}),
    ...(ref.filter ? { filter: buildFilter(ref.filter) } : {}),
    ...(ref.offsetWindow ? { offsetWindow: ref.offsetWindow } : {}),
    ...(ref.offsetToGrain ? { offsetToGrain: ref.offsetToGrain } : {}),
  };
}

function buildTypeFields(type: MetricType, params: MetricTypeParams): MetricDsl {
  switch (type) {
    case "simple": {
      const value = params as SimpleMetricTypeParams;
      return { semanticModelName: value.semanticModelName, measure: value.measure };
    }
    case "ratio": {
      const value = params as RatioMetricTypeParams;
      return { numerator: buildMetricRef(value.numerator), denominator: buildMetricRef(value.denominator) };
    }
    case "derived": {
      const value = params as DerivedMetricTypeParams;
      return {
        expression: value.expression,
        inputMetrics: value.inputMetrics.map(buildMetricRef),
      };
    }
    case "cumulative": {
      const value = params as CumulativeMetricTypeParams;
      return {
        inputMetric: buildMetricRef(value.inputMetric),
        ...(value.window ? { window: value.window } : {}),
        ...(value.grainToDate ? { grainToDate: value.grainToDate } : {}),
        ...(value.periodAggregation ? { periodAggregation: value.periodAggregation } : {}),
      };
    }
    case "conversion": {
      const value = params as ConversionMetricTypeParams;
      return {
        entity: value.entity,
        calculation: value.calculation,
        baseMetric: buildMetricRef(value.baseMetric),
        conversionMetric: buildMetricRef(value.conversionMetric),
        ...(value.window ? { window: value.window } : {}),
        ...(value.constantProperties?.length
          ? { constantProperties: value.constantProperties }
          : {}),
      };
    }
  }
}

export function buildMetricDsl(request: WriteMetricRequest): MetricDsl {
  return {
    schemaVersion: 3,
    name: request.name,
    type: request.type,
    ...(request.label ? { label: request.label } : {}),
    ...(request.description ? { description: request.description } : {}),
    ...(request.timeGranularity ? { timeGranularity: request.timeGranularity } : {}),
    ...(request.filters?.length ? { filters: request.filters.map(buildFilter) } : {}),
    ...(request.display ? { display: request.display } : {}),
    ...buildTypeFields(request.type, request.typeParams),
  };
}

function parseFilter(raw: unknown): MetricFilter {
  const value = raw as Record<string, unknown>;
  if (Array.isArray(value.and)) return { and: value.and.map(parseFilter) };
  if (Array.isArray(value.or)) return { or: value.or.map(parseFilter) };
  if (value.not) return { not: parseFilter(value.not) };
  const dimension = value.dimension as Record<string, unknown>;
  return {
    dimension: {
      semanticModelName: String(dimension.semanticModelName),
      name: String(dimension.name),
      ...(dimension.granularity ? { granularity: dimension.granularity as TimeGranularity } : {}),
    },
    operator: value.operator as MetricFilterPredicate["operator"],
    values: Array.isArray(value.values) ? value.values.map(String) : [],
  };
}

function parseRef(raw: unknown): MetricRef {
  const value = raw as Record<string, unknown>;
  return {
    metricName: String(value.metricName),
    ...(value.alias ? { alias: String(value.alias) } : {}),
    ...(value.filter ? { filter: parseFilter(value.filter) } : {}),
    ...(value.offsetWindow ? { offsetWindow: String(value.offsetWindow) } : {}),
    ...(value.offsetToGrain ? { offsetToGrain: value.offsetToGrain as TimeGranularity } : {}),
  };
}

function parseTypeParams(type: MetricType, value: Record<string, unknown>): MetricTypeParams {
  switch (type) {
    case "simple":
      return { semanticModelName: String(value.semanticModelName), measure: String(value.measure) };
    case "ratio":
      return { numerator: parseRef(value.numerator), denominator: parseRef(value.denominator) };
    case "derived":
      return {
        expression: String(value.expression),
        inputMetrics: Array.isArray(value.inputMetrics) ? value.inputMetrics.map(parseRef) : [],
      };
    case "cumulative":
      return {
        inputMetric: parseRef(value.inputMetric),
        ...(value.window ? { window: String(value.window) } : {}),
        ...(value.grainToDate ? { grainToDate: value.grainToDate as TimeGranularity } : {}),
        ...(value.periodAggregation ? { periodAggregation: value.periodAggregation as PeriodAggregation } : {}),
      };
    case "conversion":
      return {
        entity: String(value.entity),
        calculation: value.calculation as ConversionMetricTypeParams["calculation"],
        baseMetric: parseRef(value.baseMetric),
        conversionMetric: parseRef(value.conversionMetric),
        ...(value.window ? { window: String(value.window) } : {}),
        ...(Array.isArray(value.constantProperties)
          ? { constantProperties: value.constantProperties as ConstantProperty[] }
          : {}),
      };
  }
}

export function parseMetricDsl(
  id: string,
  definition: MetricDsl,
  createdAt: string,
  updatedAt: string,
): Metric {
  const value = definition;
  const type = value.type as MetricType;
  return {
    id,
    name: String(value.name),
    ...(value.label ? { label: String(value.label) } : {}),
    ...(value.description ? { description: String(value.description) } : {}),
    type,
    typeParams: parseTypeParams(type, value),
    ...(Array.isArray(value.filters) ? { filters: value.filters.map(parseFilter) } : {}),
    ...(value.timeGranularity ? { timeGranularity: value.timeGranularity as TimeGranularity } : {}),
    display: value.display as MetricDisplay | undefined ?? null,
    createdAt,
    updatedAt,
  };
}

async function writeMetric(
  workspaceId: string,
  id: string | null,
  request: WriteMetricRequest,
  idempotencyKey: string,
): Promise<Metric> {
  const path = id ? `${METRIC_API_PATH}/${encodeURIComponent(id)}` : METRIC_API_PATH;
  const response = await apiFetch(semanticBffUrl(workspaceId, path), {
    method: id ? "PUT" : "POST",
    headers: { "Content-Type": "application/json", "Idempotency-Key": idempotencyKey },
    body: JSON.stringify({ definition: buildMetricDsl(request) }),
  });
  if (!response.ok) {
    const error = await response.text();
    throw new Error(`Failed to write metric: ${error}`);
  }
  const value = await response.json() as MetricResourceWireResponse;
  return { ...parseMetricDsl(value.metricId, value.definition, value.createdAt, value.updatedAt), sourcePartitionInfo: value.sourcePartitionInfo };
}

export async function getMetric(workspaceId: string, id: string): Promise<MetricDetail> {
  const response = await apiFetch(semanticBffUrl(workspaceId, `${METRIC_API_PATH}/${encodeURIComponent(id)}`));
  if (!response.ok) throw new Error(`Failed to fetch metric: ${response.statusText}`);
  const value = await response.json() as MetricDetailWireResponse;
  return {
    ...parseMetricDsl(value.metricId, value.definition, value.createdAt, value.updatedAt),
    sourcePartitionInfo: value.sourcePartitionInfo,
    updatedBy: value.updatedBy,
    credentialSurface: value.credentialSurface,
    agentApp: value.agentApp,
  };
}

export async function listMetrics(workspaceId: string): Promise<Metric[]> {
  const response = await apiFetch(semanticBffUrl(workspaceId, METRIC_API_PATH));
  if (!response.ok) throw new Error(`Failed to fetch metrics: ${response.statusText}`);
  const resources = await response.json() as MetricResourceWireResponse[];
  return resources.map((resource) =>
    ({ ...parseMetricDsl(resource.metricId, resource.definition, resource.createdAt, resource.updatedAt), sourcePartitionInfo: resource.sourcePartitionInfo }));
}

export async function createMetric(
  workspaceId: string,
  request: WriteMetricRequest,
  idempotencyKey: string,
): Promise<Metric> {
  return writeMetric(workspaceId, null, request, idempotencyKey);
}

export async function updateMetric(
  workspaceId: string,
  id: string,
  request: WriteMetricRequest,
  idempotencyKey: string,
): Promise<Metric> {
  return writeMetric(workspaceId, id, request, idempotencyKey);
}

export const metricAPI = {
  create: createMetric,
  update: updateMetric,
  get: getMetric,
  list: listMetrics,
};

function directMetricRefs(metric: Metric): MetricRef[] {
  switch (metric.type) {
    case "simple":
      return [];
    case "ratio":
      return [
        (metric.typeParams as RatioMetricTypeParams).numerator,
        (metric.typeParams as RatioMetricTypeParams).denominator,
      ];
    case "derived":
      return (metric.typeParams as DerivedMetricTypeParams).inputMetrics;
    case "cumulative":
      return [(metric.typeParams as CumulativeMetricTypeParams).inputMetric];
    case "conversion":
      return [
        (metric.typeParams as ConversionMetricTypeParams).baseMetric,
        (metric.typeParams as ConversionMetricTypeParams).conversionMetric,
      ];
  }
}

function collectFilterModelNames(filter: MetricFilter, names: Set<string>): void {
  if ("and" in filter) {
    filter.and.forEach((child) => collectFilterModelNames(child, names));
    return;
  }
  if ("or" in filter) {
    filter.or.forEach((child) => collectFilterModelNames(child, names));
    return;
  }
  if ("not" in filter) {
    collectFilterModelNames(filter.not, names);
    return;
  }
  names.add(filter.dimension.semanticModelName);
}

/** Every model read by the authored DSL, including conditions on metric inputs. */
export function referencedModelNames(metric: Metric): Set<string> {
  const names = new Set<string>();
  if (metric.type === "simple") names.add((metric.typeParams as SimpleMetricTypeParams).semanticModelName);
  for (const filter of metric.filters ?? []) collectFilterModelNames(filter, names);
  for (const ref of directMetricRefs(metric)) if (ref.filter) collectFilterModelNames(ref.filter, names);
  return names;
}

/** Direct and indirect dependents, in inventory order, without repeating cycles. */
export function definitionDependentMetrics(kind: "semantic-models" | "metrics", name: string, metrics: Metric[]): Metric[] {
  const affected = new Set<string>(kind === "metrics" ? [name] : []);
  if (kind === "semantic-models") {
    for (const metric of metrics) if (referencedModelNames(metric).has(name)) affected.add(metric.name);
  }
  let changed = true;
  while (changed) {
    changed = false;
    for (const metric of metrics) {
      if (!affected.has(metric.name) && directMetricRefs(metric).some((ref) => affected.has(ref.metricName))) {
        affected.add(metric.name);
        changed = true;
      }
    }
  }
  return metrics.filter((metric) => affected.has(metric.name) && !(kind === "metrics" && metric.name === name));
}
