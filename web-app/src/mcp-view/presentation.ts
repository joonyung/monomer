import type { DashboardDefinition, DashboardJsonObject, DashboardJsonValue, DashboardQueryType, DashboardWidget } from "@/api/dashboard";
import { widgetQueryNames } from "@/features/dashboard/widget-query-names";
import type { NamedDashboardResult } from "@/features/dashboard/dashboard-result";
import type { DraftReadyResult } from "./contract";

export type RunState = "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";

// One widget's displayable selection: the run per query and the inputs those runs were made with.
export interface WidgetBundle {
  readonly queryRuns: Readonly<Record<string, string>>;
  readonly parameterBindings: DashboardJsonObject;
  readonly variables: DashboardJsonObject;
  readonly revision: number;
}

export interface TargetRun {
  readonly path: string;
  readonly state: RunState | null;
  readonly failureDetail: string | null;
  readonly unavailable: boolean;
}

export type ResultEntry =
  | { readonly kind: "loaded"; readonly result: NamedDashboardResult }
  | { readonly kind: "unavailable" };

export interface DashboardLayoutContent {
  readonly definition: DashboardDefinition;
  readonly queryTypes: Readonly<Record<string, DashboardQueryType>>;
  readonly parameters: DashboardJsonObject;
  readonly variables: DashboardJsonObject;
  readonly variableRevision: number;
  readonly parametersByWidgetId: Record<string, DashboardJsonObject>;
  readonly variablesByWidgetId: Record<string, DashboardJsonObject>;
  readonly variableRevisionsByWidgetId: Record<string, number>;
  readonly resultsByWidgetId: Record<string, Record<string, NamedDashboardResult>>;
  readonly displayedWidgetIds: ReadonlySet<string>;
  readonly refreshingWidgetIds: ReadonlySet<string>;
  readonly failureWidgetIds: ReadonlySet<string>;
  readonly errorMessagesByWidgetId: Record<string, string>;
  readonly loadingMessagesByWidgetId: Record<string, string>;
}

export const RESULT_NOT_SHOWN_MESSAGE = "This result can't be shown here.";
const MISSING_RUN_MESSAGE = "This widget has no result in this view.";

const queryTypesByDefinition = new WeakMap<DashboardDefinition, Readonly<Record<string, DashboardQueryType>>>();
function queryTypesOf(definition: DashboardDefinition): Readonly<Record<string, DashboardQueryType>> {
  let queryTypes = queryTypesByDefinition.get(definition);
  if (!queryTypes) {
    queryTypes = Object.fromEntries(Object.entries(definition.queries).map(([name, query]) => [name, query.type]));
    queryTypesByDefinition.set(definition, queryTypes);
  }
  return queryTypes;
}

function queryWidgets(definition: Pick<DashboardDefinition, "widgets">): [string, DashboardWidget, string[]][] {
  return Object.entries(definition.widgets).flatMap(([widgetId, widget]) => {
    const names = widgetQueryNames(widget);
    return names.length > 0 ? [[widgetId, widget, names] as [string, DashboardWidget, string[]]] : [];
  });
}

export function jsonValuesEqual(left: DashboardJsonValue | undefined, right: DashboardJsonValue | undefined): boolean {
  if (left === right) return true;
  if (left === null || right === null || left === undefined || right === undefined || typeof left !== "object" || typeof right !== "object") return false;
  if (Array.isArray(left) || Array.isArray(right)) {
    return Array.isArray(left) && Array.isArray(right) && left.length === right.length
      && left.every((value, index) => jsonValuesEqual(value as DashboardJsonValue, right[index] as DashboardJsonValue));
  }
  const leftRecord = left as Record<string, DashboardJsonValue>;
  const rightRecord = right as Record<string, DashboardJsonValue>;
  const keys = Object.keys(leftRecord);
  return keys.length === Object.keys(rightRecord).length
    && keys.every((key) => Object.hasOwn(rightRecord, key) && jsonValuesEqual(leftRecord[key], rightRecord[key]));
}

export function sameBundle(left: WidgetBundle, right: WidgetBundle): boolean {
  return left.revision === right.revision
    && jsonValuesEqual(left.queryRuns, right.queryRuns)
    && jsonValuesEqual(left.parameterBindings, right.parameterBindings)
    && jsonValuesEqual(left.variables, right.variables);
}

export function bundleFromReadyResult(ready: DraftReadyResult): WidgetBundle {
  return {
    queryRuns: ready.queryRuns,
    parameterBindings: ready.parameterBindings,
    variables: ready.variables,
    revision: Number(ready.authoringRevision),
  };
}

// Keeps unchanged bundles by reference so widgets do not re-render a frame for an equal poll result.
export function reconcileBundles(
  previous: Readonly<Record<string, WidgetBundle>>,
  next: Readonly<Record<string, WidgetBundle>>,
): Record<string, WidgetBundle> {
  return Object.fromEntries(Object.entries(next).map(([widgetId, bundle]) => {
    const kept = previous[widgetId];
    return [widgetId, kept && sameBundle(kept, bundle) ? kept : bundle];
  }));
}

