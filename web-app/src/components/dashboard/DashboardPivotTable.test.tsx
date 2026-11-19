import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { DashboardResultColumn, DashboardWidget } from "@/api/dashboard";
import { validateDashboardResult } from "@/features/dashboard/dashboard-result";
import { DashboardPivotTable } from "./DashboardPivotTable";

type PivotWidget = Extract<DashboardWidget, { pivot: unknown }>;
const channel: DashboardResultColumn = { name: "dimension.accounts.sales_channel", logicalType: "TEXT", nullable: false, display: null };
const paid: DashboardResultColumn = { name: "metric.paid_accounts", logicalType: "TEXT", nullable: true, display: { style: "NUMBER", decimals: 0 } };

function render(columns: DashboardResultColumn[], rows: (string | boolean | null)[][], values: PivotWidget["pivot"]["values"]) {
  const result = validateDashboardResult({ queryRunId: "scorecard-run", queryType: "SEMANTIC", reportingTimeZone: "UTC", columns, rows });
  const widget: PivotWidget = {
    type: "TABLE", queryName: "scorecard",
    pivot: { rows: [], columns: [{ field: channel.name, header: "Sales channel" }], values, valuesAxis: "ROWS" },
  };
  return renderToStaticMarkup(<DashboardPivotTable widget={widget} results={{ scorecard: result }} parameters={{}} variables={{}} />);
}

function cell(markup: string, field: string, columnIndex: number) {
  const rowId = `row:0:${field}`;
  const match = new RegExp(`<td[^>]*data-row-id="${rowId}" data-column-index="${columnIndex}"[^>]*>(.*?)</td>`).exec(markup);
  if (!match) throw new Error(`No cell ${rowId}:${columnIndex}`);
  return { tag: match[0].slice(0, match[0].indexOf(">") + 1), text: match[1].replace(/<[^>]+>/g, "") };
}

function header(markup: string, label: string) {
  const match = markup.match(/<th\b[^>]*>[\s\S]*?<\/th>/g)?.find((element) => element.includes(`>${label}</span>`));
  if (!match) throw new Error(`No header ${label}`);
  return match.slice(0, match.indexOf(">") + 1);
}

describe("DashboardPivotTable scorecard alignment", () => {
  beforeEach(() => vi.stubGlobal("navigator", { language: "en-US" }));
  afterEach(() => vi.unstubAllGlobals());

  it("aligns channel headers and numeric metric cells when an explicit number format accompanies TEXT", () => {
    const arpa: DashboardResultColumn = { name: "metric.arpa", logicalType: "FLOAT", nullable: true, display: { style: "CURRENCY", decimals: 2, currency: "USD" } };
    const nrr: DashboardResultColumn = { name: "metric.net_revenue_retention", logicalType: "FLOAT", nullable: true, display: { style: "PERCENT", decimals: 1 } };
    const conversion: DashboardResultColumn = { name: "metric.trial_conversion_rate", logicalType: "FLOAT", nullable: true, display: { style: "PERCENT", decimals: 1 } };
    const columns = [channel, paid, arpa, nrr, conversion];
    const markup = render(columns, [
      ["Partner", "134", "184.72805970149255", "1.0820391734667663", "0.21"],
      ["Sales-led", "100", "225.49560000000002", "1.084569656241923", "0.165"],
      ["Self-serve", "93", "217.68193548387094", "1.0801671060763713", "0.15"],
    ], columns.slice(1).map((column) => ({ field: column.name, width: 150 })));

    for (const label of ["Partner", "Sales-led", "Self-serve"]) expect(header(markup, label)).toContain('data-numeric="true"');
    expect(header(markup, "Values")).not.toContain("data-numeric");
    const expected = [["134", "100", "93"], ["$184.73", "$225.50", "$217.68"], ["108.2%", "108.5%", "108.0%"], ["21.0%", "16.5%", "15.0%"]];
    columns.slice(1).forEach((column, valueIndex) => expected[valueIndex].forEach((text, channelIndex) => {
      const presented = cell(markup, column.name, channelIndex + 1);
      expect(presented.text).toBe(text);
      expect(presented.tag).toContain('data-numeric="true"');
      expect(presented.tag).toContain("text-align:right");
    }));
    expect(markup).toContain("width:630px");
  });

  it.each([
    ["TEXT", "Enterprise"],
    ["DATE", "2026-10-12"],
    ["BOOLEAN", true],
  ] as const)("preserves label headers and non-numeric %s values in mixed scorecards", (logicalType, value) => {
    const raw: DashboardResultColumn = { name: "raw_value", logicalType, nullable: false, display: null };
    const markup = render([channel, paid, raw], [["Partner", "134", value]], [{ field: paid.name }, { field: raw.name }]);
    expect(header(markup, "Partner")).not.toContain("data-numeric");
    expect(cell(markup, paid.name, 1).tag).toContain('data-numeric="true"');
    const presented = cell(markup, raw.name, 1);
    expect(presented.text).toBe(String(value));
    expect(presented.tag).not.toContain("data-numeric");
    expect(presented.tag).not.toContain("text-align:right");
  });
});
