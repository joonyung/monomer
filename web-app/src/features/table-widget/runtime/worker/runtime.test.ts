import "ses";
import { beforeAll, describe, expect, it, vi } from "vitest";
import type { DashboardResultColumn } from "@/api/dashboard";
import { validateDashboardResult, type NamedDashboardResult } from "@/features/dashboard/dashboard-result";
import type { TableFrame } from "../../contract/abi";
import { pivotLayout, pivotSourceRequests, pivotValueFractionDigits, type PivotTotalsResult, type TablePivot } from "../../pivot/pivot-layout";
import { numericColumnFractionDigits, tableCellFormatter } from "../../table-values";
import { prepareTablePresentation, renderTableBatch, startTableWidgetWorkerRuntime } from "./runtime";
import { evaluateTableSource, lowerTableSource } from "./module-source";
import { TABLE_BATCH_CELLS, TABLE_SOURCE_BYTES } from "../limits";
import type { TableWorkerRequest, TableWorkerResponse } from "../worker-protocol";

const frame: TableFrame = {
  rowQueryName: "main", locale: "ko-KR", parameters: {}, variables: { limit: "20" },
  queries: {
    main: {
      columns: [{ name: "amount", logicalType: "DECIMAL", nullable: false, numberFormat: null }],
      rows: [{ amount: "9007199254740993.12" }, { amount: "4.2" }],
    },
    auxiliary: {
      columns: [{ name: "label", logicalType: "STRING", nullable: false, numberFormat: null }],
      rows: [{ label: "한글" }],
    },
  },
};
const digits = new Map([["amount", 2]]);
const prepare = (source: string, target: TableFrame, fractionDigits: ReadonlyMap<string, number> = digits) => prepareTablePresentation(source, target, fractionDigits);
beforeAll(() => lockdown({ errorTaming: "unsafe-debug" }));
const request = { rowIndices: [1, 0], fields: ["amount"] };

