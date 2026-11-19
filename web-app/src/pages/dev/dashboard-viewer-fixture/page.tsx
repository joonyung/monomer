import { LayoutSizingFixture } from "./LayoutSizingFixture";
import { ContentHeightFixture } from "./ContentHeightFixture";
import { InputLayoutFixture } from "./InputLayoutFixture";
import { TablePresentationFixture } from "./TablePresentationFixture";
import { TablePivotFixture } from "./TablePivotFixture";
import { AtomicVariablesFixture } from "./AtomicVariablesFixture";
import { useMemo, useState } from "react";
import { useSearch } from "@tanstack/react-router";
import type { DashboardDefinition, DashboardJsonObject, DashboardParameterDefinition, DashboardQueryRun, DashboardSessionSummary } from "@/api/dashboard";
import { DashboardLayout } from "@/components/dashboard/DashboardLayout";
import { DashboardParameterForm, dashboardInputChips } from "@/components/dashboard/DashboardParameterForm";
import { ChangeInputsDialog } from "@/pages/dashboard/viewer/ChangeInputsDialog";
import { DashboardInputsBar } from "@/pages/dashboard/viewer/InputsBar";
import { dashboardResultsStatus } from "@/pages/dashboard/viewer/inputs";
import { displayedResultsTimeZoneLabel } from "@/components/dashboard/capture/provenance";
import { DashboardInputTimeZoneProvider } from "@/components/dashboard/DashboardInputTimeZone";
import { DashboardActivitySidebar } from "@/components/dashboard/DashboardActivitySidebar";
import { notFound } from "@/lib/navigation";
import { validateDashboardResult, type NamedDashboardResult } from "@/features/dashboard/dashboard-result";

const chartSource = `export default defineChart({
  setup({ chart }) {
    chart.on("click", { seriesId: "orders_by_status" }, (event, frame) => {
      if (typeof event.name === "string") frame.setVariables({ selectedStatus: event.name });
    });
  },
  render({ queries, variables }) {
    const rows = queries.orders.rows.filter(({ ["dimension.orders.status"]: status }) => variables.selectedStatus === null || status === variables.selectedStatus);
    return {
      animation: false,
      grid: { top: 16, right: 16, bottom: 36, left: 48 },
      xAxis: { type: "category", data: rows.map(({ ["dimension.orders.status"]: status }) => status) },
      yAxis: { type: "value" },
      series: [{ id: "orders_by_status", type: "bar", data: rows.map(({ ["metric.order_count"]: orderCount }) => Number(orderCount)), itemStyle: { color: "#2466c3" } }],
    };
  },
});`;

export const dashboardViewerFixtureDefinition = {
  schemaVersion: 1,
  meta: { title: "Orders overview", description: "Deterministic Dashboard Viewer fixture" },
  parameters: {
    region: { type: "TEXT", nullable: false, label: "Region", description: "Session parameter" },
    optionalNote: { type: "TEXT", nullable: true, label: "Optional note", description: "Explicitly nullable parameter" },
  },
  variables: {
    selectedStatus: { type: "TEXT", nullable: true, initial: null, label: "Selected status", description: "Chart cross-filter" },
  },
  queries: {
    orders: { type: "SEMANTIC" as const,
      connectorId: "00000000-0000-7000-8000-000000000001",
      query: {
        schemaVersion: 4,
        metrics: ["order_count"],
        groupBys: [
          { kind: "DIMENSION", semanticModelName: "orders", name: "status" },
          { kind: "DIMENSION", semanticModelName: "shipments", name: "status" },
          { kind: "DIMENSION", semanticModelName: "orders", name: "created_at", granularity: "DAY" },
          { kind: "DIMENSION", semanticModelName: "orders", name: "created_at", granularity: "MONTH" },
          { kind: "ENTITY", semanticModelName: "orders", name: "customer" },
          { kind: "METRIC_TIME", granularity: "DAY" },
        ],
        metricTimeRange: null,
        filters: [],
        orderBy: [],
        rowLimit: 100,
      },
    },
  },
  widgets: {
    input: {
      type: "INPUT",
      variableName: "selectedStatus",
      control: { options: [{ value: "COMPLETED", label: "Completed" }, { value: "REFUNDED", label: "Refunded" }] },
    },
    table: { type: "TABLE", queryName: "orders", columns: [{ field: "dimension.orders.status", header: "Order status" }, { field: "metric.order_count", header: "Orders" }] },
    text: { type: "TEXT", content: "### Orders\n\nSelect a status in the input to update the chart." },
    chart: {
      type: "CHART",
      queryNames: ["orders"],
      module: { inputs: { parameters: ["region"], variables: ["selectedStatus"] }, source: chartSource },
    },
  },
  layout: {
    sections: [
      { id: "controls", height: { mode: "CONTENT" }, root: { type: "STACK", id: "controls-stack", direction: "ROW", children: [{ weight: 1, node: { type: "WIDGET", id: "input-item", widgetId: "input" } }, { weight: 3, node: { type: "WIDGET", id: "text-item", widgetId: "text" } }] } },
      { id: "data", height: { mode: "SCALED", scale: 2.1 }, root: { type: "STACK", id: "data-stack", direction: "COLUMN", children: [{ weight: 1, node: { type: "WIDGET", id: "table-item", widgetId: "table" } }, { weight: 2, node: { type: "WIDGET", id: "chart-item", widgetId: "chart" } }] } },
    ],
  },
} as const satisfies DashboardDefinition;

