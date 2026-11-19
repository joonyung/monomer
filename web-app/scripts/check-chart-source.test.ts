import { describe, expect, it } from "vitest";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import type { DashboardDefinition } from "../src/api/dashboard";
import { chartWidgetFixtureInputs } from "../src/pages/dev/chart-widget-runtime-fixture/fixture";
import { checkChartSource } from "./check-chart-source.mjs";

function check(source: string) {
  return checkChartSource({
    definition: {
      parameters: { amount: { type: "NUMBER", nullable: false }, hidden: { type: "TEXT", nullable: false } },
      variables: { selected: { type: "TEXT", nullable: true, initial: null } },
      queries: { totals: {} },
      widgets: { chart: { type: "CHART", queryNames: ["totals"], module: {
        inputs: { parameters: ["amount"], variables: ["selected"] }, source,
      } } },
    } as unknown as DashboardDefinition,
    widgetId: "chart",
    queryColumns: { totals: [{ name: "metric.total", logicalType: "DECIMAL", nullable: false }] },
    sourceName: "authored.ts",
  });
}

describe("Chart source authoring TypeScript diagnostics", () => {
  it("checks the existing event/formatter runtime authored example against its real input descriptors", () => {
    expect(checkChartSource({
      definition: {
        parameters: {}, variables: { selectedRegion: { type: "TEXT", nullable: true, initial: null } },
        queries: { ordersByRegion: {} },
        widgets: { chart: { type: "CHART", queryNames: ["ordersByRegion"], module: {
          inputs: { parameters: [], variables: ["selectedRegion"] }, source: chartWidgetFixtureInputs.moduleSource,
        } } },
      } as unknown as DashboardDefinition,
      widgetId: "chart",
      queryColumns: { ordersByRegion: chartWidgetFixtureInputs.queries.ordersByRegion.columns },
    })).toEqual([]);
  });

  it("returns a failing CLI exit code with the source line for a type error", () => {
    const directory = mkdtempSync(path.join(tmpdir(), "chart-authoring-"));
    const definitionPath = path.join(directory, "dashboard.json");
    try {
      const run = (source: string) => {
        writeFileSync(definitionPath, JSON.stringify({
          parameters: {}, variables: {}, queries: {}, widgets: {
            chart: { type: "CHART", queryNames: [], module: { inputs: { parameters: [], variables: [] }, source } },
          },
        }));
        return spawnSync(process.execPath, [path.join(import.meta.dirname, "check-chart-source.mjs"), definitionPath, "chart"], { encoding: "utf8" });
      };
      expect(run("export default defineChart({ render() { return {}; } });").status).toBe(0);
      const invalid = run("export default defineChart({ render() {\nconst value: number = 'bad';\nreturn {}; } });");
      expect(invalid.status).toBe(1);
      expect(invalid.stderr).toContain("#widgets.chart.module.source:2:7 TS2322");
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
    // Two cold TypeScript CLI processes took 8.1s in the loaded suite.
  }, 15_000);

  it("checks the actual ABI, ECharts callbacks, declared inputs, and exact decimal Intl formatting", () => {
    expect(check(`export default defineChart({
  setup({ chart }) {
    chart.on('click', (_event, frame) => { frame.setVariables({ selected: null }); });
  },
  render({ queries, parameters, variables, locale, viewport }) {
    const decimal: string = queries.totals.rows[0]['metric.total'];
    const title = new Intl.NumberFormat(locale).format(decimal);
    return {
      title: { text: title + new Intl.NumberFormat(locale).format(parameters.amount) },
      xAxis: { type: 'category', data: [variables.selected ?? 'all'] },
      yAxis: { type: 'value' },
      series: [{ type: 'bar', data: [viewport.width], label: { formatter: ({ value }) => String(value) } }],
    };
  },
});`)).toEqual([]);
  });

  it("reports genuine semantic errors on the original source line", () => {
    const diagnostics = check(`export default defineChart({
  render({ parameters }) {
    const amount: number = parameters.amount;
    return { title: { text: String(amount) } };
  },
});`);
    expect(diagnostics).toEqual([expect.objectContaining({ sourceName: "authored.ts", line: 3, column: 11, code: "TS2322" })]);
  });

  it("rejects undeclared names, unknown result columns, wrong variable writes, and readonly mutation", () => {
    const diagnostics = check(`export default defineChart({
  setup({ chart }) { chart.on('click', (_event, frame) => {
    frame.setVariables({ selected: 1 });
    frame.setVariables({ hidden: 'x' });
  }); },
  render({ queries, parameters, variables }) {
    parameters.amount = '1';
    parameters.hidden;
    queries.missing;
    queries.totals.rows[0]['missing'];
    variables.selected.toUpperCase();
    return {};
  },
});`);
    expect(diagnostics.map(({ line }) => line)).toEqual([3, 4, 7, 8, 9, 10, 11]);
  });

  it.each([
    ["series type", "{ series: [{ type: 'not-a-series' }] }"],
    ["title text", "{ title: { text: 123 } }"],
    ["callback return", "{ series: [{ type: 'bar', label: { formatter: () => 123 } }] }"],
  ])("uses ECharts types to diagnose %s errors", (_name, option) => {
    expect(check(`export default defineChart({ render() { return ${option}; } });`).some(({ code }) => code.startsWith("TS"))).toBe(true);
  });

  it("does not offer DOM, network, or timer globals and diagnoses async setup", () => {
    const diagnostics = check(`export default defineChart({
  async setup() {},
  render() {
    document.createElement('div');
    fetch('https://example.com');
    setTimeout(() => {}, 1);
    return {};
  },
});`);
    expect(diagnostics.map(({ line }) => line)).toEqual([2, 4, 5, 6]);
    expect(diagnostics[0].code).toBe("CHART_ABI");
  });

  it("reports syntax errors and prohibited module imports without executing source", () => {
    expect(check("export default defineChart({ render( });").some(({ code }) => code.startsWith("TS"))).toBe(true);
    expect(check("import type { EChartsOption } from 'echarts'; export default defineChart({ render() { return {}; } });")
      .some(({ code }) => code === "CHART_ABI")).toBe(true);
  });

  it("diagnoses standard ambient time/random APIs including typed aliases, with source locations", () => {
    const diagnostics = check(`export default defineChart({ render() {
  Date.now();
  Math.random();
  new Date();
  Date();
  const clock = Date.now; clock();
  const random = Math.random; random();
  const DateAlias = Date; new DateAlias();
  return {};
} });`);
    expect(diagnostics.map(({ code, line }) => [code, line])).toEqual([2, 3, 4, 5, 6, 7, 8].map((line) => ["CHART_ABI", line]));
  });

  // Each check builds a TypeScript program. Give each constructor its own test budget.
  it.each(["NumberFormat", "DateTimeFormat", "Collator", "PluralRules", "ListFormat", "RelativeTimeFormat", "Segmenter", "DisplayNames", "Locale"])("requires explicit locale on Intl.%s", (name) => {
    const diagnostics = check(`export default defineChart({ render() { new Intl.${name}(undefined); return {}; } });`);
    expect(diagnostics.some(({ code, message }) => code === "CHART_ABI" && message === `Intl.${name} requires an explicit locale.`), name).toBe(true);
  });

  it("requires explicit locale on an aliased Intl constructor", () => {
    expect(check(`const Format = Intl.NumberFormat; export default defineChart({ render() { new Format(); return {}; } });`)
      .some(({ message }) => message.includes("explicit locale"))).toBe(true);
  });

  it("diagnoses numeric toLocaleString calls and typed aliases at their source locations", () => {
    const diagnostics = check(`export default defineChart({ render() {
  (157724.09999999998).toLocaleString('en-US', { maximumFractionDigits: 2 });
  Number('21370.960000000').toLocaleString();
  const amount: Number = 12; amount.toLocaleString('en-US');
  const format = Number.prototype.toLocaleString; format();
  return {};
} });`);
    expect(diagnostics).toEqual([2, 3, 4, 5].map((line) => expect.objectContaining({
      sourceName: "authored.ts", line, code: "CHART_ABI",
      message: "Use Intl.NumberFormat with an explicit locale; toLocaleString options are ignored in the chart runtime.",
    })));
  });

  it("does not diagnose custom toLocaleString methods or explicit Intl formatting", () => {
    expect(check(`const amount = { toLocaleString: () => 'custom' };
export default defineChart({ render({ locale }) {
  amount.toLocaleString();
  return { title: { text: new Intl.NumberFormat(locale, { maximumFractionDigits: 2 }).format('157724.100000000') } };
} });`)).toEqual([]);
  });

  it("diagnoses DateTimeFormat missing timeZone and implicit current-date formatting", () => {
    const diagnostics = check(`export default defineChart({ render({ locale }) {
  new Intl.DateTimeFormat(locale);
  new Intl.DateTimeFormat(locale, { timeZone: undefined });
  const date = new Intl.DateTimeFormat(locale, { timeZone: 'UTC' });
  date.format();
  date.format(undefined);
  date.formatToParts();
  date.formatToParts(undefined);
  return { title: { text: date.format(new Date('2026-01-01T00:00:00Z')) } };
} });`);
    expect(diagnostics.map(({ code, line }) => [code, line])).toEqual([2, 3, 5, 6, 7, 8].map((line) => ["CHART_ABI", line]));
  });

  it("does not mistake shadowed Date, Math, Intl, or formatting methods for standard globals", () => {
    expect(check(`const Date = Object.assign(class { }, { now: () => 1 });
const Math = { random: () => 1 };
const Intl = { NumberFormat: class {}, DateTimeFormat: class { format() { return 'fixed'; } formatToParts() { return []; } } };
export default defineChart({ render() {
  Date.now(); new Date(); Math.random(); new Intl.NumberFormat();
  const formatter = new Intl.DateTimeFormat(); formatter.formatToParts();
  return { title: { text: formatter.format() } };
} });`)).toEqual([]);
  });
});
