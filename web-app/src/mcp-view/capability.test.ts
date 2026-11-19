import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { probeWorkerCapability, PROBE_WORKER_SOURCE, widgetRuntimePlan } from "./capability";

class FakeProbeWorker {
  onmessage: ((event: MessageEvent) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  terminated = false;
  terminate() {
    this.terminated = true;
  }
  reply(data: unknown) {
    this.onmessage?.(new MessageEvent("message", { data }));
  }
}

function probe(worker: FakeProbeWorker | (() => never), pageTransfersCanvas = true) {
  return probeWorkerCapability({
    createProbeWorker: () => (typeof worker === "function" ? worker() : worker) as unknown as Worker,
    pageTransfersCanvas,
    timeoutMs: 3_000,
  });
}

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe("runtime plan", () => {
  it("runs charts only with eval and OffscreenCanvas, tables with eval alone", () => {
    expect(widgetRuntimePlan({ workerEval: true, offscreenCanvas: true })).toEqual({ charts: "run", tables: "run" });
    expect(widgetRuntimePlan({ workerEval: true, offscreenCanvas: false })).toEqual({ charts: "placeholder", tables: "run" });
    expect(widgetRuntimePlan({ workerEval: false, offscreenCanvas: false })).toEqual({ charts: "placeholder", tables: "fallback" });
  });
});

describe("capability probe", () => {
  it("checks eval and OffscreenCanvas inside the Worker", () => {
    expect(PROBE_WORKER_SOURCE).toContain('(0, eval)("1+1") === 2');
    expect(PROBE_WORKER_SOURCE).toContain('typeof OffscreenCanvas === "function"');
  });

  it("passes when the Worker evaluates and draws offscreen and the page can transfer a canvas", async () => {
    const worker = new FakeProbeWorker();
    const result = probe(worker);
    worker.reply({ evaluates: true, offscreenCanvas: true });
    await expect(result).resolves.toEqual({ workerEval: true, offscreenCanvas: true });
    expect(worker.terminated).toBe(true);
  });

  it("fails charts when the page cannot transfer a canvas", async () => {
    const worker = new FakeProbeWorker();
    const result = probe(worker, false);
    worker.reply({ evaluates: true, offscreenCanvas: true });
    await expect(result).resolves.toEqual({ workerEval: true, offscreenCanvas: false });
  });

  it("fails when eval is blocked inside the Worker", async () => {
    const worker = new FakeProbeWorker();
    const result = probe(worker);
    worker.reply({ evaluates: false, offscreenCanvas: true });
    await expect(result).resolves.toEqual({ workerEval: false, offscreenCanvas: false });
  });

  it("fails when the Worker cannot start, errors, or stays silent", async () => {
    await expect(probe(() => { throw new Error("blocked by CSP"); })).resolves.toEqual({ workerEval: false, offscreenCanvas: false });
    const erroring = new FakeProbeWorker();
    const errored = probe(erroring);
    erroring.onerror?.(new Event("error"));
    await expect(errored).resolves.toEqual({ workerEval: false, offscreenCanvas: false });
    const silent = new FakeProbeWorker();
    const timedOut = probe(silent);
    await vi.advanceTimersByTimeAsync(3_000);
    await expect(timedOut).resolves.toEqual({ workerEval: false, offscreenCanvas: false });
    expect(silent.terminated).toBe(true);
  });
});
