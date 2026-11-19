import "ses";
import { beforeAll } from "vitest";
import { afterEach, describe, expect, it, vi } from "vitest";
import type {
  ChartHandle,
  ChartModule,
} from "../contract/abi";
import { ChartWidgetRuntime } from "./chart-widget-runtime";
import type { ChartWidgetInputs, ChartWorkerRequest, ChartWorkerResponse } from "./worker-protocol";
import { createGovernedChartHandle, type ChartEventRegistration, type GovernedChartRuntime } from "./worker/governed-chart-handle";
import { evaluateChartSource, compartmentGlobalIsFrozen } from "./worker/module-source";

beforeAll(() => {
  lockdown({ errorTaming: "unsafe-debug" });
});

const inputs: ChartWidgetInputs = {
  moduleSource: "export default defineChart({ render() { return { series: [] }; } });",
  queries: { orders: { columns: [{ name: "dimension.orders.status", logicalType: "TEXT", nullable: false, numberFormat: null }], rows: [{ "dimension.orders.status": "North" }] } },
  parameters: {},
  variables: { selectedRegion: { value: null, type: "TEXT", nullable: true } },
  locale: "en-US",
  viewport: { width: 320, height: 200 },
};

class FakeWorker {
  onmessage: ((event: MessageEvent<ChartWorkerResponse>) => void) | null = null;
  onerror: ((event: ErrorEvent) => void) | null = null;
  terminated = false;
  readonly messages: ChartWorkerRequest[] = [];

  constructor(private readonly respond = true) {}

  postMessage(request: ChartWorkerRequest): void {
    this.messages.push(request);
    if (!this.respond) return;
    if (request.type === "initialize") {
      this.emit({ type: "initialized", generation: request.generation });
      this.emit({ type: "rendered", generation: request.generation, operationId: 0 });
    } else if (request.type === "render") {
      this.emit({ type: "rendered", generation: request.generation, operationId: request.operationId });
    } else if (request.type === "resize") {
      this.emit({ type: "resized", generation: request.generation, operationId: request.operationId, viewport: request.viewport });
    } else {
      this.emit({ type: "disposed", generation: request.generation });
    }
  }

  terminate(): void {
    this.terminated = true;
  }

  emit(response: ChartWorkerResponse): void {
    this.onmessage?.({ data: response } as MessageEvent<ChartWorkerResponse>);
  }
}

function canvas(): HTMLCanvasElement {
  const listeners = new Map<string, EventListener>();
  return {
    transferControlToOffscreen: () => ({}) as OffscreenCanvas,
    addEventListener: (name: string, listener: EventListener) => { listeners.set(name, listener); },
    removeEventListener: (name: string) => { listeners.delete(name); },
    getBoundingClientRect: () => ({ left: 0, top: 0, width: 320, height: 200 } as DOMRect),
    setPointerCapture: () => undefined,
    hasPointerCapture: () => false,
    releasePointerCapture: () => undefined,
  } as unknown as HTMLCanvasElement;
}

describe("ChartWidget source ABI", () => {
  it("exposes query rows as immutable named records", () => {
    const module = evaluateChartSource(`
      export default defineChart({
        render({ queries }) {
          const { ["dimension.orders.status"]: status, ["metric.order_count"]: orderCount } = queries["orders"].rows[0];
          return { title: { text: status + ":" + orderCount } };
        },
      });
    `);
    const option = module.render({
      queries: {
        orders: {
          columns: [{ name: "dimension.orders.status", logicalType: "TEXT", nullable: false, numberFormat: null }, { name: "metric.order_count", logicalType: "NUMBER", nullable: false, numberFormat: null }],
          rows: [{ "dimension.orders.status": "North", "metric.order_count": "63" }],
        },
      },
      parameters: {},
      variables: {},
      locale: "en-US",
      viewport: { width: 320, height: 200 },
    }) as { title: { text: string } };
    expect(option.title.text).toBe("North:63");
  });

  it("runs setup once while render can be invoked repeatedly", () => {
    const module = evaluateChartSource(`
      export default defineChart({
        setup({ chart }) { chart.on("click", () => {}); },
        render() { return { series: [] }; },
      });
    `) as ChartModule & { setupCalls?: number };
    let registrations = 0;
    // Setup is a host lifecycle call and registers the handler once per instance.
    const setupChart = {
      on() { registrations += 1; },
    } as unknown as ChartHandle;
    module.setup?.({ chart: setupChart });
    expect(registrations).toBe(1);
    const first = module.render({
      queries: {},
      parameters: {},
      variables: {},
      locale: "en-US",
      viewport: { width: 320, height: 200 },
    });
    const second = module.render({
      queries: {},
      parameters: {},
      variables: {},
      locale: "en-US",
      viewport: { width: 640, height: 200 },
    });
    expect(first).toEqual({ series: [] });
    expect(second).toEqual({ series: [] });
  });

  it("keeps ambient DOM and network authority unavailable", () => {
    const module = evaluateChartSource(`
      export default defineChart({
        render() {
          return {
            title: {
              text: String(typeof document) + ":" + String(typeof fetch) + ":" + String(typeof Date),
            },
          };
        },
      });
    `);
    const option = module.render({
      queries: {},
      parameters: {},
      variables: {},
      locale: "en-US",
      viewport: { width: 320, height: 200 },
    }) as { title: { text: string } };
    expect(option.title.text).toBe("undefined:undefined:function");
    const randomModule = evaluateChartSource(`
      export default defineChart({ render() { return { title: { text: String(Math.random()) } }; } });
    `);
    expect(() => randomModule.render({
      queries: {}, parameters: {}, variables: {}, locale: "en-US", viewport: { width: 320, height: 200 },
    })).toThrow(/random/);
    expect(compartmentGlobalIsFrozen()).toBe(true);
  });
});

