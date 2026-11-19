import "ses";
import { beforeAll, describe, expect, it } from "vitest";
import { evaluateChartSource } from "@/features/chart-widget/runtime/worker/module-source";
import { EXPLORE_CHART_SOURCE } from "./chart-plan";

beforeAll(() => lockdown({ errorTaming: "unsafe-debug" }));

type Row = Readonly<Record<string, string | null>>;

function render(shape: "line" | "bar", rows: Row[], seriesColumn: string | null, xLabels: Record<string, string> = {}) {
  const module = evaluateChartSource(EXPLORE_CHART_SOURCE, "explore-chart.ts");
  return module.render({
    queries: { result: { columns: [], rows } },
    parameters: { shape, xColumn: "time", xLabels, seriesColumn, metricColumn: "value", metricLabel: "Revenue", xLabel: "Month" },
    variables: {},
    locale: "en-US",
    viewport: { width: 640, height: 360 },
  }) as { xAxis: { data: string[] }; series: Array<{ name: string; data: Array<[string, number | null]> }> };
}

describe("Explore chart source", () => {
  it("reads the original decimal per point after line sorting instead of reconstructing rounded coordinates", () => {
    const module = evaluateChartSource(EXPLORE_CHART_SOURCE, "explore-chart.ts");
    const option = module.render({
      queries: { result: { columns: [], rows: [
        { time: "2026-08-02", value: "9007199254740993.125" },
        { time: "2026-08-01", value: "0.00000000000000000000001" },
      ] } },
      parameters: { shape: "line", xColumn: "time", xLabels: {}, seriesColumn: null, metricColumn: "value", metricLabel: "Revenue", xLabel: "Day",
        valueLabels: { "9007199254740993.125": "$9,007,199,254,740,993.13", "0.00000000000000000000001": "$0.00000000000000000000001" } },
      variables: {}, locale: "en-US", viewport: { width: 640, height: 360 },
    }) as { tooltip: { formatter(params: unknown): string } };
    expect(option.tooltip.formatter({ seriesIndex: 0, seriesName: "Revenue", dataIndex: 1 })).toBe("Revenue: $9,007,199,254,740,993.13");
    expect(option.tooltip.formatter({ seriesIndex: 0, seriesName: "Revenue", dataIndex: 0 })).toBe("Revenue: $0.00000000000000000000001");
  });
  it("orders line points by their time value, empty last, without merging rows", () => {
    const option = render("line", [
      { time: "2025-11-01", region: "EU", value: "30" },
      { time: null, region: "EU", value: "5" },
      { time: "2025-10-01", region: "UK", value: "20" },
      { time: "2025-12-01", region: "EU", value: "10" },
      { time: "2025-10-01", region: "EU", value: "40" },
    ], "region");

    expect(option.xAxis.data).toEqual(["2025-10-01", "2025-11-01", "2025-12-01", "No value"]);
    expect(option.series.map((series) => series.name)).toEqual(["UK", "EU"]);
    expect(option.series[1]?.data).toEqual([["2025-10-01", 40], ["2025-11-01", 30], ["2025-12-01", 10], ["No value", 5]]);
    expect(option.series[0]?.data).toEqual([["2025-10-01", 20]]);
  });

  it("labels the time axis with the host's grain labels while ordering by the raw value", () => {
    const option = render("line", [
      { time: "2026-07-01", value: "2" },
      { time: "2026-06-01", value: "1" },
    ], null, { "2026-06-01": "Jun 2026", "2026-07-01": "Jul 2026" });

    expect(option.xAxis.data).toEqual(["Jun 2026", "Jul 2026"]);
    expect(option.series[0]?.data).toEqual([["Jun 2026", 1], ["Jul 2026", 2]]);
  });

  it("keeps bars in the returned order", () => {
    const option = render("bar", [
      { time: "Paid", value: "9" },
      { time: "Draft", value: "3" },
      { time: "Open", value: "5" },
    ], null);

    expect(option.xAxis.data).toEqual(["Paid", "Draft", "Open"]);
    expect(option.series[0]?.data).toEqual([["Paid", 9], ["Draft", 3], ["Open", 5]]);
  });

  it("lays more than six bars on their side so every category keeps its label", () => {
    const regions = ["North America", "Europe", "Asia Pacific", "Latin America", "Middle East", "United Kingdom", "Japan"];
    const option = render("bar", regions.map((region, index) => ({ time: region, value: String(10 - index) })), null) as unknown as {
      yAxis: { type: string; data: string[]; inverse: boolean; axisLabel: { interval: number } };
      series: Array<{ data: Array<[number | null, string]> }>;
    };

    expect(option.yAxis).toMatchObject({ type: "category", data: regions, inverse: true, axisLabel: { interval: 0 } });
    expect(option.series[0]?.data[0]).toEqual([10, "North America"]);
  });
});
