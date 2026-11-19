import type { ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import type { LineageFocusCone, LineageResource } from "@/api/lineage";
import { queryKeys } from "@/lib/query/keys";
import { SEMANTIC_LINK_CLASS } from "@/components/resource-detail/semantic-links";
import { calculationPathStages, SemanticCalculationRail } from "./SemanticCalculationRail";

vi.mock("@/lib/navigation", () => ({ Link: ({ href, children, className }: { href: string; children: ReactNode; className?: string }) => <a href={href} className={className}>{children}</a> }));
vi.mock("@/hooks/useConnectors", () => ({ useConnectors: () => ({ connectors: [], loading: false, error: null }) }));

function resource(kind: LineageResource["resource"]["kind"], id: string, label: string): LineageResource {
  return { resource: { kind, id }, label, address: id, description: null, connector: null, missing: false };
}
const focus = resource("METRIC", "aov", "Gross average order value");
const revenue = resource("METRIC", "revenue", "Gross revenue");
const orders = resource("METRIC", "orders", "Completed orders");
const model = resource("SEMANTIC_MODEL", "model", "Northstar order lines");
const table = resource("RELATION", "table", "order_lines");
function graph(): LineageFocusCone {
  return {
    workspaceId: "workspace", focus, ancestors: [revenue, orders, model, table], descendants: [], siblings: [], missingDefinitions: [], siblingEdges: [], entityPeers: [], dashboardUsages: [],
    edges: [{ parent: revenue.resource, dependent: focus.resource }, { parent: orders.resource, dependent: focus.resource }, { parent: model.resource, dependent: revenue.resource }, { parent: model.resource, dependent: orders.resource }, { parent: table.resource, dependent: model.resource }],
    detail: { relation: null, semanticModel: null, metric: null },
  };
}

function renderRail(current: LineageFocusCone, { editing = false, children, models = [], metrics = [] }: { editing?: boolean; children?: ReactNode; models?: object[]; metrics?: object[] } = {}): string {
  const client = new QueryClient();
  client.setQueryData(queryKeys.lineage.focus("workspace", "principal", current.focus.resource.kind, current.focus.resource.id), current);
  client.setQueryData(queryKeys.semanticModels.all("workspace", "principal"), models);
  client.setQueryData(queryKeys.metrics.all("workspace", "principal"), metrics);
  return renderToStaticMarkup(<QueryClientProvider client={client}><SemanticCalculationRail workspaceId="workspace" principalId="principal" kind={current.focus.resource.kind as "METRIC" | "SEMANTIC_MODEL"} id={current.focus.resource.id} editing={editing}>{children}</SemanticCalculationRail></QueryClientProvider>);
}

describe("Calculation path stages", () => {
  it("groups parallel metric inputs at the same depth, with one connector between stages", () => {
    const current = graph();
    const client = new QueryClient();
    client.setQueryData(queryKeys.lineage.focus("workspace", "principal", "METRIC", "aov"), current);
    client.setQueryData(queryKeys.semanticModels.all("workspace", "principal"), []);
    client.setQueryData(queryKeys.metrics.all("workspace", "principal"), []);
    const markup = renderToStaticMarkup(<QueryClientProvider client={client}><SemanticCalculationRail workspaceId="workspace" principalId="principal" kind="METRIC" id="aov" editing={false} /></QueryClientProvider>);
    const inputStage = markup.match(/<li[^>]*data-depth="1"[^>]*>(.*?)<\/li>/)?.[1];
    expect(inputStage).toContain(">Metrics</span>");
    expect(inputStage).toContain("Gross revenue");
    expect(inputStage).toContain("Completed orders");
    expect(inputStage).toMatch(/Completed orders<\/a><\/div><div[^>]*><a[^>]*>Gross revenue<\/a>/);
    expect(inputStage).not.toContain(" · ");
    expect(inputStage?.match(/lucide-chart-column|lucide-bar-chart-3/g)).toHaveLength(1);
    expect(markup.match(/data-testid="calculation-path-stage"/g)).toHaveLength(4);
    expect(markup.match(/class="absolute bottom-0/g)).toHaveLength(3);
  });

  it("uses shortest upstream depth irrespective of edge or ancestor order and terminates cycles", () => {
    const current = graph();
    current.ancestors.reverse();
    current.edges.reverse();
    current.edges.push({ parent: focus.resource, dependent: table.resource });
    const stages = calculationPathStages(current);
    expect(stages.map((stage) => stage.depth)).toEqual([3, 2, 1, 0]);
    expect(stages.find((stage) => stage.depth === 1)?.resources.map((entry) => entry.resource.id).sort()).toEqual(["orders", "revenue"]);
    expect(stages.flatMap((stage) => stage.resources)).toHaveLength(5);
  });

  it("renders the current focus as text while retaining reference links for inputs", () => {
    const markup = renderRail(graph());
    const focusStage = markup.match(/<li[^>]*data-depth="0"[^>]*>(.*?)<\/li>/)?.[1];
    expect(focusStage).toContain("Gross average order value");
    expect(focusStage).toContain(">This metric</span>");
    expect(focusStage).toContain('class="type-body block break-words text-heading"');
    expect(focusStage).not.toContain("<a");
    const referenceLink = `class="type-body break-words block ${SEMANTIC_LINK_CLASS}">`;
    expect(markup).toContain(`${referenceLink}Northstar order lines`);
    expect(markup).toContain(`${referenceLink}Gross revenue`);
    expect(markup).toContain("top-6 w-px bg-label");
  });

  it("shows exact relation addresses in mono with the connector identity beside the reference", () => {
    const current = graph();
    current.ancestors = current.ancestors.map((entry) => entry.resource.kind === "RELATION" ? { ...entry, address: "warehouse.analytics.unbroken_order_lines_address", connector: { id: "connector", displayName: "Northstar warehouse", type: "POSTGRESQL" } } : entry);
    const markup = renderRail(current);
    expect(markup).toContain(">Northstar warehouse</span>");
    expect(markup).toContain(`class="type-mono block ${SEMANTIC_LINK_CLASS}"><span class="whitespace-nowrap">warehouse.<wbr/></span><span class="whitespace-nowrap">analytics.<wbr/></span><span class="whitespace-nowrap">unbroken_order_lines_address</span></a>`);
    expect(markup).not.toContain(">order_lines</a>");
  });

  it("keeps each connector's source table in its own block at the same depth", () => {
    const warehouse = { ...table, address: "analytics.orders", connector: { id: "c1", displayName: "Warehouse", type: "POSTGRESQL" as const } };
    const replica = { ...resource("RELATION", "replica", "orders_copy"), address: "replica.analytics.orders_copy", connector: { id: "c2", displayName: "Analytics replica", type: "POSTGRESQL" as const } };
    const current = { ...graph(), ancestors: [warehouse, replica, model, revenue, orders], edges: [...[warehouse, replica].map((source) => ({ parent: source.resource, dependent: model.resource })), { parent: model.resource, dependent: revenue.resource }, { parent: model.resource, dependent: orders.resource }, { parent: revenue.resource, dependent: focus.resource }, { parent: orders.resource, dependent: focus.resource }] };
    const markup = renderRail(current);
    const tableStage = markup.match(/<li[^>]*data-depth="3"[^>]*>(.*?)<\/li>/)?.[1];
    expect(tableStage).toContain("Warehouse tables</span>");
    expect(tableStage?.replace(/<[^>]+>/g, "")).toContain("Analytics replicareplica.analytics.orders_copy");
    expect(tableStage?.replace(/<[^>]+>/g, "")).toContain("Warehouseanalytics.orders");
    expect(markup.match(/data-testid="calculation-path-stage"/g)).toHaveLength(4);
  });

  it("uses the shared compact ghost link for Open map", () => {
    const markup = renderRail(graph());
    expect(markup).toMatch(/<a[^>]*class="[^"]*border-transparent bg-transparent text-foreground hover:bg-surface[^"]*h-8 px-3[^"]*"><svg[^>]*class="lucide lucide-network"/);
    expect(markup).toContain("Open map</a>");
    expect(markup).not.toContain("<button");
  });

  it("leaves stored expressions to the calculation panel instead of repeating them in the path", () => {
    const current = graph();
    const markup = renderRail(current, {
      models: [{ id: "model", name: "orders", sourceRelations: [], entities: [], dimensions: [], measures: [{ name: "gross_revenue", expression: "gross_revenue" }] }],
      metrics: [{ id: "revenue", name: "revenue", type: "simple", typeParams: { semanticModelName: "orders", measure: "gross_revenue" } }],
    });
    expect(markup).not.toContain("Source fields");
    expect(markup).not.toContain("<pre");
  });

  it("puts Check before Affects in edit rails and omits the read Status section", () => {
    const markup = renderRail(graph(), { editing: true, children: <div><h2>Check</h2></div> });
    expect(markup.indexOf(">Check</h2>")).toBeLessThan(markup.indexOf(">Affects if saved</h2>"));
    expect(markup).not.toContain('aria-label="Status"');
  });

  it("lets a healthy check result stand alone in Status", () => {
    const markup = renderRail(graph(), { children: <div data-testid="read-check">Runs on Warehouse<details><summary>Show SQL</summary></details></div> });
    expect(markup.match(/>Status<\/h2>/g)).toHaveLength(1);
    expect(markup).toMatch(/aria-label="Status"[^>]*>.*data-testid="read-check".*Show SQL.*<\/section><\/aside>/);
    expect(markup).not.toContain("No reference issues");
    expect(renderRail(graph())).toContain("No reference issues");
  });

  it("lists dependent metrics and Dashboards together in Used in, keeping indirect metrics out", () => {
    const current = graph();
    const direct = resource("METRIC", "revenue_target", "Revenue target attainment");
    const indirect = resource("METRIC", "annual_target", "Annual target attainment");
    current.descendants = [direct, indirect];
    current.edges.push({ parent: focus.resource, dependent: direct.resource }, { parent: direct.resource, dependent: indirect.resource });
    current.dashboardUsages = [{ dashboardId: "business_overview", title: "Business overview", queryNames: ["revenue"], metricIds: [focus.resource.id] }];
    const markup = renderRail(current);
    expect(markup.match(/>Used in<\/h2>/g)).toHaveLength(1);
    expect(markup).toMatch(/>Used in<\/h2>.*Revenue target attainment.*Business overview/);
    expect(markup).not.toContain("Annual target attainment");
    expect(markup).not.toContain("No dashboards use this yet");
    expect(markup).toContain(`class="type-body min-w-0 pt-0.5 break-words ${SEMANTIC_LINK_CLASS}">Revenue target attainment</a>`);
    expect(markup).toContain(`class="type-body min-w-0 pt-0.5 break-words ${SEMANTIC_LINK_CLASS}">Business overview</a>`);
    expect(markup).toContain('>Metrics <span class="tabular-nums">1</span></h3>');
    expect(markup).toContain('>Dashboards <span class="tabular-nums">1</span></h3>');
    expect(markup).not.toContain("revenue</span>");
    expect(markup).toContain("lucide-layout-dashboard");
  });

  it("gives a model's read rail Used in with Open map instead of a two-step path, and contains broken references in Status", () => {
    const current = graph();
    current.focus = model;
    current.ancestors = [table];
    current.missingDefinitions = [resource("RELATION", "deleted_table", "Deleted orders")];
    const markup = renderRail(current, { models: [{ id: "model", name: "orders", sourceRelations: [], entities: [], dimensions: [{ name: "order_date", expression: "ordered_at" }], measures: [{ name: "gross_revenue", expression: "gross_revenue" }] }] });
    expect(markup).not.toContain("Source fields");
    expect(markup).not.toContain(">Calculation path</h2>");
    expect(markup).not.toContain(">This model</span>");
    expect(markup).toMatch(/>Used in<\/h2><\/div><a href="\/workspaces\/workspace\/lineage\?kind=SEMANTIC_MODEL&amp;id=model"/);
    expect(markup).toMatch(/aria-label="Status".*Broken reference · Deleted orders.*No executable connector/);
  });
});