describe("Table Worker preparation and cell batches", () => {
  it("prepares once, retains callbacks, preserves raw addresses and exact scalar values", () => {
    const source = `let preparations = 0; export default defineTable({ render(frame) {
      preparations++;
      const label = frame.queries.auxiliary.rows[0].label;
      return { cell({value, rowIndex}) { return { text: label + ':' + preparations + ':' + rowIndex + ':' + value }; } };
    } });`;
    const presentation = prepare(source, frame);
    const first = renderTableBatch(presentation, request);
    const second = renderTableBatch(presentation, request);
    expect(first).toEqual(second);
    expect(first.cells.map(cell => cell.view)).toEqual([{ text: "한글:1:1:4.2" }, { text: "한글:1:0:9007199254740993.12" }]);
  });
  it("isolates a failing row and cell while preserving successful outputs", () => {
    const presentation = prepare(`export default defineTable({ render() { return {
      row({rowIndex}) { if (rowIndex === 0) throw Error('row failed'); return { background: '#112233' }; },
      cell({rowIndex}) { if (rowIndex === 1) throw Error('cell failed'); return {text: ''}; }
    }; }});`, frame);
    const batch = renderTableBatch(presentation, request);
    expect(batch.rows[0].view).toEqual({ background: "#112233" });
    expect(batch.rows[1]).toEqual({ rowIndex: 0, error: "row failed" });
    expect(batch.cells[0]).toEqual({ rowIndex: 1, field: "amount", error: "cell failed" });
    expect(batch.cells[1].view).toEqual({ text: "" });
  });
  it("defaults undefined presentation callbacks and values", () => {
    const presentation = prepare("export default defineTable({render() {return {};}});", frame);
    const batch = renderTableBatch(presentation, request);
    expect(batch.cells.every(cell => cell.view === undefined && cell.error === undefined)).toBe(true);
  });
  it("freezes frame and callback arguments", () => {
    const presentation = prepare(`export default defineTable({render(frame) {
      if (!Object.isFrozen(frame.queries.main.rows[0])) throw Error('mutable frame');
      return {cell(input) { input.row.amount = 'changed'; return {text:'wrong'}; }};
    }});`, frame);
    expect(renderTableBatch(presentation, request).cells[0].error).toBeDefined();
    expect(frame.queries.main.rows[0].amount).toBe("9007199254740993.12");
  });
  it.each([
    "fetch('https://example.com')", "Date.now()", "new Date()", "Date()", "Math.random()", "document.body", "setTimeout(() => {}, 1)",
    "new WebSocket('wss://example.com')", "navigator.language", "globalThis.x = 1",
    "new Intl.DateTimeFormat(frame.locale)", "new Intl.DateTimeFormat(frame.locale, {timeZone:'UTC'}).format()",
  ])("rejects ambient access: %s", body => {
    expect(() => prepare(`export default defineTable({render(frame){ ${body}; return {}; }});`, frame)).toThrow();
  });
  it("formats with Intl in the frame locale, explicit Dates and SES Math", () => {
    const prepared = prepare(`export default defineTable({ render(frame) {
      const percent = new Intl.NumberFormat(frame.locale, { style: "percent", maximumFractionDigits: 1 });
      const day = new Intl.DateTimeFormat(frame.locale, { timeZone: "UTC", month: "short", day: "numeric" });
      const release = day.format(new Date("2026-09-07T00:00:00Z")) + " " + new Date(Date.UTC(2026, 8, 7)).getUTCFullYear();
      return { cell({ value }) { return { text: percent.format(Number(value) / 100) + " · " + release + " · " + Math.max(1, 2) }; } };
    } });`, { ...frame, locale: "en-US" });
    expect(renderTableBatch(prepared, { rowIndices: [1], fields: ["amount"] }).cells[0].view).toEqual({ text: "4.2% · Sep 7 2026 · 2" });
  });
  it.each([
    "export default {render(){return {};}};",
    "const m = defineTable({render(){return {};}}); export default {render: m.render};",
    "export default defineTable({render(){return Promise.resolve({});}});",
    "export default defineTable({render(){return {cell: 1};}});",
    "export default defineTable({get render(){throw Error('getter invoked');}});",
  ])("rejects invalid module or render output", source => {
    expect(() => prepare(source, frame)).toThrow();
  });
  it("rejects source bytes and module imports before evaluation", () => {
    expect(() => lowerTableSource(" ".repeat(TABLE_SOURCE_BYTES + 1))).toThrow(/budget/);
    expect(() => lowerTableSource("import value from 'x'; export default value;")).toThrow(/import/);
    expect(() => lowerTableSource("export const other = 1;")).toThrow(/export default/);
    expect(() => evaluateTableSource("export default defineTable({render(){return {};},setVariables(){}});")).toThrow();
  });
  it("rejects oversized outputs before crossing the Worker boundary", () => {
    const source = `export default defineTable({render(){return {cell(){return {text:'x'.repeat(4096),tooltip:'y'.repeat(8192)};}};}});`;
    const largeFrame = { ...frame, queries: { ...frame.queries, main: { ...frame.queries.main, rows: Array.from({ length: 100 }, () => ({ amount: "1" })) } } };
    expect(() => renderTableBatch(prepare(source, largeFrame), { rowIndices: Array.from({ length: 100 }, (_, index) => index), fields: ["amount"] })).toThrow(/output byte budget/);
    expect(() => renderTableBatch(prepare(source, frame), { rowIndices: [0], fields: Array(2049).fill("amount") })).toThrow(/cell budget/);
  });
  it.each(["{text:'x'.repeat(4097)}", "{text:'x',tooltip:'x'.repeat(8193)}", "{text:'x',graphic:{svg:'x'.repeat(32769)}}", "{text:'x',color:'url(https://evil.test)'}", "{get text(){throw Error('executed')}}", "{text:'x',onClick(){}}", "Promise.resolve({text:'x'})"])("rejects bad cell output without aborting the whole table", output => {
    const presentation = prepare(`export default defineTable({render(){return {cell(){return ${output};}};}});`, frame);
    expect(renderTableBatch(presentation, request).cells.every(cell => cell.view === undefined && cell.error)).toBe(true);
  });
  it("rejects invalid row and field addresses", () => {
    const presentation = prepare("export default defineTable({render(){return {};}});", frame);
    expect(() => renderTableBatch(presentation, { rowIndices: [2], fields: ["amount"] })).toThrow(/row/);
    expect(() => renderTableBatch(presentation, { rowIndices: [0], fields: ["missing"] })).toThrow(/field/);
  });
});

