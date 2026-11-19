import { describe, expect, it } from "vitest";
import { toExploreRunPresentation, withoutOwnStarter } from "@/pages/explore/ExploreRunsSurface";
import { resultTableColumns } from "@/pages/explore/result-columns";
import type { ExploreRun } from "@/api/explore";
import type { SemanticModelResponse } from "@/api/semantic-model";
import type { Connector } from "@/lib/types/connector";
import type { Metric } from "@/lib/types/metric";

const baseRun = {
  runId: "run-1",
  workspaceId: "workspace-1",
  connectorId: "connector-1",
  requestDigest: "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  failureCode: null,
  failureDetail: null,
  createdAt: "2026-08-26T00:00:00Z",
  updatedAt: "2026-08-26T00:01:00Z",
  resultMetadata: null,
  result: null,
  resultState: null,
  startedBy: { id: "user-1", displayName: "Sam Lee" },
  credentialSurface: null, agentApp: null,
} as const;

const connector: Connector = {
  id: "connector-1", workspaceId: "workspace-1", name: "Production Postgres", type: "POSTGRESQL",
  properties: { host: "fixture.internal", port: 5432, database: "warehouse" }, createdAtEpochMs: 0, updatedAtEpochMs: 0,
};

const ordersModel = {
  id: "model-1", name: "orders", label: "Orders", sourceRelations: [], entities: [{ name: "order_id", type: "primary", expression: "id" }],
  dimensions: [
    { isPartition: false, name: "ordered_at", label: "Ordered at", type: "time", granularity: "day", expression: "ordered_at" },
    { isPartition: false, name: "region", label: "Region", type: "categorical", expression: "region" },
  ],
  measures: [], createdAt: "2026-08-26T00:00:00Z", updatedAt: "2026-08-26T00:00:00Z",
} as unknown as SemanticModelResponse;
const revenue: Metric = { id: "metric-1", name: "revenue", label: "Revenue", type: "simple", typeParams: { semanticModelName: "orders", measure: "revenue" }, createdAt: "", updatedAt: "" };
const invoices: Metric = { id: "metric-2", name: "invoices", label: "Invoices", type: "simple", typeParams: { semanticModelName: "orders", measure: "invoices" }, createdAt: "", updatedAt: "" };

describe("toExploreRunPresentation", () => {
  it("shows the server-owned safe detail for an asynchronous failure", () => {
    const run: ExploreRun = { ...baseRun, kind: "SQL", sql: "SELECT 1", state: "FAILED", failureCode: "EXECUTION_FAILED", failureDetail: "The external query exceeded its execution deadline." };

    expect(toExploreRunPresentation(run, [], [], []).failureMessage).toBe("The external query exceeded its execution deadline.");
    expect(toExploreRunPresentation(run, [], [], []).credentialSurface).toBeNull();
    expect(toExploreRunPresentation({ ...run, credentialSurface: "AGENT" }, [], [], []).credentialSurface).toBe("AGENT");
  });

  it("carries the person and the agent app who started the run", () => {
    const codex = { clientId: "https://chatgpt.com/codex/client.json", name: "Codex", verifiedHost: "chatgpt.com" };
    const run: ExploreRun = { ...baseRun, kind: "SQL", sql: "SELECT 1", state: "SUCCEEDED", credentialSurface: "AGENT", agentApp: codex };
    const presentation = toExploreRunPresentation(run, [], [], []);
    expect(presentation.startedBy).toEqual({ id: "user-1", displayName: "Sam Lee" });
    expect(presentation.agentApp).toEqual(codex);
  });

  it("falls back to a safe code-based explanation for older responses", () => {
    const run: ExploreRun = { ...baseRun, kind: "SQL", sql: "SELECT 1", state: "FAILED", failureCode: "EXECUTION_ORPHANED" };
    expect(toExploreRunPresentation(run, [], [], []).failureMessage).toContain("stopped reporting progress");
  });

  it("names SQL runs by their first table and a short SQL prefix", () => {
    const run: ExploreRun = { ...baseRun, kind: "SQL", sql: "SELECT status, count(*)\nFROM \"billing\".\"invoices\" GROUP BY 1", state: "SUCCEEDED" };
    const presentation = toExploreRunPresentation(run, [connector], [], []);
    expect(presentation.title).toBe("invoices · SELECT status, count(*)…");
    expect(presentation.subtitle).toBe("SQL · Production Postgres");
  });

  it("shows a deleted connector by name only once the connector list has loaded", () => {
    const run: ExploreRun = { ...baseRun, kind: "SQL", sql: "SELECT 1", state: "SUCCEEDED" };
    expect(toExploreRunPresentation(run, [], [], []).subtitle).toBe("SQL · Deleted connector");
    expect(toExploreRunPresentation(run, null, [], []).subtitle).toBe("SQL · Connector");
  });

  it("names Semantic runs by metrics and grouping and tells them apart by filter in the subtitle", () => {
    const run: ExploreRun = {
      ...baseRun,
      kind: "SEMANTIC",
      reportingTimeZone: "UTC",
      sql: "SELECT 1",
      state: "SUCCEEDED",
      query: {
        schemaVersion: 4, metrics: ["revenue", "invoices"], metricTimeRange: null, orderBy: [], rowLimit: 1000,
        groupBys: [{ kind: "METRIC_TIME", granularity: "MONTH" }, { kind: "DIMENSION", semanticModelName: "orders", name: "region", granularity: null }],
        filters: [{ kind: "TEXT", dimension: { semanticModelName: "orders", name: "region" }, operator: "IN", values: ["EU", "UK"] }],
      },
    };
    const presentation = toExploreRunPresentation(run, [connector], [revenue, invoices], [ordersModel]);
    expect(presentation.title).toBe("Revenue, Invoices by Month, Region");
    expect(presentation.subtitle).toBe("Region is one of EU, UK · Production Postgres");
  });

  it("reports the full row count from result metadata in the rail summary", () => {
    const run: ExploreRun = {
      ...baseRun, kind: "SQL", sql: "SELECT 1", state: "SUCCEEDED",
      resultMetadata: { byteSize: "128", mediaType: "application/vnd.apache.arrow.file", rowCount: "12480", columnCount: 1, expiresAt: "2026-08-30T00:00:00Z" },
    };
    expect(toExploreRunPresentation(run, [], [], []).result?.totalRowCount).toBe(12480);
  });
});

