import "ses";
import { transform } from "sucrase";
import { createWidgetSourceIntl } from "@/features/widget-source/intl-boundary";
import type { TableModule } from "../../contract/abi";
import { TABLE_SOURCE_BYTES, byteLength } from "../limits";
import { ownRecord } from "../output-boundary";

export function lowerTableSource(source: string): string {
  if (typeof source !== "string" || !source.length || byteLength(source) > TABLE_SOURCE_BYTES) throw new Error("Table source exceeds its byte budget or is empty");
  if (/\b(?:import\s*(?:\(|[\s{*])|require\s*\()/.test(source)) throw new Error("Table source cannot import modules");
  if (/\b(?:const|let|var|function|class)\s+exports\b/.test(source)) throw new Error("Table source cannot declare exports");
  if (/\bexport\s+(?!default\b)/.test(source)) throw new Error("Table source must use only export default");
  const lowered = transform(source, { transforms: ["typescript", "imports"], filePath: "table-widget.ts", production: true }).code;
  if (/\brequire\s*\(/.test(lowered)) throw new Error("Table source cannot load modules");
  return lowered;
}

export function evaluateTableSource(source: string): TableModule {
  let defined: TableModule | undefined;
  const defineTable = harden((candidate: unknown) => {
    if (defined) throw new Error("defineTable may be called only once");
    const module = ownRecord(candidate, ["render"]);
    if (typeof module.render !== "function") throw new Error("Table render must be a function");
    defined = harden(module) as TableModule;
    return defined;
  });
  const compartment = new Compartment({
    name: "table-widget.ts",
    globals: harden({
      defineTable, Intl: createWidgetSourceIntl(new WeakSet()),
      performance: undefined, crypto: undefined,
      fetch: undefined, XMLHttpRequest: undefined, WebSocket: undefined,
      document: undefined, window: undefined, self: undefined, console: undefined,
      setTimeout: undefined, setInterval: undefined, queueMicrotask: undefined,
      Worker: undefined, navigator: undefined,
    }),
    __options__: true,
  });
  harden(compartment.globalThis);
  const result = compartment.evaluate(`(function(exports) { ${lowerTableSource(source)}; return exports.default; }).call(undefined, Object.create(null));`);
  if (!defined || result !== defined) throw new Error("Table source must export its defineTable module");
  return defined;
}
