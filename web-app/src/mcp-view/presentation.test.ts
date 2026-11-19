import { describe, expect, it } from "vitest";
import type { DashboardDefinition } from "@/api/dashboard";
import type { NamedDashboardResult } from "@/features/dashboard/dashboard-result";
import {
  adoptBundles,
  bundleFromReadyResult,
  draftLayoutContent,
  reconcileBundles,
  RESULT_NOT_SHOWN_MESSAGE,
  runningQueryCount,
  runPathsInLayoutOrder,
  sessionLayoutContent,
  type ResultEntry,
  type TargetRun,
  type WidgetBundle,
} from "./presentation";

const definition = {
  schemaVersion: 1,
  meta: { title: "Weekly revenue" },
  parameters: {},
  variables: {},
  queries: {
    revenue: { type: "SEMANTIC", connectorId: "c", query: {} },
    orders: { type: "SQL", connectorId: "c", sql: "select 1", bindings: {}, columns: ["n"], rowLimit: 10 },
    targets: { type: "SEMANTIC", connectorId: "c", query: {} },
  },
  widgets: {
    notes: { type: "TEXT", content: "Notes" },
    chart: { type: "CHART", queryNames: ["revenue", "targets"], module: { inputs: { parameters: [], variables: [] }, source: "export default defineChart({ render() { return {}; } });" } },
    table: { type: "TABLE", queryName: "orders", columns: [{ field: "n" }] },
  },
  layout: {
    sections: [{
      id: "main",
      height: { mode: "SCALED", scale: 1 },
      root: { type: "STACK", id: "row", direction: "ROW", children: [
        { weight: 1, node: { type: "WIDGET", id: "table_node", widgetId: "table" } },
        { weight: 1, node: { type: "WIDGET", id: "chart_node", widgetId: "chart" } },
      ] },
    }],
  },
} as unknown as DashboardDefinition;

const result = (queryRunId: string): NamedDashboardResult => ({ queryRunId, queryType: "SEMANTIC", reportingTimeZone: null, columns: [], rows: [] });
const loaded = (...paths: string[]) => new Map<string, ResultEntry>(paths.map((path) => [path, { kind: "loaded", result: result(path) }]));
const bundle = (revision: number, queryRuns: Record<string, string>, region = "EMEA"): WidgetBundle => ({ queryRuns, parameterBindings: { region }, variables: { focus: region }, revision });
const target = (path: string, state: TargetRun["state"], failureDetail: string | null = null): TargetRun => ({ path, state, failureDetail, unavailable: false });

function draft(overrides: Partial<Parameters<typeof draftLayoutContent>[0]>) {
  return draftLayoutContent({
    definition,
    parameterBindings: { region: "ALL" },
    variables: { focus: null },
    revision: 9,
    readyBundles: {},
    adoptedBundles: {},
    targets: {},
    results: new Map(),
    ...overrides,
  });
}

