import { performance } from "node:perf_hooks";
import { Buffer } from "node:buffer";
import process from "node:process";
import console from "node:console";
import { URL } from "node:url";
import { writeFileSync } from "node:fs";
import { capacityFrames } from "../src/pages/dev/chart-widget-runtime-fixture/capacity-fixtures.ts";
import { canonicalFrame, canonicalRenderOption, validateChartFrame } from "../src/features/chart-widget/runtime/worker/canonical-boundary.ts";

if (!globalThis.gc) throw new Error("Run node --expose-gc --experimental-strip-types scripts/measure-chart-capacity.mjs");
const measurements = [];
for (const [name, frame] of Object.entries(capacityFrames())) {
  globalThis.gc();
  const heapBefore = process.memoryUsage().heapUsed;
  const started = performance.now();
  try { validateChartFrame(frame); } catch (error) {
    measurements.push({ name, frameJsonBytes: Buffer.byteLength(JSON.stringify(frame)), rejected: error.message });
    continue;
  }
  const validated = performance.now();
  const transferred = globalThis.structuredClone(frame);
  const copied = performance.now();
  const workerFrame = canonicalFrame(transferred);
  const canonicalized = performance.now();
  const result = workerFrame.queries.sales;
  const option = { animation: false, xAxis: { type: "category" }, yAxis: { type: "value" }, series: [{ type: "bar", data: result.rows.map(row => Number(row[result.columns[0]?.name]) || 0) }] };
  const generated = performance.now();
  const safeOption = canonicalRenderOption(option);
  const finished = performance.now();
  const heapAfter = process.memoryUsage().heapUsed;
  measurements.push({ name, frameJsonBytes: Buffer.byteLength(JSON.stringify(frame)), optionJsonBytes: Buffer.byteLength(JSON.stringify(safeOption)),
    hostValidationMs: validated-started, structuredCloneMs: copied-validated, workerCanonicalMs: canonicalized-copied,
    optionGenerationMs: generated-canonicalized, optionValidationMs: finished-generated, heapDeltaBytes: heapAfter-heapBefore });
}
writeFileSync(new URL("../../screenshots/chart-c05-c06/node-capacity.json", import.meta.url), JSON.stringify({
  node: process.version, platform: process.platform, architecture: process.arch,
  note: "단일 실행의 순간 heap 차이. Node fixture 기준이며 Worker peak/GPU/decoded bitmap 메모리 또는 SLA가 아니다.", measurements,
}, null, 2));
console.log(JSON.stringify(measurements, null, 2));
