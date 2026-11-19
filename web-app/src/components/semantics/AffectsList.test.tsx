import type { AnchorHTMLAttributes } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import type { LineageEdge, LineageFocusCone, LineageResource } from "@/api/lineage";
import type { Metric } from "@/lib/types/metric";
import { AffectsList, impactDepths } from "./AffectsList";

vi.mock("@/lib/navigation", () => ({
  Link: (props: AnchorHTMLAttributes<HTMLAnchorElement>) => <a {...props} />,
}));

function resource(kind: LineageResource["resource"]["kind"], id: string, address: string, label: string): LineageResource {
  return { resource: { kind, id }, address, label, description: null, connector: null, missing: false };
}

function graph(focus: LineageResource, descendants: LineageResource[], edges: LineageEdge[]): LineageFocusCone {
  return { workspaceId: "workspace", focus, descendants, edges, ancestors: [], siblings: [], missingDefinitions: [], siblingEdges: [], entityPeers: [], dashboardUsages: [], detail: { relation: null, semanticModel: null, metric: null } };
}

const model = resource("SEMANTIC_MODEL", "orders-model", "orders_data", "Northstar order lines");
const orders = resource("METRIC", "orders-metric", "completed_orders", "Completed orders");
const revenue = resource("METRIC", "revenue-metric", "gross_revenue", "Gross revenue");
const averageOrderValue = resource("METRIC", "aov-metric", "gross_aov", "Gross average order value");
const aggregate: Metric = {
  id: orders.resource.id, name: orders.address, label: orders.label, type: "simple",
  typeParams: { semanticModelName: model.address, measure: "technical_completed_order_measure" },
  createdAt: "2026-10-07T00:00:00Z", updatedAt: "2026-10-07T00:00:00Z",
};
const ratio: Metric = {
  ...aggregate, id: averageOrderValue.resource.id, name: averageOrderValue.address, label: averageOrderValue.label,
  type: "ratio", typeParams: { numerator: { metricName: revenue.address }, denominator: { metricName: orders.address } },
};

function relationships(markup: string): string[] {
  return [...markup.matchAll(/<p[^>]*data-testid="affected-relationship"[^>]*><span[^>]*>How it depends: <\/span>(.*?)<\/p>/g)].map((match) => match[1]);
}

