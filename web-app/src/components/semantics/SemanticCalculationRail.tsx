import type { ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { CircleAlert, CircleCheck, Network } from "lucide-react";
import { metricAPI } from "@/api/metric";
import { semanticModelAPI } from "@/api/semantic-model";
import { useConnectors } from "@/hooks/useConnectors";
import { referenceProblem, hasMetricSourceConnector } from "@/components/semantics/semantic-insights";
import { lineageAPI } from "@/api/lineage";
import { AffectsList, semanticResourceHref } from "@/components/semantics/AffectsList";
import { StateBlock } from "@/components/ui/StateBlock";
import { DetailPanel } from "@/components/resource-detail/DetailPanel";
import { KindIcon } from "@/components/resource-detail/KindIcon";
import { SEMANTIC_LINK_CLASS } from "@/components/resource-detail/semantic-links";
import { buttonClassName } from "@/components/ui/Button";
import { Link } from "@/lib/navigation";
import { queryKeys } from "@/lib/query/keys";
import { webPaths } from "@/lib/web-paths";
import type { LineageFocusCone, LineageResource } from "@/api/lineage";

export function calculationPathStages(graph: LineageFocusCone): { depth: number; resources: LineageResource[] }[] {
  const resourceKey = (resource: LineageResource["resource"]) => `${resource.kind}:${resource.id}`;
  const upstreamDepth = new Map([[resourceKey(graph.focus.resource), 0]]);
  const pendingResources = [graph.focus.resource];
  while (pendingResources.length) {
    const current = pendingResources.shift()!;
    for (const edge of graph.edges) {
      const parentKey = resourceKey(edge.parent);
      if (resourceKey(edge.dependent) !== resourceKey(current) || upstreamDepth.has(parentKey)) continue;
      upstreamDepth.set(parentKey, upstreamDepth.get(resourceKey(current))! + 1);
      pendingResources.push(edge.parent);
    }
  }
  const stages = new Map<number, LineageResource[]>();
  for (const resource of [...graph.ancestors, graph.focus]) {
    const depth = upstreamDepth.get(resourceKey(resource.resource));
    if (depth === undefined) continue;
    stages.set(depth, [...(stages.get(depth) ?? []), resource]);
  }
  return [...stages].sort(([left], [right]) => right - left).map(([depth, resources]) => ({ depth, resources }));
}

/** Warehouse addresses wrap only between their dotted parts. */
function addressWithBreaks(address: string) {
  return address.split(".").map((part, index, parts) => <span key={index} className="whitespace-nowrap">{part}{index < parts.length - 1 ? "." : ""}{index < parts.length - 1 && <wbr />}</span>);
}

function UsageGroup({ title, kind, items }: { title: string; kind: "METRIC" | "DASHBOARD"; items: Array<{ key: string; label: string; href: string }> }) {
  return <section aria-label={title}>
    <h3 className="type-group mb-1.5 text-label">{title} <span className="tabular-nums">{items.length}</span></h3>
    <ul className="space-y-2">{items.map((item) => <li key={item.key} className="flex min-w-0 items-start gap-2.5"><KindIcon kind={kind} /><Link href={item.href} className={`type-body min-w-0 pt-0.5 break-words ${SEMANTIC_LINK_CLASS}`}>{item.label}</Link></li>)}</ul>
  </section>;
}

function CalculationPath({ workspaceId, graph }: { workspaceId: string; graph: LineageFocusCone }) {
  const kinds = ["RELATION", "SEMANTIC_MODEL", "METRIC"] as const;
  const labels = { RELATION: ["Warehouse table", "Warehouse tables"], SEMANTIC_MODEL: ["Model", "Models"], METRIC: ["Metric", "Metrics"] };
  const steps = calculationPathStages(graph).flatMap((stage) => kinds.flatMap((kind) => {
    const resources = stage.resources.filter((resource) => resource.resource.kind === kind).sort((left, right) => left.label.localeCompare(right.label));
    return resources.length ? [{ depth: stage.depth, kind, resources }] : [];
  }));
  return <ol>{steps.map((step, index) => <li key={`${step.depth}:${step.kind}`} data-testid="calculation-path-stage" data-depth={step.depth} className="relative flex gap-3 pb-4 last:pb-0">
    {index < steps.length - 1 && <span className="absolute bottom-0 left-[11.5px] top-6 w-px bg-label" aria-hidden="true" />}
    <KindIcon kind={step.kind} />
    <div className="min-w-0 space-y-1 pt-0.5">
      <span className="type-meta block text-foreground-secondary">{step.depth === 0 ? step.kind === "METRIC" ? "This metric" : "This model" : labels[step.kind][step.resources.length === 1 ? 0 : 1]}</span>
      {step.resources.map((resource) => <div key={resource.resource.id} className="min-w-0">
        {resource.resource.kind === "RELATION" && resource.connector && <span className="type-meta block text-foreground-secondary">{resource.connector.displayName}</span>}
        {resource.resource.kind === graph.focus.resource.kind && resource.resource.id === graph.focus.resource.id ? <span className="type-body block break-words text-heading">{resource.label}</span> : <Link href={semanticResourceHref(workspaceId, resource)} className={`${resource.resource.kind === "RELATION" ? "type-mono" : "type-body break-words"} block ${SEMANTIC_LINK_CLASS}`}>{resource.resource.kind === "RELATION" ? addressWithBreaks(resource.address) : resource.label}</Link>}
      </div>)}
    </div>
  </li>)}</ol>;
}

export function SemanticCalculationRail({ workspaceId, principalId, kind, id, editing, displayOnly = false, children }: { workspaceId: string; principalId: string; kind: "METRIC" | "SEMANTIC_MODEL"; id: string; editing: boolean; displayOnly?: boolean; children?: ReactNode }) {
  const graph = useQuery({ queryKey: queryKeys.lineage.focus(workspaceId, principalId, kind, id), queryFn: ({signal}) => lineageAPI.getFocusCone(workspaceId, {kind, id}, signal), enabled: Boolean(principalId && id), retry: false });
  const models = useQuery({ queryKey: queryKeys.semanticModels.all(workspaceId, principalId), queryFn: () => semanticModelAPI.list(workspaceId), enabled: Boolean(principalId) });
  const metrics = useQuery({ queryKey: queryKeys.metrics.all(workspaceId, principalId), queryFn: () => metricAPI.list(workspaceId), enabled: Boolean(principalId) });
  const { connectors, loading: connectorsLoading, error: connectorsError } = useConnectors({workspaceId});
  const currentMetric = metrics.data?.find((metric) => metric.id === id);
  const currentModel = models.data?.find((model) => model.id === id);
  const noConnector = !connectorsLoading && !connectorsError && (kind === "METRIC" && currentMetric && metrics.data && models.data ? !hasMetricSourceConnector(currentMetric, metrics.data, models.data, connectors.map((connector)=>connector.id)) : kind === "SEMANTIC_MODEL" && currentModel ? !currentModel.sourceRelations.some((source)=>connectors.some((connector)=>connector.id===source.connectorId)) : false);
  const problem = kind === "METRIC" && currentMetric && metrics.data && models.data ? referenceProblem(currentMetric, metrics.data, models.data) : null;
  const brokenReference = problem ?? (graph.data?.missingDefinitions.length ? graph.data.missingDefinitions.map((resource) => resource.label).join(", ") : null);
  const usedInMetrics = graph.data?.descendants.filter((resource) => resource.resource.kind === "METRIC" && !resource.missing && graph.data.edges.some((edge) => edge.parent.kind === kind && edge.parent.id === id && edge.dependent.kind === "METRIC" && edge.dependent.id === resource.resource.id)) ?? [];
  const openMap = <Link href={webPaths.lineageResource(workspaceId, kind, id)} className={buttonClassName("ghost", "sm", "")}><Network aria-hidden="true" />Open map</Link>;
  const referenceStatus = brokenReference ? `Broken reference · ${brokenReference}` : models.isError || metrics.isError || graph.isError ? "Reference status unavailable" : models.isPending || metrics.isPending || graph.isPending ? "Checking references…" : "No reference issues";
  const referencesHealthy = !brokenReference && !models.isError && !metrics.isError && !graph.isError && !models.isPending && !metrics.isPending && !graph.isPending;
  return <aside className="min-w-0 space-y-4" aria-label={editing ? "Affects if saved" : kind === "METRIC" ? "Calculation path" : "Usage and status"}>
    {editing && children}
    {(editing || kind === "METRIC") && <DetailPanel id={editing ? "rail-affects-heading" : "rail-path-heading"} title={editing ? "Affects if saved" : "Calculation path"} titleRole="item" action={openMap}>
      {displayOnly ? <p className="type-body text-foreground-secondary">Display only — numbers don’t change. New runs use this format.</p> : graph.isPending ? <StateBlock kind="loading" layout="inline" title="Loading calculation path" /> : graph.isError ? <StateBlock kind="error" layout="inline" title="Calculation path could not be loaded" description={graph.error.message} /> : graph.data && (editing ? <AffectsList workspaceId={workspaceId} graph={graph.data} metricDefinitions={metrics.data} limit={8} /> : <>
        <CalculationPath workspaceId={workspaceId} graph={graph.data} />
      </>)}
    </DetailPanel>}
    {!editing && kind === "SEMANTIC_MODEL" && !graph.data && (graph.isPending || graph.isError) && <DetailPanel id="rail-used-heading" title="Used in" titleRole="item" action={openMap}>{graph.isPending ? <StateBlock kind="loading" layout="inline" title="Loading usage" /> : <StateBlock kind="error" layout="inline" title="Usage could not be loaded" description={graph.error?.message} />}</DetailPanel>}
    {!editing && graph.data && <DetailPanel id="rail-used-heading" title="Used in" titleRole="item" action={kind === "SEMANTIC_MODEL" ? openMap : undefined}>
      {usedInMetrics.length || graph.data.dashboardUsages?.length ? <div className="space-y-4">
        {usedInMetrics.length > 0 && <UsageGroup title="Metrics" kind="METRIC" items={usedInMetrics.map((resource) => ({ key: resource.resource.id, label: resource.label, href: semanticResourceHref(workspaceId, resource) }))} />}
        {graph.data.dashboardUsages?.length ? <UsageGroup title="Dashboards" kind="DASHBOARD" items={graph.data.dashboardUsages.map((usage) => ({ key: usage.dashboardId, label: usage.title, href: webPaths.dashboard(workspaceId, usage.dashboardId) }))} /> : null}
      </div> : <p className="type-body text-foreground-secondary">No metrics or dashboards use this yet.</p>}
    </DetailPanel>}
    {!editing && <DetailPanel id="rail-status-heading" title="Status" titleRole="item">
      <div className="space-y-3" aria-label="Status">
        {!(referencesHealthy && children) && <p className={`type-body flex items-start gap-2 ${brokenReference ? "text-error" : "text-foreground"}`}>{brokenReference ? <CircleAlert className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" /> : referencesHealthy ? <CircleCheck className="mt-0.5 h-4 w-4 shrink-0 text-success" aria-hidden="true" /> : null}<span>{referenceStatus}</span></p>}
        {noConnector && <p className="type-meta flex items-center gap-2 text-error"><CircleAlert className="h-3.5 w-3.5" aria-hidden="true" />No executable connector</p>}
        {connectorsError && <p className="type-meta text-foreground-secondary">Connector status unavailable</p>}
        {children}
      </div>
    </DetailPanel>}
  </aside>;
}
