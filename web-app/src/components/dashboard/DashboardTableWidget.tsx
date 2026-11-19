import { useEffect, useMemo, useRef, useState, type CSSProperties, type KeyboardEvent } from "react";
import type { DashboardJsonObject, DashboardWidget } from "@/api/dashboard";
import { useTransientScrollbar } from "@/components/ui/useTransientScrollbar";
import type { NamedDashboardResult } from "@/features/dashboard/dashboard-result";
import { presentedCellView } from "@/features/table-widget/runtime/output-boundary";
import { defaultTableCellText, isNumericLogicalType, numericColumnFractionDigits, originalTableCellText, sortedTableRowIndices, tableCellFormatter, type TableSort } from "@/features/table-widget/table-values";
import "./DashboardTableWidget.css";
import { DashboardPivotTable } from "./DashboardPivotTable";
import { TableCopyMenu } from "./TableCopyMenu";
import { TableGraphicCell } from "./TableGraphicCell";
import { useTableSourcePresentation } from "./useTableSourcePresentation";

type TableWidget = Extract<DashboardWidget, { type: "TABLE" }>;
type FlatTableWidget = Extract<TableWidget, { columns: unknown }>;
interface DashboardTableWidgetProps<Widget extends TableWidget = TableWidget> {
  widget: Widget;
  results: Record<string, NamedDashboardResult>;
  parameters: DashboardJsonObject;
  variables: DashboardJsonObject;
}

export function DashboardTableWidget({ widget, ...props }: DashboardTableWidgetProps) {
  if ("pivot" in widget) return <DashboardPivotTable widget={widget} {...props} />;
  const result = props.results[widget.queryName];
  if (!result) return <p role="alert" className="dashboard-table-error">No result for query '{widget.queryName}'.</p>;
  const missingField = widget.columns.find((column) => !result.columns.some((actual) => actual.name === column.field));
  if (missingField) return <p role="alert" className="dashboard-table-error">Column '{missingField.field}' is missing from the result.</p>;
  return <ReadyTable key={JSON.stringify([result.queryRunId, widget.columns.map((column) => column.field)])} widget={widget} {...props} result={result} />;
}

