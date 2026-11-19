import type { DerivedMetricTypeParams, MetricType, MetricTypeParams } from "@/lib/types/metric";

export function metricStorageDefinition(type: MetricType, typeParams: MetricTypeParams): { type: MetricType; typeParams: MetricTypeParams } {
  if (type !== "derived") return { type, typeParams };
  const params = typeParams as DerivedMetricTypeParams;
  const division = /^\s*([A-Za-z_][A-Za-z0-9_]*)\s*[/÷]\s*([A-Za-z_][A-Za-z0-9_]*)\s*$/.exec(params.expression);
  if (!division || params.inputMetrics.length !== 2) return { type, typeParams };
  const inputFor = (token: string) => params.inputMetrics.filter((input) => (input.alias || input.metricName) === token);
  const numerator = inputFor(division[1]);
  const denominator = inputFor(division[2]);
  if (numerator.length !== 1 || denominator.length !== 1 || numerator[0] === denominator[0]) return { type, typeParams };
  return { type: "ratio", typeParams: { numerator: numerator[0], denominator: denominator[0] } };
}
