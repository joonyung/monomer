import { useMemo, useState } from "react";
import { useSearch } from "@tanstack/react-router";
import type { LineageConnector, LineageFocusCone, LineageResource, LineageResourceRef, LineageResourceDetail } from "@/api/lineage";
import type { WorkspaceAccess } from "@/lib/auth/types";
import { hasWorkspaceRole } from "@/lib/auth/roles";
import { MainLayout } from "@/components/layout/MainLayout";
import { LineageSurface } from "@/components/lineage/LineageSurface";
import { Link, notFound } from "@/lib/navigation";

export type LineageFixtureScene = "empty" | "ready" | "peer" | "overflow" | "unavailable" | "loading" | "error";

export function requireLineageFixture(isDevelopmentBuild: boolean): void {
  if (!isDevelopmentBuild) notFound();
}

const ORGANIZATION_ID = "71000000-0000-0000-0000-000000000001";
const WORKSPACE_ID = "71000000-0000-0000-0000-000000000002";
const NORTHSTAR_CONNECTOR = { id: "71000000-0000-0000-0000-000000000008", displayName: "Northstar warehouse", type: "POSTGRESQL" as const };
const FINANCE_CONNECTOR = { id: "71000000-0000-0000-0000-000000000009", displayName: "Finance lakehouse", type: "TRINO" as const };

function resource(kind: LineageResource["resource"]["kind"], id: string, label: string, address: string, description: string | null = null, connector: LineageConnector = NORTHSTAR_CONNECTOR): LineageResource {
  return { resource: { kind, id }, label, address, description, missing: false, connector: kind === "RELATION" ? connector : null };
}

const orderRelation = resource("RELATION", "71000000-0000-0000-0000-000000000101", "orders_fact", "warehouse.analytics.orders_fact", "A Catalog table. Definitions and samples are intentionally not shown here.");
const financeOrderRelation = resource("RELATION", "71000000-0000-0000-0000-000000000107", "order_facts", "lakehouse.finance.order_facts", "The connector-specific table used by the same semantic model.", FINANCE_CONNECTOR);
const orderModel = resource("SEMANTIC_MODEL", "71000000-0000-0000-0000-000000000102", "Orders", "orders", "Semantic Model for order-level analysis.");
const orderMetric = resource("METRIC", "71000000-0000-0000-0000-000000000103", "Order count", "order_count", "A governed Metric derived from Orders.");
const revenueMetric = resource("METRIC", "71000000-0000-0000-0000-000000000104", "Revenue", "revenue", "A downstream Metric used in reporting.");
const refundRateMetric = resource("METRIC", "71000000-0000-0000-0000-000000000106", "Refund rate", "refund_rate", "A sibling Metric sharing the Orders parent.");
const pickerResources = [
  ...Array.from({ length: 34 }, (_, index) => resource(
    "METRIC",
    `71000000-0000-0000-0000-${String(400 + index).padStart(12, "0")}`,
    index === 0 ? "Net revenue" : `Metric ${String(index + 1).padStart(2, "0")}`,
    index === 0 ? "net_revenue" : `metric_${String(index + 1).padStart(2, "0")}`,
  )),
  orderModel,
  resource("SEMANTIC_MODEL", "71000000-0000-0000-0000-000000000405", "Customers", "customers"),
  orderRelation,
];

function edge(dependent: LineageResource, parent: LineageResource) {
  return { dependent: dependent.resource, parent: parent.resource };
}

function detailFor(resource: LineageResource): LineageResourceDetail {
  if (resource.resource.kind === "RELATION") {
    return {
      relation: { columns: [
        { name: "id", nativeType: "bigint", nullable: false, hasDefault: true, primaryKey: true, foreignKey: false },
        { name: "customer_id", nativeType: "bigint", nullable: false, hasDefault: false, primaryKey: false, foreignKey: true },
      ] },
      semanticModel: null,
      metric: null,
    };
  }
  if (resource.resource.kind === "SEMANTIC_MODEL") {
    return {
      relation: null,
      semanticModel: {
        entities: [{ name: "order", type: "PRIMARY", label: "Order", description: "The order grain." }],
        dimensions: [{ name: "status", type: "CATEGORICAL", granularity: null, label: "Status", description: "Current order status." }],
        measures: [{ name: "count", aggregation: "COUNT", label: "Order count", description: "Number of orders." }],
      },
      metric: null,
    };
  }
  return {
    relation: null,
    semanticModel: null,
    metric: { type: "SIMPLE", inputs: [{ kind: "MEASURE", name: "count", semanticModelName: resource.address }], timeGranularity: null },
  };
}

