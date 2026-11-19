import { describe, expect, it } from "vitest";
import type { DashboardWidget } from "@/api/dashboard";
import { widgetQueryNames } from "./widget-query-names";

const module = (queries: string[]) => ({ inputs: { queries, parameters: [], variables: [] }, source: "export default defineTable({ render() { return {}; } });" });

describe("widget query names", () => {
  it("maps each widget to the distinct query names it reads", () => {
    expect(widgetQueryNames({ type: "CHART", queryNames: ["revenue", "targets"], module: { inputs: { parameters: [], variables: [] }, source: "" } })).toEqual(["revenue", "targets"]);
    expect(widgetQueryNames({ type: "TABLE", queryName: "accounts", columns: [{ field: "name" }], module: module(["accounts", "targets"]) })).toEqual(["accounts", "targets"]);
    expect(widgetQueryNames({ type: "TEXT", content: "Notes" })).toEqual([]);
    expect(widgetQueryNames({ type: "INPUT", variableName: "region" })).toEqual([]);
  });

  it("includes a pivot's totals queries after the primary and source queries, in order and once", () => {
    const pivot: DashboardWidget = {
      type: "TABLE",
      queryName: "revenueByRegionMonth",
      module: module(["targets", "revenueTotal"]),
      pivot: {
        rows: [{ field: "dimension.orders.region" }],
        columns: [{ field: "metric_time.month" }],
        values: [{ field: "metric.revenue" }],
        valuesAxis: "COLUMNS",
        totals: ["revenueByRegion", "revenueTotal", "revenueByMonth"],
      },
    };
    expect(widgetQueryNames(pivot)).toEqual(["revenueByRegionMonth", "targets", "revenueTotal", "revenueByRegion", "revenueByMonth"]);
    expect(widgetQueryNames({ ...pivot, module: undefined, pivot: { ...(pivot as Extract<DashboardWidget, { pivot: unknown }>).pivot, totals: undefined } })).toEqual(["revenueByRegionMonth"]);
  });
});
