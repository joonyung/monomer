import { toChartQueryResult, validateDashboardResult } from "@/features/dashboard/dashboard-result";
import type { ChartWidgetInputs } from "@/features/chart-widget/runtime/worker-protocol";

export const chartWidgetFixtureSource = `
export default defineChart({
  setup({ chart }) {
    chart.on("click", { seriesId: "orders" }, (params, frame) => {
      frame.setVariables({ selectedRegion: String(params.name ?? "") });
      chart.dispatchAction({
        type: "showTip",
        seriesId: "orders",
        dataIndex: Number(params.dataIndex ?? 0),
      });
    });
  },
  render({ queries, variables, viewport }) {
    const rows = queries["ordersByRegion"].rows;
    const selected = variables["selectedRegion"];
    return {
      animation: false,
      grid: { left: 48, right: 24, top: 24, bottom: 48 },
      xAxis: {
        type: "category",
        data: rows.map((row) => row["dimension.orders.status"]),
      },
      yAxis: { type: "value" },
      series: [{
        id: "orders",
        type: "bar",
        data: rows.map((row) => ({
          value: Number(row["metric.order_count"]),
          itemStyle: { opacity: selected === null || selected === row["dimension.orders.status"] ? 1 : 0.35 },
        })),
      }],
      graphic: [{
        type: "text",
        left: 48,
        top: 4,
        style: {
          text: "Orders by region (" + viewport.width + "px)",
          fill: "#1e2226",
          fontSize: 12,
        },
      }],
    };
  },
});
`;

export const chartWidgetFixtureInputs: ChartWidgetInputs = {
  moduleSource: chartWidgetFixtureSource,
  queries: {
    ordersByRegion: toChartQueryResult(validateDashboardResult({ queryType: "SEMANTIC" as const, reportingTimeZone: null,
      queryRunId: "fixture-orders",
      columns: [
        { name: "dimension.orders.status", logicalType: "TEXT", nullable: false, display: null },
        { name: "metric.order_count", logicalType: "NUMBER", nullable: false, display: null },
      ],
      rows: [["North", "63"], ["South", "47"], ["East", "55"], ["West", "38"]],
    })),
  },
  parameters: {},
  variables: {
    selectedRegion: { value: null, type: "TEXT", nullable: true },
  },
  locale: "en-US",
  viewport: { width: 720, height: 360 },
};

const exactColumns = [
  { name: "dimension.customers.code", logicalType: "TEXT", nullable: false, display: null },
  { name: "metric.amount", logicalType: "DECIMAL", nullable: false, display: null },
  { name: "dimension.customers.optional", logicalType: "TEXT", nullable: true, display: null },
  { name: "dimension.customers.enabled", logicalType: "BOOLEAN", nullable: false, display: null },
  { name: "dimension.page.url", logicalType: "TEXT", nullable: false, display: null },
];
const exactValues = ["00123", "12345678901234567890.12345678901234567", null, true, "https://example.test"];
const exactQueries = {
  exact: toChartQueryResult(validateDashboardResult({ queryType: "SEMANTIC" as const, reportingTimeZone: null, queryRunId: "fixture-exact", columns: exactColumns, rows: [exactValues] })),
  empty: toChartQueryResult(validateDashboardResult({ queryType: "SEMANTIC" as const, reportingTimeZone: null, queryRunId: "fixture-empty", columns: exactColumns, rows: [] })),
};

