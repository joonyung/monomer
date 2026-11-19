import { BarChart3, Layers, LayoutDashboard, Table as TableIcon } from "lucide-react";
import type { LineageResourceKind } from "@/api/lineage";

const KIND_ICONS = { RELATION: TableIcon, SEMANTIC_MODEL: Layers, METRIC: BarChart3, DASHBOARD: LayoutDashboard } as const;

export function KindIcon({ kind }: { kind: LineageResourceKind | "DASHBOARD" }) {
  const Icon = KIND_ICONS[kind];
  return <span className="relative z-10 grid h-6 w-6 shrink-0 place-items-center rounded-[4px] border border-border bg-surface" aria-hidden="true"><Icon className="h-3.5 w-3.5 text-label" /></span>;
}
