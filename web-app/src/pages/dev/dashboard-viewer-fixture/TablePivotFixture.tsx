import { useMemo, useState } from "react";
import type { DashboardDefinition, DashboardResultColumn, DashboardWidget } from "@/api/dashboard";
import { DashboardLayout } from "@/components/dashboard/DashboardLayout";
import type { DashboardWidgetMetricsTarget } from "@/components/dashboard/DashboardWidgetRenderer";
import { WidgetMetricsDialog } from "@/components/dashboard/WidgetMetricsDialog";
import { validateDashboardResult, type NamedDashboardResult } from "@/features/dashboard/dashboard-result";
import { dashboardQueryTypes } from "@/features/dashboard/widget-metrics";
import { widgetQueryNames } from "@/features/dashboard/widget-query-names";
import { displayedWidgets } from "@/pages/dashboard/viewer/screen-state";

const CONNECTOR_ID = "00000000-0000-7000-8000-000000000001";
const REGION: DashboardResultColumn = { name: "dimension.orders.region", logicalType: "TEXT", nullable: false, display: null };
const MONTH: DashboardResultColumn = { name: "metric_time.month", logicalType: "DATE", nullable: false, display: null };
const REVENUE: DashboardResultColumn = { name: "metric.revenue", logicalType: "DECIMAL", nullable: true, display: { style: "CURRENCY", currency: "USD", decimals: 2 } };
const CUSTOMERS: DashboardResultColumn = { name: "metric.unique_customers", logicalType: "INTEGER", nullable: true, display: null };
const BY_REGION = { kind: "DIMENSION", semanticModelName: "orders", name: "region" } as const;
const BY_MONTH = { kind: "METRIC_TIME", granularity: "MONTH" } as const;

const GRAND_TOTAL_QUERIES = new Set(["revenueTotal", "scorecardTotal"]);
const scenarios = ["columns", "scorecard", "duplicate key", "too many columns", "missing total"] as const;
type PivotScenario = typeof scenarios[number];

const formattingSource = `export default defineTable({ render() {
  return {
    row() { return { background: "#FDE68A" }; },
    cell({ field, value, rowIndex, displayText }) {
      if (field === "metric.unique_customers") return { text: displayText + " customers · row " + rowIndex };
      if (field === "dimension.orders.region") return { text: displayText + " · row " + rowIndex };
      if (field === "metric.revenue" && value !== null) return { text: displayText, background: "#EAF0E5" };
      return undefined;
    },
  };
} });`;

function semanticQuery(groupBys: (typeof BY_REGION | typeof BY_MONTH)[]): DashboardDefinition["queries"][string] {
  return { type: "SEMANTIC", connectorId: CONNECTOR_ID, query: { schemaVersion: 4, metrics: ["revenue", "unique_customers"], groupBys, metricTimeRange: null, filters: [], orderBy: [], rowLimit: 1000 } };
}

function result(queryRunId: string, columns: DashboardResultColumn[], rows: (string | null)[][]): NamedDashboardResult {
  return validateDashboardResult({ queryType: "SEMANTIC", reportingTimeZone: null, queryRunId, columns, rows });
}

const regionMonthRows = [
  ["West", "2026-08-01", "1350.50", "42"],
  ["West", "2026-07-01", "1200.00", "40"],
  ["East", "2026-08-01", "980.00", "30"],
  ["North", "2026-07-01", null, "12"],
  ["North", "2026-08-01", "1500.00", "25"],
];
const regionTotalRows = [["North", "1500.00", "31"], ["West", "2550.50", "55"], ["East", "980.00", "30"]];

// Unique customers are counted once across months, so every total query returns less than the summed cells.
function scenarioResults(scenario: PivotScenario): Record<string, (queryRunId: string) => NamedDashboardResult> {
  const primaryRows = scenario === "duplicate key" ? [...regionMonthRows, ["West", "2026-08-01", "10.00", "1"]]
    : scenario === "too many columns" ? Array.from({ length: 170 }, (_, index) => ["West", new Date(Date.UTC(2020, 0, 1 + index)).toISOString().slice(0, 10), "1.00", "1"])
      : regionMonthRows;
  return {
    revenueByRegionMonth: (id) => result(id, [REGION, MONTH, REVENUE, CUSTOMERS], primaryRows),
    revenueByRegion: (id) => result(id, [REGION, REVENUE, CUSTOMERS], scenario === "missing total" ? regionTotalRows.slice(1) : regionTotalRows),
    revenueByMonth: (id) => result(id, [MONTH, REVENUE, CUSTOMERS], [["2026-08-01", "3830.50", "88"], ["2026-07-01", "1200.00", "50"]]),
    revenueTotal: (id) => result(id, [REVENUE, CUSTOMERS], [["5030.50", "104"]]),
    monthlyScorecard: (id) => result(id, [MONTH, REVENUE, CUSTOMERS], [["2026-08-01", "3830.50", "88"], ["2026-07-01", "1200.00", "50"], ["2026-09-01", "2600.00", "61"]]),
    scorecardTotal: (id) => result(id, [REVENUE, CUSTOMERS], [["7630.50", "140"]]),
  };
}

