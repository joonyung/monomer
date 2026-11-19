import { describe, expect, it } from "vitest";
import document from "../../../../../testkit/dashboard-d10/dashboard.json";
import source from "./chart-source.ts.txt?raw";
import expectedResults from "./expected-results.json";
import orders from "./orders.json";
import { validateDashboardResult, toChartQueryResult } from "@/features/dashboard/dashboard-result";

type Variables = { selectedCountry: string; view: string; analysisAxis: string; timeUnit: string };
type EventFrame = { variables: Variables; setVariables: (values: Record<string, string>) => void };
type Module = {
  setup: (context: { chart: { on: (name: string, selector: object, handler: (event: { name: string }, frame: EventFrame) => void) => void } }) => void;
  render: (frame: { queries: object; variables: Variables; locale: string }) => { title: { text: string }; xAxis: { data: string[] }; series: { data: { value: number; name: string }[] }[]; graphic: { style: { text: string } }[] };
};
const module = new Function("defineChart", source.replace("export default", "return"))((value: Module) => value) as Module;
const queries = Object.fromEntries(Object.entries(expectedResults).map(([name, result]) => [name, toChartQueryResult(validateDashboardResult({ queryRunId: name, queryType: "SEMANTIC", reportingTimeZone: null, ...result, columns: result.columns.map(column => ({ ...column, display: null })) }))]));
queries.salesByCity = queries.salesByCityKR;
const variables: Variables = { selectedCountry: "KR", view: "country", analysisAxis: "geography", timeUnit: "day" };

describe("D10 portable source and fixed semantic results", () => {
  it("embeds exactly the tested portable source and only existing widget kinds", () => {
    expect(document.widgets.salesChart.module.source).toBe(source);
    expect(new Set(Object.values(document.widgets).map(widget => widget.type))).toEqual(new Set(["INPUT", "CHART"]));
    expect(document.widgets.salesChart.queryNames).toEqual(Object.keys(document.queries));
  });
  it.each([
    [variables, ["KR", "US"], [600, 900]],
    [{ ...variables, view: "city" }, ["Busan", "Seoul"], [300, 300]],
    [{ ...variables, analysisAxis: "channel" }, ["Online", "Store"], [900, 600]],
    [{ ...variables, analysisAxis: "time" }, ["2026-01-01", "2026-01-02", "2026-02-01"], [100, 900, 500]],
    [{ ...variables, analysisAxis: "time", timeUnit: "month" }, ["2026-01-01", "2026-02-01"], [1000, 500]],
  ])("selects one query and its canonical fields: %j", (input, labels, sales) => {
    const option = module.render({ queries, variables: input, locale: "en-US" });
    expect(option.xAxis.data).toEqual(labels);
    expect(option.series[0].data.map(row => row.value)).toEqual(sales);
    expect(option.series[0].data.map(row => row.name)).toEqual(labels);
  });
  it("atomic chart mutation belongs only to country bars", () => {
    let handler: (event: { name: string }, frame: EventFrame) => void = () => { throw new Error("not registered"); };
    module.setup({ chart: { on: (_name, _selector, registered) => { handler = registered; } } });
    const calls: Record<string, string>[] = [];
    handler({ name: "US" }, { variables, setVariables: values => calls.push(values) });
    handler({ name: "Online" }, { variables: { ...variables, analysisAxis: "channel" }, setVariables: values => calls.push(values) });
    expect(calls).toEqual([{ selectedCountry: "US", view: "city" }]);
    expect(variables.selectedCountry).toBe("KR");
  });
  it("January distinct customers cannot be obtained by adding daily results", () => {
    const januaryOrders = orders.rows.filter(row => String(row[0]).startsWith("2026-01"));
    expect(new Set(januaryOrders.map(row => row[4])).size).toBe(3);
    const dailyCustomers = expectedResults.salesByDay.rows.slice(0, 2).reduce((sum, row) => sum + Number(row[3]), 0);
    expect(dailyCustomers).toBe(4);
    expect(expectedResults.salesByDay.rows.slice(0, 2).reduce((sum, row) => sum + Number(row[2]), 0) / 2).toBe(200);
    const rendered = module.render({ queries, variables: { ...variables, analysisAxis: "time", timeUnit: "month" }, locale: "en-US" });
    expect(rendered.graphic[0].style.text).toContain("2026-01-01: average 250.00 / customers 3");
    expect(rendered.graphic[0].style.text).not.toContain("customers 4");
  });

  it("formats decimal averages without binary-float conversion and preserves null", () => {
    const result = { columns: [{ name: "dimension.orders.country" }], rows: [
      { "dimension.orders.country": "KR", "metric.average_order": "9007199254740993.015", "metric.customers": "3" },
      { "dimension.orders.country": "US", "metric.average_order": "157724.09999999998", "metric.customers": "2" },
      { "dimension.orders.country": "CA", "metric.average_order": null, "metric.customers": "0" },
    ] };
    const frame = { queries: { salesByCountry: result }, variables, locale: "en-US" };
    expect(module.render(frame).graphic[0].style.text).toBe("KR: average 9,007,199,254,740,993.02 / customers 3\nUS: average 157,724.10 / customers 2\nCA: average null / customers 0");
    expect(module.render({ ...frame, locale: "de-DE" }).graphic[0].style.text).toContain("157.724,10");
  });
});
