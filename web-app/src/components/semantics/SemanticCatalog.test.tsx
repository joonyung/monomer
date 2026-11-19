import type { ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { SemanticCatalog } from "@/components/semantics/SemanticCatalog";
import { METRIC_FIXTURES, SEMANTIC_MODEL_FIXTURES } from "@/components/semantics/semanticsFixtures";
import { queryKeys } from "@/lib/query/keys";
import { tableCatalogHref } from "@/lib/catalog/catalog-links";
import { webPaths } from "@/lib/web-paths";
import { SEMANTIC_LINK_CLASS } from "@/components/resource-detail/semantic-links";

vi.mock("@/hooks/use-workspace", () => ({
  useWorkspaceId: () => "workspace-1",
  usePrincipalId: () => "user-1",
}));
vi.mock("@/lib/navigation", () => ({
  useRouter: () => ({ push: vi.fn() }),
  Link: ({ href, children, ...attributes }: { href: string; children: ReactNode; className?: string; "aria-label"?: string }) => <a href={href} {...attributes}>{children}</a>,
}));

function renderCatalog(view: "metrics" | "models", client = new QueryClient()): string {
  return renderToStaticMarkup(
    <QueryClientProvider client={client}>
      <SemanticCatalog workspaceId="workspace-1" view={view} models={[{ ...SEMANTIC_MODEL_FIXTURES[0], entities: [] }]} metrics={[METRIC_FIXTURES[0]]} connectors={[]} connectorsLoaded={false} query="" canEdit />
    </QueryClientProvider>,
  );
}

describe("SemanticCatalog", () => {
  it("gives desktop identities 288px and preserves them when the table scrolls horizontally", () => {
    for (const view of ["metrics", "models"] as const) {
      const markup = renderCatalog(view);
      expect(markup).toContain("sm:sticky sm:left-0 sm:z-10 sm:w-72 sm:min-w-72 sm:max-w-72");
      expect(markup).toContain("sm:sticky sm:left-0 sm:z-20 sm:bg-background");
      expect(markup).toContain("focus-visible:underline");
      expect(markup).not.toContain('class="text-heading underline ');
    }
  });

  it("reflows a single accessible row without duplicating its primary or reference links", () => {
    const markup = renderCatalog("metrics");
    const body = markup.slice(markup.indexOf("<tbody"));
    const metric = METRIC_FIXTURES[0];
    const model = SEMANTIC_MODEL_FIXTURES[0];
    expect(markup).toContain('role="table" aria-label="Metrics catalog"');
    expect(markup).toContain('class="sr-only sm:not-sr-only" role="rowgroup"');
    expect(body).toContain("flex flex-wrap items-start");
    expect(body.match(/role="row"/g)).toHaveLength(1);
    expect(body.match(/role="cell"/g)).toHaveLength(5);
    expect(body.match(new RegExp(`href="${webPaths.metric("workspace-1", metric.id)}"`, "g"))).toHaveLength(1);
    expect(body).toContain(`aria-label="${metric.label} (${metric.name})"`);
    expect(body.match(new RegExp(`href="${webPaths.semanticModel("workspace-1", model.id)}"`, "g"))).toHaveLength(1);
    expect(body.match(/Loading dashboard usage/g)).toHaveLength(1);
    expect(body).toContain('class="type-mono block truncate sm:line-clamp-2 sm:whitespace-pre-wrap sm:[overflow-wrap:anywhere]" title="SUM(net_revenue)"');
    expect(body).not.toMatch(/<a\b[^>]*>(?:(?!<\/a>)[\s\S])*<a\b/);
    expect(body).not.toContain("max-w-80");
  });

  it("links Models source tables to their Catalog address and uses an em dash for absent joins", () => {
    const markup = renderCatalog("models");
    const source = SEMANTIC_MODEL_FIXTURES[0].sourceRelations[0];
    const href = tableCatalogHref("workspace-1", { connectorId: source.connectorId, catalogName: source.catalog ?? null, schemaName: source.schema, tableName: source.relation }).replaceAll("&", "&amp;");
    expect(markup).toContain(`href="${href}"`);
    expect(markup).toContain(SEMANTIC_LINK_CLASS);
    expect(markup).toMatch(/aria-hidden="true">Links to<\/span><div[^>]*>—<\/div>/);
    expect(markup).toMatch(/<th[^>]*type-group[^>]*text-label[^>]*text-right[^>]*>Linked metrics<\/th>/);
    expect(markup).toContain('sm:text-right tabular-nums"');
    expect(markup).toContain(`aria-label="${SEMANTIC_MODEL_FIXTURES[0].label} (${SEMANTIC_MODEL_FIXTURES[0].name})"`);
    expect(markup).not.toMatch(/<th[^>]*font-normal/);
  });

  it("shows a healthy row's No issues status in plain visible text", () => {
    for (const view of ["metrics", "models"] as const) {
      expect(renderCatalog(view)).toContain('<span class="type-status text-foreground-secondary">No issues</span>');
      expect(renderCatalog(view)).not.toContain("lucide-circle-check");
      expect(renderCatalog(view)).not.toContain("No reference issues");
    }
  });

  it("shows a dashboard usage skeleton while loading and an em dash only after zero usage is known", () => {
    expect(renderCatalog("metrics")).toContain('role="status" aria-label="Loading dashboard usage"');
    const client = new QueryClient();
    client.setQueryData(queryKeys.lineage.focus("workspace-1", "user-1", "METRIC", METRIC_FIXTURES[0].id), { dashboardUsages: [] });
    const ready = renderCatalog("metrics", client);
    expect(ready).not.toContain("Loading dashboard usage");
    expect(ready).toContain('<span class="tabular-nums">—</span>');
  });

  it("shows the current owner-provided Dashboard usage count", () => {
    const client = new QueryClient();
    client.setQueryData(queryKeys.lineage.focus("workspace-1", "user-1", "METRIC", METRIC_FIXTURES[0].id), { dashboardUsages: [{ dashboardId: "dashboard-1" }, { dashboardId: "dashboard-2" }] });
    expect(renderCatalog("metrics", client)).toContain("2 dashboards");
  });
});
