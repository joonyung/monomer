import type { DashboardWidget } from "@/api/dashboard";
import type { NamedDashboardResult } from "@/features/dashboard/dashboard-result";
import type { RawCell } from "@/features/table-widget/contract/abi";
import type { TableBatchRequest } from "@/features/table-widget/runtime/worker-protocol";
import { compareTableValues } from "@/features/table-widget/table-values";
import { columnFractionDigits } from "@/lib/numeric-display";

// Moves cells of results the host already received into a cross-tab grid. Every value cell is one
// cell of the primary result and every total is one cell of a named totals result; nothing is computed.

export type TablePivot = Extract<DashboardWidget, { pivot: unknown }>["pivot"];
export type PivotField = Readonly<{ field: string; label: string; width: number }>;
export type PivotTotalsResult = Readonly<{ queryName: string; result: NamedDashboardResult }>;

export const PIVOT_COLUMN_LIMIT = 200;
export const PIVOT_VALUES_HEADER = "Values";
export const PIVOT_TOTAL_LABEL = "Total";
const DEFAULT_COLUMN_WIDTH = 180;

type PivotRowKey = Readonly<{
  values: readonly RawCell[];
  sourceRowIndices: readonly number[];
  sourceRowByColumnKey: ReadonlyMap<number, number>;
}>;
type PivotTotals = Readonly<{ queryName: string; result: NamedDashboardResult; rowIndexByKey: readonly number[] }>;

export type PivotColumn =
  | Readonly<{ kind: "ROW_FIELD"; id: string; width: number; rowField: PivotField }>
  | Readonly<{ kind: "VALUE_NAME"; id: string; width: number }>
  | Readonly<{ kind: "VALUE"; id: string; width: number; columnKeyIndex: number; valueField: PivotField | null }>
  | Readonly<{ kind: "TOTAL"; id: string; width: number; valueField: PivotField | null }>;

export type PivotHeaderCell =
  | Readonly<{ kind: "ROW_FIELD"; id: string; rowSpan: number; columnIndex: number; rowField: PivotField }>
  | Readonly<{ kind: "VALUE_NAME"; id: string; rowSpan: number; columnIndex: number }>
  | Readonly<{ kind: "COLUMN_KEY"; id: string; colSpan: number; level: number; columnKeyIndex: number; columnIndex: number | null }>
  | Readonly<{ kind: "VALUE"; id: string; columnKeyIndex: number; valueField: PivotField; columnIndex: number }>
  | Readonly<{ kind: "TOTAL"; id: string; colSpan: number; rowSpan: number; columnIndex: number | null }>
  | Readonly<{ kind: "TOTAL_VALUE"; id: string; valueField: PivotField; columnIndex: number }>;

export type PivotRow =
  | Readonly<{ kind: "BODY"; id: string; rowKeyIndex: number; valueField: PivotField | null }>
  | Readonly<{ kind: "TOTAL"; id: string; valueField: PivotField | null }>;
export type PivotBodyRow = Extract<PivotRow, { kind: "BODY" }>;

export type PivotCell =
  | Readonly<{ kind: "ROW_HEADER"; field: string; value: RawCell; sourceRowIndex: number }>
  | Readonly<{ kind: "VALUE"; field: string; value: RawCell; sourceRowIndex: number }>
  | Readonly<{ kind: "TOTAL"; result: NamedDashboardResult; field: string; value: RawCell }>
  | Readonly<{ kind: "VALUE_NAME"; label: string }>
  | Readonly<{ kind: "TOTAL_LABEL" }>
  | Readonly<{ kind: "EMPTY" }>;

export type PivotGrid = Readonly<{
  primary: NamedDashboardResult;
  valuesAxis: TablePivot["valuesAxis"];
  rowFields: readonly PivotField[];
  columnFields: readonly PivotField[];
  valueFields: readonly PivotField[];
  rowKeys: readonly PivotRowKey[];
  columnKeys: readonly (readonly RawCell[])[];
  rowTotals: PivotTotals | null;
  columnTotals: PivotTotals | null;
  grandTotal: PivotTotals | null;
  columns: readonly PivotColumn[];
  headerRows: readonly (readonly PivotHeaderCell[])[];
  bodyRows: readonly PivotBodyRow[];
  totalRows: readonly Extract<PivotRow, { kind: "TOTAL" }>[];
}>;

