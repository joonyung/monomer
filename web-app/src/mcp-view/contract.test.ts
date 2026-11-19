import { describe, expect, it } from "vitest";
import { DraftBody, DraftStatusBody, parseShowResult, problemFromContent, QueryResultBody, QueryRunBody, SELECTION_NOT_SHOWN_MESSAGE } from "./contract";
import { draftRead, draftStatusRead, ORDERS_RUN, ordersPivotWidget, ordersTotalQuery, revenueResult, showResult, WEB_URL } from "./fixtures";

const SESSION_PATH = "workspace/dashboards/01920000-0000-7000-8000-0000000000d1/sessions/01920000-0000-7000-8000-0000000000e1";

function structured(result: { structuredContent?: unknown }): Record<string, unknown> {
  return structuredClone(result.structuredContent) as Record<string, unknown>;
}

describe("monomer_show result", () => {
  it("accepts a Draft target of the current view contract", () => {
    const outcome = parseShowResult(showResult({ requestId: "req-1", shownAt: 1000 }));
    expect(outcome.kind).toBe("accepted");
    if (outcome.kind !== "accepted") return;
    expect(outcome.show.target).toEqual({ kind: "DRAFT", path: "workspace/dashboards/01920000-0000-7000-8000-0000000000d1/draft", revision: "1" });
    expect(outcome.show.webUrl).toBe(WEB_URL);
  });

  it("accepts a Session target with its selection", () => {
    const content = structured(showResult({ requestId: "req-1", shownAt: 1000 }));
    content.target = { kind: "SESSION", path: SESSION_PATH, variables: { region: "EMEA" }, queryRuns: { revenue: `${SESSION_PATH}/query-runs/01920000-0000-7000-8000-000000000201` } };
    const outcome = parseShowResult({ isError: false, content: [], structuredContent: content });
    expect(outcome.kind).toBe("accepted");
  });

  it("closes as out of date for another view contract and keeps the link", () => {
    expect(parseShowResult(showResult({ requestId: "req-1", shownAt: 1000, viewContract: 2 }))).toEqual({ kind: "out-of-date", webUrl: WEB_URL });
    expect(parseShowResult({ isError: false, content: [], structuredContent: { webUrl: "http://insecure.test" } })).toEqual({ kind: "out-of-date", webUrl: null });
  });

  it("does not show a current-contract result whose shape or selection is invalid", () => {
    const failed = { kind: "failed", message: SELECTION_NOT_SHOWN_MESSAGE, webUrl: WEB_URL };
    const numberVariable = structured(showResult({ requestId: "req-1", shownAt: 1000 }));
    numberVariable.target = { kind: "SESSION", path: SESSION_PATH, variables: { region: 7 }, queryRuns: {} };
    expect(parseShowResult({ structuredContent: numberVariable })).toEqual(failed);
    const extra = structured(showResult({ requestId: "req-1", shownAt: 1000 }));
    extra.surprise = true;
    expect(parseShowResult({ structuredContent: extra })).toEqual(failed);
    const foreignRun = structured(showResult({ requestId: "req-1", shownAt: 1000 }));
    foreignRun.target = { kind: "SESSION", path: SESSION_PATH, variables: {}, queryRuns: { revenue: `${SESSION_PATH.replace("e1", "e2")}/query-runs/01920000-0000-7000-8000-000000000201` } };
    expect(parseShowResult({ structuredContent: foreignRun })).toEqual(failed);
    const otherDashboard = structured(showResult({ requestId: "req-1", shownAt: 1000 }));
    otherDashboard.dashboardId = "01920000-0000-7000-8000-0000000000d2";
    expect(parseShowResult({ structuredContent: otherDashboard })).toEqual(failed);
  });

  it("links to Monomer over https, or over http only on a loopback host", () => {
    for (const webUrl of ["http://127.0.0.1:3100/workspaces/w", "http://localhost:3100/w", "http://[::1]:3100/w"]) {
      const content = structured(showResult({ requestId: "req-1", shownAt: 1000 }));
      content.webUrl = webUrl;
      expect(parseShowResult({ structuredContent: content })).toMatchObject({ kind: "accepted", show: { webUrl } });
    }
    for (const webUrl of ["http://app.monomer.test/w", "http://127.0.0.1.example.test/w", "javascript:alert(1)", "not a url"]) {
      const content = structured(showResult({ requestId: "req-1", shownAt: 1000 }));
      content.webUrl = webUrl;
      expect(parseShowResult({ structuredContent: content })).toEqual({ kind: "failed", message: SELECTION_NOT_SHOWN_MESSAGE, webUrl: null });
    }
  });

  it("reports a failed show with the Problem detail", () => {
    const outcome = parseShowResult({ isError: true, content: [{ type: "text", text: `Use monomer_describe first.\n${JSON.stringify({ code: "NOT_FOUND", status: 404, detail: "The Dashboard was not found." })}` }] });
    expect(outcome).toEqual({ kind: "failed", message: "The Dashboard was not found.", webUrl: null });
    expect(parseShowResult({ isError: true, content: [] })).toEqual({ kind: "failed", message: "Monomer couldn't show this dashboard.", webUrl: null });
  });
});

