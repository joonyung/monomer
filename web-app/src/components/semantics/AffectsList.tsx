import { useState } from "react";
import type { LineageFocusCone, LineageResource, LineageResourceRef } from "@/api/lineage";
import { KindIcon } from "@/components/resource-detail/KindIcon";
import { SEMANTIC_LINK_CLASS } from "@/components/resource-detail/semantic-links";
import { Button } from "@/components/ui/Button";
import { Link } from "@/lib/navigation";
import { webPaths } from "@/lib/web-paths";

import type { Metric, SimpleMetricTypeParams } from "@/lib/types/metric";
import { metricInputs, metricFilterDimensions } from "./semantic-insights";

const resourceKey = (ref: LineageResourceRef) => `${ref.kind}:${ref.id}`;

export function impactDepths(graph: LineageFocusCone): Map<string, number> {
  const depths = new Map([[resourceKey(graph.focus.resource), 0]]);
  const pending = [graph.focus.resource];
  while (pending.length) {
    const parent = pending.shift()!;
    for (const edge of graph.edges) {
      if (resourceKey(edge.parent) !== resourceKey(parent) || depths.has(resourceKey(edge.dependent))) continue;
      depths.set(resourceKey(edge.dependent), depths.get(resourceKey(parent))! + 1);
      pending.push(edge.dependent);
    }
  }
  return depths;
}

export function semanticResourceHref(workspaceId: string, resource: LineageResource): string {
  return resource.resource.kind === "METRIC" ? webPaths.metric(workspaceId, resource.resource.id) : resource.resource.kind === "SEMANTIC_MODEL" ? webPaths.semanticModel(workspaceId, resource.resource.id) : webPaths.lineageResource(workspaceId, resource.resource.kind, resource.resource.id);
}

function viaText(labels: string[]): string {
  return labels.length > 2 ? `via ${labels.slice(0, 2).join(", ")} and ${labels.length - 2} more` : `via ${labels.join(", ")}`;
}

const KIND_NOUNS: Record<string, [string, string]> = { METRIC: ["metric", "metrics"], SEMANTIC_MODEL: ["model", "models"], RELATION: ["table", "tables"], DASHBOARD: ["dashboard", "dashboards"] };
const KIND_ORDER = ["SEMANTIC_MODEL", "METRIC", "DASHBOARD", "RELATION"];

/**
 * Everything that changes with the focus, nearest first. A row names only what the reader cannot infer: the role a
 * direct dependent gives the focus (Numerator, Filter), or the affected definition an indirect dependent goes through.
 */
export function AffectsList({ workspaceId, graph, compact = false, limit, metricDefinitions = [] }: { workspaceId: string; graph: LineageFocusCone; compact?: boolean; limit?: number; metricDefinitions?: Metric[] }) {
  const [expanded, setExpanded] = useState(false);
  const depths = impactDepths(graph);
  const focusKey = resourceKey(graph.focus.resource);
  const affected = graph.descendants.filter((resource) => depths.has(resourceKey(resource.resource))).sort((left, right) => depths.get(resourceKey(left.resource))! - depths.get(resourceKey(right.resource))! || left.label.localeCompare(right.label));
  const usages = graph.dashboardUsages ?? [];
  const resources = [graph.focus, ...graph.ancestors, ...graph.descendants];
  const labelOf = (ref: LineageResourceRef) => resources.find((candidate) => resourceKey(candidate.resource) === resourceKey(ref))?.label;
  const relationship = (resource: LineageResource): string => {
    const parents = graph.edges.filter((edge) => resourceKey(edge.dependent) === resourceKey(resource.resource) && depths.has(resourceKey(edge.parent)));
    const through = parents.filter((edge) => resourceKey(edge.parent) !== focusKey).map((edge) => labelOf(edge.parent)).filter((label): label is string => Boolean(label));
    if (through.length) return viaText([...new Set(through)]);
    const metric = resource.resource.kind === "METRIC" ? metricDefinitions.find((candidate) => candidate.id === resource.resource.id) : undefined;
    if (!metric) return "";
    if (graph.focus.resource.kind === "METRIC") return [...new Set(metricInputs(metric).filter((input) => input.ref.metricName === graph.focus.address).map((input) => input.role))].join(", ");
    if (graph.focus.resource.kind === "SEMANTIC_MODEL") {
      const aggregatesFocus = metric.type === "simple" && (metric.typeParams as SimpleMetricTypeParams).semanticModelName === graph.focus.address;
      const filtersFocus = metricFilterDimensions(metric).some((dimension) => dimension.semanticModelName === graph.focus.address);
      return filtersFocus && !aggregatesFocus ? "Filter" : "";
    }
    return "";
  };
  if (!affected.length && !usages.length) return <p className="type-body text-foreground-secondary">No saved definitions depend on this.</p>;
  const affectedRows = [
    ...affected.map((resource) => ({
      key: resourceKey(resource.resource),
      kind: resource.resource.kind,
      label: resource.label,
      href: semanticResourceHref(workspaceId, resource),
      depth: depths.get(resourceKey(resource.resource))!,
      relationship: relationship(resource),
    })),
    ...usages.map((usage) => {
      const viaMetrics = usage.metricIds.filter((id) => `METRIC:${id}` !== focusKey && depths.has(`METRIC:${id}`));
      const direct = usage.metricIds.some((id) => `METRIC:${id}` === focusKey);
      return {
        key: `DASHBOARD:${usage.dashboardId}`,
        kind: "DASHBOARD" as const,
        label: usage.title,
        href: webPaths.dashboard(workspaceId, usage.dashboardId),
        depth: Math.min(...usage.metricIds.map((id) => depths.get(`METRIC:${id}`) ?? 0)) + 1,
        relationship: direct || viaMetrics.length === 0 ? "" : viaText(viaMetrics.map((id) => labelOf({ kind: "METRIC", id })).filter((label): label is string => Boolean(label))),
      };
    }),
  ];
  const counts = KIND_ORDER.flatMap((kind) => {
    const count = affectedRows.filter((row) => row.kind === kind).length;
    return count ? [`${count} ${KIND_NOUNS[kind][count === 1 ? 0 : 1]}`] : [];
  });
  const shownRows = limit !== undefined && !expanded ? affectedRows.slice(0, limit) : affectedRows;
  return <div className="min-w-0">
    <p className="type-meta mb-2 text-foreground-secondary tabular-nums">{counts.join(" · ")}</p>
    <ul className="min-w-0 divide-y divide-border-light" aria-label="Affected definitions">
      {shownRows.map((row) => <li key={row.key} className="flex min-w-0 gap-3 py-2.5 first:pt-1 last:pb-0" data-testid="affected-definition" data-kind={row.kind} data-depth={row.depth}>
        <KindIcon kind={row.kind} />
        <div className="min-w-0 pt-0.5">
          <span className="sr-only">{KIND_NOUNS[row.kind][0]}: </span>
          <Link href={row.href} className={`type-body block break-words ${SEMANTIC_LINK_CLASS}`} title={row.label}>{row.label}</Link>
          {!compact && row.relationship && <p className="type-meta break-words text-foreground-secondary" data-testid="affected-relationship"><span className="sr-only">How it depends: </span>{row.relationship}</p>}
        </div>
      </li>)}
    </ul>
    {shownRows.length < affectedRows.length && <Button variant="ghost" size="sm" className="mt-2" onClick={() => setExpanded(true)}>Show all {affectedRows.length}</Button>}
  </div>;
}
