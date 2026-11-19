import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Handle, MiniMap, Position, ReactFlow, ReactFlowProvider, Controls, ControlButton, useNodesInitialized, useReactFlow, useStore, useViewport, type Node, type NodeProps, type Edge } from "@xyflow/react";
import { Plus, ExternalLink, Maximize } from "lucide-react";
import type { LineageFocusCone, LineageResource, LineageResourceRef } from "@/api/lineage";
import { AffectsList } from "@/components/semantics/AffectsList";
import { KindIcon } from "@/components/resource-detail/KindIcon";
import { Button } from "@/components/ui/Button";
import { Link } from "@/lib/navigation";
import { webPaths } from "@/lib/web-paths";
import "@xyflow/react/dist/style.css";

import type { Metric } from "@/lib/types/metric";

const key = (ref: LineageResourceRef) => `${ref.kind}:${ref.id}`;

export function visibleLineageKeys(graph: LineageFocusCone, expanded: Set<string>): Set<string> {
  const visible = new Set([key(graph.focus.resource)]);
  for (const edge of graph.edges) {
    if (expanded.has(key(edge.parent))) visible.add(key(edge.dependent));
    if (expanded.has(key(edge.dependent))) visible.add(key(edge.parent));
  }
  for (const usage of graph.dashboardUsages ?? []) {
    if (usage.metricIds.some((id) => expanded.has(`METRIC:${id}`))) visible.add(`DASHBOARD:${usage.dashboardId}`);
  }
  return visible;
}

export function selectedPathKeys(graph: LineageFocusCone, selected: string): Set<string> {
  const result = new Set([selected]);
  const selectedDashboard = graph.dashboardUsages?.find((usage) => `DASHBOARD:${usage.dashboardId}` === selected);
  if (selectedDashboard) {
    const pending = selectedDashboard.metricIds.map((metricId) => `METRIC:${metricId}`);
    while (pending.length) {
      const current = pending.pop()!;
      if (result.has(current)) continue;
      result.add(current);
      for (const edge of graph.edges) if (key(edge.dependent) === current) pending.push(key(edge.parent));
    }
    return result;
  }
  for (const direction of ["up", "down"] as const) {
    const visited = new Set([selected]);
    const pending = [selected];
    while (pending.length) {
      const current = pending.shift()!;
      for (const edge of graph.edges) {
        const from = direction === "up" ? key(edge.dependent) : key(edge.parent);
        const next = direction === "up" ? key(edge.parent) : key(edge.dependent);
        if (from !== current || visited.has(next)) continue;
        visited.add(next); result.add(next); pending.push(next);
      }
    }
  }
  for (const usage of graph.dashboardUsages ?? []) if (usage.metricIds.some((id) => result.has(`METRIC:${id}`))) result.add(`DASHBOARD:${usage.dashboardId}`);
  return result;
}

const KIND_LABELS = { RELATION: "Table", SEMANTIC_MODEL: "Model", METRIC: "Metric", DASHBOARD: "Dashboard" } as const;
type MapData = { label: string; kind: LineageResourceRef["kind"] | "DASHBOARD"; missing: boolean; selected: boolean; canExpand: boolean; onSelect: () => void; onExpand: () => void; href?: string; connectorName?: string };
function MapNode({ data }: NodeProps<Node<MapData>>) {
  return <div className={`relative h-full rounded-md border bg-background ${data.selected ? "border-label bg-surface" : "border-border"}`}>
    <Handle type="target" position={Position.Left} className="!border-0 !bg-label" /><Handle type="source" position={Position.Right} className="!border-0 !bg-label" />
    <button type="button" className="nodrag flex w-full items-start gap-3 rounded-md px-4 py-3 text-left focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus-ring" onClick={data.onSelect} aria-label={`Select ${data.label}${data.connectorName ? ` from ${data.connectorName}` : ""}`} title={data.label}><KindIcon kind={data.kind} /><span className="min-w-0 flex-1"><span className={`type-control text-heading ${data.href ? "pr-5" : ""} ${data.kind === "DASHBOARD" ? "line-clamp-2 break-words" : "block truncate"}`}>{data.label}</span><span className="type-meta mt-1 block text-foreground-secondary">{data.missing ? "Missing definition" : data.connectorName ?? KIND_LABELS[data.kind]}</span></span></button>
    {data.canExpand && <button type="button" className="nodrag absolute -right-3 top-5 flex h-6 w-6 items-center justify-center rounded-[4px] border border-border bg-background hover:bg-surface focus-visible:outline focus-visible:outline-2 focus-visible:outline-focus-ring" onClick={data.onExpand} aria-label={`Expand ${data.label}`}><Plus className="h-3.5 w-3.5" aria-hidden="true" /></button>}
    {data.href && <Link href={data.href} className="nodrag absolute right-2 top-3" aria-label={`Open ${data.label}`}><ExternalLink className="h-3.5 w-3.5" aria-hidden="true" /></Link>}
  </div>;
}
function LaneHeader({ data }: NodeProps<Node<{ label: string; kind: string }>>) {
  return <h2 data-testid={`lineage-lane-${data.kind}`} className="type-group text-foreground-secondary">{data.label}</h2>;
}
const nodeTypes = { semanticMap: MapNode, laneHeader: LaneHeader };

