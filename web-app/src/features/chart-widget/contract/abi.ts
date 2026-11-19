import type { EChartsOption } from "echarts";
import type { DashboardFrameColumn } from "@/features/dashboard/dashboard-result";

export type ChartScalar = string | number | boolean | null;
export interface ChartRecord {
  readonly [name: string]: ChartValue;
}
export type ChartValue = ChartScalar | readonly ChartValue[] | ChartRecord;

export type ChartQueryRow = Readonly<Record<string, string | boolean | null>>;

export type ChartEventName =
  | "click"
  | "dblclick"
  | "mousedown"
  | "mousemove"
  | "mouseup"
  | "mouseover"
  | "mouseout"
  | "globalout"
  | "contextmenu"
  | "legendselectchanged"
  | "datazoom";

export type ChartEventQuery =
  | string
  | Readonly<Record<string, string | number | boolean>>;

export type SafeChartEventValue = ChartValue;

export type SafeChartEventParams = Readonly<{
  componentType?: SafeChartEventValue;
  componentSubType?: SafeChartEventValue;
  componentIndex?: SafeChartEventValue;
  seriesType?: SafeChartEventValue;
  seriesId?: SafeChartEventValue;
  seriesIndex?: SafeChartEventValue;
  seriesName?: SafeChartEventValue;
  name?: SafeChartEventValue;
  dataIndex?: SafeChartEventValue;
  dataType?: SafeChartEventValue;
  data?: SafeChartEventValue;
  value?: SafeChartEventValue;
  color?: SafeChartEventValue;
  borderColor?: SafeChartEventValue;
  dimensionNames?: SafeChartEventValue;
  encode?: SafeChartEventValue;
  percent?: SafeChartEventValue;
  marker?: SafeChartEventValue;
  start?: SafeChartEventValue;
  end?: SafeChartEventValue;
  startValue?: SafeChartEventValue;
  endValue?: SafeChartEventValue;
  selected?: SafeChartEventValue;
  batch?: SafeChartEventValue;
  fromAction?: SafeChartEventValue;
  fromActionPayload?: SafeChartEventValue;
  readonly [name: string]: SafeChartEventValue | undefined;
}>;

export type AllowedChartAction = Readonly<{
  type:
    | "highlight"
    | "downplay"
    | "select"
    | "unselect"
    | "toggleSelect"
    | "showTip"
    | "hideTip"
    | "legendSelect"
    | "legendUnSelect"
    | "legendToggleSelect"
    | "dataZoom";
  [name: string]: ChartValue;
}>;

export type ChartHandle = Readonly<{
  on(
    eventName: ChartEventName,
    handler: (params: SafeChartEventParams, frame: ChartEventFrame) => void,
  ): void;
  on(
    eventName: ChartEventName,
    query: ChartEventQuery,
    handler: (params: SafeChartEventParams, frame: ChartEventFrame) => void,
  ): void;
  dispatchAction(action: AllowedChartAction): void;
  convertToPixel(finder: unknown, value: unknown): unknown;
  convertFromPixel(finder: unknown, value: unknown): unknown;
  containPixel(finder: unknown, value: unknown): boolean;
}>;

export type ChartQueryResult = Readonly<{
  readonly columns: readonly DashboardFrameColumn[];
  readonly rows: readonly ChartQueryRow[];
}>;

export type ChartQueryResults = Readonly<Record<string, ChartQueryResult>>;
export type ChartParameters = Readonly<Record<string, ChartValue>>;
export type ChartVariableValues = Readonly<Record<string, ChartValue>>;

export type ChartViewport = Readonly<{
  width: number;
  height: number;
}>;

export type ChartRenderFrame = Readonly<{
  queries: ChartQueryResults;
  parameters: ChartParameters;
  variables: ChartVariableValues;
  locale: string;
  viewport: ChartViewport;
}>;

export type ChartEventFrame = ChartRenderFrame & Readonly<{
  setVariables(variables: ChartVariableValues): void;
}>;

export type ChartSetupContext = Readonly<{ chart: ChartHandle }>;

export type ChartModule = Readonly<{
  setup?: (context: ChartSetupContext) => void;
  render: (frame: ChartRenderFrame) => EChartsOption;
}>;

/** Authoring-only identity function; the Worker replaces it with a guarded binding. */
export function defineChart(module: ChartModule): ChartModule {
  return module;
}