describe("Affects list presentation", () => {
  it("names each affected definition with its kind icon and counts them without exposing measures", () => {
    const current = graph(model, [orders], [{ parent: model.resource, dependent: orders.resource }]);
    const markup = renderToStaticMarkup(<AffectsList workspaceId="workspace" graph={current} metricDefinitions={[aggregate]} />);

    expect(markup).toContain('aria-label="Affected definitions"');
    expect(markup).not.toContain("<table");
    expect(markup).toContain('data-kind="METRIC" data-depth="1"');
    expect(markup).toMatch(/title="Completed orders">Completed orders<\/a>/);
    expect(markup).toContain('href="/workspaces/workspace/metrics/orders-metric"');
    expect(markup).toContain(">1 metric</p>");
    expect(markup).toContain('<span class="sr-only">metric: </span>');
    expect(markup).toContain("lucide-chart-column");
    expect(relationships(markup)).toEqual([]);
    expect(markup).not.toContain("technical_completed_order_measure");
    expect(markup).not.toContain("Depth");
    expect(markup).not.toContain("Aggregate metric");
  });

  it("names the role a direct dependent gives the focus and the definitions an indirect one goes through", () => {
    const adjusted = resource("METRIC", "adjusted-metric", "adjusted_aov", "Adjusted order value");
    const formula: Metric = {
      ...aggregate, id: adjusted.resource.id, name: adjusted.address, label: adjusted.label, type: "derived",
      typeParams: { expression: "gross_aov * 1.1", inputMetrics: [{ metricName: averageOrderValue.address }] },
    };
    const current = graph(model, [adjusted, averageOrderValue, revenue, orders], [
      { parent: averageOrderValue.resource, dependent: adjusted.resource },
      { parent: orders.resource, dependent: averageOrderValue.resource },
      { parent: revenue.resource, dependent: averageOrderValue.resource },
      { parent: model.resource, dependent: revenue.resource },
      { parent: model.resource, dependent: orders.resource },
    ]);
    const grossRevenue: Metric = { ...aggregate, id: revenue.resource.id, name: revenue.address, label: revenue.label };
    const markup = renderToStaticMarkup(<AffectsList workspaceId="workspace" graph={current} metricDefinitions={[aggregate, grossRevenue, ratio, formula]} />);

    expect(relationships(markup)).toEqual(["via Completed orders, Gross revenue", "via Gross average order value"]);
    expect(markup.indexOf('title="Completed orders"')).toBeLessThan(markup.indexOf('title="Gross revenue"'));
    expect(markup.indexOf('title="Gross revenue"')).toBeLessThan(markup.indexOf('title="Gross average order value"'));
    expect(markup.indexOf('title="Gross average order value"')).toBeLessThan(markup.indexOf('title="Adjusted order value"'));
    expect(markup.match(/data-depth="1"/g)).toHaveLength(2);
    expect(markup).toContain('data-depth="2"');
    expect(markup).toContain('data-depth="3"');
    expect(markup).toContain(">4 metrics</p>");
    expect(markup).not.toContain("gross_revenue");
    expect(markup).not.toContain("gross_aov");
  });

  it("marks a metric that only filters on the focus model without exposing dimension identifiers", () => {
    const filterModel = resource("SEMANTIC_MODEL", "customers-model", "customer_data", "Customers");
    const filtered: Metric = { ...ratio, filters: [{
      and: [
        { dimension: { semanticModelName: filterModel.address, name: "technical_region_code" }, operator: "eq", values: ["US"] },
        { not: { dimension: { semanticModelName: filterModel.address, name: "technical_status_code" }, operator: "eq", values: ["inactive"] } },
      ],
    }] };
    const current = graph(filterModel, [averageOrderValue], [{ parent: filterModel.resource, dependent: averageOrderValue.resource }]);
    const markup = renderToStaticMarkup(<AffectsList workspaceId="workspace" graph={current} metricDefinitions={[filtered]} />);

    expect(relationships(markup)).toEqual(["Filter"]);
    expect(markup).not.toContain("technical_region_code");
    expect(markup).not.toContain("technical_status_code");
    expect(markup).not.toContain("customer_data");
  });

  it("links Dashboards with full titles, leaving query names out and naming the metrics they go through", () => {
    const current = graph(revenue, [averageOrderValue], [{ parent: revenue.resource, dependent: averageOrderValue.resource }]);
    current.dashboardUsages = [
      { dashboardId: "dashboard", title: "Northstar regional performance and order value", queryNames: ["Revenue overview", "Order value"], metricIds: [revenue.resource.id, averageOrderValue.resource.id] },
      { dashboardId: "value-only", title: "Order value review", queryNames: ["Order value"], metricIds: [averageOrderValue.resource.id] },
    ];
    const markup = renderToStaticMarkup(<AffectsList workspaceId="workspace" graph={current} metricDefinitions={[ratio]} />);

    expect(markup).toContain('data-kind="DASHBOARD" data-depth="1"');
    expect(markup).toContain('data-kind="DASHBOARD" data-depth="2"');
    expect(markup).toContain('href="/workspaces/workspace/dashboards/dashboard"');
    expect(markup).toContain('title="Northstar regional performance and order value"');
    expect(relationships(markup)).toEqual(["Numerator", "via Gross average order value"]);
    expect(markup).toContain(">1 metric · 2 dashboards</p>");
    expect(markup).not.toContain("Revenue overview");
  });

  it("hides relationships in compact mode and shortens long lists until expanded", () => {
    const many = Array.from({ length: 10 }, (_, index) => resource("METRIC", `metric-${index}`, `metric_${index}`, `Metric ${String(index).padStart(2, "0")}`));
    const current = graph(model, many, many.map((entry) => ({ parent: model.resource, dependent: entry.resource })));
    current.dashboardUsages = [{ dashboardId: "dashboard", title: "Orders dashboard", queryNames: ["Order count"], metricIds: [many[0].resource.id] }];
    const compact = renderToStaticMarkup(<AffectsList workspaceId="workspace" graph={current} compact />);
    expect(relationships(compact)).toEqual([]);
    expect(compact).toContain('title="Orders dashboard"');
    const limited = renderToStaticMarkup(<AffectsList workspaceId="workspace" graph={current} limit={8} />);
    expect(limited.match(/data-testid="affected-definition"/g)).toHaveLength(8);
    expect(limited).toContain("Show all 11</button>");
    expect(limited).toContain(">10 metrics · 1 dashboard</p>");
  });

  it("shows the empty state only when there are no reachable definitions or Dashboard usages", () => {
    const current = graph(model, [orders], []);
    const empty = renderToStaticMarkup(<AffectsList workspaceId="workspace" graph={current} />);
    expect(empty).toContain("No saved definitions depend on this.");
    expect(empty).not.toContain("<ul");

    const dashboardOnly = graph(revenue, [], []);
    dashboardOnly.dashboardUsages = [{ dashboardId: "dashboard", title: "Orders dashboard", queryNames: ["Order count"], metricIds: [revenue.resource.id] }];
    const used = renderToStaticMarkup(<AffectsList workspaceId="workspace" graph={dashboardOnly} />);
    expect(used).toContain("Orders dashboard");
    expect(used).not.toContain("No saved definitions depend on this.");
  });

  it("keeps a model that depends on a focused warehouse table", () => {
    const table = resource("RELATION", "table", "analytics.orders", "Order lines table");
    const current = graph(table, [model], [{ parent: table.resource, dependent: model.resource }]);
    const markup = renderToStaticMarkup(<AffectsList workspaceId="workspace" graph={current} />);

    expect(markup).toContain('data-kind="SEMANTIC_MODEL" data-depth="1"');
    expect(markup).toContain(">1 model</p>");
    expect(markup).toContain('href="/workspaces/workspace/semantic-models/orders-model"');
    expect(markup).not.toContain("analytics.orders");
  });
});

describe("Affects hierarchy", () => {
  it("uses shortest downstream depth, ignores disconnected resources, and terminates cycles", () => {
    const disconnected = resource("METRIC", "disconnected", "disconnected", "Unrelated metric");
    const current = graph(model, [averageOrderValue, orders, disconnected], [
      { parent: orders.resource, dependent: averageOrderValue.resource },
      { parent: averageOrderValue.resource, dependent: model.resource },
      { parent: model.resource, dependent: orders.resource },
      { parent: model.resource, dependent: averageOrderValue.resource },
    ]);

    expect([...impactDepths(current)]).toEqual([["SEMANTIC_MODEL:orders-model", 0], ["METRIC:orders-metric", 1], ["METRIC:aov-metric", 1]]);
    const markup = renderToStaticMarkup(<AffectsList workspaceId="workspace" graph={current} />);
    expect(markup.match(/data-depth="1"/g)).toHaveLength(2);
    expect(markup).not.toContain("Unrelated metric");
  });
});