function MapCanvas({ nodes, edges, focusKey }: { nodes: Node[]; edges: Edge[]; focusKey: string }) {
  const canvasRef = useRef<HTMLDivElement>(null);
  const nodesInitialized = useNodesInitialized({ includeHiddenNodes: true });
  const { fitView, getNode, getNodesBounds, setCenter, viewportInitialized } = useReactFlow();
  const viewport = useViewport();
  const canvasWidth = useStore((state) => state.width);
  const canvasHeight = useStore((state) => state.height);
  const bounds = getNodesBounds(nodes.filter((node) => node.type === "semanticMap"));
  const showOverview = nodesInitialized && viewportInitialized && canvasWidth > 0 && canvasHeight > 0 && (
    bounds.x * viewport.zoom + viewport.x < 0 ||
    bounds.y * viewport.zoom + viewport.y < 0 ||
    (bounds.x + bounds.width) * viewport.zoom + viewport.x > canvasWidth ||
    (bounds.y + bounds.height) * viewport.zoom + viewport.y > canvasHeight
  );
  const fitCanvas = useCallback(() => {
    if (!nodesInitialized || !viewportInitialized || !canvasRef.current?.clientWidth || !canvasRef.current.clientHeight) return;
    if (window.matchMedia("(min-width: 1024px)").matches) {
      void fitView({ padding: 0.12, minZoom: 0.6, maxZoom: 1 });
      return;
    }
    const focus = getNode(focusKey);
    if (focus) void setCenter(focus.position.x + (focus.measured?.width ?? focus.width ?? 220) / 2, focus.position.y + (focus.measured?.height ?? focus.height ?? 76) / 2, { zoom: 1 });
  }, [fitView, focusKey, getNode, nodesInitialized, setCenter, viewportInitialized]);

  const fitCallbackRef = useRef(fitCanvas);
  const fitFrameRef = useRef<number | null>(null);
  const scheduleFit = useCallback(() => {
    if (fitFrameRef.current !== null) return;
    fitFrameRef.current = window.requestAnimationFrame(() => {
      fitFrameRef.current = null;
      fitCallbackRef.current();
    });
  }, []);

  useEffect(() => {
    fitCallbackRef.current = fitCanvas;
    scheduleFit();
  }, [fitCanvas, scheduleFit]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    let observedWidth = canvas.clientWidth;
    let observedHeight = canvas.clientHeight;
    const observer = new ResizeObserver(() => {
      if (canvas.clientWidth === observedWidth && canvas.clientHeight === observedHeight) return;
      observedWidth = canvas.clientWidth;
      observedHeight = canvas.clientHeight;
      scheduleFit();
    });
    const desktop = window.matchMedia("(min-width: 1024px)");
    observer.observe(canvas);
    desktop.addEventListener("change", scheduleFit);
    scheduleFit();
    return () => {
      observer.disconnect();
      desktop.removeEventListener("change", scheduleFit);
      if (fitFrameRef.current !== null) window.cancelAnimationFrame(fitFrameRef.current);
      fitFrameRef.current = null;
    };
  }, [scheduleFit]);

  return <><div ref={canvasRef} className="relative h-[480px]" data-testid="lineage-map-canvas">
    <ReactFlow nodes={nodes} edges={edges} nodeTypes={nodeTypes} nodesDraggable={false} nodesConnectable={false} fitViewOptions={{ padding: 0.12, minZoom: 0.6, maxZoom: 1 }} minZoom={0.5} maxZoom={1.5} zoomOnScroll={false} panOnScroll={false} preventScrolling={false} zoomOnDoubleClick={false} zoomOnPinch zoomActivationKeyCode="Control" panActivationKeyCode={null} proOptions={{ hideAttribution: true }} aria-label="Lineage map">
      <Controls showInteractive={false} showFitView={false}><ControlButton className="react-flow__controls-fitview" onClick={fitCanvas} title="Fit view" aria-label="Fit view"><Maximize aria-hidden="true" /></ControlButton></Controls>
    </ReactFlow>
  </div>
    {showOverview && <div className="flex justify-end border-t border-border px-5 py-3" data-testid="lineage-map-overview"><MiniMap pannable zoomable={false} nodeColor={(node) => node.type === "laneHeader" ? "transparent" : "var(--label)"} maskColor="var(--surface)" style={{position:"relative",right:"auto",bottom:"auto",margin:0,background:"var(--background)",border:"1px solid var(--border)"}} aria-label="Map overview" /></div>}
  </>;
}

