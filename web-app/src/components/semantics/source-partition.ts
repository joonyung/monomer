import type { components } from "@/api/control/generated";
import type { SourcePartitionInfo, SemanticModelResponse } from "@/api/semantic-model";
import type { SemanticDraft } from "@/pages/explore/explore-draft";
import type { Metric } from "@/lib/types/metric";
import { metricModelNames } from "@/pages/explore/semantic-connectors";

export type PartitionFilter = components["schemas"]["ProblemDetailsPartitionFilter"];
export type PartitionRepairAction = { kind: "TIME_RANGE" } | { kind: "FILTER"; semanticModelName: string; dimensionName: string };
export type PartitionRepairRequest = PartitionRepairAction & { id: number };

export function partitionRelationName(relation: PartitionFilter["relation"]): string {
  return [relation.catalog, relation.schema, relation.name].filter(Boolean).join(".");
}

export function sourcePartitionFilter(source: SourcePartitionInfo): Extract<PartitionFilter, { kind: "SEMANTIC_MODEL" }> {
  return { kind: "SEMANTIC_MODEL", connectorId: source.connectorId, relation: source.relation, partitionKeys: source.partitionKeys, semanticModelName: source.semanticModelName, dimensions: source.dimensions, timeRangeApplies: source.timeRangeApplies };
}

export function partitionDimensionLabel(filter: Extract<PartitionFilter, { kind: "SEMANTIC_MODEL" }>, models: readonly SemanticModelResponse[]): string {
  const dimension = models.find((model) => model.name === filter.semanticModelName)?.dimensions.find((candidate) => candidate.name === filter.dimensions[0]);
  return dimension?.label || filter.dimensions[0] || filter.partitionKeys.join(", ");
}

export function partitionFilterCondition(filter: PartitionFilter, models: readonly SemanticModelResponse[]): string {
  if (filter.kind === "TIME_SPINE") return `needs a partition filter on time spine ${partitionRelationName(filter.relation)}`;
  if (filter.dimensions.length === 0) return `needs a dimension for ${filter.partitionKeys.join(", ")}`;
  return filter.timeRangeApplies ? "needs a time range" : `needs a filter on ${partitionDimensionLabel(filter, models)}`;
}

/** Static hints cover only the models already named by the person's selection, never an inferred join path. */
export function queryPartitionHints(draft: SemanticDraft, connectorId: string | null, metrics: Metric[], models: SemanticModelResponse[], includeSatisfied = false): PartitionFilter[] {
  if (!connectorId || draft.metrics.length === 0) return [];
  const modelNames = new Set(draft.metrics.flatMap((name) => [...(metricModelNames(name, metrics) ?? [])]));
  for (const groupBy of draft.groupBys) if (groupBy.kind !== "METRIC_TIME") modelNames.add(groupBy.semanticModelName);
  for (const filter of draft.filters) modelNames.add(filter.dimension.semanticModelName);
  return models.filter((model) => modelNames.has(model.name)).flatMap((model) => (model.sourcePartitionInfo ?? []).filter((source) =>
    source.connectorId === connectorId && source.requiresPartitionFilter === true
    && (includeSatisfied || (!(source.timeRangeApplies && draft.metricTimeRange)
    && !draft.filters.some((filter) => filter.dimension.semanticModelName === source.semanticModelName && source.dimensions.includes(filter.dimension.name)))),
  ).map(sourcePartitionFilter));
}
