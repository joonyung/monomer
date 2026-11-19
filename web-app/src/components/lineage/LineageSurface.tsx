import { useEffect, useRef, useState, type ReactNode } from "react";
import {
  ArrowLeft,
  BarChart3,
  Box,
  PanelRightClose,
  PanelRightOpen,
  RefreshCw,
  Search,
  ShieldAlert,
  Table2,
  X,
} from "lucide-react";
import type {
  LineageFocusCone,
  LineageMetricDetail,
  LineageRelationColumn,
  LineageResource,
  LineageResourceDetail,
  LineageResourceRef,
  LineageSemanticDimension,
  LineageSemanticEntity,
  LineageSemanticMeasure,
} from "@/api/lineage";
import { ConnectorTypeMark } from "@/components/connectors/ConnectorTypeMark";
import type {
  LineageResourceKind,
} from "@/api/lineage";
import { Button, buttonClassName } from "@/components/ui/Button";
import { IconButton } from "@/components/ui/IconButton";
import { Input, NativeSelect } from "@/components/ui/Input";
import { StateBlock } from "@/components/ui/StateBlock";
import { Link } from "@/lib/navigation";
import { webPaths } from "@/lib/web-paths";
import { catalogHref, tableAddressFromQualifiedName, tableCatalogHref } from "@/lib/catalog/catalog-links";

import "@xyflow/react/dist/style.css";
import type { Metric } from "@/lib/types/metric";
import { LineageMap } from "@/components/lineage/LineageMap";

export type LineageViewMode = "graph" | "list";

export type LineageSurfaceState = "ready" | "loading" | "empty" | "error" | "unavailable";

export interface LineageResourceSearchProps {
  kind: LineageResourceKind;
  query: string;
  resources: LineageResource[];
  loading: boolean;
  loadingMore: boolean;
  error: string | null;
  hasNext: boolean;
  onKindChange: (kind: LineageResourceKind) => void;
  onQueryChange: (query: string) => void;
  onLoadMore: () => void;
  onRetry: () => void;
  onSubmit: (focus: LineageResourceRef) => void;
}

export interface LineageSurfaceProps {
  workspaceId: string;
  graph: LineageFocusCone | null;
  state: LineageSurfaceState;
  errorMessage?: string | null;
  onSetFocus: (focus: LineageResourceRef) => void;
  onRetry?: () => void;
  initialView?: LineageViewMode;
  resourceSearch?: LineageResourceSearchProps;
  canCreateSemanticModel: boolean;
  metricDefinitions?: Metric[];
}

const RESOURCE_KINDS: LineageResourceKind[] = ["RELATION", "SEMANTIC_MODEL", "METRIC"];

const RESOURCE_LABELS: Record<LineageResourceKind, string> = {
  RELATION: "Table",
  SEMANTIC_MODEL: "Model",
  METRIC: "Metric",
};

const RESOURCE_PLURAL_LABELS: Record<LineageResourceKind, string> = {
  RELATION: "tables",
  SEMANTIC_MODEL: "models",
  METRIC: "metrics",
};

export function resourceKey(ref: LineageResourceRef): string {
  return `${ref.kind}:${ref.id}`;
}

