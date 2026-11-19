import { useState } from "react";
import type { DashboardDefinition, DashboardLayoutNode, DashboardVariableDefinition, DashboardWidget } from "@/api/dashboard";
import { DashboardLayout } from "@/components/dashboard/DashboardLayout";
import { validateDashboardResult, type NamedDashboardResult } from "@/features/dashboard/dashboard-result";

const northstarIntro = "## Northstar Commerce\n### June 2026 business overview\n- Compare with **May 2026**; trends cover **January–June**.\n- Select regions and channels, then choose **Apply**.\n- **USD · UTC · Synthetic data**. Gross figures are before returns.";
const explanation = "### Why revenue moved\n\nNorth grew because repeat customers ordered more often after the loyalty launch. South was flat: new-customer volume rose, but average order value fell after the spring promotion.\n\n- Repeat orders: **+12%**\n- New customers: **+6%**\n- AOV South: **−4%**\n\nFinal observation: the gap is driven by retention, not acquisition.";

const barSource = 'export default defineChart({ render() { return { animation: false, grid: { top: 16, right: 16, bottom: 32, left: 40 }, xAxis: { type: "category", data: ["North", "South", "East", "West"] }, yAxis: { type: "value" }, series: [{ type: "bar", data: [63, 42, 51, 37], itemStyle: { color: "#2466c3" } }] }; } });';
const kpiSource = (value: string, label: string) => `export default defineChart({ render({ viewport }) { return { animation: false, title: { text: ${JSON.stringify(value)}, subtext: ${JSON.stringify(label)} + " · " + viewport.height + "px", left: "center", top: "middle", textStyle: { fontSize: 28 } } }; } });`;

const chart = (source: string, title?: string): DashboardWidget => ({ type: "CHART", queryNames: ["rows"], ...(title ? { title } : {}), module: { inputs: { parameters: [], variables: [] }, source } }) as DashboardWidget;
const text = (content: string): DashboardWidget => ({ type: "TEXT", content }) as DashboardWidget;
const input = (variableName: string): DashboardWidget => ({ type: "INPUT", variableName, control: { options: [{ value: "NA", label: "North America" }, { value: "EU", label: "Europe" }, { value: "APAC", label: "Asia Pacific" }] } }) as DashboardWidget;
const setInput = (variableName: string): DashboardWidget => ({ type: "INPUT", variableName, control: { options: [{ value: "NA", label: "North America" }, { value: "EU", label: "Europe" }, { value: "APAC", label: "Asia Pacific" }, { value: "LATAM", label: "Latin America" }] } }) as DashboardWidget;
const table = (title?: string): DashboardWidget => ({ type: "TABLE", queryName: "rows", ...(title ? { title } : {}), columns: [{ field: "dimension.product", header: "Product" }, { field: "metric.revenue", header: "Revenue" }] }) as DashboardWidget;

const widgetNode = (id: string): DashboardLayoutNode => ({ type: "WIDGET", id: `${id}-node`, widgetId: id });
const stack = (id: string, direction: "ROW" | "COLUMN", children: [number | null, DashboardLayoutNode][], separation?: "DIVIDER"): DashboardLayoutNode =>
  ({ type: "STACK", id, direction, ...(separation ? { separation } : {}), children: children.map(([weight, node]) => ({ ...(weight === null ? {} : { weight }), node })) }) as DashboardLayoutNode;

const rowsResult = (count: number) => validateDashboardResult({
  queryType: "SEMANTIC", reportingTimeZone: null, queryRunId: `00000000-0000-7000-8000-0000000000${String(count).padStart(2, "0")}`,
  columns: [{ name: "dimension.product", logicalType: "TEXT", nullable: false, display: null }, { name: "metric.revenue", logicalType: "NUMBER", nullable: false, display: null }],
  rows: Array.from({ length: count }, (_, index) => [`Product ${index + 1}`, String(1000 - index * 7)]),
});

