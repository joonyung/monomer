import type { ExploreResult, SemanticGroupBy, SemanticQuerySpec, SemanticTimeGranularity } from "@/api/explore";
import { formatCount } from "./run-presentation";
import { groupByResultKey } from "./semantic-labels";

export const MAX_CHART_SERIES = 12;

export interface ChartPlanMetric {
  metricName: string;
  column: string;
}

export type ExploreChartPlan =
  | {
    kind: "ready";
    shape: "line" | "bar";
    /** Column on the x axis: the time grouping for lines, the category for bars. */
    xColumn: string;
    xGroupBy: SemanticGroupBy;
    /** Grain of the x axis when it is a time grouping. */
    xGranularity: SemanticTimeGranularity | null;
    /** Category column that splits a time line into series. */
    seriesColumn: string | null;
    seriesGroupBy: SemanticGroupBy | null;
    metrics: ChartPlanMetric[];
  }
  /** A question without group-bys answers with one row: each metric reads as one number. */
  | { kind: "values"; metrics: ChartPlanMetric[] }
  | { kind: "unavailable"; message: string };

export function timeGranularityOf(groupBy: SemanticGroupBy): SemanticTimeGranularity | null {
  if (groupBy.kind === "METRIC_TIME") return groupBy.granularity;
  return groupBy.kind === "DIMENSION" ? groupBy.granularity ?? null : null;
}

function isTimeAxis(groupBy: SemanticGroupBy): boolean {
  return timeGranularityOf(groupBy) !== null;
}

/**
 * Result columns follow the Semantic projection: group-bys first, then metrics. A column named by its
 * result key maps by key; otherwise a matching column count maps by position.
 */
function projectionColumns(query: SemanticQuerySpec, result: ExploreResult): string[] | null {
  const keys = [
    ...query.groupBys.map(groupByResultKey),
    ...query.metrics.map((metricName) => `metric.${metricName}`),
  ];
  const names = result.columns.map((column) => column.name);
  if (keys.every((key) => names.includes(key))) return keys;
  return names.length === keys.length ? names : null;
}

const SHAPE_MESSAGE = "Charts need a time axis or one category.";

function incompleteMessage(totalRowCount: number, returnedRowCount: number, rowLimit: number, rowLimitReached: boolean): string {
  const advice = "Use a coarser granularity, fewer group-bys, or a filter.";
  if (totalRowCount > returnedRowCount) {
    return `Chart needs the complete result — ${formatCount(totalRowCount)} rows is more than ${formatCount(returnedRowCount)}. ${advice}`;
  }
  return rowLimitReached
    ? `Chart needs the complete result — the query stopped at its ${formatCount(rowLimit)}-row limit. ${advice}`
    : `Chart needs the complete result — the preview stopped early. ${advice}`;
}

/**
 * Decides whether and how a Semantic result can be drawn without re-aggregating it. Rows are drawn
 * exactly as the warehouse returned them, so an incomplete preview never becomes a chart.
 */
export function exploreChartPlan(input: {
  query: SemanticQuerySpec;
  result: ExploreResult;
  totalRowCount: number;
  complete: boolean;
  rowLimitReached: boolean;
}): ExploreChartPlan {
  const { query, result } = input;
  if (query.groupBys.length === 0 && query.metrics.length > 0 && result.rows.length === 1) {
    const columns = projectionColumns(query, result);
    if (columns) return { kind: "values", metrics: query.metrics.map((metricName, index) => ({ metricName, column: columns[index] as string })) };
  }
  const timeAxes = query.groupBys.filter(isTimeAxis);
  const categories = query.groupBys.filter((groupBy) => !isTimeAxis(groupBy));
  const shape = timeAxes.length === 1 && categories.length <= 1
    ? "line"
    : timeAxes.length === 0 && categories.length === 1
      ? "bar"
      : null;
  if (!shape || query.metrics.length === 0) return { kind: "unavailable", message: SHAPE_MESSAGE };
  if (!input.complete) {
    return { kind: "unavailable", message: incompleteMessage(input.totalRowCount, result.returnedRowCount, query.rowLimit, input.rowLimitReached) };
  }
  const columns = projectionColumns(query, result);
  if (!columns) return { kind: "unavailable", message: "This result’s columns don’t match its query, so it can’t be charted." };
  const columnOf = (groupBy: SemanticGroupBy) => columns[query.groupBys.indexOf(groupBy)] as string;
  const xGroupBy = shape === "line" ? timeAxes[0] as SemanticGroupBy : categories[0] as SemanticGroupBy;
  const seriesGroupBy = shape === "line" ? categories[0] ?? null : null;
  const seriesColumn = seriesGroupBy ? columnOf(seriesGroupBy) : null;
  if (seriesColumn) {
    const seriesIndex = result.columns.findIndex((column) => column.name === seriesColumn);
    const seriesCount = new Set(result.rows.map((row) => String(row[seriesIndex]))).size;
    if (seriesCount > MAX_CHART_SERIES) {
      return {
        kind: "unavailable",
        message: `Chart needs ${MAX_CHART_SERIES} or fewer series — this result has ${formatCount(seriesCount)}. Use a filter or a group-by with fewer values.`,
      };
    }
  }
  return {
    kind: "ready",
    shape,
    xColumn: columnOf(xGroupBy),
    xGroupBy,
    xGranularity: timeGranularityOf(xGroupBy),
    seriesColumn,
    seriesGroupBy,
    metrics: query.metrics.map((metricName, index) => ({
      metricName,
      column: columns[query.groupBys.length + index] as string,
    })),
  };
}