describe("resultTableColumns", () => {
  const query = {
    schemaVersion: 4 as const, metrics: ["revenue"], metricTimeRange: null, filters: [], orderBy: [], rowLimit: 100,
    groupBys: [{ kind: "METRIC_TIME" as const, granularity: "DAY" as const }, { kind: "DIMENSION" as const, semanticModelName: "orders", name: "ordered_at", granularity: "DAY" as const }],
  };
  const result = (columns: Array<[string, string]>) => ({
    columns: columns.map(([name, logicalType], index) => ({ ordinal: index + 1, name, logicalType, nullable: true })),
    rows: [],
    returnedRowCount: 0,
    stopReason: "END_OF_RESULT",
  });

  it("labels Semantic columns by result key or projection position and right-aligns numbers", () => {
    const keyed = resultTableColumns(result([["metric_time.day", "DATE"], ["dimension.orders.ordered_at.day", "DATE"], ["metric.revenue", "DECIMAL"]]), query, [revenue], [ordersModel]);
    expect(keyed.map((column) => column.label)).toEqual(["Metric time (day)", "Ordered at (day)", "Revenue"]);
    expect(keyed.map((column) => column.numeric)).toEqual([false, false, true]);
    expect(keyed[2]?.description).toBe("metric.revenue · DECIMAL");
    const aliased = resultTableColumns(result([["metric_time__day", "DATE"], ["order_id__ordered_at__day", "DATE"], ["revenue", "INTEGER"]]), query, [revenue], [ordersModel]);
    expect(aliased.map((column) => column.label)).toEqual(["Metric time (day)", "Ordered at (day)", "Revenue"]);
  });

  it("marks time group-by columns with their grain so cells read at that grain", () => {
    const keyed = resultTableColumns(result([["metric_time.day", "DATE"], ["dimension.orders.ordered_at.day", "DATE"], ["metric.revenue", "DECIMAL"]]), query, [revenue], [ordersModel]);
    expect(keyed.map((column) => column.timeGranularity)).toEqual(["DAY", "DAY", null]);
    const aliased = resultTableColumns(result([["metric_time__day", "DATE"], ["order_id__ordered_at__day", "DATE"], ["revenue", "INTEGER"]]), query, [revenue], [ordersModel]);
    expect(aliased.map((column) => column.timeGranularity)).toEqual(["DAY", "DAY", null]);
    expect(resultTableColumns(result([["day", "DATE"]]), null, [], []).map((column) => column.timeGranularity)).toEqual([null]);
  });

  it("keeps raw names when the projection cannot be matched and for SQL runs", () => {
    expect(resultTableColumns(result([["region", "TEXT"], ["revenue", "DECIMAL"]]), query, [revenue], [ordersModel]).map((column) => column.label)).toEqual(["region", "revenue"]);
    const sql = resultTableColumns(result([["local_time", "DATETIME"]]), null, [revenue], [ordersModel]);
    expect(sql[0]?.label).toBe("local_time");
    expect(sql[0]?.description).toBe("DATETIME");
  });
});

describe("Explore rail starter", () => {
  it("leaves out the viewer on their own runs and keeps an agent that acted for them", () => {
    const own = toExploreRunPresentation({ ...baseRun, kind: "SQL", state: "SUCCEEDED", sql: "SELECT 1", credentialSurface: "WEB" } as unknown as ExploreRun, [connector], [], []);
    expect(withoutOwnStarter(own, "user-1").startedBy).toBeNull();
    expect(withoutOwnStarter({ ...own, credentialSurface: "AGENT" }, "user-1").startedBy).toEqual({ id: "user-1", displayName: "Sam Lee" });
    expect(withoutOwnStarter(own, "user-2").startedBy).toEqual({ id: "user-1", displayName: "Sam Lee" });
    expect(withoutOwnStarter(own, null).startedBy).toEqual({ id: "user-1", displayName: "Sam Lee" });
  });
});
