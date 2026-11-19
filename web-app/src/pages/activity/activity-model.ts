import type { ActivityItem, ActivityPage } from "@/api/overview";
import { activityId } from "@/pages/overview/observation";

export const ACTIVITY_PAGE_SIZE = 20;
export const RUNNING_LIMIT = 20;

/** Running work first, then finished runs and changes newest first; a run that just finished shows once, finished. */
export function activityRows(running: ActivityItem[], recentPages: ActivityPage[]): ActivityItem[] {
  const finished = new Map<string, ActivityItem>();
  for (const item of recentPages.flatMap((page) => page.items)) if (!finished.has(activityId(item))) finished.set(activityId(item), item);
  const runningIds = new Set<string>();
  const stillRunning = running.filter((item) => {
    const key = activityId(item);
    if (finished.has(key) || runningIds.has(key)) return false;
    runningIds.add(key);
    return true;
  });
  return [...stillRunning, ...finished.values()];
}

