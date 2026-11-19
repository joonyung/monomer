import type {
  AllowedChartAction,
  ChartEventFrame,
  ChartEventName,
  ChartEventQuery,
  ChartHandle,
  SafeChartEventParams,
} from "../../contract/abi";
import { canonicalClone } from "./canonical-boundary";
import { assertEventName, snapshotEventQuery } from "./event-boundary";

const ALLOWED_ACTION_TYPES = new Set([
  "highlight",
  "downplay",
  "select",
  "unselect",
  "toggleSelect",
  "showTip",
  "hideTip",
  "legendSelect",
  "legendUnSelect",
  "legendToggleSelect",
  "dataZoom",
]);

export type ChartEventRegistration = Readonly<{
  eventName: ChartEventName;
  query: ChartEventQuery | undefined;
  handler: (params: SafeChartEventParams, frame: ChartEventFrame) => void;
}>;

export interface GovernedChartRuntime {
  readonly on: (registration: ChartEventRegistration) => void;
  readonly dispatchAction: (action: AllowedChartAction) => void;
  readonly convertToPixel: (finder: unknown, value: unknown) => unknown;
  readonly convertFromPixel: (finder: unknown, value: unknown) => unknown;
  readonly containPixel: (finder: unknown, value: unknown) => boolean;
}

export function createGovernedChartHandle(runtime: GovernedChartRuntime): ChartHandle {
  const handle: ChartHandle = {
    on(
      eventName: ChartEventName,
      queryOrHandler: ChartEventQuery | ((params: SafeChartEventParams, frame: ChartEventFrame) => void),
      maybeHandler?: (params: SafeChartEventParams, frame: ChartEventFrame) => void,
    ): void {
      const safeEventName = assertEventName(eventName);
      const query = typeof queryOrHandler === "function" ? undefined : snapshotEventQuery(queryOrHandler);
      const handler = typeof queryOrHandler === "function" ? queryOrHandler : maybeHandler;
      if (typeof handler !== "function") throw new Error("Chart.on requires a handler");
      runtime.on({ eventName: safeEventName, query, handler });
    },
    dispatchAction(action: AllowedChartAction): void {
      const snapshot = canonicalClone(action, "action") as AllowedChartAction;
      if (typeof snapshot.type !== "string" || !ALLOWED_ACTION_TYPES.has(snapshot.type)) {
        throw new Error(`Unsupported chart action '${String(snapshot.type)}'`);
      }
      runtime.dispatchAction(snapshot);
    },
    convertToPixel(finder: unknown, value: unknown): unknown {
      return canonicalClone(runtime.convertToPixel(
        canonicalClone(finder, "finder"),
        canonicalClone(value, "value"),
      ), "pixel");
    },
    convertFromPixel(finder: unknown, value: unknown): unknown {
      return canonicalClone(runtime.convertFromPixel(
        canonicalClone(finder, "finder"),
        canonicalClone(value, "value"),
      ), "pixel");
    },
    containPixel(finder: unknown, value: unknown): boolean {
      const result = runtime.containPixel(
        canonicalClone(finder, "finder"),
        canonicalClone(value, "value"),
      );
      if (typeof result !== "boolean") throw new Error("Chart.containPixel returned a non-boolean value");
      return result;
    },
  };
  return Object.freeze(handle);
}
