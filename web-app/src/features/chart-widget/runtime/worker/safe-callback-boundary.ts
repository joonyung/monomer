import { canonicalClone, canonicalOption, createChartValueBudget, chargeChartValueBudget, chargeChartStringBudget, CHART_CALLBACK_BYTE_LIMIT, CHART_CALLBACK_NODE_LIMIT } from "./canonical-boundary";
import type { Callable, ChartValueBudget } from "./canonical-boundary";

const RESERVED_NAMES = new Set(["__proto__", "prototype", "constructor"]);
const MAX_CALLBACK_DEPTH = 24;
const OMIT = Symbol("omit");
const functionToString = Function.prototype.toString;
const asyncFunctionPrototype = Object.getPrototypeOf(async () => undefined);

function callbackBudget(label = "Chart callback input"): ChartValueBudget {
  return createChartValueBudget(label, CHART_CALLBACK_BYTE_LIMIT, CHART_CALLBACK_NODE_LIMIT, CHART_CALLBACK_BYTE_LIMIT);
}

function snapshotValue(
  value: unknown,
  path: readonly string[],
  ancestors: WeakSet<object>,
  state: ChartValueBudget,
): unknown | typeof OMIT {
  if (typeof value !== "string") chargeChartValueBudget(state,
    typeof value === "number" ? String(value).length + 1 : value !== null && typeof value === "object" ? 3 : 10);
  if (path.length > MAX_CALLBACK_DEPTH) {
    throw new Error("Chart callback input exceeds the safety budget");
  }
  if (value === undefined || value === null || typeof value === "boolean") return value;
  if (typeof value === "number") return Number.isFinite(value) ? value : OMIT;
  if (typeof value === "string") {
    chargeChartStringBudget(state, value);
    return value;
  }
  if (typeof value === "function" || typeof value === "symbol" || typeof value === "bigint") return OMIT;
  if (typeof value !== "object") return OMIT;
  if (ancestors.has(value)) return OMIT;
  ancestors.add(value);
  try {
    if (Array.isArray(value)) {
      const output: unknown[] = [];
      for (let index = 0; index < value.length; index += 1) {
        const descriptor = Object.getOwnPropertyDescriptor(value, String(index));
        if (!descriptor || !("value" in descriptor)) {
          chargeChartValueBudget(state, 5);
          output.push(null);
          continue;
        }
        const child = snapshotValue(descriptor.value, [...path, String(index)], ancestors, state);
        output.push(child === OMIT ? null : child);
      }
      return output;
    }
    const output: Record<string, unknown> = Object.create(null);
    for (const name of Object.getOwnPropertyNames(value)) {
      if (RESERVED_NAMES.has(name)) continue;
      const descriptor = Object.getOwnPropertyDescriptor(value, name);
      if (!descriptor || !("value" in descriptor) || !descriptor.enumerable) continue;
      chargeChartStringBudget(state, name);
      chargeChartValueBudget(state, 1, 0);
      const child = snapshotValue(descriptor.value, [...path, name], ancestors, state);
      if (child !== OMIT) output[name] = child;
    }
    return output;
  } finally {
    ancestors.delete(value);
  }
}

export function snapshotCallbackArgument(value: unknown, label = "callback", budget = callbackBudget()): unknown {
  const snapshot = snapshotValue(value, [label], new WeakSet(), budget);
  return deepFreeze(snapshot === OMIT ? undefined : snapshot);
}

function deepFreeze(value: unknown): unknown {
  if (value === null || typeof value !== "object" || Object.isFrozen(value)) return value;
  Object.freeze(value);
  for (const child of Object.values(value)) deepFreeze(child);
  return value;
}

