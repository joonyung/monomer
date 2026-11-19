import { toChartQueryResult, validateDashboardResult } from "@/features/dashboard/dashboard-result";
import type { ChartWidgetInputs } from "@/features/chart-widget/runtime/worker-protocol";

export type ExpressionScenario = Readonly<{
  id: string;
  subcase: string;
  description: string;
  expectedText?: readonly string[];
  inputs: ChartWidgetInputs;
}>;

const baseViewport = { width: 720, height: 360 } as const;
const base = {
  parameters: {},
  locale: "en-US",
  viewport: baseViewport,
} as const;

function result(
  queryRunId: string,
  columns: readonly { name: string; logicalType: "TEXT" | "NUMBER" | "DECIMAL" | "BOOLEAN" | "DATE"; nullable: boolean }[],
  rows: readonly (readonly (string | boolean | null)[])[],
) {
  return toChartQueryResult(validateDashboardResult({ queryType: "SEMANTIC" as const, reportingTimeZone: null, queryRunId, columns: columns.map(column => ({ ...column, display: null })), rows: rows.map(row => [...row]) }));
}

const salesColumns = [
  { name: "dimension.date", logicalType: "DATE", nullable: false },
  { name: "metric.amount", logicalType: "DECIMAL", nullable: false },
] as const;
const salesRows = [
  ["2026-09-01", "20000.00"],
  ["2026-09-02", "24000.00"],
  ["2026-09-03", "30520.00"],
] as const;

const kpiSource = `
export default defineChart({
  render({ queries, locale }) {
    const rows = queries.current.rows;
    const total = rows.reduce((sum, row) => sum + Number(row["metric.amount"]), 0);
    const previous = Number(queries.previous.rows[0]["metric.amount"]);
    const growthValue = (total - previous) / previous * 100;
    const growth = previous === 0 ? "—" : (growthValue > 0 ? "+" : "") + growthValue.toFixed(1) + "%";
    const money = new Intl.NumberFormat(locale, { style: "currency", currency: "USD", minimumFractionDigits: 2 }).format(total.toFixed(2));
    const previousMoney = new Intl.NumberFormat(locale, { style: "currency", currency: "USD", minimumFractionDigits: 2 }).format(queries.previous.rows[0]["metric.amount"]);
    const trend = rows.map(row => new Intl.NumberFormat(locale, { maximumFractionDigits: 0 }).format(row["metric.amount"])).join("→");
    return {
      animation: false,
      title: { text: money, subtext: "Previous " + previousMoney + " · " + growth + " · Trend " + trend },
      tooltip: { trigger: "axis", renderMode: "richText", formatter: (params) => "Trend " + params[0].value },
      xAxis: { type: "category", data: rows.map(row => row["dimension.date"]) },
      yAxis: { type: "value" },
      series: [{ type: "line", data: rows.map(row => Number(row["metric.amount"])) }],
    };
  },
});`;

const decimalSource = `
export default defineChart({
  render({ queries, locale }) {
    const row = queries.decimal.rows[0];
    const raw = row["metric.amount"];
    const formatted = new Intl.NumberFormat(locale, { style: "currency", currency: "USD", maximumFractionDigits: 2 }).format(raw);
    return {
      animation: false,
      title: { text: raw, subtext: formatted + " · DECIMAL string preserved" },
      xAxis: { type: "category", data: ["exact"] }, yAxis: { type: "value" },
      series: [{ type: "bar", data: [Number(raw)] }],
    };
  },
});`;

const signedSource = `
export default defineChart({
  render({ queries, locale }) {
    const formatter = new Intl.NumberFormat(locale, { style: "currency", currency: "USD", minimumFractionDigits: 2 });
    const labels = queries.amounts.rows.map(row => row["metric.amount"] === null ? "—" : formatter.format(row["metric.amount"]));
    return {
      animation: false,
      title: { text: labels.join(" | "), subtext: "zero · negative · null" },
      xAxis: { type: "category", data: labels }, yAxis: { type: "value" },
      series: [{ type: "bar", data: queries.amounts.rows.map(row => row["metric.amount"] === null ? 0 : Number(row["metric.amount"])), itemStyle: { color: params => Number(queries.amounts.rows[params.dataIndex]["metric.amount"] ?? 0) < 0 ? "#be2038" : "#2466c3" } }],
    };
  },
});`;