describe("ChartHandle", () => {
  it("passes safe event frames to a registered handler and allows Variable writes", () => {
    let registration: ChartEventRegistration | undefined;
    let written: unknown = undefined;
    const runtime: GovernedChartRuntime = {
      on(next) { registration = next; },
      dispatchAction() { /* test port */ },
      convertToPixel() { return [0, 0]; },
      convertFromPixel() { return [0, 0]; },
      containPixel() { return true; },
    };
    const chart = createGovernedChartHandle(runtime);
    chart.on("click", (params, frame) => {
      frame.setVariables({ selectedRegion: params.name ?? null });
    });
    registration?.handler(
      { name: "North" },
      {
        queries: {},
        parameters: {},
        variables: {
          selectedRegion: null,
        },
        setVariables(variables) { written = variables.selectedRegion; },
        locale: "en-US",
        viewport: { width: 320, height: 200 },
      },
    );
    expect(written).toBe("North");
  });
});

describe("ChartWidgetRuntime host boundary", () => {
  it("shows only bounded text for a Worker failure", () => {
    const workers: FakeWorker[] = [];
    const runtime = new ChartWidgetRuntime((() => {
      const worker = new FakeWorker();
      workers.push(worker);
      return worker;
    }) as unknown as (generation: number) => Worker);
    runtime.mount(canvas(), inputs);
    workers[0].emit({ type: "failed", generation: 1, phase: "render", message: { a: 1 } as unknown as string });
    expect(runtime.snapshot.lastError).toBe("Chart Worker failed");
    workers[0].emit({ type: "failed", generation: 1, phase: "render", message: "x".repeat(50_000) });
    expect(runtime.snapshot.lastError).toHaveLength(1_000);
  });

  it("tracks render, resize and dispose without accepting a late generation", () => {
    const workers: FakeWorker[] = [];
    const runtime = new ChartWidgetRuntime((() => {
      const worker = new FakeWorker();
      workers.push(worker);
      return worker;
    }) as unknown as (generation: number) => Worker);
    runtime.mount(canvas(), inputs);
    expect(runtime.snapshot.status).toBe("ready");
    const firstWorker = workers[0];
    runtime.mount(canvas(), inputs);
    expect(runtime.snapshot.generation).toBe(2);
    firstWorker.emit({ type: "failed", generation: 1, phase: "render", message: "late" });
    expect(runtime.snapshot.lastError).toBeNull();
    runtime.render({
      queries: inputs.queries,
      parameters: inputs.parameters,
      variables: { selectedRegion: null },
      locale: inputs.locale,
      viewport: inputs.viewport,
    });
    runtime.resize({ width: 640, height: 200 });
    expect(runtime.snapshot.status).toBe("ready");
    runtime.dispose();
    expect(runtime.snapshot.status).toBe("disposed");
    expect(workers[1].terminated).toBe(true);
  });

  it("preserves earlier frame submissions while a newer render is pending or complete", () => {
    const worker = new FakeWorker(false);
    const runtime = new ChartWidgetRuntime(() => worker as unknown as Worker);
    const responses: ChartWorkerResponse[] = [];
    runtime.subscribe((response) => responses.push(response));
    runtime.mount(canvas(), inputs);
    worker.emit({ type: "rendered", generation: 1, operationId: 0 });
    const operationId = runtime.render({
      queries: inputs.queries, parameters: inputs.parameters, variables: { selectedRegion: "KR" },
      locale: inputs.locale, viewport: inputs.viewport,
    });
    const oldSubmission = { type: "variables_set" as const, generation: 1, renderOperationId: 0, variables: { selectedRegion: "US" } };
    worker.emit(oldSubmission);
    worker.emit({ type: "rendered", generation: 1, operationId });
    worker.emit(oldSubmission);
    expect(responses.filter((response) => response.type === "variables_set")).toEqual([oldSubmission, oldSubmission]);
    runtime.dispose();
    worker.emit(oldSubmission);
    expect(responses.filter((response) => response.type === "variables_set")).toHaveLength(2);
  });

  it("captures the acknowledged displayed operation when input is sent during a newer render", () => {
    const worker = new FakeWorker(false);
    const runtime = new ChartWidgetRuntime(() => worker as unknown as Worker);
    runtime.mount(canvas(), inputs);
    worker.emit({ type: "rendered", generation: 1, operationId: 0 });
    const operationId = runtime.render({
      queries: inputs.queries, parameters: inputs.parameters, variables: { selectedRegion: "KR" },
      locale: inputs.locale, viewport: inputs.viewport,
    });
    const input = {
      kind: "pointer" as const, eventType: "click" as const, x: 10, y: 10, button: 0, buttons: 0,
      pointerId: 1, altKey: false, ctrlKey: false, metaKey: false, shiftKey: false, trusted: true,
    };
    runtime.input(input);
    expect(worker.messages.at(-1)).toMatchObject({ type: "input", renderOperationId: 0 });
    worker.emit({ type: "rendered", generation: 1, operationId });
    runtime.input(input);
    expect(worker.messages.at(-1)).toMatchObject({ type: "input", renderOperationId: operationId });
    runtime.dispose();
  });

  it("notifies host listeners and closes a worker transport error", () => {
    const responses: ChartWorkerResponse[] = [];
    const worker = new FakeWorker();
    const runtime = new ChartWidgetRuntime(() => worker as unknown as Worker);
    runtime.subscribe((response) => responses.push(response));
    runtime.mount(canvas(), inputs);
    worker.onerror?.({ message: "worker exploded" } as ErrorEvent);

    expect(runtime.snapshot.status).toBe("failed");
    expect(responses.at(-1)).toMatchObject({
      type: "failed",
      generation: 1,
      phase: "render",
      message: "worker exploded",
    });
    expect(worker.terminated).toBe(true);
    expect(() => runtime.render({
      queries: inputs.queries,
      parameters: inputs.parameters,
      variables: { selectedRegion: null },
      locale: inputs.locale,
      viewport: inputs.viewport,
    })).toThrow(/not mounted/);

  });
});