describe("ready bundle adoption", () => {
  it("adopts a complete bundle and keeps the previous one while a newer bundle hydrates", () => {
    const first = bundle(1, { orders: "run/1" });
    const next = bundle(2, { orders: "run/2" });
    const adopted = adoptBundles(definition, { table: first }, loaded("run/1"), {});
    expect(adopted.table).toBe(first);
    expect(adoptBundles(definition, { table: next }, loaded("run/1"), adopted).table).toBe(first);
    expect(adoptBundles(definition, { table: next }, loaded("run/1", "run/2"), adopted).table).toBe(next);
  });

  it("needs every query of a widget before it adopts", () => {
    const partial = bundle(3, { revenue: "run/r", targets: "run/t" });
    expect(adoptBundles(definition, { chart: partial }, loaded("run/r"), {}).chart).toBeUndefined();
    expect(adoptBundles(definition, { chart: partial }, loaded("run/r", "run/t"), {}).chart).toBe(partial);
  });

  it("needs a pivot's totals results before it adopts and reads them after its primary result", () => {
    const pivotDefinition = { ...definition, widgets: { pivot: { type: "TABLE", queryName: "orders", pivot: { rows: [], columns: [{ field: "n" }], values: [{ field: "v" }], valuesAxis: "COLUMNS", totals: ["targets"] } } } } as unknown as DashboardDefinition;
    const partial = bundle(3, { orders: "run/o", targets: "run/t" });
    expect(adoptBundles(pivotDefinition, { pivot: partial }, loaded("run/o"), {}).pivot).toBeUndefined();
    expect(adoptBundles(pivotDefinition, { pivot: partial }, loaded("run/o", "run/t"), {}).pivot).toBe(partial);
    expect(runPathsInLayoutOrder(pivotDefinition, (_widgetId, names) => names.map((name) => `run/${name}`))).toEqual(["run/orders", "run/targets"]);
  });

  it("drops a shown bundle when the newer bundle's result cannot be shown", () => {
    const first = bundle(1, { orders: "run/1" });
    const results = loaded("run/1");
    results.set("run/2", { kind: "unavailable" });
    expect(adoptBundles(definition, { table: bundle(2, { orders: "run/2" }) }, results, { table: first }).table).toBeUndefined();
  });

  it("keeps equal bundles by reference across polls", () => {
    const first = bundleFromReadyResult({ authoringRevision: "4", parameterBindings: { region: "EMEA" }, variables: {}, queryRuns: { orders: "run/1" } });
    const equal = bundleFromReadyResult({ authoringRevision: "4", parameterBindings: { region: "EMEA" }, variables: {}, queryRuns: { orders: "run/1" } });
    expect(first.revision).toBe(4);
    expect(reconcileBundles({ table: first }, { table: equal }).table).toBe(first);
    expect(reconcileBundles({ table: first }, { table: { ...equal, revision: 5 } }).table).not.toBe(first);
  });
});

describe("Draft layout content", () => {
  it("shows each widget with its ready bundle's Parameters and Variables", () => {
    const tableBundle = bundle(7, { orders: "run/1" }, "APAC");
    const content = draft({ readyBundles: { table: tableBundle }, adoptedBundles: { table: tableBundle }, targets: { orders: target("run/1", "SUCCEEDED") }, results: loaded("run/1") });
    expect([...content.displayedWidgetIds]).toEqual(["table"]);
    expect(content.resultsByWidgetId.table.orders.queryRunId).toBe("run/1");
    expect(content.parametersByWidgetId.table).toEqual({ region: "APAC" });
    expect(content.variablesByWidgetId.table).toEqual({ focus: "APAC" });
    expect(content.variableRevisionsByWidgetId.table).toBe(7);
    expect(content.parameters).toEqual({ region: "ALL" });
    expect(content.variableRevision).toBe(9);
    expect(content.queryTypes).toEqual({ revenue: "SEMANTIC", orders: "SQL", targets: "SEMANTIC" });
    expect(content.refreshingWidgetIds.size).toBe(0);
    expect(content.failureWidgetIds.size).toBe(0);
  });

  it("refreshes while a newer target run replaces the shown bundle", () => {
    const shown = bundle(1, { orders: "run/1" });
    const content = draft({ readyBundles: { table: shown }, adoptedBundles: { table: shown }, targets: { orders: target("run/2", "RUNNING") }, results: loaded("run/1") });
    expect(content.displayedWidgetIds.has("table")).toBe(true);
    expect(content.refreshingWidgetIds.has("table")).toBe(true);
    expect(runningQueryCount({ orders: target("run/2", "RUNNING"), revenue: target("run/2", "RUNNING"), targets: target("run/3", "SUCCEEDED") })).toBe(1);
  });

  it("explains a failed target with its failure detail until results exist", () => {
    const failed = draft({ targets: { orders: target("run/2", "FAILED", "Column n does not exist.") } });
    expect(failed.failureWidgetIds.has("table")).toBe(true);
    expect(failed.errorMessagesByWidgetId.table).toBe("Column n does not exist.");
    expect(draft({ targets: { orders: target("run/2", "FAILED") } }).errorMessagesByWidgetId.table).toBe("The query failed.");
    expect(draft({ targets: { orders: target("run/2", "CANCELLED") } }).errorMessagesByWidgetId.table).toBe("The query was cancelled.");

    const shown = bundle(1, { orders: "run/1" });
    const keptResults = draft({ readyBundles: { table: shown }, adoptedBundles: { table: shown }, targets: { orders: target("run/2", "FAILED", "Timeout.") }, results: loaded("run/1") });
    expect(keptResults.displayedWidgetIds.has("table")).toBe(true);
    expect(keptResults.failureWidgetIds.has("table")).toBe(true);
    expect(keptResults.errorMessagesByWidgetId.table).toBeUndefined();
  });

  it("closes a widget whose result cannot be shown here", () => {
    const results = new Map<string, ResultEntry>([["run/1", { kind: "unavailable" }]]);
    const content = draft({ readyBundles: { table: bundle(1, { orders: "run/1" }) }, targets: { orders: target("run/1", "SUCCEEDED") }, results });
    expect(content.displayedWidgetIds.has("table")).toBe(false);
    expect(content.failureWidgetIds.has("table")).toBe(true);
    expect(content.errorMessagesByWidgetId.table).toBe(RESULT_NOT_SHOWN_MESSAGE);
  });

  it("labels loading widgets by what they wait for", () => {
    const content = draft({ readyBundles: { chart: bundle(1, { revenue: "run/r", targets: "run/t" }) }, targets: { orders: target("run/o", "RUNNING") }, results: loaded("run/r") });
    expect(content.displayedWidgetIds.size).toBe(0);
    expect(content.loadingMessagesByWidgetId.table).toBe("Running query…");
    expect(content.loadingMessagesByWidgetId.chart).toBe("Loading results…");
    expect(draft({}).loadingMessagesByWidgetId.table).toBe("Waiting for results…");
    expect(content.loadingMessagesByWidgetId.notes).toBeUndefined();
  });
});