export function readableEnumLabel(value: string): string {
  const words = value.toLowerCase().replaceAll("_", " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export function resourceOwnerLink(workspaceId: string, resource: LineageResource): { label: string; href: string } | null {
  if (resource.missing) return null;
  if (resource.resource.kind === "METRIC") return { label: "Open metric", href: webPaths.metric(workspaceId, resource.resource.id) };
  if (resource.resource.kind === "SEMANTIC_MODEL") return { label: "Open model", href: webPaths.semanticModel(workspaceId, resource.resource.id) };
  if (!resource.connector) return null;
  const table = tableAddressFromQualifiedName(resource.connector.id, resource.connector.type, resource.address);
  return { label: "Open in Catalog", href: table ? tableCatalogHref(workspaceId, table) : catalogHref(workspaceId, { connector: resource.connector.id }) };
}

function ResourceIcon({ kind, className }: { kind: LineageResourceKind; className: string }) {
  if (kind === "RELATION") return <Table2 className={className} aria-hidden="true" />;
  if (kind === "SEMANTIC_MODEL") return <Box className={className} aria-hidden="true" />;
  return <BarChart3 className={className} aria-hidden="true" />;
}

function DetailLabel({ children }: { children: ReactNode }) {
  return <h3 className="type-group text-foreground-secondary">{children}</h3>;
}

function ResourceDetailView({ detail }: { detail: LineageResourceDetail }) {
  if (!detail) return null;
  if (detail.relation) return <RelationDetail detail={detail.relation} />;
  if (detail.semanticModel) return <SemanticModelDetail detail={detail.semanticModel} />;
  if (detail.metric) return <MetricDetail detail={detail.metric} />;
  return null;
}

function RelationDetail({ detail }: { detail: NonNullable<LineageResourceDetail["relation"]> }) {
  return <section className="border-t border-border pt-4"><div className="flex items-center justify-between gap-2"><DetailLabel>Columns</DetailLabel><span className="type-status tabular-nums text-foreground-secondary">{detail.columns.length}</span></div><div className="mt-2 overflow-hidden border border-border"><table className="type-status w-full border-collapse text-left"><caption className="sr-only">Table columns</caption><thead className="type-group bg-surface text-foreground-secondary"><tr><th className="px-2 py-1.5">Column</th><th className="px-2 py-1.5">Type</th><th className="px-2 py-1.5">Null</th><th className="px-2 py-1.5">Key</th></tr></thead><tbody className="divide-y divide-border-light">{detail.columns.map((column) => <RelationColumnRow key={column.name} column={column} />)}</tbody></table></div></section>;
}

function RelationColumnRow({ column }: { column: LineageRelationColumn }) {
  const keyLabel = [column.primaryKey ? "PK" : null, column.foreignKey ? "FK" : null].filter(Boolean).join(" · ") || "—";
  return <tr><th scope="row" className="max-w-[120px] truncate px-2 py-2 font-normal text-foreground" title={column.name}>{column.name}</th><td className="px-2 py-2 font-mono text-foreground-secondary">{column.nativeType}</td><td className="px-2 py-2 text-foreground-secondary">{column.nullable ? "Yes" : "No"}{column.hasDefault ? " · default" : ""}</td><td className="px-2 py-2 text-foreground-secondary">{keyLabel}</td></tr>;
}

type SemanticElement = LineageSemanticEntity | LineageSemanticDimension | LineageSemanticMeasure;

function SemanticModelDetail({ detail }: { detail: NonNullable<LineageResourceDetail["semanticModel"]> }) {
  return <section className="border-t border-border pt-4"><DetailLabel>Model definition</DetailLabel><div className="mt-3 space-y-4"><ElementGroup label="Row keys and links" items={detail.entities} kind="entity" /><ElementGroup label="Slice by" items={detail.dimensions} kind="dimension" /><ElementGroup label="Aggregate metrics" items={detail.measures} kind="measure" /></div></section>;
}

function ElementGroup({ label, items, kind }: { label: string; items: SemanticElement[]; kind: "entity" | "dimension" | "measure" }) {
  if (items.length === 0) return null;
  return <div><div className="mb-1.5 flex items-center justify-between"><span className="type-status text-foreground">{label}</span><span className="type-status tabular-nums text-foreground-secondary">{items.length}</span></div><ul className="divide-y divide-border-light border border-border">{items.map((item) => <li key={item.name} className="px-2.5 py-2"><div className="flex items-start justify-between gap-2"><span className="type-status min-w-0 truncate text-foreground" title={item.name}>{item.name}</span><span className="type-group flex-none text-foreground-secondary">{elementMeta(item, kind)}</span></div>{item.label && item.label !== item.name ? <p className="type-status mt-0.5 truncate text-foreground-secondary">{item.label}</p> : null}{item.description ? <p className="type-status mt-1 line-clamp-2 text-foreground-secondary">{item.description}</p> : null}</li>)}</ul></div>;
}

function elementMeta(item: SemanticElement, kind: "entity" | "dimension" | "measure") {
  if (kind === "dimension") {
    const dimension = item as LineageSemanticDimension;
    return `${readableEnumLabel(dimension.type)}${dimension.granularity ? ` · ${readableEnumLabel(dimension.granularity)}` : ""}`;
  }
  if (kind === "measure") return readableEnumLabel((item as LineageSemanticMeasure).aggregation);
  return ({ PRIMARY: "Row key", FOREIGN: "Links to", UNIQUE: "Unique key", NATURAL: "Natural key" })[(item as LineageSemanticEntity).type];
}

function MetricDetail({ detail }: { detail: LineageMetricDetail }) {
  return <section className="border-t border-border pt-4"><DetailLabel>Metric definition</DetailLabel><div className="type-status mt-2 space-y-2"><div className="flex items-center justify-between gap-3"><span className="text-foreground-secondary">Type</span><span className="text-foreground">{({ SIMPLE: "Aggregate metric", RATIO: "Ratio", DERIVED: "Formula", CUMULATIVE: "Running total", CONVERSION: "Conversion" })[detail.type]}</span></div>{detail.timeGranularity ? <div className="flex items-center justify-between gap-3"><span className="text-foreground-secondary">Time grain</span><span className="text-foreground">{readableEnumLabel(detail.timeGranularity)}</span></div> : null}{detail.inputs.length > 0 ? <div className="border-t border-border-light pt-2"><span className="text-foreground-secondary">Inputs</span><ul className="mt-1 divide-y divide-border-light border border-border">{detail.inputs.map((input) => <li key={`${input.kind}:${input.name}:${input.semanticModelName ?? ""}`} className="flex items-center justify-between gap-2 px-2 py-1.5"><span className="min-w-0 truncate text-foreground" title={input.name}>{input.name}</span><span className="type-status flex-none text-foreground-secondary">{input.kind === "MEASURE" && input.semanticModelName ? `${input.semanticModelName} · aggregate metric` : input.kind.toLowerCase()}</span></li>)}</ul></div> : null}</div></section>;
}

function ResourceInspector({ workspaceId, graph, onClose }: { workspaceId: string; graph: LineageFocusCone; onClose: () => void }) {
  const owner = resourceOwnerLink(workspaceId, graph.focus);
  return <aside id="lineage-resource-drawer" data-testid="lineage-resource-drawer" className="w-full shrink-0 overflow-y-auto border-l border-border p-5 lg:w-80" aria-label="Resource details"><div className="mb-4 flex items-center justify-between"><h2 className="type-section text-heading">{graph.focus.label}</h2><IconButton label="Close resource details" icon={X} onClick={onClose} /></div>{owner && <Link href={owner.href} className="type-body underline underline-offset-4">{owner.label}</Link>}<ResourceDetailView detail={graph.detail} /></aside>;
}

function LineageResourceSearch(props: LineageResourceSearchProps) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const resultsVisible = open && props.query.trim().length > 0;
  useEffect(() => {
    if (!resultsVisible) return;
    const closeOnOutsidePointer = (event: PointerEvent) => {
      if (!(event.target instanceof globalThis.Node) || !containerRef.current?.contains(event.target)) setOpen(false);
    };
    document.addEventListener("pointerdown", closeOnOutsidePointer);
    return () => document.removeEventListener("pointerdown", closeOnOutsidePointer);
  }, [resultsVisible]);
  return <div ref={containerRef} className="relative w-full max-w-[420px]">
    <div className="flex items-center gap-2">
      <div className="relative min-w-0 flex-1">
        <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-foreground-secondary" aria-hidden="true" />
        <Input
          value={props.query}
          onChange={(event) => { props.onQueryChange(event.target.value); setOpen(true); }}
          onFocus={() => setOpen(true)}
          onKeyDown={(event) => { if (event.key === "Escape") setOpen(false); }}
          className="pl-9"
          placeholder={`Search ${RESOURCE_PLURAL_LABELS[props.kind]}`}
          aria-label="Search resources"
          aria-expanded={resultsVisible}
          aria-controls="lineage-resource-search-results"
        />
      </div>
      <NativeSelect value={props.kind} onChange={(event) => props.onKindChange(event.target.value as LineageResourceKind)} className="basis-40" aria-label="Resource kind">
        <option value="RELATION">Tables</option>
        <option value="SEMANTIC_MODEL">Models</option>
        <option value="METRIC">Metrics</option>
      </NativeSelect>
    </div>
    {resultsVisible ? <div id="lineage-resource-search-results" className="absolute left-0 top-full z-30 mt-1.5 w-full overflow-hidden rounded-md border border-border bg-background shadow-overlay">
      {props.loading ? <StateBlock kind="loading" layout="inline" title="Searching…" />
        : props.error ? <StateBlock kind="error" layout="inline" title={props.error} action={<Button type="button" variant="secondary" size="sm" onClick={props.onRetry}><RefreshCw aria-hidden="true" />Retry search</Button>} />
          : props.resources.length === 0 ? <StateBlock kind="empty" layout="inline" icon={Search} title={`No ${RESOURCE_PLURAL_LABELS[props.kind]} match "${props.query.trim()}".`} action={RESOURCE_KINDS.filter((kind) => kind !== props.kind).map((kind) => <Button key={kind} type="button" variant="secondary" size="sm" onClick={() => props.onKindChange(kind)}>Search {RESOURCE_PLURAL_LABELS[kind]}</Button>)} />
            : <ul className="max-h-[300px] divide-y divide-border-light overflow-y-auto">{props.resources.map((resource) => <li key={resourceKey(resource.resource)}><button type="button" onClick={() => { props.onSubmit(resource.resource); props.onQueryChange(""); setOpen(false); }} className="flex w-full items-center gap-2.5 px-3 py-2.5 text-left hover:bg-surface"><span className="text-foreground-secondary"><ResourceIcon kind={resource.resource.kind} className="h-3.5 w-3.5" /></span><span className="min-w-0 flex-1"><span className="type-meta block truncate text-foreground">{resource.label}</span><span className="type-status block truncate text-foreground-secondary">{resource.connector?.displayName ? `${resource.connector.displayName} · ` : ""}{resource.address}</span></span>{resource.connector ? <ConnectorTypeMark type={resource.connector.type} size="sm" /> : null}</button></li>)}</ul>}
      {props.hasNext ? <div className="flex justify-end border-t border-border px-3 py-2"><Button type="button" variant="secondary" size="sm" pending={props.loadingMore} onClick={props.onLoadMore}>{props.loadingMore ? "Loading…" : "Show more"}</Button></div> : null}
    </div> : null}
  </div>;
}

