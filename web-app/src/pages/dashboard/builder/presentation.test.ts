import { describe, expect, it } from "vitest";
import type { DashboardDraftResponse, DashboardQueryRun } from "@/api/dashboard";
import { validateDashboardResult } from "@/features/dashboard/dashboard-result";
import { dashboardBuilderDisplayState, dashboardRunPresentation, readyRunIds, retainHydratingBuilderWidgets } from "./presentation";

const runId = "00000000-0000-7000-8000-000000000002";
const nextRunId = "00000000-0000-7000-8000-000000000003";

const run = (queryRunId: string, state: DashboardQueryRun["state"]): DashboardQueryRun => ({ queryType: "SEMANTIC" as const,
  connectorId: "00000000-0000-7000-8000-000000000001",
  createdAt: "2026-08-24T08:00:00Z",
  startedBy: null, credentialSurface: null, agentApp: null,
  failureCode: state === "FAILED" ? "EXECUTION_ORPHANED" : null,
  failureMessage: state === "FAILED" ? "Warehouse failed" : null,
  outputColumnNames: ["metric.order_count"],
  queryName: "orders",
  queryRunId,
  resultAvailable: state === "SUCCEEDED",
  state,
  updatedAt: "2026-08-24T08:00:04Z",
});

const result = validateDashboardResult({ queryType: "SEMANTIC" as const, reportingTimeZone: null,
  queryRunId: runId,
  columns: [{ name: "metric.order_count", logicalType: "NUMBER", nullable: false, display: null }],
  rows: [["63"]],
});

function draft(overrides: Partial<DashboardDraftResponse> = {}): DashboardDraftResponse {
  return {
    workspaceId: "00000000-0000-7000-8000-000000000010",
    dashboardId: "00000000-0000-7000-8000-000000000011",
    definition: {
      schemaVersion: 1,
      meta: { title: "Orders" },
      parameters: {},
      variables: {},
      queries: {
        orders: { type: "SEMANTIC" as const,
          connectorId: "00000000-0000-7000-8000-000000000001",
          query: {} as never,
        },
      },
      widgets: {
        table: { type: "TABLE", queryName: "orders", columns: [{ field: "metric.order_count", header: "Orders" }] },
      },
      layout: {
        sections: [{
          id: "main",
          height: { mode: "SCALED", scale: 1 },
          root: { type: "WIDGET", id: "table-item", widgetId: "table" },
        }],
      },
    },
    parameterBindings: {},
    variables: {},
    authoringRevision: 1,
    targetQueryRuns: { orders: runId },
    readyResults: {
      table: { authoringRevision: 1, parameterBindings: {}, variables: {}, queryRuns: { orders: runId } },
    },
    recentQueryRuns: [run(runId, "SUCCEEDED")],
    createdAt: "2026-08-24T08:00:00Z",
    updatedAt: "2026-08-24T08:00:04Z",
    lastEditedBy: null,
    lastEditedAt: null,
    lastEditSurface: null, lastEditAgentApp: null,
    ...overrides,
  };
}

