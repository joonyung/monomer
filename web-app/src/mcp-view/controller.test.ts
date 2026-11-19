import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createDashboardViewController, type DashboardViewControllerOptions } from "./controller";
import { POLL_FAILURE_LIMIT, POLL_IDLE_LIMIT_MS, POLL_INTERVAL_MS } from "./polling";
import type { SupersessionChannel } from "./supersession";
import {
  DRAFT_PATH,
  DRAFT_STATUS_PATH,
  draftRead,
  draftReads,
  draftStatusRead,
  ORDERS_RUN,
  ORDERS_RUN_NEXT,
  ordersNextResult,
  readProblem,
  REVENUE_RUN,
  resultRead,
  runRead,
  SESSION_ORDERS_RUN,
  SESSION_PATH,
  SESSION_REVENUE_RUN,
  sessionRead,
  sessionShowResult,
  showResult,
  type ToolResult,
  WEB_URL,
} from "./fixtures";
import { RESULT_READ_ATTEMPTS } from "./sources";

const clock = {
  now: () => Date.now(),
  setTimeout: (callback: () => void, delayMs: number) => setTimeout(callback, delayMs),
  clearTimeout: (handle: unknown) => clearTimeout(handle as ReturnType<typeof setTimeout>),
};

function fakeServer(initial: Record<string, ToolResult>) {
  const reads = new Map(Object.entries(initial));
  const gates = new Map<string, Promise<void>>();
  const calls: string[] = [];
  const callServerTool = vi.fn(async (request: { name: string; arguments: Record<string, unknown> }) => {
    if (request.name !== "monomer_read") throw new Error(`Unexpected tool ${request.name}`);
    const path = String(request.arguments.path);
    calls.push(path);
    await gates.get(path);
    return structuredClone(reads.get(path) ?? readProblem(404, "NOT_FOUND", `No fixture for ${path}`));
  });
  return {
    callServerTool,
    calls,
    set: (path: string, result: ToolResult) => reads.set(path, result),
    gate: (path: string) => {
      let open: () => void = () => undefined;
      gates.set(path, new Promise<void>((resolve) => { open = resolve; }));
      return () => { gates.delete(path); open(); };
    },
  };
}

function viewController(server: ReturnType<typeof fakeServer>, overrides: Partial<DashboardViewControllerOptions> = {}) {
  return createDashboardViewController({ callServerTool: server.callServerTool, clock, instanceId: "view-a", isViewActive: () => true, createChannel: null, ...overrides });
}

