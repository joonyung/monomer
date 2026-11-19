// How many leading chips fit beside a "+N" control that reveals the rest.
export function fittingInputChipCount(chipWidths: readonly number[], available: number, gap: number, overflowWidth: number): number {
  const used = (count: number) => chipWidths.slice(0, count).reduce((sum, width) => sum + width, 0) + gap * Math.max(0, count - 1);
  if (used(chipWidths.length) <= available) return chipWidths.length;
  for (let count = chipWidths.length - 1; count > 0; count--) {
    if (used(count) + gap + overflowWidth <= available) return count;
  }
  return 0;
}

export type DashboardResultsStatus = { kind: "running" } | { kind: "results"; at: string } | { kind: "none" };

// A running displayed target outranks the time of the results it will replace.
export function dashboardResultsStatus(resultsAt: string | null, running: boolean): DashboardResultsStatus {
  if (running) return { kind: "running" };
  return resultsAt ? { kind: "results", at: resultsAt } : { kind: "none" };
}

export function scheduledRunTime(value: string, now: number): string {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return value;
  const day = new Intl.DateTimeFormat("en-US", {
    weekday: "short", month: "short", day: "numeric",
    year: date.getFullYear() !== new Date(now).getFullYear() ? "numeric" : undefined,
  }).format(date);
  const time = new Intl.DateTimeFormat("en-US", { hour: "numeric", minute: "2-digit" }).format(date);
  return `${day} · ${time}`;
}
