import { describe, expect, it, vi } from "vitest";
import { ChartWidgetRuntime } from "./chart-widget-runtime";
import type { ChartWidgetInputs, ChartWorkerRequest, ChartWorkerResponse } from "./worker-protocol";

const inputs: ChartWidgetInputs = {
  moduleSource: "export default defineChart({ render() { return { series: [] }; } });",
  queries: {},
  parameters: {},
  variables: {},
  locale: "en-US",
  viewport: { width: 320, height: 200 },
};

class SilentWorker {
  onmessage: ((event: MessageEvent<ChartWorkerResponse>) => void) | null = null;
  onerror: ((event: ErrorEvent) => void) | null = null;
  terminated = false;

  postMessage(_request: ChartWorkerRequest): void {}

  terminate(): void {
    this.terminated = true;
  }
}

function canvas(): HTMLCanvasElement {
  return {
    transferControlToOffscreen: () => ({}) as OffscreenCanvas,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  } as unknown as HTMLCanvasElement;
}

describe("ChartWidgetRuntime timeout boundary", () => {
  it("notifies host listeners when initialization times out", () => {
    vi.useFakeTimers();
    try {
      const worker = new SilentWorker();
      const runtime = new ChartWidgetRuntime(() => worker as unknown as Worker);
      const responses: ChartWorkerResponse[] = [];
      runtime.subscribe((response) => responses.push(response));

      runtime.mount(canvas(), inputs);
      vi.advanceTimersByTime(10_001);

      expect(worker.terminated).toBe(true);
      expect(runtime.snapshot.status).toBe("failed");
      expect(responses.at(-1)).toMatchObject({
        type: "failed",
        generation: 1,
        phase: "initialize",
        message: "Chart Worker operation timed out",
      });
    } finally {
      vi.useRealTimers();
    }
  });
});


it("keeps a callback deadline after render completion and terminates a stalled Worker", () => {
  vi.useFakeTimers();
  try {
    const worker = new SilentWorker();
    const runtime = new ChartWidgetRuntime(() => worker as unknown as Worker);
    runtime.mount(canvas(), inputs);
    worker.onmessage?.({ data: { type: "rendered", generation: 1, operationId: 0 } } as MessageEvent<ChartWorkerResponse>);
    worker.onmessage?.({ data: { type: "callback_started", generation: 1, operationId: -1 } } as MessageEvent<ChartWorkerResponse>);
    vi.advanceTimersByTime(2_001);
    expect(worker.terminated).toBe(true);
    expect(runtime.snapshot.lastError).toBe("Chart Worker operation timed out");
  } finally { vi.useRealTimers(); }
});
