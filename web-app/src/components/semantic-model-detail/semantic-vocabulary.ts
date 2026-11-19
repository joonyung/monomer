import {
  TIME_GRANULARITY_LABELS,
  type MeasureAggregation,
  type TimeGranularity,
} from "@/lib/types/semantic-model";
import type {
  ConversionMetricTypeParams,
  CumulativeMetricTypeParams,
  DerivedMetricTypeParams,
  Metric,
  MetricType,
  RatioMetricTypeParams,
  SimpleMetricTypeParams,
} from "@/lib/types/metric";

export const AGGREGATION_LABELS: Record<MeasureAggregation, string> = {
  sum: "Sum",
  count: "Count",
  count_distinct: "Count distinct",
  average: "Average",
  min: "Min",
  max: "Max",
  median: "Median",
  sum_boolean: "Sum of true values",
};

export function granularityLabel(granularity: TimeGranularity): string {
  return TIME_GRANULARITY_LABELS[granularity];
}

export function metricTypeLabel(type: MetricType): string {
  return { simple: "Aggregate", ratio: "Ratio", derived: "Formula", cumulative: "Running total", conversion: "Conversion" }[type];
}

export function metricDefinitionText(metric: Metric): string {
  switch (metric.type) {
    case "simple": {
      const params = metric.typeParams as SimpleMetricTypeParams;
      return `${params.semanticModelName} · ${params.measure}`;
    }
    case "ratio": {
      const params = metric.typeParams as RatioMetricTypeParams;
      return `${params.numerator.metricName} ÷ ${params.denominator.metricName}`;
    }
    case "derived":
      return (metric.typeParams as DerivedMetricTypeParams).expression;
    case "cumulative": {
      const params = metric.typeParams as CumulativeMetricTypeParams;
      return `${params.inputMetric.metricName}${params.window ? ` · ${params.window}` : ""}`;
    }
    case "conversion": {
      const params = metric.typeParams as ConversionMetricTypeParams;
      return `${params.baseMetric.metricName} → ${params.conversionMetric.metricName}${params.window ? ` · ${params.window}` : ""}`;
    }
  }
}