function result(queryType: "SEMANTIC" | "SQL", columns: DashboardResultColumn[], rows: (string | null)[][]): NamedDashboardResult {
  return validateDashboardResult({ queryType, reportingTimeZone: null, queryRunId: `${queryType}-run`, columns, rows });
}
function tableFrame(primary: NamedDashboardResult): TableFrame {
  return { rowQueryName: "main", locale: "en-US", parameters: {}, variables: {}, queries: { main: { columns: primary.columns, rows: primary.rows } } };
}
const echoDisplayText = "export default defineTable({render(){return {cell({displayText}){return {text:'<'+displayText+'>'};}};}});";
function displayTexts(primary: NamedDashboardResult, fractionDigits: ReadonlyMap<string, number>, requests: readonly { rowIndices: readonly number[]; fields: readonly string[] }[]) {
  const prepared = prepare(echoDisplayText, tableFrame(primary), fractionDigits);
  const hostText = tableCellFormatter(primary.columns, "en-US", fractionDigits);
  return requests.flatMap(request => renderTableBatch(prepared, request).cells.map(cell => {
    const view = cell.view;
    if (!view || !("text" in view)) throw new Error("Expected authored text");
    expect(view.text).toBe(`<${hostText(cell.field, primary.rows[cell.rowIndex][cell.field])}>`);
    return view.text.slice(1, -1);
  }));
}

