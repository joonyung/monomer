import { describe, expect, it } from "vitest";
import type { DashboardDefinition } from "@/api/dashboard";
import { definitionChangeSummary, sameDefinitionValue, summarizeDefinitionChanges } from "./definition-changes";

const connectorId = "019e8000-0000-7000-8000-000000000c01";

function query(metrics: string[], filters: Extract<DashboardDefinition["queries"][string], { type: "SEMANTIC" }>["query"]["filters"] = []): Extract<DashboardDefinition["queries"][string], { type: "SEMANTIC" }> {
  return { type: "SEMANTIC" as const, connectorId, query: { schemaVersion: 4, metrics, groupBys: [], metricTimeRange: null, filters, orderBy: [], rowLimit: 100 } };
}

const applied: DashboardDefinition = {
  schemaVersion: 1,
  meta: { title: "Pipeline health", description: "Stage conversion" },
  parameters: { quarter: { type: "TEXT", nullable: false, label: "Quarter" } },
  variables: {},
  queries: { stage_conversion: query(["conversion"]), slipped: query(["slipped"]) },
  widgets: {
    conversion: { type: "TABLE", queryName: "stage_conversion", columns: [{ field: "metric.conversion", header: "Conversion" }], title: "Stage conversion" },
    notes: { type: "TEXT", content: "Notes" },
  },
  schedules: { weekly: { cron: "0 8 * * 1", timeZone: "UTC", parameters: { quarter: { type: "LITERAL", value: "Q4" } } } },
  layout: { sections: [
    { id: "main", height: { mode: "SCALED", scale: 1 }, root: { type: "STACK", id: "row", direction: "ROW", children: [
      { weight: 1, node: { type: "WIDGET", id: "conversion-item", widgetId: "conversion" } },
      { weight: 1, node: { type: "WIDGET", id: "notes-item", widgetId: "notes" } },
    ] } },
  ] },
};

describe("summarizeDefinitionChanges", () => {
  it("names SQL source, bindings, columns, and row-limit changes", () => {
    const query = { type: "SQL" as const, connectorId, sql: "select {{amount}} as amount", bindings: { amount: { kind: "PARAMETER" as const, name: "quarter" } }, columns: ["amount"], rowLimit: 100 };
    const before = { ...applied, queries: { total: query } };
    const after = { ...before, queries: { total: { ...query, sql: "select {{amount}} as total", bindings: { amount: { kind: "VARIABLE" as const, name: "value" } }, columns: ["total"], rowLimit: 50 } } };
    expect(summarizeDefinitionChanges(before, after)).toEqual([{ kind: "Changed", label: "Query · total (SQL, bindings, columns, row limit)" }]);
  });
  it("reports nothing for an equal definition even when keys are ordered differently", () => {
    const reordered = JSON.parse(JSON.stringify({ widgets: applied.widgets, layout: applied.layout, meta: applied.meta, schemaVersion: 1, parameters: applied.parameters, variables: {}, queries: applied.queries, schedules: applied.schedules })) as DashboardDefinition;
    expect(sameDefinitionValue(applied, reordered)).toBe(true);
    expect(summarizeDefinitionChanges(applied, reordered)).toEqual([]);
  });

  it("names added, changed, and removed parts with human labels, added first", () => {
    const draft: DashboardDefinition = {
      ...applied,
      queries: {
        stage_conversion: query(["conversion"], [{ kind: "TEXT", operator: "EQUALS", dimension: { semanticModelName: "deals", name: "stage" }, value: "Won" }]),
        owners: query(["slipped_by_owner"]),
      },
      widgets: {
        conversion: applied.widgets.conversion,
        owners: { type: "CHART", queryNames: ["owners"], title: "Slipped deals by owner", module: { inputs: { parameters: [], variables: [] }, source: "export default defineChart({})" } },
        trend: { type: "CHART", queryNames: ["owners"], module: { inputs: { parameters: [], variables: [] }, source: "export default defineChart({})" } },
      },
      schedules: { weekly: { ...applied.schedules!.weekly, cron: "0 8 * * 1-5" } },
      layout: { sections: [
        { id: "main", height: { mode: "SCALED", scale: 1 }, root: { type: "WIDGET", id: "conversion-item", widgetId: "conversion" } },
        { id: "more", height: { mode: "SCALED", scale: 1 }, root: { type: "STACK", id: "more-row", direction: "ROW", children: [
          { weight: 1, node: { type: "WIDGET", id: "owners-item", widgetId: "owners" } },
          { weight: 1, node: { type: "WIDGET", id: "trend-item", widgetId: "trend" } },
        ] } },
      ] },
    };
    expect(summarizeDefinitionChanges(applied, draft)).toEqual([
      { kind: "Added", label: "Chart · Slipped deals by owner" },
      { kind: "Added", label: "Chart · trend" },
      { kind: "Added", label: "Query · owners" },
      { kind: "Changed", label: "Query · stage_conversion (filters)" },
      { kind: "Changed", label: "Schedule · weekly (Weekdays · 8:00 AM)" },
      { kind: "Changed", label: "Layout" },
      { kind: "Removed", label: "Text · notes" },
      { kind: "Removed", label: "Query · slipped" },
    ]);
  });

  it("does not report a layout change when a widget is only appended in a new section", () => {
    const draft: DashboardDefinition = {
      ...applied,
      widgets: { ...applied.widgets, extra: { type: "TEXT", content: "More" } },
      layout: { sections: [...applied.layout.sections, { id: "extra", height: { mode: "CONTENT" }, root: { type: "WIDGET", id: "extra-item", widgetId: "extra" } }] },
    };
    expect(summarizeDefinitionChanges(applied, draft)).toEqual([{ kind: "Added", label: "Text · extra" }]);
  });

  it("covers inputs, schedules, and dashboard details", () => {
    const draft: DashboardDefinition = {
      ...applied,
      meta: { title: "Pipeline review", description: null },
      parameters: { ...applied.parameters, team: { type: "TEXT", nullable: true, label: "Team" } },
      schedules: { daily: { cron: "0 9 L * *", timeZone: "UTC", parameters: { quarter: { type: "LITERAL", value: "Q4" }, team: { type: "LITERAL", value: null } } } },
    };
    expect(summarizeDefinitionChanges(applied, draft)).toEqual([
      { kind: "Added", label: "Input · Team" },
      { kind: "Added", label: "Schedule · daily (Custom schedule)" },
      { kind: "Changed", label: "Title" },
      { kind: "Changed", label: "Description" },
      { kind: "Removed", label: "Schedule · weekly (Mondays · 8:00 AM)" },
    ]);
  });

  it("falls back to a whole-definition change when no named part differs", () => {
    const draft = { ...applied, schemaVersion: 2 } as unknown as DashboardDefinition;
    expect(summarizeDefinitionChanges(applied, draft)).toEqual([{ kind: "Changed", label: "Definition" }]);
  });
});

describe("definitionChangeSummary", () => {
  it("counts changes by subject and kind in review order", () => {
    expect(definitionChangeSummary([
      { kind: "Added", label: "Chart · Revenue" },
      { kind: "Added", label: "Table · Orders" },
      { kind: "Changed", label: "Query · orders (filters)" },
      { kind: "Changed", label: "Layout" },
      { kind: "Removed", label: "Input · Region" },
    ])).toBe("2 widgets added, 1 query changed, layout changed, 1 input removed");
  });

  it("is empty when nothing changed", () => {
    expect(definitionChangeSummary([])).toBe("");
  });
});
