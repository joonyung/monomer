import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { DashboardTableWidget } from "./DashboardTableWidget";
import { tableInputsEqual } from "./useTableSourcePresentation";
import type { DashboardResultColumn, DashboardWidget } from "@/api/dashboard";
import { validateDashboardResult, type NamedDashboardResult } from "@/features/dashboard/dashboard-result";
import { metricNumberFormat } from "@/lib/metric-display";

const widget: Extract<DashboardWidget, { type: "TABLE" }> = { type: "TABLE", queryName: "orders", columns: [{ field: "metric.value", header: "원래 값", width: 260 }], rowHeight: 56 };
const result: NamedDashboardResult = { queryType: "SEMANTIC" as const, reportingTimeZone: null, queryRunId: "result", columns: [{ name: "metric.value", logicalType: "NUMBER", nullable: true, numberFormat: null }], rows: [{ "metric.value": null }, { "metric.value": "" }] };
const render = (results: Record<string, NamedDashboardResult>, table: Extract<DashboardWidget, { type: "TABLE" }> = widget) => renderToStaticMarkup(<DashboardTableWidget widget={table} results={results} parameters={{}} variables={{}} />);

describe("DashboardTableWidget host", () => {
  it("keeps the same input across rebuilt result maps and structurally equal declared values", () => {
    const input = () => ({
      source: "export default defineTable({ render() { throw new Error('failed'); } });",
      queryRunIds: { orders: "result" }, missing: undefined, displayFractionDigits: new Map([["metric.value", 2]]),
      frame: { rowQueryName: "orders", locale: "ko-KR", queries: { orders: { columns: result.columns, rows: result.rows } }, parameters: { range: { start: "1", end: "2" } }, variables: { selected: ["a", "b"] } },
    });
    const previous = input();
    const rerender = input();
    rerender.frame.parameters.range = { end: "2", start: "1" };
    expect(tableInputsEqual(previous, rerender)).toBe(true);
    expect(tableInputsEqual(previous, { ...rerender, source: "new source" })).toBe(false);
    expect(tableInputsEqual(previous, { ...rerender, queryRunIds: { orders: "next-result" } })).toBe(false);
    expect(tableInputsEqual(previous, { ...rerender, frame: { ...rerender.frame, queries: { orders: { columns: result.columns, rows: [...result.rows] } } } })).toBe(false);
    expect(tableInputsEqual(previous, { ...rerender, frame: { ...rerender.frame, queries: { orders: { columns: [...result.columns], rows: result.rows } } } })).toBe(false);
    expect(tableInputsEqual(previous, { ...rerender, frame: { ...rerender.frame, variables: { selected: ["b", "a"] } } })).toBe(false);
    expect(tableInputsEqual(previous, { ...rerender, frame: { ...rerender.frame, parameters: { range: { start: "1", end: "3" } } } })).toBe(false);
    expect(tableInputsEqual(previous, null)).toBe(false);
  });

  it("prepares again when only the display digits or locale behind displayText change", () => {
    const frame = { rowQueryName: "orders", locale: "en-US", queries: { orders: { columns: result.columns, rows: result.rows } }, parameters: {}, variables: {} };
    const input = { source: "source", queryRunIds: { orders: "result" }, missing: undefined, frame, displayFractionDigits: new Map([["metric.value", 2]]) };
    expect(tableInputsEqual(input, { ...input, displayFractionDigits: new Map([["metric.value", 2]]) })).toBe(true);
    expect(tableInputsEqual(input, { ...input, displayFractionDigits: new Map([["metric.value", 4]]) })).toBe(false);
    expect(tableInputsEqual(input, { ...input, displayFractionDigits: new Map() })).toBe(false);
    expect(tableInputsEqual(input, { ...input, frame: { ...frame, locale: "de-DE" } })).toBe(false);
  });

  it("renders the original table without source and distinguishes empty and null", () => {
    const markup = render({ orders: result });
    expect(markup).toContain('role="grid"');
    expect(markup).toContain('aria-rowcount="3"');
    expect(markup).toContain('aria-colcount="1"');
    expect(markup).toContain('data-value-kind="null">null');
    expect(markup).toContain('data-value-kind="empty">(empty string)');
    expect(markup).toContain("Copy raw value");
    expect(markup).toContain("Copy displayed text");
    expect(markup).toContain(">2 rows</span>");
    expect(markup).not.toContain("Sorted by");
    expect(markup).toContain("width:260px");
    expect(markup).toContain("height:56px");
  });

  it.each([0, 1, 2])("uses the correct row count label for %i rows", (count) => {
    const markup = render({ orders: { ...result, rows: Array.from({ length: count }, () => ({ "metric.value": "1" })) } });
    expect(markup).toContain(`>${count} ${count === 1 ? "row" : "rows"}</span>`);
  });

  it("keeps missing queries and missing columns as visible structural failures", () => {
    expect(render({})).toContain('role="alert"');
    const missing = render({ orders: { ...result, columns: [] } });
    expect(missing).toContain("metric.value");
    expect(missing).not.toContain('role="grid"');
  });

  it("renders a bounded viewport while preserving total row metadata and original addresses", () => {
    const rows = Array.from({ length: 10000 }, (_, index) => ({ "metric.value": String(index) }));
    const markup = render({ orders: { ...result, rows } });
    expect(markup).toContain('aria-rowcount="10001"');
    expect(markup.match(/data-row-index=/g)?.length).toBeLessThan(20);
    expect(markup).toContain('data-row-index="0"');
    expect(markup).not.toContain('data-row-index="9999"');
  });

  it("right-aligns numeric columns and groups digits for display without rounding", () => {
    const markup = render({ orders: { ...result, rows: [{ "metric.value": "12345678901234567890.123456789" }, { "metric.value": "-1234" }] } }, { ...widget, title: "Revenue by account" });
    expect(markup).toContain('aria-label="Revenue by account"');
    expect(markup).toContain('data-numeric="true"');
    expect(markup).toContain("text-align:right");
    expect(markup).toContain(">12,345,678,901,234,567,890.123456789</span>");
    expect(markup).toContain(">-1,234.000000000</span>");
    expect(markup).toContain('title="12345678901234567890.123456789"');
  });

  it("removes warehouse padding using the same exact column digits as Explore", () => {
    const markup = render({ orders: { ...result, rows: [{ "metric.value": "10430427.950000000" }, { "metric.value": "9007199254740993.123400000" }] } });
    expect(markup).toContain(">10,430,427.9500</span>");
    expect(markup).toContain(">9,007,199,254,740,993.1234</span>");
    expect(markup).toContain('title="10430427.950000000"');
  });

  it.each([
    [{ style: "CURRENCY" as const, currency: "USD" as const, decimals: 2 }, "9007199254740993.125", "$9,007,199,254,740,993.13"],
    [{ style: "PERCENT" as const, decimals: 0 }, "0.0004", "0.04%"],
  ])("applies the stored metric format and keeps the raw tooltip", (display, raw, text) => {
    const markup = render({ orders: { ...result, columns: [{ ...result.columns[0], numberFormat: metricNumberFormat(display) }], rows: [{ "metric.value": raw }] } });
    expect(markup).toContain(`>${text}</span>`);
    expect(markup).toContain(`title="${raw}"`);
  });

  it("keeps text columns left-aligned and the default grid name without a title", () => {
    const text = render({ orders: { queryType: "SEMANTIC" as const, reportingTimeZone: null, queryRunId: "text", columns: [{ name: "metric.value", logicalType: "TEXT", nullable: false, numberFormat: null }], rows: [{ "metric.value": "1234" }] } });
    expect(text).toContain('aria-label="Dashboard table"');
    expect(text).not.toContain("data-numeric");
    expect(text).toContain(">1234</span>");
  });

  it("shows an empty result with disabled copy actions", () => {
    const markup = render({ orders: { ...result, rows: [] } });
    expect(markup).toContain("No rows to display.");
    expect(markup.match(/disabled=""/g)).toHaveLength(1);
  });
});