export type PivotErrorCode =
  | "MISSING_RESULT"
  | "MISSING_COLUMN"
  | "DUPLICATE_KEY"
  | "TOO_MANY_COLUMNS"
  | "TOTALS_PLACEMENT"
  | "TOTALS_ROW_COUNT"
  | "TOTALS_DUPLICATE_KEY"
  | "TOTALS_MISSING_KEY";
export type PivotLayout =
  | Readonly<{ kind: "GRID"; grid: PivotGrid }>
  | Readonly<{ kind: "ERROR"; code: PivotErrorCode; message: string }>;

export type PivotSort = Readonly<{ columnId: string; direction: "ascending" | "descending" }>;

function pivotError(code: PivotErrorCode, message: string): PivotLayout {
  return { kind: "ERROR", code, message };
}

function keyText(values: readonly RawCell[]): string {
  return JSON.stringify(values);
}

function describeKey(fields: readonly PivotField[], values: readonly RawCell[]): string {
  const parts = fields.map((field, index) => `${field.label} ${values[index] === null ? "null" : `"${String(values[index])}"`}`);
  return parts.length <= 2 ? parts.join(" and ") : `${parts.slice(0, -1).join(", ")} and ${parts[parts.length - 1]}`;
}

function compareKeys(left: readonly RawCell[], right: readonly RawCell[], logicalTypes: readonly (string | undefined)[]): number {
  for (let index = 0; index < left.length; index++) {
    const leftValue = left[index];
    const rightValue = right[index];
    if (leftValue === null || rightValue === null) {
      if (leftValue !== rightValue) return leftValue === null ? 1 : -1;
      continue;
    }
    const comparison = compareTableValues(leftValue, rightValue, logicalTypes[index]);
    if (comparison !== 0) return comparison;
  }
  return 0;
}

function sameKeyPrefix(left: readonly RawCell[], right: readonly RawCell[], length: number): boolean {
  for (let index = 0; index < length; index++) if (left[index] !== right[index]) return false;
  return true;
}

function totalsLookup(
  totals: PivotTotalsResult,
  keyFields: readonly PivotField[],
  displayedKeys: readonly (readonly RawCell[])[],
): PivotTotals | PivotLayout {
  const { queryName, result } = totals;
  if (keyFields.length === 0 && result.rows.length !== 1) {
    return pivotError("TOTALS_ROW_COUNT", `The total query "${queryName}" must return exactly one row. It returned ${result.rows.length.toLocaleString("en-US")}.`);
  }
  const rowIndexByKeyText = new Map<string, number>();
  for (let rowIndex = 0; rowIndex < result.rows.length; rowIndex++) {
    const key = keyFields.map((field) => result.rows[rowIndex][field.field]);
    const text = keyText(key);
    if (rowIndexByKeyText.has(text)) {
      return pivotError("TOTALS_DUPLICATE_KEY", `The total query "${queryName}" has more than one row for ${describeKey(keyFields, key)}. Each total must appear once.`);
    }
    rowIndexByKeyText.set(text, rowIndex);
  }
  const rowIndexByKey: number[] = [];
  for (const key of displayedKeys) {
    const rowIndex = rowIndexByKeyText.get(keyText(key));
    if (rowIndex === undefined) {
      return pivotError("TOTALS_MISSING_KEY", `The total query "${queryName}" has no row for ${describeKey(keyFields, key)}. Raise its rowLimit or match its filters.`);
    }
    rowIndexByKey.push(rowIndex);
  }
  return { queryName, result, rowIndexByKey };
}

function isPivotLayout(value: PivotTotals | PivotLayout): value is PivotLayout {
  return "kind" in value;
}

// One header row per column field, plus a row of value names when several values share each column key.
export function pivotHeaderRowCount(pivot: TablePivot): number {
  return pivot.columns.length + (pivot.valuesAxis === "COLUMNS" && pivot.values.length > 1 ? 1 : 0);
}

