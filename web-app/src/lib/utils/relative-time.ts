export function formatRelativeTime(value: string | number, now: number = Date.now()): string {
  const date = new Date(value);
  const elapsed = Math.max(0, now - date.getTime());
  if (elapsed < 60_000) return "Just now";
  if (elapsed < 3_600_000) return `${Math.floor(elapsed / 60_000)}m ago`;
  if (elapsed < 86_400_000) return `${Math.floor(elapsed / 3_600_000)}h ago`;
  if (elapsed < 604_800_000) return `${Math.floor(elapsed / 86_400_000)}d ago`;
  return date.toLocaleDateString("en-US", { month: "short", day: "numeric" });
}