function makeFocusCone(graph: Omit<LineageFocusCone, "workspaceId" | "detail" | "missingDefinitions">): LineageFocusCone {
  return { ...graph, missingDefinitions: [], workspaceId: WORKSPACE_ID, detail: detailFor(graph.focus) };
}

export function makeLineageFixtureGraph(scene: LineageFixtureScene): LineageFocusCone | null {
  if (scene === "empty" || scene === "unavailable") return null;
  if (scene === "peer") {
    const customerModel = resource("SEMANTIC_MODEL", "71000000-0000-0000-0000-000000000105", "Customers", "customers", "Another Semantic Model sharing the customer entity name.");
    return makeFocusCone({
      focus: orderModel,
      ancestors: [orderRelation],
      descendants: [orderMetric],
      siblings: [],
      edges: [edge(orderModel, orderRelation), edge(orderMetric, orderModel)],
      siblingEdges: [],
      entityPeers: [{ resource: customerModel, sharedEntities: ["customer"], alreadyInCone: false }],
    });
  }
  if (scene === "overflow") {
    const ancestors = Array.from({ length: 16 }, (_, index) => resource("RELATION", `71000000-0000-0000-0000-${String(200 + index).padStart(12, "0")}`, `source_${String(index + 1).padStart(2, "0")}`, `warehouse.analytics.source_${String(index + 1).padStart(2, "0")}`));
    const descendants = Array.from({ length: 15 }, (_, index) => resource("METRIC", `71000000-0000-0000-0000-${String(300 + index).padStart(12, "0")}`, `Metric ${String(index + 1).padStart(2, "0")}`, `metric_${String(index + 1).padStart(2, "0")}`));
    return makeFocusCone({
      focus: orderModel,
      ancestors,
      descendants,
      siblings: [],
      edges: [...ancestors.map((ancestor) => edge(orderModel, ancestor)), ...descendants.map((descendant) => edge(descendant, orderModel))],
      siblingEdges: [],
      entityPeers: [],
    });
  }
  return makeFocusCone({
    focus: orderMetric,
    ancestors: [orderModel, orderRelation, financeOrderRelation],
    descendants: [revenueMetric],
    siblings: [refundRateMetric],
    edges: [edge(orderModel, orderRelation), edge(orderModel, financeOrderRelation), edge(orderMetric, orderModel), edge(revenueMetric, orderMetric)],
    siblingEdges: [edge(refundRateMetric, orderModel)],
    entityPeers: [],
  });
}

const fixtureAccess: WorkspaceAccess = {
  granted: true, organizationId: ORGANIZATION_ID, role: "EXPLORER", };

export default function LineageAccessFixture() {
  requireLineageFixture(import.meta.env.DEV);
  const { scene = "ready", pickerState = "ready" } = useSearch({ from: "/dev/lineage-access-fixture" });
  return <LineageFixture scene={scene} pickerState={pickerState} />;
}

