import type { ActivityItem, ActivityPage } from "@/api/overview";

export type ObservedRun = { item: ActivityItem; terminalObservedAt: number | null; lastObservedAt: number };
export const activityId = (item: ActivityItem) => `${item.kind}:${item.id}`;
export const isActive = (item: ActivityItem) => item.state === "RUNNING" || item.state === "CANCEL_REQUESTED";

export function reconcileRuns(
  previous: ObservedRun[], items: ActivityItem[], watched: ActivityItem[],
  checked: string[], unavailable: ActivityPage["unavailableSources"], now: number,
): { existing: ObservedRun[]; arrivals: ObservedRun[] } {
  const current = new Map([...items, ...watched].map((item) => [activityId(item), item]));
  const checkedIds = new Set(checked);
  const watchedById = new Map(watched.map((item) => [activityId(item), item]));
  const priorIds = new Set(previous.map(({ item }) => activityId(item)));
  const existing = previous.flatMap((row) => {
    const key = activityId(row.item);
    if (checkedIds.has(key) && !watchedById.has(key)) return unavailable.includes(row.item.kind) ? [row] : [];
    const item = current.get(key);
    if (!item) return checkedIds.has(key) && !unavailable.includes(row.item.kind) ? [] : [row];
    return [{ item, lastObservedAt: now, terminalObservedAt: isActive(item) ? null : row.terminalObservedAt ?? now }];
  });
  const arrivals = items.filter((item) => !priorIds.has(activityId(item)) && isActive(item)
    && (!checkedIds.has(activityId(item)) || watchedById.has(activityId(item)) || unavailable.includes(item.kind)))
    .map((item) => ({ item, terminalObservedAt: null, lastObservedAt: now }));
  return { existing, arrivals: [...new Map(arrivals.map((row) => [activityId(row.item), row])).values()] };
}

export function insertArrivals(rows: ObservedRun[], arrivals: ObservedRun[], protectedIds: Set<string>): ObservedRun[] {
  const lastProtected = rows.reduce((last, row, index) => protectedIds.has(activityId(row.item)) ? index : last, -1);
  const known = new Set(rows.map(({ item }) => activityId(item)));
  const fresh = arrivals.filter(({ item }) => !known.has(activityId(item)));
  return [...rows.slice(0, lastProtected + 1), ...fresh, ...rows.slice(lastProtected + 1)];
}

export function pruneRuns(rows: ObservedRun[], now: number, protectedIds: Set<string>): ObservedRun[] {
  return rows.filter((row) => row.terminalObservedAt === null || now - row.terminalObservedAt < 60_000 || protectedIds.has(activityId(row.item)));
}