export const layoutSizingVariables: Record<string, DashboardVariableDefinition> = {
  region: { type: "TEXT", nullable: false, initial: "NA", label: "Region" },
  channel: { type: "TEXT", nullable: true, initial: null, label: "Channel", description: "Leave empty to include every channel." },
  segments: { type: "TEXT_SET", nullable: false, initial: ["NA"], label: "Segments" },
};

interface SizingCase {
  id: string;
  caption: string;
  widgets: Record<string, DashboardWidget>;
  sections: DashboardDefinition["layout"]["sections"];
  results?: Record<string, Record<string, NamedDashboardResult>>;
  failures?: string[];
  errors?: Record<string, string>;
  actions?: boolean;
}

export const layoutSizingCases: SizingCase[] = [
  {
    id: "explain-chart",
    caption: "A · SCALED 1.0 ROW [TEXT 1 : CHART(title) 2] — DSL doc's explanation + chart pattern",
    widgets: { why: text(explanation), trend: chart(barSource, "Revenue by region") },
    sections: [{ id: "a", height: { mode: "SCALED", scale: 1 }, root: stack("a-row", "ROW", [[1, widgetNode("why")], [2, widgetNode("trend")]]) }],
  },
  {
    id: "context-row",
    caption: "B · Northstar context: CONTENT ROW [TEXT 3 : INPUT 1 : INPUT 1]",
    widgets: { intro: text(northstarIntro), region: input("region"), channel: input("channel") },
    sections: [{ id: "b", height: { mode: "CONTENT" }, root: stack("b-row", "ROW", [[3, widgetNode("intro")], [1, widgetNode("region")], [1, widgetNode("channel")]]) }],
  },
  {
    id: "kpi",
    caption: "C · KPI row as CHART: SCALED 0.5 vs 0.9 ROW/DIVIDER [KPI ×3] (subtext shows the viewport height the source receives)",
    widgets: {
      k1: chart(kpiSource("$1.24M", "Net revenue")), k2: chart(kpiSource("18,240", "Orders")), k3: chart(kpiSource("$68.10", "AOV")),
      m1: chart(kpiSource("$1.24M", "Net revenue")), m2: chart(kpiSource("18,240", "Orders")), m3: chart(kpiSource("$68.10", "AOV")),
    },
    sections: [
      { id: "c1", height: { mode: "SCALED", scale: 0.5 }, root: stack("c1-row", "ROW", [[1, widgetNode("k1")], [1, widgetNode("k2")], [1, widgetNode("k3")]], "DIVIDER") },
      { id: "c2", height: { mode: "SCALED", scale: 0.9 }, root: stack("c2-row", "ROW", [[1, widgetNode("m1")], [1, widgetNode("m2")], [1, widgetNode("m3")]], "DIVIDER") },
    ],
  },
  {
    id: "column-input",
    caption: "D · COLUMN [INPUT : CHART 1] at SCALED 1.0 and 2.0 — the input retains its content height",
    widgets: { r1: input("region"), c1: chart(barSource), r2: input("region"), c2: chart(barSource) },
    sections: [
      { id: "d1", height: { mode: "SCALED", scale: 1 }, root: stack("d1-col", "COLUMN", [[null, widgetNode("r1")], [1, widgetNode("c1")]]) },
      { id: "d2", height: { mode: "SCALED", scale: 2 }, root: stack("d2-col", "COLUMN", [[null, widgetNode("r2")], [1, widgetNode("c2")]]) },
    ],
  },
  {
    id: "table",
    caption: "E · TABLE: 3 rows at SCALED 1.5 vs 40 rows at SCALED 0.5 (titled)",
    widgets: { few: table("Top products"), many: table("All products") },
    sections: [
      { id: "e1", height: { mode: "SCALED", scale: 1.5 }, root: widgetNode("few") },
      { id: "e2", height: { mode: "SCALED", scale: 0.5 }, root: widgetNode("many") },
    ],
    results: { few: { rows: rowsResult(3) }, many: { rows: rowsResult(40) } },
  },
  {
    id: "small",
    caption: "F · Titled chart at SCALED 1.0, and a failed chart at SCALED 0.5",
    widgets: { titled: chart(barSource, "Orders by region"), failed: chart(barSource, "Orders by region") },
    sections: [
      { id: "f1", height: { mode: "SCALED", scale: 1 }, root: widgetNode("titled") },
      { id: "f2", height: { mode: "SCALED", scale: 0.5 }, root: widgetNode("failed") },
    ],
    failures: ["failed"],
  },
  {
    id: "filter-bar",
    caption: "G · Filter bar as CONTENT: ROW [INPUT 1 : INPUT 1 : INPUT(set) 1]",
    widgets: { g1: input("region"), g2: input("channel"), g3: setInput("segments") },
    sections: [{ id: "g", height: { mode: "CONTENT" }, root: stack("g-row", "ROW", [[1, widgetNode("g1")], [1, widgetNode("g2")], [1, widgetNode("g3")]]) } as DashboardDefinition["layout"]["sections"][number]],
  },
  {
    id: "context-content",
    caption: "H · Northstar context as CONTENT: ROW [TEXT 3 : INPUT 1 : INPUT 1]",
    widgets: { h1: text(northstarIntro), h2: input("region"), h3: input("channel") },
    sections: [{ id: "h", height: { mode: "CONTENT" }, root: stack("h-row", "ROW", [[3, widgetNode("h1")], [1, widgetNode("h2")], [1, widgetNode("h3")]]) } as DashboardDefinition["layout"]["sections"][number]],
  },
  {
    id: "side-panel",
    caption: "I · Control panel beside a chart: SCALED 1.2 ROW [CHART 3 : COLUMN/DIVIDER [INPUT, INPUT, TEXT]]",
    widgets: { i1: chart(barSource, "Revenue by region"), i2: input("region"), i3: input("channel"), i4: text("Values update after **Apply**.") },
    sections: [{ id: "i", height: { mode: "SCALED", scale: 1.2 }, root: stack("i-row", "ROW", [[3, widgetNode("i1")], [1, stack("i-col", "COLUMN", [[null, widgetNode("i2")], [null, widgetNode("i3")], [null, widgetNode("i4")]], "DIVIDER")]]) }],
  },
  {
    id: "kpi-five",
    caption: "J · Five KPIs: SCALED 0.5 ROW/DIVIDER [KPI ×5]",
    widgets: Object.fromEntries(["$1.24M", "18,240", "$68.10", "41.2%", "3,120"].map((value, index) => [`j${index}`, chart(kpiSource(value, `KPI ${index + 1}`))])),
    sections: [{ id: "j", height: { mode: "SCALED", scale: 0.5 }, root: stack("j-row", "ROW", [0, 1, 2, 3, 4].map((index) => [1, widgetNode(`j${index}`)] as [number, DashboardLayoutNode]), "DIVIDER") }],
  },
  {
    id: "column-two-charts",
    caption: "K · SCALED 1.0 COLUMN [CHART 1 : CHART 4]",
    widgets: { k1c: chart(barSource), k2c: chart(barSource) },
    sections: [{ id: "k", height: { mode: "SCALED", scale: 1 }, root: stack("k-col", "COLUMN", [[1, widgetNode("k1c")], [4, widgetNode("k2c")]]) }],
  },
  {
    id: "row-nested-column",
    caption: "L · SCALED 1.0 ROW [CHART 1 : COLUMN [CHART 1 : CHART 1] 1]",
    widgets: { l1: chart(barSource), l2: chart(barSource), l3: chart(barSource) },
    sections: [{ id: "l", height: { mode: "SCALED", scale: 1 }, root: stack("l-row", "ROW", [[1, widgetNode("l1")], [1, stack("l-col", "COLUMN", [[1, widgetNode("l2")], [1, widgetNode("l3")]])]]) }],
  },
  {
    id: "column-text-charts",
    caption: "M · SCALED 1.0 COLUMN [TEXT : CHART 1 : CHART 2]",
    widgets: { m1t: text(explanation), m2c: chart(barSource), m3c: chart(barSource) },
    sections: [{ id: "m", height: { mode: "SCALED", scale: 1 }, root: stack("m-col", "COLUMN", [[null, widgetNode("m1t")], [1, widgetNode("m2c")], [2, widgetNode("m3c")]]) }],
  },
  {
    id: "first-failure",
    caption: "N · First query failure in a titled CHART at SCALED 0.5, with Retry and Details",
    widgets: { nfail: ({ type: "CHART", queryNames: ["rows"], title: "Orders by region", module: { inputs: { parameters: [], variables: [] }, source: barSource } }) as DashboardWidget },
    sections: [{ id: "n", height: { mode: "SCALED", scale: 0.5 }, root: widgetNode("nfail") }],
    failures: ["nfail"],
    errors: { nfail: "The warehouse query timed out after 120 seconds. Check the connector and try again." },
    actions: true,
  },
  {
    id: "row-chart-input",
    caption: "O · SCALED 2.0 ROW [CHART 1 : INPUT 1]",
    widgets: { o1: chart(barSource), o2: input("region") },
    sections: [{ id: "o", height: { mode: "SCALED", scale: 2 }, root: stack("o-row", "ROW", [[1, widgetNode("o1")], [1, widgetNode("o2")]]) }],
  },
  {
    id: "worker-failure",
    caption: "P · Chart worker first failure in a titled CHART at SCALED 0.5",
    widgets: { pfail: chart('export default defineChart({ render() { throw new Error("Chart source failed: the revenue series is missing from the result."); } });', "Revenue by region") },
    sections: [{ id: "p", height: { mode: "SCALED", scale: 0.5 }, root: widgetNode("pfail") }],
  },
];