describe("Table cell displayText", () => {
  it("equals the Host default text for metric formats, exact column digits, raw values, null and empty text", () => {
    const columns: DashboardResultColumn[] = [
      { name: "metric.amount", logicalType: "DECIMAL", nullable: true, display: { style: "NUMBER", decimals: 1, unit: "day" } },
      { name: "metric.revenue", logicalType: "DECIMAL", nullable: true, display: { style: "CURRENCY", currency: "USD", decimals: 2 } },
      { name: "metric.share", logicalType: "DECIMAL", nullable: true, display: { style: "PERCENT", decimals: 1 } },
      { name: "metric.plain", logicalType: "DECIMAL", nullable: true, display: null },
      { name: "metric.count", logicalType: "INTEGER", nullable: true, display: null },
      { name: "dimension.name", logicalType: "TEXT", nullable: true, display: null },
      { name: "metric_time.second", logicalType: "TIMESTAMP", nullable: true, display: null },
    ];
    const primary = result("SEMANTIC", columns, [
      ["1234.56", "1234.5", "0.0004", "10.5", "9007199254740993", "Acme", "2026-09-07T00:00:00.000000Z"],
      [null, "-0.000000000000000000001", "0.125", "3.12500", null, "", null],
    ]);
    const fields = columns.map(column => column.name);
    expect(displayTexts(primary, numericColumnFractionDigits(primary), [{ rowIndices: [0, 1], fields }])).toEqual([
      "1,234.6 days", "$1,234.50", "0.04%", "10.500", "9,007,199,254,740,993", "Acme", "2026-09-07T00:00:00.000000Z",
      "null", "-$0.000000000000000000001", "12.5%", "3.125", "null", "(empty string)", "null",
    ]);
  });

  it("uses column digits for SQL results, whose columns carry no metric format", () => {
    const primary = result("SQL", [{ name: "revenue", logicalType: "DECIMAL", nullable: false, display: { style: "CURRENCY", currency: "USD", decimals: 0 } }], [["1234.5"], ["2.25"]]);
    expect(primary.columns[0].numberFormat).toBeNull();
    expect(displayTexts(primary, numericColumnFractionDigits(primary), [{ rowIndices: [0, 1], fields: ["revenue"] }])).toEqual(["1,234.50", "2.25"]);
  });

  it("gives pivot value cells the digits shared with their totals and row headers the row field text", () => {
    const columns: DashboardResultColumn[] = [
      { name: "dimension.stores.number", logicalType: "INTEGER", nullable: false, display: null },
      { name: "metric_time.month", logicalType: "DATE", nullable: false, display: null },
      { name: "metric.cost", logicalType: "DECIMAL", nullable: true, display: null },
    ];
    const primary = result("SEMANTIC", columns, [["1200", "2026-07-01", "1.20"], ["1200", "2026-08-01", "2.5"]]);
    const totals: PivotTotalsResult[] = [{ queryName: "byStore", result: result("SEMANTIC", [columns[0], columns[2]], [["1200", "3.1415"]]) }];
    const pivot: TablePivot = { rows: [{ field: columns[0].name }], columns: [{ field: columns[1].name }], values: [{ field: columns[2].name }], valuesAxis: "COLUMNS" };
    const pivotText = (totalResults: PivotTotalsResult[]) => {
      const layout = pivotLayout(pivot, primary, totalResults);
      if (layout.kind !== "GRID") throw new Error(layout.message);
      const fractionDigits = new Map([...numericColumnFractionDigits(primary), ...pivotValueFractionDigits(layout.grid)]);
      return displayTexts(primary, fractionDigits, pivotSourceRequests(layout.grid, layout.grid.bodyRows));
    };
    expect(pivotText(totals)).toEqual(["1.2000", "2.5000", "1,200"]);
    expect(pivotText([])).toEqual(["1.20", "2.50", "1,200"]);
  });
});

describe("Host text marker", () => {
  const note = "n".repeat(5000);
  const notes = result("SEMANTIC", [
    { name: "dimension.note", logicalType: "TEXT", nullable: false, display: null },
    { name: "dimension.label", logicalType: "TEXT", nullable: false, display: null },
  ], Array.from({ length: 200 }, () => [note, "short"]));

  it("sends text equal to displayText as the marker outside the text limit and the batch output budget", () => {
    const prepared = prepare(`export default defineTable({render(){return {cell({field, displayText}){
      return field === "dimension.note" ? {text: displayText, background: "#112233"} : {text: "x".repeat(4097)};
    }};}});`, tableFrame(notes), new Map());
    const batch = renderTableBatch(prepared, { rowIndices: Array.from({ length: 200 }, (_, index) => index), fields: ["dimension.note", "dimension.label"] });
    expect(batch.cells.filter(cell => cell.field === "dimension.note").every(cell => JSON.stringify(cell.view) === '{"background":"#112233","hostText":true}')).toBe(true);
    expect(batch.cells.filter(cell => cell.field === "dimension.label").every(cell => cell.view === undefined && cell.error === "Invalid Table cell text")).toBe(true);
  });

  it("keeps source-authored text that differs from displayText", () => {
    const prepared = prepare("export default defineTable({render(){return {cell({displayText}){return {text: displayText + ' ▲', align: 'end'};}};}});", tableFrame(notes), new Map());
    expect(renderTableBatch(prepared, { rowIndices: [0], fields: ["dimension.label"] }).cells[0].view).toEqual({ text: "short ▲", align: "end" });
  });
});

