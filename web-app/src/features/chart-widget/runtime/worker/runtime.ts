import { isDashboardValueShapeValid } from "@/features/dashboard/dashboard-value";
import * as echarts from "echarts";
import type { ECharts, EChartsOption } from "echarts";
import type {
  ChartEventFrame,
  ChartParameters,
  ChartHandle,
  ChartModule,
  ChartQueryResults,
  ChartVariableValues,
  ChartViewport,
  SafeChartEventParams,
} from "../../contract/abi";
import {
  type Callable,
  canonicalClone,
  canonicalFrame,
  canonicalRenderOption,
} from "./canonical-boundary";
import {
  createGovernedChartHandle,
  type ChartEventRegistration,
  type GovernedChartRuntime,
} from "./governed-chart-handle";
import { eventMatchesQuery, snapshotChartEventPayload } from "./event-boundary";
import { evaluateChartSource } from "./module-source";
import { loadInlineRasterImage } from "./inline-raster";
import { applyHostPresentationPolicy, MONOMER_CHART_THEME } from "./chart-theme";
import { createOptionFunctionHandler } from "./safe-callback-boundary";
import type {
  ChartVariableInput,
  ChartWorkerRequest,
  ChartWorkerResponse,
  ChartInput,
} from "../worker-protocol";

type CurrentFrame = Readonly<{
  queries: ChartQueryResults;
  parameters: ChartParameters;
  variables: ChartVariableValues;
  locale: string;
  viewport: ChartViewport;
}>;

type VariableDefinitions = Readonly<Record<string, ChartVariableInput>>;

type Registration = ChartEventRegistration & Readonly<{
  listener: (...args: unknown[]) => void;
}>;

let generation = -1;
let chart: ECharts | null = null;
let module: ChartModule | null = null;
let currentFrame: CurrentFrame | null = null;
let currentRenderOperationId = 0;
let applyingFrame = false;
let variableDefinitions: VariableDefinitions = {};
let registrations: Registration[] = [];
let setupPhase = false;
let eventPhase = false;
let disposed = true;
let density = 1;
let reducedMotion = false;
let platformConfigured = false;
let foreignFunctions = new WeakSet<Callable>();
let callbackOperationId = 0;
function observeCallback(): () => void {
  const operationId = --callbackOperationId;
  const callbackGeneration = generation;
  post({ type: "callback_started", generation: callbackGeneration, operationId });
  return () => post({ type: "callback_completed", generation: callbackGeneration, operationId });
}
let optionFunctionHandler = createOptionFunctionHandler(foreignFunctions, observeCallback);
const MAX_EVENT_REGISTRATIONS = 64;

function post(message: ChartWorkerResponse): void {
  if (message.type === "variables_set" || message.type === "callback_started") {
    self.postMessage(message);
    return;
  }
  // Completion must cross a task boundary so detached microtasks cannot cancel the Host deadline.
  setTimeout(() => self.postMessage(message), 0);
}

const MAXIMUM_ERROR_TEXT_LENGTH = 1_000;
const UNREADABLE_ERROR_TEXT = "Chart Widget source failed";

// Widget source controls thrown values; read only a plain string message so no user getter, toString or
// oversized text reaches the Host.
function errorText(error: unknown): string {
  if (typeof error === "string") return error.slice(0, MAXIMUM_ERROR_TEXT_LENGTH);
  try {
    if (!(error instanceof Error)) return UNREADABLE_ERROR_TEXT;
    const message = Object.getOwnPropertyDescriptor(error, "message");
    return message && "value" in message && typeof message.value === "string"
      ? message.value.slice(0, MAXIMUM_ERROR_TEXT_LENGTH)
      : UNREADABLE_ERROR_TEXT;
  } catch {
    return UNREADABLE_ERROR_TEXT;
  }
}

function assertFiniteViewport(viewport: ChartViewport): ChartViewport {
  if (
    !Number.isFinite(viewport.width)
    || !Number.isFinite(viewport.height)
    || viewport.width <= 0
    || viewport.height <= 0
    || viewport.width > 16_384
    || viewport.height > 16_384
  ) {
    throw new Error("Chart viewport is outside the supported bounds");
  }
  return Object.freeze({ width: Math.round(viewport.width), height: Math.round(viewport.height) });
}

function variableValues(definitions: VariableDefinitions): ChartVariableValues {
  const values: Record<string, unknown> = Object.create(null);
  for (const [name, definition] of Object.entries(definitions)) {
    values[name] = canonicalClone(definition.value, `variable.${name}`);
  }
  return canonicalClone(values, "variables") as ChartVariableValues;
}

