import type { ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup as renderMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { WorkspaceAccessContextProvider } from "@/components/auth/WorkspaceAccessProvider";
import { SemanticsExperience, type SemanticsView } from "@/components/semantics/SemanticsExperience";
import {
  CONNECTOR_FIXTURES,
  METRIC_FIXTURES,
  SEMANTIC_MODEL_FIXTURES,
  TIME_SPINE_FIXTURES,
} from "@/components/semantics/semanticsFixtures";
import type { WorkspaceAccess } from "@/lib/auth/types";
import type { SemanticModelResponse } from "@/api/semantic-model";
import type { Metric } from "@/lib/types/metric";

vi.mock("@/hooks/use-workspace", () => ({
  useWorkspaceId: () => "workspace-1",
  usePrincipalId: () => null,
}));

vi.mock("@/lib/navigation", () => ({
  useRouter: () => ({ push: vi.fn() }),
  Link: ({ href, children, className, role }: { href: string; children: ReactNode; className?: string; role?: string }) => (
    <a href={href} className={className} role={role}>{children}</a>
  ),
}));

function renderToStaticMarkup(node: ReactNode): string { return renderMarkup(<QueryClientProvider client={new QueryClient()}>{node}</QueryClientProvider>); }

const COLLECTION_EDITOR: WorkspaceAccess = {
  granted: true,
  organizationId: "organization-1",
  role: "EDITOR" as const, };

const READER: WorkspaceAccess = {
  granted: true,
  organizationId: "organization-1",
  role: "VIEWER" as const, };

function renderSemantics({
  view = "metrics",
  access = COLLECTION_EDITOR,
  loading = false,
  error = null,
  query = "",
  models = SEMANTIC_MODEL_FIXTURES,
  metrics = METRIC_FIXTURES,
  filters,
}: {
  view?: SemanticsView;
  access?: WorkspaceAccess;
  loading?: boolean;
  error?: string | null;
  query?: string;
  models?: SemanticModelResponse[];
  metrics?: Metric[];
  filters?: { modelFilter: string; kindFilter: string; statusFilter: string; sort: string };
} = {}): string {
  return renderToStaticMarkup(
    <WorkspaceAccessContextProvider workspaceId="workspace-1" access={access}>
      <SemanticsExperience
        workspaceId="workspace-1"
        view={view}
        searchQuery={query}
        semanticModels={{ items: loading || error ? [] : models, loading, error }}
        metrics={{ items: loading || error ? [] : metrics, loading, error }}
        filters={filters}
        modelPage={0}
        modelPageSize={5}
        connectors={CONNECTOR_FIXTURES}
        timeSpines={{ items: TIME_SPINE_FIXTURES, loading: false, error: null }}
        selectedConnectorId={CONNECTOR_FIXTURES[0].id}
        onViewChange={() => undefined}
        onSearchQueryChange={() => undefined}
        onModelPageChange={() => undefined}
        onConnectorChange={() => undefined}
        onTimeSpineSave={async (_connectorId, definition) => definition}
      />
    </WorkspaceAccessContextProvider>,
  );
}

describe("Semantics area composition", () => {
  it("renders Definitions as the selected area with scoped counts and creation", () => {
    const markup = renderSemantics();

    expect(markup).toMatch(/id="semantics-metrics-tab"[^>]*aria-selected="true"[^>]*tabindex="0"/);
    expect(markup).toMatch(/id="semantics-setup-tab"[^>]*aria-selected="false"[^>]*tabindex="-1"/);
    expect(markup).toMatch(/id="semantics-metrics-panel"[^>]*role="tabpanel"[^>]*aria-labelledby="semantics-metrics-tab"/);
    expect(markup).toContain("Search metrics");
    expect(markup).toMatch(new RegExp(`>${SEMANTIC_MODEL_FIXTURES.length}</strong> models`));
    expect(markup).toMatch(new RegExp(`>${METRIC_FIXTURES.length}</strong> metrics`));
    expect(markup).toContain("New");
  });

  it("keeps compact tabs unfilled and aligns the catalog gutters", () => {
    const markup = renderSemantics();
    const tabs = markup.slice(markup.indexOf('role="tablist"'), markup.indexOf('role="tabpanel"'));
    expect(tabs).toContain("relative px-3");
    expect(tabs).not.toContain("min-w-[112px]");
    expect(tabs).not.toContain("bg-surface text-foreground");
    expect(tabs).toContain("bottom-0 h-0.5 bg-accent-marker");
    expect(markup).toContain('border-border px-8" role="tablist"');
    expect(markup).toContain('border-border px-8 py-3');
    expect(markup).toContain('class="max-w-[1440px] px-8 sm:overflow-x-auto"');
  });

  it("opens Describe a table through the Catalog picker in the Models header", () => {
    const markup = renderSemantics({ view: "models" });
    expect(markup).toMatch(/<button[^>]*aria-haspopup="dialog"[^>]*>[^]*?Describe a table<\/button>/);
    expect(markup).not.toContain('aria-haspopup="menu"');
    expect(markup).not.toContain("/semantic-models/new");
    expect(renderSemantics({ view: "models", access: READER })).not.toContain("Describe a table");
  });

  it("keeps counts inside the search row on narrow screens", () => {
    const markup = renderSemantics();
    const toolbar = markup.slice(markup.indexOf('data-testid="semantics-toolbar"'), markup.indexOf('data-testid="semantic-catalog"'));
    expect(toolbar).toMatch(/flex w-full min-w-0 items-center gap-3 sm:w-auto[^]*?Search metrics[^]*?models[^]*?metrics/);
    expect(toolbar).not.toContain("ml-auto");
  });

  it("names a single model or metric in the singular", () => {
    const markup = renderToStaticMarkup(
      <WorkspaceAccessContextProvider workspaceId="workspace-1" access={COLLECTION_EDITOR}>
        <SemanticsExperience
          workspaceId="workspace-1"
          view="metrics"
          searchQuery=""
          semanticModels={{ items: SEMANTIC_MODEL_FIXTURES.slice(0, 1), loading: false, error: null }}
          metrics={{ items: METRIC_FIXTURES.slice(0, 1), loading: false, error: null }}
          modelPage={0}
          modelPageSize={5}
          connectors={CONNECTOR_FIXTURES}
          timeSpines={{ items: TIME_SPINE_FIXTURES, loading: false, error: null }}
          selectedConnectorId={CONNECTOR_FIXTURES[0].id}
          onViewChange={() => undefined}
          onSearchQueryChange={() => undefined}
          onModelPageChange={() => undefined}
          onConnectorChange={() => undefined}
          onTimeSpineSave={async (_connectorId, definition) => definition}
        />
      </WorkspaceAccessContextProvider>,
    );

    expect(markup).toContain(">1</strong> model</span>");
    expect(markup).toContain(">1</strong> metric</span>");
  });

  it("opens creation from a menu button, never a static list of links", () => {
    const markup = renderSemantics();

    expect(markup).toMatch(/<button[^>]*aria-haspopup="menu"[^>]*aria-expanded="false"/);
    expect(markup).not.toContain('role="menu"');
    expect(markup).not.toContain("/semantic-models/new");
  });

  it("withholds Definitions creation when the viewer lacks both collection capabilities", () => {
    const markup = renderSemantics({ access: READER });

    expect(markup).not.toContain('aria-haspopup="menu"');
  });

  it("scopes Calendar table search and counts and removes the Definitions action", () => {
    const markup = renderSemantics({ view: "setup" });

    expect(markup).toMatch(/id="semantics-setup-tab"[^>]*aria-selected="true"[^>]*tabindex="0"/);
    expect(markup).toMatch(/id="semantics-setup-panel"[^>]*role="tabpanel"[^>]*aria-labelledby="semantics-setup-tab"/);
    expect(markup).toContain("Search connectors, relations, or columns");
    expect(markup).toMatch(new RegExp(`>${CONNECTOR_FIXTURES.length}</strong> connectors`));
    expect(markup).toMatch(/>2<\/strong> calendar tables/);
    expect(markup).not.toContain('aria-haspopup="menu"');
  });

  it("keeps area navigation and the scoped toolbar mounted during loading and errors", () => {
    const loadingMarkup = renderSemantics({ loading: true });
    const errorMarkup = renderSemantics({ error: "Definitions unavailable" });

    expect(loadingMarkup).toContain('role="tablist"');
    expect(loadingMarkup).toContain("Loading metrics");
    expect(loadingMarkup).toContain("Search metrics");
    expect(errorMarkup).toContain('role="alert"');
    expect(errorMarkup).toContain("Definitions unavailable");
    expect(errorMarkup).toContain('role="tablist"');
  });

  it("groups search and the four catalog controls while preserving their selected filter values", () => {
    const markup = renderSemantics({ filters: { modelFilter: SEMANTIC_MODEL_FIXTURES[0].name, kindFilter: "simple", statusFilter: "connector", sort: "updated" } });
    const toolbar = markup.slice(markup.indexOf('data-testid="semantics-toolbar"'), markup.indexOf('data-testid="semantic-catalog"'));
    expect(toolbar).toContain('role="search" aria-label="Semantics filters"');
    expect(toolbar).toContain('aria-label="Search metrics"');
    for (const label of ["Filter by model", "Filter by kind", "Filter by status", "Sort definitions"]) {
      expect(toolbar).toContain(`aria-label="${label}"`);
      expect(markup.match(new RegExp(`aria-label="${label}"`, "g"))).toHaveLength(1);
    }
    for (const selected of ["Orders", "Aggregate", "No executable connector", "Recently updated"]) expect(toolbar).toContain(selected);
    expect(toolbar.match(/role="combobox"/g)).toHaveLength(4);
  });

  it("shows the model catalog sort control beside its search and leaves Setup controls scoped", () => {
    const models = renderSemantics({ view: "models" });
    const modelToolbar = models.slice(models.indexOf('data-testid="semantics-toolbar"'), models.indexOf('data-testid="semantic-catalog"'));
    expect(modelToolbar).toContain('aria-label="Search models"');
    expect(modelToolbar).toContain('aria-label="Sort definitions"');
    expect(modelToolbar.match(/role="combobox"/g)).toHaveLength(1);
    expect(renderSemantics({ view: "setup" })).not.toContain('aria-label="Sort definitions"');
  });

  it("shows stored formulas in mono with responsive clamping and the full title while metric links use Labels", () => {
    const model = { ...SEMANTIC_MODEL_FIXTURES[0], measures: [{ name: "active_customers", aggregation: "count_distinct" as const, expression: "CASE WHEN is_active THEN customer_id END" }] };
    const aggregate: Metric = { ...METRIC_FIXTURES[0], filters: undefined, timeGranularity: undefined, type: "simple", typeParams: { semanticModelName: model.name, measure: "active_customers" } };
    const formula: Metric = { ...aggregate, id: "formula-metric", name: "active_share", label: "Active share", type: "derived", typeParams: { expression: "active_customers * 100 / total_customers", inputMetrics: [{ metricName: aggregate.name }] } };
    const markup = renderSemantics({ models: [model], metrics: [aggregate, formula] });
    expect(markup).toContain('title="COUNT(DISTINCT CASE WHEN is_active THEN customer_id END)"');
    expect(markup).toContain('title="active_customers * 100 / total_customers"');
    expect(markup).toContain('class="type-mono block truncate sm:line-clamp-2 sm:whitespace-pre-wrap sm:[overflow-wrap:anywhere]"');
    expect(markup).toContain(`>${aggregate.label}</span>`);
    expect(markup).toContain('>Active share</span>');
    expect(markup).not.toContain("Count distinct of a calculated value");
    expect(markup).not.toContain("Formula using");
  });
});
