import { describe, expect, it } from "vitest";
import type { DashboardDraftResponse } from "@/api/dashboard";
import { dashboardBuilderDisplayState } from "./presentation";
import { compatibleDraftWidgetInput } from "./shared-interactions";
import { validateDashboardResult } from "@/features/dashboard/dashboard-result";

function fixture(): DashboardDraftResponse {
  return {
    workspaceId: "workspace", dashboardId: "dashboard", authoringRevision: 3,
    definition: { schemaVersion: 1, meta: { title: "Orders" }, parameters: {},
      variables: { country: { type: "TEXT", nullable: false, initial: "US" }, city: { type: "TEXT", nullable: true, initial: null }, unrelated: { type: "TEXT", nullable: true, initial: null } },
      queries: { orders: { type: "SEMANTIC" as const, connectorId: "connector", query: { filters: [{ kind: "VARIABLE", name: "country" }] } as never } },
      widgets: { chart: { type: "CHART", queryNames: ["orders"], module: { source: "source", inputs: { parameters: [], variables: ["country", "city"] } } } },
      layout: { sections: [] } },
    parameterBindings: {}, variables: { country: "US", city: null, unrelated: null },
    targetQueryRuns: { orders: "run" }, readyResults: { chart: { authoringRevision: 1, parameterBindings: {}, variables: { country: "US", city: null, unrelated: null }, queryRuns: { orders: "run" } } },
    recentQueryRuns: [], createdAt: "2026-09-14T00:00:00Z", updatedAt: "2026-09-14T00:00:00Z",
    lastEditedBy: null, lastEditedAt: null, lastEditSurface: null, lastEditAgentApp: null,
  };
}
function display(draft: DashboardDraftResponse) {
  return dashboardBuilderDisplayState(draft, { run: validateDashboardResult({ queryType: "SEMANTIC" as const, reportingTimeZone: null, queryRunId: "run", columns: [{ name: "n", logicalType: "NUMBER", nullable: false, display: null }], rows: [["1"]] }) });
}

describe("shared Draft chart frame admission", () => {
  it("accepts a multi-variable map from an older ready revision after unrelated authoring changes", () => {
    const frame = fixture();
    const current = structuredClone(frame);
    current.authoringRevision = 9;
    current.definition.meta.title = "New title";
    current.variables.unrelated = "changed";
    expect(compatibleDraftWidgetInput("chart", frame, display(frame), current, { country: "CA", city: null })).toBe(true);
  });
  it("rejects old inputs, replaced targets, changed query or source and undeclared writes", () => {
    const frame = fixture();
    for (const modify of [
      (next: DashboardDraftResponse) => { next.variables.country = "CA"; },
      (next: DashboardDraftResponse) => { next.targetQueryRuns.orders = "new-run"; },
      (next: DashboardDraftResponse) => { next.definition.queries.orders.connectorId = "another"; },
      (next: DashboardDraftResponse) => { (next.definition.widgets.chart as { module: { source: string } }).module.source = "changed"; },
    ]) {
      const current = structuredClone(frame); modify(current);
      expect(compatibleDraftWidgetInput("chart", frame, display(frame), current, { country: "CA" })).toBe(false);
    }
    expect(compatibleDraftWidgetInput("chart", frame, display(frame), frame, { unrelated: "x" })).toBe(false);
    expect(compatibleDraftWidgetInput("chart", frame, display(frame), frame, {})).toBe(false);
  });
  it("rejects a retained last-good bundle until its current target is displayed", () => {
    const frame = fixture();
    frame.targetQueryRuns.orders = "pending-run";
    expect(compatibleDraftWidgetInput("chart", frame, display(frame), frame, { country: "CA" })).toBe(false);
  });
  it("checks SQL bind references while keeping unrelated SQL text out of the input graph", () => {
    const frame = fixture();
    frame.definition.queries.orders = { type: "SQL", connectorId: "connector", sql: "select {{country}} as country", bindings: { country: { kind: "VARIABLE", name: "unrelated" } }, columns: ["country"], rowLimit: 10 };
    const current = structuredClone(frame);
    current.variables.unrelated = "changed";
    expect(compatibleDraftWidgetInput("chart", frame, display(frame), current, { city: "Toronto" })).toBe(false);
    expect(compatibleDraftWidgetInput("chart", frame, display(frame), frame, { city: "Toronto" })).toBe(true);
  });
});
