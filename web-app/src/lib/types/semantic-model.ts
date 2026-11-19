export type EntityType = "primary" | "unique" | "foreign" | "natural";

// Every granularity the Semantic DSL accepts, finest first. Agents may author any of them.
export const TIME_GRANULARITIES = [
  "nanosecond",
  "microsecond",
  "millisecond",
  "second",
  "minute",
  "hour",
  "day",
  "week",
  "month",
  "quarter",
  "year",
] as const;

export type TimeGranularity = (typeof TIME_GRANULARITIES)[number];

export const TIME_GRANULARITY_LABELS: Record<TimeGranularity, string> = {
  nanosecond: "Nanosecond",
  microsecond: "Microsecond",
  millisecond: "Millisecond",
  second: "Second",
  minute: "Minute",
  hour: "Hour",
  day: "Day",
  week: "Week",
  month: "Month",
  quarter: "Quarter",
  year: "Year",
};

// What a time dimension's values mean. Unset, the kind follows the column's warehouse type.
export const TIME_KINDS = ["date", "local", "instant"] as const;

export type TimeKind = (typeof TIME_KINDS)[number];

export const TIME_KIND_LABELS: Record<TimeKind, string> = {
  date: "Date",
  local: "Local time",
  instant: "Instant",
};

export type MeasureAggregation =
  | "sum"
  | "count"
  | "count_distinct"
  | "average"
  | "min"
  | "max"
  | "median"
  | "sum_boolean";

export type NonAdditiveWindowChoice = "min" | "max";

export interface NonAdditiveDimension {
  name: string;
  windowChoice: NonAdditiveWindowChoice;
  windowGroupings?: string[];
}
