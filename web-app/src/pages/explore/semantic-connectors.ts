import type { SemanticModelResponse } from "@/api/semantic-model";
import type {
  ConversionMetricTypeParams,
  CumulativeMetricTypeParams,
  DerivedMetricTypeParams,
  Metric,
  RatioMetricTypeParams,
  SimpleMetricTypeParams,
} from "@/lib/types/metric";

function inputMetricNames(metric: Metric): string[] {
  switch (metric.type) {
    case "simple":
      return [];
    case "ratio": {
      const params = metric.typeParams as RatioMetricTypeParams;
      return [params.numerator.metricName, params.denominator.metricName];
    }
    case "derived":
      return (metric.typeParams as DerivedMetricTypeParams).inputMetrics.map((input) => input.metricName);
    case "cumulative":
      return [(metric.typeParams as CumulativeMetricTypeParams).inputMetric.metricName];
    case "conversion": {
      const params = metric.typeParams as ConversionMetricTypeParams;
      return [params.baseMetric.metricName, params.conversionMetric.metricName];
    }
  }
}

/**
 * Semantic model names a metric reads through its input metrics. Null when a referenced metric is
 * missing, so callers do not guess where an unresolvable metric could run.
 */
export function metricModelNames(metricName: string, metrics: Metric[]): Set<string> | null {
  const models = new Set<string>();
  const visited = new Set<string>();
  const pending = [metricName];
  while (pending.length > 0) {
    const name = pending.pop() as string;
    if (visited.has(name)) continue;
    visited.add(name);
    const metric = metrics.find((candidate) => candidate.name === name);
    if (!metric) return null;
    if (metric.type === "simple") models.add((metric.typeParams as SimpleMetricTypeParams).semanticModelName);
    pending.push(...inputMetricNames(metric));
  }
  return models;
}

export interface SemanticConnectorResolution {
  /** Connectors that can run every selected metric, in workspace connector order. */
  compatible: string[];
  /** Why no connector qualifies, in words a person can act on; null when one does or nothing is selected. */
  problem: string | null;
}

/**
 * Each model a selected metric reads must have a source table on the connector. A metric that
 * references a missing metric or model names that reference instead of guessing a connector.
 */
export function resolveSemanticConnectors(
  selectedMetrics: string[],
  metrics: Metric[],
  models: SemanticModelResponse[],
  connectorIds: string[],
): SemanticConnectorResolution {
  if (selectedMetrics.length === 0) return { compatible: [], problem: null };
  let compatible = new Set(connectorIds);
  for (const metricName of selectedMetrics) {
    const metric = metrics.find((candidate) => candidate.name === metricName);
    const label = metric?.label || metricName;
    const modelNames = metricModelNames(metricName, metrics);
    if (!metric || !modelNames) {
      return { compatible: [], problem: `${label} uses a metric that no longer exists. Open the metric to fix it, or remove it from the query.` };
    }
    for (const modelName of modelNames) {
      const model = models.find((candidate) => candidate.name === modelName);
      if (!model) {
        return { compatible: [], problem: `${label} reads the model “${modelName}”, which doesn’t exist. Open the metric to fix it, or remove it from the query.` };
      }
      const sources = new Set(model.sourceRelations.map((source) => source.connectorId));
      if (connectorIds.length > 0 && sources.size > 0 && ![...sources].some((connectorId) => connectorIds.includes(connectorId))) {
        return { compatible: [], problem: `${label} reads the model “${model.label || modelName}”, whose connector was deleted. Point the model at another connector, or remove the metric from the query.` };
      }
      compatible = new Set([...compatible].filter((connectorId) => sources.has(connectorId)));
    }
  }
  const ordered = connectorIds.filter((connectorId) => compatible.has(connectorId));
  return {
    compatible: ordered,
    problem: ordered.length === 0 ? "No connector has source tables for all of these metrics." : null,
  };
}

/** Why a metric can't run on any connector by itself; null when some connector has every table it reads. */
export type MetricRunProblem =
  | { kind: "MISSING_METRIC" }
  | { kind: "MISSING_MODEL"; modelName: string }
  | { kind: "CONNECTOR_DELETED"; modelLabel: string }
  | { kind: "NO_SOURCE_TABLES" };

export function metricRunProblem(metricName: string, metrics: Metric[], models: SemanticModelResponse[], connectorIds: string[]): MetricRunProblem | null {
  const modelNames = metricModelNames(metricName, metrics);
  if (!modelNames) return { kind: "MISSING_METRIC" };
  let compatible = new Set(connectorIds);
  for (const modelName of modelNames) {
    const model = models.find((candidate) => candidate.name === modelName);
    if (!model) return { kind: "MISSING_MODEL", modelName };
    const sources = new Set(model.sourceRelations.map((source) => source.connectorId));
    if (connectorIds.length > 0 && sources.size > 0 && ![...sources].some((connectorId) => connectorIds.includes(connectorId))) {
      return { kind: "CONNECTOR_DELETED", modelLabel: model.label || modelName };
    }
    compatible = new Set([...compatible].filter((connectorId) => sources.has(connectorId)));
  }
  return compatible.size === 0 ? { kind: "NO_SOURCE_TABLES" } : null;
}

export function metricRunProblemText(problem: MetricRunProblem): string {
  switch (problem.kind) {
    case "MISSING_METRIC": return "Can’t run: it uses a metric that no longer exists.";
    case "MISSING_MODEL": return `Can’t run: it reads the model “${problem.modelName}”, which doesn’t exist.`;
    case "CONNECTOR_DELETED": return `Can’t run: the connector of “${problem.modelLabel}” was deleted.`;
    case "NO_SOURCE_TABLES": return "Can’t run: no connector has its source tables.";
  }
}

/** Metrics that read the given semantic model, directly or through input metrics. */
export function metricsReadingModel(modelName: string, metrics: Metric[]): Metric[] {
  return metrics.filter((metric) => metricModelNames(metric.name, metrics)?.has(modelName) === true);
}
