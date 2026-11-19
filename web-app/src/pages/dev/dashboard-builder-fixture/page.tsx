import { useMemo, useState } from "react";
import { useSearch } from "@tanstack/react-router";
import type { DashboardDefinition, DashboardDraftResponse, DashboardJsonObject, DashboardQueryRun } from "@/api/dashboard";
import { DashboardBuilderSurface } from "@/pages/dashboard/builder/DashboardBuilderSurface";
import { dashboardViewerFixtureDefinition, dashboardViewerFixtureResult } from "@/pages/dev/dashboard-viewer-fixture/page";
import { ParameterCoherenceFixture } from "./ParameterCoherenceFixture";
import { ChangeHighlightFixture } from "./ChangeHighlightFixture";
import { ControlBoundaryFixture } from "./ControlBoundaryFixture";
import { notFound } from "@/lib/navigation";

const successRunId = "00000000-0000-7000-8000-000000000002";
const runningRunId = "00000000-0000-7000-8000-000000000003";
const failedRunId = "00000000-0000-7000-8000-000000000004";
const cancelledRunId = "00000000-0000-7000-8000-000000000005";
const samKim = { id: "00000000-0000-7000-8000-0000000000e1", displayName: "Sam Kim" };
const minaPark = { id: "00000000-0000-7000-8000-0000000000e2", displayName: "Mina Park" };

const builderFixtureDefinition = {
  ...dashboardViewerFixtureDefinition,
  parameters: {
    region: {
      type: "TEXT",
      nullable: false,
      label: "Region",
      description: "Warehouse region filter",
      control: { options: [{ value: "North", label: "North" }, { value: "South", label: "South" }, { value: "West", label: "West" }] },
    },
    optionalNote: {
      type: "TEXT",
      nullable: true,
      label: "Optional note",
      description: "Explicitly nullable parameter",
    },
    tags: {
      type: "TEXT_SET",
      nullable: false,
      label: "Tags",
      description: "Comma-separated free values",
    },
    asOf: {
      type: "TIMESTAMP",
      nullable: false,
      label: "As of",
      description: "UTC datetime-local editor",
      control: { min: "2026-01-01T00:00:00Z", max: "2026-12-31T23:59:59Z" },
    },
    timestampSet: {
      type: "TIMESTAMP_SET",
      nullable: false,
      label: "Timestamp set",
      description: "Repeatable UTC datetime-local editor",
    },
    timestampWindow: {
      type: "TIMESTAMP_RANGE",
      nullable: false,
      label: "Timestamp window",
      description: "UTC datetime-local range",
      control: {
        min: "2026-01-01T00:00:00Z",
        max: "2026-12-31T23:59:59Z",
        startPlaceholder: "Start timestamp",
        endPlaceholder: "End timestamp",
      },
    },
    reportPeriod: {
      type: "DATE_RANGE",
      nullable: false,
      label: "Report period",
      description: "Inclusive reporting window",
      control: { min: "2026-01-01", max: "2026-12-31", startPlaceholder: "Start date", endPlaceholder: "End date" },
    },
    includeReturns: {
      type: "BOOLEAN",
      nullable: false,
      label: "Include returns",
      description: "Whether returned orders are included",
      control: { options: [{ value: false, label: "Exclude" }, { value: true, label: "Include" }] },
    },
  },
} as const satisfies DashboardDefinition;

const fixtureParameterBindings: DashboardJsonObject = {
  region: "North",
  optionalNote: "initial",
  tags: ["North", "South"],
  asOf: "2026-08-24T09:00:00Z",
  timestampSet: ["2026-08-24T09:00:00Z"],
  timestampWindow: { start: "2026-08-01T00:00:00Z", end: "2026-08-24T23:59:59Z" },
  reportPeriod: { start: "2026-07-01", end: "2026-08-24" },
  includeReturns: false,
};

const runningResult = {
  ...dashboardViewerFixtureResult,
  queryRunId: runningRunId,
};

