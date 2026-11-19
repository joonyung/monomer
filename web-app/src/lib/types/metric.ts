import type { TimeGranularity } from "@/lib/types/semantic-model";
import type { MetricDisplay } from "@/lib/metric-display";
import type { components } from "@/api/control/generated";

export type { TimeGranularity };

export type MetricType = "simple" | "ratio" | "derived" | "cumulative" | "conversion";
export type PeriodAggregation = "first" | "last" | "average";
export type ConversionCalculationType = "conversions" | "conversion_rate";

export type FilterOperator =
  | "eq"
  | "ne"
  | "gt"
  | "gte"
  | "lt"
  | "lte"
  | "in"
  | "not_in"
  | "between"
  | "is_null"
  | "is_not_null"
  | "like"
  | "not_like"
  | "contains"
  | "starts_with";

export interface MetricDimensionRef {
  semanticModelName: string;
  name: string;
  granularity?: TimeGranularity;
}

export interface MetricFilterPredicate {
  dimension: MetricDimensionRef;
  operator: FilterOperator;
  values: string[];
}

export interface MetricFilterAnd {
  and: MetricFilter[];
}

export interface MetricFilterOr {
  or: MetricFilter[];
}

export interface MetricFilterNot {
  not: MetricFilter;
}

export type MetricFilter = MetricFilterPredicate | MetricFilterAnd | MetricFilterOr | MetricFilterNot;

export interface MetricRef {
  metricName: string;
  alias?: string;
  filter?: MetricFilter;
  offsetWindow?: string;
  offsetToGrain?: TimeGranularity;
}

export interface ConstantProperty {
  baseProperty: string;
  conversionProperty: string;
}

export interface SimpleMetricTypeParams {
  semanticModelName: string;
  measure: string;
}

export interface RatioMetricTypeParams {
  numerator: MetricRef;
  denominator: MetricRef;
}

export interface DerivedMetricTypeParams {
  expression: string;
  inputMetrics: MetricRef[];
}

export interface CumulativeMetricTypeParams {
  inputMetric: MetricRef;
  window?: string;
  grainToDate?: TimeGranularity;
  periodAggregation?: PeriodAggregation;
}

export interface ConversionMetricTypeParams {
  entity: string;
  calculation: ConversionCalculationType;
  baseMetric: MetricRef;
  conversionMetric: MetricRef;
  constantProperties?: ConstantProperty[];
  window?: string;
}

export type MetricTypeParams =
  | SimpleMetricTypeParams
  | RatioMetricTypeParams
  | DerivedMetricTypeParams
  | CumulativeMetricTypeParams
  | ConversionMetricTypeParams;

export interface Metric {
  id: string;
  name: string;
  label?: string;
  description?: string;
  type: MetricType;
  typeParams: MetricTypeParams;
  filters?: MetricFilter[];
  timeGranularity?: TimeGranularity;
  display?: MetricDisplay | null;
  sourcePartitionInfo?: components["schemas"]["MetricResponse"]["sourcePartitionInfo"];
  createdAt: string;
  updatedAt: string;
}

export interface MetricSummary {
  id: string;
  name: string;
  label?: string;
  description?: string;
  type: MetricType;
  createdAt: string;
  updatedAt: string;
}

export function getMetricLabel(metric: Metric | MetricSummary): string {
  return metric.label || metric.name;
}
