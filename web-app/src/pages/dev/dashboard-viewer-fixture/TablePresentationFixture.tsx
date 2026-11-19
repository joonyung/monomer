import { useMemo, useState } from "react";
import type { DashboardDefinition, DashboardJsonObject, DashboardWidget } from "@/api/dashboard";
import { DashboardLayout } from "@/components/dashboard/DashboardLayout";
import type { DashboardWidgetMetricsTarget } from "@/components/dashboard/DashboardWidgetRenderer";
import { WidgetMetricsDialog } from "@/components/dashboard/WidgetMetricsDialog";
import { dashboardQueryTypes } from "@/features/dashboard/widget-metrics";
import { validateDashboardResult, type NamedDashboardResult } from "@/features/dashboard/dashboard-result";
import { displayedWidgets, type DashboardWidgetBundle } from "@/pages/dashboard/viewer/screen-state";
import customerWidget from "../../../../../docs/research/examples/table-widget/customer-table.widget.json";

const table = customerWidget as Extract<DashboardWidget, { type: "TABLE"; columns: unknown }>;
const customerColumns = [
  { name: "dimension.customers.customer_id", logicalType: "TEXT", nullable: false, display: null },
  { name: "dimension.customers.customer_name", logicalType: "TEXT", nullable: true, display: null },
  { name: "metric.revenue", logicalType: "DECIMAL", nullable: true, display: null },
  { name: "metric.margin_rate", logicalType: "DECIMAL", nullable: true, display: null },
  { name: "metric.target_attainment", logicalType: "DECIMAL", nullable: true, display: null },
  { name: "metric.order_count", logicalType: "INTEGER", nullable: true, display: null },
];
const customerRows = [
  ["acme", "Acme", "74520.00", "0.142", "0.83", "182"],
  ["globex", "Globex", "18340.50", "0.23", "1.13", "68"],
  ["precision", "Precision", "9007199254740993.01", "0.15", "0.97", "9007199254740993"],
  ["precision2", "Precision 2", "9007199254740993.02", "0.14", "0.50", "19"],
  ["negative", "Negative", "-10.50", "-0.03", "-0.1", "0"],
  ["zero", "Zero", "0.00", "0", "0", "0"],
  ["equal", "Equal", "74520.000", "0.21", "1", "182"],
  ["missing", "No observations", null, null, null, null],
  ["empty", "", "2", "0.20", "0.5", "1"],
];
const zeroExpansionColumns = Array.from({ length: 256 }, (_, index) => ({
  name: `metric.zero_${index}`, logicalType: "DECIMAL", nullable: false, display: { style: "CURRENCY" as const, currency: "USD" as const, decimals: 2 },
}));
// Every cell needs the widest zero expansion, so each 256-column Worker batch formats the full 2,048-cell budget.
function zeroExpansionCustomers(id: string): NamedDashboardResult {
  return validateDashboardResult({ queryType: "SEMANTIC", reportingTimeZone: null, queryRunId: id, columns: zeroExpansionColumns,
    rows: Array.from({ length: 40 }, () => zeroExpansionColumns.map(() => "0.000000000000000000001")) });
}
function customers(id: string, many: boolean): NamedDashboardResult {
  return validateDashboardResult({ queryType: "SEMANTIC" as const, reportingTimeZone: null, queryRunId: id, columns: customerColumns,
    rows: many ? Array.from({ length: 2000 }, (_, index) => [String(index), `Customer ${index}`, String(index), "0.14", "0.8", "20"]) : customerRows });
}
function daily(id: string, changed = false, duplicate = false, sql = false): NamedDashboardResult {
  const rows = ["2026-09-01", "2026-09-02", "2026-09-03"].map((day, index) => ["acme", day, (changed ? [8, 2, 9] : [2, 9, 5])[index].toString()]);
  if (duplicate) rows.push(rows[0]);
  return validateDashboardResult({ queryType: sql ? "SQL" : "SEMANTIC", reportingTimeZone: null, queryRunId: id, columns: [customerColumns[0], { name: "metric_time.day", logicalType: "DATE", nullable: false, display: null }, customerColumns[2]], rows });
}

