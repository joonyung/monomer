import { validateChartFrame } from "./worker/canonical-boundary";
import type {
  ChartParameters,
  ChartQueryResults,
  ChartVariableValues,
  ChartViewport,
} from "../contract/abi";
import { createChartWidgetWorker } from "./worker-factory";
import { attachChartInputBridge, type ChartInputBridge } from "./chart-input-bridge";
import type {
  ChartWidgetInputs,
  ChartWidgetRuntimeListener,
  ChartWidgetRuntimeStatus,
  ChartWorkerRequest,
  ChartWorkerResponse,
  ChartInput,
} from "./worker-protocol";

export type ChartWidgetRuntimeState = Readonly<{
  status: ChartWidgetRuntimeStatus;
  generation: number;
  lastGoodOperationId: number | null;
  lastError: string | null;
}>;

export type ChartWidgetWorkerFactory = (generation: number) => Worker;
const CHART_INITIALIZATION_DEADLINE_MS = 10_000;
const CHART_OPERATION_DEADLINE_MS = 2_000;
const REDUCED_MOTION_QUERY = "(prefers-reduced-motion: reduce)";

function reducedMotionQuery(): MediaQueryList | null {
  return typeof window === "undefined" || typeof window.matchMedia !== "function"
    ? null
    : window.matchMedia(REDUCED_MOTION_QUERY);
}

export class ChartWidgetRuntime {
  private readonly workerFactory: ChartWidgetWorkerFactory;
  private worker: Worker | null = null;
  private inputBridge: ChartInputBridge | null = null;
  private motionPreference: Readonly<{ query: MediaQueryList; listener: (event: MediaQueryListEvent) => void }> | null = null;
  private generation = 0;
  private operationId = 0;
  private lastFailedOperationId = -1;
  private listeners = new Set<ChartWidgetRuntimeListener>();
  private operationTimers = new Map<number, ReturnType<typeof setTimeout>>();
  private state: ChartWidgetRuntimeState = {
    status: "idle",
    generation: 0,
    lastGoodOperationId: null,
    lastError: null,
  };

  constructor(workerFactory: ChartWidgetWorkerFactory = createChartWidgetWorker) {
    this.workerFactory = workerFactory;
  }

  get snapshot(): ChartWidgetRuntimeState {
    return this.state;
  }

