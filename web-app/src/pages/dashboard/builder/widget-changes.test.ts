import { describe, expect, it } from "vitest";
import type { DashboardDefinition } from "@/api/dashboard";
import { dashboardWidgetChanges } from "./widget-changes";

const definition: DashboardDefinition = {
  schemaVersion: 1, meta: { title: "Report" }, parameters: {}, variables: {}, queries: {},
  widgets: { first: { type: "TEXT", content: "First" }, second: { type: "TEXT", content: "Second" }, footer: { type: "TEXT", content: "Footer" } },
  layout: { sections: [
    { id: "row", height: { mode: "CONTENT" }, root: { type: "STACK", id: "row", direction: "ROW", children: [
      { weight: 1, node: { type: "WIDGET", id: "first", widgetId: "first" } },
      { weight: 1, node: { type: "WIDGET", id: "second", widgetId: "second" } },
    ] } },
    { id: "footer", height: { mode: "CONTENT" }, root: { type: "WIDGET", id: "footer", widgetId: "footer" } },
  ] },
};
function changed(next: DashboardDefinition) {
  const prior = new Map(dashboardWidgetChanges(definition).map((item) => [item.id, item.fingerprint]));
  return dashboardWidgetChanges(next).filter((item) => prior.get(item.id) !== item.fingerprint).map((item) => item.id);
}
describe("Draft widget content comparison", () => {
  it("ignores JSON key order, metadata and equivalent default separation", () => {
    const next = structuredClone(definition);
    next.widgets.first = { content: "First", type: "TEXT" };
    next.meta.title = "Renamed report";
    if (next.layout.sections[0].root.type === "STACK") next.layout.sections[0].root.separation = "GAP";
    expect(changed(next)).toEqual([]);
  });
  it("selects only the edited widget", () => {
    const next = structuredClone(definition);
    next.widgets.second = { type: "TEXT", content: "Edited" };
    expect(changed(next)).toEqual(["second"]);
  });
  it("selects affected layout surfaces without marking a separate section", () => {
    const next = structuredClone(definition);
    if (next.layout.sections[0].root.type === "STACK") next.layout.sections[0].root.children[0].weight = 2;
    expect(changed(next)).toEqual(["first", "second"]);
  });
  it("detects height mode and scale changes only in the affected section", () => {
    const next = structuredClone(definition);
    next.widgets.footer = { type: "CHART", queryNames: [], module: { inputs: { parameters: [], variables: [] }, source: "export default defineChart({})" } };
    next.layout.sections[1].height = { mode: "SCALED", scale: 1 };
    expect(changed(next)).toEqual(["footer"]);
    next.layout.sections[1].height = { mode: "SCALED", scale: 2 };
    expect(changed(next)).toEqual(["footer"]);
  });
  it("fingerprints weightless COLUMN content children", () => {
    const next = structuredClone(definition);
    const root = next.layout.sections[0].root;
    if (root.type !== "STACK") throw new Error("Fixture requires a stack");
    root.direction = "COLUMN";
    root.children.forEach(child => { delete child.weight; });
    expect(changed(next)).toEqual(["first", "second"]);
    expect(dashboardWidgetChanges(structuredClone(next))).toEqual(dashboardWidgetChanges(next));
  });
  it("detects placed arrivals and ignores unplaced definitions", () => {
    const next = structuredClone(definition);
    next.widgets.new = { type: "TEXT", content: "New" };
    expect(changed(next)).toEqual([]);
    next.layout.sections.push({ id: "new", height: { mode: "CONTENT" }, root: { type: "WIDGET", id: "new", widgetId: "new" } });
    expect(changed(next)).toEqual(["new"]);
  });
});
