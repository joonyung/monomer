import { describe, expect, it } from "vitest";
import type { SemanticModelResponse, SourcePartitionInfo } from "@/api/semantic-model";
import type { Metric } from "@/lib/types/metric";
import { emptyExploreDraft } from "@/pages/explore/explore-draft";
import { partitionFilterCondition, queryPartitionHints, sourcePartitionFilter } from "./source-partition";

const source: SourcePartitionInfo = { semanticModelName: "orders", connectorId: "bq", relation: { catalog: "project", schema: "analytics", name: "orders" }, requiresPartitionFilter: true, partitionKeys: ["order_date"], dimensions: ["ordered_at"], timeRangeApplies: true };
const model: SemanticModelResponse = { id: "m1", name: "orders", sourceRelations: [{ connectorId: "bq", catalog: "project", schema: "analytics", relation: "orders" }], sourcePartitionInfo: [source], entities: [], dimensions: [{ name: "ordered_at", label: "Order date", expression: "order_date", type: "time", granularity: "day", isPartition: false }], measures: [], createdAt: "", updatedAt: "" };
const metrics: Metric[] = [
  { id: "a", name: "revenue", type: "simple", typeParams: { semanticModelName: "orders", measure: "revenue" }, createdAt: "", updatedAt: "" },
  { id: "r", name: "ratio", type: "ratio", typeParams: { numerator: { metricName: "revenue" }, denominator: { metricName: "revenue" } }, createdAt: "", updatedAt: "" },
];
const draft = { ...emptyExploreDraft().semantic, metrics: ["ratio"] };

describe("query partition hints", () => {
  it("reads the selected metric closure once per model and keeps missing-dimension hints", () => {
    expect(queryPartitionHints(draft, "bq", metrics, [model])).toEqual([sourcePartitionFilter(source)]);
    expect(queryPartitionHints(draft, "bq", metrics, [{ ...model, sourcePartitionInfo: [{ ...source, dimensions: [] }] }])[0]).toMatchObject({ dimensions: [] });
  });

  it("dismisses a hint for a relevant time range or dimension filter, not an unrelated filter", () => {
    const range = { start: { kind: "DATE" as const, value: "2026-10-01" }, end: { kind: "DATE" as const, value: "2026-10-08" } };
    expect(queryPartitionHints({ ...draft, metricTimeRange: range }, "bq", metrics, [model])).toEqual([]);
    const filter = { kind: "DATE" as const, dimension: { semanticModelName: "orders", name: "ordered_at" }, operator: "EQUALS" as const, values: ["2026-10-01"] };
    expect(queryPartitionHints({ ...draft, filters: [filter] }, "bq", metrics, [model])).toEqual([]);
    expect(queryPartitionHints({ ...draft, filters: [{ ...filter, dimension: { semanticModelName: "events", name: "ordered_at" } }] }, "bq", metrics, [model])).toHaveLength(1);
    expect(queryPartitionHints({ ...draft, metricTimeRange: range }, "bq", metrics, [{ ...model, sourcePartitionInfo: [{ ...source, timeRangeApplies: false }] }])).toHaveLength(1);
  });

  it("includes models named by group-bys and filters, but never unknown or another connector's facts", () => {
    const events = { ...model, id: "m2", name: "events", sourcePartitionInfo: [{ ...source, semanticModelName: "events" }] };
    expect(queryPartitionHints({ ...draft, groupBys: [{ kind: "ENTITY", semanticModelName: "events", name: "user" }] }, "bq", metrics, [model, events])).toHaveLength(2);
    expect(queryPartitionHints(draft, "other", metrics, [model])).toEqual([]);
    expect(queryPartitionHints(draft, "bq", metrics, [{ ...model, sourcePartitionInfo: [{ ...source, requiresPartitionFilter: null }] }])).toEqual([]);
    expect(queryPartitionHints(draft, "bq", metrics, [{ ...model, sourcePartitionInfo: [{ ...source, requiresPartitionFilter: false }] }])).toEqual([]);
  });

  it("retains satisfied facts for a warehouse failure and uses product labels for Check", () => {
    expect(queryPartitionHints({ ...draft, metricTimeRange: { start: { kind: "DATE" as const, value: "2026-10-01" }, end: { kind: "DATE" as const, value: "2026-10-08" } } }, "bq", metrics, [model], true)).toHaveLength(1);
    expect(partitionFilterCondition(sourcePartitionFilter(source), [model])).toBe("needs a time range");
    expect(partitionFilterCondition(sourcePartitionFilter({ ...source, timeRangeApplies: false }), [model])).toBe("needs a filter on Order date");
    expect(partitionFilterCondition({ kind: "TIME_SPINE", connectorId: "bq", relation: source.relation, partitionKeys: ["date_day"] }, [model])).toBe("needs a partition filter on time spine project.analytics.orders");
  });
});
