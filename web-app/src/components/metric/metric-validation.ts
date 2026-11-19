import type {
  ConversionMetricTypeParams,
  CumulativeMetricTypeParams,
  DerivedMetricTypeParams,
  MetricFilter,
  MetricRef,
  MetricType,
  MetricTypeParams,
  RatioMetricTypeParams,
  SimpleMetricTypeParams,
} from "@/lib/types/metric";
import { TIME_GRANULARITIES } from "@/lib/types/semantic-model";
import {
  TECHNICAL_NAME_PATTERN,
  technicalNameProblem,
  type ValidationProblem,
} from "@/components/semantic-model/semantic-model-validation";

export interface MetricDraft {
  name: string;
  type: MetricType;
  params: MetricTypeParams;
  filters: MetricFilter[];
}

export function validWindow(value: string): boolean {
  return /^[1-9][0-9]*\s+(?:day|week|month|quarter|year)s?$/.test(value.trim());
}

export function validFilter(node: MetricFilter): boolean {
  if ("and" in node) return node.and.length > 0 && node.and.every(validFilter);
  if ("or" in node) return node.or.length > 0 && node.or.every(validFilter);
  if ("not" in node) return validFilter(node.not);
  if (!node.dimension.semanticModelName || !node.dimension.name) return false;
  if (node.operator === "is_null" || node.operator === "is_not_null") return node.values.length === 0;
  if (node.operator === "between") return node.values.length === 2 && node.values.every(Boolean);
  if (node.operator === "in" || node.operator === "not_in") return node.values.length > 0 && node.values.every(Boolean);
  return node.values.length === 1 && Boolean(node.values[0]);
}

export function sameEffectiveRef(left: MetricRef, right: MetricRef): boolean {
  const effective = (ref: MetricRef) => JSON.stringify({
    metricName: ref.metricName,
    filter: ref.filter,
    offsetWindow: ref.offsetWindow,
    offsetToGrain: ref.offsetToGrain,
  });
  return effective(left) === effective(right);
}

/** The message for one metric input, or null when it is complete. */
function refProblem(ref: MetricRef | undefined, missing: string): string | null {
  if (!ref?.metricName) return missing;
  if (!TECHNICAL_NAME_PATTERN.test(ref.metricName)) return missing;
  if (ref.filter && !validFilter(ref.filter)) return "Complete the input filter: model, dimension, and value.";
  if (ref.offsetWindow && !validWindow(ref.offsetWindow)) return "Enter an offset window such as 1 month.";
  if (ref.offsetToGrain && !TIME_GRANULARITIES.includes(ref.offsetToGrain)) return "Select an offset grain.";
  return null;
}

/**
 * Field-level problems for a metric draft. `field` keys the inline message; `subject` names the
 * field in the summary. Messages follow the order of the form.
 */
export function validateMetricDraft(draft: MetricDraft): ValidationProblem[] {
  const problems: ValidationProblem[] = [];
  const push = (field: string, subject: string, message: string | null) => {
    if (message) problems.push({ field, subject, message });
  };
  push("name", "Name", technicalNameProblem(draft.name, "gross_revenue"));

  switch (draft.type) {
    case "simple": {
      const params = draft.params as SimpleMetricTypeParams;
      push("semanticModel", "Model", params.semanticModelName ? null : "Select a model.");
      if (params.semanticModelName) push("measure", "Aggregate metric", params.measure ? null : "Select an aggregate.");
      break;
    }
    case "ratio": {
      const params = draft.params as RatioMetricTypeParams;
      push("numerator", "Numerator", refProblem(params.numerator, "Select a numerator metric."));
      const denominator = refProblem(params.denominator, "Select a denominator metric.")
        ?? (params.numerator.metricName && sameEffectiveRef(params.numerator, params.denominator) ? "Numerator and denominator must differ." : null);
      push("denominator", "Denominator", denominator);
      break;
    }
    case "derived": {
      const params = draft.params as DerivedMetricTypeParams;
      if (params.inputMetrics.length === 0) push("inputs", "Input metrics", "Add at least one input metric.");
      params.inputMetrics.forEach((input, index) => push(`input-${index}`, `Input ${index + 1}`, refProblem(input, "Select a metric.")));
      push("expression", "Expression", params.expression.trim() ? null : "Enter an expression.");
      break;
    }
    case "cumulative": {
      const params = draft.params as CumulativeMetricTypeParams;
      push("inputMetric", "Input metric", refProblem(params.inputMetric, "Select an input metric."));
      push("window", "Window", params.window && params.grainToDate
        ? "Use a window or grain to date, not both."
        : params.window && !validWindow(params.window) ? "Enter a window such as 7 days." : null);
      break;
    }
    case "conversion": {
      const params = draft.params as ConversionMetricTypeParams;
      push("entity", "Key", TECHNICAL_NAME_PATTERN.test(params.entity) ? null : "Select the key that links both metrics.");
      push("baseMetric", "Base metric", refProblem(params.baseMetric, "Select a base metric."));
      const conversion = refProblem(params.conversionMetric, "Select a conversion metric.")
        ?? (params.baseMetric.metricName && sameEffectiveRef(params.baseMetric, params.conversionMetric) ? "Base and conversion metrics must differ." : null);
      push("conversionMetric", "Conversion metric", conversion);
      push("window", "Window", params.window && !validWindow(params.window) ? "Enter a window such as 7 days." : null);
      push("constantProperties", "Constant properties", (params.constantProperties ?? []).every((property) =>
        TECHNICAL_NAME_PATTERN.test(property.baseProperty) && TECHNICAL_NAME_PATTERN.test(property.conversionProperty))
        ? null
        : "Use lowercase names for both properties.");
      break;
    }
  }
  push("filters", "Filters", draft.filters.every(validFilter) ? null : "Complete each filter: model, dimension, and value.");
  return problems;
}