function run(
  queryRunId: string,
  state: DashboardQueryRun["state"],
  createdAt: string,
  failureMessage: string | null = null,
  credentialSurface: DashboardQueryRun["credentialSurface"] = "WEB",
): DashboardQueryRun {
  return { queryType: "SEMANTIC" as const,
    connectorId: "00000000-0000-7000-8000-000000000001",
    createdAt,
    startedBy: credentialSurface === "AGENT" ? samKim : minaPark,
    credentialSurface,
    agentApp: null,
    failureCode: failureMessage ? "EXECUTION_ORPHANED" : null,
    failureMessage,
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
    queryRunId,
    resultAvailable: state === "SUCCEEDED",
    state,
    updatedAt: createdAt,
  };
}

// What everyone sees before the agent's Draft: no Tags input and a shorter text block.
const appliedFixtureDefinition: DashboardDefinition = {
  ...builderFixtureDefinition,
  parameters: Object.fromEntries(Object.entries(builderFixtureDefinition.parameters).filter(([name]) => name !== "tags")),
  widgets: { ...builderFixtureDefinition.widgets, text: { type: "TEXT", content: "### Orders" } },
};

function createDraft(lastGood: boolean): DashboardDraftResponse {
  const currentRunId = lastGood ? runningRunId : successRunId;
  return {
    dashboardId: "00000000-0000-7000-8000-000000000010",
    workspaceId: "00000000-0000-7000-8000-000000000011",
    definition: builderFixtureDefinition,
    parameterBindings: fixtureParameterBindings,
    variables: { selectedStatus: null },
    authoringRevision: 7,
    targetQueryRuns: { orders: currentRunId },
    readyResults: {
      table: { authoringRevision: 6, parameterBindings: fixtureParameterBindings, variables: { selectedStatus: null }, queryRuns: { orders: successRunId } },
      chart: { authoringRevision: 6, parameterBindings: fixtureParameterBindings, variables: { selectedStatus: null }, queryRuns: { orders: successRunId } },
    },
    recentQueryRuns: [
      run(successRunId, "SUCCEEDED", "2026-08-24T08:00:00Z"),
      run(failedRunId, "FAILED", "2026-08-24T07:59:00Z", "The warehouse stopped reporting progress.", "AGENT"),
      run(cancelledRunId, "CANCELLED", "2026-08-24T07:58:00Z"),
      run(runningRunId, lastGood ? "RUNNING" : "SUCCEEDED", "2026-08-24T07:57:00Z"),
    ],
    createdAt: "2026-08-24T07:00:00Z",
    updatedAt: "2026-08-24T08:00:00Z",
    lastEditedBy: samKim,
    lastEditedAt: "2026-08-24T07:50:00Z",
    lastEditSurface: "AGENT", lastEditAgentApp: null,
  };
}