// The Viewer fixture titles its chart so the widget header, menu, and accessible name stay covered.
const definition: DashboardDefinition = {
  ...dashboardViewerFixtureDefinition,
  widgets: { ...dashboardViewerFixtureDefinition.widgets, chart: { ...dashboardViewerFixtureDefinition.widgets.chart, title: "Orders by status" } },
};
const parameterFixtureDefinitions: Record<string, DashboardParameterDefinition> = {
  ...definition.parameters,
  orderStatuses: {
    type: "TEXT_SET",
    nullable: false,
    label: "Order statuses",
    description: "Statuses included in this dashboard session",
    control: { options: [{ value: "COMPLETED", label: "Completed" }, { value: "SHIPPED", label: "Shipped" }, { value: "REFUNDED", label: "Refunded" }] },
  },
  reportPeriod: {
    type: "DATE_RANGE",
    nullable: false,
    label: "Report period",
    description: "Inclusive calendar date range",
    control: { min: "2025-01-01", max: "2025-12-31" },
  },
  observedWindow: {
    type: "TIMESTAMP_RANGE",
    nullable: false,
    label: "Observed window",
    description: "UTC instant range",
    control: { min: "2025-01-01T00:00:00Z", max: "2025-12-31T23:59:59Z" },
  },
};

export const dashboardViewerFixtureResult = validateDashboardResult({ queryType: "SEMANTIC" as const, reportingTimeZone: "Asia/Seoul",
  queryRunId: "00000000-0000-7000-8000-000000000002",
  columns: [
    { name: "dimension.orders.status", logicalType: "TEXT", nullable: false, display: null },
    { name: "dimension.shipments.status", logicalType: "TEXT", nullable: true, display: null },
    { name: "dimension.orders.created_at.day", logicalType: "DATE", nullable: false, display: null },
    { name: "dimension.orders.created_at.month", logicalType: "DATE", nullable: false, display: null },
    { name: "entity.orders.customer", logicalType: "TEXT", nullable: true, display: null },
    { name: "metric_time.day", logicalType: "DATE", nullable: false, display: null },
    { name: "metric.order_count", logicalType: "NUMBER", nullable: false, display: null },
  ],
  rows: [
    ["COMPLETED", "SHIPPED", "2026-08-24", "2026-08", "Acme", "2026-08-24", "63"],
    ["REFUNDED", null, "2026-08-25", "2026-08", null, "2026-08-25", "42"],
    ["DELIVERED", "DELIVERED", "2026-08-26", "2026-08", "Globex", "2026-08-26", "31"],
  ],
});

const result = dashboardViewerFixtureResult;

type FixtureScene = "layout-sizing" | "content-height" | "input-layout" | "table-presentation" | "table-pivot" | "nested-stacks" | "atomic-variables" | "parameters" | "loading" | "refreshing" | "error" | "first-failure" | "empty" | "ready" | "outside" | "session-history";

const nestedStackFixtures = (["ROW", "COLUMN"] as const).map((direction) => {
  const outerSeparation = "GAP";
  const innerSeparation = "DIVIDER";
  const id = `${direction.toLowerCase()}-gap-divider`;
  const nestedDefinition: DashboardDefinition = {
    ...definition,
    meta: { title: id },
    widgets: {
      text: { type: "TEXT", content: "### Orders by region\n\nThe description and chart show the same order result inside one group." },
      chart: definition.widgets.chart,
      table: definition.widgets.table,
    },
    layout: {
      sections: [{
        id,
        height: { mode: "SCALED", scale: direction === "ROW" ? 1.4 : 4 },
        root: {
          type: "STACK", id: `${id}-outer`, direction,
          children: [
            { weight: 3, node: {
              type: "STACK", id: `${id}-inner`, direction, separation: innerSeparation,
              children: [
                { ...(direction === "ROW" ? { weight: 1 } : {}), node: { type: "WIDGET", id: `${id}-text`, widgetId: "text" } },
                { weight: direction === "ROW" ? 2 : 1, node: { type: "WIDGET", id: `${id}-chart`, widgetId: "chart" } },
              ],
            } },
            { weight: 2, node: { type: "WIDGET", id: `${id}-table`, widgetId: "table" } },
          ],
        },
      }],
    },
  };
  return { id, direction, outerSeparation, innerSeparation, definition: nestedDefinition };
});

