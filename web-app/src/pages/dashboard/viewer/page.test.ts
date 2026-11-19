import { describe, expect, it } from "vitest";
import type { DashboardDefinition } from "@/api/dashboard";
import { acceptsChartInput, acceptScreenRequest, adoptWidgetBundles, beginScreenRequest, displayedWidgets, initialScreen, rejectScreenRequest, widgetResultAccessErrors } from "./screen-state";

const definition: DashboardDefinition = {
  schemaVersion: 1, meta: { title: "atomic table" }, parameters: {}, variables: {}, queries: {}, layout: { sections: [] },
  widgets: { table: { type: "TABLE", queryName: "orders", columns: [{ field: "metric.value" }], module: { inputs: { queries: ["daily"], parameters: [], variables: ["floor"] }, source: "" } } },
};
const result = (queryRunId: string) => ({ queryType: "SEMANTIC" as const, reportingTimeZone: null, queryRunId, columns: [], rows: [] });
const accepted = acceptScreenRequest(beginScreenRequest(initialScreen({ country: "JP", city: "Tokyo" }), {}), 1, { variables: { country: "JP", city: "Tokyo" }, queryRuns: { orders: "orders-old", daily: "daily-old" } });
const available = { "orders-old": result("orders-old"), "daily-old": result("daily-old") };

describe("independent Viewer screen", () => {
  it("submits the latest complete candidate during continuous INPUT and adopts only the latest response", () => {
    const first = beginScreenRequest(accepted, { country: "KR" });
    const latest = beginScreenRequest(first, { city: null });
    expect(latest.candidate).toEqual({ country: "KR", city: null });
    expect(acceptScreenRequest(latest, first.latestRequest, { variables: { country: "KR", city: "Tokyo" }, queryRuns: { orders: "late" } })).toBe(latest);
    const complete = acceptScreenRequest(latest, latest.latestRequest, { variables: latest.candidate, queryRuns: { orders: "new" } });
    expect(complete.variables).toEqual({ country: "KR", city: null });
    expect(complete.queryRuns).toEqual({ orders: "new" });
    expect(rejectScreenRequest(complete, first.latestRequest, "late rejection")).toBe(complete);
    expect(accepted.variables.country).toBe("JP");
  });

  it("rolls the latest rejection back to accepted values and targets without replaying a late accepted request", () => {
    const first = beginScreenRequest(accepted, { country: "KR" });
    const latest = beginScreenRequest(first, { country: "invalid" });
    const rejected = rejectScreenRequest(latest, latest.latestRequest, "denied");
    expect(rejected.candidate).toEqual(accepted.variables);
    expect(rejected.queryRuns).toBe(accepted.queryRuns);
    expect(acceptScreenRequest(rejected, first.latestRequest, { variables: first.candidate, queryRuns: { orders: "late" } })).toBe(rejected);
    expect(rejected.error).toBe("denied");
  });

  it("blocks a chart while resolve is pending and while its old ready frame remains displayed", () => {
    expect(acceptsChartInput(accepted, 1)).toBe(true);
    const pending = beginScreenRequest(accepted, { city: null });
    expect(acceptsChartInput(pending, 1)).toBe(false);
    const next = acceptScreenRequest(pending, 2, { variables: pending.candidate, queryRuns: { orders: "next", daily: "next-daily" } });
    expect(acceptsChartInput(next, 1)).toBe(false);
    expect(acceptsChartInput(next, 2)).toBe(true);
    expect(acceptsChartInput(rejectScreenRequest(pending, 2, "invalid"), 1)).toBe(true);
  });

  it("retains the complete old TABLE bundle through a partial auxiliary download, then promotes data and variables atomically", () => {
    const old = adoptWidgetBundles(definition, accepted, available, {}, new Set());
    const pending = beginScreenRequest(accepted, { city: null });
    const next = acceptScreenRequest(pending, 2, { variables: pending.candidate, queryRuns: { orders: "orders-next", daily: "daily-next" } });
    const partial = { ...available, "orders-next": result("orders-next") };
    expect(adoptWidgetBundles(definition, next, partial, old, new Set())).toBe(old);
    const display = displayedWidgets(definition, next, [], partial, old, new Set(["daily-next"]), new Set(), true);
    expect(display.variablesByWidgetId.table).toEqual(accepted.variables);
    expect(display.failureIds.has("table")).toBe(true);
    expect(display.widgetIds.has("table")).toBe(true);
    const complete = { ...partial, "daily-next": result("daily-next") };
    const bundles = adoptWidgetBundles(definition, next, complete, old, new Set());
    expect(bundles.table).toEqual({ variables: next.variables, inputOrder: 2, queryRuns: next.queryRuns });
  });

  it("removes denied ready data immediately instead of falling back to a revoked bundle", () => {
    const bundles = adoptWidgetBundles(definition, accepted, available, {}, new Set());
    const denied = new Set(["daily-old"]);
    expect(displayedWidgets(definition, accepted, [], available, bundles, denied, denied, true).widgetIds.size).toBe(0);
    expect(adoptWidgetBundles(definition, accepted, available, bundles, denied)).toEqual({});
  });

  it("clears denied replacement widgets while preserving unrelated widgets and transient-error last-good", () => {
    const twoWidgets: DashboardDefinition = { ...definition, widgets: { ...definition.widgets, unaffected: { type: "TABLE", queryName: "other", columns: [{ field: "metric.value" }] } } };
    const initial = { ...accepted, queryRuns: { ...accepted.queryRuns, other: "other-ready" } };
    const complete = { ...available, "other-ready": result("other-ready") };
    const bundles = adoptWidgetBundles(twoWidgets, initial, complete, {}, new Set());
    const next = acceptScreenRequest(beginScreenRequest(initial, { city: "Seoul" }), 2, { variables: { city: "Seoul" }, queryRuns: { orders: "orders-next", daily: "daily-next", other: "other-ready" } });
    const transientFailure = new Set(["daily-next"]);
    expect(displayedWidgets(twoWidgets, next, [], complete, bundles, transientFailure, new Set(), true).widgetIds).toEqual(new Set(["table", "unaffected"]));
    const denied = new Set(["daily-next"]);
    expect(displayedWidgets(twoWidgets, next, [], complete, bundles, new Set(), denied, true).widgetIds).toEqual(new Set(["unaffected"]));
    const adopted = adoptWidgetBundles(twoWidgets, next, complete, bundles, denied);
    expect(adopted.table).toBeUndefined();
    expect(adopted.unaffected.queryRuns).toEqual({ other: "other-ready" });
  });

  it("ignores another screen's newer result during revalidation and promotes source-only variables against the same runs", () => {
    const bundles = adoptWidgetBundles(definition, accepted, available, {}, new Set());
    expect(adoptWidgetBundles(definition, accepted, { ...available, unrelated: result("unrelated") }, bundles, new Set())).toBe(bundles);
    const pending = beginScreenRequest(accepted, { city: null });
    const next = acceptScreenRequest(pending, 2, { variables: pending.candidate, queryRuns: accepted.queryRuns });
    expect(adoptWidgetBundles(definition, next, available, bundles, new Set()).table.variables.city).toBeNull();
  });
});


