import { formatSourceRelation, type SemanticSourceRelation } from "@/api/semantic-model";
import { webPaths } from "@/lib/web-paths";

/** Names a source by its connector's name, e.g. "Northstar warehouse · analytics.customers". */
export function sourceRelationLabel(
  source: SemanticSourceRelation,
  connectors: ReadonlyArray<{ id: string; name: string }>,
): string {
  const connector = connectors.find((candidate) => candidate.id === source.connectorId);
  return `${connector?.name ?? "Unknown connector"} · ${formatSourceRelation(source)}`;
}

/** Explore with one metric preselected in the Semantic composer; it never starts a run. */
export function exploreMetricPath(workspaceId: string, metricName: string): string {
  return `${webPaths.explore(workspaceId)}?metric=${encodeURIComponent(metricName)}`;
}

/**
 * Suggests a technical name from a label: lowercase words joined by underscores, starting with a
 * letter, e.g. "Gross Revenue (USD)" -> "gross_revenue_usd".
 */
export function suggestTechnicalName(label: string): string {
  return label
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^[^a-z]+/, "")
    .replace(/_+$/, "");
}

/**
 * Get display name (label or name)
 */
export function getDisplayName(item: { name: string; label?: string }): string {
  return item.label || item.name;
}