describe("DashboardTableWidget pivot", () => {
  const usd = { style: "CURRENCY" as const, currency: "USD" as const, decimals: 2 };
  const region = { name: "dimension.orders.region", logicalType: "TEXT", nullable: false, display: null };
  const month = { name: "metric_time.month", logicalType: "DATE", nullable: false, display: null };
  const revenue = { name: "metric.revenue", logicalType: "DECIMAL", nullable: true, display: usd };
  const customers = { name: "metric.unique_customers", logicalType: "INTEGER", nullable: true, display: null };
  const semantic = (queryRunId: string, columns: DashboardResultColumn[], rows: (string | null)[][]) => validateDashboardResult({ queryType: "SEMANTIC", reportingTimeZone: null, queryRunId, columns, rows });
  const results = {
    revenueByRegionMonth: semantic("primary", [region, month, revenue, customers], [
      ["West", "2026-08-01", "1350.50", "42"], ["West", "2026-07-01", "1200.00", "40"], ["East", "2026-08-01", "980.00", "30"], ["North", "2026-07-01", null, "12"], ["North", "2026-08-01", "1500.00", "25"],
    ]),
    revenueByRegion: semantic("by-region", [region, revenue, customers], [["North", "1500.00", "31"], ["West", "2550.50", "55"], ["East", "980.00", "30"]]),
    revenueByMonth: semantic("by-month", [month, revenue, customers], [["2026-08-01", "3830.50", "88"], ["2026-07-01", "1200.00", "50"]]),
    revenueTotal: semantic("grand", [revenue, customers], [["5030.50", "104"]]),
  };
  const pivot: Extract<DashboardWidget, { pivot: unknown }> = {
    type: "TABLE", title: "Revenue by region", queryName: "revenueByRegionMonth",
    pivot: {
      rows: [{ field: "dimension.orders.region", header: "Region", width: 160 }],
      columns: [{ field: "metric_time.month", header: "Month" }],
      values: [{ field: "metric.revenue", header: "Revenue", width: 120 }, { field: "metric.unique_customers", header: "Customers" }],
      valuesAxis: "COLUMNS",
      totals: ["revenueByRegion", "revenueByMonth", "revenueTotal"],
    },
  };
  const cell = (markup: string, rowId: string, column: number) => {
    const match = new RegExp(`<td[^>]*data-row-id="${rowId}" data-column-index="${column}"[^>]*>(.*?)</td>`).exec(markup);
    if (!match) throw new Error(`No cell ${rowId}:${column}`);
    return { tag: match[0].slice(0, match[0].indexOf(">") + 1), text: match[1].replace(/<[^>]+>/g, "") };
  };

  it("renders grouped headers, placed cells, and query totals pinned after the body", () => {
    const markup = render(results, pivot);
    expect(markup).toContain('aria-rowcount="6"');
    expect(markup).toContain('aria-colcount="7"');
    expect(markup).toContain(">3 rows</span>");
    expect(markup).toMatch(/<th scope="col" rowSpan="2" aria-sort="none"[^>]*><button[^>]*><span>Region<\/span>/);
    expect(markup).toMatch(/<th scope="colgroup" colSpan="2" aria-label="Month: 2026-07-01" title="Month: 2026-07-01"><span class="dashboard-table-pivot-header-label">2026-07-01<\/span><\/th>/);
    expect(markup).toContain('aria-label="Revenue · Month: 2026-08-01"');
    expect(markup).toContain('aria-label="Customers · Total"');
    expect(markup).toMatch(/<th scope="colgroup" rowSpan="1" colSpan="2" class="dashboard-table-pivot-total"><span class="dashboard-table-pivot-header-label">Total<\/span>/);
    expect(cell(markup, "row:0", 0).text).toBe("West");
    expect(cell(markup, "row:0", 0).tag).toContain('role="rowheader"');
    expect(cell(markup, "row:0", 1).text).toBe("$1,200.00");
    expect(cell(markup, "row:0", 3).text).toBe("$1,350.50");
    expect(cell(markup, "row:0", 5).text).toBe("$2,550.50");
    expect(cell(markup, "row:0", 6).text).toBe("55");
    expect(cell(markup, "row:1", 1).tag).toContain('data-cell-kind="EMPTY"');
    expect(cell(markup, "row:1", 1).text).toBe("");
    expect(markup).toContain('data-value-kind="absent"></span>');
    expect(cell(markup, "row:2", 1).text).toBe("null");
    expect(markup).toContain('data-value-kind="null">null</span>');
    expect(markup).toContain('<tfoot data-pinned="true">');
    const tfoot = markup.slice(markup.indexOf("<tfoot"), markup.indexOf("</tfoot>"));
    expect(cell(tfoot, "total", 0).text).toBe("Total");
    expect(cell(tfoot, "total", 2).text).toBe("50");
    expect(cell(tfoot, "total", 3).text).toBe("$3,830.50");
    expect(cell(tfoot, "total", 6).text).toBe("104");
    expect(cell(tfoot, "total", 6).tag).toContain("font-weight:500");
    expect(markup).toContain("width:160px");
    expect(markup).toContain("width:120px");
  });

  it("renders a ROWS scorecard with a Values column and repeats no aggregation", () => {
    const scorecard: Extract<DashboardWidget, { pivot: unknown }> = {
      type: "TABLE", queryName: "revenueByMonth",
      pivot: { rows: [], columns: [{ field: "metric_time.month", header: "Month" }], values: [{ field: "metric.revenue", header: "Revenue" }, { field: "metric.unique_customers", header: "Customers" }], valuesAxis: "ROWS", totals: ["revenueTotal"] },
    };
    const markup = render(results, scorecard);
    expect(markup).toContain('<span class="dashboard-table-pivot-header-label">Values</span>');
    expect(markup).toContain('aria-label="Month: 2026-07-01"');
    expect(markup).not.toContain("<button type=\"button\" aria-label=\"Month");
    expect(cell(markup, "row:0:metric.revenue", 0).text).toBe("Revenue");
    expect(cell(markup, "row:0:metric.revenue", 2).text).toBe("$3,830.50");
    expect(cell(markup, "row:0:metric.unique_customers", 3).text).toBe("104");
    expect(markup).not.toContain("<tfoot");
  });

  it("pins total rows only while the header, total rows, and one body row fit the scroll area", () => {
    const rowsAxis = { ...pivot, pivot: { ...pivot.pivot, valuesAxis: "ROWS" as const } };
    const pinned = render(results, { ...rowsAxis, rowHeight: 40 });
    expect(pinned).toContain('<tfoot data-pinned="true">');
    const inFlow = render(results, { ...rowsAxis, rowHeight: 80 });
    expect(inFlow).toContain("<tfoot><tr");
    expect(inFlow.indexOf("</tbody><tfoot>")).toBeGreaterThan(0);
    expect(cell(inFlow, "total:metric.unique_customers", 4).text).toBe("104");
  });

  it("gives body and total cells of a value field without a metric format the same fraction digits", () => {
    const plainRevenue = { ...revenue, display: null };
    const cost = { name: "metric.cost", logicalType: "DECIMAL", nullable: true, display: null };
    const digits = {
      byMonth: semantic("digits", [region, month, plainRevenue, cost], [["West", "2026-07-01", "10.125", "1.5"], ["West", "2026-08-01", "20.5", "2.25"]]),
      byRegion: semantic("digits-by-region", [region, plainRevenue, cost], [["West", "31", "3.754"]]),
    };
    const markup = render(digits, {
      type: "TABLE", queryName: "byMonth",
      pivot: { rows: [{ field: region.name }], columns: [{ field: month.name }], values: [{ field: revenue.name }, { field: cost.name }], valuesAxis: "COLUMNS", totals: ["byRegion"] },
    });
    expect([1, 3, 5].map((column) => cell(markup, "row:0", column).text)).toEqual(["10.125", "20.500", "31.000"]);
    expect([2, 4, 6].map((column) => cell(markup, "row:0", column).text)).toEqual(["1.500", "2.250", "3.754"]);
    expect(cell(markup, "row:0", 5).tag).toContain('title="31"');
  });

  it("shows pivot errors and missing totals with the table error presentation", () => {
    const duplicate = { ...results, revenueByRegionMonth: semantic("duplicate", [region, month, revenue, customers], [["East", "2026-08-01", "1", "1"], ["East", "2026-08-01", "2", "2"]]) };
    expect(render(duplicate, pivot)).toBe('<p role="alert" class="dashboard-table-error">More than one row has Region &quot;East&quot; and Month &quot;2026-08-01&quot;. Each row and column pair must appear once.</p>');
    const { revenueTotal: _revenueTotal, ...withoutGrand } = results;
    expect(render(withoutGrand, pivot)).toContain("No result for query &#x27;revenueTotal&#x27;.");
    expect(render(results, { ...pivot, pivot: { ...pivot.pivot, totals: undefined } })).not.toContain("Total");
  });
});
