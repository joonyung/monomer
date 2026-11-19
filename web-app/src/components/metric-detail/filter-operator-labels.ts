import type { FilterOperator } from "@/lib/types/metric";

// People read operators as words; the DSL tokens stay on the wire.
export const FILTER_OPERATOR_LABELS: Record<FilterOperator, string> = {
  eq: "is",
  ne: "is not",
  gt: "greater than",
  gte: "greater than or equal to",
  lt: "less than",
  lte: "less than or equal to",
  in: "is one of",
  not_in: "is not one of",
  between: "is between",
  is_null: "is empty",
  is_not_null: "is not empty",
  like: "matches",
  not_like: "does not match",
  contains: "contains",
  starts_with: "starts with",
};

export function filterValuesText(operator: FilterOperator, values: readonly string[]): string {
  if (operator === "between") return values.join(" and ");
  return values.join(", ");
}
