import type { DashboardWidget } from "@/api/dashboard";

// Every query a widget reads must succeed before its ready bundle is shown, so TABLE totals count too.
export function widgetQueryNames(widget: DashboardWidget): string[] {
  if (widget.type === "CHART") return widget.queryNames;
  if (widget.type !== "TABLE") return [];
  const totals = "pivot" in widget ? widget.pivot.totals ?? [] : [];
  return [...new Set([widget.queryName, ...(widget.module?.inputs.queries ?? []), ...totals])];
}
