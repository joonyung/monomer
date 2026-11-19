import { useEffect, useMemo, useRef, useState, type RefObject } from "react";
import { ArrowDown, ArrowUp } from "lucide-react";
import type { SemanticTimeGranularity } from "@/api/explore";
import { formatCell } from "@/components/runs";
import { formatTimeGrainValue } from "./time-grain-format";
import { useTransientScrollbar } from "@/components/ui/useTransientScrollbar";
import { columnFractionDigits, formatNumericCell } from "@/lib/numeric-display";
import { formatMetricNumber, type MetricNumberFormat } from "@/lib/metric-display";

export { columnFractionDigits, formatNumericCell } from "@/lib/numeric-display";

export interface ExploreTableColumn {
  id: string;
  label: string;
  description: string;
  numeric: boolean;
  numberFormat?: MetricNumberFormat | null;
  /** Grain of a Semantic time group-by column; its cells read at that grain. */
  timeGranularity: SemanticTimeGranularity | null;
}

type Cell = string | boolean | null;

export interface TableSort {
  columnIndex: number;
  direction: "asc" | "desc";
}

/** A Semantic question without a sort reads its preview along its first time column, earliest first. */
export function defaultTableSort(columns: readonly ExploreTableColumn[], questionSorted: boolean): TableSort | null {
  if (questionSorted) return null;
  const columnIndex = columns.findIndex((column) => column.timeGranularity !== null);
  return columnIndex >= 0 ? { columnIndex, direction: "asc" } : null;
}

export function nextTableSort(current: TableSort | null, columnIndex: number): TableSort | null {
  if (!current || current.columnIndex !== columnIndex) return { columnIndex, direction: "asc" };
  return current.direction === "asc" ? { columnIndex, direction: "desc" } : null;
}

/** Sorts preview rows in the browser. Empty cells stay last in either direction. */
export function sortPreviewRows(rows: readonly (readonly Cell[])[], sort: TableSort | null, numeric: boolean): readonly (readonly Cell[])[] {
  if (!sort) return rows;
  const factor = sort.direction === "asc" ? 1 : -1;
  return [...rows].sort((left, right) => {
    const a = left[sort.columnIndex] ?? null;
    const b = right[sort.columnIndex] ?? null;
    if (a === null || b === null) return a === b ? 0 : a === null ? 1 : -1;
    if (numeric) {
      const difference = Number(a) - Number(b);
      if (Number.isFinite(difference) && difference !== 0) return difference * factor;
    }
    return String(a).localeCompare(String(b), undefined, { numeric: true }) * factor;
  });
}

/**
 * Wheel scrolling over the grid first moves the page until the grid reaches the top of it, so the
 * question scrolls away and the grid fills the view; only then does the grid scroll its own rows.
 * Scrolling back up empties the grid first, then brings the question back.
 */
function usePageFirstWheel(grid: RefObject<HTMLDivElement | null>) {
  useEffect(() => {
    const element = grid.current;
    const page = element?.closest("main");
    if (!element || !page) return;
    const onWheel = (event: WheelEvent) => {
      // Pinch and Ctrl+wheel zoom; sideways wheels scroll the grid's columns.
      if (event.ctrlKey || event.metaKey || event.deltaY === 0 || Math.abs(event.deltaX) > Math.abs(event.deltaY)) return;
      const pageRoom = page.scrollHeight - page.clientHeight - page.scrollTop;
      if (event.deltaY > 0 && pageRoom > 1) {
        event.preventDefault();
        page.scrollTop += Math.min(event.deltaY, pageRoom);
      } else if (event.deltaY < 0 && element.scrollTop <= 0 && page.scrollTop > 0) {
        event.preventDefault();
        page.scrollTop += event.deltaY;
      }
    };
    element.addEventListener("wheel", onWheel, { passive: false });
    return () => element.removeEventListener("wheel", onWheel);
  }, [grid]);
}

