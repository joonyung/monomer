import "ses";
import { transform } from "sucrase";
import type { ChartModule } from "../../contract/abi";
import { CHART_SOURCE_BYTE_LIMIT, byteLength } from "./canonical-boundary";
import type { Callable } from "./canonical-boundary";
import { createWidgetSourceIntl } from "@/features/widget-source/intl-boundary";

const FORBIDDEN_IMPORT = /\b(?:import\s*(?:\(|[\s{*])|require\s*\()/;
const RESERVED_EXPORT = /\b(?:const|let|var|function|class)\s+exports\b/;
const NAMED_EXPORT = /\bexport\s+(?!default\b)/;

function assertModuleShape(value: unknown): ChartModule {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("Chart source must return a defineChart object");
  }
  const module = value as Record<string, unknown>;
  const keys = Object.keys(module);
  if (keys.some((key) => key !== "setup" && key !== "render")) {
    throw new Error("Chart source may contain only setup and render");
  }
  if (module.setup !== undefined && typeof module.setup !== "function") {
    throw new Error("Chart setup must be a function");
  }
  if (typeof module.render !== "function") throw new Error("Chart render must be a function");
  return module as ChartModule;
}

export function lowerChartSource(source: string, sourceName = "chart-widget.ts"): string {
  if (typeof source !== "string" || source.length === 0) throw new Error("Chart source is empty");
  if (byteLength(source) > CHART_SOURCE_BYTE_LIMIT) {
    throw new Error("Chart source exceeds the byte budget");
  }
  if (FORBIDDEN_IMPORT.test(source)) throw new Error("Chart source cannot import or require modules");
  if (RESERVED_EXPORT.test(source)) throw new Error("Chart source cannot declare exports");
  if (NAMED_EXPORT.test(source)) throw new Error("Chart source must use only export default");
  const lowered = transform(source, {
    transforms: ["typescript", "imports"],
    filePath: sourceName,
    production: true,
  }).code;
  if (/\brequire\s*\(/.test(lowered)) throw new Error("Chart source lowering produced a module load");
  return lowered;
}

export function evaluateChartSource(
  source: string,
  sourceName = "chart-widget.ts",
  foreignFunctions: WeakSet<Callable> = new WeakSet(),
): ChartModule {
  let defineChartCallCount = 0;
  const defineChart = harden((candidate: unknown) => {
    defineChartCallCount += 1;
    if (defineChartCallCount > 1) throw new Error("defineChart may be called only once");
    return harden(assertModuleShape(candidate));
  });
  foreignFunctions.add(defineChart as Callable);
  const compartment = new Compartment({
    name: sourceName,
    globals: harden({
      defineChart,
      Intl: createWidgetSourceIntl(foreignFunctions),
      performance: undefined,
      crypto: undefined,
      fetch: undefined,
      XMLHttpRequest: undefined,
      document: undefined,
      window: undefined,
      console: undefined,
      setTimeout: undefined,
      setInterval: undefined,
      queueMicrotask: undefined,
    }),
    __options__: true,
  });
  harden(compartment.globalThis);
  const lowered = lowerChartSource(source, sourceName);
  const module = compartment.evaluate(`
    (function (exports) {
      ${lowered}
      return exports.default;
    }).call(undefined, Object.create(null));
    //# sourceURL=${sourceName}
  `);
  if (defineChartCallCount !== 1) throw new Error("Chart source must call defineChart exactly once");
  return assertModuleShape(module);
}

export function compartmentGlobalIsFrozen(): boolean {
  const compartment = new Compartment({ globals: {}, __options__: true });
  harden(compartment.globalThis);
  return Object.isFrozen(compartment.globalThis);
}
