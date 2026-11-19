import type { DashboardValueType } from "@/api/dashboard";
import type {
  ChartEventFrame,
  ChartModule,
  ChartParameters,
  ChartQueryResults,
  ChartVariableValues,
  ChartViewport,
} from "../contract/abi";

export type ChartVariableInput = Readonly<{
  value: unknown;
  type: DashboardValueType;
  nullable?: boolean;
}>;

export type ChartInput =
  | Readonly<{
    kind: "pointer";
    eventType: "click" | "dblclick" | "mousedown" | "mousemove" | "mouseup" | "mouseout" | "contextmenu";
    x: number;
    y: number;
    button: number;
    buttons: number;
    pointerId: number;
    altKey: boolean;
    ctrlKey: boolean;
    metaKey: boolean;
    shiftKey: boolean;
    trusted: boolean;
  }>
  | Readonly<{
    kind: "wheel";
    x: number;
    y: number;
    deltaX: number;
    deltaY: number;
    deltaMode: number;
    altKey: boolean;
    ctrlKey: boolean;
    metaKey: boolean;
    shiftKey: boolean;
    trusted: boolean;
  }>;

export type ChartWidgetInputs = Readonly<{
  moduleSource: string;
  queries: ChartQueryResults;
  parameters: ChartParameters;
  variables: Readonly<Record<string, ChartVariableInput>>;
  locale: string;
  viewport: ChartViewport;
  /** Host-only device pixel ratio; never exposed to source. */
  density?: number;
}>;

export type ChartRenderRequest = Readonly<{
  type: "render";
  generation: number;
  operationId: number;
  frame: Readonly<{
    queries: ChartQueryResults;
    parameters: ChartParameters;
    variables: ChartVariableValues;
    locale: string;
    viewport: ChartViewport;
  }>;
}>;

export type ChartWorkerRequest =
  | Readonly<{
    type: "initialize";
    generation: number;
    canvas: OffscreenCanvas;
    moduleSource: string;
    queries: ChartQueryResults;
    parameters: ChartParameters;
    variables: Readonly<Record<string, ChartVariableInput>>;
    locale: string;
    viewport: ChartViewport;
    density: number;
    reducedMotion: boolean;
  }>
  | ChartRenderRequest
  | Readonly<{
    type: "resize";
    generation: number;
    operationId: number;
    viewport: ChartViewport;
    density: number;
  }>
  | Readonly<{
    type: "presentation";
    generation: number;
    operationId: number;
    reducedMotion: boolean;
  }>
  | Readonly<{
    type: "input";
    generation: number;
    operationId: number;
    renderOperationId: number | null;
    input: ChartInput;
  }>
  | Readonly<{
    type: "dispose";
    generation: number;
  }>;

export type ChartWorkerResponse =
  | Readonly<{
    type: "callback_started" | "callback_completed";
    generation: number;
    operationId: number;
  }>
  | Readonly<{
    type: "initialized";
    generation: number;
  }>
  | Readonly<{
    type: "rendered";
    generation: number;
    operationId: number;
  }>
  | Readonly<{
    type: "resized";
    generation: number;
    operationId: number;
    viewport: ChartViewport;
  }>
  | Readonly<{
    type: "presented";
    generation: number;
    operationId: number;
  }>
  | Readonly<{
    type: "input_dispatched";
    generation: number;
    operationId: number;
    eventType: string;
  }>
  | Readonly<{
    type: "disposed";
    generation: number;
  }>
  | Readonly<{
    type: "variables_set";
    generation: number;
    renderOperationId: number;
    variables: ChartVariableValues;
  }>
  | Readonly<{
    type: "failed";
    generation: number;
    phase: "initialize" | "render" | "resize" | "event";
    operationId?: number;
    message: string;
  }>;

export type ChartWidgetRuntimeStatus =
  | "idle"
  | "initializing"
  | "ready"
  | "rendering"
  | "failed"
  | "disposed";

export type ChartWidgetRuntimeListener = (response: ChartWorkerResponse) => void;

export type { ChartEventFrame, ChartModule };
