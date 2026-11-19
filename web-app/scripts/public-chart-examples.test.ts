import "ses";
import { readFileSync } from "node:fs";
import { beforeAll, describe, expect, it } from "vitest";
import type { DashboardDefinition, DashboardResultColumn } from "../src/api/dashboard";
import type { ChartQueryResult, ChartRenderFrame } from "../src/features/chart-widget/contract/abi";
import type { DashboardFrameColumn } from "../src/features/dashboard/dashboard-result";
import { canonicalFrame, canonicalRenderOption } from "../src/features/chart-widget/runtime/worker/canonical-boundary";
import { evaluateChartSource } from "../src/features/chart-widget/runtime/worker/module-source";
import { checkChartSource } from "./check-chart-source.mjs";

const guide = readFileSync(new URL("../../public-site/src/content/docs/en/docs/dashboards/chart-source.md", import.meta.url), "utf8");
const sources = [...guide.matchAll(/```ts\n([\s\S]*?)\n```/g)].map((match) => match[1]);
const weeklyColumns: readonly DashboardFrameColumn[] = [
  { name: "metric_time.week", logicalType: "DATE", nullable: false, numberFormat: null },
  { name: "metric.revenue", logicalType: "DECIMAL", nullable: true, numberFormat: null },
];
const totalColumn: DashboardFrameColumn = {
  name: "metric.revenue", logicalType: "DECIMAL", nullable: true,
  numberFormat: { style: "decimal", minimumFractionDigits: 2, maximumFractionDigits: 2, signDisplay: "negative" },
};

function render(source: string, queryName: string, result: ChartQueryResult, locale = "en-US") {
  const frame: ChartRenderFrame = canonicalFrame({
    queries: { [queryName]: result }, parameters: {}, variables: {}, locale,
    viewport: { width: 640, height: 320 },
  });
  return canonicalRenderOption(evaluateChartSource(source, "public-chart-example.ts").render(frame));
}

beforeAll(() => lockdown({ errorTaming: "unsafe-debug" }));

describe("public chart source examples", () => {
  it("checks the actual guide sources against the declared queries and result columns", () => {
    expect(sources).toHaveLength(2);
    const examples = [
      { queryName: "weeklyRevenue", source: sources[0], columns: weeklyColumns },
      { queryName: "revenueTotal", source: sources[1], columns: [totalColumn] },
    ];
    for (const { queryName, source, columns } of examples) {
      const definition = {
        parameters: {}, variables: {}, queries: { [queryName]: {} },
        widgets: { chart: { type: "CHART", queryNames: [queryName], module: {
          inputs: { parameters: [], variables: [] }, source,
        } } },
      } as unknown as DashboardDefinition;
      const queryColumns: readonly DashboardResultColumn[] = columns.map(({ name, logicalType, nullable }) => ({
        name, logicalType, nullable, display: null,
      }));
      expect(checkChartSource({ definition, widgetId: "chart", queryColumns: { [queryName]: queryColumns } })).toEqual([]);
    }
  });

  it("renders the weekly line with named date labels, numeric coordinates, and null gaps", () => {
    const option = render(sources[0], "weeklyRevenue", {
      columns: weeklyColumns,
      rows: [
        { "metric_time.week": "2026-09-21", "metric.revenue": "1234.50" },
        { "metric_time.week": "2026-09-28", "metric.revenue": null },
        { "metric_time.week": "2026-10-05", "metric.revenue": "0" },
      ],
    });
    expect(option).toMatchObject({
      tooltip: { trigger: "axis" },
      xAxis: { type: "category", data: ["2026-09-21", "2026-09-28", "2026-10-05"] },
      yAxis: { type: "value" },
      series: [{ type: "line", name: "Revenue", data: [1234.5, null, 0] }],
    });
    expect(render(sources[0], "weeklyRevenue", { columns: weeklyColumns, rows: [] })).toMatchObject({
      xAxis: { data: [] }, series: [{ type: "line", data: [] }],
    });
  });

  it.each([
    ["en-US", "9,007,199,254,740,993.13"],
    ["de-DE", "9.007.199.254.740.993,13"],
  ])("renders exact decimal text in %s with metadata and fallback formats", (locale, text) => {
    for (const numberFormat of [totalColumn.numberFormat, null]) {
      const option = render(sources[1], "revenueTotal", {
        columns: [{ ...totalColumn, numberFormat }], rows: [{ "metric.revenue": "9007199254740993.125" }],
      }, locale);
      expect(option).toMatchObject({ graphic: [{ type: "text", left: "center", top: "middle", silent: true, style: { text } }] });
      expect(option).not.toHaveProperty("series");
      expect(option).not.toHaveProperty("xAxis");
      expect(option).not.toHaveProperty("yAxis");
    }
  });

  it.each([
    ["en-US", totalColumn.numberFormat, "0.00"],
    ["de-DE", totalColumn.numberFormat, "0,00"],
    ["en-US", null, "0"],
    ["de-DE", null, "0"],
  ])("renders zero in %s instead of treating it as missing", (locale, numberFormat, text) => {
    expect(render(sources[1], "revenueTotal", {
      columns: [{ ...totalColumn, numberFormat }], rows: [{ "metric.revenue": "0" }],
    }, locale)).toMatchObject({ graphic: [{ style: { text } }] });
  });

  it.each(["en-US", "de-DE"])("renders No data for null and empty aggregates in %s", (locale) => {
    for (const rows of [[{ "metric.revenue": null }], []]) {
      expect(render(sources[1], "revenueTotal", { columns: [totalColumn], rows }, locale))
        .toMatchObject({ graphic: [{ type: "text", style: { text: "No data" } }] });
    }
  });
});
