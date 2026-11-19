import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import type { LineageFocusCone, LineageResource, LineageEdge } from "@/api/lineage";
import type { SemanticModelResponse } from "@/api/semantic-model";
import type { Metric, MetricFilter } from "@/lib/types/metric";
import { AffectsList } from "./AffectsList";
import { hasMetricSourceConnector, referenceProblem } from "./semantic-insights";

vi.mock("@/lib/navigation", () => ({
  Link: ({ href, children }: { href: string; children: ReactNode }) => <a href={href}>{children}</a>,
}));

const orders: SemanticModelResponse = {
  id: "orders-model", name: "orders", label: "Orders", sourceRelations: [{ connectorId: "a", schema: "analytics", relation: "orders" }],
  entities: [], dimensions: [{ isPartition: false, name: "region", type: "categorical", expression: "region" }],
  measures: [{ name: "revenue", aggregation: "sum", expression: "amount" }], createdAt: "2026-10-07T00:00:00Z", updatedAt: "2026-10-07T00:00:00Z",
};
const customers: SemanticModelResponse = { ...orders, id: "customers-model", name: "customers", label: "Customers", sourceRelations: [{ connectorId: "b", schema: "analytics", relation: "customers" }], measures: [] };
const revenue: Metric = { id: "revenue-metric", name: "revenue", type: "simple", typeParams: { semanticModelName: "orders", measure: "revenue" }, createdAt: orders.createdAt, updatedAt: orders.updatedAt };
const customerFilter: MetricFilter = { or: [{ not: { dimension: { semanticModelName: "customers", name: "region" }, operator: "eq", values: ["US"] } }] };
const filtered: Metric = { ...revenue, name: "filtered_revenue", filters: [{ and: [customerFilter] }] };
const ratio: Metric = { ...revenue, id: "ratio-metric", name: "relative_revenue", type: "ratio", typeParams: { numerator: { metricName: "revenue" }, denominator: { metricName: "revenue", alias: "prior", offsetWindow: "1 month", filter: customerFilter } } };

function resource(kind: LineageResource["resource"]["kind"], id: string, address: string, label: string): LineageResource {
  return { resource: { kind, id }, address, label, description: null, connector: null, missing: false };
}
function graph(focus: LineageResource, descendants: LineageResource[], edges: LineageEdge[]): LineageFocusCone {
  return { workspaceId: "workspace", focus, descendants, edges, ancestors: [], siblings: [], missingDefinitions: [], siblingEdges: [], entityPeers: [], dashboardUsages: [], detail: { relation: null, semanticModel: null, metric: null } };
}

describe("Semantics reference status", () => {
  it("reports a deleted dimension in nested metric and input filters while its model still exists", () => {
    const missingRegion = { ...customers, dimensions: [] };
    expect(referenceProblem(filtered, [filtered], [orders, missingRegion])).toBe("Missing dimension: customers.region");
    expect(referenceProblem(ratio, [ratio, revenue], [orders, missingRegion])).toBe("Missing dimension: customers.region");
    expect(referenceProblem(ratio, [ratio, revenue], [orders, customers])).toBeNull();
  });
  it("checks filters on transitive inputs and does not substitute an entity for a dimension", () => {
    const input = { ...filtered, id: "filtered-metric" };
    const derived: Metric = { ...revenue, type: "derived", typeParams: { expression: "filtered_revenue", inputMetrics: [{ metricName: input.name }] } };
    const entityOnly = { ...customers, dimensions: [], entities: [{ name: "region", type: "primary" as const, expression: "region" }] };
    expect(referenceProblem(derived, [derived, input], [orders, entityOnly])).toBe("Missing dimension: customers.region");
  });
  it("requires one existing connector for aggregate and filter models, including qualified inputs", () => {
    expect(hasMetricSourceConnector(filtered, [filtered], [orders, customers], ["a", "b"])).toBe(false);
    expect(hasMetricSourceConnector(ratio, [ratio, revenue], [orders, customers], ["a", "b"])).toBe(false);
    const shared = { ...customers, sourceRelations: [...customers.sourceRelations, { connectorId: "a", schema: "analytics", relation: "customers" }] };
    expect(hasMetricSourceConnector(ratio, [ratio, revenue], [orders, shared], ["a", "b"])).toBe(true);
    expect(hasMetricSourceConnector(ratio, [ratio, revenue], [orders, shared], ["b"])).toBe(false);
  });
});

describe("Affects dependency roles", () => {
  it("names a filter-only model dependency and leaves the aggregate source unlabelled", () => {
    const dependent = resource("METRIC", filtered.id, filtered.name, "Filtered revenue");
    const customerParent = resource("SEMANTIC_MODEL", customers.id, customers.name, customers.label!);
    const orderParent = resource("SEMANTIC_MODEL", orders.id, orders.name, orders.label!);
    const render = (parent: LineageResource) => renderToStaticMarkup(<AffectsList workspaceId="workspace" graph={graph(parent, [dependent], [{ parent: parent.resource, dependent: dependent.resource }])} metricDefinitions={[filtered]} />);
    expect(render(customerParent)).toContain("How it depends: </span>Filter</p>");
    expect(render(customerParent)).not.toContain("Customers.region");
    expect(render(orderParent)).not.toContain("How it depends");
    expect(render(orderParent)).not.toContain("Aggregate: revenue");
  });
  it("treats a model that both feeds and filters a metric as its direct source", () => {
    const current: Metric = { ...filtered, filters: [{ dimension: { semanticModelName: "orders", name: "region" }, operator: "eq", values: ["US"] }] };
    const parent = resource("SEMANTIC_MODEL", orders.id, orders.name, orders.label!);
    const dependent = resource("METRIC", current.id, current.name, "Filtered revenue");
    const markup = renderToStaticMarkup(<AffectsList workspaceId="workspace" graph={graph(parent, [dependent], [{ parent: parent.resource, dependent: dependent.resource }])} metricDefinitions={[current]} />);
    expect(markup).not.toContain("How it depends");
    expect(markup).not.toContain("Orders.region");
  });
  it("retains numerator and denominator when a metric serves both roles with different qualifiers", () => {
    const parent = resource("METRIC", revenue.id, revenue.name, "Revenue");
    const dependent = resource("METRIC", ratio.id, ratio.name, "Relative revenue");
    const markup = renderToStaticMarkup(<AffectsList workspaceId="workspace" graph={graph(parent, [dependent], [{ parent: parent.resource, dependent: dependent.resource }])} metricDefinitions={[revenue, ratio]} />);
    expect(markup).toContain("How it depends: </span>Numerator, Denominator</p>");
  });
});
