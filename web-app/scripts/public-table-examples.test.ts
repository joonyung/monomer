import "ses";
import { readFileSync } from "node:fs";
import { beforeAll, describe, expect, it } from "vitest";
import type { DashboardFrameColumn } from "../src/features/dashboard/dashboard-result";
import type { RawRow, TableFrame } from "../src/features/table-widget/contract/abi";
import { presentedCellView } from "../src/features/table-widget/runtime/output-boundary";
import type { TableBatch } from "../src/features/table-widget/runtime/worker-protocol";
import { prepareTablePresentation, renderTableBatch } from "../src/features/table-widget/runtime/worker/runtime";
import { numericColumnFractionDigits, tableCellFormatter } from "../src/features/table-widget/table-values";

type GuideWidget = {
  type: string;
  queryName: string;
  columns: { field: string }[];
  module: { inputs: { queries: string[]; parameters: string[]; variables: string[] }; source: string };
};
type QueryResult = { columns: readonly DashboardFrameColumn[]; rows: readonly RawRow[] };

const guide = readFileSync(new URL("../../public-site/src/content/docs/en/docs/dashboards/table-source.md", import.meta.url), "utf8");
const sources = [...guide.matchAll(/```ts\n([\s\S]*?)\n```/g)].map((match) => match[1]);
const widgets = [...guide.matchAll(/```jsonc\n([\s\S]*?)\n```/g)].map((match) => JSON.parse(match[1]) as GuideWidget);

const column = (name: string, logicalType: string, numberFormat: DashboardFrameColumn["numberFormat"] = null): DashboardFrameColumn => ({
  name, logicalType, nullable: true, numberFormat,
});
const usd: DashboardFrameColumn["numberFormat"] = {
  style: "currency", currency: "USD", minimumFractionDigits: 2, maximumFractionDigits: 2, signDisplay: "negative",
};

// Builds the frame and display digits the Host prepares for the guide's widget entry, then requests every
// declared column of every row in one batch through the real Worker preparation and batch path.
function renderExample(index: number, results: Readonly<Record<string, QueryResult>>, locale: string) {
  const widget = widgets[index];
  const queryNames = [widget.queryName, ...widget.module.inputs.queries];
  expect(Object.keys(results).sort()).toEqual(queryNames.sort());
  const frame: TableFrame = {
    queries: results, parameters: {}, variables: {}, locale, rowQueryName: widget.queryName,
  };
  const primary = results[widget.queryName];
  const digits = numericColumnFractionDigits({ queryRunId: "public-table-example", queryType: "SEMANTIC", ...primary });
  const batch = renderTableBatch(prepareTablePresentation(widget.module.source, frame, digits), {
    rowIndices: primary.rows.map((_, rowIndex) => rowIndex),
    fields: widget.columns.map((entry) => entry.field),
  });
  const hostText = tableCellFormatter(primary.columns, locale, digits);
  return { batch, hostText, primary };
}

function cellViews(batch: TableBatch, field: string) {
  expect(batch.cells.filter((cell) => cell.error !== undefined)).toEqual([]);
  expect(batch.rows.filter((row) => row.error !== undefined)).toEqual([]);
  return batch.cells.filter((cell) => cell.field === field).map((cell) => cell.view);
}

beforeAll(() => lockdown({ errorTaming: "unsafe-debug" }));