const sourceVariants: Record<string, string | undefined> = {
  demo: table.module!.source,
  raw: undefined,
  compile: "export default defineTable({ render( });",
  render: 'export default defineTable({render(){throw new Error("fixture render failure")}});',
  cell: 'export default defineTable({render(){return {cell({field,value,rowIndex}) {if(field === "metric.revenue" && rowIndex === 0) throw new Error("fixture cell failure"); return {text: value === null ? "None" : String(value)}}}}});',
  row: 'export default defineTable({render(){return {row(){throw new Error("fixture row failure")},cell({value}){return {text:value === null ? "None" : String(value)}}}}});',
  timeout: "export default defineTable({render(){while(true){} }});",
  detachedPrepare: "export default defineTable({render(){Promise.resolve().then(()=>{while(true){}});return {};}});",
  detachedCell: "export default defineTable({render(){return {cell(){Promise.resolve().then(()=>{while(true){}});return {text:\"finished\"};}};}});",
  cellTimeout: "export default defineTable({render(){return {cell(){while(true){}}}}});",
  oversized: 'export default defineTable({render(){return {cell(){return {text:"x".repeat(4097)}}}}});',
  batch: 'export default defineTable({render(){return {cell(){return {text:"x".repeat(4096),tooltip:"y".repeat(8192)}}}}});',
  malicious: 'export default defineTable({render(){return {cell(){return {text:"blocked graphic",graphic:{svg:\'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><script>alert(1)</script></svg>\'}}}}}});',
  metadata: 'export default defineTable({render(frame){return {cell({field,value,rowIndex}){return {text:rowIndex+" / "+frame.queries[frame.rowQueryName].columns.find(c=>c.name===field).logicalType+" / "+String(value)}}}}});',
  styleOnly: 'export default defineTable({render(){return {cell({field,value,displayText}){if(!field.startsWith("metric.") || value === null) return undefined; return {text:displayText,background:value.startsWith("-") ? "#FBE4D5" : "#EAF0E5"}}}}});',
  intl: 'export default defineTable({render(frame){const percent=new Intl.NumberFormat(frame.locale,{style:"percent",maximumFractionDigits:1}); return {cell({field,row,displayText}){const margin=row["metric.margin_rate"]; if(field !== "metric.revenue" || margin === null) return undefined; return {text:displayText+" · "+percent.format(Number(margin))+" margin"}}}}});',
};

