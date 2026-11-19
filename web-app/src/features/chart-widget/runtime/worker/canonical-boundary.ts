const RESERVED_NAMES = new Set(["__proto__", "prototype", "constructor"]);
const MAX_DEPTH = 32;
export const CHART_FRAME_NODE_LIMIT = 250_000;
export const CHART_FRAME_BYTE_LIMIT = 16 * 1024 * 1024;
export const CHART_OPTION_NODE_LIMIT = 250_000;
export const CHART_OPTION_BYTE_LIMIT = 8 * 1024 * 1024;
export const CHART_STRING_BYTE_LIMIT = 256 * 1024;
export const CHART_CALLBACK_NODE_LIMIT = 20_000;
export const CHART_CALLBACK_BYTE_LIMIT = 512 * 1024;
export const CHART_SOURCE_BYTE_LIMIT = 256 * 1024;
export const INLINE_RASTER_BYTE_LIMIT = 2 * 1024 * 1024;
export const INLINE_RASTER_STRING_BYTE_LIMIT = 4 * Math.ceil(INLINE_RASTER_BYTE_LIMIT / 3) + 31;

export type ChartValueBudget = {
  nodes: number;
  bytes: number;
  readonly nodeLimit: number;
  readonly byteLimit: number;
  readonly label: string;
  readonly stringByteLimit: number;
  readonly allowInlineRaster: boolean;
};

export function createChartValueBudget(
  label: string,
  byteLimit: number,
  nodeLimit: number,
  stringByteLimit = CHART_STRING_BYTE_LIMIT,
  allowInlineRaster = true,
): ChartValueBudget {
  return { nodes: 0, bytes: 0, label, byteLimit, nodeLimit, stringByteLimit, allowInlineRaster };
}

export function chargeChartValueBudget(state: ChartValueBudget, bytes: number, nodes = 1): void {
  state.nodes += nodes;
  state.bytes += bytes;
  if (state.nodes > state.nodeLimit) throw new Error(`${state.label} exceeds the node budget (${state.nodeLimit} nodes)`);
  if (state.bytes > state.byteLimit) throw new Error(`${state.label} exceeds the byte budget (${state.byteLimit} bytes)`);
}

export function chargeChartStringBudget(state: ChartValueBudget, value: string, stringLimit = state.stringByteLimit): void {
  if (value.length > stringLimit) throw new Error(`${state.label} string exceeds the byte budget (${stringLimit} bytes)`);
  chargeChartValueBudget(state, 3);
  let utf8Bytes = 0;
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    let bytes = code < 0x80 ? 1 : code < 0x800 ? 2 : 3;
    let escapedBytes = code < 0x20 ? 5 : code === 34 || code === 92 ? 1 : 0;
    if (code >= 0xd800 && code <= 0xdbff && value.charCodeAt(index + 1) >= 0xdc00 && value.charCodeAt(index + 1) <= 0xdfff) {
      bytes = 4;
      index += 1;
    } else if (code >= 0xd800 && code <= 0xdfff) {
      escapedBytes = 3;
    }
    utf8Bytes += bytes;
    if (utf8Bytes > stringLimit) throw new Error(`${state.label} string exceeds the byte budget (${stringLimit} bytes)`);
    chargeChartValueBudget(state, bytes + escapedBytes, 0);
  }
}

export type Callable = (...args: never[]) => unknown;

export type CanonicalFunctionHandler = (
  value: Callable,
  path: readonly string[],
) => Callable;

export function byteLength(value: string): number {
  let bytes = 0;
  for (const character of value) {
    const code = character.codePointAt(0)!;
    bytes += code < 0x80 ? 1 : code < 0x800 ? 2 : code < 0x10000 ? 3 : 4;
  }
  return bytes;
}

function imageByteLength(value: string): number | null {
  const imageValue = value.startsWith("image://") ? value.slice("image://".length) : value;
  const match = /^data:image\/(png|jpeg|webp);base64,([a-z0-9+/=]+)$/i.exec(imageValue);
  if (!match) return null;
  const payloadLength = match[2].replace(/=+$/, "").length;
  return Math.floor(payloadLength * 3 / 4);
}

type RecordPrototype = "null" | "object";

