import { useRef, useState } from "react";
import type { DashboardDefinition, DashboardJsonObject } from "@/api/dashboard";
import { DashboardLayout } from "@/components/dashboard/DashboardLayout";

const initialVariables = { country: "JP", city: "Tokyo" };
const definition: DashboardDefinition = {
  schemaVersion: 1,
  meta: { title: "Atomic Variable fixture" },
  parameters: {},
  variables: {
    country: { type: "TEXT", nullable: false, initial: "JP", label: "Country" },
    city: { type: "TEXT", nullable: true, initial: "Tokyo", label: "City" },
  },
  queries: { orders: { type: "SEMANTIC" as const,
    connectorId: "00000000-0000-7000-8000-000000000001",
    query: { schemaVersion: 4, metrics: ["order_count"], groupBys: [], metricTimeRange: null, filters: [], orderBy: [], rowLimit: 100 },
  } },
  widgets: {
    country: { type: "INPUT", variableName: "country" },
    city: { type: "INPUT", variableName: "city" },
    chart: {
      type: "CHART", queryNames: ["orders"],
      module: {
        inputs: { parameters: [], variables: ["country", "city"] },
        source: `export default defineChart({
          setup({ chart }) {
            chart.on("click", (event, frame) => {
              frame.setVariables({ country: "KR", city: null });
            });
          },
          render({ variables }) {
            return {
              animation: false,
              title: { text: variables.country + " / " + (variables.city ?? "all cities") },
              grid: { top: 50, bottom: 30, left: 40, right: 20 },
              xAxis: { type: "category", data: ["KR"] }, yAxis: { type: "value", max: 100 },
              series: [{ type: "bar", data: [80], itemStyle: { color: "#2466c3" } }],
            };
          },
        });`,
      },
    },
  },
  layout: { sections: [
    { id: "inputs", height: { mode: "CONTENT" }, root: { type: "STACK", id: "inputStack", direction: "ROW", children: [
      { weight: 1, node: { type: "WIDGET", id: "country", widgetId: "country" } },
      { weight: 1, node: { type: "WIDGET", id: "city", widgetId: "city" } },
    ] } },
    { id: "chart", height: { mode: "SCALED", scale: 1.2 }, root: { type: "WIDGET", id: "chartItem", widgetId: "chart" } },
  ] },
};

export function AtomicVariablesFixture() {
  const [snapshot, setSnapshot] = useState({ variables: initialVariables as DashboardJsonObject, revision: 8 });
  const currentSnapshot = useRef(snapshot);
  currentSnapshot.current = snapshot;
  const [displayed, setDisplayed] = useState(snapshot);
  const [readOnly, setReadOnly] = useState(false);
  const [requests, setRequests] = useState<{ values: DashboardJsonObject; displayedInputOrder: number; accepted: boolean }[]>([]);
  return <section className="space-y-4 py-5">
    <h2 className="text-[22px] text-heading">Change several Variables at once</h2>
    <p className="text-[13px] text-foreground-secondary">Displayed frame input order {displayed.revision} · Current screen input order {snapshot.revision}</p>
    <div className="flex gap-2">
      <button className="h-9 rounded border border-border px-3" onClick={() => {
        const next = { variables: { country: "US", city: "Boston" }, revision: currentSnapshot.current.revision + 1 };
        currentSnapshot.current = next;
        setSnapshot(next);
      }}>Accept newer input</button>
      <button className="h-9 rounded border border-border px-3" aria-pressed={readOnly} onClick={() => setReadOnly(!readOnly)}>Builder read-only</button>
    </div>
    <DashboardLayout
      definition={definition} parameters={{}} variables={snapshot.variables} variableRevision={snapshot.revision}
      variablesByWidgetId={{ chart: displayed.variables }} variableRevisionsByWidgetId={{ chart: displayed.revision }}
      variableDefinitions={definition.variables} resultsByWidgetId={{}} displayedWidgetIds={new Set(["chart"])}
      refreshingWidgetIds={new Set()} failureWidgetIds={new Set()} readOnly={readOnly}
      onVariablesSet={(values, displayedInputOrder) => {
        const current = currentSnapshot.current;
        const accepted = displayedInputOrder === current.revision;
        setRequests((previous) => [...previous, { values, displayedInputOrder, accepted }]);
        if (accepted) {
          const changed = Object.entries(values).some(([name, value]) => current.variables[name] !== value);
          const next = { variables: { ...current.variables, ...values }, revision: current.revision + Number(changed) };
          currentSnapshot.current = next;
          setSnapshot(next);
          setDisplayed(next);
        }
      }}
    />
    <output data-testid="atomic-snapshot" className="block font-mono text-[12px]">{JSON.stringify(snapshot)}</output>
    <output data-testid="atomic-requests" className="block font-mono text-[12px]">{JSON.stringify(requests)}</output>
  </section>;
}
