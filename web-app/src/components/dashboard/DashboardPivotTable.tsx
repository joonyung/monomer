import { useEffect, useMemo, useRef, useState, type CSSProperties, type KeyboardEvent, type ReactNode } from "react";
import type { DashboardJsonObject, DashboardWidget } from "@/api/dashboard";
import { useTransientScrollbar } from "@/components/ui/useTransientScrollbar";
import type { NamedDashboardResult } from "@/features/dashboard/dashboard-result";
import type { CellView } from "@/features/table-widget/contract/abi";
import {
  PIVOT_TOTAL_LABEL, PIVOT_VALUES_HEADER, pivotCell, pivotColumnSortable, pivotLayout, pivotRequestRows, pivotSourceRequests, pivotValueFractionDigits, sortedPivotBodyRows,
  type PivotCell, type PivotColumn, type PivotGrid, type PivotHeaderCell, type PivotRow, type PivotSort,
} from "@/features/table-widget/pivot/pivot-layout";
import { presentedCellView } from "@/features/table-widget/runtime/output-boundary";
import { defaultTableCellText, isNumericLogicalType, numericColumnFractionDigits, originalTableCellText, tableCellFormatter, type TableCellText } from "@/features/table-widget/table-values";
import { TableCopyMenu } from "./TableCopyMenu";
import { TableGraphicCell } from "./TableGraphicCell";
import { useTableSourcePresentation } from "./useTableSourcePresentation";

type PivotTableWidget = Extract<DashboardWidget, { pivot: unknown }>;
interface DashboardPivotTableProps {
  widget: PivotTableWidget;
  results: Record<string, NamedDashboardResult>;
  parameters: DashboardJsonObject;
  variables: DashboardJsonObject;
}
type PresentedCell = { cell: PivotCell; text: string; raw: string; title: string | undefined; numeric: boolean; view?: CellView; error?: string };

const HEADER_ROW_HEIGHT = 32;

export function DashboardPivotTable(props: DashboardPivotTableProps) {
  const queryNames = [props.widget.queryName, ...(props.widget.pivot.totals ?? [])];
  const missing = queryNames.find((name) => !props.results[name]);
  if (missing) return <p role="alert" className="dashboard-table-error">No result for query '{missing}'.</p>;
  return <ReadyPivotTable key={JSON.stringify([props.widget.pivot, ...queryNames.map((name) => props.results[name].queryRunId)])} {...props} />;
}

// The key covers the pivot and every QueryRun it reads, so the layout is computed once per mount.
function ReadyPivotTable(props: DashboardPivotTableProps) {
  const [layout] = useState(() => pivotLayout(props.widget.pivot, props.results[props.widget.queryName],
    (props.widget.pivot.totals ?? []).map((queryName) => ({ queryName, result: props.results[queryName] }))));
  if (layout.kind === "ERROR") return <p role="alert" className="dashboard-table-error">{layout.message}</p>;
  return <PivotGridTable {...props} grid={layout.grid} />;
}