export default function DashboardBuilderFixturePage() {
  if (!import.meta.env.DEV) notFound();
  const { scene: requestedScene } = useSearch({ from: "/dev/dashboard-builder-fixture" });
  const scene = requestedScene ?? "ready";
  const [activeScene, setActiveScene] = useState<"ready" | "last-good" | "sse">(scene === "last-good" || scene === "sse" ? scene : "ready");
  const [draft, setDraft] = useState(() => createDraft(scene === "last-good"));
  const [sseEmitted, setSseEmitted] = useState(false);
  const [widgetRequest, setWidgetRequest] = useState<string | null>(null);
  const resultsByRunId = useMemo(() => ({
    [successRunId]: dashboardViewerFixtureResult,
    ...(sseEmitted ? { [runningRunId]: runningResult } : {}),
  }), [sseEmitted]);

  const emitSse = () => {
    setSseEmitted(true);
    setDraft((current) => ({
      ...current,
      targetQueryRuns: { orders: runningRunId },
      readyResults: Object.fromEntries(Object.entries(current.readyResults).map(([widgetId, bundle]) => [widgetId, {
        ...bundle,
        queryRuns: { orders: runningRunId },
      }])),
      recentQueryRuns: current.recentQueryRuns.map((candidate) => candidate.queryRunId === runningRunId
        ? { ...candidate, state: "SUCCEEDED", resultAvailable: true, updatedAt: "2026-08-24T08:02:00Z" }
        : candidate),
      updatedAt: "2026-08-24T08:02:00Z",
    }));
  };

  if (scene === "parameter-coherence") return <ParameterCoherenceFixture />;
  if (scene === "controls") return <ControlBoundaryFixture />;
  if (scene === "changes") return <ChangeHighlightFixture initialDraft={createDraft(false)} />;

  return (
    <div className="flex h-screen min-h-0 flex-col bg-background" style={{ fontFeatureSettings: '"ss01"' }}>
      <div className="flex shrink-0 flex-wrap items-center justify-between gap-3 border-b border-border px-5 py-3 sm:px-8">
        <div>
          <p className="text-[10px] font-medium uppercase tracking-[0.08em] text-foreground-secondary">Development fixture</p>
          <p className="mt-1 text-[13px] text-foreground-secondary">Dashboard Builder live Draft states</p>
        </div>
        <div className="flex flex-wrap gap-1.5" aria-label="Builder fixture scenes">
          {(["ready", "last-good", "sse"] as const).map((candidate) => (
            <button key={candidate} type="button" onClick={() => { setActiveScene(candidate); setDraft(createDraft(candidate === "last-good")); setSseEmitted(false); }} aria-pressed={activeScene === candidate} className={`h-8 rounded-[4px] border px-2.5 text-[11px] ${activeScene === candidate ? "border-accent bg-accent text-accent-foreground" : "border-border text-foreground hover:bg-surface"}`}>
              {candidate}
            </button>
          ))}
          <button type="button" onClick={emitSse} className="h-8 rounded-[4px] border border-border px-2.5 text-[11px] text-foreground hover:bg-surface" disabled={sseEmitted}>
            Emit SSE invalidation
          </button>
          <button type="button" onClick={() => setDraft((current) => ({ ...current, parameterBindings: { ...current.parameterBindings, optionalNote: null } }))} className="h-8 rounded-[4px] border border-border px-2.5 text-[11px] text-foreground hover:bg-surface">
            Sync optional note null
          </button>
        </div>
      </div>
      <output data-testid="dashboard-widget-request" className="sr-only">{widgetRequest ?? ""}</output>
      <DashboardBuilderSurface
        draft={draft}
        principalId={null}
        appliedDefinition={appliedFixtureDefinition}
        resultsByRunId={resultsByRunId}
        onRefresh={(queryNames) => setWidgetRequest(`refresh:${queryNames.join(",")}`)}
        onApply={() => undefined}
        onDiscard={() => setDraft((current) => ({
          ...current,
          definition: appliedFixtureDefinition,
          parameterBindings: Object.fromEntries(Object.entries(current.parameterBindings).filter(([name]) => name in appliedFixtureDefinition.parameters)),
          authoringRevision: current.authoringRevision + 1,
          lastEditedBy: minaPark,
          lastEditedAt: "2026-08-24T08:05:00Z",
          lastEditSurface: "WEB", lastEditAgentApp: null,
        }))}
        onParameterSet={(name, value) => setDraft((current) => ({
          ...current,
          authoringRevision: current.authoringRevision + 1,
          parameterBindings: { ...current.parameterBindings, [name]: value },
          updatedAt: "2026-08-24T08:03:00Z",
        }))}
        onCancel={(runId) => setDraft((current) => ({
          ...current,
          recentQueryRuns: current.recentQueryRuns.map((candidate) => candidate.queryRunId === runId ? { ...candidate, state: "CANCELLED" } : candidate),
        }))}
      />
    </div>
  );
}