const comparisonSource = `
export default defineChart({
  render({ queries, locale }) {
    const current = queries.current.rows[0]["metric.amount"];
    const previous = queries.previous.rows[0]["metric.amount"];
    const comparable = previous !== null && Number(previous) !== 0;
    const rate = comparable ? ((Number(current) - Number(previous)) / Number(previous) * 100).toFixed(1) + "%" : "—";
    const reason = comparable ? "" : "comparison unavailable";
    const money = new Intl.NumberFormat(locale, { style: "currency", currency: "USD" }).format(current);
    return { animation: false, title: { text: money + " · " + rate, subtext: reason }, xAxis: { type: "category", data: ["current"] }, yAxis: { type: "value" }, series: [{ type: "bar", data: [Number(current)] }] };
  },
});`;

const emptySource = `
export default defineChart({
  render({ queries }) {
    const rows = queries.sales.rows;
    if (rows.length === 0) return { animation: false, title: { text: "No data", subtext: "Run SUCCEEDED · row count 0" }, xAxis: { type: "category", data: [] }, yAxis: { type: "value" }, series: [{ type: "line", data: [] }] };
    return { animation: false, title: { text: "Total $74,520.00" }, xAxis: { type: "category", data: rows.map(row => row["dimension.date"]) }, yAxis: { type: "value" }, series: [{ type: "line", data: rows.map(row => Number(row["metric.amount"])) }] };
  },
});`;

const reorderedSource = `
export default defineChart({
  render({ queries }) {
    const row = queries.sales.rows[0];
    if (!("metric.amount" in row)) throw new Error("Missing canonical field metric.amount");
    return { animation: false, title: { text: "Named amount " + row["metric.amount"] }, xAxis: { type: "category", data: ["amount"] }, yAxis: { type: "value" }, series: [{ type: "bar", data: [Number(row["metric.amount"])] }] };
  },
});`;

const textSource = `
export default defineChart({
  render({ queries }) {
    const rows = queries.text.rows;
    const labels = rows.map(row => row["dimension.label"]);
    return { animation: false, title: { text: labels[0] }, tooltip: { trigger: "axis", renderMode: "richText", formatter: () => labels.join(" | ") }, xAxis: { type: "category", data: labels }, yAxis: { type: "value" }, series: [{ type: "bar", data: [1, 2, 3, 4] }] };
  },
});`;

const localeSource = `
export default defineChart({
  render({ locale, variables }) {
    const currency = variables.currency;
    const timeZone = variables.timeZone;
    const value = "1234.50";
    const amount = new Intl.NumberFormat(locale, { style: "currency", currency, minimumFractionDigits: 2 }).format(value);
    const date = new Date("2026-09-06T16:00:00Z");
    const dateParts = new Intl.DateTimeFormat(locale, { timeZone, year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(date);
    const day = dateParts.filter(part => ["year", "month", "day"].includes(part.type)).map(part => part.value).join("/");
    return { animation: false, title: { text: amount, subtext: timeZone + " · " + day }, xAxis: { type: "category", data: ["amount"] }, yAxis: { type: "value" }, series: [{ type: "bar", data: [Number(value)] }] };
  },
});`;

const standardAndCustomSource = `
export default defineChart({
  render({ locale }) {
    const map = new Map([["north", 2]]);
    const set = new Set([1, 1, 2]);
    const big = BigInt("9007199254740993");
    const typed = new Uint8Array([1, 2, 3]);
    if (map.get("north") !== 2 || set.size !== 2 || big.toString() !== "9007199254740993" || typed[2] !== 3 || !/^north$/.test("north")) throw new Error("standard API calculation failed");
    const label = new Intl.NumberFormat(locale).format("1234.50");
    return {
      animation: false, title: { text: "APIs " + label }, xAxis: { min: 0, max: 10 }, yAxis: { min: 0, max: 10 },
      series: [{ type: "custom", data: [[5, 5]], renderItem(params, api) { const point = api.coord([api.value(0), api.value(1)]); return { type: "rect", shape: { x: point[0] - 30, y: point[1] - 20, width: 60, height: 40 }, style: { fill: "#2466c3" } }; }, label: { show: true, formatter: params => "value:" + params.value } }],
    };
  },
});`;

