import type { DashboardDraftResponse, DashboardJsonObject } from "@/api/dashboard";
import { changeFingerprint } from "@/lib/change-highlight";
import type { DashboardBuilderDisplayState } from "./presentation";

function references(value: unknown, parameters: Set<string>, variables: Set<string>) {
  if (!value || typeof value !== "object") return;
  if (Array.isArray(value)) { value.forEach(item => references(item, parameters, variables)); return; }
  const item = value as Record<string, unknown>;
  if (typeof item.name === "string") {
    if (item.kind === "PARAMETER") parameters.add(item.name);
    if (item.kind === "VARIABLE") variables.add(item.name);
  }
  Object.values(item).forEach(child => references(child, parameters, variables));
}

export function compatibleDraftWidgetInput(
  widgetId: string,
  frame: DashboardDraftResponse,
  display: DashboardBuilderDisplayState,
  current: DashboardDraftResponse,
  values: DashboardJsonObject,
): boolean {
  const widget = frame.definition.widgets[widgetId];
  if (!widget || widget.type !== "CHART" || !display.displayedWidgetIds.has(widgetId) && widget.queryNames.length > 0) return false;
  if (frame.workspaceId !== current.workspaceId || frame.dashboardId !== current.dashboardId ||
      changeFingerprint(widget) !== changeFingerprint(current.definition.widgets[widgetId])) return false;
  const parameters = new Set(widget.module.inputs.parameters);
  const variables = new Set(widget.module.inputs.variables);
  if (!Object.keys(values).length || Object.keys(values).some(name => !variables.has(name))) return false;
  for (const name of widget.queryNames) {
    const query = frame.definition.queries[name];
    if (changeFingerprint(query) !== changeFingerprint(current.definition.queries[name])) return false;
    if (!current.targetQueryRuns[name] || display.resultsByWidgetId[widgetId]?.[name]?.queryRunId !== current.targetQueryRuns[name]) return false;
    references(query.type === "SQL" ? query.bindings : query.query, parameters, variables);
  }
  const shownParameters = display.parametersByWidgetId[widgetId] ?? frame.parameterBindings;
  const shownVariables = display.variablesByWidgetId[widgetId] ?? frame.variables;
  return [...parameters].every(name => changeFingerprint(shownParameters[name]) === changeFingerprint(current.parameterBindings[name]) &&
    changeFingerprint(frame.definition.parameters[name]) === changeFingerprint(current.definition.parameters[name])) &&
    [...variables].every(name => changeFingerprint(shownVariables[name]) === changeFingerprint(current.variables[name]) &&
      changeFingerprint(frame.definition.variables[name]) === changeFingerprint(current.definition.variables[name]));
}
