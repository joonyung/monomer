import type { DashboardDefinition, DashboardJsonObject } from "@/api/dashboard";
import { sessionCreatedLabel } from "@/components/dashboard/session-time";
import {
  dashboardParameterLabel,
  formatDashboardParameterValue,
  formatUnknownDashboardParameterValue,
} from "@/features/dashboard/dashboard-parameter-presentation";

export interface CaptureInput {
  label: string;
  value: string;
}

export interface CaptureProvenance {
  title: string;
  description: string | null;
  inputs: readonly CaptureInput[];
  resultsAt: string | null;
  source: string | null;
}

export function dashboardInputs(
  parameters: DashboardJsonObject,
  definitions: DashboardDefinition["parameters"],
): CaptureInput[] {
  return Object.entries(parameters).map(([name, value]) => ({
    label: definitions[name]?.label ?? dashboardParameterLabel(name),
    value: definitions[name] ? formatDashboardParameterValue(definitions[name], value) : formatUnknownDashboardParameterValue(value),
  }));
}

export function latestDisplayedResultAt(
  resultsByWidgetId: Readonly<Record<string, Readonly<Record<string, { queryRunId: string }>>>>,
  runs: readonly { queryRunId: string; state: string; updatedAt: string }[],
): string | null {
  const displayedRunIds = new Set(Object.values(resultsByWidgetId).flatMap(results => Object.values(results).map(result => result.queryRunId)));
  let latest: string | null = null;
  for (const run of runs) {
    if (run.state !== "SUCCEEDED" || !displayedRunIds.has(run.queryRunId)) continue;
    if (latest === null || Date.parse(run.updatedAt) > Date.parse(latest)) latest = run.updatedAt;
  }
  return latest;
}

/** "Times in Asia/Seoul": the zones the displayed Semantic results grouped days and read times in. */
export function displayedResultsTimeZoneLabel(
  resultsByWidgetId: Readonly<Record<string, Readonly<Record<string, { reportingTimeZone: string | null }>>>>,
): string | null {
  const zones = new Set(Object.values(resultsByWidgetId).flatMap(results => Object.values(results).flatMap(result => result.reportingTimeZone ? [result.reportingTimeZone] : [])));
  return zones.size > 0 ? `Times in ${[...zones].sort().join(", ")}` : null;
}

export function resultsFromLabel(value: string, now: number): string {
  const label = sessionCreatedLabel(value, now).replace(/^(Today|Yesterday),/, match => match.toLowerCase());
  return `Results from ${label}`;
}

export function captureSourceLine(source: string | null): string {
  return source ? `${source} · Built with Monomer` : "Built with Monomer";
}