  subscribe(listener: ChartWidgetRuntimeListener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  mount(canvas: HTMLCanvasElement, inputs: ChartWidgetInputs): void {
    validateChartFrame({
      queries: inputs.queries, parameters: inputs.parameters,
      variables: Object.fromEntries(Object.entries(inputs.variables).map(([name, input]) => [name, input.value])),
      locale: inputs.locale, viewport: inputs.viewport,
    });
    this.dispose();
    if (typeof canvas.transferControlToOffscreen !== "function") {
      throw new Error("ChartWidget requires OffscreenCanvas support");
    }
    const offscreen = canvas.transferControlToOffscreen();
    this.inputBridge = attachChartInputBridge(canvas, (input) => this.input(input));
    const motionQuery = reducedMotionQuery();
    const nextGeneration = this.generation + 1;
    this.generation = nextGeneration;
    this.operationId = 0;
    this.lastFailedOperationId = -1;
    const worker = this.workerFactory(nextGeneration);
    this.worker = worker;
    this.setState({ status: "initializing", lastGoodOperationId: null, lastError: null });
    worker.onmessage = (event: MessageEvent<ChartWorkerResponse>) => {
      const response = event.data;
      if (response.generation !== this.generation || this.worker !== worker) return;
      if (response.type === "callback_started") {
        this.armOperationTimer(response.operationId, nextGeneration);
        return;
      } else if (response.type === "callback_completed") {
        this.clearOperationTimer(response.operationId);
        return;
      } else if (response.type === "initialized") {
        if (this.lastFailedOperationId < 0) this.setState({ status: "ready" });
      } else if (response.type === "rendered") {
        this.clearOperationTimer(response.operationId);
        this.setState({
          lastGoodOperationId: response.operationId,
          ...(response.operationId >= this.lastFailedOperationId ? { status: "ready" as const, lastError: null } : {}),
        });
      } else if (response.type === "resized") {
        this.clearOperationTimer(response.operationId);
        if (response.operationId >= this.lastFailedOperationId) this.setState({ status: "ready", lastError: null });
      } else if (response.type === "presented") {
        // Re-applying the accepted frame is not a new frame, so it must not clear a newer failure.
        this.clearOperationTimer(response.operationId);
      } else if (response.type === "input_dispatched") {
        this.clearOperationTimer(response.operationId);
      } else if (response.type === "failed") {
        this.clearOperationTimer(response.operationId ?? 0);
        this.lastFailedOperationId = Math.max(this.lastFailedOperationId, response.operationId ?? this.operationId);
        this.setState({ status: "failed", lastError: chartWorkerFailureText(response.message) });
      } else if (response.type === "disposed") {
        this.setState({ status: "disposed" });
      }
      for (const listener of this.listeners) listener(response);
    };
    worker.onerror = (event) => {
      if (nextGeneration !== this.generation || this.worker !== worker) return;
      for (const timer of this.operationTimers.values()) clearTimeout(timer);
      this.operationTimers.clear();
      this.worker = null;
      this.inputBridge?.dispose();
      this.inputBridge = null;
      worker.terminate();
      const response: ChartWorkerResponse = {
        type: "failed",
        generation: nextGeneration,
        phase: this.state.status === "initializing" ? "initialize" : "render",
        message: event.message || "Chart Worker failed",
      };
      this.setState({ status: "failed", lastError: response.message });
      for (const listener of this.listeners) listener(response);
    };
    const request: ChartWorkerRequest = {
      type: "initialize",
      generation: nextGeneration,
      canvas: offscreen,
      moduleSource: inputs.moduleSource,
      queries: inputs.queries,
      parameters: inputs.parameters,
      variables: inputs.variables,
      locale: inputs.locale,
      viewport: inputs.viewport,
      density: inputs.density ?? (typeof window === "undefined" ? 1 : Math.min(window.devicePixelRatio || 1, 4)),
      reducedMotion: motionQuery?.matches === true,
    };
    this.armOperationTimer(0, nextGeneration);
    worker.postMessage(request, [offscreen]);
    if (motionQuery) {
      const listener = (event: MediaQueryListEvent) => this.present(event.matches);
      motionQuery.addEventListener("change", listener);
      this.motionPreference = { query: motionQuery, listener };
    }
  }

  render(input: Readonly<{
    queries: ChartQueryResults;
    parameters: ChartParameters;
    variables: ChartVariableValues;
    locale: string;
    viewport: ChartViewport;
  }>): number {
    this.assertMounted();
    const operationId = ++this.operationId;
    try {
      validateChartFrame(input);
    } catch (error) {
      const response: ChartWorkerResponse = {
        type: "failed", generation: this.generation, phase: "render", operationId,
        message: error instanceof Error ? error.message : String(error),
      };
      this.lastFailedOperationId = operationId;
      this.setState({ status: "failed", lastError: response.message });
      for (const listener of this.listeners) listener(response);
      return operationId;
    }
    this.setState({ status: "rendering" });
    this.armOperationTimer(operationId, this.generation);
    this.send({
      type: "render",
      generation: this.generation,
      operationId,
      frame: input,
    });
    return operationId;
  }

  resize(viewport: ChartViewport): number {
    this.assertMounted();
    const operationId = ++this.operationId;
    this.armOperationTimer(operationId, this.generation);
    this.send({
      type: "resize",
      generation: this.generation,
      operationId,
      viewport,
      density: typeof window === "undefined" ? 1 : Math.min(window.devicePixelRatio || 1, 4),
    });
    return operationId;
  }

  private present(reducedMotion: boolean): void {
    if (!this.worker) return;
    const operationId = ++this.operationId;
    this.armOperationTimer(operationId, this.generation);
    this.send({ type: "presentation", generation: this.generation, operationId, reducedMotion });
  }

  input(input: ChartInput): number {
    this.assertMounted();
    const operationId = ++this.operationId;
    this.armOperationTimer(operationId, this.generation);
    this.send({
      type: "input",
      generation: this.generation,
      operationId,
      renderOperationId: this.state.lastGoodOperationId,
      input,
    });
    return operationId;
  }

  dispose(): void {
    const worker = this.worker;
    this.worker = null;
    for (const timer of this.operationTimers.values()) clearTimeout(timer);
    this.operationTimers.clear();
    this.inputBridge?.dispose();
    this.inputBridge = null;
    this.detachMotionPreference();
    if (!worker) return;
    const disposedGeneration = this.generation;
    try {
      worker.postMessage({ type: "dispose", generation: disposedGeneration } satisfies ChartWorkerRequest);
    } finally {
      worker.terminate();
      this.setState({ status: "disposed" });
    }
  }

  private detachMotionPreference(): void {
    this.motionPreference?.query.removeEventListener("change", this.motionPreference.listener);
    this.motionPreference = null;
  }

  private send(request: ChartWorkerRequest): void {
    this.assertMounted().postMessage(request);
  }

  private assertMounted(): Worker {
    if (!this.worker) throw new Error("ChartWidget is not mounted");
    return this.worker;
  }

  private setState(partial: Partial<ChartWidgetRuntimeState>): void {
    this.state = Object.freeze({ ...this.state, ...partial, generation: this.generation });
  }

  private armOperationTimer(operationId: number, operationGeneration: number): void {
    this.clearOperationTimer(operationId);
    this.operationTimers.set(operationId, setTimeout(() => {
      if (operationGeneration !== this.generation || !this.worker) return;
      this.worker.terminate();
      this.worker = null;
      for (const timer of this.operationTimers.values()) clearTimeout(timer);
      this.operationTimers.clear();
      this.inputBridge?.dispose();
      this.inputBridge = null;
      const response: ChartWorkerResponse = {
        type: "failed",
        generation: operationGeneration,
        phase: operationId === 0 ? "initialize" : "render",
        operationId: operationId === 0 ? undefined : operationId,
        message: "Chart Worker operation timed out",
      };
      this.setState({ status: "failed", lastError: response.message });
      for (const listener of this.listeners) listener(response);
    }, operationId === 0 ? CHART_INITIALIZATION_DEADLINE_MS : CHART_OPERATION_DEADLINE_MS));
  }

  private clearOperationTimer(operationId: number): void {
    const timer = this.operationTimers.get(operationId);
    if (timer === undefined) return;
    clearTimeout(timer);
    this.operationTimers.delete(operationId);
  }
}

const MAXIMUM_CHART_WORKER_FAILURE_LENGTH = 1_000;

// The Worker runs Editor-authored source, so its failure text is untrusted display data.
function chartWorkerFailureText(message: unknown): string {
  return typeof message === "string" ? message.slice(0, MAXIMUM_CHART_WORKER_FAILURE_LENGTH) : "Chart Worker failed";
}
