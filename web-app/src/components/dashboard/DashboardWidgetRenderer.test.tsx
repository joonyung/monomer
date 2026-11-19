import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import type { DashboardWidget } from "@/api/dashboard";
import { DashboardWidgetRenderer, widgetAccessibleName, widgetHasHeader } from "./DashboardWidgetRenderer";

vi.mock("@/components/ui/Menu", () => ({
  Menu: ({ children }: { children: ReactNode }) => <div>{children}</div>,
  MenuTrigger: ({ children }: { children: ReactNode }) => <>{children}</>,
  MenuContent: ({ children }: { children: ReactNode }) => <div role="menu">{children}</div>,
  MenuItem: ({ children, disabled }: { children: ReactNode; disabled?: boolean }) => <div role="menuitem" aria-disabled={disabled}>{children}</div>,
}));

const chart: Extract<DashboardWidget, { type: "CHART" }> = { type: "CHART", queryNames: ["revenue", "targets"], module: { inputs: { parameters: [], variables: [] }, source: "export default defineChart({ render() { return {}; } });" } };
const table: Extract<DashboardWidget, { type: "TABLE" }> = { type: "TABLE", queryName: "accounts", columns: [{ field: "name" }], module: { inputs: { queries: ["accounts", "targets"], parameters: [], variables: [] }, source: "" } };
const render = (widget: DashboardWidget, overrides: Partial<Parameters<typeof DashboardWidgetRenderer>[0]> = {}) => renderToStaticMarkup(<DashboardWidgetRenderer
  widget={widget} parameters={{}} variables={{}} variableDefinitions={{}} results={{}} ready={false} queryTypes={{ revenue: "SEMANTIC", targets: "SEMANTIC", accounts: "SEMANTIC" }}
  onVariablesSet={() => undefined} variableRevision={0} {...overrides}
/>);

describe("widget accessible names", () => {
  it("prefers the authored title and otherwise names the queries", () => {
    expect(widgetAccessibleName({ ...chart, title: "Weekly revenue" })).toBe("Weekly revenue");
    expect(widgetAccessibleName(chart)).toBe("Chart of revenue, targets");
    expect(widgetAccessibleName(table)).toBe("Table of accounts, targets");
    const pivot: Extract<DashboardWidget, { pivot: unknown }> = {
      type: "TABLE", queryName: "accounts", module: table.module,
      pivot: { rows: [], columns: [{ field: "month" }], values: [{ field: "revenue" }], valuesAxis: "COLUMNS", totals: ["accountsTotal"] },
    };
    expect(widgetAccessibleName(pivot)).toBe("Table of accounts, targets");
  });
});