const invalidRootSource = `
export default defineChart({
  render({ variables }) {
    const mode = variables.mode;
    if (mode === "undefined") return undefined;
    if (mode === "null") return null;
    if (mode === "array") return [];
    if (mode === "string") return "bad";
    if (mode === "number") return 1;
    if (mode === "date") return new Date("2026-09-07");
    if (mode === "promise") return Promise.resolve({});
    return {};
  },
});`;

const authoringBoundarySource = `
export default defineChart({
  render({ variables }) {
    if (variables.mode === "async") return Promise.resolve({});
    return { animation: false, title: { text: "Runtime boundary" } };
  },
});`;

const renderLoopSource = `export default defineChart({ render() { while (true) {} } });`;
const microtaskLoopSource = `export default defineChart({ render() { Promise.resolve().then(() => { while (true) {} }); return {}; } });`;
const callbackLoopSource = `export default defineChart({ render() { return { xAxis: {}, yAxis: {}, series: [{ type: "bar", data: [1], label: { show: true, formatter() { while (true) {} return "1"; } } }] }; } });`;

const securitySource = `
export default defineChart({
  render() {
    const blocked = (label, operation) => { try { operation(); return label + ":opened"; } catch { return label + ":blocked"; } };
    const checks = [
      blocked("fetch", () => fetch("https://example.test")),
      blocked("storage", () => localStorage.getItem("x")),
      blocked("dom", () => document.createElement("div")),
      blocked("dynamic-import", () => { const candidate = globalThis["im" + "port"]; if (typeof candidate !== "function") throw new Error("dynamic loader unavailable"); candidate("https://example.test"); }),
      blocked("random", () => Math.random()),
      blocked("clock", () => Date.now()),
      blocked("constructor", () => Number.constructor("return globalThis")()),
    ];
    if (checks.some(value => value.endsWith(":opened"))) throw new Error(checks.join(", "));
    return { animation: false, title: { text: checks.join(" · ") }, xAxis: { type: "category", data: ["blocked"] }, yAxis: { type: "value" }, series: [{ type: "bar", data: [1] }] };
  },
});`;

const effectSource = `
export default defineChart({
  render({ variables }) {
    const mode = variables.mode;
    if (mode === "text") return { animation: false, title: { text: "https://example.test/plain-text", subtext: "URL remains text" }, xAxis: { type: "category", data: ["text"] }, yAxis: { type: "value" }, series: [{ type: "bar", data: [1] }] };
    if (mode === "external-image") return { graphic: [{ type: "image", style: { image: "https://example.test/image.png" } }] };
    if (mode === "external-symbol") return { xAxis: {}, yAxis: {}, series: [{ type: "scatter", data: [[1, 1]], symbol: "image://https://example.test/symbol.png" }] };
    if (mode === "callback-symbol") return { xAxis: {}, yAxis: {}, series: [{ type: "scatter", data: [[1, 1]], symbol: () => "image://https://example.test/callback-symbol.png" }] };
    if (mode === "title-link") return { title: { text: "text", link: "https://example.test" } };
    if (mode === "html-tooltip") return { tooltip: { renderMode: "html" } };
    return { toolbox: { feature: { saveAsImage: {} } } };
  },
});`;