function pivotWidget(scenario: PivotScenario, formatted: boolean): DashboardWidget {
  const module = formatted ? { inputs: { queries: [], parameters: [], variables: [] }, source: formattingSource } : undefined;
  const values = [{ field: REVENUE.name, header: "Revenue", width: 140 }, { field: CUSTOMERS.name, header: "Customers", width: 160 }];
  if (scenario === "scorecard") {
    return { type: "TABLE", title: "Monthly scorecard", queryName: "monthlyScorecard", module, pivot: { rows: [], columns: [{ field: MONTH.name, header: "Month" }], values, valuesAxis: "ROWS", totals: ["scorecardTotal"] } };
  }
  return {
    type: "TABLE", title: "Revenue by region", queryName: "revenueByRegionMonth", module,
    pivot: { rows: [{ field: REGION.name, header: "Region", width: 180 }], columns: [{ field: MONTH.name, header: "Month" }], values, valuesAxis: "COLUMNS", totals: ["revenueByRegion", "revenueByMonth", "revenueTotal"] },
  };
}

export function TablePivotFixture() {
  const [scenario, setScenario] = useState<PivotScenario>("columns");
  const [formatted, setFormatted] = useState(false);
  const [reader, setReader] = useState(false);
  const [totalPending, setTotalPending] = useState(false);
  const [queryTarget, setQueryTarget] = useState<DashboardWidgetMetricsTarget | null>(null);
  const definition = useMemo<DashboardDefinition>(() => ({
    schemaVersion: 1, meta: { title: "Regional revenue" }, parameters: {}, variables: {},
    queries: {
      revenueByRegionMonth: semanticQuery([BY_REGION, BY_MONTH]),
      revenueByRegion: semanticQuery([BY_REGION]),
      revenueByMonth: semanticQuery([BY_MONTH]),
      revenueTotal: semanticQuery([]),
      monthlyScorecard: semanticQuery([BY_MONTH]),
      scorecardTotal: semanticQuery([]),
    },
    widgets: { pivot: pivotWidget(scenario, formatted) },
    layout: { sections: [{ id: "pivot", height: { mode: "SCALED", scale: 1.4 }, root: { type: "WIDGET", id: "pivot_table", widgetId: "pivot" } }] },
  }), [scenario, formatted]);
  const queryRuns = useMemo(() => Object.fromEntries(widgetQueryNames(definition.widgets.pivot).map((name) => [name, `${name}-${scenario}`])), [definition, scenario]);
  const resultMap = useMemo(() => {
    const builders = scenarioResults(scenario);
    return Object.fromEntries(Object.entries(queryRuns).filter(([name]) => !(totalPending && GRAND_TOTAL_QUERIES.has(name))).map(([name, runId]) => [runId, builders[name](runId)]));
  }, [queryRuns, scenario, totalPending]);
  const display = displayedWidgets(definition, { inputOrder: 0, pending: false, queryRuns }, [], resultMap, { pivot: { inputOrder: 0, variables: {}, queryRuns } }, new Set(), new Set(), true);
  const layoutDefinition = reader ? { widgets: definition.widgets, layout: definition.layout } : definition;
  return <section className="space-y-4 py-5">
    <h2 className="text-[22px] font-light text-heading">Regional revenue · TABLE pivot</h2>
    <p className="text-[13px] text-foreground-secondary">Fixed-result fixture · uses the real TABLE Host, totals queries, and Worker.</p>
    <div className="flex flex-wrap items-center gap-3 text-[12px]">
      <label>Pivot scenario <select aria-label="Pivot scenario" value={scenario} onChange={(event) => setScenario(event.target.value as PivotScenario)}>{scenarios.map((key) => <option key={key}>{key}</option>)}</select></label>
      {([["Formatting source", formatted, setFormatted], ["Page reader", reader, setReader], ["Total query pending", totalPending, setTotalPending]] as const).map(([label, checked, setter]) => (
        <label key={label} className="flex items-center gap-1"><input type="checkbox" checked={checked} onChange={(event) => setter(event.target.checked)} />{label}</label>
      ))}
    </div>
    <DashboardLayout definition={layoutDefinition} parameters={{}} variables={{}} variableRevision={0}
      queryTypes={dashboardQueryTypes(definition.queries)}
      widgetActions={reader ? undefined : { pending: false, onShowMetrics: setQueryTarget }}
      variableDefinitions={{}} variablesByWidgetId={display.variablesByWidgetId} variableRevisionsByWidgetId={display.variableRevisionsByWidgetId}
      resultsByWidgetId={display.resultsByWidgetId} displayedWidgetIds={display.widgetIds} refreshingWidgetIds={display.refreshingIds} failureWidgetIds={display.failureIds}
      loadingMessagesByWidgetId={display.loadingMessagesByWidgetId}
      onVariablesSet={() => undefined} />
    <WidgetMetricsDialog workspaceId="00000000-0000-7000-8000-000000000001" principalId={null} sampleWorkspace={false} queries={definition.queries} target={reader ? null : queryTarget} onClose={() => setQueryTarget(null)} />
  </section>;
}