function ReadyTable({ widget, result, results, parameters, variables }: DashboardTableWidgetProps<FlatTableWidget> & { result: NamedDashboardResult }) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const pendingFocus = useRef(false);
  const scrollbar = useTransientScrollbar();
  const [viewport, setViewport] = useState({ top: 0, height: 240 });
  const [sort, setSort] = useState<TableSort | null>(null);
  const [selection, setSelection] = useState({ rowIndex: 0, columnIndex: 0 });
  const [copyStatus, setCopyStatus] = useState("");
  const [copyFailed, setCopyFailed] = useState(false);
  const rowHeight = widget.rowHeight ?? 40;
  const rowIndices = useMemo(() => sortedTableRowIndices(result, sort), [result, sort]);
  const start = Math.max(0, Math.min(rowIndices.length, Math.floor(Math.max(0, viewport.top - 32) / rowHeight) - 4));
  const end = Math.min(rowIndices.length, Math.ceil((viewport.top + viewport.height) / rowHeight) + 4);
  const visibleIndices = useMemo(() => rowIndices.slice(start, end), [rowIndices, start, end]);
  const requestedIndices = useMemo(() => result.rows.length && !visibleIndices.includes(selection.rowIndex) ? [...visibleIndices, selection.rowIndex] : visibleIndices, [visibleIndices, selection.rowIndex, result.rows.length]);
  const fields = useMemo(() => widget.columns.map((column) => column.field), [widget.columns]);
  const numericFields = useMemo(() => new Set(result.columns.filter((column) => isNumericLogicalType(column.logicalType)).map((column) => column.name)), [result.columns]);
  const locale = typeof navigator === "undefined" ? "en-US" : navigator.language;
  const cellDisplay = useMemo(() => ({ locale, fractionDigits: numericColumnFractionDigits(result) }), [result, locale]);
  const cellText = useMemo(() => tableCellFormatter(result.columns, cellDisplay.locale, cellDisplay.fractionDigits), [result.columns, cellDisplay]);
  const requests = useMemo(() => [{ rowIndices: requestedIndices, fields }], [requestedIndices, fields]);
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
    const cell = scrollRef.current?.querySelector<HTMLElement>(`[data-row-index="${selection.rowIndex}"][data-column-index="${selection.columnIndex}"]`);
    if (cell) { cell.focus({ preventScroll: true }); pendingFocus.current = false; }
  }, [selection, visibleIndices]);

  const cellViews = new Map(batch?.cells.map((cell) => [`${cell.rowIndex}:${cell.field}`, cell]));
  const rowViews = new Map(batch?.rows.map((row) => [row.rowIndex, row]));
  const selectedField = fields[selection.columnIndex];
  const selectedValue = result.rows[selection.rowIndex]?.[selectedField];
  const selectedOutput = cellViews.get(`${selection.rowIndex}:${selectedField}`)?.view;
  const selectedText = selectedOutput && "text" in selectedOutput ? selectedOutput.text : cellText(selectedField, selectedValue);
  const sortedColumn = sort ? widget.columns.find((column) => column.field === sort.field) : undefined;
  const copy = async (display: boolean) => {
    setCopyFailed(false);
    try {
      await navigator.clipboard.writeText(display ? selectedText : originalTableCellText(selectedValue));
      setCopyStatus(display ? "Displayed text copied." : "Raw value copied.");
    } catch { setCopyFailed(true); setCopyStatus("Could not copy to clipboard."); }
  };
  const moveSelection = (event: KeyboardEvent<HTMLTableCellElement>, rowIndex: number, columnIndex: number) => {
    let position = rowIndices.indexOf(rowIndex);
    let column = columnIndex;
    switch (event.key) {
      case "ArrowDown": position++; break;
      case "ArrowUp": position--; break;
      case "ArrowRight": column++; break;
      case "ArrowLeft": column--; break;
      case "Home": column = 0; if (event.ctrlKey || event.metaKey) position = 0; break;
      case "End": column = fields.length - 1; if (event.ctrlKey || event.metaKey) position = rowIndices.length - 1; break;
      case "PageDown": position += Math.max(1, Math.floor(viewport.height / rowHeight) - 1); break;
      case "PageUp": position -= Math.max(1, Math.floor(viewport.height / rowHeight) - 1); break;
      default: return;
    }
    event.preventDefault();
    position = Math.max(0, Math.min(rowIndices.length - 1, position));
    column = Math.max(0, Math.min(fields.length - 1, column));
    pendingFocus.current = true;
    setSelection({ rowIndex: rowIndices[position], columnIndex: column });
    const container = scrollRef.current;
    if (!container) return;
    const top = position * rowHeight;
    if (top < container.scrollTop) container.scrollTop = top;
    else if (top + rowHeight + 32 > container.scrollTop + container.clientHeight) container.scrollTop = top + rowHeight + 32 - container.clientHeight;
    const left = widget.columns.slice(0, column).reduce((sum, item) => sum + (item.width ?? 180), 0);
    const right = left + (widget.columns[column].width ?? 180);
    if (left < container.scrollLeft) container.scrollLeft = left;
    else if (right > container.scrollLeft + container.clientWidth) container.scrollLeft = right - container.clientWidth;
    setViewport({ top: container.scrollTop, height: container.clientHeight });
  };

  return (
    <div className="dashboard-table-widget" data-testid="dashboard-table-widget">
      <div className="dashboard-table-toolbar">
        <span className="tabular-nums">{result.rows.length.toLocaleString()} {result.rows.length === 1 ? "row" : "rows"}{sort && sortedColumn ? ` · Sorted by ${sortedColumn.header ?? sortedColumn.field} ${sort.direction === "ascending" ? "↑" : "↓"}` : ""}</span>
        <div className="dashboard-table-copy-actions">
          <TableCopyMenu disabled={result.rows.length === 0} onCopy={(display) => void copy(display)} />
        </div>
      </div>
      {errorMessage ? <p role="alert" className="dashboard-table-error">Table formatting could not be applied. Showing the default table. {errorMessage}</p> : null}
      <div ref={scrollRef} className={`dashboard-table-scroll ${scrollbar.className}`} onScroll={(event) => { scrollbar.onScroll(); setViewport({ top: event.currentTarget.scrollTop, height: event.currentTarget.clientHeight }); }} tabIndex={0} role="region" aria-label="Table scroll area">
        <table role="grid" aria-label={widget.title ?? "Dashboard table"} aria-rowcount={result.rows.length + 1} aria-colcount={fields.length} style={{ width: widget.columns.reduce((sum, column) => sum + (column.width ?? 180), 0) }}>
          <colgroup>{widget.columns.map((column) => <col key={column.field} style={{ width: column.width ?? 180 }} />)}</colgroup>
          <thead><tr aria-rowindex={1}>{widget.columns.map((column) => (
            <th key={column.field} scope="col" aria-sort={sort?.field === column.field ? sort.direction : "none"} data-numeric={numericFields.has(column.field) || undefined}>
              <button type="button" title={`${column.field} · Sort loaded rows`} onClick={() => setSort((current) => current?.field !== column.field ? { field: column.field, direction: "ascending" } : current.direction === "ascending" ? { field: column.field, direction: "descending" } : null)}>
                <span>{column.header ?? column.field}</span><span aria-hidden="true">{sort?.field === column.field ? sort.direction === "ascending" ? "↑" : "↓" : "↕"}</span>
              </button>
            </th>
          ))}</tr></thead>
          <tbody>
            {start > 0 ? <tr aria-hidden="true"><td colSpan={fields.length} className="dashboard-table-spacer" style={{ height: start * rowHeight }} /></tr> : null}
            {visibleIndices.map((rowIndex, offset) => {
              const rowPresentation = rowViews.get(rowIndex);
              return <tr key={rowIndex} aria-rowindex={start + offset + 2} style={{ height: rowHeight, background: rowPresentation?.view?.background }}>
                {widget.columns.map((column, columnIndex) => {
                  const cell = cellViews.get(`${rowIndex}:${column.field}`);
                  const cellError = cell?.error ?? (columnIndex === 0 ? rowPresentation?.error : undefined);
                  const value = result.rows[rowIndex][column.field];
                  const text = cellText(column.field, value);
                  const view = cell?.view && !cell.error ? presentedCellView(cell.view, text) : undefined;
                  const selected = selection.rowIndex === rowIndex && selection.columnIndex === columnIndex;
                  const style: CSSProperties = { height: rowHeight, color: view?.color, background: cellError ? undefined : view?.background, fontWeight: view?.emphasis === "strong" ? 500 : 400, textAlign: view?.align ?? (numericFields.has(column.field) ? "right" : undefined) };
                  return <td key={column.field} style={style} data-numeric={numericFields.has(column.field) || undefined} tabIndex={selected || (!visibleIndices.includes(selection.rowIndex) && offset === 0 && columnIndex === 0) ? 0 : -1} aria-selected={selected} data-row-index={rowIndex} data-column-index={columnIndex} data-cell-error={Boolean(cellError)}
                    title={cellError ? `Formatting error: ${cellError}` : view?.tooltip ?? defaultTableCellText(value)} onFocus={() => setSelection({ rowIndex, columnIndex })} onClick={(event) => event.currentTarget.focus()} onKeyDown={(event) => moveSelection(event, rowIndex, columnIndex)}>
                    <div className="dashboard-table-cell-content" style={{ height: rowHeight - 17 }}>
                      {view ? <TableGraphicCell key={view.graphic?.svg} view={view} /> : <span data-value-kind={value === undefined ? "missing" : value === null ? "null" : value === "" ? "empty" : "value"}>{text}</span>}
                      {cellError ? <span className="dashboard-table-cell-error" aria-label={`Formatting error: ${cellError}`}>Formatting error</span> : null}
                    </div>
                  </td>;
                })}
              </tr>;
            })}
            {end < rowIndices.length ? <tr aria-hidden="true"><td colSpan={fields.length} className="dashboard-table-spacer" style={{ height: (rowIndices.length - end) * rowHeight }} /></tr> : null}
          </tbody>
        </table>
        {result.rows.length === 0 ? <p className="dashboard-table-empty">No rows to display.</p> : null}
      </div>
      <span className={copyFailed ? "dashboard-table-error" : "sr-only"} role={copyFailed ? "alert" : "status"} aria-live="polite">{copyStatus}</span>
    </div>
  );
}