export function LayoutSizingFixture() {
  const [containerWidth, setContainerWidth] = useState(0);
  const [queryState, setQueryState] = useState("ready");
  const [lastAction, setLastAction] = useState("None");
  return <main className="space-y-10 bg-background px-5 py-6 sm:px-8">
    <div className="flex flex-wrap items-center gap-4">
      <label>Container width <input aria-label="Container width" type="number" min="0" value={containerWidth} onChange={event => setContainerWidth(Number(event.target.value))} /></label>
      <label>Query state <select aria-label="Query state" value={queryState} onChange={event => setQueryState(event.target.value)}><option>ready</option><option>loading</option><option>failed</option></select></label>
      <output aria-label="Last action">{lastAction}</output>
    </div>
    {layoutSizingCases.map((probe) => {
      const readyResults = { ...Object.fromEntries(Object.entries(probe.widgets).filter(([id, widget]) => widget.type === "CHART" && !probe.failures?.includes(id)).map(([id]) => [id, { rows: rowsResult(3) }])), ...probe.results };
      const results = queryState === "ready" ? readyResults : {};
      return <section key={probe.id} data-sizing-case={probe.id} className="space-y-2" style={{ width: containerWidth || undefined, maxWidth: "100%" }}>
        <h2 className="type-control text-heading">{probe.caption}</h2>
        <DashboardLayout
          definition={{ widgets: probe.widgets, layout: { sections: probe.sections } }}
          parameters={{}}
          variables={{ region: "NA", channel: null, segments: ["NA"] }}
          variableRevision={0}
          variableRevisionsByWidgetId={{}}
          variablesByWidgetId={{}}
          variableDefinitions={layoutSizingVariables}
          resultsByWidgetId={results}
          displayedWidgetIds={new Set(Object.keys(results))}
          refreshingWidgetIds={new Set()}
          failureWidgetIds={new Set(queryState === "failed" ? Object.keys(readyResults) : probe.failures ?? [])}
          errorMessagesByWidgetId={probe.errors}
          widgetActions={probe.actions ? { pending: false, onRefresh: () => setLastAction("Retry"), onShowRuns: () => setLastAction("Details") } : undefined}
          onVariablesSet={() => undefined}
        />
      </section>;
    })}
  </main>;
}
