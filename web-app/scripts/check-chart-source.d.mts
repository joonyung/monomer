import type { DashboardDefinition, DashboardResultColumn } from "../src/api/dashboard";

export interface ChartSourceDiagnostic {
  sourceName: string;
  line: number;
  column: number;
  code: string;
  message: string;
}

export function checkChartSource(input: {
  definition: DashboardDefinition;
  widgetId: string;
  queryColumns?: Readonly<Record<string, readonly DashboardResultColumn[]>>;
  sourceName?: string;
}): ChartSourceDiagnostic[];