function loadedResult(results: ReadonlyMap<string, ResultEntry>, runPath: string | undefined): NamedDashboardResult | undefined {
  const entry = runPath === undefined ? undefined : results.get(runPath);
  return entry?.kind === "loaded" ? entry.result : undefined;
}

function bundleLoaded(names: readonly string[], bundle: WidgetBundle, results: ReadonlyMap<string, ResultEntry>): boolean {
  return names.every((name) => loadedResult(results, bundle.queryRuns[name]) !== undefined);
}

function bundleUnavailable(names: readonly string[], bundle: WidgetBundle | undefined, results: ReadonlyMap<string, ResultEntry>): boolean {
  return bundle !== undefined && names.some((name) => results.get(bundle.queryRuns[name])?.kind === "unavailable");
}

// A widget shows the newest ready bundle whose results are all loaded. While a newer bundle hydrates,
// the previously shown bundle stays; a bundle whose result cannot be shown drops the widget instead.
export function adoptBundles(
  definition: Pick<DashboardDefinition, "widgets">,
  candidates: Readonly<Record<string, WidgetBundle>>,
  results: ReadonlyMap<string, ResultEntry>,
  adopted: Readonly<Record<string, WidgetBundle>>,
): Record<string, WidgetBundle> {
  const next: Record<string, WidgetBundle> = {};
  for (const [widgetId, , names] of queryWidgets(definition)) {
    const candidate = candidates[widgetId];
    const previous = adopted[widgetId];
    if (bundleUnavailable(names, candidate, results)) continue;
    if (candidate && bundleLoaded(names, candidate, results)) next[widgetId] = previous && sameBundle(previous, candidate) ? previous : candidate;
    else if (previous && bundleLoaded(names, previous, results)) next[widgetId] = previous;
  }
  return next;
}

function failureMessage(targets: readonly (TargetRun | undefined)[]): string {
  const failed = targets.find((target) => target?.state === "FAILED");
  if (failed) return failed.failureDetail ?? "The query failed.";
  return "The query was cancelled.";
}

function terminalFailure(target: TargetRun | undefined): boolean {
  return target?.state === "FAILED" || target?.state === "CANCELLED";
}

interface WidgetPresentation {
  bundle: WidgetBundle | undefined;
  refreshing: boolean;
  failed: readonly TargetRun[];
  notShownMessage: string | null;
  running: boolean;
  unstarted: boolean;
}

function loadingMessage(presentation: WidgetPresentation): string {
  if (presentation.running) return "Running query…";
  return presentation.unstarted ? "Waiting for results…" : "Loading results…";
}

function layoutContent(
  base: Pick<DashboardLayoutContent, "definition" | "parameters" | "variables" | "variableRevision">,
  widgets: readonly [string, string[], WidgetPresentation][],
  results: ReadonlyMap<string, ResultEntry>,
  perWidgetInputs: boolean,
): DashboardLayoutContent {
  const displayedWidgetIds = new Set<string>();
  const refreshingWidgetIds = new Set<string>();
  const failureWidgetIds = new Set<string>();
  const errorMessagesByWidgetId: Record<string, string> = {};
  const loadingMessagesByWidgetId: Record<string, string> = {};
  const resultsByWidgetId: Record<string, Record<string, NamedDashboardResult>> = {};
  const parametersByWidgetId: Record<string, DashboardJsonObject> = {};
  const variablesByWidgetId: Record<string, DashboardJsonObject> = {};
  const variableRevisionsByWidgetId: Record<string, number> = {};
  for (const [widgetId, names, presentation] of widgets) {
    const { bundle } = presentation;
    if (bundle) {
      displayedWidgetIds.add(widgetId);
      resultsByWidgetId[widgetId] = Object.fromEntries(names.map((name) => [name, loadedResult(results, bundle.queryRuns[name])!]));
      if (perWidgetInputs) {
        parametersByWidgetId[widgetId] = bundle.parameterBindings;
        variablesByWidgetId[widgetId] = bundle.variables;
        variableRevisionsByWidgetId[widgetId] = bundle.revision;
      }
    }
    if (presentation.refreshing) refreshingWidgetIds.add(widgetId);
    if (presentation.notShownMessage !== null) {
      failureWidgetIds.add(widgetId);
      errorMessagesByWidgetId[widgetId] = presentation.notShownMessage;
    } else if (presentation.failed.length > 0) {
      failureWidgetIds.add(widgetId);
      if (!bundle) errorMessagesByWidgetId[widgetId] = failureMessage(presentation.failed);
    }
    loadingMessagesByWidgetId[widgetId] = loadingMessage(presentation);
  }
  return {
    ...base,
    queryTypes: queryTypesOf(base.definition),
    parametersByWidgetId,
    variablesByWidgetId,
    variableRevisionsByWidgetId,
    resultsByWidgetId,
    displayedWidgetIds,
    refreshingWidgetIds,
    failureWidgetIds,
    errorMessagesByWidgetId,
    loadingMessagesByWidgetId,
  };
}