describe("Table worst-case formatting batch", () => {
  it("renders a full batch of zero-expansion metric cells with a style-only source", () => {
    const zero = result("SEMANTIC", [{ name: "metric.revenue", logicalType: "DECIMAL", nullable: false, display: { style: "CURRENCY", currency: "USD", decimals: 2 } }],
      Array.from({ length: TABLE_BATCH_CELLS }, () => ["0.000000000000000000001"]));
    const prepared = prepare(`export default defineTable({render(){return {cell({rowIndex, displayText}){
      return rowIndex === 0 ? {text: displayText + " ·"} : {text: displayText, background: "#FFF4ED"};
    }};}});`, tableFrame(zero), new Map());
    const batch = renderTableBatch(prepared, { rowIndices: Array.from({ length: TABLE_BATCH_CELLS }, (_, index) => index), fields: ["metric.revenue"] });
    expect(batch.cells[0].view).toEqual({ text: "$0.000000000000000000001 ·" });
    expect(batch.cells.slice(1).every(cell => cell.error === undefined && cell.view && "hostText" in cell.view && cell.view.background === "#FFF4ED")).toBe(true);
  });
});

describe("Table Worker success checkpoints", () => {
  it("keeps prepare and batch success pending until detached Promise jobs drain", async () => {
    const responses: TableWorkerResponse[] = [];
    const scope = {
      postMessage: (response: TableWorkerResponse) => responses.push(response),
      onmessage: null as ((event: { data: TableWorkerRequest }) => void) | null,
    };
    vi.stubGlobal("self", scope);
    try {
      startTableWidgetWorkerRuntime();
      scope.onmessage?.({ data: {
        type: "prepare", generation: 3, operationId: 5, frame, displayFractionDigits: digits,
        moduleSource: `let state = 'pending'; export default defineTable({render() {
          Promise.resolve().then(() => { state = 'prepared'; });
          return {cell() {
            Promise.resolve().then(() => { state = 'after cell'; });
            return {text:state};
          }};
        }});`,
      } });
      expect(responses).toEqual([]);
      await Promise.resolve();
      expect(responses).toEqual([]);
      await new Promise(resolve => setTimeout(resolve, 0));
      expect(responses).toEqual([{ type: "prepared", generation: 3, operationId: 5 }]);
      responses.length = 0;
      scope.onmessage?.({ data: {
        type: "batch", generation: 3, operationId: 6, request: { rowIndices: [0], fields: ["amount"] },
      } });
      expect(responses).toEqual([]);
      await Promise.resolve();
      expect(responses).toEqual([]);
      await new Promise(resolve => setTimeout(resolve, 0));
      expect(responses[0]).toMatchObject({ type: "batch", generation: 3, operationId: 6, batch: { cells: [{ view: { text: "prepared" } }] } });
      scope.onmessage?.({ data: {
        type: "batch", generation: 3, operationId: 7, request: { rowIndices: [0], fields: ["amount"] },
      } });
      await new Promise(resolve => setTimeout(resolve, 0));
      expect(responses[1]).toMatchObject({ type: "batch", operationId: 7, batch: { cells: [{ view: { text: "after cell" } }] } });
    } finally { vi.unstubAllGlobals(); }
  });

  it("captures each response generation and operation before the queued checkpoint", async () => {
    const responses: TableWorkerResponse[] = [];
    const scope = {
      postMessage: (response: TableWorkerResponse) => responses.push(response),
      onmessage: null as ((event: { data: TableWorkerRequest }) => void) | null,
    };
    vi.stubGlobal("self", scope);
    try {
      startTableWidgetWorkerRuntime();
      for (const generation of [3, 4]) {
        scope.onmessage?.({ data: {
          type: "prepare", generation, operationId: generation + 10, frame, displayFractionDigits: digits,
          moduleSource: "export default defineTable({render(){return {};}});",
        } });
      }
      expect(responses).toEqual([]);
      await new Promise(resolve => setTimeout(resolve, 0));
      expect(responses).toEqual([
        { type: "prepared", generation: 3, operationId: 13 },
        { type: "prepared", generation: 4, operationId: 14 },
      ]);
    } finally { vi.unstubAllGlobals(); }
  });
});
