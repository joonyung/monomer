import { describe, expect, it } from "vitest";
import type { ShowTarget } from "./contract";
import { DRAFT_PATH, DRAFT_STATUS_PATH, draftReads, draftStatusRead, ORDERS_RUN, ORDERS_RUN_NEXT, ordersNextResult, REVENUE_RUN, WORKSPACE_ID, type ToolResult } from "./fixtures";
import type { ResultEntry } from "./presentation";
import { createResourceReader } from "./reader";
import { createDraftSource, READ_CONCURRENCY, runInOrderBounded, type DashboardSource } from "./sources";

function deferred() {
  let resolve: () => void = () => undefined;
  let reject: (error: Error) => void = () => undefined;
  const promise = new Promise<void>((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

describe("bounded reads", () => {
  it("starts reads in order with at most four in flight", async () => {
    const started: number[] = [];
    const gates = Array.from({ length: 6 }, deferred);
    const run = runInOrderBounded([0, 1, 2, 3, 4, 5], READ_CONCURRENCY, async (index) => {
      started.push(index);
      await gates[index].promise;
    });
    await settle();
    expect(started).toEqual([0, 1, 2, 3]);
    gates[2].resolve();
    await settle();
    expect(started).toEqual([0, 1, 2, 3, 4]);
    gates.forEach((gate) => gate.resolve());
    await run;
    expect(started).toEqual([0, 1, 2, 3, 4, 5]);
  });

  it("stops starting reads after a failure and rethrows it once started reads settle", async () => {
    const started: number[] = [];
    const gates = Array.from({ length: 6 }, deferred);
    const run = runInOrderBounded([0, 1, 2, 3, 4, 5], READ_CONCURRENCY, async (index) => {
      started.push(index);
      await gates[index].promise;
    });
    const failure = new Error("contract changed");
    gates[0].reject(failure);
    gates.slice(1).forEach((gate) => gate.resolve());
    await expect(run).rejects.toBe(failure);
    expect(started).toEqual([0, 1, 2, 3]);
  });
});

describe("Draft result retention", () => {
  it("drops results that no ready bundle, shown bundle or target refers to any more", async () => {
    const reads = new Map<string, ToolResult>(Object.entries(draftReads({ formattedTable: false })));
    const reader = createResourceReader(async (request) => structuredClone(reads.get(String(request.arguments.path))!), WORKSPACE_ID);
    const results = new Map<string, ResultEntry>();
    let source: DashboardSource | null = null;
    source = createDraftSource({ kind: "DRAFT", path: DRAFT_PATH, revision: "1" } as Extract<ShowTarget, { kind: "DRAFT" }>, {
      reader,
      results,
      resultAttempts: new Map(),
      onUpdate: () => source?.present(),
      isCancelled: () => false,
    });
    await source.load();
    expect([...results.keys()].sort()).toEqual([REVENUE_RUN, ORDERS_RUN].sort());

    reads.set(DRAFT_STATUS_PATH, draftStatusRead({ revision: "1", ordersRun: ORDERS_RUN_NEXT }));
    reads.set(`${ORDERS_RUN_NEXT}/result`, ordersNextResult);
    expect(await source.poll()).toBe("changed");
    expect([...results.keys()].sort()).toEqual([REVENUE_RUN, ORDERS_RUN_NEXT].sort());
    expect(source.present()!.content.resultsByWidgetId.orders_table.orders.queryRunId).toBe(ORDERS_RUN_NEXT);
  });
});