export function pivotLayout(pivot: TablePivot, primary: NamedDashboardResult, totals: readonly PivotTotalsResult[]): PivotLayout {
  const rowFields = pivot.rows.map((column) => ({ field: column.field, label: column.header ?? column.field, width: column.width ?? DEFAULT_COLUMN_WIDTH }));
  const columnFields = pivot.columns.map((column) => ({ field: column.field, label: column.header ?? column.field, width: DEFAULT_COLUMN_WIDTH }));
  const valueFields = pivot.values.map((column) => ({ field: column.field, label: column.header ?? column.field, width: column.width ?? DEFAULT_COLUMN_WIDTH }));
  const primaryColumns = new Set(primary.columns.map((column) => column.name));
  const missingField = [...rowFields, ...columnFields, ...valueFields].find((field) => !primaryColumns.has(field.field));
  if (missingField) return pivotError("MISSING_COLUMN", `Column '${missingField.field}' is missing from the result.`);

  const keyFields = [...rowFields, ...columnFields];
  const rowKeyIndexByText = new Map<string, number>();
  const rowKeyBuilders: { values: RawCell[]; sourceRowIndices: number[]; sourceRowByColumnKeyText: Map<string, number> }[] = [];
  const columnKeyByText = new Map<string, readonly RawCell[]>();
  for (let rowIndex = 0; rowIndex < primary.rows.length; rowIndex++) {
    const row = primary.rows[rowIndex];
    const rowKey = rowFields.map((field) => row[field.field]);
    const columnKey = columnFields.map((field) => row[field.field]);
    const rowText = keyText(rowKey);
    const columnText = keyText(columnKey);
    let rowKeyIndex = rowKeyIndexByText.get(rowText);
    if (rowKeyIndex === undefined) {
      rowKeyIndex = rowKeyBuilders.length;
      rowKeyIndexByText.set(rowText, rowKeyIndex);
      rowKeyBuilders.push({ values: rowKey, sourceRowIndices: [], sourceRowByColumnKeyText: new Map() });
    }
    const builder = rowKeyBuilders[rowKeyIndex];
    if (builder.sourceRowByColumnKeyText.has(columnText)) {
      return pivotError("DUPLICATE_KEY", `More than one row has ${describeKey(keyFields, [...rowKey, ...columnKey])}. Each row and column pair must appear once.`);
    }
    builder.sourceRowByColumnKeyText.set(columnText, rowIndex);
    builder.sourceRowIndices.push(rowIndex);
    if (!columnKeyByText.has(columnText)) columnKeyByText.set(columnText, columnKey);
  }

  const columnLogicalTypes = columnFields.map((field) => primary.columns.find((column) => column.name === field.field)?.logicalType);
  const columnKeys = [...columnKeyByText.values()].sort((left, right) => compareKeys(left, right, columnLogicalTypes));
  const valuesPerColumnKey = pivot.valuesAxis === "COLUMNS" ? valueFields.length : 1;
  const generatedColumns = columnKeys.length * valuesPerColumnKey;
  if (generatedColumns > PIVOT_COLUMN_LIMIT) {
    return pivotError("TOO_MANY_COLUMNS", `This pivot would create ${generatedColumns.toLocaleString("en-US")} columns. The limit is ${PIVOT_COLUMN_LIMIT}. Narrow the query.`);
  }
  const columnKeyIndexByText = new Map(columnKeys.map((key, index) => [keyText(key), index]));
  const rowKeys: PivotRowKey[] = rowKeyBuilders.map((builder) => ({
    values: builder.values,
    sourceRowIndices: builder.sourceRowIndices,
    sourceRowByColumnKey: new Map([...builder.sourceRowByColumnKeyText].map(([text, rowIndex]) => [columnKeyIndexByText.get(text)!, rowIndex])),
  }));

  let rowTotals: PivotTotals | null = null;
  let columnTotals: PivotTotals | null = null;
  let grandTotal: PivotTotals | null = null;
  const placedBy = new Map<"ROW" | "COLUMN" | "GRAND", string>();
  for (const totalsResult of totals) {
    const { queryName, result } = totalsResult;
    const totalsColumns = new Set(result.columns.map((column) => column.name));
    const missingValue = valueFields.find((field) => !totalsColumns.has(field.field));
    if (missingValue) return pivotError("MISSING_COLUMN", `Column '${missingValue.field}' is missing from the total query "${queryName}".`);
    // Placement reads the totals result's columns because Page readers receive no query definitions.
    const groupedRows = rowFields.filter((field) => totalsColumns.has(field.field)).length;
    const groupedColumns = columnFields.filter((field) => totalsColumns.has(field.field)).length;
    const placement = groupedRows === rowFields.length && groupedColumns === 0 ? "ROW"
      : rowFields.length > 0 && groupedRows === 0 && groupedColumns === columnFields.length ? "COLUMN"
        : groupedRows === 0 && groupedColumns === 0 ? "GRAND" : null;
    if (!placement) {
      return pivotError("TOTALS_PLACEMENT", `The total query "${queryName}" does not fit this pivot. Group it by all row fields, all column fields, or nothing.`);
    }
    const placedQuery = placedBy.get(placement);
    if (placedQuery) {
      return pivotError("TOTALS_PLACEMENT", `The total queries "${placedQuery}" and "${queryName}" group by the same fields. Each total query must fill a different place.`);
    }
    placedBy.set(placement, queryName);
    const lookup = placement === "ROW" ? totalsLookup(totalsResult, rowFields, rowKeys.map((key) => key.values))
      : placement === "COLUMN" ? totalsLookup(totalsResult, columnFields, columnKeys)
        : totalsLookup(totalsResult, [], [[]]);
    if (isPivotLayout(lookup)) return lookup;
    if (placement === "ROW") rowTotals = lookup;
    else if (placement === "COLUMN") columnTotals = lookup;
    else grandTotal = lookup;
  }

  const hasTotalColumn = rowTotals !== null || grandTotal !== null;
  const hasTotalRow = columnTotals !== null || grandTotal !== null;
  const widestValue = Math.max(...valueFields.map((field) => field.width));
  const columns: PivotColumn[] = rowFields.map((rowField) => ({ kind: "ROW_FIELD", id: `row:${rowField.field}`, width: rowField.width, rowField }));
  if (pivot.valuesAxis === "ROWS") columns.push({ kind: "VALUE_NAME", id: "values", width: DEFAULT_COLUMN_WIDTH });
  const firstValueColumn = columns.length;
  columnKeys.forEach((_, columnKeyIndex) => {
    if (pivot.valuesAxis === "COLUMNS") {
      for (const valueField of valueFields) columns.push({ kind: "VALUE", id: `value:${columnKeyIndex}:${valueField.field}`, width: valueField.width, columnKeyIndex, valueField });
    } else columns.push({ kind: "VALUE", id: `value:${columnKeyIndex}`, width: widestValue, columnKeyIndex, valueField: null });
  });
  const firstTotalColumn = columns.length;
  if (hasTotalColumn) {
    if (pivot.valuesAxis === "COLUMNS") {
      for (const valueField of valueFields) columns.push({ kind: "TOTAL", id: `total:${valueField.field}`, width: valueField.width, valueField });
    } else columns.push({ kind: "TOTAL", id: "total", width: widestValue, valueField: null });
  }

  const valueHeaderRow = pivot.valuesAxis === "COLUMNS" && valueFields.length > 1;
  const headerRowCount = pivotHeaderRowCount(pivot);
  const headerRows: PivotHeaderCell[][] = Array.from({ length: headerRowCount }, () => []);
  columns.forEach((column, columnIndex) => {
    if (column.kind === "ROW_FIELD") headerRows[0].push({ kind: "ROW_FIELD", id: column.id, rowSpan: headerRowCount, columnIndex, rowField: column.rowField });
    if (column.kind === "VALUE_NAME") headerRows[0].push({ kind: "VALUE_NAME", id: column.id, rowSpan: headerRowCount, columnIndex });
  });
  columnFields.forEach((_, level) => {
    const leafLevel = level === columnFields.length - 1 && !valueHeaderRow;
    for (let groupStart = 0; groupStart < columnKeys.length;) {
      let groupEnd = groupStart + 1;
      while (groupEnd < columnKeys.length && sameKeyPrefix(columnKeys[groupStart], columnKeys[groupEnd], level + 1)) groupEnd++;
      headerRows[level].push({
        kind: "COLUMN_KEY",
        id: `key:${level}:${groupStart}`,
        colSpan: (groupEnd - groupStart) * valuesPerColumnKey,
        level,
        columnKeyIndex: groupStart,
        columnIndex: leafLevel ? firstValueColumn + groupStart : null,
      });
      groupStart = groupEnd;
    }
  });
  if (valueHeaderRow) {
    columnKeys.forEach((_, columnKeyIndex) => valueFields.forEach((valueField, valueIndex) => headerRows[headerRowCount - 1].push({
      kind: "VALUE", id: `value:${columnKeyIndex}:${valueField.field}`, columnKeyIndex, valueField, columnIndex: firstValueColumn + columnKeyIndex * valueFields.length + valueIndex,
    })));
  }
  if (hasTotalColumn) {
    if (valueHeaderRow) {
      headerRows[0].push({ kind: "TOTAL", id: "total", colSpan: valueFields.length, rowSpan: headerRowCount - 1, columnIndex: null });
      valueFields.forEach((valueField, valueIndex) => headerRows[headerRowCount - 1].push({ kind: "TOTAL_VALUE", id: `total:${valueField.field}`, valueField, columnIndex: firstTotalColumn + valueIndex }));
    } else headerRows[0].push({ kind: "TOTAL", id: "total", colSpan: 1, rowSpan: headerRowCount, columnIndex: firstTotalColumn });
  }

  const bodyRows: PivotBodyRow[] = pivot.valuesAxis === "COLUMNS"
    ? rowKeys.map((_, rowKeyIndex) => ({ kind: "BODY", id: `row:${rowKeyIndex}`, rowKeyIndex, valueField: null }))
    : rowKeys.flatMap((_, rowKeyIndex) => valueFields.map((valueField) => ({ kind: "BODY" as const, id: `row:${rowKeyIndex}:${valueField.field}`, rowKeyIndex, valueField })));
  const totalRows: Extract<PivotRow, { kind: "TOTAL" }>[] = !hasTotalRow ? []
    : pivot.valuesAxis === "COLUMNS" ? [{ kind: "TOTAL", id: "total", valueField: null }]
      : valueFields.map((valueField) => ({ kind: "TOTAL", id: `total:${valueField.field}`, valueField }));

  return {
    kind: "GRID",
    grid: { primary, valuesAxis: pivot.valuesAxis, rowFields, columnFields, valueFields, rowKeys, columnKeys, rowTotals, columnTotals, grandTotal, columns, headerRows, bodyRows, totalRows },
  };
}

