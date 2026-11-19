import { referencedModelNames } from "@/api/metric";
import type { SemanticModelResponse } from "@/api/semantic-model";
import type { Metric, MetricRef, MetricFilter, MetricDimensionRef, SimpleMetricTypeParams, RatioMetricTypeParams, DerivedMetricTypeParams, CumulativeMetricTypeParams, ConversionMetricTypeParams } from "@/lib/types/metric";
import { TIME_GRANULARITY_LABELS, type MeasureAggregation } from "@/lib/types/semantic-model";
import { FILTER_OPERATOR_LABELS, filterValuesText } from "@/components/metric-detail/filter-operator-labels";

export function metricInputs(metric: Metric): Array<{ ref: MetricRef; role: string }> {
  switch (metric.type) {
    case "simple": return [];
    case "ratio": { const value = metric.typeParams as RatioMetricTypeParams; return [{ ref: value.numerator, role: "Numerator" }, { ref: value.denominator, role: "Denominator" }]; }
    case "derived": return (metric.typeParams as DerivedMetricTypeParams).inputMetrics.map((ref) => ({ ref, role: "Input" }));
    case "cumulative": return [{ ref: (metric.typeParams as CumulativeMetricTypeParams).inputMetric, role: "Running total" }];
    case "conversion": { const value = metric.typeParams as ConversionMetricTypeParams; return [{ ref: value.baseMetric, role: "Start event" }, { ref: value.conversionMetric, role: "Completion event" }]; }
  }
}

export function metricFilterDimensions(metric: Metric): MetricDimensionRef[] {
  const dimensions: MetricDimensionRef[] = [];
  const collect = (filter: MetricFilter) => {
    if ("and" in filter) filter.and.forEach(collect);
    else if ("or" in filter) filter.or.forEach(collect);
    else if ("not" in filter) collect(filter.not);
    else dimensions.push(filter.dimension);
  };
  for (const filter of metric.filters ?? []) collect(filter);
  for (const { ref } of metricInputs(metric)) if (ref.filter) collect(ref.filter);
  return dimensions;
}

export function modelsForMetric(metric: Metric, metrics: Metric[], models: SemanticModelResponse[]): SemanticModelResponse[] {
  const names = new Set<string>();
  const visited = new Set<string>();
  const pending = [metric];
  while (pending.length) {
    const current = pending.pop()!;
    if (visited.has(current.name)) continue;
    visited.add(current.name);
    for (const name of referencedModelNames(current)) names.add(name);
    for (const { ref } of metricInputs(current)) { const input = metrics.find((candidate) => candidate.name === ref.metricName); if (input) pending.push(input); }
  }
  return models.filter((model) => names.has(model.name));
}

export function hasMetricSourceConnector(metric: Metric, metrics: Metric[], models: SemanticModelResponse[], connectorIds: string[]): boolean {
  const requiredModels = modelsForMetric(metric, metrics, models);
  return requiredModels.length > 0 && connectorIds.some((connectorId) => requiredModels.every((model) => model.sourceRelations.some((source) => source.connectorId === connectorId)));
}

const AGGREGATION_FUNCTIONS: Record<MeasureAggregation, string> = {
  sum: "SUM", count: "COUNT", count_distinct: "COUNT", average: "AVG",
  min: "MIN", max: "MAX", median: "MEDIAN", sum_boolean: "SUM_BOOLEAN",
};

/** An aggregate as the stored formula reads in the catalog, e.g. `COUNT(DISTINCT order_id)`. */
export function aggregateFormula(aggregation: MeasureAggregation, expression: string): string {
  return `${AGGREGATION_FUNCTIONS[aggregation]}(${aggregation === "count_distinct" ? "DISTINCT " : ""}${expression})`;
}

function storedFormulaOptions(options: object): string {
  return Object.entries(options)
    .filter(([, value]) => value !== undefined)
    .map(([name, value]) => `${name}: ${Array.isArray(value) ? value.map(constantPropertyText).join(", ") : String(value)}`)
    .join(", ");
}

function constantPropertyText(property: { baseProperty: string; conversionProperty: string }): string {
  return `${property.baseProperty} = ${property.conversionProperty}`;
}

/** A metric input as its stored name with its qualifiers named, never as serialized JSON. */
function metricReferenceFormula(ref: MetricRef): string {
  const qualifiers = [
    ref.alias && ref.alias !== ref.metricName ? `alias: ${ref.alias}` : null,
    ref.offsetWindow ? `offset: ${ref.offsetWindow}` : null,
    ref.offsetToGrain ? `offset to: ${ref.offsetToGrain}` : null,
    ref.filter ? `filter: ${metricFilterText(ref.filter)}` : null,
  ].filter((qualifier): qualifier is string => qualifier !== null);
  return ref.metricName + (qualifiers.length ? `(${qualifiers.join(", ")})` : "");
}

