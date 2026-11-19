import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import type { DashboardDefinition } from "@/api/dashboard";
import type { MetricDetail } from "@/api/metric";
import type { SemanticModelResponse } from "@/api/semantic-model";
import { queryKeys } from "@/lib/query/keys";
import type { Metric } from "@/lib/types/metric";
import { WidgetMetricDefinitions, WidgetMetricsDialog, WidgetSqlTemplates, type WidgetMetricListState } from "./WidgetMetricsDialog";

vi.mock("@radix-ui/react-dialog", () => {
  const Primitive = ({ children, ...props }: { children?: ReactNode } & Record<string, unknown>) => <div {...props}>{children}</div>;
  return { Root: Primitive, Portal: Primitive, Overlay: Primitive, Content: Primitive, Title: Primitive, Description: Primitive, Close: Primitive, Trigger: Primitive };
});

vi.mock("@/lib/navigation", () => ({
  Link: ({ href, children, ...props }: { href: string; children: ReactNode } & Record<string, unknown>) => <a href={href} {...props}>{children}</a>,
}));

const WORKSPACE_ID = "workspace-1";
const PRINCIPAL_ID = "u-viewer";

const grossRevenue: Metric = {
  id: "metric-gross-revenue",
  name: "gross_revenue",
  label: "Gross revenue",
  description: "Revenue from completed orders before refunds.",
  type: "simple",
  typeParams: { semanticModelName: "northstar_order_lines", measure: "gross_revenue" },
  createdAt: "2026-09-01T00:00:00.000Z",
  updatedAt: "2026-10-01T00:00:00.000Z",
};

const marginRate: Metric = {
  id: "metric-margin-rate",
  name: "gross_margin_rate",
  type: "ratio",
  typeParams: { numerator: { metricName: "gross_margin" }, denominator: { metricName: "gross_revenue" } },
  createdAt: "2026-09-01T00:00:00.000Z",
  updatedAt: "2026-09-02T00:00:00.000Z",
};

const grossRevenueDetail: MetricDetail = {
  ...grossRevenue,
  updatedBy: { id: "u-dana", displayName: "Dana" },
  credentialSurface: "AGENT",
  agentApp: { clientId: "https://claude.ai/oauth/mcp-oauth-client-metadata", name: "Claude Code", verifiedHost: "claude.ai" },
};

function visibleText(markup: string): string {
  return markup.replace(/<span[^>]*class="sr-only"[^>]*>.*?<\/span>/g, "").replace(/<span aria-hidden="true" class="pointer-events-none[^"]*"[^>]*>.*?<\/span>/g, "").replace(/<[^>]+>/g, "");
}

function renderDefinitions(metricList: WidgetMetricListState, metricNames: string[] = ["gross_revenue", "gross_margin_rate"], options: { sampleWorkspace: boolean; detailsByMetricId: Record<string, MetricDetail> } = { sampleWorkspace: false, detailsByMetricId: { [grossRevenue.id]: grossRevenueDetail } }): string {
  return renderToStaticMarkup(<WidgetMetricDefinitions
    workspaceId={WORKSPACE_ID}
    principalId={PRINCIPAL_ID}
    sampleWorkspace={options.sampleWorkspace}
    metricNames={metricNames}
    groupings={[{ queryName: "revenue_by_month", text: "By Month, Sales channel" }]}
    metricList={metricList}
    detailsByMetricId={options.detailsByMetricId}
  />);
}

describe("WidgetMetricDefinitions", () => {
  it("shows the grouping while the metrics load", () => {
    const markup = renderDefinitions({ status: "pending" });
    expect(markup).not.toContain("never raw SQL");
    expect(markup).toContain("By Month, Sales channel");
    expect(markup).toContain('role="status"');
    expect(markup).toContain("Loading metric definitions…");
  });

  it("explains a failed metric read with a retry", () => {
    const markup = renderDefinitions({ status: "error", message: "Failed to fetch metrics: Service Unavailable", onRetry: () => undefined });
    expect(markup).toContain('role="alert"');
    expect(markup).toContain("Metric definitions couldn&#x27;t load.");
    expect(markup).toContain("Failed to fetch metrics: Service Unavailable");
    expect(markup).toMatch(/<button[^>]*>Retry<\/button>/);
  });

  it("shows each metric's type, definition, description, who updated it, and a link to it", () => {
    const markup = renderDefinitions({ status: "success", metrics: [marginRate, grossRevenue] });
    const text = visibleText(markup);
    expect(markup.indexOf("Gross revenue")).toBeLessThan(markup.indexOf("gross_margin_rate"));
    expect(markup).toContain(">Gross revenue</h4>");
    expect(markup).toContain(">gross_revenue</p>");
    expect(text).toContain("Aggregate metric");
    expect(text).toContain("northstar_order_lines · gross_revenue");
    expect(text).toContain("Revenue from completed orders before refunds.");
    expect(text).toContain("Updated by Dana");
    expect(text).toContain("via Claude Code");
    expect(markup).toContain('dateTime="2026-10-01T00:00:00.000Z"');
    expect(markup).toContain(`href="/workspaces/${WORKSPACE_ID}/metrics/${grossRevenue.id}"`);
    expect(markup).toContain('aria-label="Open metric Gross revenue"');

    expect(markup).toContain(">gross_margin_rate</h4>");
    expect(text).toContain("Ratio metric");
    expect(text).toContain("gross_margin ÷ gross_revenue");
    expect(markup).toContain('dateTime="2026-09-02T00:00:00.000Z"');
    expect(markup).toContain(`href="/workspaces/${WORKSPACE_ID}/metrics/${marginRate.id}"`);
  });

  it("names Monomer for a sample Workspace's seeded metric only once its detail has loaded", () => {
    const seededDetail: MetricDetail = { ...grossRevenue, updatedBy: null, credentialSurface: null, agentApp: null };
    const loaded = visibleText(renderDefinitions({ status: "success", metrics: [grossRevenue] }, ["gross_revenue"], { sampleWorkspace: true, detailsByMetricId: { [grossRevenue.id]: seededDetail } }));
    expect(loaded).toContain("Updated by Monomer");
    const loading = visibleText(renderDefinitions({ status: "success", metrics: [grossRevenue] }, ["gross_revenue"], { sampleWorkspace: true, detailsByMetricId: {} }));
    expect(loading).not.toContain("Monomer");
    expect(loading).toMatch(/Updated \S/);
    const realWorkspace = visibleText(renderDefinitions({ status: "success", metrics: [grossRevenue] }, ["gross_revenue"], { sampleWorkspace: false, detailsByMetricId: { [grossRevenue.id]: seededDetail } }));
    expect(realWorkspace).not.toContain("Monomer");
  });

  it("names a metric the workspace no longer defines", () => {
    const markup = renderDefinitions({ status: "success", metrics: [grossRevenue] }, ["gross_revenue", "net_revenue"]);
    expect(markup).toContain(">net_revenue</h4>");
    expect(markup).toContain("Not found in this workspace&#x27;s metrics. It may have been renamed or deleted.");
  });

  it("says when a widget reads no metrics", () => {
    const markup = renderDefinitions({ status: "success", metrics: [grossRevenue] }, []);
    expect(markup).toContain("This widget doesn&#x27;t read any metrics.");
    expect(markup).not.toContain("<h4");
  });
});