function totalCell(totals: PivotTotals | null, keyIndex: number, field: string): PivotCell {
  if (!totals) return { kind: "EMPTY" };
  return { kind: "TOTAL", result: totals.result, field, value: totals.result.rows[totals.rowIndexByKey[keyIndex]][field] };
}

export function pivotCell(grid: PivotGrid, row: PivotRow, column: PivotColumn): PivotCell {
  if (column.kind === "VALUE_NAME") return { kind: "VALUE_NAME", label: row.valueField?.label ?? "" };
  const field = column.kind === "ROW_FIELD" ? column.rowField.field : (column.valueField ?? row.valueField)!.field;
  if (row.kind === "TOTAL") {
    if (column.kind === "ROW_FIELD") return grid.rowFields[0] === column.rowField ? { kind: "TOTAL_LABEL" } : { kind: "EMPTY" };
    return column.kind === "VALUE" ? totalCell(grid.columnTotals, column.columnKeyIndex, field) : totalCell(grid.grandTotal, 0, field);
  }
  const rowKey = grid.rowKeys[row.rowKeyIndex];
  if (column.kind === "ROW_FIELD") {
    const sourceRowIndex = rowKey.sourceRowIndices[0];
    return { kind: "ROW_HEADER", field, value: grid.primary.rows[sourceRowIndex][field], sourceRowIndex };
  }
  if (column.kind === "TOTAL") return totalCell(grid.rowTotals, row.rowKeyIndex, field);
  const sourceRowIndex = rowKey.sourceRowByColumnKey.get(column.columnKeyIndex);
  if (sourceRowIndex === undefined) return { kind: "EMPTY" };
  return { kind: "VALUE", field, value: grid.primary.rows[sourceRowIndex][field], sourceRowIndex };
}

