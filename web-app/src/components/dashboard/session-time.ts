export function sessionCreatedLabel(value: string, now: number): string {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return "—";
  const today = new Date(now);
  const yesterday = new Date(today);
  yesterday.setDate(today.getDate() - 1);
  const time = new Intl.DateTimeFormat("en-US", { hour: "numeric", minute: "2-digit" }).format(date);
  if (date.toDateString() === today.toDateString()) return `Today, ${time}`;
  if (date.toDateString() === yesterday.toDateString()) return `Yesterday, ${time}`;
  return new Intl.DateTimeFormat("en-US", {
    year: date.getFullYear() !== today.getFullYear() ? "numeric" : undefined,
    month: "short", day: "numeric", hour: "numeric", minute: "2-digit",
  }).format(date);
}

export function sessionExactTime(value: string): string {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return "—";
  return new Intl.DateTimeFormat("en-US", {
    year: "numeric", month: "short", day: "numeric",
    hour: "numeric", minute: "2-digit", second: "2-digit", timeZoneName: "short",
  }).format(date);
}