describe("Session layout content", () => {
  const session = (targets: Record<string, TargetRun>, results: Map<string, ResultEntry>) => sessionLayoutContent({
    definition,
    parameterBindings: { region: "EMEA" },
    variables: { focus: "KR" },
    targets,
    results,
  });

  it("shows widgets whose passed runs succeeded with the Session's shared inputs", () => {
    const content = session({ orders: target("s/1", "SUCCEEDED"), revenue: target("s/2", "RUNNING"), targets: target("s/3", "SUCCEEDED") }, loaded("s/1", "s/3"));
    expect([...content.displayedWidgetIds]).toEqual(["table"]);
    expect(content.parametersByWidgetId).toEqual({});
    expect(content.variablesByWidgetId).toEqual({});
    expect(content.variables).toEqual({ focus: "KR" });
    expect(content.loadingMessagesByWidgetId.chart).toBe("Running query…");
    expect(content.refreshingWidgetIds.size).toBe(0);
  });

  it("fails widgets whose run failed, could not be read, or was not passed", () => {
    const unavailable: TargetRun = { path: "s/1", state: null, failureDetail: null, unavailable: true };
    const content = session({ orders: unavailable, revenue: target("s/2", "FAILED", "Bind failed.") }, new Map());
    expect(content.errorMessagesByWidgetId.table).toBe(RESULT_NOT_SHOWN_MESSAGE);
    expect(content.errorMessagesByWidgetId.chart).toBe("This widget has no result in this view.");
    const failed = session({ orders: target("s/1", "SUCCEEDED"), revenue: target("s/2", "FAILED", "Bind failed."), targets: target("s/3", "SUCCEEDED") }, loaded("s/1", "s/3"));
    expect(failed.errorMessagesByWidgetId.chart).toBe("Bind failed.");
    const results = loaded("s/2", "s/3");
    results.set("s/1", { kind: "unavailable" });
    expect(session({ orders: target("s/1", "SUCCEEDED"), revenue: target("s/2", "SUCCEEDED"), targets: target("s/3", "SUCCEEDED") }, results).errorMessagesByWidgetId.table).toBe(RESULT_NOT_SHOWN_MESSAGE);
  });
});

describe("result read order", () => {
  it("follows the layout so the first widgets fill in first", () => {
    const paths = runPathsInLayoutOrder(definition, (widgetId, names) => names.map((name) => `${widgetId}/${name}`));
    expect(paths).toEqual(["table/orders", "chart/revenue", "chart/targets"]);
  });
});