const png = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==";
const jpeg = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD/4gHYSUNDX1BST0ZJTEUAAQEAAAHIAAAAAAQwAABtbnRyUkdCIFhZWiAH4AABAAEAAAAAAABhY3NwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAA9tYAAQAAAADTLQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAlkZXNjAAAA8AAAACRyWFlaAAABFAAAABRnWFlaAAABKAAAABRiWFlaAAABPAAAABR3dHB0AAABUAAAABRyVFJDAAABZAAAAChnVFJDAAABZAAAAChiVFJDAAABZAAAAChjcHJ0AAABjAAAADxtbHVjAAAAAAAAAAEAAAAMZW5VUwAAAAgAAAAcAHMAUgBHAEJYWVogAAAAAAAAb6IAADj1AAADkFhZWiAAAAAAAABimQAAt4UAABjaWFlaIAAAAAAAACSgAAAPhAAAts9YWVogAAAAAAAA9tYAAQAAAADTLXBhcmEAAAAAAAQAAAACZmYAAPKnAAANWQAAE9AAAApbAAAAAAAAAABtbHVjAAAAAAAAAAEAAAAMZW5VUwAAACAAAAAcAEcAbwBvAGcAbABlACAASQBuAGMALgAgADIAMAAxADb/2wBDAAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQH/2wBDAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQH/wAARCAABAAEDAREAAhEBAxEB/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/xAAUAQEAAAAAAAAAAAAAAAAAAAAJ/8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAwDAQACEQMRAD8AOc6At3//2Q==";
const webp = "data:image/webp;base64,UklGRhwCAABXRUJQVlA4WAoAAAAgAAAAAAAAAAAASUNDUMgBAAAAAAHIAAAAAAQwAABtbnRyUkdCIFhZWiAH4AABAAEAAAAAAABhY3NwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAA9tYAAQAAAADTLQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAlkZXNjAAAA8AAAACRyWFlaAAABFAAAABRnWFlaAAABKAAAABRiWFlaAAABPAAAABR3dHB0AAABUAAAABRyVFJDAAABZAAAAChnVFJDAAABZAAAAChiVFJDAAABZAAAAChjcHJ0AAABjAAAADxtbHVjAAAAAAAAAAEAAAAMZW5VUwAAAAgAAAAcAHMAUgBHAEJYWVogAAAAAAAAb6IAADj1AAADkFhZWiAAAAAAAABimQAAt4UAABjaWFlaIAAAAAAAACSgAAAPhAAAts9YWVogAAAAAAAA9tYAAQAAAADTLXBhcmEAAAAAAAQAAAACZmYAAPKnAAANWQAAE9AAAApbAAAAAAAAAABtbHVjAAAAAAAAAAEAAAAMZW5VUwAAACAAAAAcAEcAbwBvAGcAbABlACAASQBuAGMALgAgADIAMAAxADZWUDggLgAAANABAJ0BKgEAAQABQCYloAJ0ugH4AAOwAP7vvVf+oM+Wh8tD9hT/5glfkG65AAA=";
const rasterSource = `
export default defineChart({
  render({ variables }) {
    const source = variables.format === "png" ? ${JSON.stringify(png)} : variables.format === "jpeg" ? ${JSON.stringify(jpeg)} : variables.format === "webp" ? ${JSON.stringify(webp)} : variables.format === "svg" ? "data:image/svg+xml;base64,PHN2Zy8+" : "data:image/png;base64,not-a-real-png";
    return { animation: false, title: { text: "inline " + variables.format }, graphic: [{ type: "image", left: 40, top: 60, style: { image: source, width: 64, height: 64 } }] };
  },
});`;

const immutableSource = `
export default defineChart({
  render({ queries, variables }) {
    if (variables.mode === "sibling") {
      const row = queries.values.rows[0];
      const column = queries.values.columns[0];
      if (row["metric.amount"] !== "42.00" || column.name !== "metric.amount" || column.logicalType !== "DECIMAL" || column.nullable !== false) throw new Error("Sibling input changed");
      return { animation: false, title: { text: "Sibling " + row["metric.amount"] }, xAxis: { type: "category", data: ["amount"] }, yAxis: { type: "value" }, series: [{ type: "bar", data: [Number(row["metric.amount"])] }] };
    }
    const row = queries.values.rows[0];
    const results = [];
    const attempt = (name, action) => { try { action(); results.push(name + ":open"); } catch { results.push(name + ":blocked"); } };
    attempt("row", () => { row["metric.amount"] = "changed"; });
    attempt("column", () => { queries.values.columns[0].name = "changed"; });
    attempt("variables", () => { variables.mode = "changed"; });
    if (row["metric.amount"] !== "42.00" || variables.mode !== "ok") throw new Error("Chart frame input was mutated");
    return { animation: false, title: { text: results.join(" · ") + " · 42.00" }, xAxis: { type: "category", data: ["amount"] }, yAxis: { type: "value" }, series: [{ type: "bar", data: [42] }] };
  },
});`;