/**
 * Dashboard Chart Worker module for an Explore result. It draws one point or bar per returned row
 * and never sums or averages rows. Time values read through the host's xLabels at their grain.
 * Lines order points by their raw time value (empty last) so a
 * question sorted by a metric still draws a forward time axis; bars keep the returned order.
 * More than six bars lie on their side; up to 24 keep every label, more let the axis thin them.
 */
export const EXPLORE_CHART_SOURCE = `
export default defineChart({
  render({ queries, parameters, locale }) {
    const shape = parameters["shape"];
    const x = parameters["xColumn"];
    const byTime = (left, right) => {
      const a = left[x];
      const b = right[x];
      if (a === null || b === null) return a === b ? 0 : a === null ? 1 : -1;
      return String(a) < String(b) ? -1 : String(a) > String(b) ? 1 : 0;
    };
    const rows = shape === "line" ? [...queries["result"].rows].sort(byTime) : queries["result"].rows;
    const y = parameters["metricColumn"];
    const series = parameters["seriesColumn"];
    const xLabels = parameters["xLabels"];
    const label = (value) => value === null ? "No value" : String(value);
    const xLabel = (value) => value === null ? "No value" : (xLabels[String(value)] ?? String(value));
    const point = (value) => value === null ? null : Number(value);
    const categories = [];
    rows.forEach((row) => { const name = xLabel(row[x]); if (!categories.includes(name)) categories.push(name); });
    const groups = [];
    if (series === null) {
      groups.push({ name: parameters["metricLabel"], rows });
    } else {
      rows.forEach((row) => {
        const name = label(row[series]);
        let group = groups.find((candidate) => candidate.name === name);
        if (!group) { group = { name, rows: [] }; groups.push(group); }
        group.rows.push(row);
      });
    }
    const sideways = shape === "bar" && categories.length > 6;
    const categoryAxis = { type: "category", data: categories, name: parameters["xLabel"], nameLocation: "middle", nameGap: 32 };
    const numberFormat = queries["result"].columns.find((column) => column.name === y)?.numberFormat ?? null;
    const axisFormat = new Intl.NumberFormat(locale, { ...(numberFormat ?? {}), notation: "compact", minimumFractionDigits: 0, maximumFractionDigits: 1 });
    const valueAxis = { type: "value", name: parameters["metricLabel"], axisLabel: { formatter: (value) => axisFormat.format(value) } };
    return {
      animation: false,
      grid: sideways
        ? { left: 152, right: 32, top: 24, bottom: 48 }
        : { left: 64, right: 24, top: series === null ? 24 : 48, bottom: 48 },
      legend: series === null ? { show: false } : { top: 0, type: "scroll" },
      tooltip: {
        trigger: shape === "line" ? "axis" : "item",
        formatter: (params) => {
          const items = Array.isArray(params) ? params : [params];
          return items.map((item) => {
            const row = groups[item.seriesIndex]?.rows[item.dataIndex];
            const raw = row?.[y] ?? null;
            const value = raw === null ? "No value" : (parameters["valueLabels"]?.[String(raw)] ?? String(raw));
            return item.seriesName + ": " + value;
          }).join("\\n");
        },
      },
      xAxis: sideways ? { ...valueAxis, nameLocation: "middle", nameGap: 32 } : categoryAxis,
      yAxis: sideways
        ? { type: "category", data: categories, inverse: true, axisLabel: { interval: categories.length <= 24 ? 0 : "auto", width: 136, overflow: "truncate" } }
        : valueAxis,
      series: groups.map((group) => ({
        name: group.name,
        type: shape,
        showSymbol: shape === "line" && categories.length <= 60,
        connectNulls: false,
        data: group.rows.map((row) => sideways ? [point(row[y]), xLabel(row[x])] : [xLabel(row[x]), point(row[y])]),
      })),
    };
  },
});
`;