describe("Dashboard Builder presentation", () => {
  it("requires the TABLE auxiliary result and retains its ready Variable snapshot", () => {
    const current = draft();
    current.definition.widgets.table = {
      type: "TABLE", queryName: "orders", columns: [{ field: "metric.order_count" }],
      module: { inputs: { queries: ["daily"], parameters: [], variables: ["floor"] }, source: "" },
    };
    current.variables = { floor: "20" };
    current.parameterBindings = { currency: "EUR" };
    current.readyResults.table.parameterBindings = { currency: "USD" };
    current.readyResults.table.variables = { floor: "10" };
    current.readyResults.table.queryRuns.daily = "daily-run";
    current.targetQueryRuns.daily = "next-daily-run";
    const partial = dashboardBuilderDisplayState(current, { [runId]: result });
    expect(partial.displayedWidgetIds.has("table")).toBe(false);
    const complete = dashboardBuilderDisplayState(current, { [runId]: result, "daily-run": { ...result, queryRunId: "daily-run" } });
    expect(complete.displayedWidgetIds.has("table")).toBe(true);
    expect(complete.refreshingWidgetIds.has("table")).toBe(true);
    expect(complete.variablesByWidgetId.table).toEqual({ floor: "10" });
    expect(complete.parametersByWidgetId.table).toEqual({ currency: "USD" });
  });

  it("marks current target runs without creating a second run type", () => {
    const presentation = dashboardRunPresentation(run(runId, "RUNNING"), { orders: runId }, undefined);
    expect(presentation.isCurrentTarget).toBe(true);
    expect(presentation.state).toBe("RUNNING");
    expect(presentation.result).toBeNull();
    expect(presentation.credentialSurface).toBeNull();
  });

  it("carries the person, the agent request path, and the agent app into the shared run presentation", () => {
    const sam = { id: "00000000-0000-7000-8000-0000000000e1", displayName: "Sam Lee" };
    const codex = { clientId: "https://chatgpt.com/codex/client.json", name: "Codex", verifiedHost: "chatgpt.com" };
    const presentation = dashboardRunPresentation({ ...run(runId, "SUCCEEDED"), startedBy: sam, credentialSurface: "AGENT", agentApp: codex }, {}, undefined);
    expect(presentation.credentialSurface).toBe("AGENT");
    expect(presentation.agentApp).toEqual(codex);
    expect(presentation.startedBy).toEqual(sam);
    const unrecorded = dashboardRunPresentation(run(runId, "SUCCEEDED"), {}, undefined);
    expect(unrecorded.startedBy).toBeNull();
    expect(unrecorded.agentApp).toBeNull();
  });

  it("subtitles a run as a Dashboard query without the connector id", () => {
    const presentation = dashboardRunPresentation(run(runId, "SUCCEEDED"), {}, undefined);
    expect(presentation.subtitle).toBe("Dashboard query");
    expect(presentation.subtitle).not.toContain("00000000-0000-7000-8000-000000000001");
  });

  it("keeps the last-good widget bundle while a replacement target runs", () => {
    const current = draft({
      targetQueryRuns: { orders: nextRunId },
      recentQueryRuns: [run(runId, "SUCCEEDED"), run(nextRunId, "RUNNING")],
    });
    const state = dashboardBuilderDisplayState(current, { [runId]: result });
    expect(state.displayedWidgetIds.has("table")).toBe(true);
    expect(state.refreshingWidgetIds.has("table")).toBe(true);
    expect(state.failureWidgetIds.has("table")).toBe(false);
    expect(readyRunIds(current)).toEqual([runId]);
  });

  it("marks failed current targets while retaining the prior ready frame", () => {
    const current = draft({
      targetQueryRuns: { orders: nextRunId },
      recentQueryRuns: [run(runId, "SUCCEEDED"), run(nextRunId, "FAILED")],
    });
    const state = dashboardBuilderDisplayState(current, { [runId]: result });
    expect(state.displayedWidgetIds.has("table")).toBe(true);
    expect(state.failureWidgetIds.has("table")).toBe(true);
  });
});


describe("Builder result hydration retention", () => {
  it("retains the complete previous data and parameter snapshot until all new artifacts arrive", () => {
    const before = draft();
    before.readyResults.table.parameterBindings = { currency: "USD" };
    const previous = dashboardBuilderDisplayState(before, { [runId]: result });
    const after = draft({ parameterBindings: { currency: "EUR" }, targetQueryRuns: { orders: nextRunId }, readyResults: { table: { authoringRevision: 2, parameterBindings: { currency: "EUR" }, variables: {}, queryRuns: { orders: nextRunId } } } });
    const waiting = retainHydratingBuilderWidgets(after, dashboardBuilderDisplayState(after, {}), previous, new Set());
    expect(waiting.displayedWidgetIds.has("table")).toBe(true);
    expect(waiting.parametersByWidgetId.table).toEqual({ currency: "USD" });
    expect(waiting.resultsByWidgetId.table.orders.queryRunId).toBe(runId);
    expect(waiting.refreshingWidgetIds.has("table")).toBe(true);
    const deniedReplacement = retainHydratingBuilderWidgets(after, dashboardBuilderDisplayState(after, {}), waiting, new Set([nextRunId]));
    expect(deniedReplacement.displayedWidgetIds.has("table")).toBe(false);
    expect(deniedReplacement.refreshingWidgetIds.has("table")).toBe(false);
    expect(deniedReplacement.failureWidgetIds.has("table")).toBe(true);
    expect(deniedReplacement.resultsByWidgetId.table).toBeUndefined();
    const nextResult = { ...result, queryRunId: nextRunId };
    const complete = retainHydratingBuilderWidgets(after, dashboardBuilderDisplayState(after, { [nextRunId]: nextResult }), waiting, new Set());
    expect(complete.parametersByWidgetId.table).toEqual({ currency: "EUR" });
    expect(complete.resultsByWidgetId.table.orders.queryRunId).toBe(nextRunId);
    expect(complete.refreshingWidgetIds.has("table")).toBe(false);
  });

  it("does not retain denied results, removed bundles, or a cleared prior scope", () => {
    const current = draft();
    const previous = dashboardBuilderDisplayState(current, { [runId]: result });
    const candidate = dashboardBuilderDisplayState(current, {});
    const denied = retainHydratingBuilderWidgets(current, candidate, previous, new Set([runId]));
    expect(denied.displayedWidgetIds.has("table")).toBe(false);
    expect(denied.resultsByWidgetId.table).toBeUndefined();
    expect(denied.failureWidgetIds.has("table")).toBe(true);
    expect(retainHydratingBuilderWidgets({ ...current, readyResults: {} }, candidate, previous, new Set()).displayedWidgetIds.size).toBe(0);
    expect(retainHydratingBuilderWidgets(current, candidate, null, new Set()).displayedWidgetIds.size).toBe(0);
  });
});
