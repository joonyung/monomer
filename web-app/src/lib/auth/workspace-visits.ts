// When this browser last opened each workspace, per signed-in user. It is a convenience for the
// Workspaces list and landing, never access authority: callers still match ids against the
// accessible workspaces. Storage can be unavailable, so every read and write degrades to nothing.
const STORAGE_KEY_PREFIX = "monomer.workspace.visits.";
const MAXIMUM_REMEMBERED_WORKSPACES = 50;

export type WorkspaceVisits = Record<string, string>;

function storageKey(userId: string): string {
  return `${STORAGE_KEY_PREFIX}${userId}`;
}

export function readWorkspaceVisits(userId: string): WorkspaceVisits {
  if (typeof window === "undefined") return {};
  try {
    const raw = window.localStorage.getItem(storageKey(userId));
    if (!raw) return {};
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return {};
    return Object.fromEntries(
      Object.entries(parsed).filter(
        (entry): entry is [string, string] => typeof entry[1] === "string" && !Number.isNaN(Date.parse(entry[1])),
      ),
    );
  } catch {
    return {};
  }
}

export function recordWorkspaceVisit(userId: string, workspaceId: string, at: Date): void {
  if (typeof window === "undefined") return;
  const visits = { ...readWorkspaceVisits(userId), [workspaceId]: at.toISOString() };
  const kept = Object.entries(visits)
    .sort((left, right) => Date.parse(right[1]) - Date.parse(left[1]))
    .slice(0, MAXIMUM_REMEMBERED_WORKSPACES);
  try {
    window.localStorage.setItem(storageKey(userId), JSON.stringify(Object.fromEntries(kept)));
  } catch {
    return;
  }
}

export function latestVisitedWorkspaceId(visits: WorkspaceVisits): string | null {
  const latest = Object.entries(visits).sort((left, right) => Date.parse(right[1]) - Date.parse(left[1]))[0];
  return latest ? latest[0] : null;
}

function localDay(date: Date): number {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
}

// "Opened today", "Opened yesterday", then the date: "Sep 29", or "Sep 29, 2025" in another year.
export function lastOpenedLabel(openedAt: string, now: Date): string {
  const opened = new Date(openedAt);
  const days = Math.round((localDay(now) - localDay(opened)) / 86_400_000);
  if (days <= 0) return "Opened today";
  if (days === 1) return "Opened yesterday";
  return opened.toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    ...(opened.getFullYear() === now.getFullYear() ? {} : { year: "numeric" }),
  });
}
