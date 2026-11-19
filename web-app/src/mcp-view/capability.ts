export const CAPABILITY_PROBE_TIMEOUT_MS = 3_000;

export interface WorkerCapability {
  readonly workerEval: boolean;
  readonly offscreenCanvas: boolean;
}

export interface WidgetRuntimePlan {
  readonly charts: "run" | "placeholder";
  readonly tables: "run" | "fallback";
}

export const PROBE_WORKER_SOURCE = [
  "URL.revokeObjectURL(import.meta.url);",
  "let evaluates = false;",
  "try { evaluates = (0, eval)(\"1+1\") === 2; } catch {}",
  "self.postMessage({ evaluates, offscreenCanvas: typeof OffscreenCanvas === \"function\" });",
].join("\n");

export interface ProbeEnvironment {
  createProbeWorker(): Worker;
  pageTransfersCanvas: boolean;
  timeoutMs: number;
}

const UNAVAILABLE: WorkerCapability = { workerEval: false, offscreenCanvas: false };

// Charts need a Worker that can evaluate source and draw offscreen; tables need only the former.
export function widgetRuntimePlan(capability: WorkerCapability): WidgetRuntimePlan {
  return {
    charts: capability.workerEval && capability.offscreenCanvas ? "run" : "placeholder",
    tables: capability.workerEval ? "run" : "fallback",
  };
}

export function probeWorkerCapability(environment: ProbeEnvironment): Promise<WorkerCapability> {
  return new Promise((resolve) => {
    let worker: Worker;
    try {
      worker = environment.createProbeWorker();
    } catch {
      resolve(UNAVAILABLE);
      return;
    }
    let settled = false;
    const settle = (capability: WorkerCapability) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      worker.onmessage = null;
      worker.onerror = null;
      worker.terminate();
      resolve(capability);
    };
    const timer = setTimeout(() => settle(UNAVAILABLE), environment.timeoutMs);
    worker.onmessage = (event: MessageEvent<unknown>) => {
      const data = event.data as { evaluates?: unknown; offscreenCanvas?: unknown } | null;
      const workerEval = data?.evaluates === true;
      settle({ workerEval, offscreenCanvas: workerEval && data?.offscreenCanvas === true && environment.pageTransfersCanvas });
    };
    worker.onerror = () => settle(UNAVAILABLE);
  });
}

export function createBlobProbeWorker(): Worker {
  const url = URL.createObjectURL(new Blob([PROBE_WORKER_SOURCE], { type: "text/javascript" }));
  return new Worker(url, { type: "module", name: "monomer-capability-probe" });
}

export function pageTransfersCanvas(): boolean {
  return typeof HTMLCanvasElement === "function" && typeof HTMLCanvasElement.prototype.transferControlToOffscreen === "function";
}