export function pivotColumnSortable(grid: PivotGrid, column: PivotColumn): boolean {
  return column.kind === "ROW_FIELD" || grid.valuesAxis === "COLUMNS" && (column.kind === "VALUE" || column.kind === "TOTAL");
}

// Row-key groups move together in ROWS mode because every row of a group shares the sorted row field value.
export function sortedPivotBodyRows(grid: PivotGrid, sort: PivotSort | null): readonly PivotBodyRow[] {
  const column = sort ? grid.columns.find((candidate) => candidate.id === sort.columnId) : undefined;
  if (!sort || !column || !pivotColumnSortable(grid, column)) return grid.bodyRows;
  const keys = grid.bodyRows.map((row) => {
    const cell = pivotCell(grid, row, column);
    if (cell.kind === "ROW_HEADER" || cell.kind === "VALUE") return { value: cell.value, logicalType: grid.primary.columns.find((candidate) => candidate.name === cell.field)?.logicalType };
    if (cell.kind === "TOTAL") return { value: cell.value, logicalType: cell.result.columns.find((candidate) => candidate.name === cell.field)?.logicalType };
    return { value: null, logicalType: undefined };
  });
  const order = grid.bodyRows.map((_, index) => index).sort((leftIndex, rightIndex) => {
    const left = keys[leftIndex].value;
    const right = keys[rightIndex].value;
    if (left === null || right === null) {
      if (left === null && right === null) return leftIndex - rightIndex;
      return left === null ? 1 : -1;
    }
    const comparison = compareTableValues(left, right, keys[leftIndex].logicalType);
    return comparison === 0 ? leftIndex - rightIndex : comparison * (sort.direction === "ascending" ? 1 : -1);
  });
  return order.map((index) => grid.bodyRows[index]);
}

