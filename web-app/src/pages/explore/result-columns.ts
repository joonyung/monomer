import type { ExploreResult, SemanticQuerySpec } from "@/api/explore";
import type { SemanticModelResponse } from "@/api/semantic-model";
import type { Metric } from "@/lib/types/metric";
import type { ExploreTableColumn } from "./ExploreResultTable";
import { timeGranularityOf } from "./chart-plan";
import { groupByResultKey, semanticResultColumnLabels } from "./semantic-labels";
import { metricNumberFormat } from "@/lib/metric-display";

const NUMERIC_LOGICAL_TYPES = new Set(["INTEGER", "DECIMAL", "FLOAT"]);

/** Table headers: Semantic columns read as human names, SQL columns keep warehouse names. */
export function resultTableColumns(
  result: ExploreResult,
  query: SemanticQuerySpec | null,
  metrics: Metric[],
  models: SemanticModelResponse[],
): ExploreTableColumn[] {
  const names = result.columns.map((column) => column.name);
  const labels = query ? semanticResultColumnLabels(query, names, metrics, models) : names;
  const groupByKeys = query?.groupBys.map(groupByResultKey) ?? [];
  const positional = query !== null && names.length === query.groupBys.length + query.metrics.length;
  return result.columns.map((column, index) => {
    const label = labels[index] ?? column.name;
    const keyed = groupByKeys.indexOf(column.name);
    const groupBy = query ? query.groupBys[keyed >= 0 ? keyed : positional ? index : -1] : undefined;
    return {
      id: `${column.ordinal}-${column.name}`,
      label,
      description: label !== column.name ? `${column.name} · ${column.logicalType}` : column.logicalType,
      numeric: NUMERIC_LOGICAL_TYPES.has(column.logicalType),
      numberFormat: query ? metricNumberFormat(column.display) : null,
      timeGranularity: groupBy ? timeGranularityOf(groupBy) : null,
    };
  });
}