const optionBudgetSource = `
export default defineChart({
  render({ variables }) {
    if (variables.mode === "over") {
      const data = Array.from({ length: 249000 }, () => "12345678901234567890123456789012");
      return { xAxis: { type: "category", data: ["budget"] }, yAxis: { type: "value" }, series: [{ type: "line", data }] };
    }
    const data = Array.from({ length: 1000 }, () => "under");
    return { xAxis: { type: "category", data: ["budget"] }, yAxis: { type: "value" }, series: [{ type: "line", data }] };
  },
});`;

const callbackBudgetSource = `
export default defineChart({
  render({ variables }) {
    if (variables.mode === "output") return { xAxis: { type: "category", data: ["callback"] }, yAxis: { type: "value" }, series: [{ type: "bar", data: [1], label: { show: true, formatter: () => "x".repeat(524286) } }] };
    if (variables.mode === "input") { const payload = "x".repeat(200000); return { xAxis: { min: 0, max: 2 }, yAxis: { min: 0, max: 2 }, series: [{ type: "custom", encode: { x: 0, y: 1 }, data: [[1, 1, payload]], renderItem(_params, api) { api.value(2); api.value(2); api.value(2); return { type: "rect", shape: { x: 0, y: 0, width: 1, height: 1 } }; } }] }; }
    if (variables.mode === "depth") { const payload = []; let current = payload; for (let index = 0; index < 24; index += 1) { current.push([]); current = current[0]; } const item = { value: 1, payload }; return { xAxis: { type: "category", data: ["callback"] }, yAxis: { type: "value" }, series: [{ type: "bar", data: [item], label: { show: true, formatter(params) { return "depth:" + params.data.payload.length; } } }] }; }
    return { xAxis: { type: "category", data: ["callback"] }, yAxis: { type: "value" }, series: [{ type: "line", data: [0] }] };
  },
});`;

const resizeSource = `
export default defineChart({
  render({ viewport }) { return { animation: false, title: { text: "viewport " + viewport.width + "×" + viewport.height }, xAxis: { type: "category", data: ["a", "b", "c"] }, yAxis: { type: "value" }, series: [{ type: "line", data: [1, 2, 3] }] }; },
});`;

const accessibilitySource = `
export default defineChart({
  render({ variables }) {
    if (variables.mode === "error") throw new Error("Readable fixture error: choose Recover");
    return { animation: false, title: { text: "Keyboard-ready chart" }, xAxis: { type: "category", data: ["a", "b", "c"] }, yAxis: { type: "value" }, series: [{ type: "bar", data: [1, 2, 3] }] };
  },
});`;

const input = (moduleSource: string, queries: Record<string, ReturnType<typeof result>>, variables: Record<string, unknown> = {}, locale = "en-US"): ChartWidgetInputs => ({
  ...base,
  moduleSource,
  queries,
  variables: Object.fromEntries(Object.entries(variables).map(([name, value]) => [name, { value, type: typeof value === "boolean" ? "BOOLEAN" : "TEXT", nullable: value === null }])),
  locale,
});

const standardSales = result("expression-sales", salesColumns, salesRows);

