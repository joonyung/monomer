import { afterEach, describe, expect, it, vi } from "vitest";
import { dashboardAPI } from "@/api/dashboard";

const workspaceId = "00000000-0000-7000-8000-000000000001";
const dashboardId = "00000000-0000-7000-8000-000000000002";
const sessionId = "00000000-0000-7000-8000-000000000003";
const runId = "00000000-0000-7000-8000-000000000004";

function jsonResponse(status: number, value: unknown): Response {
  return new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json" } });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("dashboard API", () => {
  it("preserves Viewer denial as 403 instead of a missing Workspace", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      type: "about:blank", title: "Forbidden", status: 403, detail: "Explorer role is required.",
      code: "FORBIDDEN", instance: "/v2/workspaces/fixture", requestId: "request-1", traceId: "trace-1", retryable: false,
    }), { status: 403, headers: { "Content-Type": "application/problem+json" } })));
    await expect(dashboardAPI.list(workspaceId)).rejects.toMatchObject({ status: 403, message: "Explorer role is required.", problem: { retryable: false } });
  });

  it("sends a partial multi-variable map atomically with its captured Draft revision", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { authoringRevision: 8, variables: { country: "CA", city: null, untouched: "kept" } }));
    vi.stubGlobal("fetch", fetchMock);
    await dashboardAPI.setDraftVariables({ workspaceId, dashboardId, expectedRevision: 7, variables: { country: "CA", city: null }, idempotencyKey: "variables-7" });
    expect(String(fetchMock.mock.calls[0][0])).toContain(`/dashboards/${dashboardId}/draft/variables:set`);
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ expectedRevision: 7, variables: { country: "CA", city: null } });
    expect(new Headers(fetchMock.mock.calls[0][1].headers).get("Idempotency-Key")).toBe("variables-7");
  });

  it("lists and opens current dashboards through generated Control operations", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(200, { items: [{ dashboardId, workspaceId, title: "Orders", description: null, updatedAt: "2026-08-22T00:00:00Z" }] }))
      .mockResolvedValueOnce(jsonResponse(202, { dashboardId, workspaceId, dashboardSessionId: sessionId, definition: {}, parameterBindings: {}, initialVariables: {}, runningQueryRuns: [] }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(dashboardAPI.list(workspaceId)).resolves.toHaveLength(1);
    await expect(dashboardAPI.openSession({ workspaceId, dashboardId, parameterBindings: { region: "North" }, idempotencyKey: "dashboard-open-1" })).resolves.toMatchObject({ dashboardSessionId: sessionId });
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain(`/v2/workspaces/${workspaceId}/dashboards`);
    expect(new Headers(fetchMock.mock.calls[1]?.[1]?.headers).get("Idempotency-Key")).toBe("dashboard-open-1");
    expect(JSON.parse(fetchMock.mock.calls[1]?.[1]?.body as string)).toEqual({ parameterBindings: { region: "North" } });
  });

  it("sends complete screen variables with resolve and explicit refresh intent", async () => {
    const response = { variables: { country: "KR" }, queryRuns: { orders: runId } };
    const fetchMock = vi.fn().mockImplementation(() => jsonResponse(200, response));
    vi.stubGlobal("fetch", fetchMock);

    await dashboardAPI.resolveSession({ workspaceId, sessionId, variables: { showAverage: false, country: "KR", city: null }, idempotencyKey: "dashboard-variable-1" });
    await dashboardAPI.refreshSession({ workspaceId, sessionId, variables: { country: "KR" }, queryNames: ["orders"], idempotencyKey: "dashboard-refresh-1" });

    expect(JSON.parse(fetchMock.mock.calls[0]?.[1]?.body as string)).toEqual({ variables: { showAverage: false, country: "KR", city: null } });
    expect(JSON.parse(fetchMock.mock.calls[1]?.[1]?.body as string)).toEqual({ variables: { country: "KR" }, queryNames: ["orders"] });
    expect(new Headers(fetchMock.mock.calls[1]?.[1]?.headers).get("Idempotency-Key")).toBe("dashboard-refresh-1");
  });

  it("lists Dashboard sessions and their QueryRun history with cursor pagination", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(200, { items: [{ dashboardId, dashboardSessionId: sessionId, createdAt: "2026-08-25T00:00:00Z", updatedAt: "2026-08-25T00:00:01Z", parameterBindings: { region: "North" }, runningQueryRuns: [] }], nextCursor: "session-next" }))
      .mockResolvedValueOnce(jsonResponse(200, { items: [{ connectorId: "00000000-0000-7000-8000-000000000005", createdAt: "2026-08-25T00:00:00Z", failureCode: null, failureMessage: null, outputColumnNames: ["dimension.orders.status"], queryName: "orders", queryRunId: runId, resultAvailable: true, state: "SUCCEEDED", updatedAt: "2026-08-25T00:00:01Z" }], nextCursor: null }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(dashboardAPI.listSessions(workspaceId, dashboardId, { pageSize: 20 })).resolves.toMatchObject({ nextCursor: "session-next" });
    await expect(dashboardAPI.listSessionQueryRuns(workspaceId, sessionId, { cursor: "runs-next", pageSize: 50 })).resolves.toMatchObject({ nextCursor: null });

    expect(new URL(String(fetchMock.mock.calls[0]?.[0])).search).toBe("?pageSize=20");
    expect(new URL(String(fetchMock.mock.calls[1]?.[0])).search).toBe("?cursor=runs-next&pageSize=50");
  });

  it("reads an immutable ordered result without changing its cells", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { queryRunId: runId, columns: [{ name: "metric.order_count", logicalType: "NUMBER", nullable: false }], rows: [["12345678901234567890.12345678901234567"]] }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(dashboardAPI.getResult({ workspaceId, sessionId, runId })).resolves.toEqual(expect.objectContaining({ rows: [["12345678901234567890.12345678901234567"]] }));
  });

  it("starts, edits a Parameter, refreshes, applies, and cancels the shared Draft through generated operations", async () => {
    const draft = {
      workspaceId,
      dashboardId,
      definition: {},
      parameterBindings: {},
      variables: {},
      authoringRevision: 4,
      targetQueryRuns: {},
      readyResults: {},
      recentQueryRuns: [],
      createdAt: "2026-08-24T00:00:00Z",
      updatedAt: "2026-08-24T00:00:00Z",
    };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(202, draft))
      .mockResolvedValueOnce(jsonResponse(200, draft))
      .mockResolvedValueOnce(jsonResponse(200, draft))
      .mockResolvedValueOnce(jsonResponse(200, { workspaceId, dashboardId, definition: {}, createdAt: draft.createdAt, updatedAt: draft.updatedAt }))
      .mockResolvedValueOnce(jsonResponse(200, draft));
    vi.stubGlobal("fetch", fetchMock);

    await dashboardAPI.startDraft({ workspaceId, dashboardId, parameterBindings: { region: "North" }, idempotencyKey: "draft-start-1" });
    await dashboardAPI.setDraftParameter({ workspaceId, dashboardId, name: "region", expectedRevision: 4, value: "South", idempotencyKey: "draft-parameter-1" });
    await dashboardAPI.refreshDraft({ workspaceId, dashboardId, queryNames: ["orders"], idempotencyKey: "draft-refresh-1" });
    await dashboardAPI.applyDraft({ workspaceId, dashboardId, expectedRevision: 4, idempotencyKey: "draft-apply-1" });
    await dashboardAPI.cancelDraftRun({ workspaceId, dashboardId, runId, idempotencyKey: "draft-cancel-1" });

    expect(String(fetchMock.mock.calls[0]?.[0])).toContain(`/dashboards/${dashboardId}/draft`);
    expect(JSON.parse(fetchMock.mock.calls[0]?.[1]?.body as string)).toEqual({ parameterBindings: { region: "North" } });
    expect(new URL(String(fetchMock.mock.calls[1]?.[0])).pathname).toContain(`/draft/parameters/region:set`);
    expect(JSON.parse(fetchMock.mock.calls[1]?.[1]?.body as string)).toEqual({ expectedRevision: 4, value: "South" });
    expect(new Headers(fetchMock.mock.calls[1]?.[1]?.headers).get("Idempotency-Key")).toBe("draft-parameter-1");
    expect(JSON.parse(fetchMock.mock.calls[2]?.[1]?.body as string)).toEqual({ queryNames: ["orders"] });
    expect(JSON.parse(fetchMock.mock.calls[3]?.[1]?.body as string)).toEqual({ expectedRevision: 4 });
    expect(new Headers(fetchMock.mock.calls[4]?.[1]?.headers).get("Idempotency-Key")).toBe("draft-cancel-1");
  });

  it("discards the shared Draft with the exact revision and returns the reset Draft", async () => {
    const draft = {
      workspaceId, dashboardId, definition: {}, parameterBindings: {}, variables: {}, authoringRevision: 5,
      targetQueryRuns: {}, readyResults: {}, recentQueryRuns: [],
      createdAt: "2026-08-24T00:00:00Z", updatedAt: "2026-08-24T00:00:00Z",
      lastEditedBy: { id: "019e0000-0000-7000-8000-0000000000a4", displayName: "Sam Kim" },
      lastEditedAt: "2026-08-24T00:00:00Z", lastEditSurface: "WEB",
    };
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(200, draft));
    vi.stubGlobal("fetch", fetchMock);

    const discarded = await dashboardAPI.discardDraft({ workspaceId, dashboardId, expectedRevision: 4, idempotencyKey: "draft-discard-1" });

    expect(new URL(String(fetchMock.mock.calls[0]?.[0])).pathname).toBe(`/v2/workspaces/${workspaceId}/dashboards/${dashboardId}/draft:discard`);
    expect(JSON.parse(fetchMock.mock.calls[0]?.[1]?.body as string)).toEqual({ expectedRevision: 4 });
    expect(new Headers(fetchMock.mock.calls[0]?.[1]?.headers).get("Idempotency-Key")).toBe("draft-discard-1");
    expect(discarded.lastEditedBy?.displayName).toBe("Sam Kim");
  });

  it("reads Draft result artifacts from the dashboard-scoped result route", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { queryRunId: runId, columns: [], rows: [] }));
    vi.stubGlobal("fetch", fetchMock);

    await dashboardAPI.getDraftResult({ workspaceId, dashboardId, runId });

    expect(new URL(String(fetchMock.mock.calls[0]?.[0])).pathname)
      .toBe(`/v2/workspaces/${workspaceId}/dashboards/${dashboardId}/draft/query-runs/${runId}/result`);
  });
});