function SurfaceState({ workspaceId, state, errorMessage, onRetry, canCreateSemanticModel }: { workspaceId: string; state: Exclude<LineageSurfaceState, "ready">; errorMessage?: string | null; onRetry?: () => void; canCreateSemanticModel: boolean }) {
  const frame = "flex min-h-0 flex-1 flex-col bg-surface/25";
  if (state === "loading") return <div className={frame}><StateBlock kind="loading" layout="page" title="Loading Lineage…" /></div>;
  if (state === "empty") return <div className={frame}><StateBlock headingLevel={2} kind="empty" layout="page" icon={Box} title="No resources yet" description="Lineage appears once this workspace has tables in Catalog or semantic definitions." action={<><Link href={webPaths.catalog(workspaceId)} className={buttonClassName("secondary", "sm", "")}>Open Catalog</Link>{canCreateSemanticModel ? <Link href={webPaths.newSemanticModel(workspaceId)} className={buttonClassName("secondary", "sm", "")}>Describe a table</Link> : null}</>} /></div>;
  if (state === "unavailable") return <div className={frame}><StateBlock headingLevel={2} kind="empty" layout="page" icon={ShieldAlert} title="This resource isn't available" description="It may have been deleted, or you may not have access." action={<Link href={webPaths.lineage(workspaceId)} className={buttonClassName("secondary", "sm", "")}>Choose another resource</Link>} /></div>;
  return <div className={frame}><StateBlock headingLevel={2} kind="error" layout="page" title="Lineage could not be loaded" description={errorMessage ?? "The current graph is unavailable."} action={onRetry ? <Button variant="secondary" size="sm" onClick={onRetry}><RefreshCw aria-hidden="true" />Retry</Button> : undefined} /></div>;
}