const exactSource = `
export default defineChart({
  render({ queries, parameters, variables }) {
    if (parameters.pageUrl !== "https://example.test/parameter" || variables.pageUrl !== "https://example.test/variable") throw new Error("URL frame values changed");
    const expectedColumns = ${JSON.stringify(exactColumns.map(({ display: _display, ...column }) => ({ ...column, numberFormat: null })))};
    const expectedValues = ${JSON.stringify(exactValues)};
    for (const name of ["exact", "empty"]) {
      const result = queries[name];
      if (JSON.stringify(result.columns) !== JSON.stringify(expectedColumns)) throw new Error("Descriptor metadata changed: " + name);
      if (!Object.isFrozen(result.columns) || !result.columns.every(Object.isFrozen)) throw new Error("Descriptors are mutable");
    }
    if (queries.empty.rows.length !== 0) throw new Error("Empty result changed");
    const row = queries.exact.rows[0];
    if (JSON.stringify(expectedColumns.map((column) => row[column.name])) !== JSON.stringify(expectedValues)) throw new Error("Exact cells changed");
    if (!Object.isFrozen(row)) throw new Error("Row is mutable");
    const url = row["dimension.page.url"];
    return {
      animation: false,
      title: { text: url, subtext: "TEXT " + row["dimension.customers.code"] + " | DECIMAL " + row["metric.amount"] + "\\nnull | true | empty descriptors preserved", textStyle: { fontSize: 16 } },
      grid: { left: 48, right: 24, top: 115, bottom: 50 },
      xAxis: { type: "category", data: [url], axisLabel: { formatter: () => url } },
      yAxis: { type: "value", max: 10 },
      tooltip: { trigger: "item", renderMode: "richText", transitionDuration: 0, formatter: () => url + "\\n" + parameters.pageUrl + "\\n" + variables.pageUrl },
      series: [{ type: "bar", data: [6], label: { show: true, position: "top", formatter: () => url } }],
    };
  },
});`;

const raster = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==";
const customSource = `export default defineChart({ render() { return {
  animation: false,
  title: { text: "Inline raster + synchronous custom renderItem", textStyle: { fontSize: 16 } },
  xAxis: { min: 0, max: 10 }, yAxis: { min: 0, max: 10 },
  graphic: [{ type: "image", left: 40, top: 55, style: { image: ${JSON.stringify(raster)}, width: 40, height: 40 } }],
  series: [{ type: "custom", data: [[5, 5]], renderItem(params, api) {
    const point = api.coord([api.value(0), api.value(1)]);
    return { type: "rect", shape: { x: point[0] - 30, y: point[1] - 20, width: 60, height: 40 }, style: { fill: "#2466c3" } };
  } }],
}; } });`;

const microtask = 'Promise.resolve().then(() => { while (true) {} });';
export const chartWidgetFixtureScenarios: Readonly<Record<string, ChartWidgetInputs>> = {
  events: chartWidgetFixtureInputs,
  "saved-setter": { ...chartWidgetFixtureInputs, moduleSource: "let saved; " + chartWidgetFixtureSource.replace('frame.setVariables({ selectedRegion: String(params.name ?? "") });', 'if (saved) saved({ selectedRegion: "SHOULD_NOT_SUBMIT" }); else saved = frame.setVariables;') },
  "empty-option": { ...chartWidgetFixtureInputs, moduleSource: "export default defineChart({ render() { return {}; } });" },
  "detached-setup": { ...chartWidgetFixtureInputs, moduleSource: `export default defineChart({ setup() { ${microtask} }, render() { return {}; } });` },
  "detached-event": { ...chartWidgetFixtureInputs, moduleSource: chartWidgetFixtureSource.replace('frame.setVariables({ selectedRegion: String(params.name ?? "") });', microtask) },
  "async-event": { ...chartWidgetFixtureInputs, moduleSource: chartWidgetFixtureSource.replace('frame.setVariables({ selectedRegion: String(params.name ?? "") });', 'return Promise.resolve();') },
  "async-setup": { ...chartWidgetFixtureInputs, moduleSource: "export default defineChart({ setup() { return Promise.resolve(); }, render() { return {}; } });" },
  "exact-values": { ...chartWidgetFixtureInputs, moduleSource: exactSource, queries: exactQueries, parameters: { pageUrl: "https://example.test/parameter" }, variables: { pageUrl: { value: "https://example.test/variable", type: "TEXT", nullable: false } } },
  "inline-custom": { ...chartWidgetFixtureInputs, moduleSource: customSource },
};