/** Columns keep their content width from the left; a trailing filler takes the rest of a wide view. */
export function ExploreResultTable({ columns, rows, initialSort, onSortChange }: {
  columns: readonly ExploreTableColumn[];
  rows: readonly (readonly Cell[])[];
  /** How the preview opens: a question without a sort reads along its time column. */
  initialSort: TableSort | null;
  /** Header sorting orders only the rows this preview holds, so the result's status says so. */
  onSortChange: (sorted: boolean) => void;
}) {
  const scrollbar = useTransientScrollbar();
  const gridRef = useRef<HTMLDivElement>(null);
  usePageFirstWheel(gridRef);
  const [sort, setSort] = useState<TableSort | null>(initialSort);
  const sorted = sort !== null;
  useEffect(() => onSortChange(sorted), [onSortChange, sorted]);
  const sortedRows = useMemo(
    () => sortPreviewRows(rows, sort, sort ? columns[sort.columnIndex]?.numeric === true : false),
    [columns, rows, sort],
  );
  const fractionDigits = useMemo(
    () => columns.map((column, index) => column.numeric ? columnFractionDigits(rows.map((row) => row[index] ?? null)) : 0),
    [columns, rows],
  );
  return (
    <div ref={gridRef} onScroll={scrollbar.onScroll} data-testid="run-sample-grid" className={`min-h-[240px] flex-1 overflow-auto [scrollbar-gutter:stable] ${scrollbar.className}`}>
      <table className="type-meta w-full border-collapse text-left">
        <thead className="sticky top-0 z-10 bg-surface">
          <tr className="border-b border-border">
            {columns.map((column, index) => {
              const direction = sort?.columnIndex === index ? sort.direction : null;
              const SortIcon = direction === "asc" ? ArrowUp : ArrowDown;
              return (
                <th
                  key={column.id}
                  scope="col"
                  aria-sort={direction === "asc" ? "ascending" : direction === "desc" ? "descending" : "none"}
                  className="whitespace-nowrap p-0 font-normal"
                >
                  <button
                    type="button"
                    title={column.description}
                    onClick={() => setSort((current) => nextTableSort(current, index))}
                    className={`flex w-full items-center gap-1 px-4 py-2.5 hover:text-foreground ${column.numeric ? "justify-end" : ""} ${direction ? "text-foreground" : "text-foreground-secondary"}`}
                  >
                    {column.numeric ? <SortIcon className={`h-3.5 w-3.5 shrink-0 ${direction ? "" : "invisible"}`} aria-hidden="true" /> : null}
                    {column.label}
                    {column.numeric ? null : <SortIcon className={`h-3.5 w-3.5 shrink-0 ${direction ? "" : "invisible"}`} aria-hidden="true" />}
                  </button>
                </th>
              );
            })}
            <th aria-hidden="true" className="w-full p-0" />
          </tr>
        </thead>
        <tbody>
          {sortedRows.map((row, rowIndex) => (
            <tr key={rowIndex} className="border-b border-border-light">
              {row.map((cell, index) => {
                const column = columns[index];
                const granularity = typeof cell === "string" ? column?.timeGranularity ?? null : null;
                const numeric = column?.numeric === true && typeof cell === "string";
                const display = granularity ? formatTimeGrainValue(cell as string, granularity, undefined)
                  : numeric ? column?.numberFormat ? formatMetricNumber(cell as string, column.numberFormat, undefined)
                    : formatNumericCell(cell as string, undefined, fractionDigits[index] ?? 0)
                    : formatCell(cell);
                return (
                  <td key={index} title={granularity || numeric ? cell as string : undefined} className={`whitespace-nowrap px-4 py-2.5 tabular-nums text-foreground ${column?.numeric ? "text-right" : ""}`}>
                    {display}
                  </td>
                );
              })}
              <td aria-hidden="true" className="w-full p-0" />
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