describe("widget chrome", () => {
  it("explains a failed first run in place with Retry and Details", () => {
    const markup = render({ ...table, title: "Top accounts by revenue" }, { errorMessage: "The warehouse query timed out after 120 seconds.", actions: { pending: false, onRefresh: () => undefined, onShowRuns: () => undefined } });
    expect(markup).toContain(">Top accounts by revenue</h2>");
    expect(markup).toContain("This table couldn&#x27;t load.");
    expect(markup).toContain("The warehouse query timed out after 120 seconds.");
    expect(markup).toContain(">Retry</button>");
    expect(markup).toContain(">Details</button>");
    expect(markup).toContain('aria-label="More actions for Top accounts by revenue"');
    expect(markup).not.toContain("Select Refresh");
  });

  it("keeps readers' failure copy and full text without run actions or a widget menu", () => {
    const markup = render(chart, { errorMessage: "The query failed or was cancelled." });
    expect(markup).toContain("This chart couldn&#x27;t load.");
    expect(markup).toContain("The query failed or was cancelled.");
    expect(markup).not.toContain("Retry");
    expect(markup).not.toContain("<details");
    expect(markup).toContain('tabindex="0" title="The query failed or was cancelled."');
    expect(markup.match(/>The query failed or was cancelled\.<\/span>/g)).toHaveLength(1);
    expect(markup).not.toContain(">Details</button>");
    expect(markup).not.toContain("data-widget-menu");
  });

  it("offers metric definitions beside refresh and run details on internal widgets", () => {
    const markup = render({ ...chart, title: "Gross revenue" }, { ready: true, actions: { pending: false, onRefresh: () => undefined, onShowRuns: () => undefined, onShowMetrics: () => undefined } });
    const items = [...markup.matchAll(/<div role="menuitem"[^>]*>(.*?)<\/div>/g)].map((match) => match[1]);
    expect(items).toEqual(["Refresh this widget", "View run details", "Open Lineage", "Show metric definitions"]);
  });

  it("shows a widget menu with only metric definitions where results are read-only", () => {
    const markup = render(table, { actions: { pending: false, onShowMetrics: () => undefined } });
    expect(markup).toContain('aria-label="More actions for Table of accounts, targets"');
    expect(markup).toContain(">Show metric definitions</div>");
    expect(markup).not.toContain("Refresh this widget");
    expect(markup).not.toContain("View run details");
  });

  it("gives text widgets no widget menu", () => {
    const markup = render({ type: "TEXT", content: "Notes" }, { ready: true, actions: { pending: false, onShowMetrics: () => undefined } });
    expect(markup).not.toContain("data-widget-menu");
    expect(markup).not.toContain("Show metric definitions");
  });

  it("marks SQL from any referenced query, including TABLE auxiliary queries, while loading", () => {
    expect(render(table, { queryTypes: { accounts: "SEMANTIC", targets: "SQL" } })).toContain(">SQL</span>");
    expect(render(chart, { queryTypes: { revenue: "SQL", targets: "SEMANTIC" } })).toContain(">SQL</span>");
  });

  it("uses the displayed result type and floats its badge without changing the definition header", () => {
    const result = { queryRunId: "past", queryType: "SQL" as const, reportingTimeZone: null, columns: [], rows: [] };
    const retainedSql = render(table, { ready: true, queryTypes: { accounts: "SEMANTIC", targets: "SEMANTIC" }, results: { accounts: result } });
    expect(retainedSql).toContain(">SQL</span>");
    expect(retainedSql).toContain('data-widget-sql-badge="floating" class="absolute left-0 top-0 z-10"');
    expect(retainedSql).not.toContain("data-widget-header");
    const retainedSemantic = render(table, { ready: true, queryTypes: { accounts: "SQL", targets: "SQL" }, results: { accounts: { ...result, queryType: "SEMANTIC" } } });
    expect(retainedSemantic).not.toContain(">SQL</span>");
    expect(retainedSemantic).toContain("data-widget-header");
    expect(render(table, { ready: true, results: { targets: result } })).toContain(">SQL</span>");
  });

  it("shares one definition and host based header predicate", () => {
    expect(widgetHasHeader(chart, undefined, { revenue: "SEMANTIC" })).toBe(false);
    expect(widgetHasHeader({ ...chart, title: "Revenue" }, undefined, {})).toBe(true);
    expect(widgetHasHeader(chart, undefined, { targets: "SQL" })).toBe(true);
    expect(widgetHasHeader(table, { pending: false, onShowRuns: () => undefined }, {})).toBe(true);
    expect(widgetHasHeader(table, { pending: false, onShowSql: () => undefined }, { accounts: "SEMANTIC", targets: "SEMANTIC" })).toBe(false);
    for (const ready of [false, true]) {
      expect(render(chart, { ready, errorMessage: "Long failure", queryTypes: { revenue: "SQL" } })).toContain("data-widget-header");
    }
  });

  it("offers both SQL templates and relevant metric definitions for mixed widgets", () => {
    const markup = render(table, { queryTypes: { accounts: "SEMANTIC", targets: "SQL" }, actions: { pending: false, onShowSql: () => undefined, onShowMetrics: () => undefined } });
    expect(markup).toContain(">Show SQL</div>");
    expect(markup).toContain(">Show metric definitions</div>");
    expect(render(table, { queryTypes: { accounts: "SQL", targets: "SQL" }, actions: { pending: false, onShowSql: () => undefined, onShowMetrics: () => undefined } })).not.toContain("Show metric definitions");
  });

  it("keeps the SQL badge visible to Page readers without a SQL action", () => {
    const markup = render(table, { queryTypes: { accounts: "SQL", targets: "SEMANTIC" } });
    expect(markup).toContain(">SQL</span>");
    expect(markup).not.toContain("Show SQL");
    expect(markup).not.toContain("data-widget-menu");
  });

  it("keeps untitled TABLE menus above the table's copy toolbar", () => {
    const markup = render({ type: "TABLE", queryName: "accounts", columns: [{ field: "name" }] }, {
      ready: true,
      results: { accounts: { queryType: "SEMANTIC", reportingTimeZone: null, queryRunId: "result", columns: [{ name: "name", logicalType: "TEXT", nullable: false, numberFormat: null }], rows: [{ name: "Acme" }] } },
      actions: { pending: false, onShowMetrics: () => undefined },
    });
    expect(markup).toContain('data-widget-menu="" class="shrink-0"');
    expect(markup).toContain('aria-label="Copy selected cell"');
    expect(markup.indexOf("data-widget-menu")).toBeLessThan(markup.indexOf('class="dashboard-table-toolbar"'));
    expect(markup).not.toContain("absolute right-0 top-0");
  });

  it("floats the widget menu over an untitled CHART instead of adding a header row", () => {
    const markup = render(chart, { actions: { pending: false, onRefresh: () => undefined } });
    expect(markup).not.toContain("<h3");
    expect(markup).toContain("data-widget-menu");
    expect(markup).toContain("absolute right-0 top-0");
    expect(markup).toContain('aria-label="More actions for Chart of revenue, targets"');
  });
});