describe("Problem text", () => {
  it("reads the Problem JSON after an optional hint line", () => {
    expect(problemFromContent([{ type: "text", text: `Hint line.\n${JSON.stringify({ code: "FORBIDDEN", title: "Forbidden", status: 403, detail: "No access." })}` }]))
      .toEqual({ code: "FORBIDDEN", title: "Forbidden", status: 403, detail: "No access." });
    expect(problemFromContent([{ type: "text", text: "not json" }])).toBeNull();
    expect(problemFromContent("not content")).toBeNull();
  });
});

describe("read bodies", () => {
  it("accept the contract shapes the fixtures use", () => {
    expect(DraftBody.safeParse(structured(draftRead({ revision: "1", notes: "Notes", formattedTable: true, ordersRun: ORDERS_RUN })).body).success).toBe(true);
    expect(DraftStatusBody.safeParse(structured(draftStatusRead({ revision: "1", ordersRun: ORDERS_RUN })).body).success).toBe(true);
    expect(QueryResultBody.safeParse(structured(revenueResult).body).success).toBe(true);
  });

  it("reject unknown fields and unknown widget shapes", () => {
    const body = structured(draftRead({ revision: "1", notes: "Notes", formattedTable: false, ordersRun: ORDERS_RUN })).body as Record<string, unknown>;
    expect(DraftBody.safeParse({ ...body, unexpected: 1 }).success).toBe(false);
    const definition = structuredClone(body.definition) as { widgets: Record<string, unknown> };
    definition.widgets.notes = { type: "IMAGE", url: "https://example.test/x.png" };
    expect(DraftBody.safeParse({ ...body, definition }).success).toBe(false);
    const result = structured(revenueResult).body as { columns: Record<string, unknown>[] };
    expect(QueryResultBody.safeParse({ ...result, columns: result.columns.map(({ nullable: _nullable, ...column }) => column) }).success).toBe(false);
    const status = structured(draftStatusRead({ revision: "1", ordersRun: ORDERS_RUN })).body as Record<string, unknown>;
    expect(DraftStatusBody.safeParse({ ...status, draftRevision: 7 }).success).toBe(false);
  });

  it("accept a pivot TABLE and keep each TABLE shape strict", () => {
    const body = structured(draftRead({ revision: "1", notes: "Notes", formattedTable: false, ordersRun: ORDERS_RUN })).body as Record<string, unknown>;
    const pivot = ordersPivotWidget as { pivot: Record<string, unknown> };
    const accepts = (table: unknown) => {
      const definition = structuredClone(body.definition) as { queries: Record<string, unknown>; widgets: Record<string, unknown> };
      definition.queries.ordersTotal = ordersTotalQuery;
      definition.widgets.orders_table = table;
      return DraftBody.safeParse({ ...body, definition }).success;
    };
    expect(accepts(ordersPivotWidget)).toBe(true);
    expect(accepts({ ...pivot, pivot: { ...pivot.pivot, rows: [{ field: "dimension.region", width: 160 }], columns: [{ field: "metric_time.month", header: null }], valuesAxis: "ROWS", totals: undefined } })).toBe(true);
    expect(accepts({ ...pivot, columns: [{ field: "metric.orders" }] })).toBe(false);
    const { pivot: _pivot, ...neither } = pivot;
    expect(accepts(neither)).toBe(false);
    expect(accepts({ ...pivot, pivot: { ...pivot.pivot, rowFields: [] } })).toBe(false);
    expect(accepts({ ...pivot, pivot: { ...pivot.pivot, valuesAxis: "DIAGONAL" } })).toBe(false);
    expect(accepts({ ...pivot, pivot: { ...pivot.pivot, columns: [] } })).toBe(false);
    expect(accepts({ ...pivot, pivot: { ...pivot.pivot, values: [] } })).toBe(false);
    expect(accepts({ ...pivot, pivot: { ...pivot.pivot, totals: [] } })).toBe(false);
    expect(accepts({ ...pivot, pivot: { ...pivot.pivot, totals: ["ordersTotal", "ordersTotal"] } })).toBe(false);
    expect(accepts({ ...pivot, pivot: { ...pivot.pivot, columns: [{ field: "dimension.region", width: 120 }] } })).toBe(false);
  });

  it("requires failure codes only on FAILED runs", () => {
    const run = { queryName: "orders", queryType: "SQL", connectorId: "01920000-0000-7000-8000-0000000000c1", outputColumnNames: ["metric.orders"], createdAt: "2026-10-10T09:00:00Z", updatedAt: "2026-10-10T09:00:00Z" };
    expect(QueryRunBody.safeParse({ ...run, state: "RUNNING" }).success).toBe(true);
    expect(QueryRunBody.safeParse({ ...run, state: "FAILED", failureCode: "QUERY_FAILED", failureDetail: "Syntax error." }).success).toBe(true);
    expect(QueryRunBody.safeParse({ ...run, state: "FAILED" }).success).toBe(false);
    expect(QueryRunBody.safeParse({ ...run, state: "SUCCEEDED", failureCode: "QUERY_FAILED" }).success).toBe(false);
  });
});
