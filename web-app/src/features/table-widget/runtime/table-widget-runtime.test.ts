import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { TableFrame } from "../contract/abi";
import type { TableWorkerRequest, TableWorkerResponse } from "./worker-protocol";
import { TableWidgetRuntime } from "./table-widget-runtime";
import { TABLE_BATCH_TIMEOUT_MS, TABLE_PREPARE_TIMEOUT_MS } from "./limits";
import { presentedCellView } from "./output-boundary";

class FakeWorker {
  onmessage: ((event: MessageEvent<TableWorkerResponse>) => void) | null = null;
  onerror: ((event: ErrorEvent) => void) | null = null;
  onmessageerror: (() => void) | null = null;
  requests: TableWorkerRequest[] = [];
  terminate = vi.fn();
  postMessage(request: TableWorkerRequest) { this.requests.push(request); }
  respond(response: TableWorkerResponse) { this.onmessage?.({ data: response } as MessageEvent<TableWorkerResponse>); }
}
const frame: TableFrame = { queries: {}, parameters: {}, variables: {}, locale: "ko-KR", rowQueryName: "main" };
const digits = new Map<string, number>();
const workers: FakeWorker[] = [];
const runtime = () => new TableWidgetRuntime(() => {
  const worker = new FakeWorker(); workers.push(worker); return worker as unknown as Worker;
});
beforeEach(() => { vi.useFakeTimers(); workers.length = 0; });
afterEach(() => { vi.useRealTimers(); });

describe("Table Worker lifetime", () => {
  it("terminates preparation with no automatic retry", async () => {
    const table = runtime();
    const preparing = table.prepare("source", frame, digits);
    const rejection = expect(preparing).rejects.toThrow(/timed out/);
    await vi.advanceTimersByTimeAsync(TABLE_PREPARE_TIMEOUT_MS);
    await rejection;
    expect(workers[0].terminate).toHaveBeenCalledOnce();
    expect(workers).toHaveLength(1);
  });
  it("terminates an infinite batch and rejects other pending batches", async () => {
    const table = runtime();
    const preparing = table.prepare("source", frame, digits);
    workers[0].respond({ type: "prepared", generation: 1, operationId: 1 });
    await preparing;
    const first = expect(table.batch({ rowIndices: [0], fields: ["a"] })).rejects.toThrow(/timed out/);
    const second = expect(table.batch({ rowIndices: [1], fields: ["a"] })).rejects.toThrow(/timed out/);
    await vi.advanceTimersByTimeAsync(TABLE_BATCH_TIMEOUT_MS);
    await Promise.all([first, second]);
    expect(workers[0].terminate).toHaveBeenCalledOnce();
    await expect(table.batch({ rowIndices: [0], fields: ["a"] })).rejects.toThrow(/not prepared/);
  });
  it("rejects old frame operations and ignores stale replies after source replacement", async () => {
    const table = runtime();
    const oldPrepare = table.prepare("old", frame, digits);
    const rejected = expect(oldPrepare).rejects.toThrow(/superseded/);
    const next = table.prepare("new", frame, digits);
    await rejected;
    workers[0].respond({ type: "prepared", generation: 1, operationId: 1 });
    workers[1].respond({ type: "prepared", generation: 2, operationId: 2 });
    await next;
    const result = table.batch({ rowIndices: [], fields: [] });
    workers[1].respond({ type: "batch", generation: 2, operationId: 3, batch: { rows: [], cells: [] } });
    expect(await result).toEqual({ rows: [], cells: [] });
    table.dispose();
    expect(workers.every(worker => worker.terminate.mock.calls.length === 1)).toBe(true);
  });
  it("sends the display digits with preparation and accepts the Host text marker only without text", async () => {
    const table = runtime();
    const preparing = table.prepare("source", frame, new Map([["amount", 3]]));
    expect(workers[0].requests[0]).toMatchObject({ type: "prepare", displayFractionDigits: new Map([["amount", 3]]) });
    workers[0].respond({ type: "prepared", generation: 1, operationId: 1 });
    await preparing;
    const result = table.batch({ rowIndices: [0], fields: ["a", "b", "c", "d"] });
    workers[0].respond({ type: "batch", generation: 1, operationId: 2, batch: { rows: [], cells: [
      { rowIndex: 0, field: "a", view: { hostText: true, background: "#112233" } },
      { rowIndex: 0, field: "b", view: { hostText: true, text: "authored" } as never },
      { rowIndex: 0, field: "c", view: { text: "x".repeat(4097) } },
      { rowIndex: 0, field: "d", view: { text: "x".repeat(4096) } },
    ] } });
    const batch = await result;
    expect(batch.cells[0]).toEqual({ rowIndex: 0, field: "a", view: { hostText: true, background: "#112233" } });
    expect(batch.cells[1]).toEqual({ rowIndex: 0, field: "b", error: "Invalid Table cell text" });
    expect(batch.cells[2]).toEqual({ rowIndex: 0, field: "c", error: "Invalid Table cell text" });
    expect(batch.cells[3].view).toEqual({ text: "x".repeat(4096) });
    expect(presentedCellView(batch.cells[0].view!, "n".repeat(5000))).toEqual({ text: "n".repeat(5000), background: "#112233" });
    expect(presentedCellView(batch.cells[3].view!, "host")).toBe(batch.cells[3].view);
  });
  it("handles bootstrap errors immediately", async () => {
    const table = runtime();
    const preparing = table.prepare("source", frame, digits);
    workers[0].respond({ type: "failed", generation: -1, operationId: 0, message: "bootstrap failed" });
    await expect(preparing).rejects.toThrow("bootstrap failed");
    expect(workers[0].terminate).toHaveBeenCalledOnce();
  });
});