function eventFrame(frame: CurrentFrame, renderOperationId: number): {
  frame: ChartEventFrame;
  close(): void;
} {
  let active = true;
  const setVariables = (variables: ChartVariableValues): void => {
    if (!active || !eventPhase) {
      throw new Error("Variable writes are available only from the current synchronous event handler");
    }
    if (variables === null || typeof variables !== "object" || Array.isArray(variables)) {
      throw new Error("Variable changes must be a non-empty map");
    }
    const snapshot = canonicalClone(variables, "variableChanges");
    const entries = Object.entries(snapshot);
    if (entries.length === 0) throw new Error("Variable changes must be a non-empty map");
    for (const [name, value] of entries) {
      if (!Object.hasOwn(variableDefinitions, name)) {
        throw new Error(`Variable '${name}' is not a declared module input`);
      }
      const definition = variableDefinitions[name];
      if (value === null && definition.nullable !== true) {
        throw new Error(`Variable '${name}' is not nullable`);
      }
      if (!isDashboardValueShapeValid(definition.type, value)
        || (Array.isArray(value) && value.some((item) => item === null))) {
        throw new Error(`Variable '${name}' does not match ${definition.type}`);
      }
    }
    post({ type: "variables_set", generation, renderOperationId, variables: snapshot });
  };
  foreignFunctions.add(setVariables);
  return {
    frame: Object.freeze({ ...frame, setVariables }),
    close() { active = false; },
  };
}

function chartRuntime(): GovernedChartRuntime {
  return {
    on(registration): void {
      if (!setupPhase || !chart || disposed) {
        throw new Error("Chart.on is available only during setup");
      }
      if (registrations.length >= MAX_EVENT_REGISTRATIONS) {
        throw new Error(`Chart event registrations exceed ${MAX_EVENT_REGISTRATIONS}`);
      }
      const listener = (payload: unknown): void => {
        if (disposed || !currentFrame || eventPhase || applyingFrame) return;
        const event = eventFrame(currentFrame, currentRenderOperationId);
        let completeCallback: (() => void) | null = null;
        try {
          const candidate = snapshotChartEventPayload(payload);
          if (!eventMatchesQuery(candidate, registration.query)) return;
          eventPhase = true;
          completeCallback = observeCallback();
          const result = Reflect.apply(registration.handler as unknown as (
            params: SafeChartEventParams,
            frame: ChartEventFrame,
          ) => unknown, undefined, [candidate as SafeChartEventParams, event.frame]);
          if (result && typeof (result as PromiseLike<unknown>).then === "function") {
            throw new Error("Async Chart event handlers are not supported");
          }
        } catch (error) {
          post({
            type: "failed",
            generation,
            phase: "event",
            message: errorText(error),
          });
        } finally {
          completeCallback?.();
          event.close();
          eventPhase = false;
          if (registration.eventName === "legendselectchanged" || registration.eventName === "datazoom") {
            restoreAcceptedFrameAfterEvent(currentFrame);
          }
        }
      };
      const stored = { ...registration, listener };
      registrations.push(stored);
      if (registration.query === undefined) {
        chart.on(registration.eventName, listener as never);
      } else {
        chart.on(registration.eventName, registration.query as never, listener as never);
      }
    },
    dispatchAction(action): void {
      if (!eventPhase || !chart || disposed) {
        throw new Error("Chart.dispatchAction is available only from an event handler");
      }
      chart.dispatchAction(action as never);
    },
    convertToPixel(finder, value): unknown {
      if (!eventPhase || !chart || disposed) {
        throw new Error("Chart.convertToPixel is available only from an event handler");
      }
      return chart.convertToPixel(finder as never, value as never);
    },
    convertFromPixel(finder, value): unknown {
      if (!eventPhase || !chart || disposed) {
        throw new Error("Chart.convertFromPixel is available only from an event handler");
      }
      return chart.convertFromPixel(finder as never, value as never);
    },
    containPixel(finder, value): boolean {
      if (!eventPhase || !chart || disposed) {
        throw new Error("Chart.containPixel is available only from an event handler");
      }
      return chart.containPixel(finder as never, value as never);
    },
  };
}

function restoreAcceptedFrameAfterEvent(frame: CurrentFrame): void {
  const eventGeneration = generation;
  // ECharts finishes its action before another complete option may be applied.
  queueMicrotask(() => {
    if (disposed || eventGeneration !== generation || frame !== currentFrame || !chart || !module) return;
    const complete = observeCallback();
    applyingFrame = true;
    try {
      chart.setOption(hostOption(module.render(frame)), { notMerge: true, lazyUpdate: false, silent: true });
    } catch (error) {
      post({ type: "failed", generation, phase: "event", message: errorText(error) });
    } finally {
      applyingFrame = false;
      complete();
    }
  });
}

