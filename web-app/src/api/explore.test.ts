import { afterEach, describe, expect, it, vi } from "vitest";
import { exploreAPI } from "@/api/explore";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

const runView = {
  kind: "SQL",
  runId: "run-1",
  workspaceId: "workspace / 2",
  connectorId: "connector-1",
  requestDigest: "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  state: "RUNNING",
  failureCode: null,
  failureDetail: null,
  result: null,
};

describe("exploreAPI", () => {
  afterEach(() => vi.restoreAllMocks());

  it("starts SQL using the SQL variant route and idempotency key", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(runView, 202));
    const request = {
      connectorId: "connector-1",
      sql: "SELECT * FROM analytics.customers LIMIT 20",
    };

    await exploreAPI.startSqlRun({
      workspaceId: "workspace / 2",
      request,
      idempotencyKey: "explore-sql-1",
    });

    const url = new URL(String(fetchMock.mock.calls[0][0]));
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    const headers = new Headers(init.headers);
    expect(url.pathname).toBe("/v2/workspaces/workspace%20%2F%202/explore/sql-runs");
    expect(init.method).toBe("POST");
    expect(headers.get("Idempotency-Key")).toBe("explore-sql-1");
    expect(JSON.parse(String(init.body))).toEqual(request);
  });

  it("starts Semantic with the current query contract", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      ...runView,
      kind: "SEMANTIC",
      query: {
        schemaVersion: 4,
        metrics: ["revenue"],
        groupBys: [{
          kind: "DIMENSION",
          semanticModelName: "orders",
          name: "region",
          granularity: null,
        }],
        metricTimeRange: {
          start: { kind: "DATE", value: "2026-08-01" },
          end: { kind: "DATE", value: "2026-08-20" },
        },
        filters: [{
          kind: "TEXT",
          dimension: { semanticModelName: "orders", name: "region" },
          operator: "EQUALS",
          values: ["APAC"],
        }],
        orderBy: [{
          target: { kind: "METRIC", metricName: "revenue" },
          direction: "DESC",
        }],
        rowLimit: 1000,
      },
    }, 202));
    const request = {
      connectorId: "connector-1",
      query: {
        schemaVersion: 4 as const,
        metrics: ["revenue"],
        groupBys: [{
          kind: "DIMENSION" as const,
          semanticModelName: "orders",
          name: "region",
          granularity: null,
        }],
        metricTimeRange: {
          start: { kind: "DATE" as const, value: "2026-08-01" },
          end: { kind: "DATE" as const, value: "2026-08-20" },
        },
        filters: [{
          kind: "TEXT" as const,
          dimension: { semanticModelName: "orders", name: "region" },
          operator: "EQUALS" as const,
          values: ["APAC"],
        }],
        orderBy: [{
          target: { kind: "METRIC" as const, metricName: "revenue" },
          direction: "DESC" as const,
        }],
        rowLimit: 1000,
      },
    };

    await exploreAPI.startSemanticRun({
      workspaceId: "workspace / 2",
      request,
      idempotencyKey: "explore-semantic-1",
    });

    const url = new URL(String(fetchMock.mock.calls[0][0]));
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(url.pathname).toBe("/v2/workspaces/workspace%20%2F%202/explore/semantic-runs");
    expect(JSON.parse(String(init.body))).toEqual(request);
  });

  it("reads and cancels through the common run boundary", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse(runView))
      .mockResolvedValueOnce(jsonResponse({ ...runView, state: "CANCELLED" }));

    await exploreAPI.readRun({ workspaceId: "workspace / 2", runId: "run / 1" });
    await exploreAPI.cancelRun({ workspaceId: "workspace / 2", runId: "run / 1" });

    const readUrl = new URL(String(fetchMock.mock.calls[0][0]));
    const cancelUrl = new URL(String(fetchMock.mock.calls[1][0]));
    expect(readUrl.pathname).toBe("/v2/workspaces/workspace%20%2F%202/explore/runs/run%20%2F%201");
    expect(cancelUrl.pathname).toBe("/v2/workspaces/workspace%20%2F%202/explore/runs/run%20%2F%201:cancel");
    expect((fetchMock.mock.calls[1][1] as RequestInit).method).toBe("POST");
  });

  it("reads the safe detail of a failed asynchronous run", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      ...runView,
      state: "FAILED",
      failureCode: "EXECUTION_FAILED",
      failureDetail: "The external query exceeded its execution deadline.",
    }));

    const run = await exploreAPI.readRun({ workspaceId: "workspace-1", runId: "run-1" });

    expect(run.failureDetail).toBe("The external query exceeded its execution deadline.");
  });

  it("reads the recorded SQL and restores Semantic sort orders in the discovery group-by shape", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      ...runView,
      kind: "SEMANTIC",
      sql: "SELECT region, SUM(amount) FROM orders GROUP BY 1",
      query: {
        schemaVersion: 4,
        metrics: ["revenue"],
        groupBys: [{ kind: "DIMENSION", semanticModelName: "orders", name: "region" }],
        metricTimeRange: null,
        filters: [],
        orderBy: [
          { direction: "DESC", target: { groupBy: { name: "region", kind: "DIMENSION", semanticModelName: "orders" }, kind: "GROUP_BY" } },
          { target: { kind: "METRIC", metricName: "revenue" }, direction: "ASC" },
          { target: { kind: "UNKNOWN" }, direction: "ASC" },
        ],
        rowLimit: 500,
      },
    }));

    const run = await exploreAPI.readRun({ workspaceId: "workspace-1", runId: "run-1" });

    expect(run.sql).toBe("SELECT region, SUM(amount) FROM orders GROUP BY 1");
    expect(run.resultState).toBeNull();
    expect(run.kind === "SEMANTIC" ? run.query?.orderBy : null).toEqual([
      { target: { kind: "GROUP_BY", groupBy: { kind: "DIMENSION", semanticModelName: "orders", name: "region", granularity: null } }, direction: "DESC" },
      { target: { kind: "METRIC", metricName: "revenue" }, direction: "ASC" },
    ]);
    expect(JSON.stringify(run.kind === "SEMANTIC" ? run.query?.orderBy[0]?.target : null))
      .toBe(JSON.stringify({ kind: "GROUP_BY", groupBy: run.kind === "SEMANTIC" ? run.query?.groupBys[0] : null }));
  });

  it("reads the zone a Semantic run was rendered in, and none for older runs", async () => {
    vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse({ ...runView, kind: "SEMANTIC", state: "SUCCEEDED", reportingTimeZone: "Asia/Seoul" }))
      .mockResolvedValueOnce(jsonResponse({ ...runView, kind: "SEMANTIC", state: "SUCCEEDED", reportingTimeZone: null }));
    const zoned = await exploreAPI.readRun({ workspaceId: "workspace-1", runId: "run-1" });
    expect(zoned.kind === "SEMANTIC" ? zoned.reportingTimeZone : undefined).toBe("Asia/Seoul");
    const older = await exploreAPI.readRun({ workspaceId: "workspace-1", runId: "run-1" });
    expect(older.kind === "SEMANTIC" ? older.reportingTimeZone : undefined).toBeNull();
  });

  it("reads the explicit result state of an exact run read", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({ ...runView, state: "SUCCEEDED", resultState: "EXPIRED", result: null }));
    expect((await exploreAPI.readRun({ workspaceId: "workspace-1", runId: "run-1" })).resultState).toBe("EXPIRED");
  });

  it("preserves the server result availability boundary", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      ...runView,
      state: "SUCCEEDED",
      resultAvailable: false,
      result: {
        columns: [{ ordinal: 1, name: "revenue", logicalType: "DECIMAL", nullable: false }],
        rows: [["12.50"]],
        returnedRowCount: 1,
        stopReason: "ROW_LIMIT",
      },
    }));

    const run = await exploreAPI.readRun({ workspaceId: "workspace-1", runId: "run-1" });

    expect(run.resultAvailable).toBe(false);
    expect(run.result).not.toBeNull();
  });

  it("lists recent runs and downloads the original Arrow artifact", async () => {
    const arrow = new Blob(["arrow"], { type: "application/vnd.apache.arrow.file" });
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse({ runs: [runView, { ...runView, runId: "run-agent", startedBy: { id: "user-sam", displayName: "Sam Lee" }, credentialSurface: "AGENT", agentApp: { clientId: "https://claude.ai/oauth/mcp-oauth-client-metadata", name: "Claude", verifiedHost: "claude.ai" } }, { ...runView, runId: "run-web", startedBy: { id: "user-gone", displayName: null }, credentialSurface: "WEB" }] }))
      .mockResolvedValueOnce(new Response(arrow));

    const runs = await exploreAPI.listRecentRuns({ workspaceId: "workspace / 2" });
    const result = await exploreAPI.downloadResult({ workspaceId: "workspace / 2", runId: "run / 1", format: "arrow", signal: new AbortController().signal });

    expect(runs).toHaveLength(3);
    expect(runs[0].runId).toBe("run-1");
    expect(runs.map((run) => run.credentialSurface)).toEqual([null, "AGENT", "WEB"]);
    expect(runs.map((run) => run.agentApp?.name ?? null)).toEqual([null, "Claude", null]);
    expect(runs[1].agentApp?.verifiedHost).toBe("claude.ai");
    expect(runs.map((run) => run.startedBy)).toEqual([null, { id: "user-sam", displayName: "Sam Lee" }, { id: "user-gone", displayName: null }]);
    expect(result.headers.get("Content-Type")).toBe("application/vnd.apache.arrow.file");
    expect(result.body).not.toBeNull();
    expect(new URL(String(fetchMock.mock.calls[1][0])).search).toBe("?format=arrow");
    expect(new URL(String(fetchMock.mock.calls[0][0])).pathname)
      .toBe("/v2/workspaces/workspace%20%2F%202/explore/runs");
    expect(new URL(String(fetchMock.mock.calls[1][0])).pathname)
      .toBe("/v2/workspaces/workspace%20%2F%202/explore/runs/run%20%2F%201/result");
  });

  it("loads request-local Semantic group-by options", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      groupBys: [
        {
          kind: "DIMENSION",
          semanticModelName: "orders",
          name: "region",
          granularity: null,
        },
        {
          kind: "ENTITY",
          semanticModelName: "orders",
          name: "order_id",
          granularity: null,
        },
      ],
      dimensionFilterTypes: [
        { dimension: { semanticModelName: "orders", name: "region" }, kind: "TEXT" },
        { dimension: { semanticModelName: "orders", name: "amount" }, kind: "NUMBER" },
        { dimension: { semanticModelName: "orders", name: "active" }, kind: "BOOLEAN" },
        { dimension: { semanticModelName: "orders", name: "created_at" }, kind: "TIMESTAMP" },
        { dimension: { name: "missing_model" }, kind: "TEXT" },
        null,
      ],
    }));

    const response = await exploreAPI.listSemanticGroupBys({
      workspaceId: "workspace / 2",
      connectorId: "connector-1",
      metrics: ["revenue"],
    });

    const url = new URL(String(fetchMock.mock.calls[0][0]));
    expect(url.pathname).toBe("/v2/workspaces/workspace%20%2F%202/semantic/query/group-bys");
    expect(JSON.parse(String((fetchMock.mock.calls[0][1] as RequestInit).body))).toEqual({
      connectorId: "connector-1",
      metrics: ["revenue"],
    });
    expect(response.groupBys).toHaveLength(2);
    expect(response.groupBys[0]).toMatchObject({ kind: "DIMENSION", granularity: null });
    expect(response.dimensionFilterTypes).toEqual([
      { dimension: { semanticModelName: "orders", name: "region" }, kind: "TEXT" },
      { dimension: { semanticModelName: "orders", name: "amount" }, kind: "NUMBER" },
      { dimension: { semanticModelName: "orders", name: "active" }, kind: "BOOLEAN" },
    ]);
  });

  it("keeps an absent or empty comparison-type list unresolved", async () => {
    vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse({ groupBys: [], excludedSemanticModels: [], dimensionFilterTypes: [] }))
      .mockResolvedValueOnce(jsonResponse({ groupBys: [], excludedSemanticModels: [] }));
    const request = { workspaceId: "workspace-1", connectorId: "connector-1", metrics: ["revenue"] };
    expect((await exploreAPI.listSemanticGroupBys(request)).dimensionFilterTypes).toEqual([]);
    expect((await exploreAPI.listSemanticGroupBys(request)).dimensionFilterTypes).toEqual([]);
  });

  it("renders the current Semantic query without starting a run", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({
      dialect: "POSTGRESQL",
      sql: "SELECT revenue FROM analytics.orders",
    }));
    const request = {
      connectorId: "connector-1",
      query: {
        schemaVersion: 4 as const,
        metrics: ["revenue"],
        groupBys: [],
        metricTimeRange: null,
        filters: [],
        orderBy: [],
        rowLimit: 1000,
      },
    };

    const rendered = await exploreAPI.renderSemanticSql({
      workspaceId: "workspace / 2",
      request,
    });

    const url = new URL(String(fetchMock.mock.calls[0][0]));
    expect(url.pathname).toBe("/v2/workspaces/workspace%20%2F%202/semantic/query/render-sql");
    expect(JSON.parse(String((fetchMock.mock.calls[0][1] as RequestInit).body))).toEqual(request);
    expect(rendered).toEqual({
      dialect: "POSTGRESQL",
      sql: "SELECT revenue FROM analytics.orders",
    });
  });
});
