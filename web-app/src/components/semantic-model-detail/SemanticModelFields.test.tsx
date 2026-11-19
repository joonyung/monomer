import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { SemanticModelFields } from "@/components/semantic-model-detail/SemanticModelFields";
import type { SemanticModelResponse } from "@/api/semantic-model";
import type { Metric } from "@/lib/types/metric";
import type { RelationDetail } from "@/lib/types/catalog";

vi.mock("@/lib/navigation", () => ({
  Link: ({ href, children, ...props }: { href: string; children: ReactNode; className?: string }) => <a href={href} {...props}>{children}</a>,
}));

const model: SemanticModelResponse = {
  id: "orders", name: "orders", label: "Orders",
  sourceRelations: [{ connectorId: "warehouse", schema: "analytics", relation: "orders" }],
  entities: [{ name: "order_id", type: "primary", expression: "order_id", label: "Order" }],
  dimensions: [{ isPartition: false, name: "ordered_at", type: "time", expression: "ordered_at", granularity: "hour" }],
  measures: [
    { name: "order_count", aggregation: "count", expression: "order_id", label: "Order count" },
    { name: "balance", aggregation: "sum", expression: "amount_cents / 100.0", nonAdditiveDimension: { name: "ordered_at", windowChoice: "max", windowGroupings: ["order_id"] } },
  ],
  createdAt: "2026-10-07T00:00:00Z", updatedAt: "2026-10-07T00:00:00Z",
};

function metric(id: string, measure = "order_count"): Metric {
  return { id, name: id, type: "simple", typeParams: { semanticModelName: "orders", measure }, createdAt: model.createdAt, updatedAt: model.updatedAt };
}
function render(metrics: Metric[] | null = [], canCreateMetric = true) {
  return renderToStaticMarkup(<SemanticModelFields workspaceId="workspace" model={model} metrics={metrics} canCreateMetric={canCreateMetric} />);
}