describe("WidgetMetricsDialog", () => {
  const queries: DashboardDefinition["queries"] = {
    revenue_by_channel: {
      type: "SEMANTIC",
      connectorId: "00000000-0000-4000-8000-000000000001",
      query: {
        schemaVersion: 4,
        metrics: ["gross_revenue"],
        groupBys: [{ kind: "DIMENSION", semanticModelName: "northstar_order_lines", name: "channel" }],
        metricTimeRange: null,
        filters: [],
        orderBy: [],
        rowLimit: 100,
      },
    },
  };
  const ordersModel = {
    id: "model-orders", name: "northstar_order_lines", label: "Order lines", sourceRelations: [], entities: [], measures: [],
    dimensions: [{ isPartition: false, name: "channel", type: "categorical", expression: "channel", label: "Sales channel" }],
    createdAt: "", updatedAt: "",
  } as SemanticModelResponse;

  function renderDialog(seed: (queryClient: QueryClient) => void, target: { widgetTitle: string | null; queryNames: string[]; queryType: "SEMANTIC" | "SQL" } | null): string {
    const queryClient = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity, retry: false } } });
    seed(queryClient);
    return renderToStaticMarkup(
      <QueryClientProvider client={queryClient}>
        <WidgetMetricsDialog workspaceId={WORKSPACE_ID} principalId={PRINCIPAL_ID} sampleWorkspace={false} queries={queries} target={target} onClose={() => undefined} />
      </QueryClientProvider>,
    );
  }

  it("reads the widget's metrics, their last update, and model labels from the shared query cache", () => {
    const markup = renderDialog((queryClient) => {
      queryClient.setQueryData(queryKeys.metrics.all(WORKSPACE_ID, PRINCIPAL_ID), [marginRate, grossRevenue]);
      queryClient.setQueryData(queryKeys.metrics.detail(WORKSPACE_ID, PRINCIPAL_ID, grossRevenue.id), grossRevenueDetail);
      queryClient.setQueryData(queryKeys.semanticModels.all(WORKSPACE_ID, PRINCIPAL_ID), [ordersModel]);
    }, { widgetTitle: "Gross revenue by channel", queryNames: ["revenue_by_channel"], queryType: "SEMANTIC" });
    const text = visibleText(markup);
    expect(markup).toContain("Metric definitions");
    expect(markup).toContain("Gross revenue by channel");
    expect(text).toContain("By Sales channel");
    expect(text).toContain("Updated by Dana");
    expect(text).toContain("via Claude Code");
    expect(markup).not.toContain("gross_margin_rate");
  });

  it("loads while the metric list is not cached yet", () => {
    const markup = renderDialog(() => undefined, { widgetTitle: "Gross revenue by channel", queryNames: ["revenue_by_channel"], queryType: "SEMANTIC" });
    expect(markup).toContain("Loading metric definitions…");
    expect(markup).toContain("By channel");
  });

  it("renders nothing inside the dialog until a widget asks for its metrics", () => {
    const markup = renderDialog(() => undefined, null);
    expect(markup).not.toContain("Dashboard queries can only use shared metrics");
  });
});

describe("WidgetSqlTemplates", () => {
  it("labels the stored template, preserves alias placeholders, and excludes unrelated queries", () => {
    const queries: DashboardDefinition["queries"] = {
      orders: { type: "SQL", connectorId: "private-connector", sql: "select total from orders where country = {{country}}", bindings: { country: { kind: "VARIABLE", name: "country" } }, columns: ["total"], rowLimit: 100 },
      privateQuery: { type: "SQL", connectorId: "another-connector", sql: "select private_total", bindings: {}, columns: ["private_total"], rowLimit: 10 },
    };
    const markup = renderToStaticMarkup(<WidgetSqlTemplates queries={queries} queryNames={["orders"]} />);
    expect(markup).toContain("Stored templates from this definition");
    expect(markup).toContain("SQL template for orders");
    expect(markup).toContain("{{country}}");
    expect(markup).not.toContain("private_total");
    expect(markup).not.toContain("private-connector");
  });
});