const settle = async () => {
  for (let round = 0; round < 10; round += 1) await vi.advanceTimersByTimeAsync(0);
};
const shortPath = (path: string) => path.split("/").slice(-2).join("/");

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe("Draft view", () => {
  it("loads the Draft, its status and every ready result, then renders", async () => {
    const server = fakeServer(draftReads({ formattedTable: false }));
    const controller = viewController(server);
    controller.acceptToolResult(showResult({ requestId: "req-1", shownAt: 1000 }));
    await settle();
    expect(server.calls).toEqual([]);
    controller.connected();
    await settle();
    expect(server.calls).toEqual([DRAFT_PATH, DRAFT_STATUS_PATH, `${REVENUE_RUN}/result`, `${ORDERS_RUN}/result`]);
    const snapshot = controller.getSnapshot();
    expect(snapshot.stage).toBe("ready");
    expect(snapshot.live).toBe("live");
    expect([...snapshot.content!.displayedWidgetIds].sort()).toEqual(["orders_table", "revenue_chart"]);
    expect(snapshot.content!.resultsByWidgetId.orders_table.orders.rows).toHaveLength(2);
  });

  it("re-reads the Draft only when the status reports a new revision", async () => {
    const server = fakeServer(draftReads({ formattedTable: false }));
    const controller = viewController(server);
    controller.acceptToolResult(showResult({ requestId: "req-1", shownAt: 1000 }));
    controller.connected();
    await settle();
    server.calls.length = 0;
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    expect(server.calls).toEqual([DRAFT_STATUS_PATH]);

    server.set(DRAFT_PATH, draftRead({ revision: "2", notes: "Edited notes.", formattedTable: false, ordersRun: ORDERS_RUN }));
    server.set(DRAFT_STATUS_PATH, draftStatusRead({ revision: "2", ordersRun: ORDERS_RUN }));
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    expect(server.calls).toEqual([DRAFT_STATUS_PATH, DRAFT_STATUS_PATH, DRAFT_PATH]);
    const content = controller.getSnapshot().content!;
    expect(content.definition.widgets.notes).toEqual({ type: "TEXT", content: "Edited notes." });
    expect(content.variableRevision).toBe(2);
    expect(content.displayedWidgetIds.has("orders_table")).toBe(true);
  });

  it("adopts a changed ready bundle and reads only the new result, keeping the old one meanwhile", async () => {
    const server = fakeServer(draftReads({ formattedTable: false }));
    const controller = viewController(server);
    controller.acceptToolResult(showResult({ requestId: "req-1", shownAt: 1000 }));
    controller.connected();
    await settle();
    server.calls.length = 0;
    server.set(DRAFT_STATUS_PATH, draftStatusRead({ revision: "1", ordersRun: ORDERS_RUN_NEXT }));
    server.set(`${ORDERS_RUN_NEXT}/result`, ordersNextResult);
    const release = server.gate(`${ORDERS_RUN_NEXT}/result`);
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    expect(server.calls.map(shortPath)).toEqual(["draft/status", "01920000-0000-7000-8000-000000000103/result"]);
    const hydrating = controller.getSnapshot().content!;
    expect(hydrating.resultsByWidgetId.orders_table.orders.queryRunId).toBe(ORDERS_RUN);
    expect(hydrating.refreshingWidgetIds.has("orders_table")).toBe(true);

    release();
    await settle();
    const hydrated = controller.getSnapshot().content!;
    expect(hydrated.resultsByWidgetId.orders_table.orders.queryRunId).toBe(ORDERS_RUN_NEXT);
    expect(hydrated.resultsByWidgetId.orders_table.orders.rows).toHaveLength(3);
    expect(hydrated.refreshingWidgetIds.has("orders_table")).toBe(false);
    expect(server.calls.filter((path) => path === `${REVENUE_RUN}/result`)).toEqual([]);
  });

  it("keeps the same content across unchanged polls and pauses after ten quiet minutes", async () => {
    const server = fakeServer(draftReads({ formattedTable: false }));
    const controller = viewController(server);
    controller.acceptToolResult(showResult({ requestId: "req-1", shownAt: 1000 }));
    controller.connected();
    await settle();
    const content = controller.getSnapshot().content;
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 3);
    expect(controller.getSnapshot().content).toBe(content);
    await vi.advanceTimersByTimeAsync(POLL_IDLE_LIMIT_MS);
    expect(controller.getSnapshot().live).toBe("paused");
    const calls = server.calls.length;
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 5);
    expect(server.calls).toHaveLength(calls);
    controller.resume();
    await settle();
    expect(controller.getSnapshot().live).toBe("live");
    expect(server.calls).toHaveLength(calls + 1);
  });

  it("reads a failed target run once for its failure detail", async () => {
    const reads = draftReads({ formattedTable: false });
    reads[DRAFT_STATUS_PATH] = draftStatusRead({ revision: "1", ordersRun: ORDERS_RUN_NEXT, ordersState: "FAILED", readyOrdersRun: ORDERS_RUN });
    reads[ORDERS_RUN_NEXT] = runRead(ORDERS_RUN_NEXT, "orders", "FAILED", "Column orders does not exist.");
    const server = fakeServer(reads);
    const controller = viewController(server);
    controller.acceptToolResult(showResult({ requestId: "req-1", shownAt: 1000 }));
    controller.connected();
    await settle();
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 3);
    expect(server.calls.filter((path) => path === ORDERS_RUN_NEXT)).toHaveLength(1);
    const content = controller.getSnapshot().content!;
    expect(content.failureWidgetIds.has("orders_table")).toBe(true);
    expect(content.displayedWidgetIds.has("orders_table")).toBe(true);
  });

  it("closes one widget when its result cannot be read", async () => {
    const reads = draftReads({ formattedTable: false });
    reads[`${ORDERS_RUN}/result`] = readProblem(413, "RESULT_TOO_LARGE", "Too large.");
    const server = fakeServer(reads);
    const controller = viewController(server);
    controller.acceptToolResult(showResult({ requestId: "req-1", shownAt: 1000 }));
    controller.connected();
    await settle();
    const snapshot = controller.getSnapshot();
    expect(snapshot.stage).toBe("ready");
    expect(snapshot.content!.errorMessagesByWidgetId.orders_table).toBe("This result can't be shown here.");
    expect(snapshot.content!.displayedWidgetIds.has("revenue_chart")).toBe(true);
  });

  it("retries a transiently unavailable result on later polls and keeps the shown bundle meanwhile", async () => {
    const server = fakeServer(draftReads({ formattedTable: false }));
    const controller = viewController(server);
    controller.acceptToolResult(showResult({ requestId: "req-1", shownAt: 1000 }));
    controller.connected();
    await settle();
    server.set(DRAFT_STATUS_PATH, draftStatusRead({ revision: "1", ordersRun: ORDERS_RUN_NEXT }));
    server.set(`${ORDERS_RUN_NEXT}/result`, readProblem(503, "UNAVAILABLE", "Try later."));
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    let content = controller.getSnapshot().content!;
    expect(content.resultsByWidgetId.orders_table.orders.queryRunId).toBe(ORDERS_RUN);
    expect(content.refreshingWidgetIds.has("orders_table")).toBe(true);
    expect(content.failureWidgetIds.has("orders_table")).toBe(false);

    server.set(`${ORDERS_RUN_NEXT}/result`, ordersNextResult);
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    content = controller.getSnapshot().content!;
    expect(content.resultsByWidgetId.orders_table.orders.queryRunId).toBe(ORDERS_RUN_NEXT);
    expect(server.calls.filter((path) => path === `${ORDERS_RUN_NEXT}/result`)).toHaveLength(2);
  });

  it("closes a widget after three transient failures of the same result", async () => {
    const reads = draftReads({ formattedTable: false });
    reads[`${ORDERS_RUN}/result`] = readProblem(429, "RATE_LIMITED", "Slow down.");
    const server = fakeServer(reads);
    const controller = viewController(server);
    controller.acceptToolResult(showResult({ requestId: "req-1", shownAt: 1000 }));
    controller.connected();
    await settle();
    expect(controller.getSnapshot().content!.errorMessagesByWidgetId.orders_table).toBeUndefined();
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 2);
    expect(controller.getSnapshot().content!.errorMessagesByWidgetId.orders_table).toBe("This result can't be shown here.");
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 3);
    expect(server.calls.filter((path) => path === `${ORDERS_RUN}/result`)).toHaveLength(RESULT_READ_ATTEMPTS);
  });

  it("shows a whole-view error when the Draft cannot be read, and retries", async () => {
    const reads = draftReads({ formattedTable: false });
    const server = fakeServer({ ...reads, [DRAFT_PATH]: readProblem(403, "FORBIDDEN", "No access.") });
    const controller = viewController(server);
    controller.acceptToolResult(showResult({ requestId: "req-1", shownAt: 1000 }));
    controller.connected();
    await settle();
    expect(controller.getSnapshot()).toMatchObject({ stage: "error", message: "You don't have access to this dashboard.", content: null });
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 3);
    expect(server.calls).toEqual([DRAFT_PATH]);
    server.set(DRAFT_PATH, reads[DRAFT_PATH]);
    controller.retry();
    await settle();
    expect(controller.getSnapshot().stage).toBe("ready");
  });

  it("stops after three failed polls, keeps the last render, and retries on request", async () => {
    const reads = draftReads({ formattedTable: false });
    const server = fakeServer(reads);
    const controller = viewController(server);
    controller.acceptToolResult(showResult({ requestId: "req-1", shownAt: 1000 }));
    controller.connected();
    await settle();
    server.set(DRAFT_STATUS_PATH, readProblem(503, "UNAVAILABLE", "Try later."));
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * POLL_FAILURE_LIMIT);
    expect(controller.getSnapshot()).toMatchObject({ stage: "ready", live: "failed" });
    expect(controller.getSnapshot().content!.displayedWidgetIds.size).toBe(2);
    const failedCalls = server.calls.length;
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 5);
    expect(server.calls).toHaveLength(failedCalls);
    server.set(DRAFT_STATUS_PATH, reads[DRAFT_STATUS_PATH]);
    controller.retry();
    await settle();
    expect(controller.getSnapshot().live).toBe("live");
  });

  it("stops every read when a read reports another view contract", async () => {
    const server = fakeServer(draftReads({ formattedTable: false }));
    const controller = viewController(server);
    controller.acceptToolResult(showResult({ requestId: "req-1", shownAt: 1000 }));
    controller.connected();
    await settle();
    const status = draftStatusRead({ revision: "1", ordersRun: ORDERS_RUN });
    server.set(DRAFT_STATUS_PATH, { ...status, _meta: { "cc.monomer/viewContract": 2 } });
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    expect(controller.getSnapshot()).toMatchObject({ stage: "out-of-date", live: "stopped" });
    const calls = server.calls.length;
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 10);
    controller.retry();
    await settle();
    expect(server.calls).toHaveLength(calls);
  });

  it("does not read for a current-contract show result with an invalid selection", async () => {
    const server = fakeServer(draftReads({ formattedTable: false }));
    const controller = viewController(server);
    const invalid = showResult({ requestId: "req-1", shownAt: 1000 });
    controller.acceptToolResult({ ...invalid, structuredContent: { ...(invalid.structuredContent as Record<string, unknown>), shownAt: "now" } });
    controller.connected();
    await settle();
    expect(controller.getSnapshot()).toMatchObject({ stage: "show-failed", message: "This dashboard selection couldn't be shown.", webUrl: WEB_URL });
    expect(server.calls).toEqual([]);
  });

  it("does not read for a show result of another view contract", async () => {
    const server = fakeServer(draftReads({ formattedTable: false }));
    const controller = viewController(server);
    controller.acceptToolResult(showResult({ requestId: "req-1", shownAt: 1000, viewContract: 2 }));
    controller.connected();
    await settle();
    expect(controller.getSnapshot().stage).toBe("out-of-date");
    expect(server.calls).toEqual([]);
  });

  it("does not poll while inactive", async () => {
    let active = false;
    const server = fakeServer(draftReads({ formattedTable: false }));
    const controller = viewController(server, { isViewActive: () => active });
    controller.acceptToolResult(showResult({ requestId: "req-1", shownAt: 1000 }));
    controller.connected();
    await settle();
    const loaded = server.calls.length;
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 5);
    expect(server.calls).toHaveLength(loaded);
    active = true;
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    expect(server.calls).toHaveLength(loaded + 1);
  });

  it("stops polling once a newer view of the same target announces", async () => {
    let deliver: ((event: MessageEvent) => void) | null = null;
    const channel: SupersessionChannel = {
      postMessage: vi.fn(),
      close: vi.fn(),
      set onmessage(handler: ((event: MessageEvent) => void) | null) { deliver = handler; },
      get onmessage() { return deliver; },
    };
    const createChannel = vi.fn(() => channel);
    const server = fakeServer(draftReads({ formattedTable: false }));
    const controller = viewController(server, { createChannel });
    controller.acceptToolResult(showResult({ requestId: "req-1", shownAt: 1000 }));
    controller.connected();
    await settle();
    expect(createChannel).toHaveBeenCalledWith(`monomer-dashboard-view:01920000-0000-7000-8000-0000000000aa:${DRAFT_PATH}`);
    expect(channel.postMessage).toHaveBeenCalledWith({ type: "announce", instanceId: "view-a", shownAt: 1000, requestId: "req-1" });
    deliver!(new MessageEvent("message", { data: { type: "announce", instanceId: "view-b", shownAt: 2000, requestId: "req-2" } }));
    expect(controller.getSnapshot().live).toBe("superseded");
    const calls = server.calls.length;
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 5);
    expect(server.calls).toHaveLength(calls);
    expect(controller.getSnapshot().content).not.toBeNull();
    controller.teardown();
    expect(channel.close).toHaveBeenCalled();
  });
});

