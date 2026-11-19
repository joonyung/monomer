import { useRef, useState } from "react";
import type { DashboardDefinition, DashboardJsonObject } from "@/api/dashboard";
import { DashboardLayout } from "@/components/dashboard/DashboardLayout";
import { displayedWidgets, type DashboardWidgetBundle } from "@/pages/dashboard/viewer/screen-state";
import { validateDashboardResult, type NamedDashboardResult } from "@/features/dashboard/dashboard-result";
import { notFound } from "@/lib/navigation";
import dashboardDocument from "../../../../../testkit/dashboard-d10/dashboard.json";
import expectedResults from "./expected-results.json";

const definition = dashboardDocument as DashboardDefinition;
const initialVariables: DashboardJsonObject = Object.fromEntries(Object.entries(definition.variables).map(([name, value]) => [name, value.initial]));
const queryNames = Object.keys(definition.queries);
const runId = (name: string, country: string) => name === "salesByCity" ? `${name}${country}` : name;
const queryRuns = (variables: DashboardJsonObject) => Object.fromEntries(queryNames.map(name => [name, runId(name, String(variables.selectedCountry))]));
const allResults = Object.fromEntries(Object.entries(expectedResults).map(([id, result]) => [id, validateDashboardResult({ queryRunId: id, queryType: "SEMANTIC", reportingTimeZone: null, ...result, columns: result.columns.map(column => ({ ...column, display: null })) })]));
type Snapshot = { variables: DashboardJsonObject; revision: number };
type Request = { values: DashboardJsonObject; displayedInputOrder: number; accepted: boolean };

export default function DashboardD10Fixture() {
  if (!import.meta.env.DEV) notFound();
  const [snapshot, setSnapshot] = useState<Snapshot>({ variables: initialVariables, revision: 0 });
  const current = useRef(snapshot);
  const [available, setAvailable] = useState<Record<string, NamedDashboardResult>>(allResults);
  const [adopted, setAdopted] = useState<DashboardWidgetBundle>({ queryRuns: queryRuns(initialVariables), variables: initialVariables, inputOrder: 0 });
  const [hold, setHold] = useState(false);
  const [failed, setFailed] = useState(false);
  const [requests, setRequests] = useState<Request[]>([]);
  const [completedCountries, setCompletedCountries] = useState(["KR"]);
  const ready = { queryRuns: queryRuns(snapshot.variables), variables: snapshot.variables, inputOrder: snapshot.revision };
  const screen = { inputOrder: snapshot.revision, pending: false, queryRuns: ready.queryRuns };
  const display = displayedWidgets(definition, screen, [], available, { salesChart: adopted }, failed ? new Set([ready.queryRuns.salesByCity]) : new Set(), new Set(), true);
  function submit(values: DashboardJsonObject, displayedInputOrder: number) {
    const accepted = displayedInputOrder === current.current.revision;
    setRequests(previous => [...previous, { values, displayedInputOrder, accepted }]);
    if (!accepted) return;
    const changed = Object.entries(values).some(([name, value]) => current.current.variables[name] !== value);
    if (!changed) return;
    const next = { variables: { ...current.current.variables, ...values }, revision: current.current.revision + 1 };
    current.current = next;
    setSnapshot(next);
    const country = String(next.variables.selectedCountry);
    if (completedCountries.includes(country) || !hold) {
      setAdopted({ queryRuns: queryRuns(next.variables), variables: next.variables, inputOrder: next.revision });
      setCompletedCountries(previous => [...new Set([...previous, country])]);
    }
  }
  function complete(country: string) {
    setCompletedCountries(previous => [...new Set([...previous, country])]);
    if (current.current.variables.selectedCountry === country) {
      setAdopted({ queryRuns: queryRuns(current.current.variables), variables: current.current.variables, inputOrder: current.current.revision });
      setFailed(false);
    }
  }
  return <main className="mx-auto max-w-[1200px] space-y-4 px-6 py-6">
    <h1 className="text-[28px] font-light text-heading">Sales exploration · D10</h1>
    <p className="text-[13px] text-foreground-secondary">Country bar → cities · Back keeps the selected country · Channel and time cover every country</p>
    <p className="text-[12px] text-foreground-secondary">Browser fixture: the real Chart Worker, Host, INPUT, and Viewer display functions with fixed results and simulated per-screen resolve responses. It is not evidence of real API or Semantic execution.</p>
    <DashboardLayout definition={definition} parameters={{}} variables={snapshot.variables} variableRevision={snapshot.revision}
      variableDefinitions={definition.variables} variablesByWidgetId={display.variablesByWidgetId} variableRevisionsByWidgetId={display.variableRevisionsByWidgetId}
      resultsByWidgetId={display.resultsByWidgetId} displayedWidgetIds={display.widgetIds} refreshingWidgetIds={display.refreshingIds}
      failureWidgetIds={display.failureIds} onVariablesSet={submit} />
    <details open className="space-y-2 border-t border-border pt-3 text-[12px]">
      <summary>Test response controls · Not a dashboard definition</summary>
      <div className="flex flex-wrap gap-2 py-2">
        <button className="rounded border border-border px-3 py-2" aria-pressed={hold} onClick={() => setHold(!hold)}>Hold city response</button>
        <button className="rounded border border-border px-3 py-2" onClick={() => complete("US")}>Deliver US result</button>
        <button className="rounded border border-border px-3 py-2" onClick={() => setFailed(true)}>Fail current result</button>
        <button className="rounded border border-border px-3 py-2" onClick={() => {
          setAvailable(previous => ({ ...previous, [adopted.queryRuns.salesByCity]: { ...allResults[adopted.queryRuns.salesByCity], rows: [] } }));
        }}>Empty city result</button>
      </div>
      <output data-testid="d10-current" className="block break-all font-mono">{JSON.stringify(snapshot)}</output>
      <output data-testid="d10-ready" className="block break-all font-mono">{JSON.stringify(adopted)}</output>
      <output data-testid="d10-requests" className="block break-all font-mono">{JSON.stringify(requests)}</output>
    </details>
  </main>;
}