function hostOption(output: unknown): EChartsOption {
  return applyHostPresentationPolicy(canonicalRenderOption(output as EChartsOption, optionFunctionHandler), reducedMotion) as EChartsOption;
}

function closeChart(): void {
  if (chart) {
    for (const registration of registrations) {
      if (registration.query === undefined) {
        (chart.off as unknown as (...args: unknown[]) => void)(registration.eventName, registration.listener);
      } else {
        (chart.off as unknown as (...args: unknown[]) => void)(registration.eventName, registration.query, registration.listener);
      }
    }
    chart.dispose();
  }
  chart = null;
  module = null;
  currentFrame = null;
  currentRenderOperationId = 0;
  applyingFrame = false;
  registrations = [];
  setupPhase = false;
  eventPhase = false;
}

function frameFrom(input: Readonly<{
  queries: ChartQueryResults;
  parameters: ChartParameters;
  variables: ChartVariableValues;
  locale: string;
  viewport: ChartViewport;
}>): CurrentFrame {
  return canonicalFrame({
    queries: input.queries,
    parameters: input.parameters,
    variables: input.variables,
    locale: typeof input.locale === "string" ? input.locale : "en-US",
    viewport: assertFiniteViewport(input.viewport),
  });
}

function configureEChartsPlatform(): void {
  if (platformConfigured) return;
  if (typeof OffscreenCanvas === "undefined") throw new Error("OffscreenCanvas is unavailable in the Chart Worker");
  const measurementCanvas = new OffscreenCanvas(1, 1);
  const measurementContext = measurementCanvas.getContext("2d");
  echarts.setPlatformAPI({
    createCanvas: () => new OffscreenCanvas(1, 1) as never,
    measureText: (text, font) => {
      if (!measurementContext) return { width: String(text).length * 7 } as never;
      measurementContext.font = font || "12px system-ui, sans-serif";
      return measurementContext.measureText(String(text)) as never;
    },
    loadImage: loadInlineRasterImage,
  });
  platformConfigured = true;
}

function applyFrame(
  nextFrame: CurrentFrame,
  operationId: number,
): void {
  if (!chart || !module || disposed) throw new Error("Chart is not initialized");
  applyingFrame = true;
  try {
    const option = hostOption(module.render(nextFrame));
    if (
      currentFrame
      && (
        currentFrame.viewport.width !== nextFrame.viewport.width
        || currentFrame.viewport.height !== nextFrame.viewport.height
      )
    ) {
      chart.resize({ width: nextFrame.viewport.width, height: nextFrame.viewport.height });
    }
    chart.setOption(option, { notMerge: true, lazyUpdate: false, silent: false });
    currentFrame = nextFrame;
    currentRenderOperationId = operationId;
  } finally {
    applyingFrame = false;
  }
  post({ type: "rendered", generation, operationId });
}

function initialize(message: Extract<ChartWorkerRequest, { type: "initialize" }>): void {
  closeChart();
  generation = message.generation;
  disposed = false;
  foreignFunctions = new WeakSet<Callable>();
  optionFunctionHandler = createOptionFunctionHandler(foreignFunctions, observeCallback);
  variableDefinitions = canonicalClone(message.variables, "variableDefinitions");
  const viewport = assertFiniteViewport(message.viewport);
  if (!Number.isFinite(message.density) || message.density <= 0 || message.density > 4) {
    throw new Error("Chart density is outside the supported bounds");
  }
  density = message.density;
  reducedMotion = message.reducedMotion === true;
  const initialFrame = frameFrom({
    queries: message.queries, parameters: message.parameters,
    variables: variableValues(variableDefinitions), locale: message.locale, viewport,
  });
  module = evaluateChartSource(message.moduleSource, "chart-widget.ts", foreignFunctions);
  configureEChartsPlatform();
  chart = echarts.init(message.canvas as unknown as HTMLElement, MONOMER_CHART_THEME, {
    renderer: "canvas",
    width: viewport.width,
    height: viewport.height,
    devicePixelRatio: density,
  });
  const handle: ChartHandle = createGovernedChartHandle(chartRuntime());
  for (const name of [
    "on",
    "dispatchAction",
    "convertToPixel",
    "convertFromPixel",
    "containPixel",
  ] as const) {
    const candidate = handle[name];
    if (typeof candidate === "function") foreignFunctions.add(candidate as Callable);
  }
  setupPhase = true;
  try {
    const result = module.setup
      ? (module.setup as unknown as (context: { chart: ChartHandle }) => unknown)({ chart: handle })
      : undefined;
    if (result && typeof (result as PromiseLike<unknown>).then === "function") {
      throw new Error("Async Chart setup is not supported");
    }
  } finally {
    setupPhase = false;
  }
  post({ type: "initialized", generation });
  applyFrame(initialFrame, 0);
}

