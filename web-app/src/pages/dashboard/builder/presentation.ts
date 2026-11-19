import type {
  DashboardDraftResponse,
  DashboardJsonObject,
  DashboardQueryRun,
} from "@/api/dashboard";
import type { NamedDashboardResult } from "@/features/dashboard/dashboard-result";
import { widgetQueryNames } from "@/features/dashboard/widget-query-names";
import type {
  RunPresentation,
  RunPresentationResult,
} from "@/components/runs";

export interface DashboardBuilderDisplayState {
  displayedWidgetIds: ReadonlySet<string>;
  refreshingWidgetIds: ReadonlySet<string>;
  failureWidgetIds: ReadonlySet<string>;
  errorMessagesByWidgetId: Record<string, string>;
  resultsByWidgetId: Record<string, Record<string, NamedDashboardResult>>;
  parametersByWidgetId: Record<string, DashboardJsonObject>;
  variablesByWidgetId: Record<string, DashboardJsonObject>;
  variableRevisionsByWidgetId: Record<string, number>;
}

export function readyRunIds(draft: DashboardDraftResponse): string[] {
  return [...new Set(Object.values(draft.readyResults).flatMap((bundle) => Object.values(bundle.queryRuns)))];
}

export function dashboardRunPresentation(
  run: DashboardQueryRun,
  targetQueryRuns: Readonly<Record<string, string>>,
  result: NamedDashboardResult | undefined,
): RunPresentation {
  const resultView: RunPresentationResult | null = result
    ? {
      columns: result.columns.map((column, index) => ({
        id: `${index}-${column.name}`,
        label: column.name,
        description: column.logicalType,
      })),
      rows: result.rows.map((row) => result.columns.map((column) => row[column.name] ?? null)),
      returnedRowCount: result.rows.length,
      totalRowCount: null,
      byteSize: null,
    }
    : null;

  return {
    id: run.queryRunId,
    title: run.queryName,
    subtitle: "Dashboard query",
    state: run.state,
    createdAt: run.createdAt,
    updatedAt: run.updatedAt,
    resultAvailable: run.resultAvailable,
    failureMessage: run.failureMessage ?? (run.failureCode ? `Execution failed (${run.failureCode}).` : null),
    isCurrentTarget: targetQueryRuns[run.queryName] === run.queryRunId,
    metadata: [
      { label: "Query", value: run.queryName },
      { label: "Connector", value: run.connectorId },
      { label: "Columns", value: run.outputColumnNames.join(", ") || "—" },
    ],
    request: null,
    result: resultView,
    startedBy: run.startedBy,
    credentialSurface: run.credentialSurface,
    agentApp: run.agentApp,
  };
}

