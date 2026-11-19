import type { ChartEventName, ChartEventQuery } from "../../contract/abi";
import { canonicalClone } from "./canonical-boundary";
import { snapshotCallbackArgument } from "./safe-callback-boundary";

export const SUPPORTED_CHART_EVENTS: ReadonlySet<ChartEventName> = new Set([
  "click",
  "dblclick",
  "mousedown",
  "mousemove",
  "mouseup",
  "mouseover",
  "mouseout",
  "globalout",
  "contextmenu",
  "legendselectchanged",
  "datazoom",
]);

const SAFE_EVENT_FIELDS: ReadonlySet<string> = new Set([
  "componentType",
  "componentSubType",
  "componentIndex",
  "seriesType",
  "seriesId",
  "seriesIndex",
  "seriesName",
  "name",
  "dataIndex",
  "dataType",
  "data",
  "value",
  "color",
  "borderColor",
  "dimensionNames",
  "encode",
  "percent",
  "marker",
  "start",
  "end",
  "startValue",
  "endValue",
  "selected",
  "batch",
  "fromAction",
  "fromActionPayload",
]);

export function snapshotChartEventPayload(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("Chart event payload must be an object");
  }
  const payload = value as object;
  const output: Record<string, unknown> = Object.create(null);
  for (const name of SAFE_EVENT_FIELDS) {
    const descriptor = Object.getOwnPropertyDescriptor(payload, name);
    if (!descriptor || !("value" in descriptor) || !descriptor.enumerable) continue;
    if (descriptor.value !== undefined) output[name] = descriptor.value;
  }
  return snapshotCallbackArgument(output, "event") as Record<string, unknown>;
}

export function snapshotEventQuery(value: unknown): ChartEventQuery | undefined {
  if (value === undefined) return undefined;
  if (typeof value === "string") {
    if (value.length === 0 || value.length > 128) throw new Error("Chart event query string is invalid");
    return value;
  }
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("Chart event query must be a string or record");
  }
  const snapshot = canonicalClone(value, "eventQuery") as Record<string, unknown>;
  const compatibleQuery: Record<string, string | number | boolean> = {};
  for (const [name, candidate] of Object.entries(snapshot)) {
    if (
      typeof candidate !== "string"
      && typeof candidate !== "boolean"
      && !(typeof candidate === "number" && Number.isFinite(candidate))
    ) {
      throw new Error(`Chart event query '${name}' must be a finite primitive`);
    }
    compatibleQuery[name] = candidate as string | number | boolean;
  }
  return Object.freeze(compatibleQuery) as ChartEventQuery;
}

export function assertEventName(value: unknown): ChartEventName {
  if (typeof value !== "string" || !SUPPORTED_CHART_EVENTS.has(value as ChartEventName)) {
    throw new Error(`Unsupported chart event '${String(value)}'`);
  }
  return value as ChartEventName;
}

export function eventMatchesQuery(
  payload: Record<string, unknown>,
  query: ChartEventQuery | undefined,
): boolean {
  if (query === undefined || typeof query === "string") {
    return query === undefined || payload.componentType === query || payload.seriesType === query;
  }
  return Object.entries(query).every(([name, value]) => payload[name] === value);
}
