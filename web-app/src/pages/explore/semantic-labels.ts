import type { SemanticGroupBy } from "@/api/explore";
import type { SemanticModelResponse } from "@/api/semantic-model";
import type { Metric } from "@/lib/types/metric";

function granularityLabel(granularity: string): string {
  return granularity.toLowerCase();
}

/**
 * A group-by reads as its human label with the declared grain, e.g. "Ordered at (day)". The owning
 * model is secondary context; technical names stay searchable but are not repeated on the row.
 */
export function groupByPresentation(groupBy: SemanticGroupBy, models: SemanticModelResponse[]): { label: string; detail: string | null } {
  if (groupBy.kind === "METRIC_TIME") return { label: `Metric time (${granularityLabel(groupBy.granularity)})`, detail: null };
  const model = models.find((candidate) => candidate.name === groupBy.semanticModelName);
  const element = model?.dimensions.find((candidate) => candidate.name === groupBy.name)
    ?? model?.entities.find((candidate) => candidate.name === groupBy.name);
  const name = element?.label || groupBy.name;
  const granularity = groupBy.kind === "DIMENSION" && groupBy.granularity ? ` (${granularityLabel(groupBy.granularity)})` : "";
  return { label: `${name}${granularity}`, detail: model?.label || groupBy.semanticModelName };
}

export function groupByResultKey(groupBy: SemanticGroupBy): string {
  if (groupBy.kind === "METRIC_TIME") return `metric_time.${groupBy.granularity.toLowerCase()}`;
  if (groupBy.kind === "ENTITY") return `entity.${groupBy.semanticModelName}.${groupBy.name}`;
  return ["dimension", groupBy.semanticModelName, groupBy.name, groupBy.granularity?.toLowerCase()].filter(Boolean).join(".");
}

/**
 * Human headers for a Semantic result. The server projects group-bys first and metrics second; a
 * column named by its result key maps by key, otherwise a matching column count maps by position.
 * Anything else keeps the raw column names, as SQL results always do.
 */
export function semanticResultColumnLabels(
  query: { metrics: string[]; groupBys: SemanticGroupBy[] },
  columnNames: string[],
  metrics: Metric[],
  models: SemanticModelResponse[],
): string[] {
  const projection = [
    ...query.groupBys.map((groupBy) => ({ key: groupByResultKey(groupBy), ...groupByPresentation(groupBy, models) })),
    ...query.metrics.map((metricName) => {
      const metric = metrics.find((candidate) => candidate.name === metricName);
      return { key: `metric.${metricName}`, label: metric?.label || metricName, detail: null };
    }),
  ];
  const labelCounts = new Map<string, number>();
  projection.forEach((output) => labelCounts.set(output.label, (labelCounts.get(output.label) ?? 0) + 1));
  const headers = projection.map((output) => (labelCounts.get(output.label) ?? 0) > 1 && output.detail
    ? `${output.label} · ${output.detail}`
    : output.label);
  const positional = columnNames.length === projection.length;
  return columnNames.map((name, index) => {
    const keyed = projection.findIndex((output) => output.key === name);
    if (keyed >= 0) return headers[keyed];
    return positional ? headers[index] : name;
  });
}