export interface DraftPresentationInput {
  definition: DashboardDefinition;
  parameterBindings: DashboardJsonObject;
  variables: DashboardJsonObject;
  revision: number;
  readyBundles: Readonly<Record<string, WidgetBundle>>;
  adoptedBundles: Readonly<Record<string, WidgetBundle>>;
  targets: Readonly<Record<string, TargetRun>>;
  results: ReadonlyMap<string, ResultEntry>;
}

// Mirrors the Builder: each widget shows its ready bundle's results with that bundle's Parameters and
// Variables, refreshes while a newer target run replaces it, and fails when its target run failed.
export function draftLayoutContent(input: DraftPresentationInput): DashboardLayoutContent {
  const widgets = queryWidgets(input.definition).map(([widgetId, , names]): [string, string[], WidgetPresentation] => {
    const bundle = input.adoptedBundles[widgetId];
    const targets = names.map((name) => input.targets[name]);
    const notShown = bundleUnavailable(names, input.readyBundles[widgetId], input.results);
    return [widgetId, names, {
      bundle: notShown ? undefined : bundle,
      refreshing: !notShown && bundle !== undefined && names.some((name) => input.targets[name] !== undefined && input.targets[name].path !== bundle.queryRuns[name]),
      failed: targets.filter((target): target is TargetRun => terminalFailure(target)),
      notShownMessage: notShown ? RESULT_NOT_SHOWN_MESSAGE : null,
      running: targets.some((target) => target?.state === "RUNNING"),
      unstarted: input.readyBundles[widgetId] === undefined && targets.every((target) => target === undefined),
    }];
  });
  return layoutContent({
    definition: input.definition,
    parameters: input.parameterBindings,
    variables: input.variables,
    variableRevision: input.revision,
  }, widgets, input.results, true);
}

export interface SessionPresentationInput {
  definition: DashboardDefinition;
  parameterBindings: DashboardJsonObject;
  variables: DashboardJsonObject;
  targets: Readonly<Record<string, TargetRun>>;
  results: ReadonlyMap<string, ResultEntry>;
}

// Mirrors the Viewer for one shown selection: the passed runs are immutable snapshots, so a widget
// waits while a run is RUNNING, shows its results once SUCCEEDED, and fails when it ends otherwise.
export function sessionLayoutContent(input: SessionPresentationInput): DashboardLayoutContent {
  const widgets = queryWidgets(input.definition).map(([widgetId, , names]): [string, string[], WidgetPresentation] => {
    const targets = names.map((name) => input.targets[name]);
    const missing = targets.some((target) => target === undefined);
    const queryRuns = Object.fromEntries(names.map((name) => [name, input.targets[name]?.path ?? ""]));
    const bundle: WidgetBundle = { queryRuns, parameterBindings: input.parameterBindings, variables: input.variables, revision: 0 };
    const notShown = !missing && targets.some((target) => target!.unavailable || input.results.get(target!.path)?.kind === "unavailable");
    return [widgetId, names, {
      bundle: !missing && !notShown && bundleLoaded(names, bundle, input.results) ? bundle : undefined,
      refreshing: false,
      failed: targets.filter((target): target is TargetRun => terminalFailure(target)),
      notShownMessage: missing ? MISSING_RUN_MESSAGE : notShown ? RESULT_NOT_SHOWN_MESSAGE : null,
      running: targets.some((target) => target?.state === "RUNNING"),
      unstarted: false,
    }];
  });
  return layoutContent({
    definition: input.definition,
    parameters: input.parameterBindings,
    variables: input.variables,
    variableRevision: 0,
  }, widgets, input.results, false);
}

export function runningQueryCount(targets: Readonly<Record<string, TargetRun>>): number {
  return new Set(Object.values(targets).filter((target) => target.state === "RUNNING").map((target) => target.path)).size;
}

// Results load in layout order so the first widgets a reader sees fill in first.
export function widgetIdsInLayoutOrder(definition: Pick<DashboardDefinition, "layout" | "widgets">): string[] {
  const ordered: string[] = [];
  type Node = DashboardDefinition["layout"]["sections"][number]["root"];
  const visit = (node: Node) => {
    if (node.type === "WIDGET") ordered.push(node.widgetId);
    else if (node.type === "STACK") node.children.forEach((child) => visit(child.node));
  };
  definition.layout.sections.forEach((section) => visit(section.root));
  return [...new Set([...ordered, ...Object.keys(definition.widgets)])];
}

export function runPathsInLayoutOrder(
  definition: Pick<DashboardDefinition, "layout" | "widgets">,
  runPathsForWidget: (widgetId: string, queryNames: readonly string[]) => readonly (string | undefined)[],
): string[] {
  const paths: string[] = [];
  for (const widgetId of widgetIdsInLayoutOrder(definition)) {
    const widget = definition.widgets[widgetId];
    if (!widget) continue;
    const names = widgetQueryNames(widget);
    for (const path of runPathsForWidget(widgetId, names)) if (path !== undefined) paths.push(path);
  }
  return [...new Set(paths)];
}