it("rejects the aggregate frame before Worker transfer and keeps the last successful operation", () => {
  const worker = new FakeWorker();
  const runtime = new ChartWidgetRuntime(() => worker as unknown as Worker);
  runtime.mount(canvas(), inputs);
  const count = worker.messages.length;
  const lastGood = runtime.snapshot.lastGoodOperationId;
  const strings = Object.fromEntries(Array.from({ length: 36 }, (_, index) => [`p${index}`, "x".repeat(256 * 1024)]));
  const operationId = runtime.render({ queries: inputs.queries, parameters: strings, variables: strings, locale: "en-US", viewport: inputs.viewport });
  expect(worker.messages).toHaveLength(count);
  expect(runtime.snapshot).toMatchObject({ status: "failed", lastGoodOperationId: lastGood, lastError: expect.stringContaining("16777216 bytes") });
  expect(operationId).toBeGreaterThan(lastGood!);
  runtime.dispose();
});

it("checks initial frame totals before transferring the canvas or constructing a Worker", () => {
  let constructed = false;
  const runtime = new ChartWidgetRuntime(() => { constructed = true; return new FakeWorker() as unknown as Worker; });
  expect(() => runtime.mount(canvas(), { ...inputs, parameters: { huge: "x".repeat(256 * 1024 + 1) } })).toThrow("string exceeds");
  expect(constructed).toBe(false);
});

it("keeps a rejected newer frame's error through delayed render and resize completions", () => {
  const worker = new FakeWorker(false);
  const runtime = new ChartWidgetRuntime(() => worker as unknown as Worker);
  runtime.mount(canvas(), inputs);
  const generation = runtime.snapshot.generation;
  worker.emit({ type: "rendered", generation, operationId: 0 });
  const frame = { queries: inputs.queries, parameters: {}, variables: {}, locale: "en-US", viewport: inputs.viewport };
  const renderOperation = runtime.render(frame);
  const resizeOperation = runtime.resize(inputs.viewport);
  runtime.render({ ...frame, parameters: { tooLarge: "x".repeat(256 * 1024 + 1) } });
  const failure = runtime.snapshot.lastError;
  worker.emit({ type: "rendered", generation, operationId: renderOperation });
  worker.emit({ type: "resized", generation, operationId: resizeOperation, viewport: inputs.viewport });
  expect(runtime.snapshot).toMatchObject({ status: "failed", lastError: failure, lastGoodOperationId: renderOperation });
  const accepted = runtime.render(frame);
  worker.emit({ type: "rendered", generation, operationId: accepted });
  expect(runtime.snapshot).toMatchObject({ status: "ready", lastError: null, lastGoodOperationId: accepted });
  runtime.dispose();
});