it("deletes without a body or receipt, reads bounded History and restores the original id", async () => {
  const changeId = "00000000-0000-7000-8000-000000000005";
  const history = { definitionId: dashboardId, name: "Revenue", deleted: true, changes: [], nextCursor: "older" };
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(new Response(null, { status: 204 }))
    .mockResolvedValueOnce(jsonResponse(200, history))
    .mockResolvedValueOnce(jsonResponse(200, { changeId, action: "DELETED", restorable: true }))
    .mockResolvedValueOnce(jsonResponse(200, { workspaceId, dashboardId, definition: {} }));
  vi.stubGlobal("fetch", fetchMock);
  await expect(dashboardAPI.delete(workspaceId, dashboardId)).resolves.toBeUndefined();
  await expect(dashboardAPI.history(workspaceId, dashboardId, "bound-cursor")).resolves.toEqual(history);
  await expect(dashboardAPI.historyVersion(workspaceId, dashboardId, changeId)).resolves.toMatchObject({ changeId });
  await expect(dashboardAPI.restore(workspaceId, dashboardId, changeId, "restore-intent")).resolves.toMatchObject({ dashboardId });
  expect(fetchMock.mock.calls[0][1].method).toBe("DELETE");
  expect(fetchMock.mock.calls[0][1].body).toBeUndefined();
  expect(new Headers(fetchMock.mock.calls[0][1].headers).has("Idempotency-Key")).toBe(false);
  expect(new URL(String(fetchMock.mock.calls[1][0])).searchParams.get("limit")).toBe("20");
  expect(new URL(String(fetchMock.mock.calls[1][0])).searchParams.get("cursor")).toBe("bound-cursor");
  expect(String(fetchMock.mock.calls[2][0])).toContain(`/dashboards/${dashboardId}/history/${changeId}`);
  expect(new Headers(fetchMock.mock.calls[3][1].headers).get("Idempotency-Key")).toBe("restore-intent");
  expect(fetchMock.mock.calls[3][1].method).toBe("POST");
});

it("preserves repeat delete 404 and an unavailable restore 409 for their distinct UI flows", async () => {
  const fetchMock = vi.fn().mockImplementationOnce(() => new Response(JSON.stringify({ detail: "Dashboard was not found.", status: 404, type: "about:blank", title: "Not Found", instance: "/v2/workspaces/fixture", code: "NOT_FOUND", requestId: "test", traceId: "test", retryable: false }), { status: 404, headers: { "Content-Type": "application/problem+json" } }))
    .mockImplementationOnce(() => new Response(JSON.stringify({ detail: "This version cannot be restored.", status: 409, type: "about:blank", title: "Conflict", instance: "/v2/workspaces/fixture", code: "CONFLICT", requestId: "test", traceId: "test", retryable: false }), { status: 409, headers: { "Content-Type": "application/problem+json" } }));
  vi.stubGlobal("fetch", fetchMock);
  await expect(dashboardAPI.delete(workspaceId, dashboardId)).rejects.toMatchObject({ status: 404, message: "Dashboard was not found." });
  await expect(dashboardAPI.restore(workspaceId, dashboardId, runId, "retry-intent")).rejects.toMatchObject({ status: 409, message: "This version cannot be restored." });
});
