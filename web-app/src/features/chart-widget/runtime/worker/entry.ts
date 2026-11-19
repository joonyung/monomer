import "ses";
import * as trustedECharts from "echarts";
import { transform as trustedSucraseTransform } from "sucrase";
import { CHART_FONT_LOAD_DEADLINE_MS, registerChartFont } from "./chart-font";

Object.defineProperty(globalThis, "global", {
  value: globalThis,
  writable: false,
  configurable: false,
});

lockdown({
  errorTaming: import.meta.env.DEV ? "unsafe-debug" : "safe",
});

if (
  trustedECharts.version !== "6.1.0"
  || typeof trustedECharts.init !== "function"
  || typeof trustedSucraseTransform !== "function"
) {
  throw new Error("ChartWidget Worker dependencies failed to initialize");
}

const pendingMessages: MessageEvent[] = [];
self.onmessage = (event) => pendingMessages.push(event);

// Text metrics are measured at first render, so the chart font must be ready before messages run.
// A missing FontFace API, a failed face, or a slow load falls back to the theme's system font stack.
const chartFontReady = registerChartFont(self, CHART_FONT_LOAD_DEADLINE_MS).catch(() => false);

void Promise.all([import("./runtime"), chartFontReady]).then(([{ startChartWidgetWorkerRuntime }]) => {
  startChartWidgetWorkerRuntime();
  const handleMessage = self.onmessage;
  for (const event of pendingMessages.splice(0)) handleMessage?.call(self, event);
}).catch((error: unknown) => {
  self.postMessage({
    type: "failed",
    generation: -1,
    phase: "initialize",
    message: error instanceof Error ? error.message : String(error),
  });
});
