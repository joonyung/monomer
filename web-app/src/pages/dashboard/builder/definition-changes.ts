import type { DashboardDefinition, DashboardLayoutNode, DashboardWidget } from "@/api/dashboard";
import { scheduleSummary } from "@/features/dashboard/schedule-summary";

export type DefinitionChangeKind = "Added" | "Changed" | "Removed";

export interface DefinitionChange {
  kind: DefinitionChangeKind;
  label: string;
}

const KIND_ORDER: Record<DefinitionChangeKind, number> = { Added: 0, Changed: 1, Removed: 2 };

const WIDGET_TYPE_LABEL: Record<DashboardWidget["type"], string> = {
  CHART: "Chart",
  TABLE: "Table",
  TEXT: "Text",
  INPUT: "Input control",
};

const QUERY_FIELD_LABEL: Record<string, string> = {
  connectorId: "connector",
  metrics: "metrics",
  groupBys: "grouping",
  metricTimeRange: "time range",
  filters: "filters",
  orderBy: "sort",
  rowLimit: "row limit",
  type: "query type",
  sql: "SQL",
  bindings: "bindings",
  columns: "columns",
};

function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical);
  if (value !== null && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical((value as Record<string, unknown>)[key])]));
  }
  return value;
}

// Key order is not meaning: the shared Draft and the applied definition may serialize objects differently.
export function sameDefinitionValue(left: unknown, right: unknown): boolean {
  return JSON.stringify(canonical(left)) === JSON.stringify(canonical(right));
}

function widgetLabel(id: string, widget: DashboardWidget): string {
  const title = "title" in widget && widget.title ? widget.title : id;
  return `${WIDGET_TYPE_LABEL[widget.type]} · ${title}`;
}

function changedQueryFields(left: Record<string, unknown>, right: Record<string, unknown>): string[] {
  const fields = new Set<string>();
  for (const key of new Set([...Object.keys(left), ...Object.keys(right)])) {
    if (sameDefinitionValue(left[key], right[key])) continue;
    fields.add(QUERY_FIELD_LABEL[key] ?? "definition");
  }
  return [...fields];
}

function compareEntries<T>(
  subjectLabel: (key: string, value: T) => string,
  applied: Readonly<Record<string, T>>,
  draft: Readonly<Record<string, T>>,
  changedLabel: (key: string, before: T, after: T) => string = (key, _before, after) => subjectLabel(key, after),
): DefinitionChange[] {
  const changes: DefinitionChange[] = [];
  for (const [key, value] of Object.entries(draft)) {
    if (!Object.hasOwn(applied, key)) changes.push({ kind: "Added", label: subjectLabel(key, value) });
    else if (!sameDefinitionValue(applied[key], value)) changes.push({ kind: "Changed", label: changedLabel(key, applied[key], value) });
  }
  for (const [key, value] of Object.entries(applied)) {
    if (!Object.hasOwn(draft, key)) changes.push({ kind: "Removed", label: subjectLabel(key, value) });
  }
  return changes;
}

function widgetPlacements(definition: DashboardDefinition): Map<string, string> {
  const placements = new Map<string, unknown[]>();
  const visit = (node: DashboardLayoutNode, path: unknown[]) => {
    if (node.type === "WIDGET") placements.set(node.widgetId, [...(placements.get(node.widgetId) ?? []), path]);
    else if (node.type === "STACK") node.children.forEach((child, index) => visit(child.node, [...path, {
      direction: node.direction, separation: node.separation ?? "GAP", index, weights: node.children.map((entry) => entry.weight ?? null),
    }]));
  };
  definition.layout.sections.forEach((section, index) => visit(section.root, [{ index, height: section.height }]));
  return new Map([...placements].map(([id, paths]) => [id, JSON.stringify(canonical(paths))]));
}

// Adding or removing a widget necessarily touches the layout; only moved or resized shared widgets count as a layout change.
function layoutChanged(applied: DashboardDefinition, draft: DashboardDefinition): boolean {
  const before = widgetPlacements(applied);
  const after = widgetPlacements(draft);
  return [...before].some(([id, placement]) => after.has(id) && after.get(id) !== placement);
}

