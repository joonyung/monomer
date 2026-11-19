import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import type { DashboardDefinition } from "@/api/dashboard";
import { DashboardLayout } from "./DashboardLayout";

const definition: DashboardDefinition = {
  schemaVersion: 1,
  meta: { title: "Separation fixture" },
  parameters: {},
  variables: {},
  queries: {},
  widgets: {
    first: { type: "TEXT", content: "First" },
    second: { type: "TEXT", content: "Second" },
    third: { type: "TEXT", content: "Third" },
    fourth: { type: "TEXT", content: "Fourth" },
  },
  layout: {
    sections: [
      {
        id: "divided",
        height: { mode: "CONTENT" },
        root: {
          type: "STACK",
          id: "dividedRow",
          direction: "ROW",
          separation: "DIVIDER",
          children: [
            { weight: 1, node: { type: "WIDGET", id: "firstItem", widgetId: "first" } },
            { weight: 1, node: { type: "WIDGET", id: "secondItem", widgetId: "second" } },
          ],
        },
      },
      {
        id: "gapped",
        height: { mode: "CONTENT" },
        root: {
          type: "STACK",
          id: "gappedRow",
          direction: "ROW",
          children: [
            { weight: 1, node: { type: "WIDGET", id: "thirdItem", widgetId: "third" } },
            { weight: 1, node: { type: "WIDGET", id: "fourthItem", widgetId: "fourth" } },
          ],
        },
      },
    ],
  },
};

describe("DashboardLayout separation", () => {
  it("renders DIVIDER as one surface and omitted separation as GAP", () => {
    const markup = renderToStaticMarkup(
      <DashboardLayout
        definition={definition}
        parameters={{}}
        variables={{}}
        variableRevision={0} variableRevisionsByWidgetId={{}} variablesByWidgetId={{}}
        variableDefinitions={{}}
        resultsByWidgetId={{}}
        displayedWidgetIds={new Set()}
        refreshingWidgetIds={new Set()}
        failureWidgetIds={new Set()}
        onVariablesSet={() => undefined}
      />,
    );

    expect(markup).toContain('data-layout-separation="DIVIDER"');
    expect(markup).toContain('data-layout-separation="GAP"');
    expect(markup.match(/dashboard-layout-divided/g)).toHaveLength(1);
    expect(markup.match(/dashboard-layout-surface/g)).toHaveLength(2);
  });
});

describe.each(["ROW", "COLUMN"] as const)("DashboardLayout nested %s groups", (direction) => {
  it.each([
    ["GAP", "GAP"], ["GAP", "DIVIDER"], ["DIVIDER", "GAP"], ["DIVIDER", "DIVIDER"],
  ] as const)("preserves parent-relative weights for %s / %s", (outerSeparation, innerSeparation) => {
    const nestedDefinition: DashboardDefinition = {
      ...definition,
      queries: { rows: { type: "SEMANTIC", connectorId: "00000000-0000-7000-8000-000000000001", query: { schemaVersion: 4, metrics: ["fixture_rows"], groupBys: [], metricTimeRange: null, filters: [], orderBy: [], rowLimit: 100 } } },
      widgets: Object.fromEntries(["first", "second", "third"].map(id => [id, { type: "CHART", queryNames: ["rows"], module: { inputs: { parameters: [], variables: [] }, source: "export default defineChart({ render() { return {}; } })" } }])),
      layout: { sections: [{
        id: "nested", height: { mode: "SCALED", scale: 2 },
        root: {
          type: "STACK", id: "outer", direction, separation: outerSeparation,
          children: [
            { weight: 3, node: {
              type: "STACK", id: "inner", direction, separation: innerSeparation,
              children: [
                { weight: 1, node: { type: "WIDGET", id: "firstItem", widgetId: "first" } },
                { weight: 2, node: { type: "WIDGET", id: "secondItem", widgetId: "second" } },
              ],
            } },
            { weight: 2, node: { type: "WIDGET", id: "thirdItem", widgetId: "third" } },
          ],
        },
      }] },
    };
    const originalDefinition = JSON.stringify(nestedDefinition);
    const markup = renderToStaticMarkup(
      <DashboardLayout definition={nestedDefinition} parameters={{}} variables={{}} variableRevision={0} variableRevisionsByWidgetId={{}} variablesByWidgetId={{}} variableDefinitions={{}} resultsByWidgetId={{}} displayedWidgetIds={new Set()} refreshingWidgetIds={new Set()} failureWidgetIds={new Set()} onVariablesSet={() => undefined} />,
    );


    expect(markup.match(/data-layout-direction=/g)).toHaveLength(2);
    expect(markup.match(/dashboard-layout-child /g)).toHaveLength(4);
    expect(markup).toContain("flex:3 1 0px");
    expect(markup).toContain("flex:2 1 0px");
    expect(markup).not.toContain("fr;");
    expect(markup.indexOf(`data-layout-separation="${outerSeparation}"`)).toBeLessThan(markup.lastIndexOf(`data-layout-separation="${innerSeparation}"`));
    expect(markup.match(/dashboard-layout-divided/g) ?? []).toHaveLength(
      Number(outerSeparation === "DIVIDER") + Number(innerSeparation === "DIVIDER"),
    );
    expect(markup.match(/dashboard-layout-surface/g) ?? []).toHaveLength(
      Number(outerSeparation === "GAP") + 2 * Number(innerSeparation === "GAP"),
    );
    expect(JSON.stringify(nestedDefinition)).toBe(originalDefinition);
  });
});