export function LineageMap({ workspaceId, graph, onSetFocus, metricDefinitions }: { workspaceId: string; graph: LineageFocusCone; onSetFocus: (ref: LineageResourceRef) => void; metricDefinitions?: Metric[] }) {
  const focusKey = key(graph.focus.resource);
  const [expansion, setExpansion] = useState<{focus: string; keys: Set<string>}>({focus: focusKey, keys:new Set([focusKey])});
  const [selection, setSelection] = useState<{focus: string; key: string}>({focus:focusKey,key:focusKey});
  const expanded = expansion.focus === focusKey ? expansion.keys : new Set([focusKey]);
  const selected = selection.focus === focusKey ? selection.key : focusKey;
  const visible = visibleLineageKeys(graph, expanded);
  const path = selectedPathKeys(graph, selected);
  const resources = useMemo(() => [...new Map([graph.focus, ...graph.ancestors, ...graph.descendants, ...graph.missingDefinitions].map((resource) => [key(resource.resource), resource])).values()], [graph]);
  const metricLayers = new Map<string, number>();
  const metricLayer = (resourceId: string, visited = new Set<string>()): number => {
    if (visited.has(resourceId)) return 0;
    const cached = metricLayers.get(resourceId);
    if (cached !== undefined) return cached;
    const nextVisited = new Set([...visited, resourceId]);
    const parentMetrics = graph.edges.filter((edge) => key(edge.dependent) === resourceId && edge.parent.kind === "METRIC");
    const layer = parentMetrics.length ? Math.max(...parentMetrics.map((edge) => metricLayer(key(edge.parent), nextVisited))) + 1 : 0;
    metricLayers.set(resourceId, layer);
    return layer;
  };
  const lane = (resource: LineageResource) => resource.resource.kind === "RELATION" ? 0 : resource.resource.kind === "SEMANTIC_MODEL" ? 1 : 2 + metricLayer(key(resource.resource));
  const visibleResources = resources.filter((resource) => visible.has(key(resource.resource)));
  const visibleDashboardUsages = (graph.dashboardUsages ?? []).filter((usage) => visible.has(`DASHBOARD:${usage.dashboardId}`));
  const upstreamCount = new Set([...graph.ancestors, ...graph.missingDefinitions].filter((resource) => visible.has(key(resource.resource))).map((resource) => key(resource.resource))).size;
  const downstreamCount = graph.descendants.filter((resource) => visible.has(key(resource.resource))).length + visibleDashboardUsages.length;
  const dashboardLane = Math.max(2, ...visibleResources.map(lane)) + 1;
  const occupiedLanes = [...new Set([...visibleResources.map(lane), ...visibleDashboardUsages.map(() => dashboardLane)])].sort((left, right) => left - right);
  const lanePositions = new Map(occupiedLanes.map((laneIndex, columnIndex) => [laneIndex, columnIndex * 280]));
  const laneHeaders: Node<{ label: string; kind: string }>[] = occupiedLanes.map((laneIndex) => {
    const kind = laneIndex === 0 ? "RELATION" : laneIndex === 1 ? "SEMANTIC_MODEL" : laneIndex === dashboardLane ? "DASHBOARD" : "METRIC";
    return { id: `LANE:${kind}:${laneIndex}`, data: { label: kind === "DASHBOARD" ? "Used in" : KIND_LABELS[kind], kind }, type: "laneHeader", position: { x: lanePositions.get(laneIndex)!, y: 0 }, width: 220, height: 24, selectable: false, focusable: false, draggable: false, connectable: false };
  });
  const rowCounts = new Map<number, number>();
  const nextRow = (laneIndex: number) => { const row = rowCounts.get(laneIndex) ?? 0; rowCounts.set(laneIndex, row + 1); return row; };
  const nodes: Node<MapData>[] = visibleResources.sort((left,right)=>lane(left)-lane(right)||left.label.localeCompare(right.label)).map((resource) => {
    const resourceId = key(resource.resource); const laneIndex = lane(resource);
    return { id:resourceId, type:"semanticMap", position:{x:lanePositions.get(laneIndex)!,y:nextRow(laneIndex)*110+50}, width:220,height:76, style:{opacity:path.has(resourceId)?1:0.3}, data:{label:resource.label,connectorName:resource.connector?.displayName,kind:resource.resource.kind,missing:resource.missing,selected:resourceId===selected,canExpand:graph.edges.some((edge) => (key(edge.parent)===resourceId&&!visible.has(key(edge.dependent)))||(key(edge.dependent)===resourceId&&!visible.has(key(edge.parent)))) || (graph.dashboardUsages ?? []).some((usage)=>usage.metricIds.includes(resource.resource.id)&&!visible.has(`DASHBOARD:${usage.dashboardId}`)),onSelect:()=>setSelection({focus:focusKey,key:resourceId}),onExpand:()=>setExpansion({focus:focusKey,keys:new Set([...expanded,resourceId])})} };
  });
  const edges: Edge[] = graph.edges.filter((edge) => visible.has(key(edge.parent))&&visible.has(key(edge.dependent))).map((edge) => ({id:`${key(edge.parent)}>${key(edge.dependent)}`,source:key(edge.parent),target:key(edge.dependent),type:"smoothstep",style:{stroke:"var(--label)",opacity:path.has(key(edge.parent))&&path.has(key(edge.dependent))?1:0.15}}));
  for (const usage of visibleDashboardUsages) {
    const id=`DASHBOARD:${usage.dashboardId}`;
    const active = path.has(id);
    nodes.push({id,type:"semanticMap",position:{x:lanePositions.get(dashboardLane)!,y:nextRow(dashboardLane)*110+50},width:220,height:96,style:{opacity:active?1:0.3},data:{label:usage.title,kind:"DASHBOARD",missing:false,selected:selected===id,canExpand:false,onSelect:()=>setSelection({focus:focusKey,key:id}),onExpand:()=>undefined,href:webPaths.dashboard(workspaceId,usage.dashboardId)}});
    for (const metricId of usage.metricIds) if (visible.has(`METRIC:${metricId}`)) edges.push({id:`${metricId}>${id}`,source:`METRIC:${metricId}`,target:id,type:"smoothstep",style:{stroke:"var(--label)",opacity:path.has(`METRIC:${metricId}`)&&path.has(id)?1:0.15}});
  }
  const selectedResource = resources.find((resource)=>key(resource.resource)===selected);
  return <div className="min-h-0 flex-1 overflow-y-auto" data-testid="lineage-map">
    <div className="flex items-center justify-between border-b border-border px-5 py-3"><div className="min-w-0"><p className="type-status tabular-nums text-foreground-secondary">{upstreamCount} upstream · {downstreamCount} downstream</p><p className="type-status text-foreground-secondary">One step from the focus · Expand with +</p></div><Button variant="ghost" size="sm" disabled={!selectedResource || selectedResource.missing || selected===focusKey} onClick={()=>selectedResource&&onSetFocus(selectedResource.resource)}>Focus selected</Button></div>
    <ReactFlowProvider key={[...visible].sort().join("|")}>
      <MapCanvas nodes={[...laneHeaders, ...nodes]} edges={edges} focusKey={focusKey} />
    </ReactFlowProvider>
    {graph.missingDefinitions.length > 0 && <section className="border-t border-border px-5 py-3" aria-label="Broken references"><h2 className="type-group text-heading">Broken references</h2><ul>{graph.missingDefinitions.map((resource)=><li key={key(resource.resource)} className="type-body text-error">Missing definition · {resource.label}</li>)}</ul></section>}
    <section className="border-t border-border px-5 py-5"><h2 className="type-item mb-3 text-heading">Affects</h2><AffectsList workspaceId={workspaceId} graph={graph} metricDefinitions={metricDefinitions} /></section>
  </div>;
}
