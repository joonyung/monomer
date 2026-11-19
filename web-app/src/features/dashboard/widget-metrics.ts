import type { DashboardDefinition, DashboardQuery, DashboardQueryType } from "@/api/dashboard";
import type { SemanticModelResponse } from "@/api/semantic-model";
import { groupByTitleLabel } from "@/pages/explore/run-presentation";

type DashboardQueries = DashboardDefinition["queries"];
type DashboardGroupBy = Extract<DashboardQuery, { type: "SEMANTIC" }>["query"]["groupBys"][number];

export function dashboardQueryTypes(queries: DashboardQueries): Record<string, DashboardQueryType> {
  return Object.fromEntries(Object.entries(queries).map(([name, query]) => [name, query.type]));
}

export interface WidgetQueryGroupBys {
  queryName: string;
  groupBys: DashboardGroupBy[];
}

export interface WidgetMetricSources {
  metricNames: string[];
  queries: WidgetQueryGroupBys[];
}

export function widgetMetricSources(queries: DashboardQueries, queryNames: readonly string[]): WidgetMetricSources {
  const metricNames = new Set<string>();
  const usedQueries: WidgetQueryGroupBys[] = [];
  for (const queryName of queryNames) {
    if (!Object.hasOwn(queries, queryName)) continue;
    const namedQuery = queries[queryName];
    if (namedQuery.type !== "SEMANTIC") continue;
    const { query } = namedQuery;
    query.metrics.forEach((metricName) => metricNames.add(metricName));
    usedQueries.push({ queryName, groupBys: query.groupBys });
  }
  return { metricNames: [...metricNames], queries: usedQueries };
}

export function queryGroupingText(groupBys: readonly DashboardGroupBy[], models: SemanticModelResponse[]): string {
  if (groupBys.length === 0) return "One total, not grouped";
  return `By ${groupBys.map((groupBy) => groupByTitleLabel(groupBy, models)).join(", ")}`;
}