function NestedStackFixtures() {
  return <div className="space-y-8 py-6">
    {nestedStackFixtures.map((fixture) => <article key={fixture.id} data-testid={`nested-stack-${fixture.id}`}>
      <h2 className="mb-3 text-[15px] text-heading">{fixture.direction} / {fixture.outerSeparation} → {fixture.innerSeparation}</h2>
      <DashboardLayout definition={fixture.definition} parameters={{ region: "North", optionalNote: null }} variables={{ selectedStatus: null }} variableRevision={0} variableRevisionsByWidgetId={{}} variablesByWidgetId={{}} variableDefinitions={definition.variables} resultsByWidgetId={{ chart: { orders: result }, table: { orders: result } }} displayedWidgetIds={new Set(["chart", "table"])} refreshingWidgetIds={new Set()} failureWidgetIds={new Set()} onVariablesSet={() => undefined} />
    </article>)}
  </div>;
}

const fixtureSessionId = "00000000-0000-7000-8000-000000000010";
const fixturePreviousSessionId = "00000000-0000-7000-8000-000000000011";
const fixtureRunId = "00000000-0000-7000-8000-000000000012";

const fixtureSessions: DashboardSessionSummary[] = [
  {
    dashboardId: "00000000-0000-7000-8000-000000000013",
    dashboardSessionId: fixtureSessionId,
    createdAt: "2026-08-27T06:30:00Z",
    updatedAt: "2026-08-27T06:30:04Z",
    lastQueryRunAt: "2026-08-27T06:30:00Z",
    parameterBindings: { region: "North", optionalNote: null },
    runningQueryRuns: [{ queryName: "orders", queryRunId: fixtureRunId, state: "RUNNING" }],
  },
  {
    dashboardId: "00000000-0000-7000-8000-000000000013",
    dashboardSessionId: fixturePreviousSessionId,
    createdAt: "2026-08-24T15:10:00Z",
    updatedAt: "2026-08-24T15:10:06Z",
    lastQueryRunAt: "2026-08-24T15:10:00Z",
    parameterBindings: { region: "South", optionalNote: null },
    runningQueryRuns: [],
  },
];

const fixtureSessionRuns: DashboardQueryRun[] = [{ queryType: "SEMANTIC" as const,
  connectorId: "00000000-0000-7000-8000-000000000015",
  createdAt: "2026-08-27T06:30:00Z",
  startedBy: { id: "00000000-0000-7000-8000-0000000000e1", displayName: "Sam Kim" },
  credentialSurface: "AGENT", agentApp: null,
  failureCode: null,
  failureMessage: null,
  outputColumnNames: [
    "dimension.orders.status",
    "dimension.shipments.status",
    "dimension.orders.created_at.day",
    "dimension.orders.created_at.month",
    "entity.orders.customer",
    "metric_time.day",
    "metric.order_count",
  ],
  queryName: "orders",
  queryRunId: fixtureRunId,
  resultAvailable: false,
  state: "RUNNING",
  updatedAt: "2026-08-27T06:30:04Z",
}];

