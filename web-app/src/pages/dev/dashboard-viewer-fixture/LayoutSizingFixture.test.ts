import { writeFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import type { DashboardDefinition, DashboardLayoutNode } from "@/api/dashboard";
import { layoutSizingCases, layoutSizingVariables } from "./LayoutSizingFixture";

const definitions = layoutSizingCases.map((scene): DashboardDefinition => {
  const queries: DashboardDefinition["queries"] = {};
  if (Object.values(scene.widgets).some(widget => widget.type === "TABLE" || widget.type === "CHART" && widget.queryNames.includes("rows"))) {
    queries.rows = { type: "SEMANTIC", connectorId: "00000000-0000-7000-8000-000000000001", query: { schemaVersion: 4, metrics: ["fixture_rows"], groupBys: [], metricTimeRange: null, filters: [], orderBy: [], rowLimit: 100 } };
  }
  return { schemaVersion: 1, meta: { title: scene.id }, parameters: {}, variables: layoutSizingVariables,
    queries, widgets: scene.widgets, layout: { sections: scene.sections } };
});

function allotted(node: DashboardLayoutNode, definition: DashboardDefinition, parent: "ROW" | "COLUMN" | null): boolean {
  if (node.type === "SPACER") return parent === "COLUMN";
  if (node.type === "WIDGET") return ["CHART", "TABLE"].includes(definition.widgets[node.widgetId].type);
  return node.children.some(child => allotted(child.node, definition, node.direction));
}

describe("layout sizing scenes", () => {
  it.each(definitions.map(definition => [definition.meta.title, definition] as const))("%s uses canonical height and weight rules", (_, definition) => {
    const placed: string[] = [];
    const visit = (node: DashboardLayoutNode) => {
      if (node.type === "WIDGET") placed.push(node.widgetId);
      if (node.type !== "STACK") return;
      const weights: number[] = [];
      node.children.forEach(child => {
        expect(child.weight !== undefined).toBe(node.direction === "ROW" || allotted(child.node, definition, node.direction));
        if (child.weight !== undefined) weights.push(child.weight);
        visit(child.node);
      });
      const gcd = (left: number, right: number): number => right ? gcd(right, left % right) : left;
      if (weights.length) expect(weights.reduce(gcd)).toBe(1);
    };
    definition.layout.sections.forEach(section => {
      expect(section.height.mode).toBe(allotted(section.root, definition, null) ? "SCALED" : "CONTENT");
      visit(section.root);
    });
    expect(placed.sort()).toEqual(Object.keys(definition.widgets).sort());
  });
  it("exports complete definitions for the actual owner decoder gate when requested", () => {
    const output = process.env.MONOMER_LAYOUT_SIZING_FIXTURES_OUTPUT;
    if (output) writeFileSync(output, definitions.map(definition => JSON.stringify(definition)).join("\n") + "\n");
    expect(definitions).toHaveLength(16);
  });
});