const scenarios: ExpressionScenario[] = [
  {
    id: "A01", subcase: "kpi-trend", description: "KPI total, previous value, growth, and three-day trend",
    expectedText: ["$74,520.00", "Previous $65,000.00 · +14.6% · Trend 20,000→24,000→30,520"],
    inputs: input(kpiSource, { current: standardSales, previous: result("expression-previous", [{ name: "metric.amount", logicalType: "DECIMAL", nullable: false }], [["65000.00"]]) }),
  },
  {
    id: "A02", subcase: "decimal-exact", description: "DECIMAL remains a string for exact labels",
    expectedText: ["9007199254740993.27", "$9,007,199,254,740,993.27 · DECIMAL string preserved"],
    inputs: input(decimalSource, { decimal: result("expression-decimal", [{ name: "metric.amount", logicalType: "DECIMAL", nullable: false }], [["9007199254740993.27"]]) }),
  },
  {
    id: "A03", subcase: "zero-negative-null", description: "Zero, negative, and null use separate labels",
    expectedText: ["$0.00 | -$1,850.25 | —"],
    inputs: input(signedSource, { amounts: result("expression-signed", [{ name: "metric.amount", logicalType: "DECIMAL", nullable: true }], [["0.00"], ["-1850.25"], [null]]) }),
  },
  {
    id: "A04", subcase: "comparison-zero", description: "Zero comparison renders unavailable instead of Infinity",
    expectedText: ["$100.00 · —", "comparison unavailable"],
    inputs: input(comparisonSource, { current: result("expression-current-zero", [{ name: "metric.amount", logicalType: "DECIMAL", nullable: false }], [["100.00"]]), previous: result("expression-previous-zero", [{ name: "metric.amount", logicalType: "DECIMAL", nullable: false }], [["0.00"]]) }),
  },
  {
    id: "A04", subcase: "comparison-null", description: "Null comparison renders unavailable instead of NaN",
    expectedText: ["$100.00 · —", "comparison unavailable"],
    inputs: input(comparisonSource, { current: result("expression-current-null", [{ name: "metric.amount", logicalType: "DECIMAL", nullable: false }], [["100.00"]]), previous: result("expression-previous-null", [{ name: "metric.amount", logicalType: "DECIMAL", nullable: true }], [[null]]) }),
  },
  {
    id: "A05", subcase: "empty-success", description: "Zero-row success replaces the last total with an empty state",
    expectedText: ["No data", "Run SUCCEEDED · row count 0"],
    inputs: input(emptySource, { sales: standardSales }),
  },
  {
    id: "A06", subcase: "reordered-columns", description: "Named canonical fields survive a reordered descriptor",
    expectedText: ["Named amount 74520.00"],
    inputs: input(reorderedSource, { sales: result("expression-reordered", [{ name: "metric.amount", logicalType: "DECIMAL", nullable: false }, { name: "dimension.date", logicalType: "DATE", nullable: false }], [["74520.00", "2026-09-03"]]) }),
  },
  {
    id: "A06", subcase: "missing-field", description: "Missing canonical fields fail explicitly",
    expectedText: ["Missing canonical field metric.amount"],
    inputs: input(reorderedSource, { sales: result("expression-missing", [{ name: "dimension.date", logicalType: "DATE", nullable: false }], [["2026-09-03"]]) }),
  },
  {
    id: "A07", subcase: "text-url", description: "Unicode, long names, and URLs remain text",
    expectedText: ["대한민국 🇰🇷", "https://example.test/path?query=1"],
    inputs: input(textSource, { text: result("expression-text", [{ name: "dimension.label", logicalType: "TEXT", nullable: false }], [["대한민국 🇰🇷"], ["A very long customer name — " + "고객".repeat(20)], ["https://example.test/path?query=1"], ["emoji 📊"]]) }),
  },
  {
    id: "A08", subcase: "ko-kr-usd-seoul", description: "ko-KR USD and Asia/Seoul timezone",
    expectedText: ["US$1,234.50", "Asia/Seoul · 2026/09/07"],
    inputs: input(localeSource, {}, { currency: "USD", timeZone: "Asia/Seoul" }, "ko-KR"),
  },
  {
    id: "A08", subcase: "en-us-usd-utc", description: "en-US USD and UTC timezone",
    expectedText: ["$1,234.50", "UTC · 09/06/2026"],
    inputs: input(localeSource, {}, { currency: "USD", timeZone: "UTC" }, "en-US"),
  },
  {
    id: "A08", subcase: "de-de-krw-seoul", description: "de-DE KRW and Asia/Seoul timezone",
    expectedText: ["1.234,50 ₩", "Asia/Seoul · 07/09/2026"],
    inputs: input(localeSource, {}, { currency: "KRW", timeZone: "Asia/Seoul" }, "de-DE"),
  },
  {
    id: "F01", subcase: "standard-apis-custom", description: "Standard APIs and synchronous custom renderItem",
    inputs: input(standardAndCustomSource, {}),
  },
  {
    id: "F02", subcase: "invalid-render-roots", description: "Invalid roots preserve the previous canvas and empty object recovers",
    inputs: input(invalidRootSource, {}, { mode: "success" }),
  },
  {
    id: "F03", subcase: "runtime-async", description: "Runtime rejects async source independently from authoring diagnostics",
    inputs: input(authoringBoundarySource, {}, { mode: "ok" }),
  },
  {
    id: "F04", subcase: "render-loop", description: "Synchronous infinite render is bounded by the existing deadline",
    inputs: input(renderLoopSource, {}),
  },
  {
    id: "F04", subcase: "microtask-loop", description: "Detached microtask loop cannot cancel the deadline",
    inputs: input(microtaskLoopSource, {}),
  },
  {
    id: "F04", subcase: "callback-loop", description: "Late callback loop is bounded by the callback deadline",
    inputs: input(callbackLoopSource, {}),
  },
  {
    id: "F05", subcase: "ambient-authority", description: "Network, DOM, storage, import, time, random, and constructor escapes are blocked",
    expectedText: ["fetch:blocked · storage:blocked · dom:blocked · dynamic-import:blocked · random:blocked · clock:blocked · constructor:blocked"],
    inputs: input(securitySource, {}),
  },
  {
    id: "F06", subcase: "text-and-effects", description: "Text URL is allowed while effect paths are rejected",
    expectedText: ["https://example.test/plain-text"],
    inputs: input(effectSource, {}, { mode: "text" }),
  },
  {
    id: "F07", subcase: "inline-raster-formats", description: "PNG, JPEG, and WebP inline data are offered by a native Worker",
    inputs: input(rasterSource, {}, { format: "png" }),
  },
  {
    id: "F08", subcase: "input-immutable", description: "Rows, descriptors, and variables stay immutable across rerenders",
    expectedText: ["row:blocked · column:blocked · variables:blocked · 42.00"],
    inputs: input(immutableSource, { values: result("expression-immutable", [{ name: "metric.amount", logicalType: "DECIMAL", nullable: false }], [["42.00"]]) }, { mode: "ok" }),
  },
  {
    id: "G01", subcase: "option-budget", description: "Option byte overflow is rejected by the Worker validator",
    inputs: input(optionBudgetSource, {}, { mode: "under" }),
  },
  {
    id: "G02", subcase: "callback-budgets", description: "Callback input and output budgets are checked independently",
    inputs: input(callbackBudgetSource, {}, { mode: "base" }),
  },
  {
    id: "G04", subcase: "mobile-resize", description: "Cold mobile render and explicit resize keep the chart readable",
    inputs: input(resizeSource, {}),
  },
  {
    id: "G05", subcase: "keyboard-error-recovery", description: "Named controls and readable error recovery remain usable",
    inputs: input(accessibilitySource, {}, { mode: "ok" }),
  },
];

export const expressionScenarios: Readonly<Record<string, ExpressionScenario>> = Object.fromEntries(
  scenarios.map(scenario => [`${scenario.id}-${scenario.subcase}`, scenario]),
);

export const expressionScenarioKeys = Object.keys(expressionScenarios);

export function expressionVariableValues(inputs: ChartWidgetInputs): Record<string, unknown> {
  return Object.fromEntries(Object.entries(inputs.variables).map(([name, definition]) => [name, definition.value]));
}
