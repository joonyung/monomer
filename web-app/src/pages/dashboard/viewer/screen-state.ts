import type { DashboardDefinition, DashboardJsonObject, DashboardQueryRun } from "@/api/dashboard";
import type { NamedDashboardResult } from "@/features/dashboard/dashboard-result";
import { widgetQueryNames } from "@/features/dashboard/widget-query-names";

export interface DashboardScreenState {
  variables: DashboardJsonObject;
  candidate: DashboardJsonObject;
  inputOrder: number;
  latestRequest: number;
  pending: boolean;
  queryRuns: Record<string, string>;
  error: string | null;
}

export interface DashboardWidgetBundle {
  inputOrder: number;
  variables: DashboardJsonObject;
  queryRuns: Record<string, string>;
}

export function initialScreen(variables: DashboardJsonObject): DashboardScreenState {
  return { variables, candidate: variables, inputOrder: 0, latestRequest: 0, pending: false, queryRuns: {}, error: null };
}

export function beginScreenRequest(screen: DashboardScreenState, changes: DashboardJsonObject): DashboardScreenState {
  return { ...screen, candidate: { ...screen.candidate, ...changes }, latestRequest: screen.latestRequest + 1, pending: true, error: null };
}

export function acceptScreenRequest(screen: DashboardScreenState, order: number, response: { variables: DashboardJsonObject; queryRuns: Record<string, string> }): DashboardScreenState {
  if (order !== screen.latestRequest) return screen;
  return { ...screen, ...response, candidate: response.variables, inputOrder: order, pending: false, error: null };
}

export function rejectScreenRequest(screen: DashboardScreenState, order: number, error: string): DashboardScreenState {
  if (order !== screen.latestRequest) return screen;
  return { ...screen, candidate: screen.variables, pending: false, error };
}

export function acceptsChartInput(screen: DashboardScreenState, displayedInputOrder: number): boolean {
  return !screen.pending && displayedInputOrder === screen.inputOrder;
}

export function adoptWidgetBundles(
  definition: Pick<DashboardDefinition, "widgets">,
  screen: DashboardScreenState,
  available: Record<string, NamedDashboardResult>,
  current: Record<string, DashboardWidgetBundle>,
  deniedRunIds: ReadonlySet<string>,
): Record<string, DashboardWidgetBundle> {
  const next = { ...current };
  let changed = false;
  for (const [widgetId, widget] of Object.entries(definition.widgets)) {
    const names = widgetQueryNames(widget);
    const previous = next[widgetId];
    if (previous && (Object.values(previous.queryRuns).some(id => deniedRunIds.has(id)) || names.some(name => deniedRunIds.has(screen.queryRuns[name])))) {
      delete next[widgetId];
      changed = true;
    }
    if (!names.length || !names.every(name => screen.queryRuns[name] && available[screen.queryRuns[name]] && !deniedRunIds.has(screen.queryRuns[name]))) continue;
    const queryRuns = Object.fromEntries(names.map(name => [name, screen.queryRuns[name]]));
    if (next[widgetId]?.inputOrder === screen.inputOrder && names.every(name => next[widgetId].queryRuns[name] === queryRuns[name])) continue;
    next[widgetId] = { inputOrder: screen.inputOrder, variables: screen.variables, queryRuns };
    changed = true;
  }
  return changed ? next : current;
}