function hostApiMethod(
  api: Record<string, unknown>,
  name: string,
  foreignFunctions: WeakSet<Callable>,
  budget: ChartValueBudget,
): Callable | undefined {
  const candidate = api[name];
  if (typeof candidate !== "function") return undefined;
  const method = (...args: unknown[]): unknown => {
    const safeArgs = canonicalClone(args, `renderItem.api.${name}`, budget);
    const result = Reflect.apply(candidate, api, safeArgs);
    return snapshotCallbackArgument(result, `renderItem.api.${name}.result`, budget);
  };
  foreignFunctions.add(method as Callable);
  return method as Callable;
}

export function createSafeRenderItemApi(
  value: unknown,
  foreignFunctions: WeakSet<Callable>,
  budget = callbackBudget(),
): Readonly<Record<string, unknown>> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("ECharts custom renderItem API is unavailable");
  }
  const api = value as Record<string, unknown>;
  chargeChartValueBudget(budget, 3);
  const output: Record<string, unknown> = Object.create(null);
  for (const name of [
    "coord",
    "size",
    "layout",
    "getWidth",
    "getHeight",
    "getDevicePixelRatio",
    "value",
    "ordinalRawValue",
    "style",
    "styleEmphasis",
    "visual",
    "barLayout",
    "currentSeriesIndices",
    "font",
  ]) {
    const method = hostApiMethod(api, name, foreignFunctions, budget);
    if (method) {
      chargeChartStringBudget(budget, name);
      chargeChartValueBudget(budget, 10);
      output[name] = method;
    }
  }
  return Object.freeze(output);
}

function isNativeFunction(value: Callable): boolean {
  try {
    return /\[native code\]/.test(functionToString.call(value));
  } catch {
    return true;
  }
}

function isPromiseLike(value: unknown): boolean {
  return value !== null
    && (typeof value === "object" || typeof value === "function")
    && typeof (value as { then?: unknown }).then === "function";
}

function callbackKind(path: readonly string[]): "renderItem" | "generic" {
  return path[path.length - 1] === "renderItem" ? "renderItem" : "generic";
}

export function createOptionFunctionHandler(
  foreignFunctions: WeakSet<Callable>,
  observeCallback: () => () => void = () => () => undefined,
): (value: Callable, path: readonly string[]) => Callable {
  const wrappers = new WeakMap<Callable, Map<string, Callable>>();
  return (value, path): Callable => {
    if (foreignFunctions.has(value) || isNativeFunction(value)) {
      throw new Error(`Only SES-created chart callbacks are allowed at ${path.join(".")}`);
    }
    if (Object.getPrototypeOf(value) === asyncFunctionPrototype) {
      throw new Error(`Async chart callbacks are not supported at ${path.join(".")}`);
    }
    const kind = callbackKind(path);
    let byPath = wrappers.get(value);
    if (!byPath) {
      byPath = new Map();
      wrappers.set(value, byPath);
    }
    const cacheKey = JSON.stringify(path);
    const cached = byPath.get(cacheKey);
    if (cached) return cached;
    const wrapper = (...hostArgs: unknown[]): unknown => {
      const complete = observeCallback();
      try {
        const inputBudget = callbackBudget();
        chargeChartValueBudget(inputBudget, 3);
        const safeArgs = hostArgs.map((argument, index) => {
          if (kind === "renderItem" && index === 1) {
            return createSafeRenderItemApi(argument, foreignFunctions, inputBudget);
          }
          return snapshotCallbackArgument(argument, `chartCallback.${kind}.${index}`, inputBudget);
        });
        const result = Reflect.apply(value, undefined, safeArgs);
        if (isPromiseLike(result)) throw new Error(`Async chart callbacks are not supported at ${path.join(".")}`);
        if (path.includes("tooltip") && path.at(-1) === "formatter"
          && result != null && typeof result !== "string") {
          throw new Error(`Only text tooltip callback results are supported at ${[...path, "return"].join(".")}`);
        }
        return canonicalOption(result, undefined, [...path, "return"], callbackBudget("Chart callback output"));
      } finally {
        complete();
      }
    };
    foreignFunctions.add(wrapper as Callable);
    byPath.set(cacheKey, wrapper as Callable);
    return wrapper as Callable;
  };
}
