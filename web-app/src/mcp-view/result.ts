import { validateDashboardResult, type NamedDashboardResult } from "@/features/dashboard/dashboard-result";
import type { QueryResultBody } from "./contract";

export class ResultNotShownError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ResultNotShownError";
  }
}

// Like the Web, the view shows whatever rows the result returns, including a truncated result.
export function namedResultFromAgent(runPath: string, body: QueryResultBody): NamedDashboardResult {
  if (body.returnedRowCount !== body.rows.length) {
    throw new ResultNotShownError("The result row count does not match its rows.");
  }
  return validateDashboardResult({
    queryRunId: runPath,
    queryType: body.queryType,
    reportingTimeZone: body.reportingTimeZone,
    columns: body.columns.map((column) => ({ name: column.name, logicalType: column.logicalType, nullable: column.nullable, display: column.display })),
    rows: body.rows,
  });
}
