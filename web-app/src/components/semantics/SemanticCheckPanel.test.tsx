import type { ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup as renderMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { queryKeys } from "@/lib/query/keys";
import { SemanticCheckPanel, type SemanticCheckState } from "@/components/semantics/SemanticCheckPanel";
import type { SemanticCheckResult } from "@/api/semantic-check";

vi.mock("@/hooks/use-workspace", () => ({ useWorkspaceId: () => "w1", usePrincipalId: () => "p1" }));
vi.mock("@/lib/navigation", () => ({ Link: ({ href, children, className }: { href: string; children: ReactNode; className?: string }) => <a href={href} className={className}>{children}</a> }));

function renderToStaticMarkup(element: ReactNode): string {
  const client = new QueryClient();
  client.setQueryData(queryKeys.semanticModels.all("w1", "p1"), [{ id: "orders-copy-id", name: "orders_copy", label: "Orders copy" }]);
  client.setQueryData(queryKeys.metrics.all("w1", "p1"), []);
  return renderMarkup(<QueryClientProvider client={client}>{element}</QueryClientProvider>);
}

const connectors = [{ id: "c1", name: "Production Postgres" }];

function render(state: Partial<SemanticCheckState>, mode: "read" | "edit" = "edit"): string {
  return renderToStaticMarkup(
    <SemanticCheckPanel
      mode={mode}
      state={{ result: null, checking: false, failed: false, checkNow: () => undefined, ...state }}
      connectors={connectors}
      incompleteMessage="Complete the required fields to check this metric."
      onGoToField={() => undefined}
      canGoToField={(field) => field === "measure"}
    />,
  );
}

describe("SemanticCheckPanel", () => {
  it("preserves runsOn and appends partition conditions without changing SQL", () => {
    const filter = { kind: "SEMANTIC_MODEL" as const, connectorId: "c1", relation: { catalog: null, schema: "public", name: "orders" }, partitionKeys: ["order_date"], semanticModelName: "orders", dimensions: ["signup_date"], timeRangeApplies: false };
    const markup = render({ result: { ok: true, runsOn: ["c1"], problems: [], partitionFilters: [filter], preview: { connectorId: "c1", dialect: "BIGQUERY", sql: "SELECT 1", groupBys: [] } } });
    expect(markup).toContain("Runs on Production Postgres · needs a filter on signup_date");
    expect(markup).toContain("SELECT 1");
    const problem = render({ result: { ok: false, runsOn: ["c1"], preview: null, partitionFilters: [{ ...filter, dimensions: [] }], problems: [{ code: "PARTITION_COLUMN_NOT_MODELED", connectorId: "c1", field: null, definition: null, message: "The table needs a filter on order_date. Add a dimension that uses it." }] } });
    expect(problem).toContain("Runs on Production Postgres");
    expect(problem).toContain("Add a dimension that uses it.");
    expect(problem).toContain("You can still save with problems.");
  });
  it("shows where a resolving draft runs and keeps its SQL behind Show SQL", () => {
    const markup = render({ result: { partitionFilters: [], ok: true, runsOn: ["c1"], problems: [], preview: { connectorId: "c1", dialect: "POSTGRESQL", sql: "SELECT DATE_TRUNC('day', issued_at)", groupBys: [
      { kind: "DIMENSION", semanticModelName: "billing", name: "status" },
      { kind: "ENTITY", semanticModelName: "billing", name: "account" },
    ] } } });
    expect(markup).not.toContain("Resolves");
    expect(markup).toContain("<span>Runs on Production Postgres</span>");
    expect(markup).not.toContain("Group by");
    expect(markup).toContain("<details><summary");
    expect(markup).toContain("SELECT DATE_TRUNC(&#x27;day&#x27;, issued_at)");
    expect(markup).not.toContain("You can still save");
    expect(markup).not.toContain("agents can create definitions");
  });

  it("counts problems, names their connector, and links known fields", () => {
    const markup = render({ result: { partitionFilters: [], ok: false, runsOn: [], preview: null, problems: [
      { code: "METRICFLOW_MANIFEST_INVALID", field: "measure", connectorId: null, definition: null, message: "Measure invoice_cnt referenced in metric invoices is not defined in the model!" },
      { code: "TIME_SPINE_UNAVAILABLE", field: null, connectorId: "c1", definition: null, message: "This metric needs a time spine on this connector." },
    ] } });
    expect(markup).toContain("2 problems");
    expect(markup).toContain("You can still save with problems.");
    expect(markup.match(/Go to field/g)).toHaveLength(1);
    expect(markup).toContain("Production Postgres: </span><span class=\"text-foreground\">This metric needs a time spine on this connector.");
    expect(markup).not.toContain("On Production Postgres.");
  });

  it("marks an undeclared timeKind as a warning and keeps a mismatch as an error", () => {
    const markup = render({ result: { partitionFilters: [], ok: false, runsOn: ["c1"], preview: null, problems: [
      { code: "TIME_KIND_UNDECLARED", field: "dimensions[0]", connectorId: "c1", definition: null, message: "Declare timeKind for this time dimension." },
      { code: "TIME_KIND_MISMATCH", field: "dimensions[1]", connectorId: "c1", definition: null, message: "timeKind `date` doesn't match this column's Catalog type `timestamptz`." },
    ] } });
    expect(markup).toContain("text-warning");
    expect(markup).toContain('<span class="sr-only">Warning: </span>');
    expect(markup.match(/sr-only">Warning: /g)).toHaveLength(1);
    expect(markup).toContain("Declare timeKind for this time dimension so times convert to the Reporting time zone correctly.");
    expect(markup).toContain("timeKind `date` doesn&#x27;t match this column&#x27;s Catalog type `timestamptz`.");
    expect(markup.match(/text-error/g)).toHaveLength(1);
  });

  it("groups problems that belong to other saved definitions under them with a link to each", () => {
    const markup = render({ result: { partitionFilters: [], ok: false, runsOn: [], preview: null, problems: [
      { code: "NAME_CONFLICT", field: "name", connectorId: null, definition: null, message: "Another metric already uses this name." },
      { code: "METRICFLOW_MANIFEST_INVALID", field: null, connectorId: null, definition: { kind: "SEMANTIC_MODEL", name: "orders_copy" }, message: "In semantic model `orders_copy`, type conflict for dimension `region`." },
      { code: "METRICFLOW_MANIFEST_INVALID", field: null, connectorId: null, definition: { kind: "METRIC", name: "deleted_metric" }, message: "Metric `deleted_metric` is invalid." },
    ] } });
    expect(markup.indexOf("Another metric already uses this name.")).toBeLessThan(markup.indexOf(">Other definitions <"));
    expect(markup).toContain('aria-label="Problems in other definitions"');
    expect(markup).toMatch(/href="\/workspaces\/w1\/semantic-models\/orders-copy-id"[^>]*>Orders copy<\/a><span class="text-foreground-secondary"> · Model<\/span>/);
    expect(markup).toContain('<span class="text-foreground">deleted_metric</span><span class="text-foreground-secondary"> · Metric</span>');
    expect(markup).toContain("In semantic model `orders_copy`, type conflict for dimension `region`.");
  });

  it("prefixes connector-specific problems in read mode without prefixing definition-wide problems", () => {
    const markup = render({ result: { partitionFilters: [], ok: false, runsOn: [], preview: null, problems: [
      { code: "SOURCE_RELATION_NOT_FOUND", field: null, connectorId: "c1", definition: null, message: "Source table is missing." },
      { code: "METRICFLOW_MANIFEST_INVALID", field: null, connectorId: null, definition: null, message: "Definition has an invalid reference." },
    ] } }, "read");
    expect(markup).toContain("Production Postgres: </span><span class=\"text-foreground\">Source table is missing.");
    expect(markup).toContain('<span class="min-w-0"><span class="text-foreground">Definition has an invalid reference.</span>');
  });

  it("explains an incomplete draft without calling it a problem", () => {
    expect(render({})).toContain("Complete the required fields to check this metric.");
  });

  it.each([{}, [], { ok: false }, { partitionFilters: [], ok: true, runsOn: null, problems: [], preview: null }, { partitionFilters: [], ok: false, runsOn: [], problems: [null], preview: null }])("contains malformed responses in the panel: %j", (payload) => {
    const markup = render({ result: payload as unknown as SemanticCheckResult }, "read");
    expect(markup).toContain("Check unavailable");
    expect(markup).toContain("Retry");
    expect(markup).toContain("The definition is still available.");
  });

  it("shows Retry on failed reads even when an old result remains cached", () => {
    const markup = render({ failed: true, result: { partitionFilters: [], ok: true, runsOn: ["c1"], problems: [], preview: null } }, "read");
    expect(markup).toContain("Check unavailable");
    expect(markup).toContain("Retry");
    expect(markup).not.toContain("Resolves");
    expect(markup).not.toContain("Runs on Production Postgres");
  });

  it("keeps failed checks local while the definition remains rendered", () => {
    const markup = renderToStaticMarkup(<main><h1>Net Revenue</h1><p>Current revenue definition</p><SemanticCheckPanel mode="read" state={{ result: null, checking: false, failed: true, checkNow: () => undefined }} connectors={connectors} incompleteMessage="Not checked" onGoToField={() => undefined} canGoToField={() => false} /></main>);
    expect(markup).toContain("Net Revenue");
    expect(markup).toContain("Current revenue definition");
    expect(markup).toContain('role="alert"');
  });

  it("collapses SQL and omits save advice in read mode", () => {
    const markup = render({ result: { partitionFilters: [], ok: true, runsOn: ["c1"], problems: [], preview: { connectorId: "c1", dialect: "POSTGRESQL", sql: "SELECT 1", groupBys: [] } } }, "read");
    expect(markup).toContain('aria-label="Definition status"');
    expect(markup).not.toContain("<h2");
    expect(markup).toMatch(/^<div[^>]*class="min-w-0 space-y-3"/);
    expect(markup.indexOf("Runs on Production Postgres")).toBeLessThan(markup.indexOf("Show SQL"));
    expect(markup).toContain("<details><summary");
    expect(markup).toContain("Show SQL");
    expect(markup).not.toContain("<details open");
    expect(markup).not.toContain("Saving is allowed");
  });

});
