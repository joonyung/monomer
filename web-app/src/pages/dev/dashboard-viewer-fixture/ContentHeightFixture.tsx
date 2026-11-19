import { useRef, useState } from "react";
import type { DashboardDefinition } from "@/api/dashboard";
import { DashboardLayout } from "@/components/dashboard/DashboardLayout";
import { DashboardCapture } from "@/components/dashboard/capture/DashboardCapture";

const paragraphs = Array.from({ length: 8 }, (_, index) => `Paragraph ${index + 1}. Revenue improved across every region. The analysis combines completed orders and repeat customer activity to explain the change.`).join("\n\n");
const contents: Record<string, string> = {
  short: "### Revenue overview\n\nCompleted orders grew this quarter.",
  long: `# Revenue overview\n\n${paragraphs}\n\n- North improved\n- South remained stable\n\nFinal observation.`,
  empty: "",
  overflow: `## Details\n\n${"LongWord".repeat(40)}\n\n\`\`\`text\n${"column ".repeat(60)}\n\`\`\``,
  image: "## Regional detail\n\n![Regional revenue illustration](/content-height-image.png)\n\nImage caption.",
};

export function ContentHeightFixture() {
  const [content, setContent] = useState("short");
  const [narrow, setNarrow] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const definition: DashboardDefinition = {
    schemaVersion: 1, meta: { title: "Content height" }, parameters: {}, variables: {}, queries: { illustration: { type: "SEMANTIC", connectorId: "00000000-0000-7000-8000-000000000001", query: { schemaVersion: 4, metrics: ["illustration"], groupBys: [], metricTimeRange: null, filters: [], orderBy: [], rowLimit: 1 } } },
    widgets: {
      intro: { type: "TEXT", content: content === "image" ? contents.short : contents[content] },
      scaledText: { type: "TEXT", content: content === "image" ? contents.image : "### Additional context\n\nText uses its content height." },
      secondChart: { type: "CHART", queryNames: ["illustration"], module: { inputs: { parameters: [], variables: [] }, source: 'export default defineChart({ render() { return { animation: false, title: { text: "Revenue", top: "middle", left: "center" } }; } });' } },
      chart: { type: "CHART", queryNames: ["illustration"], module: { inputs: { parameters: [], variables: [] }, source: 'export default defineChart({ render() { return { animation: false, xAxis: { type: "category", data: ["North", "South"] }, yAxis: { type: "value" }, series: [{ type: "bar", data: [63, 42] }] }; } });' } },
    },
    layout: { sections: [
      { id: "intro", height: { mode: "CONTENT" }, root: { type: "WIDGET", id: "intro-node", widgetId: "intro" } },
      { id: "chart", height: { mode: "SCALED", scale: 1.5 }, root: { type: "WIDGET", id: "chart-node", widgetId: "chart" } },
      { id: "scaledText", height: { mode: "SCALED", scale: 1 }, root: { type: "STACK", id: "mixed-column", direction: "COLUMN", children: [
        { node: { type: "WIDGET", id: "scaled-node", widgetId: "scaledText" } },
        { weight: 1, node: { type: "WIDGET", id: "second-chart-node", widgetId: "secondChart" } },
      ] } },
    ] },
  };
  return <main>
    <header className="flex flex-wrap items-center gap-4 p-4">
      <h1 className="text-[22px] font-light">Content height</h1>
      <div className="flex flex-wrap items-center gap-4">
        <label>Text <select aria-label="Text" value={content} onChange={event => setContent(event.target.value)}>{Object.keys(contents).map(value => <option key={value}>{value}</option>)}</select></label>
        <label><input type="checkbox" checked={narrow} onChange={event => setNarrow(event.target.checked)} /> Narrow container</label>
        <DashboardCapture target={root} provenance={{ title: "Content height", description: null, inputs: [], resultsAt: null, source: null }} />
      </div>
    </header>
    <div ref={root} style={{ width: narrow ? "600px" : "100%", maxWidth: "100%" }}>
      <DashboardLayout definition={definition} parameters={{}} variables={{}} variableRevision={0} variableRevisionsByWidgetId={{}} variablesByWidgetId={{}} variableDefinitions={{}} resultsByWidgetId={{}} displayedWidgetIds={new Set(["chart", "secondChart"])} refreshingWidgetIds={new Set()} failureWidgetIds={new Set()} onVariablesSet={() => undefined} />
    </div>
  </main>;
}