function render(message: Extract<ChartWorkerRequest, { type: "render" }>): void {
  if (message.generation !== generation || disposed) return;
  applyFrame(frameFrom(message.frame), message.operationId);
}

function resize(message: Extract<ChartWorkerRequest, { type: "resize" }>): void {
  if (message.generation !== generation || disposed || !chart || !currentFrame) return;
  const viewport = assertFiniteViewport(message.viewport);
  const nextFrame = frameFrom({ ...currentFrame, viewport });
  applyingFrame = true;
  try {
    const option = hostOption(module?.render(nextFrame));
    chart.resize({ width: viewport.width, height: viewport.height });
    chart.setOption(option, { notMerge: true, lazyUpdate: false, silent: false });
    currentFrame = nextFrame;
  } finally {
    applyingFrame = false;
  }
  post({ type: "resized", generation, operationId: message.operationId, viewport });
}

function present(message: Extract<ChartWorkerRequest, { type: "presentation" }>): void {
  if (message.generation !== generation || disposed) return;
  reducedMotion = message.reducedMotion === true;
  if (chart && module && currentFrame) {
    applyingFrame = true;
    try {
      chart.setOption(hostOption(module.render(currentFrame)), { notMerge: true, lazyUpdate: false, silent: true });
    } finally {
      applyingFrame = false;
    }
  }
  post({ type: "presented", generation, operationId: message.operationId });
}

function rawInputEvent(input: ChartInput): Record<string, unknown> {
  if (input.kind === "wheel") {
    return {
      zrX: input.x,
      zrY: input.y,
      zrDelta: input.deltaY === 0 ? 0 : -input.deltaY / 120,
      wheelDelta: input.deltaY === 0 ? 0 : -input.deltaY / 120,
      deltaX: input.deltaX,
      deltaY: input.deltaY,
      deltaMode: input.deltaMode,
      altKey: input.altKey,
      ctrlKey: input.ctrlKey,
      metaKey: input.metaKey,
      shiftKey: input.shiftKey,
      which: 0,
      preventDefault: () => undefined,
      stopPropagation: () => undefined,
    };
  }
  return {
    zrX: input.x,
    zrY: input.y,
    button: input.button,
    buttons: input.buttons,
    which: input.button + 1,
    pointerId: input.pointerId,
    altKey: input.altKey,
    ctrlKey: input.ctrlKey,
    metaKey: input.metaKey,
    shiftKey: input.shiftKey,
    preventDefault: () => undefined,
    stopPropagation: () => undefined,
  };
}

function dispatchInput(message: Extract<ChartWorkerRequest, { type: "input" }>): void {
  if (message.generation !== generation || disposed || !chart) return;
  const eventType = message.input.kind === "wheel" ? "mousewheel" : message.input.eventType;
  if (message.renderOperationId === currentRenderOperationId && currentFrame) {
    chart.getZr().handler.dispatch(eventType as never, rawInputEvent(message.input) as never);
  }
  post({ type: "input_dispatched", generation, operationId: message.operationId, eventType });
}

function handle(request: ChartWorkerRequest): void {
  try {
    if (request.type === "initialize") initialize(request);
    else if (request.type === "render") render(request);
    else if (request.type === "resize") resize(request);
    else if (request.type === "presentation") present(request);
    else if (request.type === "input") dispatchInput(request);
    else if (request.type === "dispose" && request.generation === generation) {
      disposed = true;
      closeChart();
      post({ type: "disposed", generation });
    }
  } catch (error) {
    const phase = request.type === "initialize"
      ? "initialize"
      : request.type === "render" || request.type === "presentation"
        ? "render"
        : "resize";
    post({
      type: "failed",
      generation: request.generation,
      phase,
      operationId: "operationId" in request ? request.operationId : undefined,
      message: errorText(error),
    });
  }
}

export function startChartWidgetWorkerRuntime(): void {
  self.onmessage = (event: MessageEvent<ChartWorkerRequest>) => handle(event.data);
}