export function TablePresentationFixture() {
  const [variant, setVariant] = useState("demo");
  const [narrowColumns, setNarrowColumns] = useState(false);
  const [many, setMany] = useState(false);
  const [duplicate, setDuplicate] = useState(false);
  const [missingField, setMissingField] = useState(false);
  const [empty, setEmpty] = useState(false);
  const [zeroExpansion, setZeroExpansion] = useState(false);
  const [queryFailure, setQueryFailure] = useState(false);
  const [queryDependent, setQueryDependent] = useState(false);
  const [sqlAuxiliary, setSqlAuxiliary] = useState(false);
  const [retainedSql, setRetainedSql] = useState(false);
  const [reader, setReader] = useState(false);
  const [queryTarget, setQueryTarget] = useState<DashboardWidgetMetricsTarget | null>(null);
  const [pending, setPending] = useState(false);
  const [primaryDone, setPrimaryDone] = useState(false);
  const [revision, setRevision] = useState(0);
  const [variables, setVariables] = useState<DashboardJsonObject>({ marginFloor: "0.15" });
  const [ready, setReady] = useState<DashboardWidgetBundle>({ inputOrder: 0, variables: { marginFloor: "0.15" }, queryRuns: { customers: "customers-0", revenueDaily: "daily-0" } });
  const definition = useMemo<DashboardDefinition>(() => ({
    schemaVersion: 1, meta: { title: "Customer performance" }, parameters: {},
    variables: { marginFloor: { type: "NUMBER", nullable: false, initial: "0.15", label: "Margin floor" } },
    queries: {
      customers: { type: "SEMANTIC", connectorId: "00000000-0000-7000-8000-000000000001", query: { schemaVersion: 4, metrics: ["revenue"], groupBys: [], metricTimeRange: null, filters: [], orderBy: [], rowLimit: 2000 } },
      revenueDaily: sqlAuxiliary
        ? { type: "SQL", connectorId: "00000000-0000-7000-8000-000000000001", sql: 'SELECT customer_id AS "dimension.customers.customer_id", order_date AS "metric_time.day", revenue AS "metric.revenue" FROM analytics.orders WHERE margin_rate >= {{minimum}}', bindings: { minimum: { kind: "VARIABLE", name: "marginFloor" } }, columns: ["dimension.customers.customer_id", "metric_time.day", "metric.revenue"], rowLimit: 2000 }
        : { type: "SEMANTIC", connectorId: "00000000-0000-7000-8000-000000000001", query: { schemaVersion: 4, metrics: ["revenue"], groupBys: [], metricTimeRange: null, filters: [], orderBy: [], rowLimit: 2000 } },
    },
    widgets: {
      marginFloor: { type: "INPUT", variableName: "marginFloor", control: { options: [{ value: "0.1", label: "10%" }, { value: "0.15", label: "15%" }, { value: "0.2", label: "20%" }] } },
      customers: { ...table, columns: zeroExpansion ? zeroExpansionColumns.map((column) => ({ field: column.name, width: 200 })) : missingField ? [{ field: "metric.missing" }] : narrowColumns ? table.columns.slice(0, 2) : table.columns, module: sourceVariants[variant] === undefined ? undefined : { ...table.module!, source: sourceVariants[variant]! } },
    },
    layout: { sections: [
      { id: "threshold", height: { mode: "CONTENT" }, root: { type: "WIDGET", id: "threshold_input", widgetId: "marginFloor" } },
      { id: "customers", height: { mode: "SCALED", scale: 1.9 }, root: { type: "WIDGET", id: "customer_table", widgetId: "customers" } },
    ] },
  }), [variant, missingField, narrowColumns, sqlAuxiliary, zeroExpansion]);
  const resultMap = useMemo(() => {
    const primary = zeroExpansion ? zeroExpansionCustomers(ready.queryRuns.customers) : customers(ready.queryRuns.customers, many);
    return { [ready.queryRuns.customers]: empty ? { ...primary, rows: [] } : primary, [ready.queryRuns.revenueDaily]: daily(ready.queryRuns.revenueDaily, ready.queryRuns.revenueDaily !== "daily-0", duplicate, sqlAuxiliary || retainedSql) };
  }, [ready, many, duplicate, empty, sqlAuxiliary, retainedSql, zeroExpansion]);
  const screen = { inputOrder: revision, pending: false, queryRuns: pending ? { customers: "customers-next", revenueDaily: "daily-next" } : ready.queryRuns };
  const display = displayedWidgets(definition, screen, [], resultMap, { customers: ready }, new Set(queryFailure ? [screen.queryRuns.revenueDaily] : []), new Set(), true);
  return <section className="space-y-4 py-5">
    <h2 className="text-[22px] font-light text-heading">Customer performance · TABLE presentation</h2>
    <p className="text-[13px] text-foreground-secondary">Fixed-result fixture · uses the real INPUT, TABLE Host, and Worker.</p>
    <div className="flex flex-wrap items-center gap-3 text-[12px]">
      <label>Validation source <select aria-label="Validation source" value={variant} onChange={(event) => setVariant(event.target.value)}>{Object.keys(sourceVariants).map((key) => <option key={key}>{key}</option>)}</select></label>
      {[["Narrow columns", narrowColumns, setNarrowColumns], ["2,000 rows", many, setMany], ["Duplicate observation", duplicate, setDuplicate], ["Missing field", missingField, setMissingField], ["Empty result", empty, setEmpty], ["Zero-expansion wide result", zeroExpansion, setZeroExpansion], ["Auxiliary query failure", queryFailure, setQueryFailure], ["Variable also drives queries", queryDependent, setQueryDependent], ["SQL auxiliary query", sqlAuxiliary, setSqlAuxiliary], ["Retained SQL result", retainedSql, setRetainedSql], ["Page reader", reader, setReader]].map(([label, checked, setter]) => <label key={String(label)} className="flex items-center gap-1"><input type="checkbox" checked={checked as boolean} onChange={(event) => (setter as (value: boolean) => void)(event.target.checked)} />{String(label)}</label>)}
      <button className="h-8 border border-border px-2" disabled={!pending} onClick={() => setPrimaryDone(true)}>Complete primary query</button>
      <button className="h-8 border border-border px-2" disabled={!pending || !primaryDone} onClick={() => {
        setReady({ variables, inputOrder: revision, queryRuns: { customers: `customers-${revision}`, revenueDaily: `daily-${revision}` } });
        setPending(false); setPrimaryDone(false);
      }}>Complete auxiliary query</button>
    </div>
    <output data-testid="table-ready-snapshot" className="block break-all text-[12px] text-foreground-secondary">{JSON.stringify({ current: variables.marginFloor, displayed: ready.variables.marginFloor, revision, readyRevision: ready.inputOrder, pending, primaryDone })}</output>
    <DashboardLayout definition={definition} parameters={{}} variables={variables} variableRevision={revision}
      queryTypes={dashboardQueryTypes(definition.queries)}
      widgetActions={reader ? undefined : { pending: false, onShowSql: setQueryTarget, onShowMetrics: setQueryTarget }}
      variableDefinitions={definition.variables} variablesByWidgetId={display.variablesByWidgetId} variableRevisionsByWidgetId={display.variableRevisionsByWidgetId}
      resultsByWidgetId={display.resultsByWidgetId} displayedWidgetIds={display.widgetIds} refreshingWidgetIds={display.refreshingIds} failureWidgetIds={display.failureIds}
      onVariablesSet={(values, displayedInputOrder) => {
        if (displayedInputOrder !== revision) return;
        const next = { ...variables, ...values }; setVariables(next); setRevision(revision + 1);
        if (queryDependent) { setPending(true); setPrimaryDone(false); }
        else setReady({ ...ready, variables: next, inputOrder: revision + 1 });
      }} />
    <WidgetMetricsDialog workspaceId="00000000-0000-7000-8000-000000000001" principalId={null} sampleWorkspace={false} queries={definition.queries} target={reader ? null : queryTarget} onClose={() => setQueryTarget(null)} />
  </section>;
}