/** A stored filter in reading form: `orders.region is one of EMEA, APAC`. */
export function metricFilterText(filter: MetricFilter): string {
  if ("and" in filter) return `(${filter.and.map(metricFilterText).join(" and ")})`;
  if ("or" in filter) return `(${filter.or.map(metricFilterText).join(" or ")})`;
  if ("not" in filter) return `not ${metricFilterText(filter.not)}`;
  const grain = filter.dimension.granularity ? ` by ${filter.dimension.granularity}` : "";
  const values = filter.values.length ? ` ${filterValuesText(filter.operator, filter.values)}` : "";
  return `${filter.dimension.semanticModelName}.${filter.dimension.name}${grain} ${FILTER_OPERATOR_LABELS[filter.operator]}${values}`;
}

/** The stored calculation the catalog shows as written, with input names rather than Labels. */
export function metricCatalogFormula(metric: Metric, models: SemanticModelResponse[]): string {
  switch (metric.type) {
    case "simple": {
      const params = metric.typeParams as SimpleMetricTypeParams;
      const measure = models.find((model) => model.name === params.semanticModelName)?.measures.find((candidate) => candidate.name === params.measure);
      return measure ? aggregateFormula(measure.aggregation, measure.expression) : `${params.semanticModelName}.${params.measure}`;
    }
    case "ratio": {
      const params = metric.typeParams as RatioMetricTypeParams;
      return `${metricReferenceFormula(params.numerator)} / ${metricReferenceFormula(params.denominator)}`;
    }
    case "derived":
      return (metric.typeParams as DerivedMetricTypeParams).expression;
    case "cumulative": {
      const { inputMetric, ...options } = metric.typeParams as CumulativeMetricTypeParams;
      const qualifiers = storedFormulaOptions(options);
      return `CUMULATIVE(${metricReferenceFormula(inputMetric)}${qualifiers ? `, ${qualifiers}` : ""})`;
    }
    case "conversion": {
      const { baseMetric, conversionMetric, ...options } = metric.typeParams as ConversionMetricTypeParams;
      return `CONVERSION(${metricReferenceFormula(baseMetric)} → ${metricReferenceFormula(conversionMetric)}, ${storedFormulaOptions(options)})`;
    }
  }
}

/**
 * What applies beyond the formula: a Formula's qualified inputs, metric filters, and the default grain. The catalog
 * shows these on a second line so the formula itself stays as written.
 */
export function metricCatalogQualifiers(metric: Metric): string[] {
  const inputs = metric.type === "derived"
    ? (metric.typeParams as DerivedMetricTypeParams).inputMetrics.filter((ref) => (ref.alias && ref.alias !== ref.metricName) || ref.filter || ref.offsetWindow || ref.offsetToGrain).map((ref) => `${ref.alias ?? ref.metricName} = ${metricReferenceFormula({ ...ref, alias: undefined })}`)
    : [];
  return [
    ...inputs,
    ...(metric.filters ?? []).map((filter) => `Filter: ${metricFilterText(filter)}`),
    ...(metric.timeGranularity ? [`Default grain: ${TIME_GRANULARITY_LABELS[metric.timeGranularity]}`] : []),
  ];
}

export function referenceProblem(metric: Metric, metrics: Metric[], models: SemanticModelResponse[]): string | null {
  const visited = new Set<string>();
  const pending = [metric];
  while (pending.length) {
    const current = pending.pop()!;
    if (visited.has(current.name)) continue;
    visited.add(current.name);
    for (const name of referencedModelNames(current)) if (!models.some((model) => model.name === name)) return `Missing model: ${name}`;
    for (const dimension of metricFilterDimensions(current)) {
      const model = models.find((candidate) => candidate.name === dimension.semanticModelName);
      if (!model?.dimensions.some((candidate) => candidate.name === dimension.name)) return `Missing dimension: ${dimension.semanticModelName}.${dimension.name}`;
    }
    if (current.type === "simple") { const value = current.typeParams as SimpleMetricTypeParams; if (!models.find((model) => model.name === value.semanticModelName)?.measures.some((measure) => measure.name === value.measure)) return `Missing aggregate: ${value.measure}`; }
    for (const { ref } of metricInputs(current)) { const input = metrics.find((candidate) => candidate.name === ref.metricName); if (!input) return `Missing metric: ${ref.metricName}`; pending.push(input); }
  }
  return null;
}

/** An aggregate over a calculated expression reads only through its description, so one without a description asks for it. */
export function needsCalculationDescription(metric: Metric, models: SemanticModelResponse[]): boolean {
  if (metric.type !== "simple" || metric.description?.trim()) return false;
  const params = metric.typeParams as SimpleMetricTypeParams;
  const expression = models.find((model) => model.name === params.semanticModelName)?.measures.find((measure) => measure.name === params.measure)?.expression;
  return expression !== undefined && singleColumnExpression(expression) === null;
}

export function singleColumnExpression(expression: string): string | null {
  const value = expression.trim();
  return /^[A-Za-z_][A-Za-z0-9_$]*$/.test(value) ? value : null;
}
