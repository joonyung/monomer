import type { DashboardResult, DashboardResultColumn, DashboardQueryType } from "@/api/dashboard";
import type { ChartQueryResult } from "@/features/chart-widget/contract/abi";
import { metricNumberFormat, type MetricNumberFormat } from "@/lib/metric-display";

type DashboardResultCell = string | boolean | null;
type DashboardResultRow = Readonly<Record<string, DashboardResultCell>>;
export type DashboardFrameColumn = Readonly<{
  name: string;
  logicalType: string;
  nullable: boolean;
  numberFormat: MetricNumberFormat | null;
}>;

export type NamedDashboardResult = Readonly<{
  queryRunId: string;
  queryType: DashboardQueryType;
  // The zone a Semantic run grouped days and read times in; null for SQL runs and older runs.
  reportingTimeZone: string | null;
  columns: readonly DashboardFrameColumn[];
  rows: readonly DashboardResultRow[];
}>;

function freezeColumns(
  columns: readonly DashboardResultColumn[],
  queryType: DashboardQueryType,
): readonly DashboardFrameColumn[] {
  return Object.freeze(columns.map((column) => Object.freeze({
    name: column.name,
    logicalType: column.logicalType,
    nullable: column.nullable,
    numberFormat: queryType === "SEMANTIC" ? metricNumberFormat(column.display) : null,
  })));
}

export function validateDashboardResult(result: DashboardResult): NamedDashboardResult {
  if (result.queryType !== "SEMANTIC" && result.queryType !== "SQL") {
    throw new Error("Dashboard result has an unsupported query type.");
  }
  const columns = freezeColumns(result.columns, result.queryType);
  const seen = new Set<string>();
  for (const column of columns) {
    if (column.name.length === 0 || seen.has(column.name)) {
      throw new Error("Dashboard result has duplicate or empty column names.");
    }
    seen.add(column.name);
  }
  const rows = result.rows.map((row) => {
    if (row.length !== columns.length) {
      throw new Error("Dashboard result row width does not match its columns.");
    }
    const record: Record<string, DashboardResultCell> = Object.create(null);
    columns.forEach((column, index) => {
      const value = row[index];
      if (value !== null && typeof value !== "string" && typeof value !== "boolean") {
        throw new Error("Dashboard result contains an unsupported cell.");
      }
      record[column.name] = value;
    });
    return Object.freeze(record);
  });
  return Object.freeze({
    queryRunId: result.queryRunId,
    queryType: result.queryType,
    reportingTimeZone: result.reportingTimeZone,
    columns,
    rows: Object.freeze(rows),
  });
}

export function toChartQueryResult(result: NamedDashboardResult): ChartQueryResult {
  return Object.freeze({
    columns: result.columns,
    rows: result.rows,
  });
}