function copyValue(
  value: unknown,
  path: readonly string[],
  ancestors: WeakSet<object>,
  state: ChartValueBudget,
  recordPrototype: RecordPrototype | "validate",
  functionHandler?: CanonicalFunctionHandler,
): unknown {
  if (typeof value !== "string") chargeChartValueBudget(state,
    typeof value === "number" ? String(value).length + 1 : value !== null && typeof value === "object" ? 3 : 10);
  if (value === undefined || value === null || typeof value === "boolean") return value;
  if (typeof value === "function") {
    if (!functionHandler) throw new Error(`Functions are forbidden at ${path.join(".")}`);
    return functionHandler(value as Callable, path);
  }
  if (typeof value === "string") {
    const imageBytes = state.allowInlineRaster && value.length <= INLINE_RASTER_STRING_BYTE_LIMIT
      ? imageByteLength(value) : null;
    if (imageBytes !== null && imageBytes > INLINE_RASTER_BYTE_LIMIT) {
      throw new Error("Inline raster image exceeds the byte budget");
    }
    chargeChartStringBudget(state, value, imageBytes === null ? state.stringByteLimit : INLINE_RASTER_STRING_BYTE_LIMIT);
    return value;
  }
  if (typeof value === "number") {
    if (!Number.isFinite(value)) throw new Error(`Non-finite number at ${path.join(".")}`);
    return value;
  }
  if (typeof value !== "object") {
    throw new Error(`Unsupported value at ${path.join(".")}`);
  }
  if (ancestors.has(value)) throw new Error(`Cyclic value at ${path.join(".")}`);
  if (path.length > MAX_DEPTH) throw new Error("Chart value exceeds the depth budget");
  ancestors.add(value);
  try {
    if (Array.isArray(value)) {
      const length = value.length;
      if (!Number.isSafeInteger(length) || length > state.nodeLimit) {
        throw new Error(`Chart array exceeds the length budget at ${path.join(".")}`);
      }
      const output: unknown[] | undefined = recordPrototype === "validate" ? undefined : [];
      for (let index = 0; index < length; index += 1) {
        if (!Object.hasOwn(value, index)) throw new Error(`Sparse chart array at ${path.join(".")}`);
        const descriptor = Object.getOwnPropertyDescriptor(value, String(index));
        if (!descriptor || !("value" in descriptor)) throw new Error(`Chart accessors are forbidden at ${path.join(".")}`);
        const child = copyValue(descriptor.value, [...path, String(index)], ancestors, state, recordPrototype, functionHandler);
        output?.push(child);
      }
      return output;
    }
    const prototype = Object.getPrototypeOf(value);
    if (prototype !== Object.prototype && prototype !== null) {
      throw new Error(`Chart records must be plain objects at ${path.join(".")}`);
    }
    const output: Record<string, unknown> | undefined = recordPrototype === "validate" ? undefined : recordPrototype === "null" ? Object.create(null) : {};
    for (const name of Object.getOwnPropertyNames(value)) {
      if (RESERVED_NAMES.has(name)) throw new Error(`Reserved chart key '${name}' is forbidden`);
      const descriptor = Object.getOwnPropertyDescriptor(value, name);
      if (!descriptor || !("value" in descriptor) || !descriptor.enumerable) {
        throw new Error(`Chart accessors are forbidden at ${[...path, name].join(".")}`);
      }
      chargeChartStringBudget(state, name);
      chargeChartValueBudget(state, 1, 0);
      const child = copyValue(descriptor.value, [...path, name], ancestors, state, recordPrototype, functionHandler);
      if (output) output[name] = child;
    }
    return output;
  } finally {
    ancestors.delete(value);
  }
}

export function canonicalClone<T>(value: T, label: string, budget = createChartValueBudget("Chart value", CHART_FRAME_BYTE_LIMIT, CHART_FRAME_NODE_LIMIT)): T {
  const copy = copyValue(value, [label], new WeakSet(), budget, "null");
  return deepFreeze(copy) as T;
}

export function canonicalFrame<T>(value: T): T {
  const copy = copyValue(value, ["frame"], new WeakSet(),
    createChartValueBudget("Chart frame", CHART_FRAME_BYTE_LIMIT, CHART_FRAME_NODE_LIMIT, CHART_STRING_BYTE_LIMIT, false), "null");
  return deepFreeze(copy) as T;
}

export function validateChartFrame(value: unknown): void {
  copyValue(value, ["frame"], new WeakSet(),
    createChartValueBudget("Chart frame", CHART_FRAME_BYTE_LIMIT, CHART_FRAME_NODE_LIMIT, CHART_STRING_BYTE_LIMIT, false), "validate");
}

