import type { DashboardFrameColumn, NamedDashboardResult } from "@/features/dashboard/dashboard-result";
import { formatMetricNumber } from "@/lib/metric-display";
import { columnFractionDigits, formatNumericCell } from "@/lib/numeric-display";

export type TableScalar = string | boolean | null | undefined;
export type TableSort = { field: string; direction: "ascending" | "descending" };

function compareText(left: string, right: string): number {
  return left < right ? -1 : left > right ? 1 : 0;
}

function decimalParts(value: string) {
  const match = /^([+-]?)(\d+)(?:\.(\d*))?(?:[eE]([+-]?\d+))?$/.exec(value);
  if (!match) return undefined;
  const digits = `${match[2]}${match[3] ?? ""}`.replace(/^0+/, "");
  return {
    sign: digits.length === 0 ? 0 : match[1] === "-" ? -1 : 1,
    digits,
    magnitude: BigInt(match[4] ?? "0") - BigInt(match[3]?.length ?? 0) + BigInt(digits.length),
  };
}

export function compareTableDecimals(left: string, right: string): number {
  const rank = (value: string) => value === "-Infinity" ? -1 : value === "Infinity" ? 1 : value === "NaN" ? 2 : 0;
  const leftRank = rank(left);
  const rightRank = rank(right);
  if (leftRank !== rightRank) return leftRank - rightRank;
  if (leftRank !== 0) return 0;
  const a = decimalParts(left);
  const b = decimalParts(right);
  if (!a || !b) return compareText(left, right);
  if (a.sign !== b.sign) return a.sign - b.sign;
  if (a.sign === 0) return 0;
  if (a.magnitude !== b.magnitude) return (a.magnitude < b.magnitude ? -1 : 1) * a.sign;
  const width = Math.max(a.digits.length, b.digits.length);
  return compareText(a.digits.padEnd(width, "0"), b.digits.padEnd(width, "0")) * a.sign;
}

export function compareTableTimestamps(left: string, right: string): number {
  const parse = (value: string) => {
    const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:\d{2})$/.exec(value);
    if (!match) return undefined;
    const instant = Date.parse(`${match[1]}${match[3]}`);
    return Number.isFinite(instant) ? { instant, fraction: match[2] ?? "" } : undefined;
  };
  const a = parse(left);
  const b = parse(right);
  if (!a || !b) return compareText(left, right);
  if (a.instant !== b.instant) return a.instant < b.instant ? -1 : 1;
  const width = Math.max(a.fraction.length, b.fraction.length);
  return compareText(a.fraction.padEnd(width, "0"), b.fraction.padEnd(width, "0"));
}

export function compareTableValues(left: string | boolean, right: string | boolean, logicalType: string | undefined): number {
  return isNumericLogicalType(logicalType) ? compareTableDecimals(String(left), String(right))
    : logicalType === "TIMESTAMP" ? compareTableTimestamps(String(left), String(right))
      : logicalType === "BOOLEAN" ? Number(left) - Number(right)
        : compareText(String(left), String(right));
}

export function sortedTableRowIndices(result: NamedDashboardResult, sort: TableSort | null): number[] {
  const indices = result.rows.map((_, index) => index);
  if (!sort) return indices;
  const logicalType = result.columns.find((column) => column.name === sort.field)?.logicalType;
  return indices.sort((leftIndex, rightIndex) => {
    const left = result.rows[leftIndex][sort.field];
    const right = result.rows[rightIndex][sort.field];
    if (left == null || right == null) {
      if (left == null && right == null) return leftIndex - rightIndex;
      return left == null ? 1 : -1;
    }
    const comparison = compareTableValues(left, right, logicalType);
    return comparison === 0 ? leftIndex - rightIndex : comparison * (sort.direction === "ascending" ? 1 : -1);
  });
}

export function originalTableCellText(value: TableScalar): string {
  return value === undefined ? "(missing)" : value === null ? "null" : String(value);
}

export function defaultTableCellText(value: TableScalar): string {
  return value === "" ? "(empty string)" : originalTableCellText(value);
}

export function isNumericLogicalType(logicalType: string | undefined): boolean {
  return logicalType === "INTEGER" || logicalType === "DECIMAL" || logicalType === "FLOAT" || logicalType === "NUMBER";
}

export type TableCellText = (field: string, value: TableScalar) => string;

export function numericColumnFractionDigits(result: NamedDashboardResult): Map<string, number> {
  return new Map(result.columns.filter((column) => isNumericLogicalType(column.logicalType))
    .map((column) => [column.name, columnFractionDigits(result.rows.map((row) => row[column.name]))]));
}

// Numeric columns use the stored metric format, or else the given significant fraction digits. The Host and
// the TABLE Worker both format with this function, so a source's displayText is exactly the Host's text.
export function tableCellFormatter(columns: readonly DashboardFrameColumn[], locale: string, fractionDigits: ReadonlyMap<string, number>): TableCellText {
  const numericFields = new Set(columns.filter((column) => isNumericLogicalType(column.logicalType)).map((column) => column.name));
  const columnFormats = new Map(columns.map((column) => [column.name, column.numberFormat]));
  return (field, value) => {
    if (!numericFields.has(field) || typeof value !== "string" || value === "") return defaultTableCellText(value);
    const options = columnFormats.get(field);
    return options ? formatMetricNumber(value, options, locale) : formatNumericCell(value, locale, fractionDigits.get(field) ?? 0);
  };
}

// Groups the integer digits of a canonical decimal string for display without converting it to a
// binary float, so wide integers and long fractions keep every digit. Other spellings stay raw.
export function formatTableNumber(value: string, locale?: string): string {
  const match = /^([+-]?)(\d+)(?:\.(\d+))?$/.exec(value);
  if (!match) return value;
  const parts = new Intl.NumberFormat(locale, { useGrouping: true }).formatToParts(-1234567.5);
  const group = parts.find((part) => part.type === "group")?.value ?? ",";
  const decimal = parts.find((part) => part.type === "decimal")?.value ?? ".";
  const minus = parts.find((part) => part.type === "minusSign")?.value ?? "-";
  const integer = match[2].replace(/\B(?=(\d{3})+(?!\d))/g, group);
  return `${match[1] === "-" ? minus : ""}${integer}${match[3] === undefined ? "" : `${decimal}${match[3]}`}`;
}
