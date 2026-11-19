import { useState } from "react";
import type { DashboardDefinition, DashboardDraftResponse, DashboardQueryRun } from "@/api/dashboard";
import { validateDashboardResult } from "@/features/dashboard/dashboard-result";
import { DashboardBuilderSurface } from "@/pages/dashboard/builder/DashboardBuilderSurface";

const oldRun = "00000000-0000-7000-8000-000000000001";
const newRun = "00000000-0000-7000-8000-000000000002";
const definition: DashboardDefinition = {
  schemaVersion: 1, meta: { title: "E04 Parameter coherence" },
  parameters: { currency: { type: "TEXT", nullable: false, label: "Currency" } }, variables: {},
  queries: { sales: { type: "SEMANTIC" as const, connectorId: oldRun, query: { schemaVersion: 4, metrics: ["sales"], groupBys: [], filters: [], metricTimeRange: null, orderBy: [], rowLimit: 10 } } },
  widgets: {
    chart: { type: "CHART", queryNames: ["sales"], module: { inputs: { parameters: ["currency"], variables: [] }, source: 'export default defineChart({ render({parameters,queries}) { return {animation:false,title:{text:parameters.currency+" "+queries.sales.rows[0]["metric.sales"]}}; } });' } },
    table: { type: "TABLE", queryName: "sales", columns: [{ field: "metric.sales", header: "Sales" }], module: { inputs: { queries: [], parameters: ["currency"], variables: [] }, source: 'export default defineTable({ render({parameters}) { return {cell({value}) {return {text:parameters.currency+" "+value};}}; } });' } },
  },
  layout: { sections: [{ id: "chart", height: { mode: "SCALED", scale: 0.6 }, root: { type: "WIDGET", id: "chart_node", widgetId: "chart" } }, { id: "table", height: { mode: "SCALED", scale: 0.6 }, root: { type: "WIDGET", id: "table_node", widgetId: "table" } }] },
};
function run(id: string, state: DashboardQueryRun["state"]): DashboardQueryRun {
  return { queryType: "SEMANTIC" as const, queryRunId: id, queryName: "sales", connectorId: oldRun, state, resultAvailable: state === "SUCCEEDED", outputColumnNames: ["metric.sales"], failureCode: state === "FAILED" ? "EXECUTION_FAILED" : null, failureMessage: state === "FAILED" ? "Expected warehouse failure" : null, createdAt: "2026-09-07T00:00:00Z", updatedAt: "2026-09-07T00:00:00Z", startedBy: null, credentialSurface: null, agentApp: null };
}
const result = (id: string, value: string) => validateDashboardResult({ queryType: "SEMANTIC" as const, reportingTimeZone: null, queryRunId: id, columns: [{ name: "metric.sales", logicalType: "DECIMAL", nullable: false, display: null }], rows: [[value]] });
function bundles(currency: string, id: string, revision: number) {
  return Object.fromEntries(["chart", "table"].map(name => [name, { authoringRevision: revision, parameterBindings: { currency }, variables: {}, queryRuns: { sales: id } }]));
}
export function ParameterCoherenceFixture() {
  const [state, setState] = useState<"ready" | "pending" | "failed" | "hydrating" | "success" | "denied">("ready");
  const [currency, setCurrency] = useState("USD");
  const nextReady = state === "hydrating" || state === "success" || state === "denied";
  const draft: DashboardDraftResponse = {
    workspaceId: oldRun, dashboardId: newRun, definition, parameterBindings: { currency }, variables: {}, authoringRevision: currency === "USD" ? 1 : 2,
    targetQueryRuns: { sales: state === "ready" ? oldRun : newRun }, readyResults: nextReady ? bundles("EUR", newRun, 2) : bundles("USD", oldRun, 1),
    recentQueryRuns: [run(oldRun, "SUCCEEDED"), ...(state === "ready" ? [] : [run(newRun, state === "failed" ? "FAILED" : nextReady ? "SUCCEEDED" : "RUNNING")])],
    createdAt: "2026-09-07T00:00:00Z", updatedAt: "2026-09-07T00:00:00Z",
    lastEditedBy: null, lastEditedAt: null, lastEditSurface: null, lastEditAgentApp: null,
  };
  return <div className="h-screen flex flex-col">
    <div className="flex gap-3 p-3" aria-label="Fixture transitions">
      <button onClick={() => setState("failed")}>Fail target</button>
      <button onClick={() => setState("hydrating")}>Succeed before result read</button>
      <button onClick={() => setState("success")}>Deliver result</button>
      <button onClick={() => setState("denied")}>Deny result read</button>
      <output data-testid="coherence-state">{state}</output>
    </div>
    <DashboardBuilderSurface draft={draft} principalId={null} appliedDefinition={definition} deniedRunIds={new Set(state === "denied" ? [newRun] : [])} resultsByRunId={{ [oldRun]: result(oldRun, "100.00"), ...(state === "success" ? { [newRun]: result(newRun, "120.00") } : {}) }}
      onRefresh={() => undefined} onApply={() => undefined} onDiscard={() => undefined} onCancel={() => undefined}
      onParameterSet={(_name, value) => { setCurrency(String(value)); setState("pending"); }} />
  </div>;
}
