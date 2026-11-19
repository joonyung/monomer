export const TIME_SPINE_GRANULARITIES = [
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

export type TimeSpineGranularity = (typeof TIME_SPINE_GRANULARITIES)[number];

export interface TimeSpineRelation {
  catalog?: string;
  schema: string;
  relation: string;
}

export interface TimeSpinePrimaryColumn {
  name: string;
  granularity: TimeSpineGranularity;
}

export interface TimeSpine {
  relation: TimeSpineRelation;
  primaryColumn: TimeSpinePrimaryColumn;
}

export interface TimeSpineDefinition {
  schemaVersion: 1;
  timeSpines: TimeSpine[];
}

export interface TimeSpineConfiguration {
  connectorId: string;
  definition: TimeSpineDefinition;
}