function PivotGridTable({ grid, widget, results, parameters, variables }: DashboardPivotTableProps & { grid: PivotGrid }) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const pendingFocus = useRef(false);
  const scrollbar = useTransientScrollbar();
  const [viewport, setViewport] = useState({ top: 0, height: 240 });
  const [sort, setSort] = useState<PivotSort | null>(null);
  const [selection, setSelection] = useState(() => ({ rowId: grid.bodyRows[0]?.id ?? grid.totalRows[0]?.id ?? "", columnIndex: 0 }));
  const [copyStatus, setCopyStatus] = useState("");
  const [copyFailed, setCopyFailed] = useState(false);
  const rowHeight = widget.rowHeight ?? 40;
  const headerHeight = grid.headerRows.length * HEADER_ROW_HEIGHT;
  const footerHeight = grid.totalRows.length * rowHeight;
  const footerPinned = grid.totalRows.length > 0 && headerHeight + footerHeight + rowHeight <= viewport.height;
  const pinnedFooterHeight = footerPinned ? footerHeight : 0;
  const bodyRows = useMemo(() => sortedPivotBodyRows(grid, sort), [grid, sort]);
  const start = Math.max(0, Math.min(bodyRows.length, Math.floor(Math.max(0, viewport.top - headerHeight) / rowHeight) - 4));
  const end = Math.min(bodyRows.length, Math.ceil((viewport.top + viewport.height - pinnedFooterHeight) / rowHeight) + 4);
  const visibleRows = useMemo(() => bodyRows.slice(start, end), [bodyRows, start, end]);
  const displayRows = useMemo<readonly PivotRow[]>(() => [...bodyRows, ...grid.totalRows], [bodyRows, grid.totalRows]);
  const rowsById = useMemo(() => new Map<string, PivotRow>([...grid.bodyRows, ...grid.totalRows].map((row) => [row.id, row])), [grid]);
  const selectedRow = rowsById.get(selection.rowId);
  const selectedBodyRow = selectedRow?.kind === "BODY" ? selectedRow : undefined;
  const locale = typeof navigator === "undefined" ? "en-US" : navigator.language;
  const formatters = useMemo(() => {
    const valueDigits = pivotValueFractionDigits(grid);
    const byResult = new Map<NamedDashboardResult, { text: TableCellText; numericFields: ReadonlySet<string>; fractionDigits: ReadonlyMap<string, number> }>();
    for (const result of [grid.primary, grid.rowTotals?.result, grid.columnTotals?.result, grid.grandTotal?.result]) {
      if (result && !byResult.has(result)) {
        const fractionDigits = new Map([...numericColumnFractionDigits(result), ...valueDigits]);
        byResult.set(result, {
          text: tableCellFormatter(result.columns, locale, fractionDigits),
          numericFields: new Set(result.columns.filter((column) => column.numberFormat !== null || isNumericLogicalType(column.logicalType)).map((column) => column.name)),
          fractionDigits,
        });
      }
    }
    return byResult;
  }, [grid, locale]);
  const primaryFormat = formatters.get(grid.primary)!;
  const cellDisplay = useMemo(() => ({ locale, fractionDigits: primaryFormat.fractionDigits }), [locale, primaryFormat]);
  const columnLefts = useMemo(() => {
    let left = 0;
    return grid.columns.map((column) => { const columnLeft = left; left += column.width; return columnLeft; });
  }, [grid.columns]);
  const rowHeaderWidth = grid.columns.filter(isRowHeaderColumn).reduce((sum, column) => sum + column.width, 0);
  const requestRows = useMemo(() => pivotRequestRows(visibleRows, selectedBodyRow), [visibleRows, selectedBodyRow]);
  const requests = useMemo(() => pivotSourceRequests(grid, requestRows), [grid, requestRows]);
  const { errorMessage, batch } = useTableSourcePresentation(widget, results, parameters, variables, requests, cellDisplay);

  useEffect(() => {
    const element = scrollRef.current;
    if (!element) return;
    const measure = () => setViewport({ top: element.scrollTop, height: element.clientHeight });
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (!pendingFocus.current) return;
    const cell = scrollRef.current?.querySelector<HTMLElement>(`[data-row-id="${CSS.escape(selection.rowId)}"][data-column-index="${selection.columnIndex}"]`);
    if (cell) { cell.focus({ preventScroll: true }); pendingFocus.current = false; }
  }, [selection, visibleRows]);

  const cellViews = new Map(batch?.cells.map((cell) => [`${cell.rowIndex}:${cell.field}`, cell]));
  const columnKeyDescription = (columnKeyIndex: number) => grid.columnFields
    .map((field, level) => `${field.label}: ${primaryFormat.text(field.field, grid.columnKeys[columnKeyIndex][level])}`).join(", ");
  const columnLabel = (column: PivotColumn): string => {
    switch (column.kind) {
      case "ROW_FIELD": return column.rowField.label;
      case "VALUE_NAME": return PIVOT_VALUES_HEADER;
      case "VALUE": return column.valueField && grid.valueFields.length > 1 ? `${column.valueField.label} · ${columnKeyDescription(column.columnKeyIndex)}` : columnKeyDescription(column.columnKeyIndex);
      case "TOTAL": return column.valueField && grid.valueFields.length > 1 ? `${column.valueField.label} · ${PIVOT_TOTAL_LABEL}` : PIVOT_TOTAL_LABEL;
    }
  };
  const columnNumeric = (column: PivotColumn): boolean => column.kind === "ROW_FIELD" ? primaryFormat.numericFields.has(column.rowField.field)
    : column.kind === "VALUE_NAME" ? false
      : column.valueField ? primaryFormat.numericFields.has(column.valueField.field) : grid.valueFields.every((field) => primaryFormat.numericFields.has(field.field));
  const present = (row: PivotRow, column: PivotColumn): PresentedCell => {
    const cell = pivotCell(grid, row, column);
    switch (cell.kind) {
      case "ROW_HEADER":
      case "VALUE": {
        const source = cellViews.get(`${cell.sourceRowIndex}:${cell.field}`);
        const text = primaryFormat.text(cell.field, cell.value);
        const view = source?.view && !source.error ? presentedCellView(source.view, text) : undefined;
        return { cell, view, error: source?.error, text: view?.text ?? text, raw: originalTableCellText(cell.value), title: view?.tooltip ?? defaultTableCellText(cell.value), numeric: primaryFormat.numericFields.has(cell.field) };
      }
      case "TOTAL": {
        const format = formatters.get(cell.result)!;
        return { cell, text: format.text(cell.field, cell.value), raw: originalTableCellText(cell.value), title: defaultTableCellText(cell.value), numeric: format.numericFields.has(cell.field) };
      }
      case "VALUE_NAME": return { cell, text: cell.label, raw: cell.label, title: undefined, numeric: false };
      case "TOTAL_LABEL": return { cell, text: PIVOT_TOTAL_LABEL, raw: PIVOT_TOTAL_LABEL, title: undefined, numeric: false };
      case "EMPTY": return { cell, text: "", raw: "", title: undefined, numeric: false };
    }
  };
  const selectedColumn = grid.columns[selection.columnIndex];
  const selectedCell = selectedRow && selectedColumn ? present(selectedRow, selectedColumn) : undefined;
  const sortedColumn = sort ? grid.columns.find((column) => column.id === sort.columnId) : undefined;
  const copy = async (display: boolean) => {
    setCopyFailed(false);
    try {
      await navigator.clipboard.writeText(display ? selectedCell?.text ?? "" : selectedCell?.raw ?? "");
      setCopyStatus(display ? "Displayed text copied." : "Raw value copied.");
    } catch { setCopyFailed(true); setCopyStatus("Could not copy to clipboard."); }
  };
  const moveSelection = (event: KeyboardEvent<HTMLTableCellElement>, rowId: string, columnIndex: number) => {
    let position = displayRows.findIndex((row) => row.id === rowId);
    let column = columnIndex;
    switch (event.key) {
      case "ArrowDown": position++; break;
      case "ArrowUp": position--; break;
      case "ArrowRight": column++; break;
      case "ArrowLeft": column--; break;
      case "Home": column = 0; if (event.ctrlKey || event.metaKey) position = 0; break;
      case "End": column = grid.columns.length - 1; if (event.ctrlKey || event.metaKey) position = displayRows.length - 1; break;
      case "PageDown": position += Math.max(1, Math.floor(viewport.height / rowHeight) - 1); break;
      case "PageUp": position -= Math.max(1, Math.floor(viewport.height / rowHeight) - 1); break;
      default: return;
    }
    event.preventDefault();
    position = Math.max(0, Math.min(displayRows.length - 1, position));
    column = Math.max(0, Math.min(grid.columns.length - 1, column));
    pendingFocus.current = true;
    setSelection({ rowId: displayRows[position].id, columnIndex: column });
    const container = scrollRef.current;
    if (!container) return;
    if (position < bodyRows.length || !footerPinned) {
      const top = position * rowHeight;
      if (top < container.scrollTop) container.scrollTop = top;
      else if (top + rowHeight + headerHeight + pinnedFooterHeight > container.scrollTop + container.clientHeight) container.scrollTop = top + rowHeight + headerHeight + pinnedFooterHeight - container.clientHeight;
    }
    const target = grid.columns[column];
    if (!isRowHeaderColumn(target)) {
      const left = columnLefts[column];
      if (left - rowHeaderWidth < container.scrollLeft) container.scrollLeft = left - rowHeaderWidth;
      else if (left + target.width > container.scrollLeft + container.clientWidth) container.scrollLeft = left + target.width - container.clientWidth;
    }
    setViewport({ top: container.scrollTop, height: container.clientHeight });
  };

  const stickyStyle = (columnIndex: number): CSSProperties | undefined => isRowHeaderColumn(grid.columns[columnIndex]) ? { left: columnLefts[columnIndex] } : undefined;
  const sortableHeader = (cell: PivotHeaderCell, columnIndex: number, label: string, span: { rowSpan?: number; colSpan?: number }) => {
    const column = grid.columns[columnIndex];
    const accessibleLabel = columnLabel(column);
    const active = sort?.columnId === column.id ? sort : null;
    return <th key={cell.id} scope="col" {...span} aria-sort={active ? active.direction : "none"} data-numeric={columnNumeric(column) || undefined}
      className={isRowHeaderColumn(column) ? "dashboard-table-pivot-row-header" : cell.kind === "TOTAL" ? "dashboard-table-pivot-total" : undefined} style={stickyStyle(columnIndex)}>
      <button type="button" aria-label={accessibleLabel === label ? undefined : accessibleLabel}
        title={`${column.kind === "ROW_FIELD" ? column.rowField.field : accessibleLabel} · Sort loaded rows`}
        onClick={() => setSort((current) => current?.columnId !== column.id ? { columnId: column.id, direction: "ascending" } : current.direction === "ascending" ? { columnId: column.id, direction: "descending" } : null)}>
        <span>{label}</span><span aria-hidden="true">{active ? active.direction === "ascending" ? "↑" : "↓" : "↕"}</span>
      </button>
    </th>;
  };
  const labelHeader = (cell: PivotHeaderCell, text: string, accessibleLabel: string, options: { rowSpan?: number; colSpan?: number; columnIndex: number | null; className?: string }): ReactNode => {
    const column = options.columnIndex === null ? undefined : grid.columns[options.columnIndex];
    return <th key={cell.id} scope={(options.colSpan ?? 1) > 1 ? "colgroup" : "col"} rowSpan={options.rowSpan} colSpan={options.colSpan}
      aria-label={accessibleLabel === text ? undefined : accessibleLabel} title={accessibleLabel === text ? undefined : accessibleLabel}
      data-numeric={column && columnNumeric(column) || undefined} className={column && isRowHeaderColumn(column) ? "dashboard-table-pivot-row-header" : options.className}
      style={options.columnIndex === null ? undefined : stickyStyle(options.columnIndex)}>
      <span className="dashboard-table-pivot-header-label">{text}</span>
    </th>;
  };
  const header = (cell: PivotHeaderCell): ReactNode => {
    const sortable = (columnIndex: number | null): columnIndex is number => columnIndex !== null && pivotColumnSortable(grid, grid.columns[columnIndex]);
    switch (cell.kind) {
      case "ROW_FIELD": return sortableHeader(cell, cell.columnIndex, cell.rowField.label, { rowSpan: cell.rowSpan });
      case "VALUE_NAME": return labelHeader(cell, PIVOT_VALUES_HEADER, PIVOT_VALUES_HEADER, { rowSpan: cell.rowSpan, columnIndex: cell.columnIndex });
      case "COLUMN_KEY": {
        const field = grid.columnFields[cell.level];
        const text = primaryFormat.text(field.field, grid.columnKeys[cell.columnKeyIndex][cell.level]);
        if (sortable(cell.columnIndex)) return sortableHeader(cell, cell.columnIndex, text, { colSpan: cell.colSpan });
        return labelHeader(cell, text, cell.columnIndex === null ? `${field.label}: ${text}` : columnLabel(grid.columns[cell.columnIndex]), { colSpan: cell.colSpan, columnIndex: cell.columnIndex });
      }
      case "VALUE": return sortableHeader(cell, cell.columnIndex, cell.valueField.label, {});
      case "TOTAL":
        if (sortable(cell.columnIndex)) return sortableHeader(cell, cell.columnIndex, PIVOT_TOTAL_LABEL, { rowSpan: cell.rowSpan });
        return labelHeader(cell, PIVOT_TOTAL_LABEL, PIVOT_TOTAL_LABEL, { rowSpan: cell.rowSpan, colSpan: cell.colSpan, columnIndex: cell.columnIndex, className: "dashboard-table-pivot-total" });
      case "TOTAL_VALUE": return sortableHeader(cell, cell.columnIndex, cell.valueField.label, {});
    }
  };
  const renderCell = (row: PivotRow, column: PivotColumn, columnIndex: number, focusable: boolean) => {
    const presented = present(row, column);
    const { cell, view, error } = presented;
    const value = cell.kind === "ROW_HEADER" || cell.kind === "VALUE" || cell.kind === "TOTAL" ? cell.value : undefined;
    const selected = selection.rowId === row.id && selection.columnIndex === columnIndex;
    const rowHeader = isRowHeaderColumn(column);
    const total = cell.kind === "TOTAL" || cell.kind === "TOTAL_LABEL";
    const style: CSSProperties = {
      ...stickyStyle(columnIndex), height: rowHeight, color: view?.color, background: error ? undefined : view?.background,
      fontWeight: view?.emphasis === "strong" || total ? 500 : 400, textAlign: view?.align ?? (presented.numeric ? "right" : undefined),
    };
    return <td key={column.id} role={rowHeader ? "rowheader" : undefined} className={rowHeader ? "dashboard-table-pivot-row-header" : undefined} style={style}
      data-numeric={presented.numeric || undefined} data-cell-kind={cell.kind} tabIndex={selected || focusable ? 0 : -1} aria-selected={selected}
      data-row-id={row.id} data-column-index={columnIndex} data-cell-error={Boolean(error)} title={error ? `Formatting error: ${error}` : presented.title}
      onFocus={() => setSelection({ rowId: row.id, columnIndex })} onClick={(event) => event.currentTarget.focus()} onKeyDown={(event) => moveSelection(event, row.id, columnIndex)}>
      <div className="dashboard-table-cell-content" style={{ height: rowHeight - 17 }}>
        {view ? <TableGraphicCell key={view.graphic?.svg} view={view} />
          : <span data-value-kind={cell.kind === "EMPTY" ? "absent" : value === undefined ? "label" : value === null ? "null" : value === "" ? "empty" : "value"}>{presented.text}</span>}
        {error ? <span className="dashboard-table-cell-error" aria-label={`Formatting error: ${error}`}>Formatting error</span> : null}
      </div>
    </td>;
  };
  const selectionRendered = visibleRows.some((row) => row.id === selection.rowId) || grid.totalRows.some((row) => row.id === selection.rowId);

  return (
    <div className="dashboard-table-widget dashboard-table-pivot" data-testid="dashboard-table-widget">
      <div className="dashboard-table-toolbar">
        <span className="tabular-nums">{bodyRows.length.toLocaleString()} {bodyRows.length === 1 ? "row" : "rows"}{sort && sortedColumn ? ` · Sorted by ${columnLabel(sortedColumn)} ${sort.direction === "ascending" ? "↑" : "↓"}` : ""}</span>
        <div className="dashboard-table-copy-actions">
          <TableCopyMenu disabled={displayRows.length === 0} onCopy={(display) => void copy(display)} />
        </div>
      </div>
      {errorMessage ? <p role="alert" className="dashboard-table-error">Table formatting could not be applied. Showing the default table. {errorMessage}</p> : null}
      <div ref={scrollRef} className={`dashboard-table-scroll ${scrollbar.className}`} onScroll={(event) => { scrollbar.onScroll(); setViewport({ top: event.currentTarget.scrollTop, height: event.currentTarget.clientHeight }); }} tabIndex={0} role="region" aria-label="Table scroll area">
        <table role="grid" aria-label={widget.title ?? "Dashboard table"} aria-rowcount={grid.headerRows.length + bodyRows.length + grid.totalRows.length} aria-colcount={grid.columns.length} style={{ width: grid.columns.reduce((sum, column) => sum + column.width, 0) }}>
          <colgroup>{grid.columns.map((column) => <col key={column.id} style={{ width: column.width }} />)}</colgroup>
          <thead>{grid.headerRows.map((cells, rowIndex) => <tr key={rowIndex} aria-rowindex={rowIndex + 1}>{cells.map(header)}</tr>)}</thead>
          <tbody>
            {start > 0 ? <tr aria-hidden="true"><td colSpan={grid.columns.length} className="dashboard-table-spacer" style={{ height: start * rowHeight }} /></tr> : null}
            {visibleRows.map((row, offset) => <tr key={row.id} aria-rowindex={grid.headerRows.length + start + offset + 1} style={{ height: rowHeight }}>
              {grid.columns.map((column, columnIndex) => renderCell(row, column, columnIndex, !selectionRendered && offset === 0 && columnIndex === 0))}
            </tr>)}
            {end < bodyRows.length ? <tr aria-hidden="true"><td colSpan={grid.columns.length} className="dashboard-table-spacer" style={{ height: (bodyRows.length - end) * rowHeight }} /></tr> : null}
          </tbody>
          {grid.totalRows.length > 0 ? <tfoot data-pinned={footerPinned || undefined}>
            {grid.totalRows.map((row, index) => <tr key={row.id} aria-rowindex={grid.headerRows.length + bodyRows.length + index + 1} style={{ height: rowHeight }}>
              {grid.columns.map((column, columnIndex) => renderCell(row, column, columnIndex, !selectionRendered && visibleRows.length === 0 && index === 0 && columnIndex === 0))}
            </tr>)}
          </tfoot> : null}
        </table>
        {grid.bodyRows.length === 0 ? <p className="dashboard-table-empty">No rows to display.</p> : null}
      </div>
      <span className={copyFailed ? "dashboard-table-error" : "sr-only"} role={copyFailed ? "alert" : "status"} aria-live="polite">{copyStatus}</span>
    </div>
  );
}

function isRowHeaderColumn(column: PivotColumn): boolean {
  return column.kind === "ROW_FIELD" || column.kind === "VALUE_NAME";
}
