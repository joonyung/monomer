import { formatRelativeTime } from "@/lib/utils/relative-time";

export function formatLastRefreshed(
  epochMs: number | null | undefined,
  now: number = Date.now(),
): string | null {
  if (epochMs === null || epochMs === undefined || epochMs <= 0) return null;
  return formatRelativeTime(epochMs, now);
}
