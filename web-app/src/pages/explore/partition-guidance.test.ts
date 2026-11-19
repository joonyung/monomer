import { describe, expect, it, vi } from "vitest";
import type { ExploreRun } from "@/api/explore";
import type { SemanticModelResponse, SourcePartitionInfo } from "@/api/semantic-model";
import type { Metric } from "@/lib/types/metric";
import { sourcePartitionFilter } from "@/components/semantics/source-partition";
import { failedRunGuidance, startRejectionGuidance } from "./run-presentation";

const source: SourcePartitionInfo = { semanticModelName: "orders", connectorId: "bq", relation: { catalog: "project", schema: "analytics", name: "orders" }, requiresPartitionFilter: true, partitionKeys: ["order_date"], dimensions: ["ordered_at"], timeRangeApplies: true };
const model: SemanticModelResponse = { id: "m1", name: "orders", label: "Northstar orders", sourceRelations: [], sourcePartitionInfo: [source], entities: [], dimensions: [{ name: "ordered_at", label: "Order date", expression: "order_date", type: "time", granularity: "day", isPartition: false }], measures: [], createdAt: "", updatedAt: "" };
const metric: Metric = { id: "r", name: "revenue", type: "simple", typeParams: { semanticModelName: "orders", measure: "revenue" }, createdAt: "", updatedAt: "" };
const context = { workspaceId: "w1", connectorId: "bq", connectors: [], metricNames: ["revenue"], metrics: [metric], models: [model], canEditDefinitions: true, onPartitionRepair: vi.fn() };
const error = { code: "PARTITION_FILTER_REQUIRED", detail: "Partition filter required", retryable: false, traceId: "trace", violations: [], partitionFilters: [sourcePartitionFilter(source)] };

describe("partition failure guidance", () => {
  it("counts a shared physical table once without changing model repair facts", () => {
    const filters = [sourcePartitionFilter(source), sourcePartitionFilter({ ...source, semanticModelName: "order_lines", dimensions: ["line_date"], timeRangeApplies: false }), sourcePartitionFilter({ ...source, semanticModelName: "snapshot_orders", dimensions: [] })];
    const original = structuredClone(filters);
    const notice = startRejectionGuidance({ ...error, partitionFilters: filters }, "SEMANTIC", context);
    expect(notice.explanation).toBe("Set a time range to run.");
    expect(notice.actions[0].label).toBe("Set time range");
    expect(filters).toEqual(original);
  });

  it.each([
    { connectorId: "another-bq", relation: source.relation },
    { connectorId: source.connectorId, relation: { ...source.relation, catalog: "another-project" } },
    { connectorId: source.connectorId, relation: { ...source.relation, schema: "another-schema" } },
    { connectorId: source.connectorId, relation: { ...source.relation, name: "another-table" } },
  ])("counts an additional table by its full physical address: %j", (address) => {
    const filters = [sourcePartitionFilter(source), sourcePartitionFilter({ ...source, semanticModelName: "duplicate_model" }), sourcePartitionFilter({ ...source, ...address, semanticModelName: "another_model" }), sourcePartitionFilter({ ...source, ...address, semanticModelName: "another_duplicate_model" })];
    expect(startRejectionGuidance({ ...error, partitionFilters: filters }, "SEMANTIC", context).explanation)
      .toBe("Set a time range to run. 1 other table also needs a partition filter.");
  });

  it("counts multiple distinct tables with plural wording", () => {
    const filters = [sourcePartitionFilter(source), sourcePartitionFilter({ ...source, relation: { ...source.relation, name: "line_items" } }), sourcePartitionFilter({ ...source, relation: { ...source.relation, name: "customers" } })];
    expect(startRejectionGuidance({ ...error, partitionFilters: filters }, "SEMANTIC", context).explanation)
      .toBe("Set a time range to run. 2 other tables also need a partition filter.");
  });

  it("uses canonical rejection facts and opens the existing period editor", () => {
    const notice = startRejectionGuidance(error, "SEMANTIC", context);
    expect(notice.title).toBe("Northstar orders reads a table that needs a filter on order_date.");
    expect(notice.reference).toBe("PARTITION_FILTER_REQUIRED · Reference trace");
    expect(notice.actions[0].label).toBe("Set time range");
    notice.actions[0].onClick?.();
    expect(context.onPartitionRepair).toHaveBeenCalledWith({ kind: "TIME_RANGE" });
  });

  it("offers dimension repair by role and a preselected dimension filter", () => {
    const missing = { ...error, partitionFilters: [sourcePartitionFilter({ ...source, dimensions: [] })] };
    expect(startRejectionGuidance(missing, "SEMANTIC", context).actions).toEqual([{ label: "Open model", href: "/workspaces/w1/semantic-models/m1?edit=true" }]);
    expect(startRejectionGuidance(missing, "SEMANTIC", { ...context, canEditDefinitions: false })).toMatchObject({ actions: [], explanation: "Ask an editor to add it." });
    const filter = startRejectionGuidance({ ...error, partitionFilters: [sourcePartitionFilter({ ...source, timeRangeApplies: false })] }, "SEMANTIC", context);
    filter.actions[0].onClick?.();
    expect(context.onPartitionRepair).toHaveBeenCalledWith({ kind: "FILTER", semanticModelName: "orders", dimensionName: "ordered_at" });
  });

  it("keys runtime recovery by failureCode even if a time range was already provided", () => {
    const run = { kind: "SEMANTIC", runId: "r1", connectorId: "bq", failureCode: "PARTITION_FILTER_REQUIRED", failureDetail: "Safe warehouse detail", query: { schemaVersion: 4, metrics: ["revenue"], groupBys: [], filters: [], metricTimeRange: { start: { kind: "DATE", value: "2026-10-01" }, end: { kind: "DATE", value: "2026-10-08" } }, orderBy: [], rowLimit: 100 } } as unknown as ExploreRun;
    expect(failedRunGuidance(run, context).actions[0].label).toBe("Set time range");
    expect(failedRunGuidance({ ...run, failureCode: "AUTHENTICATION_FAILED" } as ExploreRun, context).actions.some((action) => action.label === "Set time range")).toBe(false);
    expect(failedRunGuidance({ ...run, kind: "SQL" } as unknown as ExploreRun, context)).toMatchObject({ actions: [], explanation: "Add a filter on the partition column in SQL, then run again." });
  });
});