it("keeps early rejection visible when initialization completes late", () => {
  const worker = new FakeWorker(false);
  const runtime = new ChartWidgetRuntime(() => worker as unknown as Worker);
  runtime.mount(canvas(), inputs);
  const generation = runtime.snapshot.generation;
  runtime.render({ queries: {}, parameters: { tooLarge: "x".repeat(256 * 1024 + 1) }, variables: {}, locale: "en-US", viewport: inputs.viewport });
  const failure = runtime.snapshot.lastError;
  worker.emit({ type: "initialized", generation });
  worker.emit({ type: "rendered", generation, operationId: 0 });
  expect(runtime.snapshot).toMatchObject({ status: "failed", lastError: failure, lastGoodOperationId: 0 });
  runtime.dispose();
});

describe("ChartWidgetRuntime motion preference", () => {
  afterEach(() => { vi.unstubAllGlobals(); });

  function stubMotionPreference(matches: boolean) {
    const listeners = new Set<(event: MediaQueryListEvent) => void>();
    const query = {
      matches,
      addEventListener: (_name: string, listener: (event: MediaQueryListEvent) => void) => { listeners.add(listener); },
      removeEventListener: (_name: string, listener: (event: MediaQueryListEvent) => void) => { listeners.delete(listener); },
    };
    const queried: string[] = [];
    vi.stubGlobal("window", {
      devicePixelRatio: 1,
      matchMedia: (media: string) => { queried.push(media); return query; },
    });
    return {
      queried,
      listeners,
      change(next: boolean) { for (const listener of [...listeners]) listener({ matches: next } as MediaQueryListEvent); },
    };
  }

  it("sends the current preference with initialize", () => {
    const preference = stubMotionPreference(true);
    const worker = new FakeWorker();
    const runtime = new ChartWidgetRuntime(() => worker as unknown as Worker);
    runtime.mount(canvas(), inputs);
    expect(preference.queried).toEqual(["(prefers-reduced-motion: reduce)"]);
    expect(worker.messages[0]).toMatchObject({ type: "initialize", reducedMotion: true });
    runtime.dispose();
  });

  it("forwards a preference change as a timed presentation operation that keeps the displayed operation", () => {
    const preference = stubMotionPreference(false);
    const worker = new FakeWorker(false);
    const runtime = new ChartWidgetRuntime(() => worker as unknown as Worker);
    runtime.mount(canvas(), inputs);
    const generation = runtime.snapshot.generation;
    worker.emit({ type: "rendered", generation, operationId: 0 });
    preference.change(true);
    const presentation = worker.messages.at(-1);
    expect(presentation).toMatchObject({ type: "presentation", generation, reducedMotion: true });
    worker.emit({ type: "presented", generation, operationId: (presentation as { operationId: number }).operationId });
    expect(runtime.snapshot).toMatchObject({ status: "ready", lastGoodOperationId: 0 });
    runtime.dispose();
    expect(preference.listeners.size).toBe(0);
    preference.change(false);
    expect(worker.messages.filter((message) => message.type === "presentation")).toHaveLength(1);
  });

  it("does not clear a newer frame failure when the accepted frame is re-presented", () => {
    const preference = stubMotionPreference(false);
    const worker = new FakeWorker(false);
    const runtime = new ChartWidgetRuntime(() => worker as unknown as Worker);
    runtime.mount(canvas(), inputs);
    const generation = runtime.snapshot.generation;
    worker.emit({ type: "rendered", generation, operationId: 0 });
    runtime.render({ queries: {}, parameters: { tooLarge: "x".repeat(256 * 1024 + 1) }, variables: {}, locale: "en-US", viewport: inputs.viewport });
    const failure = runtime.snapshot.lastError;
    preference.change(true);
    const presentation = worker.messages.at(-1) as { operationId: number };
    worker.emit({ type: "presented", generation, operationId: presentation.operationId });
    expect(runtime.snapshot).toMatchObject({ status: "failed", lastError: failure, lastGoodOperationId: 0 });
    runtime.dispose();
  });
});