function LineageFixture({ scene = "ready", pickerState = "ready" }: { scene?: LineageFixtureScene; pickerState?: "ready" | "loading" | "error" | "empty" }) {
  requireLineageFixture(import.meta.env.DEV);
  const baseGraph = useMemo(() => {
    const current = makeLineageFixtureGraph(scene);
    return scene === "ready" && current ? { ...current, dashboardUsages: [{ dashboardId: "71000000-0000-0000-0000-000000000108", title: "Northstar Commerce · Business overview for all completed orders", queryNames: ["orders"], metricIds: [orderMetric.resource.id] }] } : current;
  }, [scene]);
  const [selectedRef, setSelectedRef] = useState<LineageResourceRef | null>(null);
  const graph = useMemo(() => {
    if (!baseGraph || !selectedRef) return baseGraph;
    const selected = [baseGraph.focus, ...baseGraph.ancestors, ...baseGraph.descendants, ...baseGraph.siblings, ...baseGraph.entityPeers.map((peer) => peer.resource), ...pickerResources].find((candidate) => resourceKey(candidate.resource) === resourceKey(selectedRef));
    if (!selected) return baseGraph;
    const resources = [baseGraph.focus, ...baseGraph.ancestors, ...baseGraph.descendants, ...baseGraph.siblings];
    const edges = [...baseGraph.edges, ...baseGraph.siblingEdges];
    const collect = (upstream: boolean) => {
      const found = new Set<string>();
      const pending = [resourceKey(selected.resource)];
      while (pending.length) {
        const key = pending.pop();
        for (const edge of edges) {
          if (resourceKey(upstream ? edge.dependent : edge.parent) !== key) continue;
          const next = resourceKey(upstream ? edge.parent : edge.dependent);
          if (!found.has(next)) { found.add(next); pending.push(next); }
        }
      }
      return resources.filter((candidate) => found.has(resourceKey(candidate.resource)));
    };
    const ancestors = collect(true);
    const descendants = collect(false);
    const visible = new Set([selected, ...ancestors, ...descendants].map((candidate) => resourceKey(candidate.resource)));
    return makeFocusCone({ focus: selected, ancestors, descendants, siblings: [], siblingEdges: [], entityPeers: [], edges: edges.filter((edge) => visible.has(resourceKey(edge.parent)) && visible.has(resourceKey(edge.dependent))), dashboardUsages: baseGraph.dashboardUsages?.filter((usage) => usage.metricIds.some((id) => [selected, ...descendants].some((resource) => resource.resource.kind === "METRIC" && resource.resource.id === id))) });
  }, [baseGraph, selectedRef]);
  const [pickerKind, setPickerKind] = useState<LineageResource["resource"]["kind"]>("METRIC");
  const [pickerQuery, setPickerQuery] = useState("");
  const [visiblePickerResources, setVisiblePickerResources] = useState(20);
  const filteredPickerResources = useMemo(() => pickerResources.filter((candidate) =>
    candidate.resource.kind === pickerKind &&
    (!pickerQuery.trim() || `${candidate.label} ${candidate.address}`.toLowerCase().includes(pickerQuery.trim().toLowerCase())),
  ), [pickerKind, pickerQuery]);
  const pickerModel = {
    kind: pickerKind,
    query: pickerQuery,
    resources: pickerState === "ready" ? filteredPickerResources.slice(0, visiblePickerResources) : [],
    loading: pickerState === "loading",
    loadingMore: false,
    error: pickerState === "error" ? "The current Resource inventory could not be loaded." : null,
    hasNext: pickerState === "ready" && visiblePickerResources < filteredPickerResources.length,
    onKindChange: (kind: LineageResource["resource"]["kind"]) => {
      setPickerKind(kind);
      setVisiblePickerResources(20);
    },
    onQueryChange: (query: string) => {
      setPickerQuery(query);
      setVisiblePickerResources(20);
    },
    onLoadMore: () => setVisiblePickerResources((count) => count + 20),
    onRetry: () => undefined,
    onSubmit: setSelectedRef,
  };

  return <MainLayout workspaceFixture={{ workspace: { id: WORKSPACE_ID, organizationId: ORGANIZATION_ID, publicPages: false, reportingTimeZone: "UTC", name: "Northstar Analytics" }, access: fixtureAccess }}>
    <div className="flex h-full min-h-0 flex-col">
      <nav className="flex flex-none flex-wrap items-center gap-1 border-b border-border bg-background px-4 py-2" aria-label="Lineage fixture scenes"><span className="type-group mr-2 text-foreground-secondary">Fixture</span>{(["empty", "ready", "peer", "overflow", "unavailable", "loading", "error"] as const).map((option) => <Link key={option} href={`/dev/lineage-access-fixture?scene=${option}`} className={`type-status rounded-[4px] px-2 py-1 ${scene === option ? "bg-accent text-accent-foreground" : "text-foreground-secondary hover:bg-surface"}`}>{option}</Link>)}</nav>
      <div className="min-h-0 flex-1"><LineageSurface workspaceId={WORKSPACE_ID} graph={graph} state={scene === "peer" || scene === "overflow" ? "ready" : scene} onSetFocus={setSelectedRef} resourceSearch={graph ? pickerModel : undefined} canCreateSemanticModel={hasWorkspaceRole(fixtureAccess, "EDITOR")} /></div>
    </div>
  </MainLayout>;
}

function resourceKey(ref: LineageResourceRef): string {
  return `${ref.kind}:${ref.id}`;
}