it("keeps the access denial message after removing the revoked bundle and clears it after an authorized result", () => {
  const bundles = adoptWidgetBundles(definition, accepted, available, {}, new Set());
  const denied = new Set(["daily-old"]);
  const errors = widgetResultAccessErrors(definition, accepted.queryRuns, bundles, {}, denied, {});
  expect(errors.table).toContain("do not have access");
  expect(errors.table).not.toContain("Refresh");
  expect(widgetResultAccessErrors(definition, accepted.queryRuns, {}, {}, new Set(), errors)).toEqual(errors);
  expect(widgetResultAccessErrors(definition, accepted.queryRuns, {}, bundles, new Set(), errors)).toEqual({});
});

describe("viewer result messages", () => {
  const bundles = adoptWidgetBundles(definition, accepted, available, {}, new Set());
  const successfulRuns = Object.keys(available).map(queryRunId => ({ queryRunId, state: "SUCCEEDED" as const }));
  it("distinguishes a read failure from a new input's results", () => {
    const same = displayedWidgets(definition, accepted, successfulRuns, available, bundles, new Set(["orders-old"]), new Set(), true);
    expect(same.errorMessagesByWidgetId.table).toBe("Results could not be checked. Displayed results are unchanged.");
    const next = acceptScreenRequest(beginScreenRequest(accepted, { city: "Seoul" }), 2, { variables: { city: "Seoul" }, queryRuns: { orders: "new", daily: "daily-old" } });
    const changed = displayedWidgets(definition, next, successfulRuns, available, bundles, new Set(["new"]), new Set(), true);
    expect(changed.errorMessagesByWidgetId.table).toBe("Updated results could not be loaded. Previous results remain visible.");
  });
  it("explains a first execution failure with the run's message and keeps last-good copy for an updated failure", () => {
    const first = displayedWidgets(definition, accepted, [{ queryRunId: "orders-old", state: "FAILED" }, { queryRunId: "daily-old", state: "SUCCEEDED" }], {}, {}, new Set(), new Set(), true);
    expect(first.errorMessagesByWidgetId.table).toBe("The query failed.");
    const described = displayedWidgets(definition, accepted, [{ queryRunId: "orders-old", state: "FAILED", failureMessage: "The warehouse query timed out after 120 seconds." }, { queryRunId: "daily-old", state: "SUCCEEDED" }], {}, {}, new Set(), new Set(), true);
    expect(described.errorMessagesByWidgetId.table).toBe("The warehouse query timed out after 120 seconds.");
    const cancelled = displayedWidgets(definition, accepted, [{ queryRunId: "orders-old", state: "CANCELLED" }, { queryRunId: "daily-old", state: "SUCCEEDED" }], {}, {}, new Set(), new Set(), true);
    expect(cancelled.errorMessagesByWidgetId.table).toBe("The query was cancelled.");
    expect(Object.values({ ...first.errorMessagesByWidgetId, ...cancelled.errorMessagesByWidgetId }).join(" ")).not.toContain("Select Refresh");
    const readOnly = displayedWidgets(definition, accepted, [{ queryRunId: "orders-old", state: "FAILED" }, { queryRunId: "daily-old", state: "SUCCEEDED" }], {}, {}, new Set(), new Set(), false);
    expect(readOnly.errorMessagesByWidgetId.table).toBe("The query failed or was cancelled.");
    const next = acceptScreenRequest(beginScreenRequest(accepted, { city: "Seoul" }), 2, { variables: { city: "Seoul" }, queryRuns: { orders: "orders-failed", daily: "daily-old" } });
    const updated = displayedWidgets(definition, next, [...successfulRuns, { queryRunId: "orders-failed", state: "FAILED" }], available, bundles, new Set(), new Set(), true);
    expect(updated.widgetIds.has("table")).toBe(true);
    expect(updated.errorMessagesByWidgetId.table).toBe("The updated query failed. Previous results remain visible.");
  });
  it("distinguishes initial artifact loading, query execution and updates", () => {
    const initial = displayedWidgets(definition, accepted, successfulRuns, {}, {}, new Set(), new Set(), true);
    expect(initial.loadingMessagesByWidgetId.table).toBe("Loading results…");
    const running = displayedWidgets(definition, accepted, [{ queryRunId: "orders-old", state: "RUNNING" }], {}, {}, new Set(), new Set(), true);
    expect(running.loadingMessagesByWidgetId.table).toBe("Running query…");
    const pending = displayedWidgets(definition, beginScreenRequest(accepted, { city: "Seoul" }), successfulRuns, available, bundles, new Set(), new Set(), true);
    expect(pending.loadingMessagesByWidgetId.table).toBe("Updating results…");
  });
  it("uses update copy only while a kept result is displayed", () => {
    const firstEntry = beginScreenRequest(initialScreen({ country: "JP", city: "Tokyo" }), {});
    expect(displayedWidgets(definition, firstEntry, [], {}, {}, new Set(), new Set(), true).loadingMessagesByWidgetId.table).toBe("Loading results…");
    const firstTarget = acceptScreenRequest(firstEntry, 1, { variables: firstEntry.candidate, queryRuns: { orders: "orders-old", daily: "daily-old" } });
    const running = displayedWidgets(definition, firstTarget, [{ queryRunId: "orders-old", state: "RUNNING" }], {}, {}, new Set(), new Set(), true);
    expect(running.loadingMessagesByWidgetId.table).toBe("Running query…");
    const next = acceptScreenRequest(beginScreenRequest(accepted, { city: "Seoul" }), 2, { variables: { city: "Seoul" }, queryRuns: { orders: "orders-next", daily: "daily-next" } });
    const replacedWithoutKeptResult = displayedWidgets(definition, next, [], {}, bundles, new Set(), new Set(), true);
    expect(replacedWithoutKeptResult.widgetIds.has("table")).toBe(false);
    expect(replacedWithoutKeptResult.loadingMessagesByWidgetId.table).toBe("Loading results…");
  });
});