export function canonicalRenderOption<T>(value: T, functionHandler?: CanonicalFunctionHandler): T {
  if (value === null || typeof value !== "object" || Array.isArray(value)
    || ![null, Object.prototype].includes(Object.getPrototypeOf(value))
    || typeof (value as { then?: unknown }).then === "function") {
    throw new Error("render.option must be a non-null plain option object; async render is not supported");
  }
  return canonicalOption(value, functionHandler);
}

export function canonicalOption<T>(
  value: T,
  functionHandler?: CanonicalFunctionHandler,
  path: readonly string[] = ["option"],
  budget = createChartValueBudget("Chart option", CHART_OPTION_BYTE_LIMIT, CHART_OPTION_NODE_LIMIT),
): T {
  const copy = copyValue(value, path, new WeakSet(), budget, "object", functionHandler);
  validateOptionEffects(copy, path);
  // ECharts mutates options and custom graphics; source values remain detached by the copy.
  return copy as T;
}

function deepFreeze(value: unknown): unknown {
  if (value === null || typeof value !== "object" || Object.isFrozen(value)) return value;
  Object.freeze(value);
  for (const child of Object.values(value)) deepFreeze(child);
  return value;
}

function validateImage(value: unknown, path: readonly string[]): void {
  if (value === undefined || value === null) return;
  const bytes = typeof value === "string" ? imageByteLength(value) : null;
  if (bytes === null || bytes === 0) {
    throw new Error(`Only inline PNG, JPEG, and WebP resources are supported at ${path.join(".")}`);
  }
  if (bytes > INLINE_RASTER_BYTE_LIMIT) {
    throw new Error(`Inline raster image exceeds the byte budget at ${path.join(".")}`);
  }
}

function validateSymbol(value: unknown, path: readonly string[]): void {
  if (Array.isArray(value)) {
    value.forEach((symbol, index) => validateSymbol(symbol, [...path, String(index)]));
  } else if (typeof value === "string" && /^(?:image:\/\/|[a-z][a-z0-9+.-]*:|\/\/)/i.test(value)
    && !value.startsWith("path://")) {
    validateImage(value, path);
  }
}

function validateOptionEffects(value: unknown, path: readonly string[]): void {
  const property = path.at(-1) === "return" ? path.at(-2) : path.at(-1);
  if (property && /(?:symbol|icon)$/i.test(property)) validateSymbol(value, path);
  if (value === null || typeof value !== "object") return;
  if (Array.isArray(value)) {
    value.forEach((child, index) => validateOptionEffects(child, [...path, String(index)]));
    return;
  }
  const record = value as Record<string, unknown>;
  for (const [name, child] of Object.entries(record)) {
    const childPath = [...path, name];
    const location = childPath.join(".");
    // Dataset cells and ECharts data values are payload, not effect options.
    const isArrayItem = /^\d+$/.test(path.at(-1) ?? "");
    const isDataValue = name === "value" && isArrayItem && ["data", "children"].includes(path.at(-2) ?? "");
    const isDatasetSource = name === "source"
      && (path.at(-1) === "dataset" || (isArrayItem && path.at(-2) === "dataset"));
    if (isDataValue || isDatasetSource) continue;
    if (name === "image" && path.some((part) => /style$/i.test(part)
      || ["backgroundColor", "color", "fill", "stroke"].includes(part))) {
      validateImage(child, childPath);
    }
    if ((name === "link" || name === "sublink") && child != null && child !== ""
      && (path.includes("title") || path.includes("series"))) {
      throw new Error(`Navigation links are not supported at ${location}`);
    }
    if (name === "nodeClick" && child === "link") {
      throw new Error(`Navigation links are not supported at ${location}`);
    }
    if (path.includes("tooltip")) {
      if (name === "formatter" && child != null && typeof child !== "string" && typeof child !== "function") {
        throw new Error(`Only text tooltip formatters are supported at ${location}`);
      }
      if (name === "renderMode" && child !== "auto" && child !== "richText") {
        throw new Error(`Only canvas richText tooltips are supported at ${location}`);
      }
      if (["appendTo", "appendToBody", "className", "extraCssText"].includes(name) && child != null) {
        throw new Error(`DOM tooltip options are not supported at ${location}`);
      }
    }
    if (path.includes("toolbox") && path.includes("feature")
      && (name === "saveAsImage" || name === "dataView") && child != null) {
      throw new Error(`DOM toolbox features are not supported at ${location}`);
    }
    validateOptionEffects(child, childPath);
  }
}