function LineageHeader({ workspaceId, focus }: { workspaceId: string; focus?: LineageResource }) {
  const definitionHref = focus?.resource.kind === "METRIC" ? webPaths.metric(workspaceId, focus.resource.id) : focus?.resource.kind === "SEMANTIC_MODEL" ? webPaths.semanticModel(workspaceId, focus.resource.id) : null;
  return <header className="flex flex-shrink-0 flex-wrap items-end justify-between gap-4 border-b border-border bg-background px-5 py-5 sm:px-8"><div className="min-w-0 flex-1">{focus && definitionHref ? <Link href={definitionHref} className="type-control inline-flex max-w-full items-center gap-1.5 text-foreground-secondary transition-colors hover:text-foreground focus:outline-none focus-visible:underline"><ArrowLeft className="h-3.5 w-3.5 shrink-0" aria-hidden="true" /><span className="truncate">{focus.label}</span></Link> : <p className="type-group text-foreground-secondary">Workspace</p>}<h1 className="type-display mt-1 truncate text-heading">Lineage</h1>{focus ? <p className="type-meta mt-1.5 truncate text-foreground-secondary" title={focus.address}>{focus.label} · {RESOURCE_LABELS[focus.resource.kind]}</p> : null}</div></header>;
}

export function LineageSurface({ metricDefinitions,  workspaceId, graph, state, errorMessage, onSetFocus, onRetry, resourceSearch, canCreateSemanticModel }: LineageSurfaceProps) {

  const [drawerOpen, setDrawerOpen] = useState(() => typeof window !== "undefined" && (window.matchMedia?.("(min-width: 1536px)").matches ?? false));
  if (state !== "ready" || !graph) return <div className="flex h-full min-h-0 flex-col bg-background"><LineageHeader workspaceId={workspaceId} focus={graph?.focus} /><SurfaceState workspaceId={workspaceId} state={state === "ready" ? "unavailable" : state} errorMessage={errorMessage} onRetry={onRetry} canCreateSemanticModel={canCreateSemanticModel} /></div>;
  const handleSelect = (ref: LineageResourceRef) => { onSetFocus(ref); };
  return <div className="flex h-full min-h-0 flex-col bg-background" style={{ fontFeatureSettings: '"ss01"' }}>
    <LineageHeader workspaceId={workspaceId} focus={graph.focus} />
    <div className="flex min-h-0 flex-1 flex-col lg:flex-row">
      <main className="flex min-h-0 min-w-0 flex-1 flex-col">
        <div className="flex min-h-[56px] flex-wrap items-center justify-between gap-3 border-b border-border px-4 py-2 sm:px-5">
          {resourceSearch ? <LineageResourceSearch {...resourceSearch} /> : <div />}
          <div className="flex items-center gap-3">
            <IconButton
              label={drawerOpen ? "Hide resource details" : "Show resource details"}
              icon={drawerOpen ? PanelRightClose : PanelRightOpen}
              onClick={() => { setDrawerOpen((open) => !open); }}
              aria-expanded={drawerOpen}
              aria-controls="lineage-resource-drawer"
            />
          </div>
        </div>
        <LineageMap metricDefinitions={metricDefinitions} workspaceId={workspaceId} graph={graph} onSetFocus={handleSelect} />
      </main>
      {drawerOpen ? <ResourceInspector workspaceId={workspaceId} graph={graph} onClose={() => setDrawerOpen(false)} />
        : null}
    </div>
  </div>;
}