describe("Session view", () => {
  const sessionResults = {
    [`${SESSION_REVENUE_RUN}/result`]: resultRead(SESSION_REVENUE_RUN, ["dimension.region", "metric.revenue"], [["EMEA", "10"]]),
    [`${SESSION_ORDERS_RUN}/result`]: resultRead(SESSION_ORDERS_RUN, ["dimension.region", "metric.orders"], [["EMEA", "3"]]),
  };

  it("polls only running runs and stops once every run has ended", async () => {
    const server = fakeServer({
      [SESSION_PATH]: sessionRead(),
      [SESSION_REVENUE_RUN]: runRead(SESSION_REVENUE_RUN, "revenue", "SUCCEEDED"),
      [SESSION_ORDERS_RUN]: runRead(SESSION_ORDERS_RUN, "orders", "RUNNING"),
      ...sessionResults,
    });
    const controller = viewController(server);
    controller.acceptToolResult(sessionShowResult({ requestId: "req-1", shownAt: 1000 }));
    controller.connected();
    await settle();
    expect(server.calls.map(shortPath)).toEqual([
      `sessions/${SESSION_PATH.split("/").pop()}`,
      "query-runs/01920000-0000-7000-8000-000000000201",
      "query-runs/01920000-0000-7000-8000-000000000202",
      "01920000-0000-7000-8000-000000000201/result",
    ]);
    expect(controller.getSnapshot()).toMatchObject({ stage: "ready", live: "live", runningQueries: 1 });
    expect(controller.getSnapshot().content!.variables).toEqual({ region: "EMEA" });
    server.calls.length = 0;
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    expect(server.calls).toEqual([SESSION_ORDERS_RUN]);

    server.set(SESSION_ORDERS_RUN, runRead(SESSION_ORDERS_RUN, "orders", "SUCCEEDED"));
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    expect(server.calls).toEqual([SESSION_ORDERS_RUN, SESSION_ORDERS_RUN, `${SESSION_ORDERS_RUN}/result`]);
    expect(controller.getSnapshot()).toMatchObject({ live: "complete", runningQueries: 0 });
    expect(controller.getSnapshot().content!.displayedWidgetIds.has("orders_table")).toBe(true);
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 5);
    expect(server.calls).toHaveLength(3);
  });

  it("keeps polling while a finished run's result waits for a retry", async () => {
    const server = fakeServer({
      [SESSION_PATH]: sessionRead(),
      [SESSION_REVENUE_RUN]: runRead(SESSION_REVENUE_RUN, "revenue", "SUCCEEDED"),
      [SESSION_ORDERS_RUN]: runRead(SESSION_ORDERS_RUN, "orders", "SUCCEEDED"),
      ...sessionResults,
      [`${SESSION_ORDERS_RUN}/result`]: readProblem(502, "UPSTREAM", "Try later."),
    });
    const controller = viewController(server);
    controller.acceptToolResult(sessionShowResult({ requestId: "req-1", shownAt: 1000 }));
    controller.connected();
    await settle();
    expect(controller.getSnapshot().live).toBe("live");
    server.calls.length = 0;
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    expect(server.calls).toEqual([`${SESSION_ORDERS_RUN}/result`]);
    expect(controller.getSnapshot().live).toBe("live");
    server.set(`${SESSION_ORDERS_RUN}/result`, sessionResults[`${SESSION_ORDERS_RUN}/result`]);
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    expect(controller.getSnapshot().live).toBe("complete");
    expect(controller.getSnapshot().content!.displayedWidgetIds.has("orders_table")).toBe(true);
  });

  it("does not poll when every passed run has already ended", async () => {
    const server = fakeServer({
      [SESSION_PATH]: sessionRead(),
      [SESSION_REVENUE_RUN]: runRead(SESSION_REVENUE_RUN, "revenue", "SUCCEEDED"),
      [SESSION_ORDERS_RUN]: runRead(SESSION_ORDERS_RUN, "orders", "FAILED", "Bind failed."),
      ...sessionResults,
    });
    const controller = viewController(server);
    controller.acceptToolResult(sessionShowResult({ requestId: "req-1", shownAt: 1000 }));
    controller.connected();
    await settle();
    expect(controller.getSnapshot().live).toBe("complete");
    expect(controller.getSnapshot().content!.errorMessagesByWidgetId.orders_table).toBe("Bind failed.");
    const calls = server.calls.length;
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 5);
    expect(server.calls).toHaveLength(calls);
  });
});