export default function DashboardViewerFixturePage() {
  if (!import.meta.env.DEV) notFound();
  const { scene: requestedScene, zone } = useSearch({ from: "/dev/dashboard-viewer-fixture" });
  const [scene, setScene] = useState<FixtureScene>(requestedScene ?? "ready");
  const [parameters, setParameters] = useState<DashboardJsonObject>({ region: "North", optionalNote: null });
  const [variables, setVariables] = useState<DashboardJsonObject>(() => ({ selectedStatus: scene === "outside" ? "DELIVERED" : null }));
  const [fixtureSelectedSessionId, setFixtureSelectedSessionId] = useState(fixtureSessionId);
  const [runsCollapsed, setRunsCollapsed] = useState(false);
  const [widgetRequest, setWidgetRequest] = useState<string | null>(null);
  const [changeInputsOpen, setChangeInputsOpen] = useState(false);
  const [expandedGroups, setExpandedGroups] = useState<Record<string, readonly string[]>>({});
  const [activityView, setActivityView] = useState<"sessions" | "queries">("sessions");
  const [mobileOpen, setMobileOpen] = useState(false);
  const [fixtureRunning, setFixtureRunning] = useState(true);
  const activitySessions = fixtureSessions.map(session => ({ ...session, runningQueryRuns: fixtureRunning ? session.runningQueryRuns : [] }));
  const selectedFixtureSession = fixtureSessions.find(session => session.dashboardSessionId === fixtureSelectedSessionId)!;
  const currentFixtureRuns = fixtureSelectedSessionId === fixtureSessionId ? fixtureSessionRuns.map(run => ({ ...run, state: fixtureRunning ? "RUNNING" as const : "SUCCEEDED" as const })) : [{ ...fixtureSessionRuns[0], queryRunId: "00000000-0000-7000-8000-000000000014", state: "SUCCEEDED" as const, credentialSurface: "WEB" as const }];
  const [refreshingHistoryQueries, setRefreshingHistoryQueries] = useState<string[]>([]);
  const display = useMemo(() => scene === "ready" || scene === "refreshing" || scene === "error" || scene === "outside" || scene === "session-history", [scene]);
  const displayedWidgetIds = display ? new Set(["table", "chart"]) : new Set<string>();
  const refreshingWidgetIds = scene === "refreshing" ? new Set(["table", "chart"]) : new Set<string>();
  const failureWidgetIds = scene === "error" || scene === "first-failure" ? new Set(["table", "chart"]) : new Set<string>();
  const firstFailureMessages = scene === "first-failure" ? { table: "The warehouse query timed out after 120 seconds.", chart: "The query failed." } : undefined;
  const resultsByWidgetId: Record<string, Record<string, NamedDashboardResult>> = {
    table: { orders: result },
    chart: { orders: result },
  };

  if (scene === "layout-sizing") return <LayoutSizingFixture />;
  if (scene === "content-height") return <ContentHeightFixture />;
  return (
    <main className="min-h-screen bg-background px-5 py-6 sm:px-8" style={{ fontFeatureSettings: '"ss01"' }}>
      <div className="mx-auto max-w-[1120px]">
        <header className="flex flex-wrap items-end justify-between gap-4 border-b border-border pb-5">
          <div><p className="text-[10px] font-medium uppercase tracking-[0.08em] text-foreground-secondary">Development fixture</p><h1 className="mt-1 text-[32px] font-light tracking-[-0.64px] text-heading">Dashboard Viewer</h1><p className="mt-1.5 text-[14px] text-foreground-secondary">Parameter, INPUT/TABLE/TEXT/CHART widgets, shared query result, responsive layout, and Chart Worker host.</p></div>
          <nav aria-label="Dashboard fixture scenes" className="flex flex-wrap gap-1.5">
          {(["parameters", "loading", "refreshing", "error", "first-failure", "empty", "ready", "outside", "session-history", "nested-stacks", "atomic-variables", "table-presentation", "table-pivot", "input-layout"] as FixtureScene[]).map((candidate) => <button key={candidate} type="button" onClick={() => { setScene(candidate); setVariables((current) => ({ ...current, selectedStatus: candidate === "outside" ? "DELIVERED" : null })); }} aria-pressed={scene === candidate} className={`h-8 rounded-[4px] border px-2.5 text-[11px] ${scene === candidate ? "border-accent bg-accent text-accent-foreground" : "border-border text-foreground hover:bg-surface"}`}>{candidate}</button>)}
          </nav>
        </header>
        {scene === "input-layout" ? <InputLayoutFixture /> : null}
        {scene === "nested-stacks" ? <NestedStackFixtures /> : null}
        {scene === "table-presentation" ? <TablePresentationFixture /> : null}
        {scene === "table-pivot" ? <TablePivotFixture /> : null}
        {scene === "atomic-variables" ? <AtomicVariablesFixture /> : null}
        {scene === "parameters" ? <DashboardInputTimeZoneProvider timeZone={zone ?? null}><DashboardParameterForm parameters={parameterFixtureDefinitions} onOpen={(bindings) => { setParameters(bindings); setScene("ready"); }} /></DashboardInputTimeZoneProvider> : null}
        {scene === "empty" ? <div className="flex min-h-[420px] items-center justify-center text-center"><div><h2 className="text-[22px] font-light text-heading">No dashboard content</h2><p className="mt-2 text-[13px] text-foreground-secondary">This fixture scene exercises the empty state.</p></div></div> : null}
        {scene !== "input-layout" && scene !== "parameters" && scene !== "empty" && scene !== "nested-stacks" && scene !== "atomic-variables" && scene !== "table-presentation" && scene !== "table-pivot" ? <>
          {scene === "loading" ? <p role="status" className="my-4 text-[12px] text-foreground-secondary">Initial QueryRun is loading…</p> : null}
          {scene === "refreshing" ? <p role="status" className="my-4 text-[12px] text-foreground-secondary">Variable changed; previous ready frame remains visible.</p> : null}
          {scene === "error" ? <p role="alert" className="my-4 text-[12px] text-error">Latest target failed; the last-good frame remains visible.</p> : null}
          <output data-testid="dashboard-parameter-optionalNote-value" className="sr-only">{parameters.optionalNote === null ? "null" : String(parameters.optionalNote ?? "undefined")}</output>
          <output data-testid="dashboard-parameter-reportPeriod-value" className="sr-only">{JSON.stringify(parameters.reportPeriod)}</output>
          <output data-testid="dashboard-parameter-observedWindow-value" className="sr-only">{JSON.stringify(parameters.observedWindow)}</output>
          {scene === "first-failure" || scene === "ready" ? <output data-testid="dashboard-widget-request" className="sr-only">{widgetRequest ?? ""}</output> : null}
          {scene === "session-history" ? <div className="mt-4 border-t border-border"><DashboardInputsBar chips={dashboardInputChips(parameters, definition.parameters)} status={dashboardResultsStatus(selectedFixtureSession.lastQueryRunAt, fixtureRunning && fixtureSelectedSessionId === fixtureSessionId)} timeZone={displayedResultsTimeZoneLabel(resultsByWidgetId)} onChangeInputs={() => setChangeInputsOpen(true)} /></div> : null}
          {scene === "session-history" ? <ChangeInputsDialog open={changeInputsOpen} onOpenChange={setChangeInputsOpen} parameters={definition.parameters} currentValues={parameters} pending={false} error={null} onSubmit={(bindings) => { setParameters(bindings); setChangeInputsOpen(false); }} /> : null}
          {scene === "session-history" ? <div className="mt-4 flex justify-end gap-3"><button onClick={() => setFixtureRunning(false)}>Finish running query</button><button onClick={() => { if (window.matchMedia("(min-width: 1024px)").matches) setRunsCollapsed((collapsed) => !collapsed); else setMobileOpen(true); }}>History</button></div> : null}
          <div className="mt-4 flex gap-5 items-stretch">
            <div className="min-w-0 flex-1"><DashboardLayout definition={definition} parameters={parameters} variables={variables} variableRevision={0} variableRevisionsByWidgetId={{}} variablesByWidgetId={{ chart: variables }} variableDefinitions={definition.variables} resultsByWidgetId={resultsByWidgetId} displayedWidgetIds={displayedWidgetIds} refreshingWidgetIds={refreshingWidgetIds} failureWidgetIds={failureWidgetIds} errorMessagesByWidgetId={firstFailureMessages} widgetActions={{ pending: false, onRefresh: (queryNames) => setWidgetRequest(`refresh:${queryNames.join(",")}`), onShowRuns: (queryNames) => setWidgetRequest(`details:${queryNames.join(",")}`) }} onVariablesSet={(values) => setVariables((current) => ({ ...current, ...values }))} /></div>
            {scene === "session-history" ? <DashboardActivitySidebar principalId={null} expandedGroups={expandedGroups} onExpandedGroupsChange={setExpandedGroups} sessions={activitySessions} selectedSessionId={fixtureSelectedSessionId} selectedSession={{ dashboardSessionId: fixtureSelectedSessionId, definition, parameterBindings: selectedFixtureSession.parameterBindings }} queryRuns={currentFixtureRuns} targetQueryRuns={{ orders: currentFixtureRuns[0].queryRunId }} collapsed={runsCollapsed} onCollapsedChange={setRunsCollapsed} view={activityView} onViewChange={setActivityView} mobileOpen={mobileOpen} onMobileOpenChange={setMobileOpen} queryRunsLoading={false} queryRunsError={null} hasNextQueryRuns loadingNextQueryRuns={false} refreshingQueryNames={refreshingHistoryQueries} onRefreshQuery={(queryName) => { setRefreshingHistoryQueries([queryName]); window.setTimeout(() => setRefreshingHistoryQueries([]), 700); }} onLoadMoreQueryRuns={() => undefined} hasNextSessions loadingNextSessions={false} sessionsError={null} onSelectSession={(id) => { setFixtureSelectedSessionId(id); setParameters(fixtureSessions.find(session => session.dashboardSessionId === id)!.parameterBindings); }} onLoadMoreSessions={() => undefined} /> : null}
          </div>
        </> : null}
      </div>
    </main>
  );
}