describe("DashboardLayout widget references", () => {
  it("uses widgetId independently from node identity and preserves weighted spacers and section height", () => {
    const spacedDefinition: DashboardDefinition = {
      ...definition,
      widgets: { first: definition.widgets.first },
      layout: { sections: [{
        id: "spaced", height: { mode: "SCALED", scale: 1.5 },
        root: {
          type: "STACK", id: "root", direction: "COLUMN",
          children: [
            { node: { type: "WIDGET", id: "placement", widgetId: "first" } },
            { weight: 1, node: { type: "SPACER", id: "space" } },
          ],
        },
      }] },
    };
    const markup = renderToStaticMarkup(
      <DashboardLayout definition={spacedDefinition} parameters={{}} variables={{}} variableRevision={0} variableRevisionsByWidgetId={{}} variablesByWidgetId={{}} variableDefinitions={{}} resultsByWidgetId={{}} displayedWidgetIds={new Set()} refreshingWidgetIds={new Set()} failureWidgetIds={new Set()} onVariablesSet={() => undefined} />,
    );
    expect(markup).toContain("First");
    expect(markup).not.toContain("could not be found");
    expect(markup).toContain("grid-template-rows:minmax(450px, auto)");
    expect(markup).toContain('data-layout-spacer-child=""');
    expect(markup).toContain("flex:none");
    expect(markup).toContain("flex:1 1 0px");
    expect(markup).toContain('aria-hidden="true" class="dashboard-layout-spacer"');
    expect(markup.match(/dashboard-layout-surface/g)).toHaveLength(1);
  });
});

it("passes the precise loading state through the layout", () => {
  const table: DashboardDefinition = { ...definition, widgets: { ...definition.widgets, first: { type: "TABLE", queryName: "orders", columns: [] } } };
  table.layout = structuredClone(table.layout); table.layout.sections[0].height = { mode: "SCALED", scale: 1 };
  const markup = renderToStaticMarkup(<DashboardLayout definition={table} parameters={{}} variables={{}} variableDefinitions={{}} variableRevision={0} variableRevisionsByWidgetId={{}} variablesByWidgetId={{}} resultsByWidgetId={{}} displayedWidgetIds={new Set()} refreshingWidgetIds={new Set(["first"])} failureWidgetIds={new Set()} loadingMessagesByWidgetId={{ first: "Running query…" }} onVariablesSet={() => undefined} />);
  expect(markup).toContain("Running query…");
  expect(markup).not.toContain("Loading new results");
});