describe("Model column table", () => {
  it("groups stored roles under role headings in one table and preserves separate uses of one column", () => {
    const markup = render();
    expect(markup.match(/<table/g)).toHaveLength(1);
    for (const heading of ["Name", "Details"]) expect(markup).toContain(`>${heading}</th>`);
    expect(markup.match(/scope="col"/g)).toHaveLength(2);
    expect(markup).not.toContain(">Expression</th>");
    expect(markup.match(/scope="rowgroup"/g)).toHaveLength(3);
    expect(markup.indexOf(">Keys <")).toBeLessThan(markup.indexOf(">Time <"));
    expect(markup.indexOf(">Time <")).toBeLessThan(markup.indexOf(">Aggregates <"));
    expect(markup).toContain(">Row key</span>");
    expect(markup).toContain(">Hour</span>");
    expect(markup).toContain(">COUNT(order_id)</code>");
    expect(markup).toContain(">SUM(amount_cents / 100.0)</code>");
    expect(markup).not.toContain("Counted by");
    expect(markup).not.toContain("Group and filter");
    expect(markup).not.toContain("Identifies each row");
    expect(markup).not.toContain("Entities");
    expect(markup).not.toContain("Measures");
  });

  it("shows a label first with its stored name below and the description under it", () => {
    const described = { ...model, dimensions: [{ ...model.dimensions[0], label: "Ordered at", description: "When the order was placed." }] };
    const markup = renderToStaticMarkup(<SemanticModelFields workspaceId="workspace" model={described} metrics={[]} canCreateMetric />);
    expect(markup).toMatch(/title="Ordered at"[^>]*>Ordered at<\/span>/);
    expect(markup).toContain(">ordered_<wbr/>at</code>");
    expect(markup).toContain("When the order was placed.");
  });

  it("marks the model's Metric time and shows an aggregate's own Metric time only when it differs", () => {
    const timed = { ...model, aggregationTimeDimension: "ordered_at", dimensions: [...model.dimensions, { isPartition: false, name: "shipped_at", type: "time" as const, expression: "shipped_at", granularity: "day" as const }], measures: [{ ...model.measures[0], aggregationTimeDimension: "ordered_at" }, { ...model.measures[1], aggregationTimeDimension: "shipped_at" }] };
    const markup = renderToStaticMarkup(<SemanticModelFields workspaceId="workspace" model={timed} metrics={[]} canCreateMetric />);
    expect(markup.match(/ · Metric time/g)).toHaveLength(1);
    expect(markup.match(/Metric time: <code/g)).toHaveLength(1);
    expect(markup).toContain('Metric time: <code class="type-mono">shipped_at</code>');
  });

  it("collapses columns that are not used behind a count", () => {
    const relation = { partitionKeys: [], requiresPartitionFilter: null, columns: [{ name: "order_id", nativeType: "INT64" }, { name: "notes", nativeType: "STRING" }, { name: "legacy_code", nativeType: "STRING" }], primaryKey: null, foreignKeys: [] } as unknown as RelationDetail;
    const markup = renderToStaticMarkup(<SemanticModelFields workspaceId="workspace" model={model} relation={relation} metrics={[]} canCreateMetric />);
    expect(markup).toMatch(/aria-expanded="false"[^>]*>[\s\S]*?Not used <span class="tabular-nums">2<\/span>/);
    expect(markup).not.toContain(">notes<");
    expect(markup).not.toContain("Suggested");
  });

  it("breaks identifiers only at underscores and retains the complete value in its title", () => {
    const name = "promised_delivery_date";
    const source = { ...model, entities: [], dimensions: [{ isPartition: false, name, expression: name, type: "time" as const, granularity: "day" as const }], measures: [] };
    const markup = renderToStaticMarkup(<SemanticModelFields workspaceId="workspace" model={source} metrics={[]} canCreateMetric />);
    expect(markup).toContain('title="promised_delivery_date"');
    expect(markup).toContain(">promised_<wbr/>delivery_<wbr/>date</span>");
    expect(markup).toContain("[overflow-wrap:normal] [word-break:normal]");
  });

  it("shows latest-value rules without reducing them to a plain sum", () => {
    expect(render()).toContain("Latest value on ordered_at by order_id");
  });

  it("links all metrics that use an aggregate, including filtered wrappers", () => {
    const markup = render([metric("total_orders"), { ...metric("paid_orders"), filters: [{ dimension: { semanticModelName: "orders", name: "status" }, operator: "eq", values: ["paid"] }] }, { ...metric("other_orders"), typeParams: { semanticModelName: "other", measure: "order_count" } }]);
    expect(markup).toContain('href="/workspaces/workspace/metrics/total_orders"');
    expect(markup).toContain('href="/workspaces/workspace/metrics/paid_orders"');
    expect(markup).not.toContain("metrics/other_orders");
    expect(markup).toContain("lucide-chart-column");
    expect(markup).toContain("underline-offset-4");
    expect(markup).toContain("No metric yet");
  });

  it("starts aggregate authoring as a new metric on this model and withholds it below Editor", () => {
    expect(render()).toContain('href="/workspaces/workspace/metrics/new?model=orders"');
    expect(render()).toContain("Add aggregate metric");
    expect(render([], false)).not.toContain("Add aggregate metric");
    expect(render(null)).not.toContain("No metric yet");
  });
});


it("shows every eligible model matching the stored entity key name", () => {
  const source = { ...model, entities: [...model.entities, { name: "customer_id", type: "foreign" as const, expression: "customer_column" }] };
  const customerModel = { ...model, id: "customers", name: "customers", label: "Customers", entities: [{ name: "customer_id", type: "primary" as const, expression: "id" }] };
  const accountModel = { ...model, id: "accounts", name: "accounts", label: "Accounts", entities: [{ name: "account_id", type: "primary" as const, expression: "id" }, { name: "customer_id", type: "unique" as const, expression: "customer_column" }] };
  const markup = renderToStaticMarkup(<SemanticModelFields workspaceId="workspace" model={source} models={[customerModel, accountModel]} metrics={[]} canCreateMetric />);
  expect(markup).toContain('href="/workspaces/workspace/semantic-models/customers"');
  expect(markup).toContain('href="/workspaces/workspace/semantic-models/accounts"');
  expect(markup).toMatch(/semantic-models\/customers"[^>]*>Customers<\/a>/);
  expect(markup).toMatch(/semantic-models\/accounts"[^>]*>Accounts<\/a>/);
  expect(markup).toContain(">Links to models <");
});
