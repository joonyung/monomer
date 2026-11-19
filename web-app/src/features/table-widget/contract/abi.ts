import type { DashboardFrameColumn } from "@/features/dashboard/dashboard-result";

export type RawCell = string | boolean | null;
export type RawRow = Readonly<Record<string, RawCell>>;
export type TableValue = RawCell | number | readonly TableValue[] | { readonly [name: string]: TableValue };
export type TableFrame = Readonly<{
  queries: Readonly<Record<string, Readonly<{
    columns: readonly DashboardFrameColumn[];
    rows: readonly RawRow[];
  }>>>;
  parameters: Readonly<Record<string, TableValue>>;
  variables: Readonly<Record<string, TableValue>>;
  locale: string;
  rowQueryName: string;
}>;
export type CellView = Readonly<{
  text: string;
  color?: string;
  background?: string;
  emphasis?: "normal" | "strong";
  align?: "start" | "center" | "end";
  tooltip?: string;
  graphic?: Readonly<{ svg: string }>;
}>;
export type RowView = Readonly<{ background?: string }>;
export type TablePresentation = Readonly<{
  row?: (input: Readonly<{ row: RawRow; rowIndex: number }>) => RowView | undefined;
  cell?: (input: Readonly<{ field: string; value: RawCell; row: RawRow; rowIndex: number; displayText: string }>) => CellView | undefined;
}>;
export type TableModule = Readonly<{ render(frame: TableFrame): TablePresentation }>;

export function defineTable(module: TableModule): TableModule { return module; }