export function dashboardBuilderDisplayState(
  draft: DashboardDraftResponse,
  resultsByRunId: Readonly<Record<string, NamedDashboardResult>>,
): DashboardBuilderDisplayState {
  const runsById = new Map(draft.recentQueryRuns.map((run) => [run.queryRunId, run]));
  const displayedWidgetIds = new Set<string>();
  const refreshingWidgetIds = new Set<string>();
  const failureWidgetIds = new Set<string>();
  const resultsByWidgetId: Record<string, Record<string, NamedDashboardResult>> = {};
  const parametersByWidgetId: Record<string, DashboardJsonObject> = {};
  const variablesByWidgetId: Record<string, DashboardJsonObject> = {};
  const variableRevisionsByWidgetId: Record<string, number> = {};

  for (const [widgetId, widget] of Object.entries(draft.definition.widgets)) {
    const queryNames = widgetQueryNames(widget);
    if (queryNames.length === 0) continue;
    const readyBundle = draft.readyResults[widgetId];
    const widgetResults: Record<string, NamedDashboardResult> = {};
    if (readyBundle) {
      for (const [queryName, runId] of Object.entries(readyBundle.queryRuns)) {
        const result = resultsByRunId[runId];
        if (result) widgetResults[queryName] = result;
      }
      variablesByWidgetId[widgetId] = readyBundle.variables;
      parametersByWidgetId[widgetId] = readyBundle.parameterBindings;
      variableRevisionsByWidgetId[widgetId] = readyBundle.authoringRevision;
      if (queryNames.every((queryName) => widgetResults[queryName] !== undefined)) {
        displayedWidgetIds.add(widgetId);
      }
    }
    resultsByWidgetId[widgetId] = widgetResults;

    const targetChanged = queryNames.some((queryName) => {
      const targetRunId = draft.targetQueryRuns[queryName];
      const readyRunId = readyBundle?.queryRuns[queryName];
      return targetRunId !== undefined && targetRunId !== readyRunId;
    });
    const failedTarget = queryNames.some((queryName) => {
      const targetRunId = draft.targetQueryRuns[queryName];
      const targetRun = targetRunId ? runsById.get(targetRunId) : undefined;
      return targetRun?.state === "FAILED" || targetRun?.state === "CANCELLED";
    });
    if (failedTarget) failureWidgetIds.add(widgetId);
    if (targetChanged) refreshingWidgetIds.add(widgetId);
  }

  return {
    displayedWidgetIds,
    refreshingWidgetIds,
    failureWidgetIds,
    errorMessagesByWidgetId: {},
    resultsByWidgetId,
    parametersByWidgetId,
    variablesByWidgetId,
    variableRevisionsByWidgetId,
  };
}

export function retainHydratingBuilderWidgets(
  draft: DashboardDraftResponse,
  candidate: DashboardBuilderDisplayState,
  previous: DashboardBuilderDisplayState | null,
  deniedRunIds: ReadonlySet<string>,
): DashboardBuilderDisplayState {
  const next = { ...candidate, errorMessagesByWidgetId: { ...candidate.errorMessagesByWidgetId }, displayedWidgetIds: new Set(candidate.displayedWidgetIds), refreshingWidgetIds: new Set(candidate.refreshingWidgetIds), failureWidgetIds: new Set(candidate.failureWidgetIds), resultsByWidgetId: { ...candidate.resultsByWidgetId }, parametersByWidgetId: { ...candidate.parametersByWidgetId }, variablesByWidgetId: { ...candidate.variablesByWidgetId }, variableRevisionsByWidgetId: { ...candidate.variableRevisionsByWidgetId } };
  for (const [id, widget] of Object.entries(draft.definition.widgets)) {
    const selectedResults = candidate.displayedWidgetIds.has(id) ? candidate.resultsByWidgetId[id] : previous?.resultsByWidgetId[id];
    const readyDenied = Object.values(draft.readyResults[id]?.queryRuns ?? {}).some(runId => deniedRunIds.has(runId));
    const denied = readyDenied || (selectedResults && Object.values(selectedResults).some(result => deniedRunIds.has(result.queryRunId)));
    if (denied) {
      next.displayedWidgetIds.delete(id);
      next.refreshingWidgetIds.delete(id);
      next.failureWidgetIds.add(id);
      next.errorMessagesByWidgetId[id] = "You do not have access to this result. Check your data permissions.";
      delete next.resultsByWidgetId[id];
      continue;
    }
    if (candidate.displayedWidgetIds.has(id) || !draft.readyResults[id] || !previous?.displayedWidgetIds.has(id)) continue;
    if (!widgetQueryNames(widget).every(name => previous.resultsByWidgetId[id]?.[name])) continue;
    next.displayedWidgetIds.add(id);
    next.refreshingWidgetIds.add(id);
    next.resultsByWidgetId[id] = previous.resultsByWidgetId[id];
    next.parametersByWidgetId[id] = previous.parametersByWidgetId[id];
    next.variablesByWidgetId[id] = previous.variablesByWidgetId[id];
    next.variableRevisionsByWidgetId[id] = previous.variableRevisionsByWidgetId[id];
  }
  return next;
}
