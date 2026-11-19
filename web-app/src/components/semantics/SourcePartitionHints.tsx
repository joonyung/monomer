import { TriangleAlert } from "lucide-react";
import type { SourcePartitionInfo, SemanticModelResponse } from "@/api/semantic-model";
import type { Connector } from "@/lib/types/connector";
import { Button } from "@/components/ui/Button";
import { Link } from "@/lib/navigation";
import { webPaths } from "@/lib/web-paths";
import { partitionDimensionLabel, partitionRelationName, sourcePartitionFilter, type PartitionFilter, type PartitionRepairAction } from "./source-partition";

export function PartitionFilterHint({ filter, models, workspaceId, canEdit, onRepair }: {
  filter: PartitionFilter;
  models: readonly SemanticModelResponse[];
  workspaceId: string | null;
  canEdit: boolean;
  onRepair?: (action: PartitionRepairAction) => void;
}) {
  const keys = filter.partitionKeys.join(", ") || "its partition column";
  const model = filter.kind === "SEMANTIC_MODEL" ? models.find((candidate) => candidate.name === filter.semanticModelName) : undefined;
  const label = model?.label || (filter.kind === "SEMANTIC_MODEL" ? filter.semanticModelName : partitionRelationName(filter.relation));
  const missingDimension = filter.kind === "SEMANTIC_MODEL" && filter.dimensions.length === 0;
  const timeRange = filter.kind === "SEMANTIC_MODEL" && filter.timeRangeApplies && !missingDimension;
  const dimension = filter.kind === "SEMANTIC_MODEL" ? partitionDimensionLabel(filter, models) : "";
  return <div className="flex min-w-0 gap-2 type-meta text-foreground">
    <TriangleAlert aria-hidden="true" className="mt-0.5 h-3.5 w-3.5 shrink-0 text-warning" />
    <div className="min-w-0">
      <p className="[overflow-wrap:anywhere]">{filter.kind === "TIME_SPINE"
        ? `Time spine ${label} needs a filter on ${keys}. Choose a time spine table that can be read without a partition filter.`
        : `${label} reads a table that needs a filter on ${keys}.${missingDimension ? " The model has no dimension for it." : timeRange ? " Set a time range to run." : ` Add a filter on ${dimension}.`}`}</p>
      {filter.kind === "SEMANTIC_MODEL" && missingDimension ? canEdit && model && workspaceId
        ? <Link href={`${webPaths.semanticModel(workspaceId, model.id)}?edit=true`} className="mt-1 inline-block underline underline-offset-4">Open model</Link>
        : <p className="mt-1 text-foreground-secondary">Ask an editor to add it.</p>
        : filter.kind === "SEMANTIC_MODEL" && onRepair ? <Button variant="ghost" size="sm" className="-ml-3 mt-1" onClick={() => onRepair(timeRange ? { kind: "TIME_RANGE" } : { kind: "FILTER", semanticModelName: filter.semanticModelName, dimensionName: filter.dimensions[0] })}>{timeRange ? "Set time range" : "Add filter"}</Button> : null}
    </div>
  </div>;
}

/** Saved read metadata stays separate from the definition and its snapshot-join option. */
export function SourcePartitionHints({ sources, models, connectors }: { sources: readonly SourcePartitionInfo[]; models: readonly SemanticModelResponse[]; connectors: readonly Pick<Connector, "id" | "name">[] }) {
  const required = sources.filter((source) => source.requiresPartitionFilter === true);
  if (required.length === 0) return null;
  return <div className="space-y-2" aria-label="Source partition requirements">{required.map((source) => {
    const model = models.find((candidate) => candidate.name === source.semanticModelName);
    const connector = connectors.find((candidate) => candidate.id === source.connectorId)?.name || "Connector";
    const condition = source.dimensions.length === 0 ? `needs a dimension for ${source.partitionKeys.join(", ")}` : source.timeRangeApplies ? "needs a time range" : `needs a filter on ${partitionDimensionLabel(sourcePartitionFilter(source), models)}`;
    return <p key={`${source.semanticModelName}:${source.connectorId}`} className="type-meta [overflow-wrap:anywhere] text-foreground-secondary">{model?.label || source.semanticModelName} · {connector} · Partitioned by {source.partitionKeys.join(", ")} · {condition}</p>;
  })}</div>;
}