describe("public TABLE source guide examples", () => {
  it("pairs each of the four sources with a schema-shaped TABLE widget entry carrying the same source", () => {
    expect(sources).toHaveLength(4);
    expect(widgets).toHaveLength(4);
    widgets.forEach((widget, index) => {
      expect(widget.type).toBe("TABLE");
      expect(widget.module.source).toBe(sources[index]);
      expect(widget.module.inputs.parameters).toEqual([]);
      expect(widget.module.inputs.variables).toEqual([]);
    });
  });

  it("shades revenue from the host text marker without rewriting the host's numbers", () => {
    const columns = [column("dimension.orders.region", "TEXT"), column("metric.revenue", "DECIMAL")];
    const rows = [
      { "dimension.orders.region": "East", "metric.revenue": "100" },
      { "dimension.orders.region": "West", "metric.revenue": "300.50" },
      { "dimension.orders.region": "North", "metric.revenue": null },
      { "dimension.orders.region": "South", "metric.revenue": "200.25" },
    ];
    const { batch, hostText, primary } = renderExample(0, { revenue_by_region: { columns, rows } }, "en-US");
    const views = cellViews(batch, "metric.revenue");
    expect(views).toEqual([
      { hostText: true, background: "#3870DF18" },
      { hostText: true, background: "#3870DF80" },
      undefined,
      { hostText: true, background: "#3870DF4c" },
    ]);
    const shown = views.map((view, rowIndex) => view && presentedCellView(view, hostText("metric.revenue", primary.rows[rowIndex]["metric.revenue"])).text);
    expect(shown).toEqual(["100.00", "300.50", undefined, "200.25"]);
    expect(cellViews(batch, "dimension.orders.region")).toEqual([undefined, undefined, undefined, undefined]);
  });

  it("maps known statuses to labelled colors and leaves other values to the host", () => {
    const columns = [column("dimension.orders.status", "TEXT"), column("metric.order_count", "INTEGER")];
    const rows = ["delivered", "shipped", "pending", "cancelled", "returned", null]
      .map((status, index) => ({ "dimension.orders.status": status, "metric.order_count": String(index + 1) }));
    const { batch } = renderExample(1, { orders_by_status: { columns, rows } }, "en-US");
    const style = { emphasis: "strong", align: "center" };
    expect(cellViews(batch, "dimension.orders.status")).toEqual([
      { text: "Delivered", color: "#007044", background: "#3695651F", ...style },
      { text: "Shipped", color: "#3055B6", background: "#ECF4FE", ...style },
      { text: "Pending", color: "#806A1F", background: "#806A1F1F", ...style },
      { text: "Cancelled", color: "#A5092A", background: "#BE20381A", ...style },
      undefined,
      undefined,
    ]);
    expect(cellViews(batch, "metric.order_count").every((view) => view === undefined)).toBe(true);
  });

  it.each([
    ["en-US", [
      { text: "$112.30 (▲ +12.3%)", tooltip: "Previous period: $100.00", color: "#007044" },
      { text: "$80.00 (▼ -20%)", tooltip: "Previous period: $100.00", color: "#BE2038" },
      { text: "$50.00 (0%)", tooltip: "Previous period: $50.00" },
    ]],
    ["de-DE", [
      { text: "112,30 $ (▲ +12,3 %)", tooltip: "Previous period: 100,00 $", color: "#007044" },
      { text: "80,00 $ (▼ -20 %)", tooltip: "Previous period: 100,00 $", color: "#BE2038" },
      { text: "50,00 $ (0 %)", tooltip: "Previous period: 50,00 $" },
    ]],
  ])("appends the change from the auxiliary query with Intl in %s", (locale, changed) => {
    const columns = [column("dimension.orders.region", "TEXT"), column("metric.revenue", "DECIMAL", usd)];
    const revenue = (region: string, amount: string | null) => ({ "dimension.orders.region": region, "metric.revenue": amount });
    const { batch } = renderExample(2, {
      revenue_by_region: { columns, rows: [revenue("East", "112.30"), revenue("West", "80.00"), revenue("North", "50.00"), revenue("South", "70.00"), revenue("Central", null)] },
      revenue_previous: { columns, rows: [revenue("East", "100.00"), revenue("West", "100.00"), revenue("North", "50.00")] },
    }, locale);
    expect(cellViews(batch, "metric.revenue")).toEqual([...changed, undefined, undefined]);
  });

  it.each([
    ["en-US", ["Sep 21, 2026", "Oct 1, 2026"]],
    ["de-DE", ["21.09.2026", "01.10.2026"]],
  ])("formats DATE values with an explicit UTC time zone in %s", (locale, dates) => {
    const columns = [column("metric_time.day", "DATE"), column("metric.revenue", "DECIMAL")];
    const rows = [
      { "metric_time.day": "2026-09-21", "metric.revenue": "120.5" },
      { "metric_time.day": "2026-10-01", "metric.revenue": "98" },
      { "metric_time.day": null, "metric.revenue": null },
    ];
    const { batch } = renderExample(3, { daily_revenue: { columns, rows } }, locale);
    expect(cellViews(batch, "metric_time.day")).toEqual([{ text: dates[0] }, { text: dates[1] }, undefined]);
    expect(cellViews(batch, "metric.revenue").every((view) => view === undefined)).toBe(true);
  });
});