export function summarizeDefinitionChanges(applied: DashboardDefinition, draft: DashboardDefinition): DefinitionChange[] {
  if (sameDefinitionValue(applied, draft)) return [];
  const changes: DefinitionChange[] = [
    ...compareEntries((id, widget: DashboardWidget) => widgetLabel(id, widget), applied.widgets, draft.widgets),
    ...compareEntries(
      (name) => `Query · ${name}`,
      applied.queries,
      draft.queries,
      (name, before, after) => {
        const fields = changedQueryFields(
          before.type === "SEMANTIC" ? { type: before.type, connectorId: before.connectorId, ...before.query } : before,
          after.type === "SEMANTIC" ? { type: after.type, connectorId: after.connectorId, ...after.query } : after,
        );
        return fields.length > 0 ? `Query · ${name} (${fields.join(", ")})` : `Query · ${name}`;
      },
    ),
    ...compareEntries((name, parameter: DashboardDefinition["parameters"][string]) => `Input · ${parameter.label ?? name}`, applied.parameters, draft.parameters),
    ...compareEntries((name, variable: DashboardDefinition["variables"][string]) => `Variable · ${variable.label ?? name}`, applied.variables, draft.variables),
    ...compareEntries(
      (key, schedule: NonNullable<DashboardDefinition["schedules"]>[string]) => `Schedule · ${key} (${scheduleSummary(schedule.cron)})`,
      applied.schedules ?? {},
      draft.schedules ?? {},
    ),
  ];
  if (layoutChanged(applied, draft)) changes.push({ kind: "Changed", label: "Layout" });
  if (applied.meta.title !== draft.meta.title) changes.push({ kind: "Changed", label: "Title" });
  if ((applied.meta.description ?? null) !== (draft.meta.description ?? null)) changes.push({ kind: "Changed", label: "Description" });
  if (changes.length === 0) changes.push({ kind: "Changed", label: "Definition" });
  return changes
    .map((change, index) => ({ change, index }))
    .sort((left, right) => KIND_ORDER[left.change.kind] - KIND_ORDER[right.change.kind] || left.index - right.index)
    .map(({ change }) => change);
}

const SUMMARY_NOUNS: Record<string, readonly [string, string] | null> = {
  [WIDGET_TYPE_LABEL.CHART]: ["widget", "widgets"],
  [WIDGET_TYPE_LABEL.TABLE]: ["widget", "widgets"],
  [WIDGET_TYPE_LABEL.TEXT]: ["widget", "widgets"],
  [WIDGET_TYPE_LABEL.INPUT]: ["widget", "widgets"],
  Query: ["query", "queries"],
  Input: ["input", "inputs"],
  Variable: ["variable", "variables"],
  Schedule: ["schedule", "schedules"],
  Layout: null,
  Title: null,
  Description: null,
  Definition: null,
};

const KIND_VERB: Record<DefinitionChangeKind, string> = { Added: "added", Changed: "changed", Removed: "removed" };

/** One short line for the Discard confirmation, e.g. "2 widgets added, 1 query changed, layout changed". */
export function definitionChangeSummary(changes: readonly DefinitionChange[]): string {
  const counts = new Map<string, { kind: DefinitionChangeKind; noun: readonly [string, string] | null; subject: string; count: number }>();
  for (const change of changes) {
    const subject = change.label.split(" · ")[0];
    const noun = Object.hasOwn(SUMMARY_NOUNS, subject) ? SUMMARY_NOUNS[subject] : null;
    const groupKey = `${change.kind}:${noun ? noun[0] : subject}`;
    const current = counts.get(groupKey);
    if (current) current.count += 1;
    else counts.set(groupKey, { kind: change.kind, noun, subject, count: 1 });
  }
  return [...counts.values()].map(({ kind, noun, subject, count }) => noun
    ? `${count} ${count === 1 ? noun[0] : noun[1]} ${KIND_VERB[kind]}`
    : `${subject.toLowerCase()} ${KIND_VERB[kind]}`).join(", ");
}