export function displayedWidgets(
  definition: Pick<DashboardDefinition, "widgets">,
  screen: Pick<DashboardScreenState, "queryRuns" | "inputOrder" | "pending">,
  queryRuns: readonly (Pick<DashboardQueryRun, "queryRunId" | "state"> & { failureMessage?: string | null })[],
  results: Record<string, NamedDashboardResult>,
  bundles: Record<string, DashboardWidgetBundle>,
  unavailableRunIds: ReadonlySet<string>,
  deniedRunIds: ReadonlySet<string>,
  refreshAvailable: boolean,
) {
  const widgetIds = new Set<string>();
  const refreshingIds = new Set<string>();
  const failureIds = new Set<string>();
  const errorMessagesByWidgetId: Record<string, string> = {};
  const loadingMessagesByWidgetId: Record<string, string> = {};
  const resultsByWidgetId: Record<string, Record<string, NamedDashboardResult>> = {};
  const variablesByWidgetId: Record<string, DashboardJsonObject> = {};
  const variableRevisionsByWidgetId: Record<string, number> = {};
  const runs = new Map(queryRuns.map(run => [run.queryRunId, run]));
  for (const [widgetId, widget] of Object.entries(definition.widgets)) {
    const names = widgetQueryNames(widget);
    if (!names.length) continue;
    const bundle = bundles[widgetId];
    const permitted = bundle && !Object.values(bundle.queryRuns).some(id => deniedRunIds.has(id)) && !names.some(name => deniedRunIds.has(screen.queryRuns[name]));
    if (permitted && names.every(name => results[bundle.queryRuns[name]])) {
      widgetIds.add(widgetId);
      resultsByWidgetId[widgetId] = Object.fromEntries(names.map(name => [name, results[bundle.queryRuns[name]]]));
      variablesByWidgetId[widgetId] = bundle.variables;
      variableRevisionsByWidgetId[widgetId] = bundle.inputOrder;
    }
    const failed = names.some(name => {
      const id = screen.queryRuns[name];
      return unavailableRunIds.has(id) || deniedRunIds.has(id) || ["FAILED", "CANCELLED"].includes(runs.get(id)?.state ?? "");
    }) || (bundle && !permitted);
    const changedTarget = Boolean(bundle && names.some(name => bundle.queryRuns[name] !== screen.queryRuns[name]));
    const readFailed = names.some(name => unavailableRunIds.has(screen.queryRuns[name]));
    const running = names.some(name => runs.get(screen.queryRuns[name])?.state === "RUNNING");
    loadingMessagesByWidgetId[widgetId] = widgetIds.has(widgetId) && (screen.pending || changedTarget)
      ? "Updating results…" : running ? "Running query…" : "Loading results…";
    if (failed) {
      failureIds.add(widgetId);
      const kept = widgetIds.has(widgetId);
      errorMessagesByWidgetId[widgetId] = readFailed
        ? kept
          ? changedTarget ? "Updated results could not be loaded. Previous results remain visible." : "Results could not be checked. Displayed results are unchanged."
          : "Results could not be loaded. Please retry."
        : kept && changedTarget ? "The updated query failed. Previous results remain visible." : refreshAvailable ? firstFailureDescription(names.map(name => runs.get(screen.queryRuns[name]))) : "The query failed or was cancelled.";
    }
    else if (screen.pending || !widgetIds.has(widgetId) || bundle.inputOrder !== screen.inputOrder || names.some(name => bundle.queryRuns[name] !== screen.queryRuns[name])) refreshingIds.add(widgetId);
  }
  return { widgetIds, refreshingIds, failureIds, resultsByWidgetId, variablesByWidgetId, variableRevisionsByWidgetId, errorMessagesByWidgetId, loadingMessagesByWidgetId };
}


// A widget that has never shown results explains its own failed run; Retry and Details sit beside it.
function firstFailureDescription(runs: readonly ({ state: string; failureMessage?: string | null } | undefined)[]): string {
  const failed = runs.find(run => run?.state === "FAILED");
  if (failed) return failed.failureMessage ?? "The query failed.";
  return runs.some(run => run?.state === "CANCELLED") ? "The query was cancelled." : "The query failed.";
}

export function widgetResultAccessErrors(
  definition: Pick<DashboardDefinition, "widgets">,
  targets: Record<string, string>,
  previousBundles: Record<string, DashboardWidgetBundle>,
  nextBundles: Record<string, DashboardWidgetBundle>,
  deniedRunIds: ReadonlySet<string>,
  previousErrors: Record<string, string>,
): Record<string, string> {
  const errors = { ...previousErrors };
  for (const [widgetId, widget] of Object.entries(definition.widgets)) {
    const runIds = [...widgetQueryNames(widget).map(name => targets[name]), ...Object.values(previousBundles[widgetId]?.queryRuns ?? {})];
    if (runIds.some(id => deniedRunIds.has(id))) {
      errors[widgetId] = "You do not have access to this result. Check your data permissions.";
    } else if (nextBundles[widgetId]) {
      delete errors[widgetId];
    }
  }
  return errors;
}