// Source requests depend only on row keys, so a selected row whose row key is already visible keeps the
// visible list itself and the rendered batch stays current.
export function pivotRequestRows(visibleRows: readonly PivotBodyRow[], selectedRow: PivotBodyRow | undefined): readonly PivotBodyRow[] {
  if (!selectedRow || visibleRows.some((row) => row.rowKeyIndex === selectedRow.rowKeyIndex)) return visibleRows;
  return [...visibleRows, selectedRow];
}

// The source module formats cells at their original addresses: each value cell's own source row and
// each row header's first source row. Empty and total cells have no primary address and are not requested.
export function pivotSourceRequests(grid: PivotGrid, rows: readonly PivotBodyRow[]): TableBatchRequest[] {
  const rowKeyIndices = [...new Set(rows.map((row) => row.rowKeyIndex))];
  return [
    { rowIndices: rowKeyIndices.flatMap((index) => grid.rowKeys[index].sourceRowIndices), fields: grid.valueFields.map((field) => field.field) },
    { rowIndices: rowKeyIndices.map((index) => grid.rowKeys[index].sourceRowIndices[0]), fields: grid.rowFields.map((field) => field.field) },
  ].filter((request) => request.rowIndices.length > 0 && request.fields.length > 0);
}

// A value field's body and total cells share one fraction width, wide enough for every value without rounding.
export function pivotValueFractionDigits(grid: PivotGrid): Map<string, number> {
  const results = [grid.primary, grid.rowTotals?.result, grid.columnTotals?.result, grid.grandTotal?.result]
    .filter((result): result is NamedDashboardResult => result !== undefined);
  return new Map(grid.valueFields.map((field) => [field.field, columnFractionDigits(results.flatMap((result) => result.rows.map((row) => row[field.field])))]));
}
